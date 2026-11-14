package com.kaia.meshvpn

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Cycle de vie du démon `meshd`.
 *
 * Le binaire Go est livré dans l'APK sous `lib/<abi>/libmeshd.so` (nommé `.so`
 * pour être extrait par le gestionnaire de paquets, `extractNativeLibs` actif).
 * Il n'est PAS un exécutable à cet emplacement : on le copie dans `filesDir`,
 * on le rend exécutable, puis on le lance comme processus natif.
 *
 * Rien n'est simulé ici : si le binaire manque ou refuse de démarrer, on
 * renvoie une erreur explicite que l'interface affiche telle quelle.
 */
class MeshDaemon(private val ctx: Context) {

    companion object {
        private const val TAG = "MeshDaemon"
        private const val BIN_NAME = "meshd"
        private const val LIB_NAME = "libmeshd.so"
    }

    data class LaunchResult(val ok: Boolean, val pid: Int, val error: String?)

    val socketPath: String get() = File(ctx.filesDir, "meshd.sock").absolutePath
    private val binary: File get() = File(ctx.filesDir, BIN_NAME)
    private var process: Process? = null

    /** Extrait le binaire de l'APK vers filesDir et le rend exécutable. */
    private fun installBinary(): Result<File> = runCatching {
        if (binary.exists() && binary.canExecute() && binary.length() > 0) return@runCatching binary

        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull()
            ?: throw IllegalStateException("aucune ABI supportée")
        val inApk = "lib/$abi/$LIB_NAME"
        var stream: java.io.InputStream? = ctx.applicationInfo.nativeLibraryDir?.let { dir ->
            File(dir, LIB_NAME).takeIf { it.exists() }?.inputStream()
        }
        if (stream == null) stream = ctx.assets.open(inApk)

        stream.use { input ->
            binary.outputStream().use { out -> input.copyTo(out) }
        }
        if (!binary.setExecutable(true, false)) {
            throw IllegalStateException("chmod +x impossible sur ${binary.absolutePath}")
        }
        Log.i(TAG, "meshd installé (${binary.length()} octets) depuis $abi")
        binary
    }

    /** Démarre le démon s'il ne tourne pas déjà ; idempotent. */
    fun ensureRunning(): LaunchResult {
        process?.let { p ->
            if (isAlive()) return LaunchResult(true, pidOf(p), null)
        }
        val bin = installBinary().getOrElse { e ->
            return LaunchResult(false, 0, "extraction de meshd impossible : ${e.message}")
        }
        return try {
            val sb = ProcessBuilder(
                bin.absolutePath,
                "--socket", socketPath,
                "--vip", "10.42.0.1",
            )
                .directory(ctx.filesDir)
                .redirectErrorStream(true)
            val p = sb.start()
            process = p
            // Laisse au démon le temps de créer sa socket (max ~2,5 s).
            repeat(10) {
                if (File(socketPath).exists()) return@repeat
                Thread.sleep(250)
            }
            LaunchResult(true, pidOf(p), null)
        } catch (e: Exception) {
            Log.e(TAG, "start meshd failed", e)
            LaunchResult(false, 0, "démarrage de meshd impossible : ${e.message}")
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
