package com.itantra.mesh

import android.content.Context
import android.util.Log
import com.itantra.identity.IdentityManager
import com.itantra.mesh.model.BitchatMessage
import com.itantra.mesh.service.MeshServiceHolder
import com.itantra.mesh.transport.MeshDelegate
import com.itantra.mesh.transport.MeshService
import com.itantra.schema.ITantraMessage
import com.itantra.schema.MessageType
import com.itantra.schema.Peer
import com.itantra.services.meshgraph.MeshGraphService
import com.itantra.services.meshgraph.RoutePlanner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Primary high-level coordinator for iTantra mesh communication.
 *
 * Connects the UI / App layer to the underlying BLE mesh transport core.
 * Transmits ONLY text representations (STT output, typed text, alert messages), NEVER audio.
 */
class ITantraMeshManager(private val context: Context) : MeshDelegate {

    companion object {
        private const val TAG = "ITantraMeshManager"

        @Volatile
        private var instance: ITantraMeshManager? = null

        fun getInstance(context: Context): ITantraMeshManager {
            return instance ?: synchronized(this) {
                instance ?: ITantraMeshManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val identityManager = IdentityManager.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Low-level BLE mesh service from bitchat core
    private var meshService: MeshService? = null

    // Reactive peer list state
    private val _connectedPeers = MutableStateFlow<List<Peer>>(emptyList())
    val connectedPeers: StateFlow<List<Peer>> = _connectedPeers.asStateFlow()

    // Reactive incoming message stream
    private val _incomingMessages = MutableSharedFlow<ITantraMessage>(extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<ITantraMessage> = _incomingMessages.asSharedFlow()

    // Mesh running status
    private val _isMeshRunning = MutableStateFlow(false)
    val isMeshRunning: StateFlow<Boolean> = _isMeshRunning.asStateFlow()

    private val locationProvider = LocationProvider(context)

    /**
     * Distress announcements live here rather than in a screen: a phone must keep
     * relaying an SOS it is holding even with the UI closed.
     */
    val sos: SosManager by lazy { SosManager(context, this) }

    init {
        initMeshService()
    }

    private fun initMeshService() {
        try {
            val service = MeshServiceHolder.getUnifiedOrCreate(context)
            service.delegate = this
            meshService = service
            Log.i(TAG, "Shared UnifiedMeshService initialized with peerId=${service.myPeerID}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize BluetoothMeshService: ${e.message}", e)
        }
    }

    /**
     * Starts advertising and scanning on the BLE mesh.
     */
    fun startMesh() {
        if (_isMeshRunning.value) return

        try {
            val service = meshService ?: MeshServiceHolder.getUnifiedOrCreate(context).also {
                it.delegate = this
                meshService = it
            }

            service.startServices()
            _isMeshRunning.value = true
            sos.start()
            Log.i(TAG, "iTantra mesh started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start mesh: ${e.message}", e)
        }
    }

    /**
     * Stops the BLE mesh operations.
     */
    fun stopMesh() {
        try {
            meshService?.stopServices()
            sos.stop()
            _isMeshRunning.value = false
            _connectedPeers.value = emptyList()
            Log.i(TAG, "iTantra mesh stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop mesh: ${e.message}", e)
        }
    }

    /**
     * Sends an ITantraMessage over the mesh.
     *
     * @param message The message to transmit (text only)
     * @param recipientPeerId Target peer ID (null for mesh broadcast to all peers)
     */
    fun sendMessage(
        message: ITantraMessage,
        recipientPeerId: String? = null,
        secure: Boolean = false
    ): Boolean {
        val service = meshService ?: return false

        return try {
            // Encode the payload with iTantra wire header
            val wireBytes = ITantraMeshPayloadCodec.encode(message)
            val wireString = String(wireBytes, Charsets.UTF_8)

            when {
                recipientPeerId != null -> {
                    service.sendPrivateMessage(
                        content = wireString,
                        recipientPeerID = recipientPeerId,
                        recipientNickname = "peer"
                    )
                    Log.d(TAG, "Sent Noise-encrypted message to $recipientPeerId: msgId=${message.msgId}")
                }

                secure -> {
                    // Fan out as individual private messages so every copy is Noise
                    // encrypted end-to-end. Costs a handshake per peer and cannot reach
                    // peers we have no session with — that is the trade for confidentiality.
                    val peers = _connectedPeers.value
                    if (peers.isEmpty()) {
                        Log.w(TAG, "Secure send requested with no connected peers; message not transmitted")
                        return false
                    }
                    for (peer in peers) {
                        service.sendPrivateMessage(
                            content = wireString,
                            recipientPeerID = peer.peerId,
                            recipientNickname = peer.name.ifEmpty { "peer" }
                        )
                    }
                    Log.d(TAG, "Sent Noise-encrypted message to ${peers.size} peers: msgId=${message.msgId}")
                }

                else -> {
                    // Public mesh broadcast: signed and relayed up to 7 hops, but the
                    // payload is readable by any node on the mesh.
                    service.sendMessage(content = wireString)
                    Log.d(TAG, "Broadcast message (signed, not encrypted): msgId=${message.msgId}")
                }
            }
            Log.d(TAG, "Sent message: msgId=${message.msgId}, type=${message.type}, secure=$secure")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message: ${e.message}", e)
            false
        }
    }

    /**
     * Convenience method to send speech-derived text.
     */
    fun sendVoiceText(
        text: String,
        srcLang: String = "hi",
        isAlert: Boolean = false,
        recipientPeerId: String? = null,
        secure: Boolean = false
    ): ITantraMessage? {
        val identity = identityManager.getCurrentIdentity()
            ?: identityManager.getOrCreateIdentity("User")

        val message = ITantraMessage(
            v = 1,
            msgId = UUID.randomUUID().toString(),
            type = if (isAlert) MessageType.ALERT else MessageType.VOICE_TEXT,
            srcLang = srcLang,
            text = text,
            senderName = identity.displayName,
            senderId = identity.peerId,
            deviceModel = identity.deviceModel,
            isAlert = isAlert,
            ts = System.currentTimeMillis()
        )

        val success = sendMessage(message, recipientPeerId, secure)
        return if (success) message else null
    }

    /**
     * Convenience method to send typed text.
     */
    fun sendTypedText(
        text: String,
        srcLang: String = "hi",
        isAlert: Boolean = false,
        recipientPeerId: String? = null,
        secure: Boolean = false
    ): ITantraMessage? {
        val identity = identityManager.getCurrentIdentity()
            ?: identityManager.getOrCreateIdentity("User")

        val message = ITantraMessage(
            v = 1,
            msgId = UUID.randomUUID().toString(),
            type = if (isAlert) MessageType.ALERT else MessageType.TYPED_TEXT,
            srcLang = srcLang,
            text = text,
            senderName = identity.displayName,
            senderId = identity.peerId,
            deviceModel = identity.deviceModel,
            isAlert = isAlert,
            ts = System.currentTimeMillis()
        )

        val success = sendMessage(message, recipientPeerId, secure)
        return if (success) message else null
    }

    /**
     * Raises a distress announcement: broadcast, no recipient, carrying coordinates when
     * a fix was available. Always sent in the clear on the public mesh — a distress call
     * that only reachable peers with an established Noise session could read would defeat
     * its own purpose.
     */
    fun sendSos(
        text: String,
        srcLang: String,
        lat: Double?,
        lon: Double?,
        accuracy: Float?,
        expiresAt: Long
    ): ITantraMessage? {
        val identity = identityManager.getCurrentIdentity()
            ?: identityManager.getOrCreateIdentity("User")

        val message = ITantraMessage(
            v = 1,
            msgId = UUID.randomUUID().toString(),
            type = MessageType.SOS,
            srcLang = srcLang,
            text = text,
            senderName = identity.displayName,
            senderId = identity.peerId,
            deviceModel = identity.deviceModel,
            isAlert = true,
            ts = System.currentTimeMillis(),
            lat = lat,
            lon = lon,
            gpsAccuracyM = accuracy,
            expiresAt = expiresAt
        )

        return if (sendMessage(message, recipientPeerId = null, secure = false)) message else null
    }

    /** Re-broadcasts an announcement this device is holding, unchanged. */
    fun rebroadcastSos(message: ITantraMessage): Boolean {
        if (message.isExpired()) return false
        return sendMessage(message, recipientPeerId = null, secure = false)
    }

    /** Tells the mesh that [original] has been resolved and must stop propagating. */
    fun sendSosResolved(original: ITantraMessage): Boolean {
        val identity = identityManager.getCurrentIdentity()
            ?: identityManager.getOrCreateIdentity("User")

        val message = ITantraMessage(
            v = 1,
            msgId = UUID.randomUUID().toString(),
            type = MessageType.SOS_RESOLVED,
            srcLang = original.srcLang,
            text = "Resolved",
            senderName = identity.displayName,
            senderId = identity.peerId,
            deviceModel = identity.deviceModel,
            ts = System.currentTimeMillis(),
            refMsgId = original.msgId
        )
        return sendMessage(message, recipientPeerId = null, secure = false)
    }

    // --- BluetoothMeshDelegate implementation ---

    override fun didReceiveMessage(message: BitchatMessage) {
        val payloadBytes = message.content.toByteArray(Charsets.UTF_8)
        val decoded = ITantraMeshPayloadCodec.decode(
            payloadBytes = payloadBytes,
            fallbackSenderId = message.senderPeerID ?: message.sender,
            fallbackSenderName = message.sender
        )

        if (decoded == null) return

        Log.d(TAG, "Decoded incoming ITantraMessage from ${decoded.senderName} (${decoded.deviceModel}): ${decoded.text}")

        // Expired distress announcements are dropped rather than shown or relayed.
        if (decoded.isExpired()) {
            Log.i(TAG, "Dropping expired message ${decoded.msgId} (type=${decoded.type})")
            return
        }

        // Range gate: hop count always applies, GPS narrows it when both ends have a fix.
        val hops = senderHops(decoded.senderId)
        val verdict = RangePolicy.evaluate(hops, decoded.origin(), locationProvider.lastKnown())
        if (verdict is RangePolicy.Verdict.OutOfRange) {
            RangePolicy.logDrop("message ${decoded.msgId} from ${decoded.senderName}", verdict)
            return
        }

        // Distress announcements are held and relayed by this device, not just displayed.
        if (decoded.type == MessageType.SOS || decoded.type == MessageType.SOS_RESOLVED) {
            sos.onReceived(decoded)
        }

        scope.launch {
            _incomingMessages.emit(decoded)
        }
    }

    /** Hops to [peerId] from the gossip graph, or null when it cannot be determined. */
    private fun senderHops(peerId: String): Int? = try {
        _connectedPeers.value.firstOrNull { it.peerId == peerId }?.hops
    } catch (e: Exception) {
        null
    }

    override fun didUpdatePeerList(peers: List<String>) {
        updatePeerList()
    }

    override fun didReceiveChannelLeave(channel: String, fromPeer: String) {
        Log.d(TAG, "Peer $fromPeer left channel $channel")
    }

    override fun didReceiveDeliveryAck(messageID: String, recipientPeerID: String) {
        Log.d(TAG, "Delivery ack received for message $messageID from $recipientPeerID")
    }

    override fun didReceiveReadReceipt(messageID: String, recipientPeerID: String) {
        Log.d(TAG, "Read receipt received for message $messageID from $recipientPeerID")
    }

    override fun didReceiveVerifyChallenge(peerID: String, payload: ByteArray, timestampMs: Long) {
        Log.d(TAG, "Verify challenge from $peerID")
    }

    override fun didReceiveVerifyResponse(peerID: String, payload: ByteArray, timestampMs: Long) {
        Log.d(TAG, "Verify response from $peerID")
    }

    override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? {
        return null
    }

    override fun getNickname(): String? {
        return identityManager.getCurrentIdentity()?.displayName
    }

    override fun isFavorite(peerID: String): Boolean {
        return false
    }

    /**
     * Builds the peer list from two sources:
     *  - **Direct peers** we hold a BLE link to, reported by the transport (hops = 1).
     *  - **Multi-hop peers** learned from bitchat's announcement gossip, with the hop
     *    count taken from the shortest path through [MeshGraphService]'s graph.
     *
     * Anything beyond [RangePolicy.MAX_HOPS] is dropped, so the list stays bounded to
     * the incident area rather than growing with the whole mesh.
     *
     * Note the graph's reach is itself limited by how far announcements propagate, so
     * observed hop counts will not exceed the announcement TTL regardless of the policy
     * ceiling.
     */
    private fun updatePeerList() {
        val service = meshService ?: return
        val nicknames = service.getPeerNicknames()
        val rssiMap = service.getPeerRSSI()

        val direct = nicknames.map { (peerId, nickname) ->
            val peerInfo = service.getPeerInfo(peerId)
            Peer(
                peerId = peerId,
                name = nickname.ifEmpty { "Peer ${peerId.take(4)}" },
                deviceModel = peerInfo?.deviceModel.orEmpty(),
                hops = 1,
                lastSeen = peerInfo?.lastSeen ?: System.currentTimeMillis(),
                rssi = rssiMap[peerId]
            )
        }

        val directIds = direct.map { it.peerId }.toSet()
        val myId = service.myPeerID

        val remote = try {
            MeshGraphService.getInstance().graphState.value.nodes
                .asSequence()
                .filter { it.peerID != myId && it.peerID !in directIds }
                .mapNotNull { node ->
                    // shortestPath includes both endpoints, so hops = edges = size - 1.
                    val path = RoutePlanner.shortestPath(myId, node.peerID) ?: return@mapNotNull null
                    val hops = path.size - 1
                    if (hops <= 1 || hops > RangePolicy.MAX_HOPS) return@mapNotNull null
                    Peer(
                        peerId = node.peerID,
                        name = node.nickname?.takeIf { it.isNotBlank() }
                            ?: "Peer ${node.peerID.take(4)}",
                        deviceModel = "",
                        hops = hops,
                        lastSeen = System.currentTimeMillis(),
                        rssi = null
                    )
                }
                .toList()
        } catch (e: Exception) {
            Log.e(TAG, "Mesh graph unavailable, showing direct peers only: ${e.message}")
            emptyList()
        }

        val all = (direct + remote).sortedWith(compareBy({ it.hops }, { it.name.lowercase() }))
        if (remote.isNotEmpty()) {
            Log.d(TAG, "Peers: ${direct.size} direct, ${remote.size} multi-hop")
        }
        _connectedPeers.value = all
    }
}
