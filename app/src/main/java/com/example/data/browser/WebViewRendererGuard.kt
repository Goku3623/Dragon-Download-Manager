package com.example.data.browser

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import java.util.Collections
import java.util.WeakHashMap

/**
 * Central safety guard for Android WebView Chromium renderer lifecycle & crash recovery.
 *
 * Ensures every WebView in the app:
 * 1. Detaches and destroys crashed/stale WebView instances exactly once (never double-destroyed or reused).
 * 2. Prevents `aw_browser_terminator.cc` renderer kills (`code -1`) from terminating the host app process
 *    by consistently handling `onRenderProcessGone` and returning `true`.
 * 3. Breaks infinite crash-reload loops if a specific page causes repeated renderer crashes within 15s.
 */
object WebViewRendererGuard {

    private const val TAG = "WebViewRendererGuard"
    private const val CRASH_WINDOW_MS = 15_000L
    private const val MAX_AUTO_RELOAD_CRASHES_IN_WINDOW = 2

    private val mainHandler = Handler(Looper.getMainLooper())
    private val destroyedViews: MutableSet<WebView> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    private val crashTimestampsByKey = LinkedHashMap<String, MutableList<Long>>()

    fun isDestroyed(view: WebView?): Boolean {
        if (view == null) return true
        return synchronized(destroyedViews) { view in destroyedViews }
    }

    /**
     * Marks [view] as destroyed. Returns `true` if this call is the first to mark it,
     * or `false` if it was already destroyed.
     */
    private fun markDestroyed(view: WebView): Boolean {
        return synchronized(destroyedViews) { destroyedViews.add(view) }
    }

    fun destroyWebViewSafely(view: WebView?) {
        if (view == null) return
        if (!markDestroyed(view)) return
        BackgroundMediaWebViewStore.removeDeadWebView(view)
        runCatching { view.stopLoading() }
        runCatching { (view.parent as? ViewGroup)?.removeView(view) }
        runCatching { view.removeAllViews() }
        runCatching { view.destroy() }
    }

    fun applyRendererPrioritySafely(
        view: WebView?,
        priority: Int,
        waivedWhenNotVisible: Boolean
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (view == null || isDestroyed(view)) return
        runCatching {
            view.setRendererPriorityPolicy(priority, waivedWhenNotVisible)
        }
    }

    fun recordCrashAndShouldAutoReload(
        trackerKey: String,
        didCrash: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        synchronized(crashTimestampsByKey) {
            val list = crashTimestampsByKey.getOrPut(trackerKey) { mutableListOf() }
            list.removeAll { ts -> (nowMs - ts) > CRASH_WINDOW_MS }
            list.add(nowMs)
            // Allow 1 transparent auto-reload in a 15s window; pause auto-reload if it crashes repeatedly.
            return list.size < MAX_AUTO_RELOAD_CRASHES_IN_WINDOW
        }
    }

    fun resetCrashHistory(trackerKey: String) {
        synchronized(crashTimestampsByKey) {
            crashTimestampsByKey.remove(trackerKey)
        }
    }

    fun handleRenderProcessGone(
        view: WebView?,
        detail: RenderProcessGoneDetail?,
        trackerKey: String,
        onRecovered: (shouldAutoReload: Boolean) -> Unit
    ): Boolean {
        val didCrash = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { detail?.didCrash() == true }.getOrDefault(false)
        } else {
            false
        }
        val priorityAtExit = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { detail?.rendererPriorityAtExit() ?: -1 }.getOrDefault(-1)
        } else {
            -1
        }
        Log.w(
            TAG,
            "Chromium WebView renderer gone (key=$trackerKey, didCrash=$didCrash, priorityAtExit=$priorityAtExit)"
        )

        destroyWebViewSafely(view)
        val shouldAutoReload = recordCrashAndShouldAutoReload(trackerKey, didCrash)

        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCatching { onRecovered(shouldAutoReload) }
        } else {
            mainHandler.post { runCatching { onRecovered(shouldAutoReload) } }
        }
        return true
    }

    fun clearCrashTrackerForTests() {
        synchronized(crashTimestampsByKey) {
            crashTimestampsByKey.clear()
        }
    }
}
