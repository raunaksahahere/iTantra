package com.itantra.schema

import android.os.Parcelable
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize
import java.util.UUID

/**
 * Core application-level message payload carried inside bitchat mesh packets.
 *
 * CRITICAL CONSTRAINT:
 * Only text is ever transmitted over the mesh — NEVER audio bytes.
 */
@Parcelize
data class ITantraMessage(
    @SerializedName("v")
    val v: Int = 1,

    @SerializedName("msgId")
    val msgId: String = UUID.randomUUID().toString(),

    @SerializedName("type")
    val type: MessageType = MessageType.VOICE_TEXT,

    @SerializedName("srcLang")
    val srcLang: String = "hi",

    @SerializedName("text")
    val text: String,

    @SerializedName("senderName")
    val senderName: String,

    @SerializedName("senderId")
    val senderId: String,

    @SerializedName("deviceModel")
    val deviceModel: String,

    @SerializedName("isAlert")
    val isAlert: Boolean = false,

    @SerializedName("ts")
    val ts: Long = System.currentTimeMillis()
) : Parcelable {

    fun toJson(): String {
        return gson.toJson(this)
    }

    fun toByteArray(): ByteArray {
        return toJson().toByteArray(Charsets.UTF_8)
    }

    companion object {
        private val gson: Gson = GsonBuilder().create()

        fun fromJson(json: String): ITantraMessage? {
            return try {
                gson.fromJson(json, ITantraMessage::class.java)
            } catch (e: Exception) {
                null
            }
        }

        fun fromByteArray(bytes: ByteArray): ITantraMessage? {
            return try {
                fromJson(String(bytes, Charsets.UTF_8))
            } catch (e: Exception) {
                null
            }
        }
    }
}
