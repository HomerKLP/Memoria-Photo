package app.memoria

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
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
 * The vault's master key: 64 random bytes, wrapped by an Android Keystore key that needs the phone's
 * own lock (a fingerprint or its PIN) and stays open for 30 s after. No app PIN to forget or type twice.
 */
object VaultKey {
    private const val alias = "memoria-vault"
    private const val window = 30 // seconds the key stays usable after the sheet says yes

    fun file(context: Context) = File(context.filesDir, "vault/key")

    fun exists(context: Context) = file(context).exists()

    /** The keys, or null when the phone has to ask for the fingerprint or PIN first (call again after). */
    fun keys(context: Context): VaultKeys? = runCatching { VaultKeys(unwrap(context)) }.getOrNull()

    /** Makes the Keystore key and the master on first use. Needs the auth window the sheet just opened. */
    fun create(context: Context): VaultKeys {
        val master = VaultKeys.random()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret())
        val iv = cipher.iv
        val wrapped = cipher.doFinal(master)
        file(context).apply { parentFile?.mkdirs() }.writeBytes(iv + wrapped)
        return VaultKeys(master)
    }

    private fun unwrap(context: Context): ByteArray {
        val blob = file(context).readBytes()
        if (blob.size < 12 + 64) throw java.io.IOException("not a wrapped key")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, blob, 0, 12))
        return cipher.doFinal(blob, 12, blob.size - 12)
    }

    private fun secret(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                // A fingerprint, or the phone's PIN or pattern: the sheet offers both
                .setUserAuthenticationParameters(window, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
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
