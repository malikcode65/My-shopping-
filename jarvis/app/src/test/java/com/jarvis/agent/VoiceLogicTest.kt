package com.jarvis.agent

import com.jarvis.agent.core.MultiPressDetector
import com.jarvis.agent.voice.EndOfSpeechDetector
import com.jarvis.agent.voice.LanguageDetector
import com.jarvis.agent.voice.SpokenLanguage
import com.jarvis.agent.voice.Wav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceLogicTest {

    @Test
    fun detectsUrduScript() {
        assertEquals(SpokenLanguage.URDU, LanguageDetector.detect("امی کو میسج بھیج دو کہ میں دیر سے آؤں گا"))
    }

    @Test
    fun detectsRomanUrdu() {
        assertEquals(SpokenLanguage.ROMAN_URDU, LanguageDetector.detect("ammi ko message bhej do ke main late hoon"))
        assertEquals(SpokenLanguage.ROMAN_URDU, LanguageDetector.detect("kal subah 7 baje alarm laga do"))
    }

    @Test
    fun detectsEnglish() {
        assertEquals(SpokenLanguage.ENGLISH, LanguageDetector.detect("Send an email to Ali about the meeting tomorrow"))
        assertEquals(SpokenLanguage.ENGLISH, LanguageDetector.detect(""))
    }

    @Test
    fun volumeDownThreeTimesTriggersOnlyInsideWindow() {
        val d = MultiPressDetector(presses = 3, windowMs = 1500)
        assertFalse(d.onPress(0))
        assertFalse(d.onPress(400))
        assertTrue(d.onPress(900))
        // resets after triggering
        assertFalse(d.onPress(1000))
        // slow presses never trigger
        val slow = MultiPressDetector()
        assertFalse(slow.onPress(0))
        assertFalse(slow.onPress(1000))
        assertFalse(slow.onPress(2000))
        assertFalse(slow.onPress(3000))
    }

    @Test
    fun endOfSpeechAfterTrailingSilence() {
        val d = EndOfSpeechDetector(calibrationMs = 100, trailingSilenceMs = 200)
        repeat(5) { assertEquals(EndOfSpeechDetector.State.CONTINUE, d.onFrame(50.0, 20)) } // calibrate
        repeat(10) { assertEquals(EndOfSpeechDetector.State.CONTINUE, d.onFrame(3000.0, 20)) } // speech
        repeat(9) { assertEquals(EndOfSpeechDetector.State.CONTINUE, d.onFrame(60.0, 20)) }
        assertEquals(EndOfSpeechDetector.State.DONE, d.onFrame(60.0, 20))
    }

    @Test
    fun noSpeechTimesOut() {
        val d = EndOfSpeechDetector(calibrationMs = 100, noSpeechTimeoutMs = 400)
        var last = EndOfSpeechDetector.State.CONTINUE
        repeat(20) { last = d.onFrame(30.0, 20) }
        assertEquals(EndOfSpeechDetector.State.NO_SPEECH, last)
    }

    @Test
    fun wavHeaderIsValid() {
        val wav = Wav.fromPcm16Mono(ByteArray(100), 16000)
        assertEquals(144, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals("data", String(wav, 36, 4))
        assertEquals(100, (wav[40].toInt() and 0xFF) or ((wav[41].toInt() and 0xFF) shl 8))
    }
}
