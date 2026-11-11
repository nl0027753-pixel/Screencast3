package com.screencast.mobile

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.DataChannel
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RendererCommon
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Protocolo (igual ao do site): cada transmissor abre UMA conexão WebRTC só de envio para cada
 * participante, e cada espectador responde com uma conexão só de recebimento. Assim nunca há
 * ofertas simultâneas (glare) e várias pessoas podem transmitir ao mesmo tempo.
 *   signal.payload.kind: offer | answer | ice | stop
 *   ice.dir: "b" = candidato do transmissor, "v" = candidato do espectador
 */
class MainActivity : AppCompatActivity() {
    private class Link(val pc: PeerConnection) { var remoteSet = false; val pending = mutableListOf<IceCandidate>() }
    private class Tile(val view: View, val renderer: SurfaceViewRenderer, val track: VideoTrack)

    private companion object {
        // Servidor de sinalização embutido (o mesmo do site). Não é preciso digitar nada.
        const val WSS_URL = "wss://screencast3-1.onrender.com/"
        const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    }

    private val projectionRequest = 4102
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var manager: MediaProjectionManager
    private lateinit var roomInput: EditText
    private lateinit var status: TextView
    private lateinit var joinButton: Button
    private lateinit var createButton: Button
    private lateinit var emptyView: TextView
    private lateinit var shareButton: Button
    private lateinit var streamsBox: LinearLayout

    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null
    private val peerId = "android-" + UUID.randomUUID().toString().take(8)
    private var room = ""
    private var joined = false

    private val eglBase: EglBase by lazy { EglBase.create() }
    private var factory: PeerConnectionFactory? = null
    private var helper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var localTrack: VideoTrack? = null

    // Tudo abaixo só é tocado na thread principal.
    private val knownPeers = mutableSetOf<String>()
    private val sending = HashMap<String, Link>()
    private val receiving = HashMap<String, Link>()
    private val tiles = HashMap<String, Tile>()

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        // Para redes restritivas, adicione um servidor TURN aqui (e no site).
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // tela acesa enquanto assiste
        manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(applicationContext).createInitializationOptions())
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
        buildUi()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(24), dp(16), dp(24)); gravity = Gravity.CENTER_HORIZONTAL }
        val title = TextView(this).apply { text = "◉ ScreenCast"; textSize = 30f; setTextColor(0xFFB69CFF.toInt()); gravity = Gravity.CENTER }
        val intro = TextView(this).apply { text = "Entre na mesma sala do site, assista às telas e compartilhe a do seu celular."; textSize = 15f; setTextColor(0xFFE8E8F0.toInt()); gravity = Gravity.CENTER }
        roomInput = EditText(this).apply { hint = "Código da sala"; setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS; setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0xFF8A8AA0.toInt()) }
        status = TextView(this).apply { text = "Pronto para conectar"; setTextColor(0xFFB8B8C8.toInt()); gravity = Gravity.CENTER }
        joinButton = Button(this).apply { text = "Entrar na sala"; setOnClickListener { if (joined || socket != null) leaveRoom("Você saiu da sala.") else connectRoom() } }
        createButton = Button(this).apply { text = "Criar sala nova"; setOnClickListener { if (socket == null) connectRoom(randomCode()) } }
        emptyView = TextView(this).apply { text = "Ninguém está transmitindo ainda.\nQuando alguém compartilhar a tela (celular ou computador), ela aparece aqui."; setTextColor(0xFF8A8AA0.toInt()); gravity = Gravity.CENTER; visibility = View.GONE }
        shareButton = Button(this).apply { text = "Compartilhar tela do celular"; isEnabled = false; setOnClickListener { if (localTrack == null) startScreenCapture() else stopScreenCapture() } }
        streamsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listOf(title, intro, roomInput, joinButton, createButton, status, shareButton, emptyView, streamsBox).forEach { v ->
            root.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(0xFF0D071F.toInt()); addView(root) })
    }

    // ---------- Sala / WebSocket ----------

    private fun randomCode(): String {
        val rnd = SecureRandom()
        return (1..8).map { CODE_ALPHABET[rnd.nextInt(CODE_ALPHABET.length)] }.joinToString("")
    }

    private fun connectRoom(forcedCode: String? = null) {
        room = (forcedCode ?: roomInput.text.toString()).trim().uppercase().replace(Regex("[^A-Z0-9]"), "").take(24)
        if (room.isBlank()) { status.text = "Informe o código da sala."; return }
        roomInput.setText(room)
        status.text = "Conectando…"
        joinButton.text = "Cancelar"
        createButton.isEnabled = false
        val ws = http.newWebSocket(Request.Builder().url(WSS_URL).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("type", "join").put("room", room).put("peerId", peerId).put("platform", "android").toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = try { JSONObject(text) } catch (e: Exception) { return }
                handler.post { if (socket === webSocket) handleMessage(msg) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                handler.post { if (socket === webSocket) leaveRoom("Falha de conexão: ${t.message}") }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handler.post { if (socket === webSocket) leaveRoom("Desconectado do servidor.") }
            }
        })
        socket = ws
    }

    private fun leaveRoom(message: String) {
        if (localTrack != null) stopScreenCapture()
        sending.keys.toList().forEach { closeSending(it) }
        receiving.keys.toList().forEach { closeReceiving(it) }
        knownPeers.clear()
        val ws = socket; socket = null
        ws?.close(1000, "left")
        joined = false
        refreshStatus()
        joinButton.text = "Entrar na sala"; joinButton.isEnabled = true
        createButton.isEnabled = true
        shareButton.isEnabled = false
        status.text = message
    }

    private fun handleMessage(msg: JSONObject) {
        when (msg.optString("type")) {
            "joined" -> { joined = true; joinButton.text = "Sair da sala"; shareButton.isEnabled = true; refreshStatus() }
            "error" -> leaveRoom("Erro: ${msg.optString("message")}")
            "peers" -> { val a = msg.optJSONArray("peers"); if (a != null) for (i in 0 until a.length()) knownPeers.add(a.optString(i)) }
            "peer-joined" -> { val id = msg.optString("peerId"); knownPeers.add(id); startSending(id) }
            "peer-left" -> { val id = msg.optString("peerId"); knownPeers.remove(id); closeSending(id); closeReceiving(id) }
            "signal" -> handleSignal(msg.optString("from"), msg.optJSONObject("payload") ?: return)
        }
    }

    private fun sendSignal(to: String, payload: JSONObject) {
        socket?.send(JSONObject().put("type", "signal").put("to", to).put("payload", payload).toString())
    }

    private fun handleSignal(from: String, p: JSONObject) {
        when (p.optString("kind")) {
            "offer" -> handleOffer(from, p.optString("sdp"))
            "answer" -> {
                val link = sending[from] ?: return
                link.pc.setRemoteDescription(object : SdpAdapter() {
                    override fun onSetSuccess() { handler.post { link.remoteSet = true; flush(link) } }
                }, SessionDescription(SessionDescription.Type.ANSWER, p.optString("sdp")))
            }
            "ice" -> {
                val link = (if (p.optString("dir") == "b") receiving[from] else sending[from]) ?: return
                val c = IceCandidate(p.optString("sdpMid"), p.optInt("sdpMLineIndex"), p.optString("candidate"))
                if (link.remoteSet) link.pc.addIceCandidate(c) else link.pending.add(c)
            }
            "stop" -> closeReceiving(from)
        }
    }

    private fun flush(link: Link) { link.pending.forEach { link.pc.addIceCandidate(it) }; link.pending.clear() }

    /** Atualiza a faixa de status e o aviso de "ninguém transmitindo". */
    private fun refreshStatus() {
        emptyView.visibility = if (joined && tiles.isEmpty()) View.VISIBLE else View.GONE
        if (!joined) return
        val parts = mutableListOf("Sala $room")
        parts.add(if (tiles.isEmpty()) "ninguém transmitindo" else "assistindo ${tiles.size} transmissão(ões)")
        if (localTrack != null) parts.add("você está transmitindo")
        status.text = parts.joinToString(" · ")
    }

    // ---------- Receber (assistir) ----------

    private fun handleOffer(from: String, sdp: String) {
        closeReceiving(from)
        val pc = factory?.createPeerConnection(PeerConnection.RTCConfiguration(iceServers).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }, observer(from, "v") { track ->
            handler.post { if (receiving.containsKey(from)) addTile(from, track) }
        }) ?: return
        val link = Link(pc); receiving[from] = link
        pc.setRemoteDescription(object : SdpAdapter() {
            override fun onSetSuccess() {
                handler.post { link.remoteSet = true; flush(link) }
                pc.createAnswer(object : SdpAdapter() {
                    override fun onCreateSuccess(desc: SessionDescription) {
                        pc.setLocalDescription(SdpAdapter(), desc)
                        sendSignal(from, JSONObject().put("kind", "answer").put("sdp", desc.description))
                    }
                }, MediaConstraints())
            }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    private fun closeReceiving(id: String) {
        removeTile(id)
        receiving.remove(id)?.let { try { it.pc.close(); it.pc.dispose() } catch (_: Exception) {} }
    }

    private fun addTile(id: String, track: VideoTrack) {
        removeTile(id)
        val renderer = SurfaceViewRenderer(this).apply {
            init(eglBase.eglBaseContext, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            setEnableHardwareScaler(true)
        }
        track.addSink(renderer)
        val label = TextView(this).apply { text = "● Transmissão de ${id.take(12)}"; setTextColor(0xFFB69CFF.toInt()); setPadding(0, dp(4), 0, dp(8)) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(renderer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)))
            addView(label)
        }
        streamsBox.addView(box)
        tiles[id] = Tile(box, renderer, track)
        refreshStatus()
    }

    private fun removeTile(id: String) {
        val t = tiles.remove(id) ?: return
        try { t.track.removeSink(t.renderer) } catch (_: Exception) {}
        t.renderer.release()
        streamsBox.removeView(t.view)
        refreshStatus()
    }

    // ---------- Transmitir ----------

    private fun startScreenCapture() {
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), projectionRequest)
    }

    @Deprecated("Android MediaProjection permission callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != projectionRequest) return
        if (resultCode != Activity.RESULT_OK || data == null) { status.text = "Compartilhamento cancelado."; return }
        // 1) sobe o serviço em primeiro plano; 2) só então inicia a captura (evita o fechamento do app).
        ScreenShareService.onReady = { handler.post { beginCapture(data) } }
        ScreenShareService.start(this)
    }

    private fun beginCapture(data: Intent) {
        try {
            val f = factory ?: return
            val dm = resources.displayMetrics
            val scale = minOf(1f, 1280f / maxOf(dm.widthPixels, dm.heightPixels))
            val w = (dm.widthPixels * scale).toInt() / 2 * 2
            val h = (dm.heightPixels * scale).toInt() / 2 * 2
            helper = SurfaceTextureHelper.create("ScreenCastCapture", eglBase.eglBaseContext)
            videoSource = f.createVideoSource(true)
            capturer = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
                override fun onStop() { handler.post { if (localTrack != null) stopScreenCapture() } }
            })
            capturer!!.initialize(helper, this, videoSource!!.capturerObserver)
            capturer!!.startCapture(w, h, 15)
            localTrack = f.createVideoTrack("screen-$peerId", videoSource)
            shareButton.text = "Parar transmissão"
            refreshStatus()
            knownPeers.forEach { startSending(it) }
        } catch (e: Exception) {
            status.text = "Não foi possível capturar a tela: ${e.message}"
            stopScreenCapture()
        }
    }

    private fun startSending(id: String) {
        val track = localTrack ?: return
        if (sending.containsKey(id)) return
        val pc = factory?.createPeerConnection(PeerConnection.RTCConfiguration(iceServers).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }, observer(id, "b") {}) ?: return
        sending[id] = Link(pc)
        pc.addTrack(track, listOf("screen-$peerId"))
        pc.createOffer(object : SdpAdapter() {
            override fun onCreateSuccess(desc: SessionDescription) {
                pc.setLocalDescription(SdpAdapter(), desc)
                sendSignal(id, JSONObject().put("kind", "offer").put("sdp", desc.description))
            }
        }, MediaConstraints())
    }

    private fun closeSending(id: String) {
        sending.remove(id)?.let { try { it.pc.close(); it.pc.dispose() } catch (_: Exception) {} }
    }

    private fun stopScreenCapture() {
        sending.keys.toList().forEach { id -> sendSignal(id, JSONObject().put("kind", "stop")); closeSending(id) }
        try { capturer?.stopCapture() } catch (_: Exception) {}
        capturer?.dispose(); capturer = null
        localTrack?.dispose(); localTrack = null
        videoSource?.dispose(); videoSource = null
        helper?.dispose(); helper = null
        ScreenShareService.onReady = null
        ScreenShareService.stop(this)
        shareButton.text = "Compartilhar tela do celular"
        refreshStatus()
    }

    // ---------- WebRTC ----------

    private fun observer(remoteId: String, dir: String, onVideo: (VideoTrack) -> Unit) = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
        override fun onIceCandidate(c: IceCandidate) {
            sendSignal(remoteId, JSONObject().put("kind", "ice").put("dir", dir)
                .put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex).put("candidate", c.sdp))
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onDataChannel(channel: DataChannel) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
        override fun onTrack(transceiver: RtpTransceiver) {
            val t = transceiver.receiver.track()
            if (t is VideoTrack) onVideo(t)
        }
    }

    override fun onDestroy() {
        try { leaveRoom("") } catch (_: Exception) {}
        http.dispatcher.executorService.shutdown()
        factory?.dispose(); factory = null
        eglBase.release()
        super.onDestroy()
    }
}

open class SdpAdapter : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String) {}
    override fun onSetFailure(error: String) {}
}
