package com.kaia.meshvpn

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Lifecycle of the `meshd` daemon.
 *
 * ## Where the binary is executed from (this is the fix)
 *
 * The Go binary ships inside the APK as `jniLibs/<abi>/libmeshd.so`, so the package
 * manager extracts it into `Context.applicationInfo.nativeLibraryDir`
 * (`/data/app/<pkg>/lib/<abi>/libmeshd.so`). That directory is the ONLY app-private
 * location where Android 10+ allows executing a file (no noexec, no W^X block).
 *
 * The binary is therefore executed **in place** — no copy to `filesDir`, no
 * `.../install` sub-directory (that is what produced the user-visible failure
 * `Cannot run program .../install`), no chmod dance: the package manager already
 * installed it with mode 0755.
 *
 * Resolution order (see [BinaryPaths], unit-tested on a plain JVM):
 *  1. `nativeLibraryDir/libmeshd.so` — executed in place (official method, what
 *     WireGuard / Tailscale / OpenVPN do).
 *  2. `codeCacheDir/meshd` — last-resort fallback only, if the APK was somehow built
 *     without jniLibs: copied from the asset `lib/<abi>/libmeshd.so` and forced 0755.
 *
 * If neither location yields a runnable binary, an explicit *readable* error is
 * returned: no raw path is ever shown to the user.
 */
class MeshDaemon(private val ctx: Context) {

    companion object {
        private const val TAG = "MeshDaemon"

        /** How many extraction attempts before giving up (corrupt copy retry). */
        private const val MAX_INSTALL_ATTEMPTS = 3

        /** Message shown to the user when no runnable binary can be produced. */
        const val ERR_BINARY_MISSING =
            "Could not start the tunnel — native binary missing, reinstall the app"
        const val ERR_BINARY_BROKEN =
            "Could not start the tunnel — native binary is corrupted, reinstall the app"
        const val ERR_ABI_UNSUPPORTED =
            "Could not start the tunnel — this device's CPU architecture is not supported by this build"
        const val ERR_DAEMON_START =
            "Could not start the tunnel — the mesh daemon failed to launch"
    }

    data class LaunchResult(val ok: Boolean, val pid: Int, val error: String?) {
        /** Short, path-free description of the method used (for logs / report). */
        var method: String = ""
            get() = field.ifBlank { "none" }
    }

    val socketPath: String get() = File(ctx.filesDir, "meshd.sock").absolutePath

    /** Primary exec location: the app's own native library directory. */
    private val nativeLibraryDir: String? get() = ctx.applicationInfo?.nativeLibraryDir

    private var process: Process? = null
    private var usedAbi: String? = null

    /** Path of the binary that is actually launched (for logs / UI). */
    var executablePath: String = ""
        private set

    /**
     * Picks the first ABI the device supports *and* the APK actually ships, instead of
     * blindly taking `Build.SUPPORTED_ABIS.first()`. On the user's LGE LM-G900TM this
     * resolves to `arm64-v8a` (the ABI of the shipped libmeshd.so).
     */
    private fun resolveAbi(): String? {
        val supported = runCatching { Build.SUPPORTED_ABIS?.toList().orEmpty() }.getOrDefault(emptyList())
        val shipped = shippedAbis()
        val picked = BinaryPaths.resolveAbi(supported, shipped)
        Log.i(TAG, "SUPPORTED_ABIS=${supported.joinToString(",")} shipped=${shipped.joinToString(",")} -> picked=$picked")
        usedAbi = picked
        return picked
    }

    /** ABIs for which this APK really contains a libmeshd.so (nativeLibraryDir or assets). */
    private fun shippedAbis(): Set<String> = runCatching {
        val fromNative = listOfNotNull(
            nativeLibraryDir?.takeIf { it.isNotBlank() }
                ?.let { File(it, BinaryPaths.LIB_NAME) }
                ?.takeIf { it.exists() }
                // nativeLibraryDir is .../lib/<abi> ; take the folder name.
                ?.let { File(nativeLibraryDir).name.takeIf { n -> n.isNotBlank() && n != "lib" } }
        )
        val fromAssets = runCatching {
            (ctx.assets.list("lib") ?: emptyArray())
                .filter { abi -> assetExists("lib/$abi/${BinaryPaths.LIB_NAME}") }
        }.getOrDefault(emptyList())
        (fromNative + fromAssets).toSet()
    }.getOrDefault(emptySet())

    private fun assetExists(path: String): Boolean = runCatching {
        ctx.assets.open(path).use { true }
    }.getOrDefault(false)

    /** True when [path] is inside a directory Android lets us exec from. */
    private fun isAllowed(path: String): Boolean = BinaryPaths.isAllowedExecPath(
        path,
        nativeLibraryDir,
        ctx.codeCacheDir?.absolutePath,
    )

    /**
     * Method 1 — the APK's own native library directory, executed in place.
     * Returns null when absent / not a valid ELF (then the caller may fall back).
     */
    private fun nativeLibraryBinary(): File? {
        val dir = nativeLibraryDir
        if (dir.isNullOrBlank()) {
            Log.w(TAG, "applicationInfo.nativeLibraryDir is empty")
            return null
        }
        val f = BinaryPaths.nativeLibBinary(dir) ?: return null
        if (!BinaryPaths.isAllowedExecPath(f.absolutePath, dir, ctx.codeCacheDir?.absolutePath)) {
            Log.e(TAG, "refusing a forbidden exec location: ${f.absolutePath}")
            return null
        }
        if (!f.exists()) {
            Log.w(TAG, "${BinaryPaths.LIB_NAME} absent from nativeLibraryDir"
                + " (files=${runCatching { File(dir).list()?.joinToString(",") }.getOrNull()})")
            return null
        }
        if (!BinaryGate.isUsableBin(f)) {
            Log.w(TAG, "${BinaryPaths.LIB_NAME} in nativeLibraryDir is not a usable ELF (len=${f.length()})")
            return null
        }
        if (!f.canExecute()) {
            // Should not happen (the package manager sets 0755), but recover anyway.
            Log.w(TAG, "${BinaryPaths.LIB_NAME} is not executable, forcing 0755")
            runCatching {
                f.setReadable(true, false)
                f.setExecutable(true, false)
            }
        }
        if (!f.canExecute()) {
            Log.w(TAG, "nativeLibraryDir binary not executable even after chmod -> fallback")
            return null
        }
        Log.i(TAG, "using nativeLibraryDir binary: ${f.absolutePath} (${f.length()} bytes, abi=$usedAbi)")
        return f
    }

    /**
     * Method 2 (last-resort fallback): extract the APK asset into `codeCacheDir` and
     * force 0755. Re-extracts whenever the copy on disk is missing, truncated or
     * corrupt — never re-launches a half-written file.
     */
    private fun codeCacheBinary(): Result<File> = runCatching {
        val target = BinaryPaths.fallbackBinary(ctx.codeCacheDir)
        if (BinaryGate.isUsableBin(target) && target.canExecute()) {
            Log.i(TAG, "reusing fallback binary in codeCacheDir")
            return@runCatching target
        }
        BinaryGate.prepareReinstall(target)

        val abi = usedAbi ?: resolveAbi()
        val inApk = BinaryPaths.assetPath(abi ?: "arm64-v8a")
        val stream = runCatching { ctx.assets.open(inApk) }.getOrElse { e ->
            throw IllegalStateException("${BinaryPaths.LIB_NAME} not found in APK assets ($inApk)", e)
        }

        ctx.codeCacheDir.mkdirs()
        stream.use { input ->
            target.outputStream().use { out ->
                input.copyTo(out)
                out.flush()
            }
        }

        val len = target.length()
        Log.i(TAG, "copied ${BinaryPaths.LIB_NAME} -> ${target.absolutePath} ($len bytes, abi=$abi)")
        if (!BinaryGate.isUsableBin(target)) {
            BinaryGate.prepareReinstall(target)
            throw IllegalStateException("extracted meshd is corrupt or truncated ($len bytes)")
        }

        // Explicit 0755: the copy inherits the umask (often 0644) otherwise.
        target.setReadable(true, false)
        target.setExecutable(true, false)
        if (!target.canExecute()) {
            BinaryGate.prepareReinstall(target)
            throw IllegalStateException("chmod 755 failed on fallback copy (canExecute=false)")
        }
        Log.i(TAG, "fallback meshd ready and executable (abi=$abi, $len bytes)")
        target
    }

    /**
     * Locates a runnable binary: nativeLibraryDir first (executed in place),
     * codeCacheDir fallback, with [MAX_INSTALL_ATTEMPTS] retries on a corrupt copy.
     */
    private fun resolveBinary(): Result<File> {
        resolveAbi()
        nativeLibraryBinary()?.let { return Result.success(it) }

        var lastError: Throwable? = null
        for (attempt in 1..MAX_INSTALL_ATTEMPTS) {
            val res = codeCacheBinary()
            if (res.isSuccess) {
                if (attempt > 1) Log.i(TAG, "fallback extraction succeeded on attempt $attempt")
                return res
            }
            lastError = res.exceptionOrNull()
            Log.w(TAG, "fallback extraction $attempt/$MAX_INSTALL_ATTEMPTS failed: ${lastError?.message}")
            BinaryGate.prepareReinstall(BinaryPaths.fallbackBinary(ctx.codeCacheDir))
        }
        return Result.failure(lastError ?: IllegalStateException("no meshd binary available"))
    }

    /** Starts the daemon if not already running; idempotent. */
    fun ensureRunning(): LaunchResult {
        process?.let {
            if (isAlive()) {
                Log.i(TAG, "meshd already running pid=${pidOf(it)}")
                return LaunchResult(true, pidOf(it), null)
            }
        }

        // --- 1. Locate a runnable binary -------------------------------------
        val exec: File = resolveBinary().getOrElse { e ->
            Log.e(TAG, "no runnable meshd binary", e)
            val msg = when {
                usedAbi == null -> ERR_ABI_UNSUPPORTED
                e.message?.contains("corrupt") == true || e.message?.contains("chmod") == true -> ERR_BINARY_BROKEN
                else -> ERR_BINARY_MISSING
            }
            return LaunchResult(false, 0, msg)
        }
        // (method resolved below, once the executable path is known)
        executablePath = exec.absolutePath
        val method = when {
            nativeLibraryDir?.isNotBlank() == true && exec.absolutePath.startsWith(nativeLibraryDir!!.trimEnd('/') + "/") ->
                BinaryPaths.NATIVE_LIB_DIR
            else -> BinaryPaths.FALLBACK_DIR_NAME
        }
        Log.i(TAG, "exec method=$method path=${exec.absolutePath}")

        // Last guard: never exec from filesDir or any .../install path again.
        if (!isAllowed(exec.absolutePath)) {
            Log.e(TAG, "blocked exec from a forbidden location: ${exec.absolutePath}")
            return LaunchResult(false, 0, ERR_BINARY_MISSING)
        }

        // Remove a stale socket left behind by a previous crashed run, otherwise
        // the daemon may refuse to bind and we would wait forever.
        runCatching {
            val stale = File(socketPath)
            if (stale.exists()) {
                Log.w(TAG, "removing stale socket ${stale.absolutePath}")
                stale.delete()
            }
        }

        // --- 2. Launch (binary executed in place from nativeLibraryDir) ------
        return try {
            val pb = ProcessBuilder(
                exec.absolutePath,
                "--socket", socketPath,
                "--vip", "10.42.0.1",
            )
                .directory(ctx.filesDir)
                .redirectErrorStream(true)
            Log.i(TAG, "exec ${exec.absolutePath} --socket $socketPath --vip 10.42.0.1 (abi=$usedAbi)")
            val p = pb.start()
            process = p

            // Wait for the daemon to create its socket (max ~5 s total).
            val sock = File(socketPath)
            for (i in 1..20) {
                if (sock.exists()) break
                Thread.sleep(250)
            }
            if (!p.isAlive) {
                val code = runCatching { p.exitValue() }.getOrDefault(-1)
                val out = runCatching {
                    p.inputStream.bufferedReader().readText().take(300)
                }.getOrDefault("")
                Log.e(TAG, "meshd exited immediately code=$code output=$out")
                return LaunchResult(false, 0, "$ERR_DAEMON_START (exit code $code)")
            }
            LaunchResult(true, pidOf(p), null).also { it.method = method }
        } catch (e: Exception) {
            // Never surface the raw path (e.g. "Cannot run program /data/user/0/...").
            Log.e(TAG, "start meshd failed (path=${exec.absolutePath})", e)
            LaunchResult(false, 0, ERR_DAEMON_START)
        }
    }

    fun isAlive(): Boolean = process?.let {
        try { it.isAlive } catch (_: Exception) { false }
    } ?: false

    fun stop() {
        process?.let { p ->
            runCatching { p.destroy() }
            runCatching { if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly() }
        }
        process = null
        runCatching { File(socketPath).delete() }
    }

    private fun pidOf(p: Process): Int = runCatching {
        val f = Process::class.java.getDeclaredField("pid").apply { isAccessible = true }
        (f.get(p) as? Int) ?: 0
    }.getOrDefault(0)
}
