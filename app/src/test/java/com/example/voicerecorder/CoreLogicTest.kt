package com.example.voicerecorder

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreLogicTest {

    @Test
    fun wavFileHasValidHeaderAndLength() {
        val f = File.createTempFile("wavtest", ".wav")
        WavWriter(f).use { w ->
            repeat(400) { w.write(ByteArray(3200)) } // 40 s -> triggers periodic header patches
        }
        val bytes = f.readBytes()
        val data = 400 * 3200
        assertEquals(44 + data, bytes.size)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals(36 + data, b.getInt(4))
        assertEquals(16_000, b.getInt(24))
        assertEquals(data, b.getInt(40))
        f.delete()
    }

    @Test
    fun transcriptCommitsAndKeepsPendingPartial() {
        val t = TranscriptBuilder()
        t.setPartial("hello wor", 1000)
        t.commit("hello world", 1500)
        t.setPartial("second bit", 2000)
        t.commitPendingPartial() // e.g. the session died before a final arrived
        assertEquals("hello world\nsecond bit", t.fullText())
        assertEquals(listOf(1000L, 1500L, 2000L), t.updateTimesMs())
    }

    @Test
    fun failurePolicyGivesUpOnlyAfterRepeatedQuickFailures() {
        val p = com.example.voicerecorder.engine.FailurePolicy(maxConsecutive = 3, healthyMs = 30_000)
        assertFalse(p.onSessionEnded(2_000))
        assertFalse(p.onSessionEnded(2_000))
        assertFalse(p.onSessionEnded(45_000)) // a long-lived session resets the streak
        assertFalse(p.onSessionEnded(2_000))
        assertFalse(p.onSessionEnded(2_000))
        assertTrue(p.onSessionEnded(2_000))
    }

    @Test
    fun pauseTestPassesOnlyWhenTextContinuesAfterSilence() {
        val ok = PauseTest.evaluate(listOf(3_000, 9_000, 45_000, 55_000), 60_000, 0, "x")
        assertTrue(ok.passed)
        val cutOff = PauseTest.evaluate(listOf(3_000, 9_000), 60_000, 0, "x")
        assertFalse(cutOff.passed)
        val early = PauseTest.evaluate(listOf(3_000, 45_000), 30_000, 0, "x")
        assertFalse(early.passed)
    }
}
