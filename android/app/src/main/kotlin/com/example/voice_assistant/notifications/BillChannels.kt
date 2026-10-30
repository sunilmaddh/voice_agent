package com.example.voice_assistant.notifications

import android.app.Activity
import android.app.KeyguardManager
import com.example.voice_assistant.native_bridge.contracts.CapabilityResult
import com.example.voice_assistant.sms.AndroidSmsPort
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.*
import java.util.*

/** Explicit foreground scanning only. No payment-provider integration or passive inbox archive. */
class BillChannels(private val activity: Activity, messenger: BinaryMessenger) {
    private val channel = MethodChannel(messenger, "voice_assistant/bills/v1")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var resumed = false
    private var job: Job? = null
    private val extractor = BillExtractor()
    private val candidates = mutableMapOf<String, BillCandidate>()
    private fun requireVisible() {
        check(resumed && !activity.isDestroyed && !activity.isFinishing) { "APP_NOT_FOREGROUND" }
        check(!activity.getSystemService(KeyguardManager::class.java).isDeviceLocked) { "UNLOCK_REQUIRED" }
    }
    init {
        channel.setMethodCallHandler { call, result ->
            if (call.method == "clear") { job?.cancel(); candidates.clear(); result.success(null); return@setMethodCallHandler }
            if (job?.isActive == true) { result.error("BILL_BUSY", "Wait for the current operation.", null); return@setMethodCallHandler }
            job = scope.launch {
                try {
                    requireVisible()
                    val output: Any? = when (call.method) {
                        "extract" -> {
                            candidates.clear()
                            listOf(analyze(call.argument<String>("text") ?: "", "PASTED_SAMPLE"))
                        }
                        "samples" -> {
                            candidates.clear()
                            listOf("Your electricity bill of ₹2,450 is due on 05-Oct-2026.",
                                "Your credit card payment of ₹18,500 is due on 10-Oct-2026.",
                                "Your mobile plan expires on 12-Oct-2026.").map { analyze(it, "SYNTHETIC_SAMPLE") }
                        }
                        "notifications" -> {
                            candidates.clear()
                            when (val snapshot = AssistantNotificationListener.snapshot(activity)) {
                                is CapabilityResult.Unavailable -> error(snapshot.code)
                                is CapabilityResult.Available -> snapshot.value.take(50).map { record ->
                                    if (record.text.isNullOrBlank()) mapOf("source" to record.sourcePackage,
                                        "error" to (record.restrictionCode ?: "NOTIFICATION_CONTENT_UNAVAILABLE"))
                                    else analyze(record.text, "NOTIFICATION:${record.sourcePackage}")
                                }
                            }
                        }
                        "sms" -> {
                            candidates.clear()
                            val sms = AndroidSmsPort(activity) { resumed }
                            sms.restriction()?.let { error(it.code) }
                            val message = sms.latest()
                            requireVisible()
                            sms.restriction()?.let { error(it.code) }
                            message?.let { listOf(analyze(it.body, "LATEST_SMS")) } ?: emptyList<Any>()
                        }
                        "schedule" -> {
                            val candidate = candidates[call.argument<String>("id")] ?: error("BILL_RESCAN_REQUIRED")
                            val hour = call.argument<Int>("hour") ?: 9
                            val minute = call.argument<Int>("minute") ?: 0
                            val plan = BillExtractor.plan(candidate, hour, minute, TimeZone.getDefault(), System.currentTimeMillis())
                            val started = System.nanoTime()
                            val action = LocalReminderScheduler(activity.applicationContext).scheduleBill(plan)
                            action.toWire() + mapOf("mechanism" to "AlarmManager.setAndAllowWhileIdle",
                                "actionMs" to (System.nanoTime() - started) / 1e6, "executionMode" to "LOCAL",
                                "requestedTimeMs" to plan.dueEpochMs, "timeZone" to plan.zone,
                                "deliveryEvidence" to "PENDING_DEVICE_DELIVERY")
                        }
                        else -> error("UNKNOWN_BILL_METHOD")
                    }
                    result.success(output)
                } catch (_: CancellationException) { result.error("BILL_CANCELLED", "Operation cancelled. Refresh before retrying.", null) }
                catch (e: Exception) {
                    val code = e.message?.takeIf { it.matches(Regex("[A-Z_]{3,80}")) } ?: "BILL_OPERATION_FAILED"
                    result.error(code, "Bill operation unavailable. Check permissions, source text and date, then retry.", null)
                } finally { if (job === currentCoroutineContext()[Job]) job = null }
            }
        }
    }
    private fun analyze(text: String, source: String): Map<String, Any?> {
        val start = System.nanoTime()
        val bill = extractor.extract(text, BillExtractor.today())
        val id = UUID.randomUUID().toString()
        candidates[id] = bill
        return bill.toWire() + mapOf("id" to id, "source" to source, "input" to text,
            "executionMode" to "LOCAL", "routingTier" to "DETERMINISTIC", "selectedModel" to "NONE",
            "extractionMs" to (System.nanoTime() - start) / 1e6,
            "merchantEvidence" to if (bill.merchant == null) "NOT_STATED_OR_NOT_RECOGNIZED" else "EXPLICIT_TEXT",
            "reason" to if (bill.eligible) "Recognized category, explicit date and required amount." else "Incomplete, ambiguous or unsupported notice; no reminder created.")
    }
    fun resume() { resumed = true }
    fun pause() { resumed = false }
    fun close() { candidates.clear(); channel.setMethodCallHandler(null); scope.cancel() }
}
