package com.example.data.browser

import android.content.Context
import android.content.MutableContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView

/**
 * Application-scoped retention store for live playing browser [WebView] instances when Android
 * dismisses or destroys the host Activity after the user taps the System PiP close (X) button.
 *
 * Swaps each WebView's [MutableContextWrapper] base context to [Context.getApplicationContext]
 * while retained (preventing Activity leaks), keeps timers/media active in the foreground media
 * service process, routes notification Play/Pause/Stop controls directly to the retained WebView,
 * and hands the live WebView back seamlessly when the user returns to the browser.
 */
object BackgroundMediaWebViewStore {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val retainedWebViews = LinkedHashMap<String, WebView>()

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
        }

        // Release any previously retained views that are not in the new set
        val staleEntries = retainedWebViews.entries.filter { it.key !in keepIds }
        for ((staleKey, staleWv) in staleEntries) {
            retainedWebViews.remove(staleKey)
            destroyWebViewSafely(staleWv)
        }

        for ((tabId, wv) in webViews) {
            if (tabId in keepIds) {
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
    }

    @Synchronized
    fun takeRetainedWebViews(newActivityContext: Context): Map<String, WebView> {
        if (retainedWebViews.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, WebView>(retainedWebViews.size)
        for ((tabId, wv) in retainedWebViews) {
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
        return result
    }

    fun dispatchTogglePlayOnRetained(): Boolean {
        val target: WebView
        val js: String
        synchronized(this) {
            if (retainedWebViews.isEmpty()) return false
            val targetId = retainedFloatingTabId ?: retainedActiveTabId
            target = (targetId?.let { retainedWebViews[it] } ?: retainedWebViews.values.firstOrNull())
                ?: return false
            js = togglePlayJs
        }
        if (js.isBlank()) return false
        mainHandler.post {
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
        runCatching {
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
            if (stillRetained) {
                runCatching {
                    wv.resumeTimers()
                    wv.onResume()
                    if (bgAudioJs.isNotBlank()) {
                        wv.evaluateJavascript(bgAudioJs, null)
                    }
                }
            }
        }, 160L)
    }

    private fun destroyWebViewSafely(wv: WebView) {
        runCatching {
            wv.resumeTimers()
            wv.stopLoading()
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        }
    }
}
