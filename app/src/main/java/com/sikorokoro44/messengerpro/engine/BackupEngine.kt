package com.sikorokoro44.messengerpro.engine

/**
 * Versioned, checksummed backup format. The payload is an opaque snapshot string
 * produced by the caller; this engine owns the envelope so integrity, versioning
 * and corruption handling are testable without Android.
 */
class BackupEngine {

    data class Backup(val version: Int, val createdAt: Long, val payload: String, val checksum: String, val bytes: Int)

    data class Result(val ok: Boolean, val backup: Backup? = null, val error: String? = null)

    private companion object {
        const val CURRENT_VERSION = 1
        const val HEADER = "MESSENGERPRO-BACKUP"
    }

    fun create(payload: String, createdAt: Long = System.currentTimeMillis()): Backup {
        val checksum = Hashing.sha256(payload)
        return Backup(CURRENT_VERSION, createdAt, payload, checksum, payload.toByteArray().size)
    }

    fun serialise(backup: Backup): String =
        "$HEADER\nversion=${backup.version}\ncreatedAt=${backup.createdAt}\n" +
            "bytes=${backup.bytes}\nchecksum=${backup.checksum}\n--\n${backup.payload}"

    fun parse(serialised: String): Result {
        val parts = serialised.split("\n--\n", limit = 2)
        if (parts.size != 2) return Result(false, error = "Corrupted backup: missing payload separator")
        val header = parts[0].lines()
        if (header.firstOrNull() != HEADER) return Result(false, error = "Corrupted backup: bad header")
        val fields = header.drop(1).associate {
            val idx = it.indexOf('=')
            if (idx < 0) "" to "" else it.substring(0, idx) to it.substring(idx + 1)
        }
        val version = fields["version"]?.toIntOrNull() ?: return Result(false, error = "Corrupted backup: missing version")
        if (version > CURRENT_VERSION) return Result(false, error = "Unsupported backup version $version")
        val payload = parts[1]
        val checksum = fields["checksum"] ?: return Result(false, error = "Corrupted backup: missing checksum")
        if (Hashing.sha256(payload) != checksum) return Result(false, error = "Backup integrity check failed")
        val createdAt = fields["createdAt"]?.toLongOrNull() ?: 0L
        return Result(true, Backup(version, createdAt, payload, checksum, payload.toByteArray().size))
    }

    fun size(backup: Backup): Int = backup.bytes
}
