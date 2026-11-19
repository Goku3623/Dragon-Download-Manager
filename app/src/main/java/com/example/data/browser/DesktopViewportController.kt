package com.example.data.browser

import android.content.Context
import android.net.Uri
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Pure data plan describing how a Desktop-mode web page should be laid out and scaled
 * so that it fits edge-to-edge on the current WebView dimensions in both portrait and landscape.
 */
data class DesktopViewportPlan(
    val viewDipWidth: Int,
    val viewDipHeight: Int,
    val isLandscape: Boolean,
    val baseDesktopWidth: Int,
    val maxAutoDesktopWidth: Int,
    val initialScale: Double,
    val minScale: Double
)

/**
 * Coordinates Android WebView settings, User-Agent Client Hints, mobile-to-desktop URL upgrades,
 * and dynamic DIP-accurate `<meta name="viewport">` fitting so Desktop Mode fits cleanly on
 * mobile screens in both portrait and landscape orientations without clipping or blank margins.
 */
object DesktopViewportController {

    private const val PORTRAIT_BASE_DESKTOP_WIDTH = 1024
    private const val LANDSCAPE_BASE_DESKTOP_WIDTH = 1280
    private const val PORTRAIT_MAX_AUTO_WIDTH = 1440
    private const val LANDSCAPE_MAX_AUTO_WIDTH = 1520
    private const val MIN_ALLOWED_SCALE = 0.18

    private val MOBILE_HOST_UPGRADES: Map<String, String> = mapOf(
        "m.youtube.com" to "www.youtube.com",
        "m.facebook.com" to "www.facebook.com",
        "mbasic.facebook.com" to "www.facebook.com",
        "touch.facebook.com" to "www.facebook.com",
        "mobile.twitter.com" to "x.com",
        "m.twitter.com" to "x.com",
        "mobile.x.com" to "x.com",
        "m.x.com" to "x.com",
        "m.imdb.com" to "www.imdb.com",
        "m.reddit.com" to "www.reddit.com",
        "m.twitch.tv" to "www.twitch.tv",
        "m.ebay.com" to "www.ebay.com",
        "m.aliexpress.com" to "www.aliexpress.com",
        "m.soundcloud.com" to "soundcloud.com",
        "m.vimeo.com" to "vimeo.com"
    )

    private val defaultMobileUserAgentMetadataCache =
        java.util.WeakHashMap<WebView, UserAgentMetadata>()

    /**
     * Computes exact desktop CSS layout width and fit-to-screen scale from WebView DIP dimensions.
     * Rounding scale downward to 4 decimal places prevents sub-pixel horizontal overflow.
     */
    fun computeViewportPlan(viewDipWidth: Int, viewDipHeight: Int): DesktopViewportPlan {
        val safeW = viewDipWidth.coerceAtLeast(240)
        val safeH = viewDipHeight.coerceAtLeast(240)
        val isLandscape = safeW > safeH
        val baseW = if (isLandscape) {
            max(LANDSCAPE_BASE_DESKTOP_WIDTH, safeW)
        } else {
            max(PORTRAIT_BASE_DESKTOP_WIDTH, safeW)
        }
        val maxAutoW = if (isLandscape) {
            max(LANDSCAPE_MAX_AUTO_WIDTH, baseW)
        } else {
            max(PORTRAIT_MAX_AUTO_WIDTH, baseW)
        }
        val fitScale = computeFitScale(safeW, baseW)
        return DesktopViewportPlan(
            viewDipWidth = safeW,
            viewDipHeight = safeH,
            isLandscape = isLandscape,
            baseDesktopWidth = baseW,
            maxAutoDesktopWidth = maxAutoW,
            initialScale = fitScale,
            minScale = max(MIN_ALLOWED_SCALE, fitScale)
        )
    }

    fun computeFitScale(viewDipWidth: Int, targetCssWidth: Int): Double {
        val safeDipW = viewDipWidth.coerceAtLeast(240).toDouble()
        val safeTargetW = targetCssWidth.coerceAtLeast(240).toDouble()
        val raw = min(1.0, safeDipW / safeTargetW)
        val floored = floor(raw * 10000.0) / 10000.0
        return floored.coerceIn(MIN_ALLOWED_SCALE, 1.0)
    }

    /**
     * Upgrades well-known mobile subdomains (e.g. `m.youtube.com`, `en.m.wikipedia.org`)
     * to their desktop host when the user turns Desktop Mode ON so the desktop site loads cleanly.
     */
    fun rewriteUrlForDesktopToggle(rawUrl: String?, enablingDesktop: Boolean): String? {
        if (!enablingDesktop || rawUrl.isNullOrBlank()) return null
        val trimmed = rawUrl.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }
        val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.US) ?: return null

        val directDesktopHost = MOBILE_HOST_UPGRADES[host]
        val upgradedHost = when {
            directDesktopHost != null -> directDesktopHost
            host.endsWith(".m.wikipedia.org") -> host.replace(".m.wikipedia.org", ".wikipedia.org")
            host == "m.wikipedia.org" -> "en.wikipedia.org"
            else -> null
        } ?: return null

        val builder = uri.buildUpon().authority(
            if (uri.port > 0) "$upgradedHost:${uri.port}" else upgradedHost
        )
        // Strip mobile-forcing query flags like `app=m` or `mobileaction=...` on YouTube/Wikipedia
        if (uri.queryParameterNames.any { it.equals("app", true) || it.equals("mobileaction", true) }) {
            builder.clearQuery()
            for (name in uri.queryParameterNames) {
                if (name.equals("app", true) || name.equals("mobileaction", true)) continue
                for (value in uri.getQueryParameters(name)) {
                    builder.appendQueryParameter(name, value)
                }
            }
        }
        return builder.build().toString()
    }

    /**
     * Applies Android WebView settings + User-Agent Client Hints (`Sec-CH-UA-Mobile`, `Sec-CH-UA-Platform`)
     * for Desktop vs. Mobile browsing.
     */
    fun applyWebSettings(
        webView: WebView,
        userAgent: String,
        desktopMode: Boolean,
        resetInitialScale: Boolean = true
    ) {
        val settings = webView.settings
        if (!settings.useWideViewPort) settings.useWideViewPort = true
        if (!settings.loadWithOverviewMode) settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        if (!settings.builtInZoomControls) settings.builtInZoomControls = true
        if (settings.displayZoomControls) settings.displayZoomControls = false
        if (settings.textZoom != 100) settings.textZoom = 100
        // Disable Android Font Boosting / Text Autosizing in Desktop Mode so desktop grids & headers keep true proportions
        val desiredAlgo = if (desktopMode) {
            WebSettings.LayoutAlgorithm.NORMAL
        } else {
            WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
        }
        if (settings.layoutAlgorithm != desiredAlgo) {
            settings.layoutAlgorithm = desiredAlgo
        }
        if (settings.userAgentString != userAgent) {
            settings.userAgentString = userAgent
        }
        if (resetInitialScale) {
            // Reset legacy physical-pixel initialScale override ONLY on creation or Desktop Mode toggle
            runCatching { webView.setInitialScale(0) }
        }

        // Keep Sec-CH-UA Client Hints aligned with Desktop vs. Mobile mode (only when creating/toggling)
        if (resetInitialScale) {
            runCatching {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
                    val originalDefault = synchronized(defaultMobileUserAgentMetadataCache) {
                        defaultMobileUserAgentMetadataCache.getOrPut(webView) {
                            WebSettingsCompat.getUserAgentMetadata(settings)
                        }
                    }
                    if (desktopMode) {
                        val desktopMeta = UserAgentMetadata.Builder(originalDefault)
                            .setMobile(false)
                            .setPlatform("Linux")
                            .setPlatformVersion("6.6.0")
                            .setArchitecture("x86")
                            .setBitness(64)
                            .setModel("")
                            .build()
                        WebSettingsCompat.setUserAgentMetadata(settings, desktopMeta)
                    } else {
                        WebSettingsCompat.setUserAgentMetadata(settings, originalDefault)
                    }
                }
            }
        }
    }

    /**
     * Measures the live DIP dimensions of [webView] (falling back to DisplayMetrics before first layout).
     */
    fun measureWebViewDipSize(webView: WebView, context: Context = webView.context): Pair<Int, Int> {
        val dm = context.resources.displayMetrics
        val density = dm.density.coerceAtLeast(1f)
        val rawW = if (webView.width >= (200 * density).toInt()) {
            ceil(webView.width / density).toInt()
        } else {
            ceil(dm.widthPixels / density).toInt()
        }
        val rawH = if (webView.height >= (200 * density).toInt()) {
            ceil(webView.height / density).toInt()
        } else {
            ceil(dm.heightPixels / density).toInt()
        }
        return rawW.coerceAtLeast(240) to rawH.coerceAtLeast(240)
    }

    /**
     * Evaluates the desktop viewport sync script against [webView] using its exact current DIP bounds.
     */
    fun syncWebViewViewport(
        webView: WebView,
        desktopMode: Boolean,
        forceRefit: Boolean,
        measureOverflow: Boolean = false
    ) {
        if (BrowserPipController.isInPipModeFlow.value) return
        val (dipW, dipH) = measureWebViewDipSize(webView)
        if (dipW < 240 || dipH < 200) return
        val js = buildSyncInvocationScript(
            desktopMode = desktopMode,
            viewDipWidth = dipW,
            viewDipHeight = dipH,
            forceRefit = forceRefit,
            measureOverflow = measureOverflow
        )
        runCatching { webView.evaluateJavascript(js, null) }
    }

    fun buildSyncInvocationScript(
        desktopMode: Boolean,
        viewDipWidth: Int,
        viewDipHeight: Int,
        forceRefit: Boolean,
        measureOverflow: Boolean = false
    ): String {
        val safeW = viewDipWidth.coerceAtLeast(240)
        val safeH = viewDipHeight.coerceAtLeast(240)
        return """
            (function() {
              try {
                if (typeof window.__dragonSyncDesktopViewport !== 'function') {
                  $DESKTOP_VIEWPORT_CONTROLLER_JS
                }
                if (typeof window.__dragonSyncDesktopViewport === 'function') {
                  window.__dragonSyncDesktopViewport($desktopMode, $safeW, $safeH, $forceRefit, $measureOverflow);
                }
              } catch (e) {}
            })();
        """.trimIndent()
    }

    /**
     * Document-start + reusable JavaScript viewport manager.
     *
     * Key behaviors:
     * 1. Detects Desktop UA immediately at `document_start` to prevent any mobile-layout flash before first paint.
     * 2. Uses exact Android WebView DIP width/height (portrait & landscape) so scaling is pixel-accurate across all densities.
     * 3. Uses a 2-step Blink scale snap (lock to exact `fitScale` for 1 frame, then unlock `maximum-scale=5.0, user-scalable=yes`)
     *    on initial load, desktop toggle, and portrait <-> landscape rotation, while never fighting user pinch-to-zoom.
     * 4. Measures actual fixed-width desktop page overflow (`scrollWidth`) up to a safe cap (`1440px` portrait / `1520px` landscape)
     *    so fixed-width 1200px/1280px/1366px desktop sites also fit edge-to-edge without clipping.
     * 5. Guards `<meta name="viewport">` with a MutationObserver against SPAs that attempt to rewrite `width=device-width`.
     */
    const val DESKTOP_VIEWPORT_CONTROLLER_JS: String = """
(function() {
  if (window.__dragonDesktopViewportInstalled) return;
  window.__dragonDesktopViewportInstalled = true;

  var STYLE_ID = '__dragon_desktop_fit_style';
  var state = {
    enabled: false,
    pipViewportLocked: false,
    hasInitialFit: false,
    userInteractedZoom: false,
    activeTouches: 0,
    savedScrollX: 0,
    savedScrollY: 0,
    dipW: 0,
    dipH: 0,
    lastSnappedDipW: 0,
    lastTargetW: 0,
    lastFitScaleNum: 1.0,
    isApplying: false,
    unlockTimer: null,
    settleTimer: null,
    observer: null,
    observerTarget: null
  };
  window.__dragonDesktopViewportState = state;

  function getZoomRatio() {
    try {
      if (window.visualViewport) {
        var vvW = window.visualViewport.width;
        var layoutW = (document.documentElement && document.documentElement.clientWidth) || window.innerWidth || state.lastTargetW || 0;
        if (typeof vvW === 'number' && vvW > 20 && typeof layoutW === 'number' && layoutW > 20) {
          return layoutW / vvW;
        }
        var s = window.visualViewport.scale;
        if (typeof s === 'number' && s > 0) {
          var baseScale = (state.lastFitScaleNum > 0 && state.lastFitScaleNum < 0.99 && s < 0.99) ? state.lastFitScaleNum : 1.0;
          return s / baseScale;
        }
      }
    } catch (e) {}
    return 1.0;
  }

  function isUserZoomedIn() {
    if (state.userInteractedZoom) return true;
    return getZoomRatio() > 1.06;
  }

  // Track user pinch gestures passively so Desktop Mode NEVER fights or resets user zoom
  try {
    document.addEventListener('touchstart', function(ev) {
      var cnt = (ev && ev.touches) ? ev.touches.length : 0;
      state.activeTouches = cnt;
      if (cnt >= 2 && state.enabled && !state.pipViewportLocked) {
        state.userInteractedZoom = true;
        if (state.unlockTimer) {
          clearTimeout(state.unlockTimer);
          state.unlockTimer = null;
        }
        if (state.settleTimer) {
          clearTimeout(state.settleTimer);
          state.settleTimer = null;
        }
      }
    }, { passive: true, capture: true });
    document.addEventListener('touchend', function(ev) {
      state.activeTouches = (ev && ev.touches) ? ev.touches.length : 0;
    }, { passive: true, capture: true });
    document.addEventListener('touchcancel', function() {
      state.activeTouches = 0;
    }, { passive: true, capture: true });
    if (window.visualViewport && typeof window.visualViewport.addEventListener === 'function') {
      window.visualViewport.addEventListener('resize', function() {
        if (!state.enabled || state.pipViewportLocked || state.isApplying || state.unlockTimer) return;
        try {
          var ratio = getZoomRatio();
          if (ratio > 1.06) {
            state.userInteractedZoom = true;
          } else if (ratio <= 1.03) {
            state.userInteractedZoom = false;
          }
        } catch (e) {}
      });
    }
  } catch (e) {}

  function floorScale(dipW, targetW) {
    var raw = Math.min(1.0, Math.max(0.18, dipW / Math.max(240, targetW)));
    return (Math.floor(raw * 10000) / 10000).toFixed(4);
  }

  function computeBaseWidths(dipW, dipH) {
    var isLandscape = dipW > dipH;
    var baseW = isLandscape ? Math.max(1280, Math.ceil(dipW)) : Math.max(1024, Math.ceil(dipW));
    var maxAutoW = isLandscape ? Math.max(1520, baseW) : Math.max(1440, baseW);
    return { isLandscape: isLandscape, baseW: baseW, maxAutoW: maxAutoW };
  }

  function ensureFitStyle(enable) {
    try {
      var existing = document.getElementById(STYLE_ID);
      if (!enable) {
        if (existing && existing.parentNode) existing.parentNode.removeChild(existing);
        return;
      }
      if (!existing) {
        var st = document.createElement('style');
        st.id = STYLE_ID;
        st.textContent = 'html { -webkit-text-size-adjust: 100% !important; text-size-adjust: 100% !important; }';
        var parent = document.head || document.documentElement;
        if (parent) parent.appendChild(st);
      }
    } catch (e) {}
  }

  function getOrCreatePrimaryViewportMeta() {
    var metas = document.querySelectorAll('meta[name="viewport" i]');
    var primary = null;
    for (var i = 0; i < metas.length; i++) {
      var m = metas[i];
      if (!primary) {
        primary = m;
      } else {
        try {
          if (m.parentNode) m.parentNode.removeChild(m);
        } catch (e) {}
      }
    }
    var head = document.head || document.documentElement;
    if (!primary) {
      primary = document.createElement('meta');
      primary.setAttribute('name', 'viewport');
      primary.setAttribute('data-dragon-created', '1');
      if (head) {
        if (head.firstChild) head.insertBefore(primary, head.firstChild);
        else head.appendChild(primary);
      }
    } else {
      if (!primary.hasAttribute('data-dragon-orig-viewport') && !primary.hasAttribute('data-dragon-created')) {
        primary.setAttribute('data-dragon-orig-viewport', primary.getAttribute('content') || '');
      }
      if (document.head && primary.parentNode !== document.head) {
        try {
          if (document.head.firstChild) document.head.insertBefore(primary, document.head.firstChild);
          else document.head.appendChild(primary);
        } catch (e) {}
      }
    }
    return primary;
  }

  function writeViewportContent(meta, contentValue) {
    if (!meta) return;
    if (meta.getAttribute('content') === contentValue) return;
    state.isApplying = true;
    try {
      meta.setAttribute('content', contentValue);
      if (state.observer) {
        try { state.observer.takeRecords(); } catch (e) {}
      }
    } finally {
      state.isApplying = false;
    }
  }

  function measureReasonableContentWidth(baseW, maxAutoW) {
    try {
      var docEl = document.documentElement;
      var body = document.body;
      var scrollW = Math.max(
        docEl ? (docEl.scrollWidth || 0) : 0,
        body ? (body.scrollWidth || 0) : 0
      );
      if (scrollW > baseW + 18 && scrollW <= maxAutoW) {
        return Math.ceil(scrollW);
      }
      if (scrollW > maxAutoW && scrollW <= 2200) {
        return maxAutoW;
      }
    } catch (e) {}
    return baseW;
  }

  function applyDesktopViewport(forceSnap, measureOverflow) {
    if (!state.enabled || state.pipViewportLocked || state.dipW < 240 || state.dipH < 200) return;
    if (state.activeTouches > 0) return;

    var wInfo = computeBaseWidths(state.dipW, state.dipH);
    var widthChanged = state.lastSnappedDipW > 0 && Math.abs(state.dipW - state.lastSnappedDipW) >= 20;

    // Never fight or interrupt the user's pinch-zoom unless the physical orientation/width changed
    if (!widthChanged && isUserZoomedIn()) {
      return;
    }

    var meta = getOrCreatePrimaryViewportMeta();
    if (!meta) return;
    ensureFitStyle(true);

    if (widthChanged) {
      state.userInteractedZoom = false;
    }

    var targetW = wInfo.baseW;
    if (measureOverflow && !widthChanged) {
      targetW = measureReasonableContentWidth(wInfo.baseW, wInfo.maxAutoW);
    } else if (!widthChanged && state.lastTargetW >= wInfo.baseW) {
      targetW = Math.min(wInfo.maxAutoW, state.lastTargetW);
    }

    var fitScale = floorScale(state.dipW, targetW);
    state.lastFitScaleNum = parseFloat(fitScale) || 1.0;

    // Only perform the 1-frame Blink scale snap on genuine orientation change, PiP unlock, or very first fit.
    // Avoid repeated snaps/viewport churn during playback or user browsing.
    var targetExpanded = state.lastTargetW > 0 && targetW > state.lastTargetW + 16;
    var shouldSnap = !!(
      widthChanged ||
      (!state.hasInitialFit && forceSnap) ||
      (targetExpanded && !isUserZoomedIn())
    );
    state.lastTargetW = targetW;
    state.lastSnappedDipW = state.dipW;

    if (state.unlockTimer) {
      clearTimeout(state.unlockTimer);
      state.unlockTimer = null;
    }

    var relaxedContent =
      'width=' + targetW +
      ', initial-scale=' + fitScale +
      ', minimum-scale=' + fitScale +
      ', maximum-scale=5.0' +
      ', user-scalable=yes';

    if (shouldSnap) {
      state.hasInitialFit = true;
      var snapContent =
        'width=' + targetW +
        ', initial-scale=' + fitScale +
        ', minimum-scale=' + fitScale +
        ', maximum-scale=' + fitScale +
        ', user-scalable=yes';
      writeViewportContent(meta, snapContent);

      var unlockFn = function() {
        state.unlockTimer = null;
        if (!state.enabled || state.pipViewportLocked) return;
        var curMeta = getOrCreatePrimaryViewportMeta();
        writeViewportContent(curMeta, relaxedContent);
        try {
          if (window.scrollX > 0 && !isUserZoomedIn()) window.scrollTo(0, window.scrollY || 0);
        } catch (e) {}
      };
      state.unlockTimer = setTimeout(function() {
        if (typeof window.requestAnimationFrame === 'function') {
          window.requestAnimationFrame(unlockFn);
        } else {
          unlockFn();
        }
      }, 65);
    } else {
      state.hasInitialFit = true;
      writeViewportContent(meta, relaxedContent);
    }
  }

  function startViewportObserver() {
    if (typeof MutationObserver !== 'function') return;
    var target = document.head || document.documentElement;
    if (!target) return;
    if (state.observer && state.observerTarget === target) return;
    stopViewportObserver();
    try {
      state.observerTarget = target;
      var isHead = (target === document.head);
      state.observer = new MutationObserver(function(mutations) {
        if (!state.enabled || state.pipViewportLocked || state.isApplying) return;
        if (!isHead && document.head) {
          startViewportObserver();
          applyDesktopViewport(false, false);
          return;
        }
        var needsReapply = false;
        for (var i = 0; i < mutations.length; i++) {
          var mut = mutations[i];
          if (mut.type === 'attributes' && mut.target && mut.target.tagName === 'META') {
            var n = (mut.target.getAttribute('name') || '').toLowerCase();
            if (n === 'viewport') {
              var c = (mut.target.getAttribute('content') || '').toLowerCase();
              if (
                c.indexOf('device-width') !== -1 ||
                c.indexOf('width=' + state.lastTargetW) === -1 ||
                c.indexOf('user-scalable=no') !== -1 ||
                c.indexOf('user-scalable=0') !== -1
              ) {
                needsReapply = true;
                break;
              }
            }
          } else if (mut.type === 'childList' && mut.addedNodes) {
            for (var j = 0; j < mut.addedNodes.length; j++) {
              var node = mut.addedNodes[j];
              if (node && node.nodeType === 1) {
                var tag = (node.tagName || '').toUpperCase();
                if (tag === 'META' && ((node.getAttribute('name') || '').toLowerCase() === 'viewport')) {
                  needsReapply = true;
                  break;
                }
              }
            }
          }
          if (needsReapply) break;
        }
        if (needsReapply) {
          applyDesktopViewport(false, false);
        }
      });
      state.observer.observe(target, {
        childList: true,
        subtree: isHead,
        attributes: isHead,
        attributeFilter: ['content', 'name']
      });
    } catch (e) {}
  }

  function stopViewportObserver() {
    if (state.observer) {
      try { state.observer.disconnect(); } catch (e) {}
      state.observer = null;
      state.observerTarget = null;
    }
  }

  function scheduleOverflowCheck() {
    if (state.settleTimer) clearTimeout(state.settleTimer);
    state.settleTimer = setTimeout(function() {
      if (!state.enabled || state.pipViewportLocked || state.dipW < 240) return;
      if (state.activeTouches > 0 || isUserZoomedIn()) return;
      var wInfo = computeBaseWidths(state.dipW, state.dipH);
      var measured = measureReasonableContentWidth(wInfo.baseW, wInfo.maxAutoW);
      if (measured > state.lastTargetW + 16) {
        applyDesktopViewport(false, true);
      }
    }, 320);
  }

  window.__dragonSetPipViewportLock = function(locked) {
    var nextLock = !!locked;
    if (nextLock) {
      if (!state.pipViewportLocked) {
        state.savedScrollX = window.scrollX || 0;
        state.savedScrollY = window.scrollY || 0;
        state.pipLockedDesktopViewport = !!state.enabled;
      }
      state.pipViewportLocked = true;
      if (state.unlockTimer) { clearTimeout(state.unlockTimer); state.unlockTimer = null; }
      if (state.settleTimer) { clearTimeout(state.settleTimer); state.settleTimer = null; }
      if (state.enabled) {
        var meta = getOrCreatePrimaryViewportMeta();
        writeViewportContent(meta, 'width=device-width, initial-scale=1.0, minimum-scale=1.0, maximum-scale=1.0, user-scalable=no');
        try { window.scrollTo(0, 0); } catch (e) {}
      }
    } else {
      var wasLocked = state.pipViewportLocked;
      var hadDesktopLock = !!state.pipLockedDesktopViewport;
      state.pipViewportLocked = false;
      state.pipLockedDesktopViewport = false;
      if (!wasLocked) return;
      if (state.enabled) {
        state.hasInitialFit = false;
        state.userInteractedZoom = false;
        applyDesktopViewport(true, false);
        scheduleOverflowCheck();
        var restoreX = state.savedScrollX || 0;
        var restoreY = state.savedScrollY || 0;
        setTimeout(function() {
          try { window.scrollTo(restoreX, restoreY); } catch (e) {}
        }, 90);
      } else if (hadDesktopLock) {
        var metas = document.querySelectorAll('meta[name="viewport" i]');
        for (var i = 0; i < metas.length; i++) {
          var m = metas[i];
          if (m.hasAttribute('data-dragon-created')) {
            if (m.parentNode) m.parentNode.removeChild(m);
          } else if (m.hasAttribute('data-dragon-orig-viewport')) {
            writeViewportContent(m, m.getAttribute('data-dragon-orig-viewport') || 'width=device-width, initial-scale=1');
          }
        }
      }
    }
  };

  window.__dragonSyncDesktopViewport = function(enabled, viewDipW, viewDipH, forceRefit, measureOverflow) {
    state.enabled = !!enabled;
    if (!state.enabled) {
      stopViewportObserver();
      if (state.unlockTimer) { clearTimeout(state.unlockTimer); state.unlockTimer = null; }
      if (state.settleTimer) { clearTimeout(state.settleTimer); state.settleTimer = null; }
      ensureFitStyle(false);
      var metas = document.querySelectorAll('meta[name="viewport" i]');
      for (var i = 0; i < metas.length; i++) {
        var m = metas[i];
        if (m.hasAttribute('data-dragon-created')) {
          if (m.parentNode) m.parentNode.removeChild(m);
        } else if (m.hasAttribute('data-dragon-orig-viewport')) {
          writeViewportContent(m, m.getAttribute('data-dragon-orig-viewport') || 'width=device-width, initial-scale=1');
        }
      }
      state.lastSnappedDipW = 0;
      state.lastTargetW = 0;
      state.hasInitialFit = false;
      state.userInteractedZoom = false;
      return;
    }

    if (typeof viewDipW === 'number' && viewDipW >= 240) state.dipW = Math.round(viewDipW);
    if (typeof viewDipH === 'number' && viewDipH >= 200) state.dipH = Math.round(viewDipH);
    if (state.dipW < 240 || state.dipH < 200) {
      var sw = (window.screen && window.screen.width) ? window.screen.width : (window.innerWidth || 390);
      var sh = (window.screen && window.screen.height) ? window.screen.height : (window.innerHeight || 844);
      state.dipW = Math.max(240, Math.round(sw));
      state.dipH = Math.max(200, Math.round(sh));
    }

    startViewportObserver();
    if (!state.pipViewportLocked) {
      applyDesktopViewport(!!forceRefit, !!measureOverflow);
      if ((forceRefit || measureOverflow) && !isUserZoomedIn()) {
        scheduleOverflowCheck();
      }
    }
  };

  // Document-start fast bootstrap: if Desktop UA is already active, apply desktop viewport before first paint
  try {
    var ua = navigator.userAgent || '';
    var isDesktopUa = /X11;\s*Linux\s*x86_64/i.test(ua) && !/\bMobile\b/i.test(ua);
    if (isDesktopUa) {
      var bootW = (window.screen && window.screen.width >= 240) ? window.screen.width : (window.innerWidth || 390);
      var bootH = (window.screen && window.screen.height >= 200) ? window.screen.height : (window.innerHeight || 844);
      window.__dragonSyncDesktopViewport(true, bootW, bootH, true, false);
      document.addEventListener('DOMContentLoaded', function() {
        if (state.enabled && !isUserZoomedIn()) applyDesktopViewport(false, true);
      }, { once: true });
      window.addEventListener('load', function() {
        if (state.enabled && !isUserZoomedIn()) scheduleOverflowCheck();
      }, { once: true });
    }
  } catch (e) {}
})();
    """
}
