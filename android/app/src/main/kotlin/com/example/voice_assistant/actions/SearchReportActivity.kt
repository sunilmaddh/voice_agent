package com.example.voice_assistant.actions

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.google.gson.JsonParser
import com.example.voice_assistant.native_bridge.contracts.CapabilityResult
import com.example.voice_assistant.tts.AndroidTextToSpeechEngine
import com.example.voice_assistant.voice.SpeechAudioFocus
import kotlinx.coroutines.*

/** User-visible browser. No JavaScript bridge, account access, or background page reading. */
class SearchReportActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var summarize: Button
    private val tts by lazy { AndroidTextToSpeechEngine(this) {} }
    private val focus by lazy { SpeechAudioFocus(this) }
    private var speech: Job? = null
    private var pageGeneration = 0
    private var readGeneration = 0
    private var active = false
    private fun unlocked() = !getSystemService(KeyguardManager::class.java).isDeviceLocked

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!unlocked()) { finish(); return }
        val query = intent.getStringExtra("query")?.takeIf { it.isNotBlank() && it.length <= 1000 }
            ?: run { finish(); return }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(this).apply { text = "Search results: $query"; setPadding(16, 16, 16, 16) }
        root.addView(status)
        summarize = Button(this).apply {
            text = "Speak summary"; isEnabled = false
            setOnClickListener { readResults() }
        }
        root.addView(summarize)
        root.addView(Button(this).apply {
            text = "Stop speaking"; setOnClickListener { stopSpeech() }
        })
        root.addView(Button(this).apply {
            text = "Open in browser"
            setOnClickListener {
                if (unlocked()) try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GoogleSearchActionExecutor.searchUrl(query))))
                } catch (_: Exception) { status.text = "No browser is available." }
            }
        })
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.domStorageEnabled = true
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.url.scheme != "https" || !unlocked()
                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    pageGeneration++; stopSpeech(); summarize.isEnabled = false
                }
                override fun onPageFinished(view: WebView, url: String?) {
                    val uri = url?.let(Uri::parse)
                    summarize.isEnabled = unlocked() && uri?.scheme == "https" && uri.host == "www.google.com" && uri.path == "/search"
                    status.text = if (summarize.isEnabled) "Results loaded. Tap Speak summary to hear the visible results."
                        else "Return to Google search results to speak a summary."
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) { summarize.isEnabled = false; status.text = "Search couldn't load. Check your connection or open in browser." }
                }
            }
        }
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        web.loadUrl(GoogleSearchActionExecutor.searchUrl(query))
    }

    private fun readResults() {
        if (!unlocked() || !summarize.isEnabled) return
        stopSpeech()
        val generation = pageGeneration
        val reading = readGeneration
        // Read rendered result text only, excluding scripts, hidden content and browser controls.
        web.evaluateJavascript("""(function() {
            const root = document.querySelector('#search') || document.querySelector('main');
            if (!root) return '';
            const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
            const lines = []; let node; let size = 0;
            while ((node = walker.nextNode()) && size < 12000) {
                const p = node.parentElement;
                if (!p || p.closest('script,style,nav,button,footer,header,[aria-hidden="true"]')) continue;
                const s = getComputedStyle(p);
                if (s.display === 'none' || s.visibility === 'hidden' || !p.getClientRects().length) continue;
                const t = node.textContent.trim(); if (t) { lines.push(t); size += t.length; }
            }
            return lines.join('\n').slice(0,12000);
        })()""") { value ->
            if (!active || isFinishing || !unlocked() || generation != pageGeneration || reading != readGeneration || !summarize.isEnabled) return@evaluateJavascript
            val text = runCatching { JsonParser.parseString(value).asString }.getOrDefault("")
            val report = SearchPageSummary.summarize(text)
            status.text = report
            speech = scope.launch {
                try {
                    val job = currentCoroutineContext()[Job]
                    focus.acquire { job?.cancel() }
                    if (tts.speak("search-${System.currentTimeMillis()}", report) is CapabilityResult.Unavailable)
                        status.text = "$report\nSpeech is unavailable."
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { status.text = "$report\nSpeech is unavailable." }
                finally { tts.stop(); focus.release() }
            }
        }
    }
    private fun stopSpeech() { readGeneration++; speech?.cancel(); speech = null; tts.stop(); focus.release() }
    override fun onPause() { active = false; stopSpeech(); if (::web.isInitialized) web.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); active = true; if (!unlocked()) finish() else if (::web.isInitialized) web.onResume() }
    override fun onDestroy() {
        stopSpeech(); scope.cancel(); tts.close()
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }
}
