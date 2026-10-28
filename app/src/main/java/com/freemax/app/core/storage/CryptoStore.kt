package com.freemax.app.core.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Шифрование локальных файлов кэша (AES-GCM, ключ в Android Keystore).
 *
 * Формат файла: "FMX1" + iv(12 байт) + ciphertext+tag.
 * Старые (незашифрованные) файлы читаются как есть — миграция прозрачная:
 * при следующей записи файл станет зашифрованным. Если расшифровка не
 * удалась (ключ потерян и т.п.) — вызывающий получит null и пересоздаст кэш.
 */
@Singleton
class CryptoStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "CryptoStore"
        private const val KEY_ALIAS = "freemax_cache_key"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val MAGIC = "FMX1"
        private const val IV_LEN = 12
        private const val TAG_BITS = 128
    }

    private val key: SecretKey? by lazy { loadOrCreateKey() }

    private fun loadOrCreateKey(): SecretKey? {
        return try {
            val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
                ?: generateKey()
        } catch (e: Exception) {
            Log.e(TAG, "keystore init failed", e)
            null
        }
    }

    private fun generateKey(): SecretKey? {
        return try {
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            gen.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            gen.generateKey()
        } catch (e: Exception) {
            Log.e(TAG, "key generation failed", e)
            null
        }
    }

    /** Зашифровать строку. Возвращает null, если шифрование недоступно. */
    fun encrypt(text: String): ByteArray? {
        val k = key ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, k)
            val iv = cipher.iv
            val encrypted = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
            MAGIC.toByteArray() + iv + encrypted
        } catch (e: Exception) {
            Log.e(TAG, "encrypt failed", e)
            null
        }
    }

    /** Расшифровать; null — не наш формат (legacy) или ошибка. */
    fun decrypt(bytes: ByteArray): String? {
        if (bytes.size < 4 + IV_LEN || String(bytes, 0, 4) != MAGIC) return null
        val k = key ?: return null
        return try {
            val iv = bytes.copyOfRange(4, 4 + IV_LEN)
            val data = bytes.copyOfRange(4 + IV_LEN, bytes.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "decrypt failed (key changed?)", e)
            null
        }
    }

    /**
     * Читает текстовый файл: зашифрованный — расшифровывает, legacy — как есть.
     * Возвращает null, если файла нет или он не читается/не расшифровывается.
     */
    fun readText(file: File): String? {
        if (!file.exists()) return null
        return try {
            val bytes = file.readBytes()
            if (bytes.isEmpty()) return null
            if (bytes.size >= 4 && String(bytes, 0, 4) == MAGIC) {
                decrypt(bytes) // null — пересоздадим файл
            } else {
                // legacy-плейнтекст: читаем и сразу перешифровываем
                val text = String(bytes, Charsets.UTF_8)
                writeAtomic(file, text)
                text
            }
        } catch (e: Exception) {
            Log.e(TAG, "readText failed ${file.name}", e)
            null
        }
    }

    /** Атомарная запись (tmp + rename), данные шифруются. */
    fun writeAtomic(file: File, text: String) {
        try {
            file.parentFile?.mkdirs()
            val payload = encrypt(text) ?: text.toByteArray(Charsets.UTF_8)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeBytes(payload)
            if (!tmp.renameTo(file)) {
                file.writeBytes(payload)
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "writeAtomic failed ${file.name}", e)
        }
    }
}
