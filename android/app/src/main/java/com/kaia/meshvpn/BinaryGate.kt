package com.kaia.meshvpn

import java.io.File

/**
 * Pure JVM mirror of the integrity / re-extraction rules enforced by
 * [MeshDaemon] on device.
 *
 * `MeshDaemon` itself needs `android.content.Context` and cannot be instanced on
 * a plain JVM, so the exact same predicates are exposed here (same constants,
 * same order of checks) and the unit tests in `app/src/test` prove them:
 *
 *  - [MIN_BINARY_BYTES] : a zero-length or truncated copy is never launched.
 *  - [ELF_MAGIC]        : a file that is not an ELF executable is refused.
 *  - [prepareReinstall] : a corrupt copy is deleted before a re-extraction, so a
 *                         retry never re-uses a half-written file.
 *
 * `MeshDaemon` uses these helpers directly, so there is a single source of truth.
 */
object BinaryGate {

    /** ELF magic: 0x7F 'E' 'L' 'F'. */
    val ELF_MAGIC = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())

    /** Minimum plausible size for the Go binary (sanity floor). */
    const val MIN_BINARY_BYTES = 1_000_000L

    /**
     * True when the file on disk looks like a complete, valid ELF binary.
     * On device the same checks are applied after every copy (see MeshDaemon).
     */
    fun isUsableBin(file: File): Boolean {
        if (!file.exists() || !file.isFile) return false
        if (file.length() < MIN_BINARY_BYTES) return false
        val magic = ByteArray(4)
        val read = runCatching { file.inputStream().use { it.read(magic) } }.getOrDefault(0)
        return read == 4 && magic.contentEquals(ELF_MAGIC)
    }

    /**
     * Removes a copy that cannot be used, so the next [install] performs a clean
     * re-extraction. A usable copy is kept (install is idempotent).
     */
    fun prepareReinstall(file: File): Boolean {
        if (!file.exists()) return false
        if (isUsableBin(file)) return false
        return file.delete()
    }
}
