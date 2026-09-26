package com.seu.timetable.ai

import com.seu.timetable.data.TimetableLibrary
import com.seu.timetable.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.LocalDate

class AiBoardCodecTest {
    private val meta = BoardMeta("native-test", "秋学期", BoardSource.MANUAL,
        TermContext("manual-test", firstMonday=LocalDate.of(2026,9,21),totalWeeks=18,lastTeachingWeek=16))
    private val original = BoardContent(
        listOf(Course("c1","数学", "王老师", "CODE", "02",3.5,"带计算器",7)),
        listOf(CourseSession("s1","c1",2,1,2,setOf(1,3,5),"礼东")),
        listOf(UnplacedCourse("社会实践","张老师",2.0,32,"1-16","U01")))
    private fun board(value: BoardContent=original)=AiBoardCodec.snapshot(meta,value,2,LocalDate.of(2026,9,26)).getValue("board").jsonObject
    @Test fun `adapter round trip preserves all native fields including unplaced data`() {
        val converted = AiBoardCodec.content(board(),meta)
        assertEquals(original,converted.copy(aiState=null))
        val snapshot=AiBoardCodec.snapshot(meta,converted,2,LocalDate.of(2026,9,26))
        assertEquals("2026-09-26",snapshot.getValue("date").jsonPrimitive.content)
        assertEquals(2,snapshot.getValue("viewedWeek").jsonPrimitive.int)
        assertEquals(13,snapshot.getValue("periods").jsonArray.size)
        assertEquals(board(),snapshot.getValue("board"))
    }
    @Test fun `adapter rejects invalid dates orphan sessions and duplicate IDs`() {
        val good=board()
        for (patch in listOf("day" to JsonPrimitive(8),"end" to JsonPrimitive(14),"weeks" to JsonArray(listOf(JsonPrimitive(19))),"courseId" to JsonPrimitive("missing"))) {
            val invalid=JsonObject(good+("sessions" to JsonArray(listOf(JsonObject(good.getValue("sessions").jsonArray[0].jsonObject+patch)))))
            assertThrows(IllegalArgumentException::class.java){AiBoardCodec.content(invalid,meta)}
        }
        val duplicated=JsonObject(good+("courses" to JsonArray(good.getValue("courses").jsonArray+good.getValue("courses").jsonArray)))
        assertThrows(IllegalArgumentException::class.java){AiBoardCodec.content(duplicated,meta)}
        assertThrows(IllegalArgumentException::class.java){AiBoardCodec.content(JsonObject(good+("id" to JsonPrimitive("other"))),meta)}
    }
    @Test fun `atomic content and receipt persist and stale or switched board fails without mutation`() = runBlocking {
        val directory=Files.createTempDirectory("seu-ai-atomic").toFile()
        try {
            val library=TimetableLibrary(directory)
            val index=BoardIndex(meta.id,listOf(meta))
            library.writeIndex(index);library.writeContent(meta.id,original)
            val next=original.copy(courses=original.courses+Course("new","CPP"),aiState=buildJsonObject { put("aiRequestIds",JsonArray(listOf(JsonPrimitive("request-1"))));put("aiLastOperation",buildJsonObject { put("id","request-1") }) })
            library.compareAndSetActiveContent(meta,original,next)
            assertEquals(next,TimetableLibrary(directory).activeStrict().second)
            assertFails { library.compareAndSetActiveContent(meta,original,BoardContent.EMPTY) }
            assertEquals(next,library.activeStrict().second)
            val other=meta.copy(id="other")
            library.writeContent(other.id,BoardContent.EMPTY);library.writeIndex(BoardIndex(other.id,listOf(meta,other)))
            assertFails { library.compareAndSetActiveContent(meta,next,BoardContent.EMPTY) }
            assertEquals(next,library.readContent(meta.id))
            library.writeIndex(index.copy(boards=listOf(meta.copy(schedule=PeriodTimes.SEU))))
            assertFails { library.compareAndSetActiveContent(meta,next,BoardContent.EMPTY) }
            assertEquals(next,library.readContent(meta.id))
        } finally { cleanup(directory) }
    }
    @Test fun `write failure does not claim success or truncate previous target`() = runBlocking {
        val directory=Files.createTempDirectory("seu-ai-failed-write").toFile()
        try {
            val library=TimetableLibrary(directory)
            library.writeIndex(BoardIndex(meta.id,listOf(meta)));library.writeContent(meta.id,original)
            // An occupied temporary path reproduces an I/O failure without platform permission assumptions.
            val obstruction=java.io.File(directory,"content_${meta.id}.json.tmp").apply{mkdir()}
            java.io.File(obstruction,"occupied").writeText("test")
            assertFails { library.compareAndSetActiveContent(meta,original,BoardContent.EMPTY) }
            assertEquals(original,library.activeStrict().second)
        } finally { cleanup(directory) }
    }
    private fun cleanup(directory: java.io.File) {
        require(directory.canonicalFile.parentFile == java.io.File(requireNotNull(System.getProperty("java.io.tmpdir"))).canonicalFile && directory.name.startsWith("seu-ai-"))
        directory.deleteRecursively()
    }
    private suspend fun assertFails(block:suspend ()->Unit) {
        try { block();fail("Expected rejection") } catch (e:Exception) { assertNotNull(e.message) }
    }
}
