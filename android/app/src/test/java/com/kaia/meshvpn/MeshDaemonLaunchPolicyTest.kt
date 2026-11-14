package com.kaia.meshvpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression test for the user-reported startup failure:
 *
 *   "Cannot run program /data/user/0/com.kaya.kaia.s/install ..."
 *
 * `MeshDaemon` cannot be instantiated on a plain JVM (it needs `android.content.Context`),
 * so this test reproduces the *decision* it takes, step by step, with real files on
 * disk and the very same helpers ([BinaryPaths] + [BinaryGate]) it calls on device.
 *
 * It proves the two things that were broken:
 *  1. when `nativeLibraryDir/libmeshd.so` exists and is a valid ELF, it is selected
 *     and executed **in place** — no copy, no chmod, no `filesDir`, no `.../install`;
 *  2. when it is missing, the only fallback is `codeCacheDir/meshd` and the
 *     `filesDir` location is rejected, so the old failing path can never come back.
 */
class MeshDaemonLaunchPolicyTest {

    /** Mimics a real APK install path (note: `lib/<abi>`, not `install`). */
    private fun fakeNativeDir(root: File): File =
        File(root, "app/~~abc123==/com.kaia.meshvpn-XYZ==/lib/arm64").apply { mkdirs() }

    private fun fakeCodeCache(root: File): File = File(root, "user/0/com.kaia.meshvpn/code_cache").apply { mkdirs() }
    private fun fakeFilesDir(root: File): File = File(root, "user/0/com.kaia.meshvpn/files").apply { mkdirs() }

    /** Writes a file that passes [BinaryGate] (ELF magic + plausible size). */
    private fun writeFakeElf(target: File) {
        target.parentFile?.mkdirs()
        val header = BinaryGate.ELF_MAGIC + ByteArray(64)
        target.outputStream().use { out ->
            out.write(header)
            out.write(ByteArray(BinaryGate.MIN_BINARY_BYTES.toInt() - header.size))
        }
    }

    /**
     * The selection logic of `MeshDaemon.resolveBinary()` / `nativeLibraryBinary()`,
     * expressed against real files.
     */
    private fun selectExecutable(nativeDir: String, codeCacheDir: File, filesDir: File): File? {
        // 1. nativeLibraryDir, executed in place.
        BinaryPaths.nativeLibBinary(nativeDir)
            ?.takeIf { BinaryPaths.isAllowedExecPath(it.absolutePath, nativeDir, codeCacheDir.absolutePath) }
            ?.takeIf { it.exists() && BinaryGate.isUsableBin(it) && it.canExecute() }
            ?.let { return it }

        // 2. codeCacheDir fallback only.
        val fallback = BinaryPaths.fallbackBinary(codeCacheDir)
        if (BinaryPaths.isAllowedExecPath(fallback.absolutePath, nativeDir, codeCacheDir.absolutePath) &&
            fallback.exists() && BinaryGate.isUsableBin(fallback) && fallback.canExecute()
        ) {
            return fallback
        }

        // 3. filesDir / .../install is NEVER returned.
        val legacy = File(filesDir, "install/meshd")
        check(!BinaryPaths.isForbiddenExecPath(legacy.absolutePath, filesDir.absolutePath))
        return null
    }

    @Test
    fun `binary is executed in place from nativeLibraryDir`() {
        val root = createTempDir("mesh-launch")
        val nativeDir = fakeNativeDir(root)
        val codeCache = fakeCodeCache(root)
        val filesDir = fakeFilesDir(root)

        val lib = File(nativeDir, BinaryPaths.LIB_NAME)
        writeFakeElf(lib)
        assertTrue("the fake lib must be executable", lib.setExecutable(true, false))

        val selected = selectExecutable(nativeDir.absolutePath, codeCache, filesDir)

        assertNotNull("a runnable binary must be found", selected)
        assertEquals(lib.absolutePath, selected!!.absolutePath)
        // Executed in place: not copied anywhere.
        assertFalse(File(codeCache, BinaryPaths.BIN_NAME).exists())
        assertFalse(File(filesDir, BinaryPaths.BIN_NAME).exists())
        assertFalse(File(filesDir, "install").exists())
        // And it is a location Android 10+ actually allows.
        assertTrue(BinaryPaths.isAllowedExecPath(selected.absolutePath, nativeDir.absolutePath, codeCache.absolutePath))
    }

    @Test
    fun `fallback goes to codeCacheDir and never to filesDir`() {
        val root = createTempDir("mesh-launch-fallback")
        val nativeDir = fakeNativeDir(root) // empty: no libmeshd.so
        val codeCache = fakeCodeCache(root)
        val filesDir = fakeFilesDir(root)

        // The legacy layout from the bug report exists on disk...
        writeFakeElf(File(filesDir, "install/meshd"))

        // ...and must be ignored until a valid fallback exists.
        assertNull("filesDir/install must never be selected", selectExecutable(nativeDir.absolutePath, codeCache, filesDir))

        val fallback = File(codeCache, BinaryPaths.BIN_NAME)
        writeFakeElf(fallback)
        assertTrue(fallback.setExecutable(true, false))

        val selected = selectExecutable(nativeDir.absolutePath, codeCache, filesDir)
        assertEquals(fallback.absolutePath, selected?.absolutePath)
    }

    @Test
    fun `the exact path from the bug report is rejected`() {
        val root = createTempDir("mesh-launch-bugreport")
        val nativeDir = fakeNativeDir(root)
        val codeCache = fakeCodeCache(root)
        val filesDir = fakeFilesDir(root)

        val reported = "/data/user/0/com.kaya.kaia.s/install/meshd"
        assertTrue(BinaryPaths.isForbiddenExecPath(reported, "/data/user/0/com.kaya.kaia.s/files"))
        assertTrue(BinaryPaths.isForbiddenExecPath("$filesDir/install/meshd", filesDir.absolutePath))
        assertFalse(
            "a filesDir exec location must not be allowed",
            BinaryPaths.isAllowedExecPath("$filesDir/install/meshd", nativeDir.absolutePath, codeCache.absolutePath),
        )
        // No valid binary anywhere -> no selection, and the user gets a clean message.
        assertNull(selectExecutable(nativeDir.absolutePath, codeCache, filesDir))
        assertFalse(MeshDaemon.ERR_BINARY_MISSING.contains("/data/"))
    }

    @Test
    fun `a truncated copy is refused by the gate`() {
        val root = createTempDir("mesh-launch-corrupt")
        val nativeDir = fakeNativeDir(root)
        val codeCache = fakeCodeCache(root)
        val filesDir = fakeFilesDir(root)

        // Too small to be the Go binary.
        File(nativeDir, BinaryPaths.LIB_NAME).writeBytes(BinaryGate.ELF_MAGIC)
        assertNull(selectExecutable(nativeDir.absolutePath, codeCache, filesDir))

        // Right size but not an ELF.
        val notElf = File(codeCache, BinaryPaths.BIN_NAME)
        notElf.writeBytes(ByteArray(BinaryGate.MIN_BINARY_BYTES.toInt() + 16))
        assertTrue(notElf.setExecutable(true, false))
        assertNull(selectExecutable(nativeDir.absolutePath, codeCache, filesDir))
    }

    private fun createTempDir(prefix: String): File =
        File.createTempFile(prefix, "").let { f ->
            f.delete()
            f.mkdirs()
            f
        }
}
