package com.theacar.android.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class EncryptedSecretFile(context: Context, fileName: String, private val alias: String) {
    init { require(Regex("[a-zA-Z0-9_.-]+").matches(fileName)) }
    private val file = AtomicFile(File(context.noBackupFilesDir, fileName))
    private val associatedData = alias.toByteArray(Charsets.UTF_8)

    fun read(): String {
        val input = try { file.openRead() } catch (error: FileNotFoundException) {
            if (file.baseFile.exists()) throw error
            return ""
        }
        return DataInputStream(input).use { data ->
            require(data.readUnsignedByte() == 1)
            val iv = ByteArray(12).also(data::readFully)
            val size = data.readInt()
            require(size in 16..16_384)
            val encrypted = ByteArray(size).also(data::readFully)
            require(data.read() == -1)
            val key = keyStore().getKey(alias, null) as? SecretKey ?: throw IOException("Credential key unavailable")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(associatedData)
            cipher.doFinal(encrypted).toString(Charsets.UTF_8)
        }
    }

    fun write(token: String) {
        if (token.isEmpty()) {
            file.delete()
            if (file.baseFile.exists()) throw IOException("Credential removal failed")
            return
        }
        val bytes = token.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 4_096)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        require(cipher.iv.size == 12)
        cipher.updateAAD(associatedData)
        val encrypted = cipher.doFinal(bytes)
        val output = file.startWrite()
        try {
            val data = DataOutputStream(output)
            data.writeByte(1)
            data.write(cipher.iv)
            data.writeInt(encrypted.size)
            data.write(encrypted)
            data.flush()
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun encryptionKey(): SecretKey {
        (keyStore().getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
            generateKey()
        }
    }
}
