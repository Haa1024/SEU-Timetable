package com.seu.timetable.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.exifinterface.media.ExifInterface
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
private data class SavedAiSettings(val config: AiConfig = AiConfig(), val encryptedKey: String = "")

/**
 * AI 助手总开关。默认 **false**（关）。
 *
 * 存成独立字段而不是并进 [SavedAiSettings] 的 config：开关是「是否启用」，
 * 不属于「模型如何配置」，两者混在一起会让「保存模型设置」顺手改动开关状态。
 */
@Serializable
private data class SavedAiSwitch(val enabled: Boolean = false)

class AiStore(private val context: Context) {
    private val folder = File(context.noBackupFilesDir, "ai-chat").apply { mkdirs() }
    private val images = File(folder, "images").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun loadSettings(): Pair<AiConfig, String> {
        val file = AtomicFile(File(folder, "settings.json"))
        if (!file.baseFile.exists()) return AiConfig() to ""
        val saved = json.decodeFromString<SavedAiSettings>(file.readFully().decodeToString())
        return saved.config.validated() to if (saved.encryptedKey.isBlank()) "" else decrypt(saved.encryptedKey)
    }

    fun saveSettings(config: AiConfig, apiKey: String) {
        val saved = SavedAiSettings(config.validated(), if (config.rememberKey && apiKey.isNotBlank()) encrypt(apiKey) else "")
        write("settings.json", json.encodeToString(saved))
    }

    fun loadChat(): ChatDocument {
        val file = AtomicFile(File(folder, "chat.json"))
        if (!file.baseFile.exists()) return ChatDocument()
        val doc = json.decodeFromString<ChatDocument>(file.readFully().decodeToString())
        return doc.copy(messages = doc.messages.map { if (it.status == "streaming") it.copy(status = "interrupted") else it })
    }
    fun saveChat(document: ChatDocument) = write("chat.json", json.encodeToString(document))
    fun loadPosition(): ChatPosition = runCatching { json.decodeFromString<ChatPosition>(AtomicFile(File(folder, "position.json")).readFully().decodeToString()) }.getOrDefault(ChatPosition())
    fun savePosition(value: ChatPosition) = write("position.json", json.encodeToString(value))

    /**
     * 读总开关。
     *
     * 文件不存在、读坏、解密失败一律按「关」处理。默认值必须是关：
     * 读不出来时宁可 AI 不工作，也不能擅自把它打开。
     */
    fun loadEnabled(): Boolean = runCatching {
        val file = AtomicFile(File(folder, "enabled.json"))
        if (!file.baseFile.exists()) false
        else json.decodeFromString<SavedAiSwitch>(file.readFully().decodeToString()).enabled
    }.getOrDefault(false)

    fun saveEnabled(enabled: Boolean) = write("enabled.json", json.encodeToString(SavedAiSwitch(enabled)))

    private fun write(name: String, text: String) {
        val atomic = AtomicFile(File(folder, name))
        val stream = atomic.startWrite()
        try { stream.write(text.toByteArray()); atomic.finishWrite(stream) }
        catch (failure: Exception) { atomic.failWrite(stream); throw failure }
    }

    fun imageFile(image: ChatImage): File {
        require(Regex("[a-fA-F0-9-]{36}").matches(image.id)) { "图片记录无效，请重新添加" }
        return File(images, "${image.id}.jpg")
    }

    fun importImage(uri: Uri): ChatImage {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream(); val buffer = ByteArray(16384)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                require(out.size() + count <= 8 * 1024 * 1024) { "单张原图不能超过 8 MB" }
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        } ?: error("无法读取照片，请重新选择")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "照片格式无法读取" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2400) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("照片解码失败")
        val orientation = runCatching { ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
        val matrix = Matrix().apply {
            when (orientation) {
                2 -> setScale(-1f, 1f); 3 -> setRotate(180f); 4 -> setScale(1f, -1f)
                5 -> { setRotate(90f); postScale(-1f, 1f) }; 6 -> setRotate(90f)
                7 -> { setRotate(-90f); postScale(-1f, 1f) }; 8 -> setRotate(-90f)
            }
        }
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (rotated !== decoded) decoded.recycle()
        val ratio = minOf(1f, 1600f / maxOf(rotated.width, rotated.height))
        val target = Bitmap.createBitmap((rotated.width * ratio).toInt().coerceAtLeast(1), (rotated.height * ratio).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        Canvas(target).apply { drawColor(Color.WHITE); drawBitmap(rotated, null, android.graphics.Rect(0, 0, target.width, target.height), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)) }
        rotated.recycle()
        val out = ByteArrayOutputStream()
        try { check(target.compress(Bitmap.CompressFormat.JPEG, 85, out)) { "照片转换失败" } } finally { target.recycle() }
        require(out.size() <= 2300000) { "照片处理后仍过大，请选择较小的图片" }
        val image = ChatImage(UUID.randomUUID().toString(), "照片")
        imageFile(image).writeBytes(out.toByteArray())
        return image
    }

    fun removeUnusedImages(document: ChatDocument) {
        val used = (document.images + document.messages.flatMap { it.images }).map { "${it.id}.jpg" }.toSet()
        images.listFiles()?.filter { it.name !in used && it.extension == "jpg" }?.forEach { it.delete() }
    }

    private fun secret(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry("seu_ai_key_v1", null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("seu_ai_key_v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    private fun encrypt(key: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secret()) }
        return Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(key.toByteArray()))
    }
    private fun decrypt(value: String): String {
        val bytes = Base64.getDecoder().decode(value)
        require(bytes.size > 12) { "已存 API Key 无法读取，请重新填写" }
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }
            .doFinal(bytes.copyOfRange(12, bytes.size)).decodeToString()
    }
}
