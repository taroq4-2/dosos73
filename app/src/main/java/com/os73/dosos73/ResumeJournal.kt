package com.os73.dosos73

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Only durable bytes are resumable. The journal never contains a source URL or credentials. */
internal data class ResumeCheckpoint(
    val sourceHash: String,
    val targetHash: String,
    val etag: String,
    val total: Long,
    val offset: Long,
) {
    companion object {
        fun hash(value: String) =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        fun strongTag(value: String?): String? =
            value?.takeIf {
                it.length in 2..1024 &&
                    it.startsWith('"') &&
                    it.endsWith('"') &&
                    it.substring(1, it.lastIndex).none { c ->
                        c == '"' || c.code < 0x21 || c.code == 0x7f
                    }
            }
    }
}

internal class ResumeJournal(destination: File) {
    private val file = File(destination.parentFile, destination.name + ".resume")
    private val pending = File(destination.parentFile, destination.name + ".resume.tmp")

    fun read(): ResumeCheckpoint? =
        runCatching {
                require(file.length() in 1..4096)
                DataInputStream(file.inputStream().buffered()).use {
                    require(it.readInt() == 1)
                    val checkpoint =
                        ResumeCheckpoint(
                            it.readUTF(),
                            it.readUTF(),
                            it.readUTF(),
                            it.readLong(),
                            it.readLong(),
                        )
                    require(checkpoint.sourceHash.matches(Regex("[0-9a-f]{64}")))
                    require(checkpoint.targetHash.matches(Regex("[0-9a-f]{64}")))
                    require(ResumeCheckpoint.strongTag(checkpoint.etag) != null)
                    require(checkpoint.total > 0 && checkpoint.offset in 0..checkpoint.total)
                    require(it.read() == -1)
                    checkpoint
                }
            }
            .getOrNull()

    fun write(checkpoint: ResumeCheckpoint) {
        FileOutputStream(pending).use { stream ->
            val data = DataOutputStream(stream)
            data.writeInt(1)
            data.writeUTF(checkpoint.sourceHash)
            data.writeUTF(checkpoint.targetHash)
            data.writeUTF(checkpoint.etag)
            data.writeLong(checkpoint.total)
            data.writeLong(checkpoint.offset)
            data.flush()
            stream.fd.sync()
        }
        // Atomic replacement leaves the previous checkpoint usable if the process dies mid-write.
        Files.move(
            pending.toPath(),
            file.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    fun clear() {
        if (file.exists() && !file.delete())
            throw java.io.IOException("Cannot remove resume journal")
        if (pending.exists() && !pending.delete())
            throw java.io.IOException("Cannot remove resume journal")
    }
}
