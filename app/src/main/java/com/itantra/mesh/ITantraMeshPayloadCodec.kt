package com.itantra.mesh

import android.util.Log
import com.itantra.schema.ITantraMessage
import com.itantra.schema.MessageType
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.nio.charset.StandardCharsets

/**
 * Codec for packing and unpacking ITantraMessage payloads across the mesh transport.
 *
 * Payload wire framing:
 * - Magic header: "EB1:" prefix to distinguish iTantra payloads from raw legacy text.
 * - JSON payload serialized as UTF-8 bytes.
 *
 * Fallback:
 * If an incoming payload does not have the "EB1:" prefix (e.g. from legacy bitchat client),
 * it is safely parsed as plain typed text.
 */
object ITantraMeshPayloadCodec {

    private const val TAG = "ITantraPayloadCodec"
    private const val MAGIC_PREFIX = "EB1:"
    private val gson: Gson = GsonBuilder().create()

    /**
     * Encodes an ITantraMessage into wire bytes for transmission.
     */
    fun encode(message: ITantraMessage): ByteArray {
        val jsonString = message.toJson()
        val wireString = MAGIC_PREFIX + jsonString
        return wireString.toByteArray(StandardCharsets.UTF_8)
    }

    /**
     * Decodes wire bytes received over mesh into an ITantraMessage.
     */
    fun decode(
        payloadBytes: ByteArray,
        fallbackSenderId: String = "",
        fallbackSenderName: String = "Peer"
    ): ITantraMessage? {
        if (payloadBytes.isEmpty()) return null

        return try {
            val textContent = String(payloadBytes, StandardCharsets.UTF_8)
            if (textContent.startsWith(MAGIC_PREFIX)) {
                val json = textContent.removePrefix(MAGIC_PREFIX)
                ITantraMessage.fromJson(json)
            } else {
                // Legacy plain text fallback
                ITantraMessage(
                    v = 1,
                    type = MessageType.TYPED_TEXT,
                    srcLang = "en",
                    text = textContent,
                    senderName = fallbackSenderName,
                    senderId = fallbackSenderId,
                    deviceModel = "Mesh Node",
                    isAlert = false,
                    ts = System.currentTimeMillis()
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding iTantra payload: ${e.message}")
            null
        }
    }

    /**
     * Checks if given byte payload is an iTantra framed message.
     */
    fun isITantraPayload(payloadBytes: ByteArray): Boolean {
        if (payloadBytes.size < MAGIC_PREFIX.length) return false
        val prefixBytes = MAGIC_PREFIX.toByteArray(StandardCharsets.UTF_8)
        for (i in prefixBytes.indices) {
            if (payloadBytes[i] != prefixBytes[i]) return false
        }
        return true
    }
}
