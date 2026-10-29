package com.os73.dosos73

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-bound authenticated encryption; legacy JSON migrates at the next successful write. */
internal class PrivateJournal(context: Context, name: String) {
    private val file = AtomicFile(File(context.filesDir, name))
    private val binding = name.toByteArray(Charsets.UTF_8)

    fun readOrNull(): String? {
        val bytes =
            try {
                file.openRead().use { it.readBytes() }
            } catch (_: FileNotFoundException) {
                return null
            }
        if (bytes.firstOrNull()?.toInt() in listOf('['.code, '{'.code)) {
            val legacy = bytes.toString(Charsets.UTF_8)
            write(legacy)
            return legacy
        }
        if (bytes.size < 32 || !bytes.copyOfRange(0, 4).contentEquals(MAGIC))
            throw IOException("Invalid private journal")
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(4, 16)))
            cipher.updateAAD(binding)
            cipher.doFinal(bytes, 16, bytes.size - 16).toString(Charsets.UTF_8)
        } catch (error: Exception) {
            throw IOException("Private journal unavailable", error)
        }
    }

    fun write(text: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(binding)
        val bytes = MAGIC + cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    companion object {
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
