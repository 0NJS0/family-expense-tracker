package bd.family.expenses.data.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Persistent storage for the refresh token. The access token is kept in memory only. */
interface TokenStore {
  fun load(): String?

  fun save(refreshToken: String)

  fun clear()
}

/**
 * Encrypts the refresh token with an AES-256-GCM key held in the Android
 * Keystore. The key cannot be exported and is not included in backups, so a
 * copied preferences file is useless without this device.
 */
class KeystoreTokenStore(context: Context) : TokenStore {
  private val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)

  override fun load(): String? {
    val stored = prefs.getString(KEY_TOKEN, null) ?: return null
    return try {
      val bytes = Base64.decode(stored, Base64.NO_WRAP)
      // IV + at least the GCM tag; anything shorter is corrupt.
      if (bytes.size <= IV_BYTES + TAG_BITS / 8) {
        clear()
        return null
      }
      val iv = bytes.copyOfRange(0, IV_BYTES)
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
      String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    } catch (e: GeneralSecurityException) {
      // Key invalidated (e.g. lock-screen reset) or data corrupted: log in again.
      clear()
      null
    } catch (e: IllegalArgumentException) {
      clear()
      null
    }
  }

  override fun save(refreshToken: String) {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, key())
    val encrypted = cipher.iv + cipher.doFinal(refreshToken.toByteArray(Charsets.UTF_8))
    prefs.edit().putString(KEY_TOKEN, Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
  }

  override fun clear() {
    prefs.edit().remove(KEY_TOKEN).apply()
  }

  private fun key(): SecretKey {
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
    generator.init(
      KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .build()
    )
    return generator.generateKey()
  }

  private companion object {
    const val ANDROID_KEYSTORE = "AndroidKeyStore"
    const val KEY_ALIAS = "fet_refresh_token_key"
    const val KEY_TOKEN = "refresh_token"
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val IV_BYTES = 12
    const val TAG_BITS = 128
  }
}
