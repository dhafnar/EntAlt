package si.hafnar.entalt.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.*

class CredentialStore(
    context: Context,
    recordName: String = "account.v1",
    private val alias: String = "entalt.account.v1",
    hueRecordName: String = if (recordName == "account.v1") "hue.v1" else "$recordName.hue",
    private val hueAlias: String = if (alias == "entalt.account.v1") "entalt.hue.v1" else "$alias.hue",
) : AccountStore, HueStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, recordName))
    private val hueFile = AtomicFile(File(context.noBackupFilesDir, hueRecordName))
    private val keyStore get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun key(keyAlias: String): SecretKey {
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private fun encryptAndWrite(targetFile: AtomicFile, keyAlias: String, plaintext: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(keyAlias))
        val encrypted = try { cipher.doFinal(plaintext) } finally { plaintext.fill(0) }
        val bytes = ByteBuffer.allocate(8 + cipher.iv.size + encrypted.size)
            .putInt(1).putInt(cipher.iv.size).put(cipher.iv).put(encrypted).array()
        val stream = targetFile.startWrite()
        try { stream.write(bytes); targetFile.finishWrite(stream) } catch (e: Exception) { targetFile.failWrite(stream); throw e }
    }

    private fun readAndDecrypt(targetFile: AtomicFile, keyAlias: String, missingLabel: String): ByteArray? {
        if (!targetFile.baseFile.exists() && !File(targetFile.baseFile.path + ".bak").exists()) return null
        val bytes = ByteBuffer.wrap(targetFile.readFully())
        require(bytes.int == 1) { "Unsupported saved $missingLabel." }
        val ivLength = bytes.int
        require(ivLength == 12 && bytes.remaining() > ivLength + 16) { "The saved $missingLabel is damaged." }
        val iv = ByteArray(ivLength).also(bytes::get)
        val ciphertext = ByteArray(bytes.remaining()).also(bytes::get)
        val existing = keyStore.getKey(keyAlias, null) as? SecretKey ?: error("Saved $missingLabel key is missing.")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, existing, GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    @Synchronized override fun save(credentials: Credentials) {
        val plaintext = buildJsonObject {
            put("username", credentials.username)
            put("password", credentials.password)
        }.toString().toByteArray()
        encryptAndWrite(file, alias, plaintext)
    }

    @Synchronized override fun load(): Credentials? {
        val plaintext = readAndDecrypt(file, alias, "account") ?: return null
        return try {
            val record = Json.parseToJsonElement(plaintext.decodeToString()).jsonObject
            Credentials(record.getValue("username").jsonPrimitive.content, record.getValue("password").jsonPrimitive.content)
        } finally { plaintext.fill(0) }
    }

    @Synchronized override fun clear() { file.delete(); keyStore.deleteEntry(alias) }

    @Synchronized override fun saveHue(credentials: HueCredentials) {
        val plaintext = buildJsonObject {
            put("bridgeIp", credentials.bridgeIp)
            put("appKey", credentials.appKey)
            credentials.clientKey?.let { put("clientKey", it) }
            credentials.bridgeId?.let { put("bridgeId", it) }
        }.toString().toByteArray()
        encryptAndWrite(hueFile, hueAlias, plaintext)
    }

    @Synchronized override fun loadHue(): HueCredentials? {
        val plaintext = readAndDecrypt(hueFile, hueAlias, "Hue bridge") ?: return null
        return try {
            val record = Json.parseToJsonElement(plaintext.decodeToString()).jsonObject
            HueCredentials(
                bridgeIp = record.getValue("bridgeIp").jsonPrimitive.content,
                appKey = record.getValue("appKey").jsonPrimitive.content,
                clientKey = (record["clientKey"] as? JsonPrimitive)?.contentOrNull,
                bridgeId = (record["bridgeId"] as? JsonPrimitive)?.contentOrNull,
            )
        } finally { plaintext.fill(0) }
    }

    @Synchronized override fun clearHue() { hueFile.delete(); keyStore.deleteEntry(hueAlias) }
}
