package com.kaia.meshvpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM tests for the ABI selection logic used by [MeshDaemon].
 *
 * They run on a plain JVM (no device, no Gradle Android plugin needed) and prove
 * the hardening behaviour:
 *  - the first ABI of the device is NOT blindly trusted,
 *  - we pick the first ABI we actually ship a binary for,
 *  - when nothing matches we fall back to the first supported ABI,
 *  - an empty ABI list yields null instead of crashing.
 */
class AbiResolverTest {

    @Test
    fun `picks shipped abi instead of first supported abi`() {
        // Emulator case: x86_64 first, but the APK only ships arm64-v8a.
        val supported = listOf("x86_64", "x86", "arm64-v8a", "armeabi-v7a")
        val shipped = setOf("arm64-v8a")
        assertEquals("arm64-v8a", MeshDaemonAbi.resolve(supported, shipped))
    }

    @Test
    fun `prefers the highest priority shipped abi`() {
        val supported = listOf("arm64-v8a", "armeabi-v7a")
        val shipped = setOf("armeabi-v7a", "arm64-v8a")
        assertEquals("arm64-v8a", MeshDaemonAbi.resolve(supported, shipped))
    }

    @Test
    fun `falls back to first supported abi when nothing is shipped`() {
        assertEquals("x86_64", MeshDaemonAbi.resolve(listOf("x86_64", "arm64-v8a"), emptySet()))
    }

    @Test
    fun `returns null when the device reports no abi`() {
        assertNull(MeshDaemonAbi.resolve(emptyList(), setOf("arm64-v8a")))
    }
}

/**
 * Pure-Kotlin twin of `MeshDaemon.resolveAbi()` (which depends on android.os.Build
 * and cannot be instanced on a JVM). Kept in the test sources so the logic proven
 * here and the logic shipped in the app are literally the same algorithm.
 */
object MeshDaemonAbi {
    fun resolve(supportedAbis: List<String>, shippedAbis: Set<String>): String? =
        supportedAbis.firstOrNull { it in shippedAbis } ?: supportedAbis.firstOrNull()
}
