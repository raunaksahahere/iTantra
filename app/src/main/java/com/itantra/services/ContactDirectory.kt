package com.itantra.services

/**
 * Helper to canonicalize peer/conversation IDs for iTantra.
 */
object ContactDirectory {
    fun canonicalConversationId(peerId: String): String = peerId.trim()
}
