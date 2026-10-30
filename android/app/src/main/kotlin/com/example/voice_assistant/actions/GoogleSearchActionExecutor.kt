package com.example.voice_assistant.actions

import java.net.URLEncoder
import com.example.voice_assistant.intent.CommandValidator
import com.example.voice_assistant.native_bridge.contracts.*

interface SearchLaunchPort {
    fun restriction(): ActionResult?
    fun openGoogleApp(query: String): Boolean
    fun openBrowser(url: String): Boolean
}

/** The query is data, never a URL, component name, or executable model instruction. */
class GoogleSearchActionExecutor(private val launcher: SearchLaunchPort) : ActionExecutor {
    override suspend fun execute(action: AssistantAction): ActionResult {
        if (action.name != "GOOGLE_SEARCH" || action.intent.kind != IntentKind.googleSearch ||
            CommandValidator().validate(action.intent) !is CapabilityResult.Available)
            return ActionResult(ActionStatus.failed, "INVALID_SEARCH_ACTION", "Please provide a clear Google Search query.")
        launcher.restriction()?.let { return it }
        val query = action.intent.parameters.single().value as String
        return try {
            if (launcher.openGoogleApp(query)) {
                ActionResult(ActionStatus.succeeded, "GOOGLE_SEARCH_APP_LAUNCH_REQUESTED", "Google Search launch requested in the Google app. Check the app for results.")
            } else if (launcher.openBrowser(searchUrl(query))) {
                ActionResult(ActionStatus.succeeded, "GOOGLE_SEARCH_BROWSER_LAUNCH_REQUESTED", "Google Search launch requested through your browser. Check the browser for results.")
            } else ActionResult(ActionStatus.unsupported, "GOOGLE_SEARCH_UNAVAILABLE", "No app is available to open Google Search. Install or enable the Google app or a browser.")
        } catch (_: SecurityException) {
            ActionResult(ActionStatus.failed, "GOOGLE_SEARCH_LAUNCH_DENIED", "Android did not allow Google Search to open.")
        }
    }
    companion object {
        fun searchUrl(query: String) = "https://www.google.com/search?q=" + URLEncoder.encode(query, "UTF-8")
    }
}
