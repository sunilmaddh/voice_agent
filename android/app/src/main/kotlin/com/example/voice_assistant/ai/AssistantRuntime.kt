package com.example.voice_assistant.ai

import android.content.Context
import com.example.voice_assistant.intent.TieredIntentRouter
import java.io.File

/** Process-scoped model; the Flutter and system assistant share configuration and its inference mutex. */
object AssistantRuntime {
    private var model: LocalCommandModel? = null
    @Synchronized fun commandModel(context: Context): LocalCommandModel = model ?: LocalCommandModel(
        File(context.applicationContext.filesDir, "command_models"),
        { LiteRtCommandRuntime(context.applicationContext.cacheDir.path) }, { android.os.Debug.getPss() }).also { model = it }
    fun router(context: Context) = TieredIntentRouter(commandModel(context))
}
