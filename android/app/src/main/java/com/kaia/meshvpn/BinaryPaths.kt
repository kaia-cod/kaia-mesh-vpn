package com.kaia.meshvpn

import java.io.File

/**
 * Pure-JVM description of *where* the `meshd` binary is executed from, and of the
 * ABI selection rule. No `android.*` import: it is unit-tested on a plain JVM
 * (see `app/src/test/.../BinaryPathsTest.kt`).
 *
 * Policy (this is the fix for `Cannot run program .../install`):
 *  - the binary is executed from [NATIVE_LIB_DIR] (`applicationInfo.nativeLibraryDir`,
 *    i.e. `/data/app/<pkg>/lib/<abi>/libmeshd.so`), the only app-private directory
 *    where Android 10+ allows exec;
 *  - [FALLBACK_DIR_NAME] (`codeCacheDir`) is the single fallback, with an explicit
 *    chmod 0755 after the copy;
 *  - the app `filesDir` (and any `.../install` or `.../files/meshd` location) is
 *    FORBIDDEN as an exec location: noexec + W^X on Android 10+.
 */
object BinaryPaths {

    const val LIB_NAME = "libmeshd.so"
    const val BIN_NAME = "meshd"

    /** Symbolic name of the primary exec location (nativeLibraryDir). */
    const val NATIVE_LIB_DIR = "nativeLibraryDir"

    /** Symbolic name of the fallback exec location (codeCacheDir). */
    const val FALLBACK_DIR_NAME = "codeCacheDir"

    /** Exec locations in order of preference. */
    val EXEC_LOCATIONS = listOf(NATIVE_LIB_DIR, FALLBACK_DIR_NAME)

    /** Name of the forbidden legacy sub-directory. */
    const val FORBIDDEN_DIR = "install"

    /**
     * Picks the first ABI the device supports *and* the APK actually ships,
     * instead of blindly trusting `SUPPORTED_ABIS.first()`.
     */
    fun resolveAbi(supportedAbis: List<String>, shippedAbis: Set<String>): String? =
        supportedAbis.firstOrNull { it in shippedAbis } ?: supportedAbis.firstOrNull()

    /** `nativeLibraryDir/libmeshd.so`. */
    fun nativeLibBinary(nativeLibraryDir: String?): File? =
        nativeLibraryDir?.takeIf { it.isNotBlank() }?.let { File(it, LIB_NAME) }

    /** `codeCacheDir/meshd` (fallback). */
    fun fallbackBinary(codeCacheDir: File): File = File(codeCacheDir, BIN_NAME)

    /** Path inside the APK assets holding the packaged binary. */
    fun assetPath(abi: String): String = "lib/$abi/$LIB_NAME"

    /**
     * True when [path] may be executed. Used by tests and by the launch guard so a
     * forbidden location can never reach ProcessBuilder again.
     */
    fun isAllowedExecPath(path: String, nativeLibraryDir: String?, codeCacheDir: String?): Boolean {
        val dirs = listOfNotNull(
            nativeLibraryDir?.takeIf { it.isNotBlank() },
            codeCacheDir?.takeIf { it.isNotBlank() },
        )
        if (dirs.isEmpty()) return false
        return dirs.any { dir ->
            path == dir || path.startsWith(dir.trimEnd('/') + "/")
        }
    }

    /** True when [path] is a legacy forbidden location (filesDir/meshd, anything/install). */
    fun isForbiddenExecPath(path: String, filesDir: String?): Boolean {
        if (path.split('/').any { it == FORBIDDEN_DIR }) return true
        if (filesDir.isNullOrBlank()) return false
        val root = filesDir.trimEnd('/') + "/"
        // filesDir itself may only hold the socket, never the executable.
        return path.startsWith(root) && !path.startsWith(root + "code_cache/")
    }
}
