package com.os73.dosos73

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-bound authenticated encryption with a bounded, one-time legacy upgrade. */
internal class PrivateJournal(context: Context, name: String, private val maxRecordBytes: Int = MAX_RECORD_BYTES) {
    private val file = AtomicFile(File(context.filesDir, name))
    private val binding = name.toByteArray(Charsets.UTF_8)
    // Only these two journals existed before authenticated encryption. Publication,
    // batch and timeline journals have always been encrypted; no per-task markers grow.
    private val format = if (name in LEGACY_NAMES)
        AtomicFile(File(context.filesDir, "$name.encrypted-format")) else null
    private val formatBinding = ("format-v1:$name").toByteArray(Charsets.UTF_8)
    // AtomicFile protects commits, not concurrent readers/writers. A bounded stripe
    // is shared by every instance for this canonical path, with no per-task map growth.
    private val monitor = FILE_LOCKS[(file.baseFile.canonicalPath.hashCode() and Int.MAX_VALUE) % FILE_LOCKS.size]

    init { require(maxRecordBytes >= ENVELOPE_BYTES) }

    fun readOrNull(): String? = synchronized(monitor) { readLocked() }

    private fun readLocked(): String? {
        val bytes = readBytes(file, maxRecordBytes) ?: return null
        if (isLegacy(bytes)) {
            if (format == null) throw IOException("Plaintext private journal is not supported")
            val legacy = bytes.toString(Charsets.UTF_8)
            writeLocked(legacy, bytes)
            return legacy
        }
        val plaintext = decrypt(bytes, binding).toString(Charsets.UTF_8)
        sealFormat()
        return plaintext
    }

    private fun readBytes(source: AtomicFile, limit: Int): ByteArray? = try {
        source.openRead().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(minOf(65536, limit))
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n > limit - output.size()) throw IOException("Private journal exceeds size limit")
                output.write(buffer, 0, n)
            }
            output.toByteArray()
        }
    } catch (error: FileNotFoundException) {
        // An existing but unreadable marker must not reopen the legacy path.
        // A missing base after an interrupted initial .new write is still absent;
        // AtomicFile.openRead performs its own committed-backup recovery first.
        if (source.baseFile.exists() || File(source.baseFile.path + ".bak").exists() ||
            source.baseFile.parentFile?.isDirectory != true)
            throw IOException("Private journal cannot be read", error)
        null
    }

    private fun decrypt(bytes: ByteArray, aad: ByteArray): ByteArray {
        if (bytes.size < ENVELOPE_BYTES || !bytes.copyOfRange(0, 4).contentEquals(MAGIC))
            throw IOException("Invalid private journal")
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(4, 16)))
            cipher.updateAAD(aad)
            cipher.doFinal(bytes, 16, bytes.size - 16)
        } catch (error: Exception) {
            throw IOException("Private journal unavailable", error)
        }
    }

    fun write(text: String) = synchronized(monitor) { writeLocked(text) }

    private fun writeLocked(text: String, legacyBytes: ByteArray? = null) {
        val plaintext = boundedUtf8(text)
        prepareFormat(legacyBytes)
        // Fault injection is debug-only and eliminated from minified Release.
        if (BuildConfig.DEBUG) beforeWriteForTests?.invoke(file.baseFile)
        atomicWrite(file, encrypt(plaintext, binding))
        sealFormat()
    }

    private fun boundedUtf8(text: String): ByteArray {
        val limit = maxRecordBytes - ENVELOPE_BYTES
        if (text.length > limit) throw IOException("Private journal exceeds size limit")
        // Count UTF-8 before allocating it; a surrogate pair is four bytes, and an
        // unmatched surrogate uses the encoder's one-byte replacement character.
        var bytes = 0
        var index = 0
        while (index < text.length) {
            val char = text[index++]
            bytes += when {
                char.code < 0x80 -> 1
                char.code < 0x800 -> 2
                Character.isHighSurrogate(char) && index < text.length && Character.isLowSurrogate(text[index]) -> {
                    index++; 4
                }
                Character.isSurrogate(char) -> 1
                else -> 3
            }
            if (bytes > limit) throw IOException("Private journal exceeds size limit")
        }
        return text.toByteArray(Charsets.UTF_8)
    }

    private fun formatState(): String? {
        val marker = format ?: return SEALED
        val bytes = readBytes(marker, 256) ?: return null
        return decrypt(bytes, formatBinding).toString(Charsets.UTF_8).also {
            if (it != SEALED && !it.matches(Regex("legacy:[0-9a-f]{64}")))
                throw IOException("Invalid journal format marker")
        }
    }

    private fun prepareFormat(legacyBytes: ByteArray?) {
        if (format == null) return
        val state = formatState()
        val existing = legacyBytes ?: if (state != SEALED) readBytes(file, maxRecordBytes) else null
        if (existing != null && isLegacy(existing)) {
            if (state == SEALED) throw IOException("Private journal plaintext downgrade rejected")
            val pending = "legacy:" + MessageDigest.getInstance("SHA-256").digest(existing)
                .joinToString("") { "%02x".format(it) }
            if (state != null && state != pending)
                throw IOException("Legacy private journal changed during migration")
            if (state == null) writeFormat(pending)
        } else if (state == null) writeFormat(SEALED)
    }

    private fun sealFormat() {
        if (format != null && formatState() != SEALED) writeFormat(SEALED)
    }

    private fun writeFormat(state: String) {
        val marker = checkNotNull(format)
        if (BuildConfig.DEBUG) beforeFormatWriteForTests?.invoke(marker.baseFile, state)
        atomicWrite(marker, encrypt(state.toByteArray(Charsets.UTF_8), formatBinding))
    }

    private fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(aad)
        return MAGIC + cipher.iv + cipher.doFinal(plaintext)
    }

    private fun atomicWrite(destination: AtomicFile, bytes: ByteArray) {
        val output = destination.startWrite()
        try {
            output.write(bytes)
            destination.finishWrite(output)
        } catch (error: Exception) {
            destination.failWrite(output)
            throw error
        }
    }

    private fun isLegacy(bytes: ByteArray) = bytes.firstOrNull()?.toInt() in listOf('['.code, '{'.code)

    companion object {
        @Volatile internal var beforeWriteForTests: ((File) -> Unit)? = null
        @Volatile internal var beforeFormatWriteForTests: ((File, String) -> Unit)? = null
        private const val MAX_RECORD_BYTES = 16 * 1024 * 1024
        private const val ENVELOPE_BYTES = 32
        private const val SEALED = "encrypted-v1"
        private val LEGACY_NAMES = setOf("waiting-tasks.json", "failed-downloads.json")
        private val FILE_LOCKS = Array(64) { Any() }
        private val MAGIC = byteArrayOf(68, 55, 51, 1)
        private const val ALIAS = "dosos73.private-journal.v1"

        @Synchronized
        private fun key(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(ALIAS, null) as? SecretKey)?.let {
                return it
            }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                .apply {
                    init(
                        KeyGenParameterSpec.Builder(
                                ALIAS,
                                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                            )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .build()
                    )
                }
                .generateKey()
        }
    }
}
