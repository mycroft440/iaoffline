package com.example.ialocal.models

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
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

    @Test
    fun sizesPerLayerEmbeddingsThatStayInTheFile() {
        val header = ByteArrayOutputStream().apply {
            write(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()))
            u32(3)
            u64(3)
            u64(1)
            kvString("general.architecture", "gemma4")
            tensor("token_embd.weight", offset = 0)
            tensor("per_layer_token_embd.weight", offset = 1024)
            tensor("output_norm.weight", offset = 5120)
        }.toByteArray()
        val dataStart = (header.size + 31) / 32 * 32
        val fileSize = dataStart + 6144L

        val metadata = inspector.inspect(ByteArrayInputStream(header), totalBytes = fileSize)

        assertEquals(4096L, metadata.onDemandBytes)
        // Stored last, it ends at the end of the file.
        val last = ByteArrayOutputStream().apply {
            write(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()))
            u32(3)
            u64(2)
            u64(0)
            tensor("token_embd.weight", offset = 0)
            tensor("per_layer_token_embd.weight", offset = 2048)
        }.toByteArray()
        val lastStart = (last.size + 31) / 32 * 32
        assertEquals(1000L, inspector.inspect(ByteArrayInputStream(last), totalBytes = lastStart + 3048L).onDemandBytes)
    }

    @Test
    fun modelsWithoutOnDemandTensorsKeepEverythingInRam() {
        val bytes = ByteArrayOutputStream().apply {
            write(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()))
            u32(3)
            u64(1)
            u64(0)
            tensor("token_embd.weight", offset = 0)
        }.toByteArray()
        assertEquals(0L, inspector.inspect(ByteArrayInputStream(bytes), totalBytes = 4096L).onDemandBytes)
    }

    /** Runs on a real Gemma 4 E2B/E4B GGUF when IAOFFLINE_GEMMA4_FILE points to one. */
    @Test
    fun realGemma4FileKeepsItsPerLayerEmbeddingsOnStorage() {
        val path = System.getenv("IAOFFLINE_GEMMA4_FILE")
        Assume.assumeTrue("IAOFFLINE_GEMMA4_FILE not set", path != null)
        val file = File(requireNotNull(path))

        val metadata = inspector.inspect(file)

        println("${file.name}: ${metadata.onDemandBytes} of ${file.length()} bytes read on demand")
        assertEquals("gemma4", metadata.architecture)
        assertTrue(metadata.onDemandBytes > file.length() / 3)
        assertTrue(metadata.onDemandBytes < file.length() * 2 / 3)
    }

    @Test(expected = IOException::class)
    fun rejectsNonGgufMagic() {
        inspector.inspect(ByteArrayInputStream("NOPE".toByteArray()))
    }

    private fun ByteArrayOutputStream.tensor(name: String, offset: Long) {
        string(name)
        u32(2)
        u64(256)
        u64(4)
        u32(8) // Q8_0
        u64(offset)
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
