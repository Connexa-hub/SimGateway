package com.connexa.simgateway

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Client-side connection layer.
 *
 * Ownership model: exactly ONE thread ("SimGatewayConnection") ever reads from the socket.
 * Writes are serialized by [writeLock]. Requests are matched to responses by id; callbacks and
 * listener events are always delivered on the main thread.
 */
class ConnectionManager {

    enum class State { DISCONNECTED, CONNECTING, AUTHENTICATING, CONNECTED, RECONNECTING, AUTH_FAILED }

    class Response(val ok: Boolean, val message: JSONObject?, val error: String?)

    interface Listener {
        fun onStateChanged(state: State, detail: String?)
        fun onEvent(message: JSONObject)
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val PING_INTERVAL_S = 8L
        private const val PING_TIMEOUT_MS = 5000L
        private const val MAX_RECONNECT_ATTEMPTS = 6
        private const val MAX_LINE = 65536
    }

    private val main = Handler(Looper.getMainLooper())
    private val daemonFactory = ThreadFactory { r ->
        val t = Thread(r, "SimGatewayConn")
        t.isDaemon = true
        t
    }
    private val writer = Executors.newSingleThreadExecutor(daemonFactory)
    private val timer = Executors.newSingleThreadScheduledExecutor(daemonFactory)
    private val pending = ConcurrentHashMap<String, (Response) -> Unit>()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val lock = Any()
    private val writeLock = Any()

    @Volatile var state: State = State.DISCONNECTED
        private set
    @Volatile var autoReconnect = true

    @Volatile private var generation = 0
    @Volatile private var socket: Socket? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var userStopped = true
    @Volatile private var sessionEstablished = false
    private var connThread: Thread? = null
    private var pingFuture: ScheduledFuture<*>? = null

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    fun connect(gateway: Discovery.Gateway, pin: String) {
        synchronized(lock) {
            teardownLocked()
            generation++
            val gen = generation
            userStopped = false
            sessionEstablished = false
            connThread = thread(name = "SimGatewayConnection", isDaemon = true) {
                runLoop(gen, gateway, pin)
            }
        }
    }

    fun disconnect() {
        synchronized(lock) {
            userStopped = true
            generation++
            teardownLocked()
        }
        if (state != State.DISCONNECTED) setState(State.DISCONNECTED, null)
    }

    /** Sends a request; [callback] runs on the main thread with the matching response, a timeout or an error. */
    fun request(
        type: String,
        fields: Map<String, String> = emptyMap(),
        timeoutMs: Long = 8000,
        callback: (Response) -> Unit
    ) {
        if (state != State.CONNECTED) {
            deliver(callback, Response(false, null, "not_connected"))
            return
        }
        val id = Proto.newId()
        pending[id] = callback
        timer.schedule(Runnable {
            val cb = pending.remove(id)
            if (cb != null) deliver(cb, Response(false, null, "timeout"))
        }, timeoutMs, TimeUnit.MILLISECONDS)
        val msg = Proto.build(type, id) { for ((k, v) in fields) put(k, v) }
        writer.execute(Runnable {
            try {
                writeRaw(msg)
            } catch (e: Exception) {
                val cb = pending.remove(id)
                if (cb != null) deliver(cb, Response(false, null, "connection_lost"))
                closeSocket()
            }
        })
    }

    // ---- internals -------------------------------------------------------------------------

    private fun isCurrent(gen: Int) = gen == generation && !userStopped

    private fun runLoop(gen: Int, gw: Discovery.Gateway, pin: String) {
        var attempt = 0
        while (isCurrent(gen)) {
            setStateIfCurrent(gen, if (sessionEstablished) State.RECONNECTING else State.CONNECTING, null)
            val reached = runSession(gen, gw, pin)
            if (!isCurrent(gen)) return
            if (state == State.AUTH_FAILED) return
            if (reached) attempt = 0
            if (!sessionEstablished || !autoReconnect || attempt >= MAX_RECONNECT_ATTEMPTS) {
                setStateIfCurrent(gen, State.DISCONNECTED, if (sessionEstablished) "lost" else "unreachable")
                return
            }
            attempt++
            setStateIfCurrent(gen, State.RECONNECTING, "attempt $attempt")
            try {
                Thread.sleep(minOf(1000L shl (attempt - 1), 8000L))
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    /** Runs one socket session on the calling thread. Returns true if it reached CONNECTED. */
    private fun runSession(gen: Int, gw: Discovery.Gateway, pin: String): Boolean {
        val s = Socket()
        var reached = false
        var authed = false
        try {
            s.tcpNoDelay = true
            s.keepAlive = true
            s.connect(InetSocketAddress(gw.address, gw.port), CONNECT_TIMEOUT_MS)
            synchronized(lock) {
                if (!isCurrent(gen)) {
                    s.close()
                    return false
                }
                socket = s
                output = s.getOutputStream()
            }
            setStateIfCurrent(gen, State.AUTHENTICATING, null)
            val authId = Proto.newId()
            writeRaw(Proto.build(Proto.AUTH, authId) { put("pin", pin) })

            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            while (true) {
                val line = readLineBounded(reader, MAX_LINE) ?: break
                val msg = Proto.parse(line) ?: continue
                val id = msg.optString("id", "")
                val type = msg.optString("type", "")

                if (id == authId) {
                    if (type == Proto.AUTH_OK) {
                        authed = true
                        reached = true
                        sessionEstablished = true
                        setStateIfCurrent(gen, State.CONNECTED, null)
                        startPing(gen)
                    } else {
                        val code = msg.optString("code", "auth_failed")
                        if (isCurrent(gen)) setState(State.AUTH_FAILED, code)
                        return false
                    }
                    continue
                }

                val cb = pending.remove(id)
                if (cb != null) {
                    val failed = Proto.isFailure(type)
                    deliver(cb, Response(!failed, msg, if (failed) msg.optString("code", "failed") else null))
                } else if (authed && type != Proto.HELLO) {
                    main.post { for (l in listeners) l.onEvent(msg) }
                }
            }
        } catch (e: Exception) {
            // Connection ended or failed; handled by the caller's reconnect logic.
        } finally {
            try {
                s.close()
            } catch (_: Exception) {
            }
            synchronized(lock) {
                if (socket === s) {
                    socket = null
                    output = null
                }
            }
            if (gen == generation) {
                cancelPing()
                failPending("connection_lost")
            }
        }
        return reached
    }

    private fun startPing(gen: Int) {
        synchronized(lock) {
            pingFuture?.cancel(false)
            pingFuture = timer.scheduleWithFixedDelay(Runnable {
                if (isCurrent(gen) && state == State.CONNECTED) {
                    request(Proto.PING, emptyMap(), PING_TIMEOUT_MS) { r ->
                        if (!r.ok && r.error == "timeout") closeSocket()
                    }
                }
            }, PING_INTERVAL_S, PING_INTERVAL_S, TimeUnit.SECONDS)
        }
    }

    private fun cancelPing() {
        synchronized(lock) {
            pingFuture?.cancel(false)
            pingFuture = null
        }
    }

    private fun teardownLocked() {
        connThread?.interrupt()
        connThread = null
        closeSocket()
        pingFuture?.cancel(false)
        pingFuture = null
        failPending("connection_lost")
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
    }

    @Throws(IOException::class)
    private fun writeRaw(msg: JSONObject) {
        val out = output ?: throw IOException("not connected")
        val bytes = (msg.toString() + "\n").toByteArray(Charsets.UTF_8)
        synchronized(writeLock) {
            out.write(bytes)
            out.flush()
        }
    }

    private fun failPending(code: String) {
        for (k in pending.keys.toList()) {
            val cb = pending.remove(k)
            if (cb != null) deliver(cb, Response(false, null, code))
        }
    }

    private fun deliver(cb: (Response) -> Unit, r: Response) {
        main.post { cb(r) }
    }

    private fun setStateIfCurrent(gen: Int, s: State, detail: String?) {
        if (isCurrent(gen)) setState(s, detail)
    }

    private fun setState(s: State, detail: String?) {
        state = s
        main.post { for (l in listeners) l.onStateChanged(s, detail) }
    }
}
