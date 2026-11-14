package com.kaia.meshvpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * JVM tests for the binary integrity gate of [MeshDaemon].
 *
 * They prove, without a device:
 *  - ELF magic is required (a truncated / random file is rejected),
 *  - a minimum plausible size is enforced (zero-length copy rejected),
 *  - a corrupt file is deleted before re-extraction (so the retry starts clean).
 */
class BinaryIntegrityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun elfOf(size: Int): ByteArray {
        val b = ByteArray(size)
        b[0] = 0x7F
        b[1] = 'E'.code.toByte()
        b[2] = 'L'.code.toByte()
        b[3] = 'F'.code.toByte()
        return b
    }

    @Test
    fun `valid elf of sufficient size is usable`() {
        val f = tmp.newFile("meshd-ok")
        f.writeBytes(elfOf(2_000_000))
        assertTrue(BinaryGate.isUsableBin(f))
    }

    @Test
    fun `file without ELF magic is rejected`() {
        val f = tmp.newFile("meshd-bad-magic")
        f.writeBytes(ByteArray(2_000_000) { 0x41 })
        assertFalse(BinaryGate.isUsableBin(f))
    }

    @Test
    fun `truncated file is rejected`() {
        val f = tmp.newFile("meshd-truncated")
        f.writeBytes(elfOf(1024))
        assertFalse(BinaryGate.isUsableBin(f))
    }

    @Test
    fun `missing file is rejected`() {
        assertFalse(BinaryGate.isUsableBin(File(tmp.root, "absent")))
    }

    @Test
    fun `corrupt file is wiped so re-extraction starts clean`() {
        val f = tmp.newFile("meshd-corrupt")
        f.writeBytes(ByteArray(64))
        assertEquals(64L, f.length())
        BinaryGate.prepareReinstall(f)
        assertFalse("corrupt copy must be deleted before retry", f.exists())
    }

    @Test
    fun `valid file is left untouched by prepareReinstall`() {
        val f = tmp.newFile("meshd-valid")
        f.writeBytes(elfOf(2_000_000))
        BinaryGate.prepareReinstall(f)
        assertTrue("usable copy must be kept (idempotent install)", f.exists())
    }
}
