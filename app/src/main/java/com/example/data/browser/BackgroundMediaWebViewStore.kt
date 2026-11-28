package com.example.data.browser

import android.content.Context
import android.content.MutableContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView

import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Application-scoped retention store for live playing browser [WebView] instances when Android
 * dismisses or destroys the host Activity after the user taps the System PiP close (X) button
 * or switches to the Download Panel while a Telegram/browser stream download is active or paused.
 *
 * Swaps each WebView's [MutableContextWrapper] base context to [Context.getApplicationContext]
 * while retained (preventing Activity leaks), keeps timers/media/workers unthrottled, routes
 * Play/Pause/Stop and Download Panel controls directly to retained WebViews, and hands the live
 * WebView back seamlessly when the user returns to the browser.
 */
object BackgroundMediaWebViewStore {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val retainedWebViews = LinkedHashMap<String, WebView>()
    private val _retainedCountFlow = MutableStateFlow(0)
    val retainedCountFlow: StateFlow<Int> = _retainedCountFlow.asStateFlow()

    @Volatile
    var retainedActiveTabId: String? = null
        private set

    @Volatile
    var retainedFloatingTabId: String? = null
        private set

    @Volatile
    private var togglePlayJs: String = ""

    @Volatile
    private var stopAllJs: String = ""

    @Volatile
    private var enterBackgroundAudioJs: String = ""

    @Synchronized
    fun hasRetainedWebViews(): Boolean = retainedWebViews.isNotEmpty()

    @Synchronized
    fun removeDeadWebView(deadView: WebView): String? {
        val match = retainedWebViews.entries.firstOrNull { it.value === deadView }?.key ?: return null
        retainedWebViews.remove(match)
        if (retainedActiveTabId == match) {
            retainedActiveTabId = retainedWebViews.keys.firstOrNull()
        }
        if (retainedFloatingTabId == match) {
            retainedFloatingTabId = null
        }
        _retainedCountFlow.value = retainedWebViews.size
        return match
    }

    @Synchronized
    fun getPrimaryRetainedWebView(): WebView? {
        if (retainedWebViews.isEmpty()) return null
        val targetId = retainedActiveTabId ?: retainedFloatingTabId
        val candidate = targetId?.let { retainedWebViews[it] } ?: retainedWebViews.values.firstOrNull()
        if (candidate != null && WebViewRendererGuard.isDestroyed(candidate)) {
            removeDeadWebView(candidate)
            return retainedWebViews.values.firstOrNull { !WebViewRendererGuard.isDestroyed(it) }
        }
        return candidate
    }

    @Synchronized
    fun retainForBackgroundPlayback(
        appContext: Context,
        activeTabId: String,
        floatingTabId: String?,
        webViews: Map<String, WebView>,
        toggleJs: String,
        stopJs: String,
        bgAudioJs: String
    ) {
        val safeAppContext = appContext.applicationContext
        togglePlayJs = toggleJs
        stopAllJs = stopJs
        enterBackgroundAudioJs = bgAudioJs

        val keepIds = LinkedHashSet<String>().apply {
            if (activeTabId.isNotBlank()) add(activeTabId)
            if (!floatingTabId.isNullOrBlank()) add(floatingTabId)
            // Also keep any Telegram Web tab so active/paused Telegram downloads stay controllable
            webViews.forEach { (id, wv) ->
                val u = runCatching { wv.url.orEmpty() }.getOrDefault("")
                if (u.contains("telegram.org", ignoreCase = true)) {
                    add(id)
                }
            }
        }

        // Release any previously retained views that are not in the new set
        val staleEntries = retainedWebViews.entries.filter { it.key !in keepIds }
        for ((staleKey, staleWv) in staleEntries) {
            retainedWebViews.remove(staleKey)
            destroyWebViewSafely(staleWv)
        }

        for ((tabId, wv) in webViews) {
            if (tabId in keepIds && !WebViewRendererGuard.isDestroyed(wv)) {
                runCatching { (wv.parent as? ViewGroup)?.removeView(wv) }
                runCatching {
                    (wv.context as? MutableContextWrapper)?.baseContext = safeAppContext
                }
                retainedWebViews[tabId] = wv
                keepWebViewMediaAlive(wv, bgAudioJs)
            } else {
                destroyWebViewSafely(wv)
            }
        }

        retainedActiveTabId = activeTabId.takeIf { it in retainedWebViews }
            ?: retainedWebViews.keys.firstOrNull()
        retainedFloatingTabId = floatingTabId?.takeIf { it in retainedWebViews }
        _retainedCountFlow.value = retainedWebViews.size
    }

    @Synchronized
    fun takeRetainedWebViews(newActivityContext: Context): Map<String, WebView> {
        if (retainedWebViews.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, WebView>(retainedWebViews.size)
        for ((tabId, wv) in retainedWebViews) {
            if (WebViewRendererGuard.isDestroyed(wv)) continue
            runCatching { (wv.parent as? ViewGroup)?.removeView(wv) }
            runCatching {
                (wv.context as? MutableContextWrapper)?.baseContext = newActivityContext
            }
            runCatching {
                wv.resumeTimers()
                wv.onResume()
            }
            result[tabId] = wv
        }
        retainedWebViews.clear()
        retainedActiveTabId = null
        retainedFloatingTabId = null
        _retainedCountFlow.value = 0
        return result
    }

    fun evaluateJavascriptOnAllRetained(js: String): Boolean {
        if (js.isBlank()) return false
        val targets = synchronized(this) {
            retainedWebViews.values.filter { !WebViewRendererGuard.isDestroyed(it) }
        }
        if (targets.isEmpty()) return false
        mainHandler.post {
            targets.forEach { wv ->
                if (WebViewRendererGuard.isDestroyed(wv)) return@forEach
                runCatching {
                    wv.resumeTimers()
                    wv.onResume()
                    wv.evaluateJavascript(js, null)
                }
            }
        }
        return true
    }

    fun dispatchTogglePlayOnRetained(): Boolean {
        val target: WebView
        val js: String
        synchronized(this) {
            if (retainedWebViews.isEmpty()) return false
            val targetId = retainedFloatingTabId ?: retainedActiveTabId
            target = (targetId?.let { retainedWebViews[it] } ?: retainedWebViews.values.firstOrNull())
                ?.takeIf { !WebViewRendererGuard.isDestroyed(it) }
                ?: return false
            js = togglePlayJs
        }
        if (js.isBlank()) return false
        mainHandler.post {
            if (WebViewRendererGuard.isDestroyed(target)) return@post
            runCatching {
                target.resumeTimers()
                target.onResume()
                target.evaluateJavascript(js, null)
            }
        }
        return true
    }

    fun stopAndReleaseAll() {
        val viewsToRelease: List<WebView>
        val js: String
        synchronized(this) {
            if (retainedWebViews.isEmpty()) return
            viewsToRelease = retainedWebViews.values.toList()
            js = stopAllJs
            retainedWebViews.clear()
            retainedActiveTabId = null
            retainedFloatingTabId = null
            _retainedCountFlow.value = 0
        }
        mainHandler.post {
            viewsToRelease.forEach { wv ->
                runCatching {
                    if (js.isNotBlank()) {
                        wv.evaluateJavascript(js, null)
                    }
                }
                destroyWebViewSafely(wv)
            }
        }
    }

    private fun keepWebViewMediaAlive(wv: WebView, bgAudioJs: String) {
        if (WebViewRendererGuard.isDestroyed(wv)) return
        runCatching {
            WebViewRendererGuard.applyRendererPrioritySafely(wv, WebView.RENDERER_PRIORITY_IMPORTANT, false)
            wv.resumeTimers()
            wv.onResume()
            wv.dispatchWindowVisibilityChanged(View.VISIBLE)
            if (bgAudioJs.isNotBlank()) {
                wv.evaluateJavascript(bgAudioJs, null)
            }
        }
        // Re-assert timers & playback shortly after window detachment settles
        mainHandler.postDelayed({
            val stillRetained = synchronized(this) { retainedWebViews.containsValue(wv) }
            if (stillRetained && !WebViewRendererGuard.isDestroyed(wv)) {
                runCatching {
                    WebViewRendererGuard.applyRendererPrioritySafely(wv, WebView.RENDERER_PRIORITY_IMPORTANT, false)
                    wv.resumeTimers()
                    wv.onResume()
                    wv.dispatchWindowVisibilityChanged(View.VISIBLE)
                    if (bgAudioJs.isNotBlank()) {
                        wv.evaluateJavascript(bgAudioJs, null)
                    }
                }
            }
        }, 160L)
    }

    private fun destroyWebViewSafely(wv: WebView) {
        WebViewRendererGuard.destroyWebViewSafely(wv)
    }
}
