package com.kaia.meshvpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [BinaryPaths] — the exec-location policy that fixes
 * `Cannot run program .../install`.
 *
 * They prove, without a device:
 *  - the binary is resolved from nativeLibraryDir first, codeCacheDir second,
 *  - no forbidden location (filesDir, .../install) is ever accepted,
 *  - the ABI rule picks a shipped ABI rather than the first supported one.
 */
class BinaryPathsTest {

    private val nativeDir = "/data/app/~~abc==/com.kaia.meshvpn-XYZ==/lib/arm64"
    private val codeCache = "/data/user/0/com.kaia.meshvpn/code_cache"
    private val filesDir = "/data/user/0/com.kaia.meshvpn/files"

    @Test
    fun `native library binary is libmeshd_so inside nativeLibraryDir`() {
        val f = BinaryPaths.nativeLibBinary(nativeDir)
        assertEquals("$nativeDir/libmeshd.so", f?.absolutePath)
    }

    @Test
    fun `native library binary is null when nativeLibraryDir is missing`() {
        assertNull(BinaryPaths.nativeLibBinary(null))
        assertNull(BinaryPaths.nativeLibBinary(""))
    }

    @Test
    fun `fallback binary lives in codeCacheDir`() {
        assertEquals("$codeCache/meshd", BinaryPaths.fallbackBinary(java.io.File(codeCache)).absolutePath)
    }

    @Test
    fun `nativeLibraryDir is an allowed exec location`() {
        assertTrue(BinaryPaths.isAllowedExecPath("$nativeDir/libmeshd.so", nativeDir, codeCache))
    }

    @Test
    fun `codeCacheDir is an allowed exec location`() {
        assertTrue(BinaryPaths.isAllowedExecPath("$codeCache/meshd", nativeDir, codeCache))
    }

    @Test
    fun `filesDir and install paths are forbidden exec locations`() {
        assertFalse(BinaryPaths.isAllowedExecPath("$filesDir/meshd", nativeDir, codeCache))
        assertFalse(BinaryPaths.isAllowedExecPath("$filesDir/install/meshd", nativeDir, codeCache))
        assertTrue(BinaryPaths.isForbiddenExecPath("$filesDir/install/meshd", filesDir))
        assertTrue(BinaryPaths.isForbiddenExecPath("/data/user/0/com.kaia.meshvpn/install/meshd", filesDir))
        assertTrue(BinaryPaths.isForbiddenExecPath("$filesDir/meshd", filesDir))
    }

    @Test
    fun `asset path matches the packaged layout`() {
        assertEquals("lib/arm64-v8a/libmeshd.so", BinaryPaths.assetPath("arm64-v8a"))
    }

    @Test
    fun `prefers a shipped abi over the first supported one`() {
        assertEquals(
            "arm64-v8a",
            BinaryPaths.resolveAbi(listOf("x86_64", "arm64-v8a"), setOf("arm64-v8a")),
        )
        assertEquals("arm64-v8a", BinaryPaths.resolveAbi(listOf("arm64-v8a"), setOf("arm64-v8a")))
        assertNull(BinaryPaths.resolveAbi(emptyList(), setOf("arm64-v8a")))
    }

    @Test
    fun `user facing messages never leak a path nor the mesh D typo`() {
        val messages = listOf(
            MeshDaemon.ERR_BINARY_MISSING,
            MeshDaemon.ERR_BINARY_BROKEN,
            MeshDaemon.ERR_ABI_UNSUPPORTED,
            MeshDaemon.ERR_DAEMON_START,
        )
        messages.forEach { m ->
            assertFalse("leaks a path: $m", m.contains("/data/"))
            assertFalse("leaks a path: $m", m.contains("/install"))
            assertFalse("typo 'mesh D': $m", m.contains("mesh D"))
            assertTrue("not user friendly: $m", m.startsWith("Could not start the tunnel"))
        }
    }
}
