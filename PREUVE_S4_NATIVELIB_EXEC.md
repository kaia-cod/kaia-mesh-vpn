# S4 — Preuve : exécution effective depuis nativeLibraryDir/libmeshd.so
# date: 2026-11-15T02:13:47Z  commit: fff770c

## A) grep nativeLibraryDir (code d execution)
android/app/src/main/java/com/kaia/meshvpn/BinaryPaths.kt:11: *  - the binary is executed from [NATIVE_LIB_DIR] (`applicationInfo.nativeLibraryDir`,
android/app/src/main/java/com/kaia/meshvpn/BinaryPaths.kt:24:    /** Symbolic name of the primary exec location (nativeLibraryDir). */
android/app/src/main/java/com/kaia/meshvpn/BinaryPaths.kt:25:    const val NATIVE_LIB_DIR = "nativeLibraryDir"
android/app/src/main/java/com/kaia/meshvpn/BinaryPaths.kt:43:    /** `nativeLibraryDir/libmeshd.so`. */
android/app/src/main/java/com/kaia/meshvpn/BinaryPaths.kt:44:    fun nativeLibBinary(nativeLibraryDir: String?): File? =
android/app/src/main/java/com/kaia/meshvpn/BinaryPaths.kt:45:        nativeLibraryDir?.takeIf { it.isNotBlank() }?.let { File(it, LIB_NAME) }
android/app/src/main/java/com/kaia/meshvpn/BinaryPaths.kt:57:    fun isAllowedExecPath(path: String, nativeLibraryDir: String?, codeCacheDir: String?): Boolean {
android/app/src/main/java/com/kaia/meshvpn/BinaryPaths.kt:59:            nativeLibraryDir?.takeIf { it.isNotBlank() },
android/app/src/main/java/com/kaia/meshvpn/MeshDaemon.kt:14: * manager extracts it into `Context.applicationInfo.nativeLibraryDir`
android/app/src/main/java/com/kaia/meshvpn/MeshDaemon.kt:24: *  1. `nativeLibraryDir/libmeshd.so` — executed in place (official method, what
android/app/src/main/java/com/kaia/meshvpn/MeshDaemon.kt:60:    private val nativeLibraryDir: String? get() = ctx.applicationInfo?.nativeLibraryDir
android/app/src/main/java/com/kaia/meshvpn/MeshDaemon.kt:66:     * Why the primary location (nativeLibraryDir) was rejected, if it was. Logged
android/app/src/main/java/com/kaia/meshvpn/MeshDaemon.kt:90:    /** ABIs for which this APK really contains a libmeshd.so (nativeLibraryDir or assets). */
android/app/src/main/java/com/kaia/meshvpn/MeshDaemon.kt:93:            nativeLibraryDir?.takeIf { it.isNotBlank() }
android/app/src/main/java/com/kaia/meshvpn/MeshDaemon.kt:96:                // nativeLibraryDir is .../lib/<abi> ; take the folder name.
android/app/src/main/java/com/kaia/meshvpn/MeshDaemon.kt:97:                ?.let { File(nativeLibraryDir).name.takeIf { n -> n.isNotBlank() && n != "lib" } }
android/app/src/main/java/com/kaia/meshvpn/MeshDaemon.kt:113:        nativeLibraryDir,
android/app/src/main/java/com/kaia/meshvpn/MeshDaemon.kt:122:        val dir = nativeLibraryDir
android/app/src/main/java/com/kaia/meshvpn/MeshDaemon.kt:124:            fallbackReason = "applicationInfo.nativeLibraryDir is empty"
android/app/src/main/java/com/kaia/meshvpn/MeshDaemon.kt:129:            fallbackReason = "nativeLibraryDir path could not be built"
android/app/src/main/java/com/kaia/meshvpn/MeshDaemon.kt:139:            fallbackReason = "${BinaryPaths.LIB_NAME} absent from nativeLibraryDir"
android/app/src/main/java/com/kaia/meshvpn/MeshDaemon.kt:144:            fallbackReason = "${BinaryPaths.LIB_NAME} in nativeLibraryDir is not a usable ELF (len=${f.length()})"
android/app/src/main/java/com/kaia/meshvpn/MeshDaemon.kt:157:            Log.w(TAG, "nativeLibraryDir binary not executable even after chmod -> fallback")
android/app/src/main/java/com/kaia/meshvpn/MeshDaemon.kt:160:        Log.i(TAG, "using nativeLibraryDir binary: ${f.absolutePath} (${f.length()} bytes, abi=$usedAbi)")
android/app/src/main/java/com/kaia/meshvpn/MeshDaemon.kt:210:     * Locates a runnable binary: nativeLibraryDir first (executed in place),
android/app/src/main/java/com/kaia/meshvpn/MeshDaemon.kt:217:            Log.i(TAG, "exec decision: nativeLibraryDir (${it.absolutePath})")
android/app/src/main/java/com/kaia/meshvpn/MeshDaemon.kt:258:            nativeLibraryDir?.isNotBlank() == true && exec.absolutePath.startsWith(nativeLibraryDir!!.trimEnd('/') + "/") ->
android/app/src/main/java/com/kaia/meshvpn/MeshDaemon.kt:265:        // --- 1b. Runtime assertion: the launched file IS nativeLibraryDir/libmeshd.so
android/app/src/main/java/com/kaia/meshvpn/MeshDaemon.kt:269:        val expected: File? = BinaryPaths.nativeLibBinary(nativeLibraryDir)
android/app/src/main/java/com/kaia/meshvpn/MeshDaemon.kt:275:                    "ASSERTION FAILED: exec path=${exec.absolutePath} != expected nativeLibraryDir=$expectedPath" +
android/app/src/main/java/com/kaia/meshvpn/MeshDaemon.kt:280:            Log.i(TAG, "ASSERTION OK: exec path == File(applicationInfo.nativeLibraryDir, \"${BinaryPaths.LIB_NAME}\")")
android/app/src/main/java/com/kaia/meshvpn/MeshDaemon.kt:284:                "ASSERTION SKIPPED: ${BinaryPaths.LIB_NAME} absent from nativeLibraryDir, running fallback (${exec.absolutePath})",
android/app/src/main/java/com/kaia/meshvpn/MeshDaemon.kt:304:        // --- 2. Launch (binary executed in place from nativeLibraryDir) ------
android/app/src/test/java/com/kaia/meshvpn/BinaryPathsTest.kt:14: *  - the binary is resolved from nativeLibraryDir first, codeCacheDir second,
android/app/src/test/java/com/kaia/meshvpn/BinaryPathsTest.kt:25:    fun `native library binary is libmeshd_so inside nativeLibraryDir`() {
android/app/src/test/java/com/kaia/meshvpn/BinaryPathsTest.kt:31:    fun `native library binary is null when nativeLibraryDir is missing`() {
android/app/src/test/java/com/kaia/meshvpn/BinaryPathsTest.kt:42:    fun `nativeLibraryDir is an allowed exec location`() {
android/app/src/test/java/com/kaia/meshvpn/MeshDaemonLaunchPolicyTest.kt:21: *  1. when `nativeLibraryDir/libmeshd.so` exists and is a valid ELF, it is selected
android/app/src/test/java/com/kaia/meshvpn/MeshDaemonLaunchPolicyTest.kt:50:        // 1. nativeLibraryDir, executed in place.
android/app/src/test/java/com/kaia/meshvpn/MeshDaemonLaunchPolicyTest.kt:71:    fun `binary is executed in place from nativeLibraryDir`() {

## B) Ligne d execution effective — MeshDaemon.kt:304-314
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

## C) Grep filesDir+meshd dans le CODE (hors tests/commentaires)
--- occurrences de filesDir dans app/src/main (code reel) ---
android/app/src/main/java/com/kaia/meshvpn/BinaryPaths.kt:16: *  - the app `filesDir` (and any `.../install` or `.../files/meshd` location) is
android/app/src/main/java/com/kaia/meshvpn/BinaryPaths.kt:68:    /** True when [path] is a legacy forbidden location (filesDir/meshd, anything/install). */
android/app/src/main/java/com/kaia/meshvpn/BinaryPaths.kt:69:    fun isForbiddenExecPath(path: String, filesDir: String?): Boolean {
android/app/src/main/java/com/kaia/meshvpn/BinaryPaths.kt:71:        if (filesDir.isNullOrBlank()) return false
android/app/src/main/java/com/kaia/meshvpn/BinaryPaths.kt:72:        val root = filesDir.trimEnd('/') + "/"
android/app/src/main/java/com/kaia/meshvpn/BinaryPaths.kt:73:        // filesDir itself may only hold the socket, never the executable.
android/app/src/main/java/com/kaia/meshvpn/MeshDaemon.kt:18: * The binary is therefore executed **in place** — no copy to `filesDir`, no
android/app/src/main/java/com/kaia/meshvpn/MeshDaemon.kt:57:    val socketPath: String get() = File(ctx.filesDir, "meshd.sock").absolutePath
android/app/src/main/java/com/kaia/meshvpn/MeshDaemon.kt:288:        // Last guard: never exec from filesDir or any .../install path again.
android/app/src/main/java/com/kaia/meshvpn/MeshDaemon.kt:311:                .directory(ctx.filesDir)
--- aucune construction de fichier executable depuis filesDir :
android/app/src/main/java/com/kaia/meshvpn/BinaryPaths.kt:16: *  - the app `filesDir` (and any `.../install` or `.../files/meshd` location) is
android/app/src/main/java/com/kaia/meshvpn/BinaryPaths.kt:68:    /** True when [path] is a legacy forbidden location (filesDir/meshd, anything/install). */
android/app/src/main/java/com/kaia/meshvpn/MeshDaemon.kt:288:        // Last guard: never exec from filesDir or any .../install path again.

## D) Grep /install dans le code (hors commentaires/tests)
ZERO HIT pour "/install"

## E) jniLibs + build config
-rw-r--r-- 1 ubuntu ubuntu 26870081 Nov 14 20:45 android/app/src/main/jniLibs/arm64-v8a/libmeshd.so
android/app/src/main/jniLibs/arm64-v8a/libmeshd.so: ELF 64-bit LSB pie executable, ARM aarch64, version 1 (SYSV), dynamically linked, interpreter /system/bin/linker64, Go BuildID=PN2yAoYkaYjz86aPBL4h/7C9S2ECOIAPOTM7DPYE0/-dVYzE6EDVvR6ReG9BIS/9h-AWBk-Wuf21ca0W4YU, stripped
17:        ndk { abiFilters 'arm64-v8a' }
45:            useLegacyPackaging = true
46:            doNotStrip "**/libmeshd.so"
13:        android:extractNativeLibs="true"

## F) Preuve dynamique (java source-launcher)
== S4 — proof: exec from nativeLibraryDir/libmeshd.so ==
repo            : /home/ubuntu/SAM-G-Agent/workspace/kaia-mesh-vpn
shipped binary  : /home/ubuntu/SAM-G-Agent/workspace/kaia-mesh-vpn/android/app/src/main/jniLibs/arm64-v8a/libmeshd.so  (26870081 bytes, exists=true)
precondition: shipped libmeshd.so exists and is a usable ELF             PASS   len=26870081
nativeLibraryDir: /tmp/s4-sandbox-3604123761716831194/data/app/com.kaia.meshvpn-1/lib/arm64-v8a
codeCacheDir    : /tmp/s4-sandbox-3604123761716831194/data/user/0/com.kaia.meshvpn/code_cache
filesDir        : /tmp/s4-sandbox-3604123761716831194/data/user/0/com.kaia.meshvpn/files
legacy (broken) : /tmp/s4-sandbox-3604123761716831194/data/user/0/com.kaia.meshvpn/install/meshd
1. resolveBinary() selects a binary                                      PASS   /tmp/s4-sandbox-3604123761716831194/data/app/com.kaia.meshvpn-1/lib/arm64-v8a/libmeshd.so
2. exec path == nativeLibraryDir/libmeshd.so (absolutePath)              PASS   /tmp/s4-sandbox-3604123761716831194/data/app/com.kaia.meshvpn-1/lib/arm64-v8a/libmeshd.so
3. exec path is NOT the legacy .../install/meshd                         PASS   /tmp/s4-sandbox-3604123761716831194/data/user/0/com.kaia.meshvpn/install/meshd
4. exec path is allowed (nativeLibraryDir|codeCacheDir)                  PASS   
5. filesDir/install/meshd is FORBIDDEN                                   PASS   
6. ProcessBuilder started libmeshd.so (path-shaped, not PERMISSION denied) PASS   exit=2 out[0]=/tmp/s4-sandbox-3604123761716831194/data/app/com.kaia.meshvpn-1/lib/arm64-v8a/libmeshd.so: 1: ELF�A@p@8: not found
7. resolveAbi(device=[arm64-v8a, armeabi-v7a], shipped={arm64-v8a}) == arm64-v8a PASS   arm64-v8a
8. with empty nativeLibraryDir -> fallback codeCacheDir/meshd (chmod 755) PASS   libmeshd.so absent/invalid in nativeLibraryDir -> /tmp/s4-sandbox-3604123761716831194/data/user/0/com.kaia.meshvpn/code_cache/meshd
9. host ELF executed from nativeLibraryDir (exit 0, no Permission denied) PASS   exit=0 out='' path=/tmp/s4-sandbox-3604123761716831194/data/app/com.kaia.meshvpn-1/lib/arm64-v8a/libmeshd.so.host
10. filesDir/meshd is refused by the guard (the original bug cannot come back) PASS   /tmp/s4-sandbox-3604123761716831194/data/user/0/com.kaia.meshvpn/files/meshd

RESULT: 11 passed / 0 failed
LAUNCH_OUTPUT_FIRST_120_CHARS: /tmp/s4-sandbox-3604123761716831194/data/app/com.kaia.meshvpn-1/lib/arm64-v8a/libmeshd.so: 1: ELF�A@p@8: not fou
