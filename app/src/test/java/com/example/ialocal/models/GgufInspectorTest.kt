package com.example.ialocal.models

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GgufInspectorTest {
    private val inspector = GgufInspector()

    @Test
    fun readsCoreMetadataWithoutTensorPayload() {
        val bytes = ByteArrayOutputStream().apply {
            write(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()))
            u32(3)
            u64(42)
            u64(5)
            kvString("general.name", "Qwen Test")
            kvString("general.architecture", "qwen2")
            kvString("general.size_label", "1.5B")
            kvU32("qwen2.context_length", 32768)
            kvString("tokenizer.chat_template", "{{ messages }}")
        }.toByteArray()

        val metadata = inspector.inspect(ByteArrayInputStream(bytes))

        assertEquals(3, metadata.version)
        assertEquals(42L, metadata.tensorCount)
        assertEquals("Qwen Test", metadata.name)
        assertEquals("qwen2", metadata.architecture)
        assertEquals("1.5B", metadata.sizeLabel)
        assertEquals(32768, metadata.contextLength)
        assertTrue(metadata.hasChatTemplate)
    }

    @Test(expected = IOException::class)
    fun rejectsNonGgufMagic() {
        inspector.inspect(ByteArrayInputStream("NOPE".toByteArray()))
    }

    private fun ByteArrayOutputStream.kvString(key: String, value: String) {
        string(key)
        u32(8)
        string(value)
    }

    private fun ByteArrayOutputStream.kvU32(key: String, value: Int) {
        string(key)
        u32(4)
        u32(value.toLong())
    }

    private fun ByteArrayOutputStream.string(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        u64(bytes.size.toLong())
        write(bytes)
    }

    private fun ByteArrayOutputStream.u32(value: Int) = u32(value.toLong())

    private fun ByteArrayOutputStream.u32(value: Long) {
        repeat(4) { write(((value ushr (it * 8)) and 0xff).toInt()) }
    }

    private fun ByteArrayOutputStream.u64(value: Long) {
        repeat(8) { write(((value ushr (it * 8)) and 0xff).toInt()) }
    }
}
