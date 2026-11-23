package app.memoria

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The vault's master key: 64 random bytes, wrapped by an Android Keystore key that never leaves the phone's chip.
 * That key needs no screen lock: Android deletes a lock-bound key when the lock is turned off, and every locked
 * photo with it. The phone's own fingerprint or PIN sheet still opens the folder each time (`unlockVault`).
 */
object VaultKey {
    private const val alias = "memoria-vault-2"
    private const val lockBound = "memoria-vault" // the first builds' key, tied to the lock: moved off on the first unlock

    fun file(context: Context) = File(context.filesDir, "vault/key")

    fun exists(context: Context) = file(context).exists()

    /** The keys, or null when the key file or its Keystore key is gone. No sheet: locking asks for nothing. */
    fun keys(context: Context): VaultKeys? = runCatching { VaultKeys(unwrap(context)) }.getOrNull()

    /** Makes the master on first use. */
    fun create(context: Context): VaultKeys = VaultKeys.random().let { save(context, it); VaultKeys(it) }

    private fun unwrap(context: Context): ByteArray {
        val blob = file(context).readBytes()
        if (blob.size < 12 + 64) throw IOException("not a wrapped key")
        try {
            return open(blob, secret())
        } catch (e: GeneralSecurityException) {
            // A first-build vault: the sheet just opened its lock-bound key for 30 s, so move the master to the new key
            val old = store().getKey(lockBound, null) as? SecretKey ?: throw e
            return open(blob, old).also { save(context, it); store().deleteEntry(lockBound); AppLog.i("vault: key moved off the screen lock") }
        }
    }

    private fun open(blob: ByteArray, key: SecretKey): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, 12))
        doFinal(blob, 12, blob.size - 12)
    }

    // A new file, then a rename: a crash mid-write must never leave half of the only way into the vault
    private fun save(context: Context, master: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secret()) }
        val wrapped = cipher.iv + cipher.doFinal(master)
        val f = file(context).apply { parentFile?.mkdirs() }
        val tmp = File(f.parentFile, "key.tmp").apply { writeBytes(wrapped) }
        if (!tmp.renameTo(f)) throw IOException("cannot save the vault key")
    }

    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun secret(): SecretKey {
        (store().getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }
}

/** True when the phone has a fingerprint or a lock screen to confirm with. */
fun canUnlockVault(context: Context): Boolean = runCatching {
    BiometricManager.from(context)
        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL) ==
        BiometricManager.BIOMETRIC_SUCCESS
}.getOrDefault(false)

/** The phone's own fingerprint or PIN sheet. [onOk] runs once the phone says the user is who it thinks. */
fun askVaultAuth(activity: FragmentActivity, onOk: () -> Unit, onFail: (String) -> Unit = {}) {
    val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
        override fun onAuthenticationError(code: Int, message: CharSequence) = onFail(message.toString())
    })
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock the locked folder")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build(),
    )
}

/** The keys after the sheet said yes, or null when the user cancelled or the phone has no lock screen. */
suspend fun unlockVault(activity: Activity): VaultKeys? = suspendCancellableCoroutine { cont ->
    val sheet = activity as? FragmentActivity
    var answered = false
    fun finish(keys: VaultKeys?) {
        if (answered) return
        answered = true
        cont.resume(keys)
    }
    if (sheet == null || !canUnlockVault(activity)) { finish(null); return@suspendCancellableCoroutine }
    askVaultAuth(
        sheet,
        // Never a new master over an old key file: that would leave every locked file unreadable
        onOk = {
            val keys = runCatching { VaultKey.keys(activity) ?: if (!VaultKey.exists(activity)) VaultKey.create(activity) else null }
                .onFailure { AppLog.i("vault: the phone said yes, the key did not open: $it") }
                .getOrNull()
            AppLog.i("vault: ${if (keys != null) "unlocked" else "still locked after auth"}")
            finish(keys)
        },
        onFail = { message -> AppLog.i("vault: auth said no: $message"); finish(null) },
    )
    cont.invokeOnCancellation { answered = true }
}

/** The activity behind a composable's context. */
fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.findActivity()
    else -> null
}
