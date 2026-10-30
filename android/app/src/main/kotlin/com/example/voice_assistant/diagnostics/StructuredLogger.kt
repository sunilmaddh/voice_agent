package com.example.voice_assistant.diagnostics
import android.util.Log
import org.json.JSONObject
import com.example.voice_assistant.native_bridge.contracts.DiagnosticEvent

/** Allowlisted typed metadata only. Never log arbitrary maps, tokens, input or Throwable. */
class StructuredLogger {
    fun record(event: DiagnosticEvent) {
        Log.i("AssistantPOC", JSONObject(event.toWire()).toString())
    }
}
