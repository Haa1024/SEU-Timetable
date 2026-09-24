package com.seu.timetable.domain

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.LocalDate
import java.time.LocalTime

/**
 * `java.time` 的序列化器。
 *
 * 必须自行实现的原因：kotlinx.serialization 默认不识别 `LocalDate`/`LocalTime`，
 * 直接为 data class 添加 `@Serializable` 会在编译期报
 * "Serializer has not been found for type 'LocalDate'"。
 *
 * 存储为 ISO-8601 文本（`2026-09-21` / `08:00`）而非时间戳，理由有二：
 *   1. 人类可直接阅读——本地存储为 JSON，出现问题时用 adb 拉取即可肉眼核对；
 *   2. 无时区歧义——课表的"第一周周一"是一个纯日期，不应被时区规则左右。
 */
object LocalDateSerializer : KSerializer<LocalDate> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LocalDate", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LocalDate) =
        encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString())
}

object LocalTimeSerializer : KSerializer<LocalTime> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LocalTime", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LocalTime) =
        encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): LocalTime = LocalTime.parse(decoder.decodeString())
}
