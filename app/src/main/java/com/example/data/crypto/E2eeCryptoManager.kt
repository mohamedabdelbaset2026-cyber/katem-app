package com.example.data.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class EncryptedPayload(
    val ciphertextBase64: String,
    val ivBase64: String
)

data class PublicKeyBundle(
    val publicKeyBase64: String,
    val fingerprint: String,
    val algorithm: String = E2eeCryptoManager.ALGORITHM_SUITE
)

data class WrappedRecoveryBlob(
    val encryptedBlobBase64: String,
    val saltBase64: String,
    val ivBase64: String,
    val kdfIterations: Int = E2eeCryptoManager.RECOVERY_PBKDF2_ITERATIONS
)

/**
 * Standard JCA End-to-End Encryption manager using NIST-standard primitives only:
 * - Identity keys: ECDH secp256r1 (P-256)
 * - Key derivation: HKDF-SHA256 (RFC 5869)
 * - Message cipher: AES-256-GCM (96-bit random IV, 128-bit auth tag, conversationId AAD)
 * - Local private key protection: AndroidKeyStore AES-256-GCM wrapping key
 * - Recovery vault: PBKDF2WithHmacSHA256 (100,000 iterations) + AES-256-GCM
 */
class E2eeCryptoManager(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secureRandom = SecureRandom()

    fun getOrCreateIdentityKeyPair(uid: String): KeyPair {
        loadIdentityKeyPair(uid)?.let { return it }
        val keyPairGenerator = KeyPairGenerator.getInstance("EC")
        keyPairGenerator.initialize(ECGenParameterSpec("secp256r1"), secureRandom)
        val generated = keyPairGenerator.generateKeyPair()
        saveIdentityKeyPair(uid, generated)
        return generated
    }

    fun getPublicKeyBundle(uid: String): PublicKeyBundle {
        val kp = getOrCreateIdentityKeyPair(uid)
        val encoded = kp.public.encoded
        val b64 = Base64.encodeToString(encoded, Base64.NO_WRAP)
        return PublicKeyBundle(
            publicKeyBase64 = b64,
            fingerprint = computeFingerprint(encoded)
        )
    }

    fun computeSafetyNumber(myPublicKeyBase64: String, peerPublicKeyBase64: String): String {
        val sorted = listOf(myPublicKeyBase64, peerPublicKeyBase64).sorted()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((sorted[0] + "|" + sorted[1]).toByteArray(Charsets.UTF_8))
        return formatSafetyGroups(digest)
    }

    fun encryptMessage(
        myUid: String,
        peerPublicKeyBase64: String,
        conversationId: String,
        plaintext: String
    ): EncryptedPayload {
        val aesKey = deriveConversationAesKey(myUid, peerPublicKeyBase64, conversationId)
        val iv = ByteArray(GCM_IV_BYTES).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(conversationId.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return EncryptedPayload(
            ciphertextBase64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
            ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        )
    }

    fun decryptMessage(
        myUid: String,
        peerPublicKeyBase64: String,
        conversationId: String,
        ciphertextBase64: String,
        ivBase64: String
    ): Result<String> = runCatching {
        val aesKey = deriveConversationAesKey(myUid, peerPublicKeyBase64, conversationId)
        val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
        val ciphertext = Base64.decode(ciphertextBase64, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(conversationId.toByteArray(Charsets.UTF_8))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    /**
     * Encrypts the user's private & public identity keypair with a user-chosen recovery passphrase
     * using PBKDF2WithHmacSHA256 + AES-256-GCM before storing in Firestore recoveryData.
     */
    fun createRecoveryVaultBlob(uid: String, passphrase: String): WrappedRecoveryBlob {
        require(passphrase.length >= 8) { "Passphrase must be at least 8 characters" }
        val kp = getOrCreateIdentityKeyPair(uid)
        val salt = ByteArray(16).also { secureRandom.nextBytes(it) }
        val iv = ByteArray(GCM_IV_BYTES).also { secureRandom.nextBytes(it) }
        val wrappingKey = derivePbkdf2Key(passphrase, salt, RECOVERY_PBKDF2_ITERATIONS)

        val privB64 = Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP)
        val pubB64 = Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP)
        val payload = "$privB64|$pubB64".toByteArray(Charsets.UTF_8)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD("katem-recovery-$uid".toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(payload)

        return WrappedRecoveryBlob(
            encryptedBlobBase64 = Base64.encodeToString(encrypted, Base64.NO_WRAP),
            saltBase64 = Base64.encodeToString(salt, Base64.NO_WRAP),
            ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP),
            kdfIterations = RECOVERY_PBKDF2_ITERATIONS
        )
    }

    /**
     * Unwraps the encrypted recovery vault using the passphrase and restores the local E2EE keypair.
     */
    fun restoreFromRecoveryVaultBlob(
        uid: String,
        passphrase: String,
        encryptedBlobBase64: String,
        saltBase64: String,
        ivBase64: String,
        kdfIterations: Int
    ): Result<PublicKeyBundle> = runCatching {
        val salt = Base64.decode(saltBase64, Base64.NO_WRAP)
        val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
        val encrypted = Base64.decode(encryptedBlobBase64, Base64.NO_WRAP)
        val wrappingKey = derivePbkdf2Key(passphrase, salt, kdfIterations)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD("katem-recovery-$uid".toByteArray(Charsets.UTF_8))
        val decrypted = String(cipher.doFinal(encrypted), Charsets.UTF_8)

        val parts = decrypted.split("|")
        require(parts.size == 2) { "Malformed recovery key payload" }
        val keyFactory = KeyFactory.getInstance("EC")
        val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(parts[0], Base64.NO_WRAP)))
        val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(Base64.decode(parts[1], Base64.NO_WRAP)))
        val restoredPair = KeyPair(publicKey, privateKey)
        saveIdentityKeyPair(uid, restoredPair)
        PublicKeyBundle(
            publicKeyBase64 = Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP),
            fingerprint = computeFingerprint(publicKey.encoded)
        )
    }

    fun generateStrongPassphrase(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (0 until 4).joinToString("-") {
            (0 until 4).map { alphabet[secureRandom.nextInt(alphabet.length)] }.joinToString("")
        }
    }

    private fun deriveConversationAesKey(
        myUid: String,
        peerPublicKeyBase64: String,
        conversationId: String
    ): SecretKeySpec {
        val myPair = getOrCreateIdentityKeyPair(myUid)
        val peerPublicBytes = Base64.decode(peerPublicKeyBase64, Base64.NO_WRAP)
        val peerPublicKey: PublicKey = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(peerPublicBytes))

        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(myPair.private)
        keyAgreement.doPhase(peerPublicKey, true)
        val sharedSecret = keyAgreement.generateSecret()

        val salt = "katem-e2ee-v1".toByteArray(Charsets.UTF_8)
        val info = "conv:$conversationId".toByteArray(Charsets.UTF_8)
        val derived32 = hkdfSha256(ikm = sharedSecret, salt = salt, info = info, length = 32)
        return SecretKeySpec(derived32, "AES")
    }

    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val extractMac = Mac.getInstance("HmacSHA256")
        extractMac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = extractMac.doFinal(ikm)

        val expandMac = Mac.getInstance("HmacSHA256")
        expandMac.init(SecretKeySpec(prk, "HmacSHA256"))
        val result = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var counter: Byte = 1
        while (offset < length) {
            expandMac.reset()
            expandMac.update(t)
            expandMac.update(info)
            expandMac.update(counter)
            t = expandMac.doFinal()
            val copyLen = minOf(t.size, length - offset)
            System.arraycopy(t, 0, result, offset, copyLen)
            offset += copyLen
            counter++
        }
        return result
    }

    private fun derivePbkdf2Key(passphrase: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    private fun getOrCreateKeystoreMasterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun saveIdentityKeyPair(uid: String, keyPair: KeyPair) {
        val masterKey = getOrCreateKeystoreMasterKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey)
        val encryptedPrivate = cipher.doFinal(keyPair.private.encoded)
        val iv = cipher.iv

        prefs.edit()
            .putString("priv_enc_$uid", Base64.encodeToString(encryptedPrivate, Base64.NO_WRAP))
            .putString("priv_iv_$uid", Base64.encodeToString(iv, Base64.NO_WRAP))
            .putString("pub_$uid", Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP))
            .apply()
    }

    private fun loadIdentityKeyPair(uid: String): KeyPair? {
        val encPrivB64 = prefs.getString("priv_enc_$uid", null) ?: return null
        val ivB64 = prefs.getString("priv_iv_$uid", null) ?: return null
        val pubB64 = prefs.getString("pub_$uid", null) ?: return null

        return runCatching {
            val masterKey = getOrCreateKeystoreMasterKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                masterKey,
                GCMParameterSpec(GCM_TAG_BITS, Base64.decode(ivB64, Base64.NO_WRAP))
            )
            val privBytes = cipher.doFinal(Base64.decode(encPrivB64, Base64.NO_WRAP))
            val pubBytes = Base64.decode(pubB64, Base64.NO_WRAP)
            val kf = KeyFactory.getInstance("EC")
            val privateKey: PrivateKey = kf.generatePrivate(PKCS8EncodedKeySpec(privBytes))
            val publicKey: PublicKey = kf.generatePublic(X509EncodedKeySpec(pubBytes))
            KeyPair(publicKey, privateKey)
        }.getOrNull()
    }

    private fun computeFingerprint(publicKeyBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyBytes)
        return formatSafetyGroups(digest)
    }

    private fun formatSafetyGroups(hash: ByteArray): String {
        val hex = hash.take(16).joinToString("") { "%02X".format(it) }
        return hex.chunked(4).joinToString(" ")
    }

    companion object {
        const val ALGORITHM_SUITE = "ECDH-P256-AES256-GCM"
        const val RECOVERY_PBKDF2_ITERATIONS = 100_000
        private const val PREFS_NAME = "katem_e2ee_keystore_vault"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEYSTORE_ALIAS = "katem_e2ee_master_aes256"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }
}
