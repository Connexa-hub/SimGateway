package com.connexa.simgateway

import org.json.JSONObject
import java.util.UUID

/** Versioned NDJSON protocol shared by provider and client. One JSON object per line, UTF-8. */
object Proto {
    const val VERSION = 1
    const val PORT = 8765
    const val SERVICE_TYPE = "_simgateway._tcp."
    const val SERVICE_NAME = "SIM Gateway"

    const val HELLO = "hello"
    const val AUTH = "auth"
    const val AUTH_OK = "auth_ok"
    const val PING = "ping"
    const val PONG = "pong"
    const val STATUS = "status"
    const val STATUS_RESPONSE = "status_response"
    const val CALL = "call"
    const val CALL_STARTED = "call_started"
    const val CALL_FAILED = "call_failed"
    const val HANGUP = "hangup"
    const val HANGUP_OK = "hangup_ok"
    const val ANSWER = "answer"
    const val ANSWER_OK = "answer_ok"
    const val REJECT = "reject"
    const val REJECT_OK = "reject_ok"
    const val INCOMING_CALL = "incoming_call"
    const val CALL_STATE = "call_state"
    const val SMS = "sms"
    const val SMS_SENT = "sms_sent"
    const val ERROR = "error"

    fun newId(): String = UUID.randomUUID().toString().substring(0, 8)

    fun build(type: String, id: String = newId(), fill: JSONObject.() -> Unit = {}): JSONObject {
        val o = JSONObject()
        o.put("v", VERSION)
        o.put("id", id)
        o.put("type", type)
        o.fill()
        return o
    }

    fun err(id: String, code: String, type: String = ERROR): JSONObject =
        build(type, id) { put("code", code) }

    fun parse(line: String): JSONObject? = try {
        JSONObject(line)
    } catch (e: Exception) {
        null
    }

    fun isFailure(type: String): Boolean = type == ERROR || type == CALL_FAILED

    /** Returns a sanitized dialable number, or null if it doesn't look valid. */
    fun cleanNumber(raw: String): String? {
        val s = raw.filter { it != ' ' && it != '-' && it != '(' && it != ')' }
        return if (Regex("^\\+?[0-9*#]{3,20}$").matches(s)) s else null
    }
}
