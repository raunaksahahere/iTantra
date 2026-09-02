package com.itantra.schema

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

/**
 * Message types carried in the iTantra mesh payload.
 */
@Parcelize
enum class MessageType : Parcelable {
    @SerializedName("VOICE_TEXT")
    VOICE_TEXT,

    @SerializedName("TYPED_TEXT")
    TYPED_TEXT,

    @SerializedName("ALERT")
    ALERT,

    @SerializedName("SYSTEM")
    SYSTEM
}
