package com.seu.timetable.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.seu.timetable.util.DebugLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.credentialDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "seu_credentials")

/** 校园账号凭据。仅在内存中以明文存在，落盘前密码必须经过加密。 */
data class Credentials(val username: String, val password: String)

/**
 * 校园账号的加密存储。
 *
 * 设计取向：用户选择"真正全自动"，故密码须留在本机；留存密码只以密文落盘，密钥存于硬件。
 *
 * 实现要点：
 * - 密钥由 Android Keystore 生成（AES-256-GCM），永不出 Keystore；App 仅可借其加解密，无法取得原始字节。
 * - 明文以 AES/GCM/NoPadding 加密，随机 12 字节 IV 与密文一并存储，格式为 base64(IV || 密文 || GCM Tag)；GCM 自带完整性校验，密文被篡改即解密失败。
 * - 不设 setUserAuthenticationRequired，以免每次解密均须指纹/锁屏，与后台自动登录冲突。
 *
 * 仅加密密码，学号明文保存（学号非机密，界面需展示"已保存 xxx"）；密码绝不落明文。
 *
 * 解密失败按"无凭据"处理（返回 null），不抛异常；常见于清除应用数据、换机恢复或 Keystore 密钥作废，此时应引导用户重新登录。
 */
class CredentialStore(private val context: Context) {

    /** 已保存的学号（明文），界面用于展示"已保存 xxx"。 */
    val savedUsername: Flow<String?> =
        context.credentialDataStore.data.map { it[KEY_USERNAME] }

    /** 是否已保存密码，用于决定是否走自动登录。 */
    val hasPassword: Flow<Boolean> =
        context.credentialDataStore.data.map { !it[KEY_PASSWORD_ENC].isNullOrBlank() }

    /** 自动登录开关，默认关闭；是否在本机留存密码由用户自行决定。 */
    val autoLoginEnabled: Flow<Boolean> =
        context.credentialDataStore.data.map { it[KEY_AUTO_LOGIN] ?: false }

    /**
     * 保存账号密码，密码在落盘前立即加密。
     * @throws IllegalStateException Keystore 不可用（极少见）
     */
    suspend fun save(username: String, password: String) {
        val enc = encrypt(password)
        context.credentialDataStore.edit {
            it[KEY_USERNAME] = username.trim()
            it[KEY_PASSWORD_ENC] = enc
        }
        DebugLog.i("凭据已加密保存：学号=${username.trim()}，密文 ${enc.length} 字符（AES-256-GCM）")
    }

    /**
     * 读取可用凭据。任一步失败均返回 null，调用方应退回到手动登录。
     *
     * 此处不打印日志（尤其不打印学号以外的内容）：本方法会被自动登录频繁调用，
     * 多一行日志即多一分敏感信息写入 logcat 的风险。
     */
    suspend fun load(): Credentials? {
        val prefs = context.credentialDataStore.data.first()
        val user = prefs[KEY_USERNAME]?.takeIf { it.isNotBlank() } ?: return null
        val enc = prefs[KEY_PASSWORD_ENC]?.takeIf { it.isNotBlank() } ?: return null
        val pwd = decrypt(enc) ?: run {
            DebugLog.w("凭据解密失败（Keystore 密钥可能已作废）→ 当作未保存，需重新登录")
            return null
        }
        return Credentials(user, pwd)
    }

    /** 清空账号密码，并同时关闭自动登录（避免开关开启却无凭据的矛盾状态）。 */
    suspend fun clear() {
        context.credentialDataStore.edit {
            it.remove(KEY_USERNAME)
            it.remove(KEY_PASSWORD_ENC)
            it[KEY_AUTO_LOGIN] = false
        }
        DebugLog.i("凭据已清空，自动登录已关闭")
    }

    suspend fun setAutoLogin(enabled: Boolean) =
        context.credentialDataStore.edit { it[KEY_AUTO_LOGIN] = enabled }

    // 加解密

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        // 须保留 IV 否则无法解密；GCM 的 IV 无需保密，可与密文一并存储。
        return Base64.encodeToString(cipher.iv + body, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String? = runCatching {
        val bytes = Base64.decode(blob, Base64.NO_WRAP)
        if (bytes.size <= IV_BYTES) return@runCatching null
        val iv = bytes.copyOfRange(0, IV_BYTES)
        val body = bytes.copyOfRange(IV_BYTES, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(body), Charsets.UTF_8)
    }.getOrNull()

    /**
     * 获取（或首次生成）Keystore 中的 AES 密钥。
     *
     * `KeyStore.getInstance("AndroidKeyStore")` 的 `load(null)` 为必需调用
     * （参数即 null，不可省略），否则 `getEntry` 会抛出 KeyStoreException。
     * 生成参数须写全：GCM 模式 + NoPadding + 256 位。遗漏任一项，
     * 后续 `Cipher.getInstance("AES/GCM/NoPadding")` 将报
     * "AEADBadTagException" 或 "InvalidAlgorithmParameterException"。
     */
    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        DebugLog.i("已在 Android Keystore 生成 AES-256-GCM 密钥（别名 $KEY_ALIAS）")
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "seu_credential_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val IV_BYTES = 12

        val KEY_USERNAME = stringPreferencesKey("cred_username")
        val KEY_PASSWORD_ENC = stringPreferencesKey("cred_password_enc")
        val KEY_AUTO_LOGIN = booleanPreferencesKey("auto_login_enabled")
    }
}
