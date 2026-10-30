package com.example.voice_assistant.voice.wakeword

import kotlinx.coroutines.CompletableDeferred
import java.util.UUID

/** Main-thread, in-memory lease. The detector stays closed for the entire native session. */
object SystemWakeHandoff {
    const val EXTRA = "aiko.systemWakeTicket"
    class Ticket(val id: String = UUID.randomUUID().toString()) {
        val claimed = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
    }
    private var current: Ticket? = null
    fun begin(): Ticket {
        check(current == null)
        return Ticket().also { current = it }
    }
    fun claim(id: String?): Ticket? = current?.takeIf {
        it.id == id && !it.claimed.isCompleted && !it.finished.isCompleted
    }?.also { it.claimed.complete(Unit) }
    fun end(ticket: Ticket) {
        ticket.finished.complete(Unit)
        if (current === ticket) current = null
    }
}
