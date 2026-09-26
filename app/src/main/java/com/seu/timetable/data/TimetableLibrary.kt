package com.seu.timetable.data

import android.content.Context
import com.seu.timetable.domain.BoardContent
import com.seu.timetable.domain.BoardIndex
import com.seu.timetable.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 课表库的**磁盘层**，仅负责单文件读写，不包含业务逻辑。
 *
 * 目录布局（`/data/data/com.seu.timetable/files/boards/`）：
 * ```
 * boards/
 *   index.json          ← BoardIndex：所有课表的元信息 + 当前选中哪张
 *   content_<id>.json   ← BoardContent：那一张的课程 / 时间块 / 未排课
 * ```
 *
 * 不使用 Room / DataStore 的原因：
 *   - 对于仅含若干课表、数百条课程的规模，引入 Room 需额外 KSP 处理器与 schema，开销过重；
 *   - DataStore(Preferences) 仅适合小体量键值对，写入数 KB 课表会触发整份重新序列化，
 *     且其 `Flow` 语义在显式读写场景下并无必要。
 *   直接写入 JSON 文件便于通过 adb pull 取出人工核对，异常时可直接定位。
 *
 * 所有写入均先写临时文件再改名（[writeAtomic]）。课表数据在磁盘上仅有一份，
 *   直接覆写时若进程被系统中断，会残留截断的半份 JSON，导致下次启动无法读取。
 *   同文件系统内的 rename 为原子操作。
 */
class TimetableLibrary(private val dir: File) {
    constructor(context: Context) : this(File(context.filesDir, BOARD_DIR))
    private val indexFile = File(dir, INDEX_FILE)

    // ---------------------------------------------------------------- 索引

    suspend fun readIndex(): BoardIndex = withContext(Dispatchers.IO) {
        if (!indexFile.exists()) return@withContext BoardIndex.EMPTY
        val text = runCatching { indexFile.readText() }.getOrElse {
            DebugLog.w("课表索引读不出来：${it.message}")
            return@withContext BoardIndex.EMPTY
        }
        try {
            JSON.decodeFromString(BoardIndex.serializer(), text)
        } catch (e: Exception) {
            // 索引损坏时不应导致应用不可用：保留坏文件副本（便于事后排查），
            //   并从空索引继续；内容文件仍完整保留于磁盘。
            runCatching { indexFile.renameTo(File(dir, "index.corrupt.json")) }
            DebugLog.e("课表索引解析失败，已改名备份为 index.corrupt.json：${e.message}")
            BoardIndex.EMPTY
        }
    }

    suspend fun writeIndex(index: BoardIndex) = withContext(Dispatchers.IO) {
        synchronized(IO_LOCK) { writeAtomic(indexFile, JSON.encodeToString(BoardIndex.serializer(), index)) }
    }

    // ---------------------------------------------------------------- 内容

    suspend fun readContent(id: String): BoardContent = withContext(Dispatchers.IO) {
        val f = contentFile(id)
        if (!f.exists()) return@withContext BoardContent.EMPTY
        val text = runCatching { f.readText() }.getOrElse { return@withContext BoardContent.EMPTY }
        try {
            JSON.decodeFromString(BoardContent.serializer(), text)
        } catch (e: Exception) {
            DebugLog.e("课表内容解析失败 id=$id：${e.message}")
            BoardContent.EMPTY
        }
    }

    suspend fun writeContent(id: String, content: BoardContent) = withContext(Dispatchers.IO) {
        synchronized(IO_LOCK) { writeAtomic(contentFile(id), JSON.encodeToString(BoardContent.serializer(), content)) }
    }

    suspend fun deleteContent(id: String) = withContext(Dispatchers.IO) {
        synchronized(IO_LOCK) { contentFile(id).delete() }
        Unit
    }

    suspend fun contentExists(id: String): Boolean = withContext(Dispatchers.IO) {
        contentFile(id).exists()
    }

    /** AI must never treat unreadable/corrupt storage as an empty timetable. */
    suspend fun activeStrict(): Pair<com.seu.timetable.domain.BoardMeta, BoardContent> = withContext(Dispatchers.IO) {
        synchronized(IO_LOCK) { activeLocked() }
    }

    private fun activeLocked(): Pair<com.seu.timetable.domain.BoardMeta, BoardContent> {
        check(indexFile.exists()) { "请先创建或选择一张课表" }
        val index = JSON.decodeFromString(BoardIndex.serializer(), indexFile.readText())
        val meta = index.meta(index.effectiveActiveId) ?: error("请先创建或选择一张课表")
        val file = contentFile(meta.id)
        check(file.exists()) { "课表文件缺失，本次未修改" }
        return meta to JSON.decodeFromString(BoardContent.serializer(), file.readText())
    }

    suspend fun compareAndSetActiveContent(
        expectedMeta: com.seu.timetable.domain.BoardMeta,
        expectedContent: BoardContent,
        next: BoardContent,
    ) = withContext(Dispatchers.IO) {
        synchronized(IO_LOCK) {
            val (meta, content) = activeLocked()
            check(meta == expectedMeta && content == expectedContent) { "课表、作息或当前选择已改变，本次未执行，请重新发送" }
            // One atomic rename commits courses and the undo receipt together.
            // No later index write can turn an already committed operation into a reported failure.
            writeAtomic(contentFile(meta.id), JSON.encodeToString(BoardContent.serializer(), next))
        }
    }

    // ---------------------------------------------------------------- 工具

    /**
     * 用于生成新文件名的安全 id。
     *
     * `b` 前缀 + 毫秒时间戳 + 3 位随机数。时间戳前缀使 `ls` 结果按创建时间
     *   自然排序，便于人工排查；随机后缀避免同毫秒内重复"复制"导致文件名冲突。
     */
    fun newId(): String =
        "b${System.currentTimeMillis()}${(100..999).random()}"

    private fun contentFile(id: String): File {
        require(id.isNotBlank() && id.none { it == '/' || it == '\\' || it == '\u0000' }) { "无效课表标识" }
        return File(dir, "content_$id.json")
    }

    private fun writeAtomic(target: File, text: String) {
        if (!dir.exists() && !dir.mkdirs()) {
            error("建不了课表目录：${dir.absolutePath}")
        }
        val tmp = File(dir, "${target.name}.tmp")
        try {
            FileOutputStream(tmp).use { stream -> stream.write(text.toByteArray(Charsets.UTF_8)); stream.fd.sync() }
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { tmp.delete() }
    }

    private companion object {
        val IO_LOCK = Any()
        const val BOARD_DIR = "boards"
        const val INDEX_FILE = "index.json"

        /**
         * `encodeDefaults = true` 与 `prettyPrint = true` 为刻意设置：
         *   以数 KB 体积换取 adb 可直接读取；新增字段时老文件可见默认值，减少排查成本。
         *   `ignoreUnknownKeys` 确保未来删改字段时老文件仍可解析。
         */
        val JSON = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}
