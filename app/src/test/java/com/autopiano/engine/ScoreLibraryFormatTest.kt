package com.autopiano.engine

import java.lang.reflect.InvocationTargetException
import org.junit.Assert.*
import org.junit.Test

/** 格式校验不依赖文件选择器或 Android Context。 */
class ScoreLibraryFormatTest {
    private fun validate(name: String, bytes: ByteArray): Boolean {
        val method = ScoreLibrary.javaClass.getDeclaredMethod("validateFormat", String::class.java, ByteArray::class.java)
        method.isAccessible = true
        return try {
            method.invoke(ScoreLibrary, name, bytes) as Boolean
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun midiHeader() = byteArrayOf(
        0x4D, 0x54, 0x68, 0x64, 0, 0, 0, 6, 0, 1, 0, 1, 0x01, 0xE0.toByte()
    )

    @Test fun midiHeaderIsRecognizedEvenWithTxtExtension() {
        assertTrue(validate("renamed.txt", midiHeader()))
    }
    @Test fun uppercaseMidiExtensionIsAccepted() {
        assertTrue(validate("song.MIDI", midiHeader()))
    }
    @Test fun utf8JianpuIsAccepted() {
        assertFalse(validate("song.txt", "BPM=100\n1 2 3 0".toByteArray()))
    }
    @Test fun utf8BomIsAccepted() {
        assertFalse(validate("song.txt", "\uFEFF1 2 3".toByteArray()))
    }
    @Test(expected = IllegalArgumentException::class)
    fun emptyTxtIsRejected() { validate("song.txt", byteArrayOf()) }
    @Test(expected = IllegalArgumentException::class)
    fun whitespaceOnlyTxtIsRejected() { validate("song.txt", "\uFEFF \n\t".toByteArray()) }
    @Test(expected = IllegalArgumentException::class)
    fun invalidMidiHeaderIsRejected() { validate("song.mid", "not-midi".toByteArray()) }
    @Test(expected = IllegalArgumentException::class)
    fun truncatedMidiIsRejected() { validate("song.mid", "MThd".toByteArray()) }
    @Test(expected = IllegalArgumentException::class)
    fun unsupportedExtensionIsRejected() { validate("song.pdf", midiHeader()) }
    @Test(expected = IllegalArgumentException::class)
    fun malformedUtf8IsRejected() { validate("song.txt", byteArrayOf(0xC3.toByte(), 0x28)) }
    @Test(expected = IllegalArgumentException::class)
    fun binaryControlCharactersAreRejected() { validate("song.txt", byteArrayOf(49, 0, 50)) }
}
