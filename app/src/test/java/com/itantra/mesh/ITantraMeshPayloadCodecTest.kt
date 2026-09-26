package com.itantra.mesh

import com.itantra.schema.ITantraMessage
import com.itantra.schema.Languages
import com.itantra.schema.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ITantraMeshPayloadCodecTest {

    private val sample = ITantraMessage(
        msgId = "abc",
        type = MessageType.VOICE_TEXT,
        srcLang = "hi",
        text = "मुझे पानी चाहिए",
        senderName = "Asha",
        senderId = "0123456789abcdef",
        deviceModel = "Pixel 7",
        lat = 12.5,
        lon = 77.25
    )

    @Test
    fun `framed payloads round trip unchanged`() {
        val decoded = ITantraMeshPayloadCodec.decode(ITantraMeshPayloadCodec.encode(sample))
        assertEquals(sample, decoded)
    }

    @Test
    fun `plain bitchat text is not stamped english`() {
        val hindi = ITantraMeshPayloadCodec.decode("मदद चाहिए".toByteArray(), "peer1", "Ravi")!!
        assertEquals(Languages.UNDETERMINED, hindi.srcLang)
        assertEquals("peer1", hindi.senderId)
        assertEquals(MessageType.TYPED_TEXT, hindi.type)

        val tamil = ITantraMeshPayloadCodec.decode("உதவி தேவை".toByteArray(), "peer2", "Kavin")!!
        assertEquals("ta", tamil.srcLang)
    }

    @Test
    fun `payloads missing required fields are rejected, not half-built`() {
        assertNull(ITantraMeshPayloadCodec.decode("EB1:{\"msgId\":\"x\",\"senderId\":\"p\"}".toByteArray()))
        assertNull(ITantraMeshPayloadCodec.decode("EB1:not json".toByteArray()))
    }

    @Test
    fun `a message type this build does not know is rejected`() {
        val future = String(ITantraMeshPayloadCodec.encode(sample), Charsets.UTF_8)
            .replace("\"VOICE_TEXT\"", "\"HOLOGRAM\"")
        assertNull(ITantraMeshPayloadCodec.decode(future.toByteArray()))
    }

    @Test
    fun `optional fields may be absent`() {
        val minimal = "EB1:{\"msgId\":\"m\",\"type\":\"TYPED_TEXT\",\"srcLang\":\"en\",\"text\":\"hi\"," +
            "\"senderName\":\"A\",\"senderId\":\"p\",\"deviceModel\":\"X\"}"
        val decoded = ITantraMeshPayloadCodec.decode(minimal.toByteArray())
        assertNotNull(decoded)
        assertNull(decoded!!.lat)
    }
}
