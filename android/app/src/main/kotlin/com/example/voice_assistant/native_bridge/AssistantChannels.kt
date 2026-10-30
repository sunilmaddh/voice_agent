package com.example.voice_assistant.native_bridge

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.EventChannel
import kotlinx.coroutines.*
import com.example.voice_assistant.diagnostics.StructuredLogger
import com.example.voice_assistant.native_bridge.contracts.*
import com.example.voice_assistant.voice.SpeechCoordinator
import com.example.voice_assistant.ai.*
import com.example.voice_assistant.intent.TieredIntentRouter
import com.example.voice_assistant.benchmark.CommandBenchmark
import java.io.File
import com.example.voice_assistant.voice.wakeword.WakeWordService
import com.example.voice_assistant.voice.wakeword.WakeWordPolicy

class AssistantChannels(private val activity: Activity, messenger: BinaryMessenger) : EventChannel.StreamHandler {
    companion object { const val MICROPHONE_REQUEST = 4801; const val CALENDAR_REQUEST = 4802; const val WAKE_REQUEST = 4805 }
    private val methods = MethodChannel(messenger, "voice_assistant/methods/v1")
    private val events = EventChannel(messenger, "voice_assistant/events/v1")
    private val wakeEvents = EventChannel(messenger, "voice_assistant/wake_events/v1")
    private var wakeSink: EventChannel.EventSink? = null
    private var wakePermissionResult: MethodChannel.Result? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sink: EventChannel.EventSink? = null
    private val logger = StructuredLogger()
    private val commandModel = AssistantRuntime.commandModel(activity.applicationContext)
    private val router = TieredIntentRouter(commandModel)
    private val gmailAuth = com.example.voice_assistant.gmail.GoogleGmailAuthorization(activity)
    private val gmailApi = com.example.voice_assistant.gmail.GmailApiService(gmailAuth)
    private fun gmailRestriction(): ActionResult? = when {
        !resumed || activity.isFinishing || activity.isDestroyed -> ActionResult(ActionStatus.failed, "APP_NOT_FOREGROUND", "Open Aiko to use Gmail.")
        activity.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked -> ActionResult(ActionStatus.requiresConfirmation, "UNLOCK_REQUIRED", "Unlock your phone to use Gmail.")
        else -> null
    }
    private val gmail = com.example.voice_assistant.gmail.GmailSession(gmailApi, ::gmailRestriction)
    private fun whatsappRestriction(): ActionResult? = when {
        !resumed || activity.isFinishing || activity.isDestroyed -> ActionResult(ActionStatus.failed, "APP_NOT_FOREGROUND", "Open Aiko to reply.")
        activity.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked -> ActionResult(ActionStatus.requiresConfirmation, "UNLOCK_REQUIRED", "Unlock your phone to reply.")
        else -> null
    }
    private val whatsapp = com.example.voice_assistant.notifications.WhatsAppReplySession(
        { com.example.voice_assistant.notifications.AssistantNotificationListener.replyTarget(activity) }, ::whatsappRestriction,
        instantEnabled = { activity.getSharedPreferences("notification_preferences", 0).getBoolean("instantVoiceReplies", false) },
        readTarget = { record -> com.example.voice_assistant.notifications.AssistantNotificationListener.replyTarget(activity, record.id, record.postTime) })
    private val coordinator = SpeechCoordinator(activity.applicationContext, router,
        com.example.voice_assistant.actions.AndroidActionExecutor(activity, gmail, whatsapp) { resumed }) { event ->
        logger.record(event) // metadata only, never transcript
        sink?.success(event.toSessionWire())
    }
    private data class Pending(val request: VoiceRequest, val result: MethodChannel.Result, val ttsOnly: Boolean)
    private var pending: Pending? = null
    private var activeJob: Job? = null
    private var resumed = false
    private var permissionInFlight = false
    private var inspecting = false
    private var commandJob: Job? = null
    private var calendarPermissionResult: MethodChannel.Result? = null

    private fun calendarPermissions(): Map<String, Boolean> = mapOf(
        "calendarRead" to (activity.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED),
        "notifications" to com.example.voice_assistant.notifications.LocalReminderScheduler(activity.applicationContext).notificationsEnabled())

    init {
        wakeEvents.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) { wakeSink = events }
            override fun onCancel(arguments: Any?) { wakeSink = null }
        })
        WakeWordService.foregroundHandoff = {
            val keyguard = activity.getSystemService(android.app.KeyguardManager::class.java)
            if (WakeWordPolicy.mayHandoff(resumed, !keyguard.isDeviceLocked && !keyguard.isKeyguardLocked,
                pending != null || inspecting || commandJob != null || permissionInFlight, wakeSink != null)) {
                wakeSink?.success(mapOf("event" to "WAKE_DETECTED")); true
            } else false
        }
        events.setStreamHandler(this)
        methods.setMethodCallHandler { call, result ->
            if ((WakeWordService.enabled || WakeWordService.active) && call.method !in setOf("smsSettings", "callingSettings", "wakeWordStatus", "wakeWordStop", "cancelVoiceInteraction",
                    "callerAnnouncementStatus", "callerAnnouncementSet", "callerAnnouncementContacts", "callerAnnouncementMicrophone", "drivingModeStatus", "drivingModeSet", "drivingModeAutoReply", "drivingModeMessage", "drivingReplyDictate", "voiceFriendlyRingingSet", "speechPreferencesRead", "speechPreferencesWrite", "notificationAccessStatus", "notificationAccessSettings",
                    "systemAssistantLockedReplies", "systemAssistantStatus", "systemAssistantPrivacy", "requestAssistantRole", "lockScreenReport", "previewLockScreenAssistant",
                    "whatsappReplyReview", "whatsappReplyConfirm", "whatsappReplyCancel", "instantReplyRead", "instantReplyWrite",
                    "gmailStatus", "gmailConnect", "gmailDisconnect", "gmailRead", "gmailSelect", "gmailConfirmReply", "gmailCancelReply") &&
                !(call.method == "startVoiceInteraction" && !WakeWordService.active && WakeWordService.handoff != null)) {
                result.error("WAKE_LISTENING_ACTIVE", "Stop Hey Indo listening before using another microphone or native operation.", null)
                return@setMethodCallHandler
            }
            when (call.method) {
                "callerAnnouncementStatus", "callerAnnouncementSet", "callerAnnouncementContacts", "callerAnnouncementMicrophone" -> {
                    val caller = com.example.voice_assistant.communications.CallerAnnouncer
                    if (call.method == "callerAnnouncementStatus") result.success(caller.status(activity))
                    else if (!resumed || activity.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked)
                        result.error("UNLOCK_REQUIRED", "Unlock Aiko to configure caller announcements.", null)
                    else try {
                        if (call.method == "callerAnnouncementContacts") {
                            activity.requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 6103)
                        } else if (call.method == "callerAnnouncementMicrophone") {
                            activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 6104)
                        } else {
                            val enabled = call.argument<Boolean>("enabled") ?: error("INVALID_SETTING")
                            val locked = call.argument<Boolean>("allowLocked") ?: error("INVALID_SETTING")
                            caller.set(activity, enabled, locked, call.argument<Boolean>("voiceControl"))
                        }
                        result.success(caller.status(activity))
                    } catch (_: Exception) { result.error("CALLER_SETTING_FAILED", "Could not update caller announcement settings.", null) }
                }
                "drivingReplyDictate" -> {
                    if (!resumed || activity.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked)
                        result.error("UNLOCK_REQUIRED", "Open and unlock Aiko to dictate your reply.", null)
                    else if (activeJob != null || pending != null || commandJob != null || inspecting || permissionInFlight)
                        result.error("VOICE_BUSY", "Finish the current voice request first.", null)
                    else if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                        result.error("MICROPHONE_PERMISSION_REQUIRED", "Grant microphone permission using Aiko's voice controls, then try again.", null)
                    else {
                        activeJob = scope.launch {
                            val stt = com.example.voice_assistant.stt.AndroidSpeechToTextEngine(activity) {}
                            try {
                                val capture = com.example.voice_assistant.voice.wakeword.ReplyMicrophonePause.use {
                                    stt.recognize(VoiceRequest("req-${System.currentTimeMillis()}-0", options =
                                        com.example.voice_assistant.stt.SavedSpeechOptions.read(activity)))
                                }
                                when (capture) {
                                    is CapabilityResult.Available -> {
                                        check(capture.value.isFinal)
                                        // Return only a draft. Never route dictated content as a command.
                                        result.success(mapOf("draft" to capture.value.transcript))
                                    }
                                    is CapabilityResult.Unavailable -> result.error(capture.code, "Reply dictation unavailable.", null)
                                }
                            } catch (_: CancellationException) { result.error("CANCELLED", "Reply dictation cancelled.", null) }
                            catch (e: SpeechFailure) { result.error(e.code, "Reply dictation failed. Check your speech language settings.", null) }
                            catch (_: Exception) { result.error("DICTATION_FAILED", "Could not transcribe your reply.", null) }
                            finally { stt.close(); activeJob = null }
                        }
                    }
                }
                "drivingModeMessage" -> {
                    if (!resumed || activity.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked)
                        result.error("UNLOCK_REQUIRED", "Unlock Aiko to save your personal reply.", null)
                    else try {
                        com.example.voice_assistant.communications.DrivingMode.setMessage(activity, call.argument<String>("message") ?: "")
                        result.success(com.example.voice_assistant.communications.DrivingMode.status(activity))
                    } catch (_: IllegalArgumentException) { result.error("INVALID_REPLY", "Enter a reply of 1 to 70 characters without line breaks.", null) }
                    catch (_: Exception) { result.error("DRIVING_SAVE_FAILED", "Your reply could not be saved.", null) }
                }
                "drivingModeStatus", "drivingModeSet", "drivingModeAutoReply", "voiceFriendlyRingingSet" -> {
                    val driving = com.example.voice_assistant.communications.DrivingMode
                    if (call.method == "drivingModeStatus") result.success(driving.status(activity))
                    else if (!resumed || activity.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked)
                        result.error("UNLOCK_REQUIRED", "Unlock Aiko to configure driving mode.", null)
                    else {
                        val value = call.argument<Boolean>("enabled")
                        if (value == null) result.error("INVALID_REQUEST", "Boolean setting required.", null)
                        else try {
                            if (call.method == "voiceFriendlyRingingSet") driving.setVoiceFriendlyRinging(activity, value)
                            else if (call.method == "drivingModeAutoReply") driving.setAutomaticReply(activity, value)
                            else {
                                val outcome = driving.set(activity, value)
                                if (outcome.status != ActionStatus.succeeded) throw IllegalStateException()
                            }
                            result.success(driving.status(activity))
                        } catch (_: Exception) { result.error("DRIVING_SAVE_FAILED", "Driving setting could not be saved.", null) }
                    }
                }
                "smsSettings" -> {
                    if (!resumed || activity.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked)
                        result.error("UNLOCK_REQUIRED", "Unlock Aiko to configure SMS.", null)
                    else {
                        activity.startActivity(android.content.Intent(activity, com.example.voice_assistant.sms.SmsSetupActivity::class.java))
                        result.success(null)
                    }
                }
                "callingSettings" -> {
                    if (!resumed || activity.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked)
                        result.error("UNLOCK_REQUIRED", "Unlock Aiko to configure calling.", null)
                    else {
                        activity.startActivity(android.content.Intent(activity, com.example.voice_assistant.actions.CallingSetupActivity::class.java))
                        result.success(null)
                    }
                }
                "systemAssistantLockedReplies", "systemAssistantStatus", "systemAssistantPrivacy" -> {
                    if (!resumed || activity.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked) {
                        result.error("UNLOCK_REQUIRED", "Unlock Aiko to change assistant privacy.", null)
                    } else {
                        val prefs = activity.getSharedPreferences("assistant_privacy", 0)
                        val value = (call.arguments as? Map<*, *>)?.get("enabled") as? Boolean
                        val key = if (call.method == "systemAssistantLockedReplies") "lockedWhatsAppReplies" else "lockedPersonalReads"
                        if (call.method != "systemAssistantStatus" && value == null) result.error("INVALID_REQUEST", "Boolean setting required.", null)
                        else if (call.method != "systemAssistantStatus" && !prefs.edit().putBoolean(key, value!!).commit()) result.error("SAVE_FAILED", "Privacy preference could not be saved.", null)
                        else result.success(com.example.voice_assistant.lockscreen.LockScreenProbe(activity).report())
                    }
                }
                "instantReplyRead", "instantReplyWrite" -> {
                    if (whatsappRestriction() != null || pending != null) {
                        result.error("WHATSAPP_REPLY_BUSY", "Keep Aiko unlocked and finish voice interaction first.", null)
                    } else {
                        val prefs = activity.getSharedPreferences("notification_preferences", 0)
                        val enabled = (call.arguments as? Map<*, *>)?.get("enabled") as? Boolean
                        if (call.method == "instantReplyWrite" && enabled == null) {
                            result.error("INVALID_REQUEST", "A boolean setting is required.", null)
                        } else if (call.method == "instantReplyWrite" && !prefs.edit().putBoolean("instantVoiceReplies", enabled!!).commit()) {
                            result.error("PREFERENCE_SAVE_FAILED", "Setting could not be saved.", null)
                        } else {
                            if (call.method == "instantReplyWrite") whatsapp.clear()
                            result.success(prefs.getBoolean("instantVoiceReplies", false))
                        }
                    }
                }
                "whatsappReplyReview", "whatsappReplyConfirm", "whatsappReplyCancel" -> {
                    if (whatsappRestriction() != null || pending != null || commandJob != null) {
                        result.error("WHATSAPP_REPLY_BUSY", "Keep Aiko unlocked and finish voice interaction first.", null)
                    } else when (call.method) {
                        "whatsappReplyReview" -> result.success(whatsapp.review())
                        "whatsappReplyCancel" -> { whatsapp.clear(); result.success(null) }
                        else -> {
                            val token = (call.arguments as? Map<*, *>)?.get("token") as? String ?: ""
                            val outcome = whatsapp.confirm(token)
                            result.success(mapOf("code" to outcome.code, "message" to outcome.message,
                                "status" to outcome.status.name, "evidence" to outcome.evidence))
                        }
                    }
                }
                "notificationAccessStatus", "notificationAccessSettings" -> {
                    if (!resumed || activity.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked) {
                        result.error("UNLOCK_REQUIRED", "Open and unlock Aiko first.", null)
                    } else try {
                        if (call.method == "notificationAccessSettings") activity.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        result.success(mapOf("granted" to com.example.voice_assistant.notifications.AssistantNotificationListener.granted(activity),
                            "connected" to com.example.voice_assistant.notifications.AssistantNotificationListener.ready()))
                    } catch (_: Exception) { result.error("NOTIFICATION_SETTINGS_UNAVAILABLE", "Could not open notification access settings.", null) }
                }
                "speechPreferencesRead", "speechPreferencesWrite" -> {
                    val preferences = activity.getSharedPreferences("speech_preferences", android.content.Context.MODE_PRIVATE)
                    if (call.method == "speechPreferencesRead") {
                        val allowed = preferences.getBoolean("allowSystemRecognition", false)
                        result.success(mapOf("languageTag" to com.example.voice_assistant.stt.SavedSpeechOptions.read(activity).languageTag, "allowSystemRecognition" to allowed,
                            "preferSystemRecognition" to (allowed && preferences.getBoolean("preferSystemRecognition", false))))
                    } else try {
                        val options = SpeechOptions.fromWire(call.arguments)
                        preferences.edit().putBoolean("allowSystemRecognition", options.allowSystemRecognition)
                            .putBoolean("preferSystemRecognition", options.preferSystemRecognition).putString("languageTag", options.languageTag).apply()
                        result.success(null)
                    } catch (_: IllegalArgumentException) {
                        result.error("INVALID_REQUEST", "Invalid speech preferences.", null)
                    }
                }
                "wakeWordStatus" -> result.success(WakeWordService.report + mapOf("active" to WakeWordService.enabled, "microphoneOwnedByWakeService" to WakeWordService.active))
                "wakeWordStop" -> { activity.stopService(android.content.Intent(activity, WakeWordService::class.java)); result.success(null) }
                "wakeWordStart", "wakeWordPermissions" -> {
                    if (!resumed || pending != null || inspecting || commandJob != null || permissionInFlight ||
                        activity.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked) {
                        result.error("VOICE_BUSY", "Open and unlock Aiko and finish the current operation.", null)
                    } else try {
                        val missing = mutableListOf<String>()
                        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.RECORD_AUDIO)
                        if (android.os.Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.POST_NOTIFICATIONS)
                        if (call.method == "wakeWordPermissions") {
                            if (missing.isEmpty()) result.success(null)
                            else { wakePermissionResult = result; permissionInFlight = true; activity.requestPermissions(missing.toTypedArray(), WAKE_REQUEST) }
                        } else if (missing.isNotEmpty()) result.error("WAKE_PERMISSION_REQUIRED", "Grant microphone and notification permissions first.", null)
                        else {
                            val intent = android.content.Intent(activity, WakeWordService::class.java).setAction("ARM")
                            if (android.os.Build.VERSION.SDK_INT >= 26) activity.startForegroundService(intent) else activity.startService(intent)
                            result.success(null)
                        }
                    } catch (_: Exception) {
                        wakePermissionResult = null; permissionInFlight = false
                        result.error("WAKE_START_FAILED", "Android did not allow wake-word listening. Check permissions and notification settings.", null)
                    }
                }
                "lockScreenReport", "requestAssistantRole", "previewLockScreenAssistant" -> {
                    if (!resumed || pending != null || inspecting || commandJob != null || permissionInFlight ||
                        activity.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked) {
                        result.error("LOCK_SCREEN_BUSY", "Open and unlock Aiko and finish the current operation first.", null)
                    } else try {
                        val probe = com.example.voice_assistant.lockscreen.LockScreenProbe(activity)
                        when (call.method) {
                            "requestAssistantRole" -> probe.requestRole()
                            "previewLockScreenAssistant" -> activity.startActivity(android.content.Intent(activity,
                                com.example.voice_assistant.lockscreen.SystemAssistantActivity::class.java))
                        }
                        result.success(probe.report())
                    } catch (_: Exception) {
                        result.error("LOCK_SCREEN_UNAVAILABLE", "Android could not open assistant settings or the test surface.", null)
                    }
                }
                "gmailStatus", "gmailConnect", "gmailDisconnect", "gmailRead", "gmailSelect", "gmailConfirmReply", "gmailCancelReply" -> {
                    if (gmailRestriction() != null) {
                        result.error("GMAIL_LOCKED_OR_BACKGROUND", "Keep Aiko open and unlocked to use Gmail.", null)
                    } else if (pending != null || inspecting || commandJob != null || permissionInFlight) {
                        result.error("VOICE_BUSY", "Finish the current operation first.", null)
                    } else if (call.method == "gmailConnect") {
                        gmail.clear(); gmailApi.clear(); permissionInFlight = true
                        gmailAuth.connect { error ->
                            permissionInFlight = false
                            if (error != null) result.error(error, "Google authorization failed or was cancelled. Check OAuth setup and granted scopes.", null)
                            else {
                                commandJob = scope.launch(start = CoroutineStart.LAZY) {
                                    try { gmailApi.profile(); result.success(gmailState()) }
                                    catch (_: Exception) { gmailAuth.clear(); gmailApi.clear(); result.error("GMAIL_PROFILE_FAILED", "Could not verify the Gmail account. Check API setup and reconnect.", null) }
                                    finally { commandJob = null }
                                }
                                commandJob?.start()
                            }
                        }
                    } else {
                        commandJob = scope.launch(start = CoroutineStart.LAZY) {
                            try {
                                val args = call.arguments as? Map<*, *> ?: emptyMap<Any, Any>()
                                val action = when (call.method) {
                                    "gmailDisconnect" -> { gmailAuth.clear(); gmailApi.clear(); gmail.clear(); null }
                                    "gmailCancelReply" -> { gmail.cancelDraft(); null }
                                    "gmailSelect" -> gmail.select(args["id"] as? String ?: "")
                                    "gmailConfirmReply" -> gmail.confirm(args["draftId"] as? String ?: "")
                                    "gmailRead" -> {
                                        val intent = com.example.voice_assistant.intent.TypedCommand.ReadEmail(args["scope"] as? String ?: "LATEST").toIntent(1.0)
                                        gmail.execute(AssistantAction("READ_EMAIL", intent))
                                    }
                                    else -> null
                                }
                                result.success(gmailState() + mapOf("operationResult" to action?.toWire()))
                            } catch (_: CancellationException) { result.error("CANCELLED", "Gmail operation cancelled. Check Gmail before retrying a send.", null) }
                            catch (_: Exception) { result.error("GMAIL_OPERATION_FAILED", "Gmail operation failed. Check Gmail before retrying a send.", null) }
                            finally { commandJob = null }
                        }
                        commandJob?.start()
                    }
                }
                "calendarPermissions" -> result.success(calendarPermissions())
                "requestCalendarPermissions" -> {
                    if (!resumed || pending != null || inspecting || commandJob != null || permissionInFlight) {
                        result.error("VOICE_BUSY", "Keep Aiko open and finish the current operation first.", null)
                    } else {
                        val permissions = mutableListOf<String>()
                        if (activity.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.READ_CALENDAR)
                        if (android.os.Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                        if (permissions.isEmpty()) result.success(calendarPermissions())
                        else {
                            calendarPermissionResult = result; permissionInFlight = true
                            try { activity.requestPermissions(permissions.toTypedArray(), CALENDAR_REQUEST) }
                            catch (_: RuntimeException) {
                                calendarPermissionResult = null; permissionInFlight = false
                                result.error("PERMISSION_REQUEST_FAILED", "Enable permissions in Android app settings, then retry.", null)
                            }
                        }
                    }
                }
                "cancelCommandWork" -> { commandJob?.cancel(); result.success(null) }
                "configureCommandModel", "unloadCommandModel", "commandModelStatus", "extractCommand", "diagnoseCommandModel", "benchmarkCommands" -> {
                    if (pending != null || inspecting || commandJob != null || permissionInFlight) {
                        result.error("VOICE_BUSY", "Another native operation is running.", null)
                    } else if (!resumed) {
                        result.error("APP_NOT_FOREGROUND", "Open the app to use local inference.", null)
                    } else {
                        commandJob = scope.launch(start = CoroutineStart.LAZY) {
                            try {
                                val args = call.arguments as? Map<*, *> ?: emptyMap<Any, Any>()
                                val response: Any = when (call.method) {
                                    "configureCommandModel" -> commandModel.configure(CommandModelConfig(
                                        args["fileName"] as? String ?: "", args["modelId"] as? String ?: "",
                                        args["sha256"] as? String ?: "", args["quantization"] as? String ?: ""))
                                    "unloadCommandModel" -> { commandModel.close(); commandModel.diagnostics }
                                    "commandModelStatus" -> commandModel.diagnostics + mapOf("modelDirectory" to File(activity.filesDir, "command_models").path)
                                    "diagnoseCommandModel" -> commandModel.diagnose(args["input"] as? String ?: throw IllegalArgumentException())
                                    "extractCommand" -> {
                                        val input = args["input"] as? String ?: throw IllegalArgumentException()
                                        when (val route = router.route(input)) {
                                            is CapabilityResult.Available -> route.value.diagnostics() + mapOf(
                                                "structuredOutput" to route.value.structured(), "response" to route.value.response)
                                            is CapabilityResult.Unavailable -> mapOf("error" to route.code)
                                        }
                                    }
                                    else -> {
                                        val cases = withContext(Dispatchers.IO) {
                                            activity.assets.open("command_benchmark_v1.json").bufferedReader().use {
                                                CommandBenchmark.parseDataset(it.readText())
                                            }
                                        }
                                        CommandBenchmark(commandModel).run(cases, mapOf("manufacturer" to android.os.Build.MANUFACTURER,
                                            "model" to android.os.Build.MODEL, "sdk" to android.os.Build.VERSION.SDK_INT,
                                            "abis" to android.os.Build.SUPPORTED_ABIS.toList()))
                                    }
                                }
                                result.success(response)
                            } catch (_: CancellationException) { result.error("CANCELLED", "Local inference cancelled.", null) }
                            catch (_: LinkageError) { result.error("MODEL_RUNTIME_UNSUPPORTED", "Runtime is incompatible with this device.", null) }
                            catch (e: IllegalArgumentException) {
                                val code = e.message?.takeIf { it in setOf("MODEL_FILE_MISSING", "MODEL_CHECKSUM_MISMATCH") } ?: "INVALID_MODEL_REQUEST"
                                result.error(code, code, null)
                            } catch (_: Exception) { result.error("MODEL_OPERATION_FAILED", "Local model operation failed.", null) }
                            finally { commandJob = null }
                        }
                        commandJob?.start()
                    }
                }
                "speechCapabilities" -> {
                    if (pending != null || inspecting || commandJob != null) result.error("VOICE_BUSY", "Voice service is busy.", null)
                    else {
                        inspecting = true
                        scope.launch {
                            try { result.success(coordinator.capabilities()) }
                            catch (_: Exception) { result.error("CAPABILITIES_UNAVAILABLE", "Speech capabilities unavailable.", null) }
                            finally { inspecting = false }
                        }
                    }
                }
                "cancelVoiceInteraction" -> {
                    val id = (call.arguments as? Map<*, *>)?.get("requestId")
                    if (id is String && pending?.request?.requestId == id) cancel()
                    result.success(null)
                }
                "startVoiceInteraction", "speak" -> {
                    val request = try { VoiceRequest.fromWire(call.arguments) } catch (_: IllegalArgumentException) { null }
                    if (request == null || (call.method == "startVoiceInteraction" && request.input != null)) {
                        result.error("INVALID_REQUEST", "Invalid voice request.", null)
                    } else if (pending != null || inspecting || permissionInFlight || commandJob != null) {
                        result.error("VOICE_BUSY", "Voice service is busy.", null)
                    } else if (!resumed) {
                        result.error("APP_NOT_FOREGROUND", "Open the app to use speech.", null)
                    } else {
                        pending = Pending(request, result, call.method == "speak")
                        if (call.method != "speak" && activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            permissionInFlight = true
                            activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MICROPHONE_REQUEST)
                        } else launchPending()
                    }
                }
                else -> result.notImplemented()
            }
        }
    }
    private fun launchPending() {
        val work = pending ?: return
        if (!resumed || activeJob != null || permissionInFlight) return
        val wakeTransfer = WakeWordService.handoff?.takeIf { it.claim() }
        activeJob = scope.launch(start = CoroutineStart.LAZY) {
            try { work.result.success(coordinator.run(work.request, work.ttsOnly).toWire()) }
            catch (_: Exception) { work.result.error("VOICE_ERROR", "Voice request failed.", null) }
            finally { pending = null; activeJob = null; wakeTransfer?.finish() }
        }
        activeJob?.start()
    }
    private fun gmailState(): Map<String, Any?> = if (gmailRestriction() != null) mapOf("restricted" to "UNLOCK_REQUIRED")
        else gmail.status() + mapOf("connected" to gmailAuth.connected(), "account" to gmailApi.account)
    fun gmailAuthorizationResult(resultCode: Int, data: android.content.Intent?) = gmailAuth.onActivityResult(resultCode, data)
    fun permissionResult(granted: Boolean) {
        permissionInFlight = false
        if (!granted) {
            val work = pending
            pending = null
            work?.result?.error("MICROPHONE_PERMISSION_DENIED", "Enable microphone permission in Android app settings, then retry.", null)
        } else launchPending()
    }
    fun calendarPermissionResult() {
        permissionInFlight = false
        calendarPermissionResult?.success(calendarPermissions()); calendarPermissionResult = null
    }
    fun wakePermissionResult() {
        permissionInFlight = false
        wakePermissionResult?.success(null); wakePermissionResult = null
    }
    fun resume() {
        resumed = true; launchPending()
        scope.launch(Dispatchers.IO) {
            try { com.example.voice_assistant.notifications.LocalReminderScheduler(activity.applicationContext).restore() }
            catch (_: Exception) { /* No reminder contents in logs. */ }
        }
    }
    fun pause() { resumed = false; whatsapp.clear(); commandJob?.cancel(); if (!permissionInFlight) cancel() }
    private fun cancel() {
        if (activeJob != null) { activeJob?.cancel(); coordinator.cancel() }
        else { val work = pending; pending = null; work?.result?.error("CANCELLED", "Voice request cancelled.", null) }
    }
    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) { sink = events }
    override fun onCancel(arguments: Any?) { sink = null }
    fun close() {
        whatsapp.clear()
        WakeWordService.foregroundHandoff = null
        wakePermissionResult?.error("CANCELLED", "Permission request cancelled.", null); wakePermissionResult = null
        wakeSink = null; wakeEvents.setStreamHandler(null)
        gmailAuth.close(); gmail.clear(); gmailApi.clear()
        calendarPermissionResult?.error("CANCELLED", "Permission request cancelled.", null); calendarPermissionResult = null
        commandJob?.cancel()
        cancel(); scope.cancel(); coordinator.close(); sink = null
        // Cleanup waits on the model mutex, so native inference cannot race engine destruction.
        // Shared model lifetime is process-scoped; closing Flutter must not interrupt the system assistant.
        methods.setMethodCallHandler(null); events.setStreamHandler(null)
    }
}


