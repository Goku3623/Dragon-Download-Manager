package com.example.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.data.browser.WebViewRendererGuard
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.data.browser.BrowserStateRepository
import com.example.data.downloader.ApkPackageInstaller
import com.example.data.model.MediaFormatOption
import com.example.data.model.MediaInfo
import com.example.data.model.MediaPlatform
import com.example.data.repository.KernelRepository
import com.example.data.security.MalwareProtectionEngine
import com.example.service.DragonFloatingBrowserService
import com.example.ui.theme.DangerRed
import com.example.ui.theme.DragonRed
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningOrange
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.util.Locale

private data class FloatingBrowserTab(
    val id: Int,
    val url: String,
    val title: String
)

private enum class FloatingBrowserOverlayPanel {
    NONE,
    TABS,
    BOOKMARKS
}

/**
 * Keep-alive WebView tailored for floating overlay windows so minimizing to a bubble,
 * resizing the overlay very small, or losing focus when tapping other apps never signals
 * hidden/blur to Chromium or pauses YouTube / HTML5 video & audio playback — while still
 * reporting genuine user taps inside the WebView so Play/Pause controls always work immediately.
 */
private class FloatingKeepAliveWebView(context: Context) : WebView(context) {
    var onNativeUserTouch: (() -> Unit)? = null
    var onViewportResized: (() -> Unit)? = null

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(View.VISIBLE)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, View.VISIBLE)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        if (!hasWindowFocus) return
        super.onWindowFocusChanged(true)
    }

    override fun onPause() {
        // Keep floating browser video/audio decoders continuously active even when host app pauses
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        val action = ev?.actionMasked
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP) {
            onNativeUserTouch?.invoke()
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w != ow || h != oh) {
            onViewportResized?.invoke()
        }
    }
}

private const val FLOATING_MEDIA_KEEPALIVE_JS = """
(function() {
  try {
    if (!window.__dragonFloatingMediaGuardInstalled) {
      window.__dragonFloatingMediaGuardInstalled = true;
      window.__dragonFloatingMinimized = false;
      window.__dragonIsResizing = false;
      window.__dragonTransitionShieldUntil = 0;
      window.__dragonResizeShieldUntil = 0;
      window.__dragonLastResizeEndTs = 0;
      window.__dragonLastRestoreTs = 0;
      window.__dragonLastUserMediaTouchMs = 0;
      var trackedMedia = new Set();
      var watchdogTimer = null;
      var origPlay = HTMLMediaElement.prototype.play;
      var origPause = HTMLMediaElement.prototype.pause;

      var broadcastToFrames = function(payload) {
        try {
          var frames = document.querySelectorAll('iframe');
          for (var i = 0; i < frames.length; i++) {
            try {
              var cw = frames[i].contentWindow;
              if (cw) cw.postMessage({ __dragonMediaGuardMsg: true, payload: payload }, '*');
            } catch (e) {}
          }
        } catch (e) {}
      };

      var collectAllMedia = function() {
        var list = [];
        var seen = new Set();
        var scanRoot = function(root) {
          if (!root) return;
          try {
            var mediaNodes = root.querySelectorAll('video, audio');
            for (var i = 0; i < mediaNodes.length; i++) {
              if (!seen.has(mediaNodes[i])) {
                seen.add(mediaNodes[i]);
                list.push(mediaNodes[i]);
              }
            }
            var allEls = root.querySelectorAll('*');
            for (var j = 0; j < allEls.length; j++) {
              var sr = allEls[j].shadowRoot;
              if (sr) scanRoot(sr);
            }
          } catch (e) {}
        };
        scanRoot(document);
        return list;
      };

      // 1. Patch Page Visibility & Focus APIs at document_start BEFORE YouTube/player scripts execute
      try {
        var patchProp = function(obj, prop, val) {
          if (!obj) return;
          try {
            Object.defineProperty(obj, prop, {
              configurable: true,
              get: function() { return val; }
            });
          } catch (e) {}
        };
        patchProp(Document.prototype, 'hidden', false);
        patchProp(Document.prototype, 'webkitHidden', false);
        patchProp(Document.prototype, 'visibilityState', 'visible');
        patchProp(Document.prototype, 'webkitVisibilityState', 'visible');
        patchProp(document, 'hidden', false);
        patchProp(document, 'webkitHidden', false);
        patchProp(document, 'visibilityState', 'visible');
        patchProp(document, 'webkitVisibilityState', 'visible');
        try {
          Document.prototype.hasFocus = function() { return true; };
          document.hasFocus = function() { return true; };
        } catch (e) {}
      } catch (e) {}

      // 1b. Wrap IntersectionObserver so shrinking/clipping a small floating window NEVER
      // tricks YouTube or video websites into auto-pausing off-viewport/clipped media.
      try {
        var OrigIO = window.IntersectionObserver;
        if (OrigIO && !window.__dragonIOWrapped) {
          window.__dragonIOWrapped = true;
          var isMediaRelatedTarget = function(target) {
            try {
              if (!target || target === document.body || target === document.documentElement) return false;
              var tag = target.tagName;
              if (tag === 'VIDEO' || tag === 'AUDIO') return true;
              var media = collectAllMedia();
              for (var i = 0; i < media.length; i++) {
                var m = media[i];
                if (!m) continue;
                if (m === target) return true;
                if (target.contains && target.contains(m)) return true;
                if (m.contains && m.contains(target)) return true;
              }
            } catch (e) {}
            return false;
          };
          var shouldKeepMediaVisible = function() {
            var hasActive = false;
            trackedMedia.forEach(function(el) {
              if (el && el.isConnected && !el.ended && (el.__dragonShouldBePlaying || !el.paused)) {
                hasActive = true;
              }
            });
            var now = Date.now();
            return hasActive || !!window.__dragonFloatingMinimized || !!window.__dragonIsResizing || now < (window.__dragonResizeShieldUntil || 0);
          };
          var WrappedIO = function(callback, options) {
            var wrappedCb = function(entries, observer) {
              try {
                if (entries && entries.length && shouldKeepMediaVisible()) {
                  var mapped = [];
                  for (var i = 0; i < entries.length; i++) {
                    var entry = entries[i];
                    if (entry && (!entry.isIntersecting || entry.intersectionRatio < 0.95) && isMediaRelatedTarget(entry.target)) {
                      try {
                        var rect = entry.boundingClientRect;
                        var safeRect = (rect && rect.width > 0 && rect.height > 0) ? rect : {
                          top: 0, left: 0, right: 320, bottom: 180, width: 320, height: 180, x: 0, y: 0
                        };
                        mapped.push(new Proxy(entry, {
                          get: function(t, prop) {
                            if (prop === 'isIntersecting') return true;
                            if (prop === 'intersectionRatio') return 1;
                            if (prop === 'intersectionRect') return safeRect;
                            var val = t[prop];
                            return typeof val === 'function' ? val.bind(t) : val;
                          }
                        }));
                      } catch (pe) {
                        mapped.push(entry);
                      }
                    } else {
                      mapped.push(entry);
                    }
                  }
                  return callback.call(this, mapped, observer);
                }
              } catch (e) {}
              return callback.call(this, entries, observer);
            };
            return new OrigIO(wrappedCb, options);
          };
          WrappedIO.prototype = OrigIO.prototype;
          window.IntersectionObserver = WrappedIO;
        }
      } catch (e) {}

      // 2. Swallow visibilitychange / pagehide / freeze / window blur so YouTube never pauses
      var blockEvents = ['visibilitychange', 'webkitvisibilitychange', 'mozvisibilitychange', 'pagehide', 'freeze'];
      blockEvents.forEach(function(evtName) {
        try {
          document.addEventListener(evtName, function(e) {
            try { e.stopImmediatePropagation(); } catch (err) {}
          }, true);
          window.addEventListener(evtName, function(e) {
            try { e.stopImmediatePropagation(); } catch (err) {}
          }, true);
        } catch (e) {}
      });
      ['blur'].forEach(function(evtName) {
        try {
          window.addEventListener(evtName, function(e) {
            if (e && (e.target === window || e.target === document)) {
              try { e.stopImmediatePropagation(); } catch (err) {}
            }
          }, true);
          document.addEventListener(evtName, function(e) {
            if (e && (e.target === window || e.target === document)) {
              try { e.stopImmediatePropagation(); } catch (err) {}
            }
          }, true);
        } catch (e) {}
      });

      // 3. Track real user interaction inside the WebView (only when NOT minimized & NOT resizing).
      // Any genuine user tap immediately clears post-restore/post-resize shields so Play/Pause responds instantly.
      var markUserGesture = function(shouldBroadcast) {
        if (window.__dragonFloatingMinimized || window.__dragonIsResizing) return;
        var now = Date.now();
        window.__dragonLastUserMediaTouchMs = now;
        window.__dragonTransitionShieldUntil = 0;
        window.__dragonResizeShieldUntil = 0;
        window.__dragonLastResizeEndTs = 0;
        if (shouldBroadcast) {
          broadcastToFrames({ type: 'nativeTouch' });
        }
      };
      ['pointerdown', 'pointerup', 'touchstart', 'touchend', 'mousedown', 'mouseup', 'click', 'keydown'].forEach(function(ev) {
        try { window.addEventListener(ev, function() { markUserGesture(false); }, true); } catch (e) {}
      });

      window.__dragonMarkNativeUserTouch = function() {
        markUserGesture(true);
      };

      var isGenuineUserPause = function() {
        if (window.__dragonFloatingMinimized || window.__dragonIsResizing) return false;
        var now = Date.now();
        var lastTouch = window.__dragonLastUserMediaTouchMs || 0;
        if (lastTouch <= 0) return false;
        // Ignore stale touches from before the latest resize ended
        if (lastTouch <= (window.__dragonLastResizeEndTs || 0) + 120) return false;
        return (now - lastTouch) <= 1300;
      };

      var shouldProtectPlayback = function(el) {
        if (!el || el.ended) return false;
        if (!el.__dragonShouldBePlaying) return false;
        if (window.__dragonFloatingMinimized || window.__dragonIsResizing) return true;
        return !isGenuineUserPause();
      };

      var hasAnyTrackedPlaying = function() {
        var active = false;
        trackedMedia.forEach(function(el) {
          if (el && el.isConnected && !el.ended && el.__dragonShouldBePlaying) {
            active = true;
          }
        });
        return active;
      };

      var tryResumeElement = function(el) {
        try {
          if (!el || !el.isConnected || el.ended) return;
          if (el.paused && shouldProtectPlayback(el)) {
            var p = origPlay ? origPlay.call(el) : el.play();
            if (p && p.catch) p.catch(function() {});
          }
        } catch (e) {}
      };

      var syncWatchdog = function() {
        var now = Date.now();
        var urgent = !!window.__dragonFloatingMinimized || !!window.__dragonIsResizing ||
          now < (window.__dragonTransitionShieldUntil || 0) || now < (window.__dragonResizeShieldUntil || 0);
        var needsWatchdog = urgent || hasAnyTrackedPlaying();
        if (needsWatchdog && !watchdogTimer) {
          watchdogTimer = setInterval(function() {
            var t = Date.now();
            var stillUrgent = !!window.__dragonFloatingMinimized || !!window.__dragonIsResizing ||
              t < (window.__dragonTransitionShieldUntil || 0) || t < (window.__dragonResizeShieldUntil || 0);
            if (!stillUrgent && !hasAnyTrackedPlaying()) {
              clearInterval(watchdogTimer);
              watchdogTimer = null;
              return;
            }
            if (window.__dragonEnsureMediaPlaying) window.__dragonEnsureMediaPlaying();
          }, 420);
        }
      };

      var bindMediaElement = function(el) {
        if (!el || el.__dragonBound) return;
        el.__dragonBound = true;
        if (!el.paused && !el.ended) {
          el.__dragonShouldBePlaying = true;
          trackedMedia.add(el);
          syncWatchdog();
        }
        el.addEventListener('play', function() {
          el.__dragonShouldBePlaying = true;
          trackedMedia.add(el);
          syncWatchdog();
        }, true);
        el.addEventListener('playing', function() {
          el.__dragonShouldBePlaying = true;
          trackedMedia.add(el);
          syncWatchdog();
        }, true);
        el.addEventListener('ended', function() {
          el.__dragonShouldBePlaying = false;
        }, true);
        el.addEventListener('pause', function() {
          if (isGenuineUserPause()) {
            el.__dragonShouldBePlaying = false;
            return;
          }
          if (shouldProtectPlayback(el)) {
            setTimeout(function() { tryResumeElement(el); }, 35);
            setTimeout(function() { tryResumeElement(el); }, 150);
            setTimeout(function() { tryResumeElement(el); }, 380);
          }
        }, true);
      };

      // 4. Intercept HTMLMediaElement.prototype.play / pause at document_start
      try {
        if (origPlay && !HTMLMediaElement.prototype.__dragonPlayWrapped) {
          HTMLMediaElement.prototype.__dragonPlayWrapped = true;
          HTMLMediaElement.prototype.play = function() {
            try {
              bindMediaElement(this);
              this.__dragonShouldBePlaying = true;
              trackedMedia.add(this);
              syncWatchdog();
            } catch (e) {}
            return origPlay.apply(this, arguments);
          };
        }
        if (origPause && !HTMLMediaElement.prototype.__dragonPauseWrapped) {
          HTMLMediaElement.prototype.__dragonPauseWrapped = true;
          HTMLMediaElement.prototype.pause = function() {
            if (isGenuineUserPause()) {
              this.__dragonShouldBePlaying = false;
              return origPause.apply(this, arguments);
            }
            if (shouldProtectPlayback(this)) {
              return;
            }
            return origPause.apply(this, arguments);
          };
        }
      } catch (e) {}

      // Track dynamically added videos (YouTube SPA / AJAX navigation)
      document.addEventListener('play', function(e) {
        if (e && e.target && (e.target.tagName === 'VIDEO' || e.target.tagName === 'AUDIO')) {
          bindMediaElement(e.target);
          e.target.__dragonShouldBePlaying = true;
          trackedMedia.add(e.target);
          syncWatchdog();
        }
      }, true);

      // 5. Synchronous viewport resize listener inside EVERY frame (including cross-origin iframes)
      // arms the resize shield in 0ms before any page ResizeObserver/IntersectionObserver can pause video.
      var onFrameViewportResized = function() {
        try {
          if (window.__dragonSyntheticResize) return;
          var now = Date.now();
          window.__dragonResizeShieldUntil = Math.max(window.__dragonResizeShieldUntil || 0, now + 2600);
          window.__dragonLastResizeEndTs = now;
          var media = collectAllMedia();
          for (var i = 0; i < media.length; i++) {
            bindMediaElement(media[i]);
            if (!media[i].paused && !media[i].ended) {
              media[i].__dragonShouldBePlaying = true;
              trackedMedia.add(media[i]);
            }
          }
          if (hasAnyTrackedPlaying()) {
            syncWatchdog();
            [40, 150, 360, 750].forEach(function(ms) {
              setTimeout(function() {
                if (window.__dragonEnsureMediaPlaying) window.__dragonEnsureMediaPlaying();
              }, ms);
            });
          }
        } catch (e) {}
      };
      try { window.addEventListener('resize', onFrameViewportResized, true); } catch (e) {}
      try {
        if (window.visualViewport) {
          window.visualViewport.addEventListener('resize', onFrameViewportResized, true);
        }
      } catch (e) {}

      collectAllMedia().forEach(bindMediaElement);

      window.__dragonEnsureMediaPlaying = function() {
        try {
          collectAllMedia().forEach(bindMediaElement);
          var anyShouldPlay = false;
          var anyActuallyPlaying = false;
          trackedMedia.forEach(function(el) {
            if (!el || !el.isConnected) return;
            if (el.__dragonShouldBePlaying && !el.ended) {
              anyShouldPlay = true;
              if (!el.paused) {
                anyActuallyPlaying = true;
              } else if (shouldProtectPlayback(el)) {
                tryResumeElement(el);
              }
            }
          });
          // YouTube mobile & desktop player API fallback (only when NOT manually paused by user)
          var ytPlayer = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
          if (anyShouldPlay && ytPlayer && typeof ytPlayer.getPlayerState === 'function' && typeof ytPlayer.playVideo === 'function') {
            var st = ytPlayer.getPlayerState();
            if ((st === 2 || st === -1 || st === 5) && !isGenuineUserPause()) {
              try { ytPlayer.playVideo(); } catch (e) {}
            } else if (anyActuallyPlaying && st === 2 && !isGenuineUserPause()) {
              try { ytPlayer.playVideo(); } catch (e) {}
            }
          }
        } catch (e) {}
      };

      window.__dragonSetResizingState = function(resizing) {
        try {
          var isNowResizing = !!resizing;
          window.__dragonIsResizing = isNowResizing;
          var now = Date.now();
          if (isNowResizing) {
            window.__dragonResizeShieldUntil = Math.max(window.__dragonResizeShieldUntil || 0, now + 5000);
          } else {
            window.__dragonLastResizeEndTs = now;
            window.__dragonResizeShieldUntil = Math.max(window.__dragonResizeShieldUntil || 0, now + 2400);
          }
          collectAllMedia().forEach(function(el) {
            bindMediaElement(el);
            if (!el.paused && !el.ended) {
              el.__dragonShouldBePlaying = true;
              trackedMedia.add(el);
            }
          });
          syncWatchdog();
          if (window.__dragonEnsureMediaPlaying) window.__dragonEnsureMediaPlaying();
          broadcastToFrames({ type: 'setResizing', value: isNowResizing });
        } catch (e) {}
      };

      window.__dragonSetMinimizedState = function(minimized) {
        try {
          var wasMinimized = !!window.__dragonFloatingMinimized;
          var nextMinimized = !!minimized;
          window.__dragonFloatingMinimized = nextMinimized;
          var now = Date.now();
          if (nextMinimized) {
            window.__dragonTransitionShieldUntil = Math.max(window.__dragonTransitionShieldUntil || 0, now + 3500);
          } else {
            window.__dragonLastRestoreTs = now;
            // Short stabilization window on restore; cleared immediately on first user touch inside WebView!
            window.__dragonTransitionShieldUntil = Math.max(window.__dragonTransitionShieldUntil || 0, now + 1100);
          }
          collectAllMedia().forEach(function(el) {
            bindMediaElement(el);
            if (!el.paused && !el.ended) {
              el.__dragonShouldBePlaying = true;
              trackedMedia.add(el);
            }
          });
          syncWatchdog();
          if (window.__dragonEnsureMediaPlaying) window.__dragonEnsureMediaPlaying();
          // When expanding back from minimized bubble, re-sync YouTube/player UI hitboxes and Play/Pause button state
          if (wasMinimized && !nextMinimized) {
            var restoreMark = now;
            setTimeout(function() {
              try {
                window.__dragonSyntheticResize = true;
                try { window.dispatchEvent(new Event('resize')); } finally { window.__dragonSyntheticResize = false; }
                if ((window.__dragonLastUserMediaTouchMs || 0) <= restoreMark) {
                  var ytPlayer = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                  if (ytPlayer && typeof ytPlayer.getPlayerState === 'function' && typeof ytPlayer.playVideo === 'function') {
                    var st = ytPlayer.getPlayerState();
                    if (hasAnyTrackedPlaying() && (st === 2 || st === -1)) {
                      try { ytPlayer.playVideo(); } catch (e) {}
                    }
                  }
                  if (window.__dragonEnsureMediaPlaying) window.__dragonEnsureMediaPlaying();
                  // Re-sync m.youtube.com & custom player Play/Pause button state with live playing <video> elements
                  collectAllMedia().forEach(function(el) {
                    try {
                      if (el && el.isConnected && !el.paused && !el.ended) {
                        el.dispatchEvent(new Event('play'));
                        el.dispatchEvent(new Event('playing'));
                      }
                    } catch (e) {}
                  });
                }
              } catch (e) {}
            }, 180);
          }
          broadcastToFrames({ type: 'setMinimized', value: nextMinimized });
        } catch (e) {}
      };

      window.__dragonArmMediaShield = function(durationMs) {
        try {
          var dur = durationMs || 2600;
          window.__dragonTransitionShieldUntil = Math.max(window.__dragonTransitionShieldUntil || 0, Date.now() + dur);
          collectAllMedia().forEach(function(el) {
            bindMediaElement(el);
            if (!el.paused && !el.ended) {
              el.__dragonShouldBePlaying = true;
              trackedMedia.add(el);
            }
          });
          syncWatchdog();
          [50, 180, 420, 850].forEach(function(ms) {
            setTimeout(function() {
              if (window.__dragonEnsureMediaPlaying) window.__dragonEnsureMediaPlaying();
            }, ms);
          });
          broadcastToFrames({ type: 'armShield', durationMs: dur });
        } catch (e) {}
      };

      // Listen for parent-frame media guard commands inside child/cross-origin iframes
      window.addEventListener('message', function(e) {
        try {
          if (!e || !e.data || !e.data.__dragonMediaGuardMsg || !e.data.payload) return;
          var p = e.data.payload;
          if (p.type === 'setResizing' && window.__dragonSetResizingState) {
            window.__dragonSetResizingState(!!p.value);
          } else if (p.type === 'setMinimized' && window.__dragonSetMinimizedState) {
            window.__dragonSetMinimizedState(!!p.value);
          } else if (p.type === 'armShield' && window.__dragonArmMediaShield) {
            window.__dragonArmMediaShield(p.durationMs || 2600);
          } else if (p.type === 'nativeTouch') {
            markUserGesture(true);
          } else if (p.type === 'ensurePlaying' && window.__dragonEnsureMediaPlaying) {
            window.__dragonEnsureMediaPlaying();
            broadcastToFrames({ type: 'ensurePlaying' });
          }
        } catch (err) {}
      });
    }
  } catch (e) {}
})();
"""

/**
 * Self-contained Floating & Manually Resizable Dragon Browser Window.
 *
 * Designed for Android system overlay hosting (`TYPE_APPLICATION_OVERLAY`):
 * renders a live hardware-accelerated Android WebView, omnibox URL/search bar,
 * multi-tab switcher, bookmarks picker, desktop-site toggle, DDM download interceptor,
 * smart keyboard-focus handoff, bubble minimize, and full 2D edge/corner manual resizing
 * without ever using `android.app.Dialog`.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun FloatingDragonBrowserWindow(
    repository: KernelRepository,
    themedContext: Context,
    initialUrl: String,
    isMinimized: Boolean,
    isInputFocusEnabled: Boolean,
    windowWidthDp: Int,
    windowHeightDp: Int,
    onActiveUrlChanged: (String) -> Unit,
    onDragWindow: (dxPx: Float, dyPx: Float) -> Unit,
    onResizeWindow: (dwPx: Float, dhPx: Float, anchorLeft: Boolean) -> Unit,
    onApplySizePreset: (presetIndex: Int) -> Unit,
    onToggleMinimize: () -> Unit,
    onInputFocusNeeded: (Boolean) -> Unit,
    onExpandToFullScreen: (activeUrl: String) -> Unit,
    onCloseFloating: () -> Unit
) {
    val browserRepo = remember { repository.browserRepository }
    val bookmarks by browserRepo.bookmarksFlow.collectAsState()

    var nextTabId by remember { mutableIntStateOf(2) }
    var tabs by remember {
        mutableStateOf(
            listOf(
                FloatingBrowserTab(
                    id = 1,
                    url = initialUrl.ifBlank { BrowserStateRepository.DEFAULT_HOME_URL },
                    title = "Dragon Browser"
                )
            )
        )
    }
    var activeTabId by remember { mutableIntStateOf(1) }
    val activeTab = remember(tabs, activeTabId) {
        tabs.firstOrNull { it.id == activeTabId } ?: tabs.first()
    }

    var addressInput by remember(activeTab.id) { mutableStateOf(activeTab.url) }
    var isEditingAddress by remember { mutableStateOf(false) }
    var pageProgress by remember { mutableIntStateOf(100) }
    var isLoadingPage by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var isDesktopMode by remember { mutableStateOf(false) }
    var opacityStep by remember { mutableIntStateOf(0) } // 0 = 1.0f, 1 = 0.90f, 2 = 0.78f
    var sizePresetIndex by remember { mutableIntStateOf(1) }
    var activePanel by remember { mutableStateOf(FloatingBrowserOverlayPanel.NONE) }
    var showCompactOmnibox by remember { mutableStateOf(false) }
    var isActivelyResizing by remember { mutableStateOf(false) }
    val latestMinimized by rememberUpdatedState(isMinimized)
    val latestResizing by rememberUpdatedState(isActivelyResizing)

    val isCompactWindow = windowHeightDp < 330

    val webViews = remember { mutableStateMapOf<Int, WebView>() }
    var webViewRecoverySeq by remember { mutableIntStateOf(0) }
    var pausedTabUrlAfterCrash by remember { mutableStateOf<Pair<Int, String>?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            webViews.values.forEach { wv ->
                WebViewRendererGuard.destroyWebViewSafely(wv)
            }
            webViews.clear()
        }
    }

    val windowAlpha = when (opacityStep) {
        1 -> 0.90f
        2 -> 0.78f
        else -> 1.0f
    }

    fun normalizeBrowserInput(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return BrowserStateRepository.DEFAULT_HOME_URL
        if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) return trimmed
        if (!trimmed.contains(' ') && trimmed.contains('.') && !trimmed.endsWith('.')) {
            return "https://$trimmed"
        }
        val encoded = runCatching { URLEncoder.encode(trimmed, "UTF-8") }.getOrDefault(trimmed)
        return "https://www.google.com/search?q=$encoded"
    }

    fun getOrCreateWebView(tab: FloatingBrowserTab): WebView {
        webViews[tab.id]?.takeIf { !WebViewRendererGuard.isDestroyed(it) }?.let { return it }
        val wv = FloatingKeepAliveWebView(themedContext).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            WebViewRendererGuard.applyRendererPrioritySafely(
                this,
                WebView.RENDERER_PRIORITY_IMPORTANT,
                false
            )
            onNativeUserTouch = {
                if (!latestMinimized && !latestResizing) {
                    runCatching {
                        evaluateJavascript(
                            "if (window.__dragonMarkNativeUserTouch) window.__dragonMarkNativeUserTouch();",
                            null
                        )
                    }
                }
            }
            onViewportResized = {
                runCatching {
                    resumeTimers()
                    onResume()
                }
            }
            runCatching {
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            }
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                allowContentAccess = true
                allowFileAccess = false
            }

            // Inject media keep-alive guard at document_start BEFORE YouTube or video player scripts bind visibility/pause
            runCatching {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    WebViewCompat.addDocumentStartJavaScript(this, FLOATING_MEDIA_KEEPALIVE_JS, setOf("*"))
                }
            }

            setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
                if (url.isNullOrBlank()) return@setDownloadListener
                val guessedName = runCatching {
                    URLUtil.guessFileName(url, contentDisposition, mimetype)
                }.getOrDefault("download.bin").ifBlank { "download.bin" }
                val ext = guessedName.substringAfterLast('.', "").lowercase(Locale.US).ifBlank { "bin" }
                val isVideo = mimetype?.startsWith("video/", true) == true || ext in setOf("mp4", "mkv", "webm", "mov")
                val isAudio = mimetype?.startsWith("audio/", true) == true || ext in setOf("mp3", "m4a", "flac", "wav", "ogg")
                val isGeneric = !isVideo && !isAudio
                val cookies = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull().orEmpty()
                val headers = buildMap {
                    if (!userAgent.isNullOrBlank()) put("User-Agent", userAgent)
                    if (cookies.isNotBlank()) put("Cookie", cookies)
                }
                val format = MediaFormatOption(
                    id = "floating_browser_${System.currentTimeMillis()}",
                    qualityLabel = ext.uppercase(Locale.US),
                    container = ext,
                    mimeType = mimetype?.takeIf { it.isNotBlank() } ?: "application/octet-stream",
                    contentLengthBytes = if (contentLength > 0L) contentLength else -1L,
                    isAudioOnly = isAudio,
                    isGenericFile = isGeneric,
                    streamUrl = url,
                    requestHeaders = headers
                )
                val info = MediaInfo(
                    originalUrl = url,
                    canonicalUrl = url,
                    title = guessedName.substringBeforeLast('.').ifBlank { guessedName },
                    creator = Uri.parse(url).host?.removePrefix("www.") ?: "Dragon Browser",
                    durationSeconds = 0L,
                    durationFormatted = if (isGeneric) "File" else "Media",
                    thumbnailUrl = "",
                    platform = MediaPlatform.detectFromUrl(url).let {
                        if (it == MediaPlatform.UNSUPPORTED) MediaPlatform.DIRECT else it
                    },
                    videoFormats = if (isVideo) listOf(format) else emptyList(),
                    audioFormats = if (isAudio) listOf(format) else emptyList(),
                    extractorSource = "Floating Dragon Browser",
                    fileFormats = if (isGeneric) listOf(format) else emptyList()
                )
                repository.downloadManager.startDownload(info, format, customFilename = guessedName)
                ApkPackageInstaller.showToastOnMain(themedContext, "Sent to DDM: $guessedName")
            }

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val reqUrl = request?.url?.toString() ?: return null
                    if (MalwareProtectionEngine.stateFlow.value.malwareShieldEnabled) {
                        val verdict = MalwareProtectionEngine.inspectUrl(reqUrl, themedContext)
                        if (verdict.isBlocked) {
                            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    runCatching { view?.evaluateJavascript(FLOATING_MEDIA_KEEPALIVE_JS, null) }
                    val safeUrl = url.orEmpty()
                    if (activeTabId == tab.id) {
                        isLoadingPage = true
                        if (!isEditingAddress && safeUrl.isNotBlank()) {
                            addressInput = safeUrl
                        }
                        canGoBack = view?.canGoBack() == true
                        canGoForward = view?.canGoForward() == true
                        if (safeUrl.isNotBlank()) onActiveUrlChanged(safeUrl)
                    }
                    if (safeUrl.isNotBlank()) {
                        tabs = tabs.map { if (it.id == tab.id) it.copy(url = safeUrl) else it }
                    }
                }

                override fun onPageCommitVisible(view: WebView?, url: String?) {
                    super.onPageCommitVisible(view, url)
                    runCatching { view?.evaluateJavascript(FLOATING_MEDIA_KEEPALIVE_JS, null) }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    runCatching { view?.evaluateJavascript(FLOATING_MEDIA_KEEPALIVE_JS, null) }
                    val safeUrl = url.orEmpty()
                    val pageTitle = view?.title?.takeIf { it.isNotBlank() } ?: safeUrl
                    if (activeTabId == tab.id) {
                        isLoadingPage = false
                        pageProgress = 100
                        if (!isEditingAddress && safeUrl.isNotBlank()) {
                            addressInput = safeUrl
                        }
                        canGoBack = view?.canGoBack() == true
                        canGoForward = view?.canGoForward() == true
                        if (safeUrl.isNotBlank()) onActiveUrlChanged(safeUrl)
                    }
                    tabs = tabs.map {
                        if (it.id == tab.id) it.copy(
                            url = safeUrl.ifBlank { it.url },
                            title = pageTitle
                        ) else it
                    }
                }

                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: RenderProcessGoneDetail?
                ): Boolean {
                    return WebViewRendererGuard.handleRenderProcessGone(
                        view = view ?: this@apply,
                        detail = detail,
                        trackerKey = "floating_tab_${tab.id}"
                    ) { shouldAutoReload ->
                        webViews.entries
                            .filter { it.value === view || it.value === this@apply || it.key == tab.id }
                            .map { it.key }
                            .forEach { webViews.remove(it) }
                        if (activeTabId == tab.id) {
                            isLoadingPage = false
                            pageProgress = 100
                        }
                        if (!shouldAutoReload) {
                            pausedTabUrlAfterCrash = tab.id to tab.url
                            ApkPackageInstaller.showToastOnMain(
                                themedContext,
                                "Renderer crashed repeatedly; tap Reload to retry"
                            )
                        } else {
                            pausedTabUrlAfterCrash = null
                        }
                        webViewRecoverySeq++
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (activeTabId == tab.id) {
                        pageProgress = newProgress
                        isLoadingPage = newProgress in 1..99
                    }
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    if (!title.isNullOrBlank()) {
                        tabs = tabs.map { if (it.id == tab.id) it.copy(title = title) else it }
                    }
                }
            }

            val pausedEntry = pausedTabUrlAfterCrash
            val initialLoadUrl = if (pausedEntry != null && pausedEntry.first == tab.id) {
                pausedTabUrlAfterCrash = null
                "about:blank"
            } else {
                tab.url
            }
            loadUrl(initialLoadUrl)
        }
        webViews[tab.id] = wv
        return wv
    }

    val activeWebView = remember(activeTab.id, webViewRecoverySeq) { getOrCreateWebView(activeTab) }
    val coroutineScope = rememberCoroutineScope()
    var lastResizeShieldMs by remember { mutableLongStateOf(0L) }

    fun evalKeepAliveCommand(wv: WebView, command: String) {
        if (WebViewRendererGuard.isDestroyed(wv)) return
        runCatching {
            wv.resumeTimers()
            wv.onResume()
            wv.evaluateJavascript(
                "if (!window.__dragonFloatingMediaGuardInstalled) { $FLOATING_MEDIA_KEEPALIVE_JS } $command",
                null
            )
        }
    }

    fun setResizingMediaGuard(resizing: Boolean, throttleMs: Long = 0L) {
        isActivelyResizing = resizing
        val now = System.currentTimeMillis()
        if (throttleMs > 0L && (now - lastResizeShieldMs) < throttleMs) return
        lastResizeShieldMs = now
        val cmd = "if (window.__dragonSetResizingState) window.__dragonSetResizingState($resizing);"
        webViews.values.forEach { wv -> evalKeepAliveCommand(wv, cmd) }
    }

    fun protectPlaybackAcrossWindowTransition(
        durationMs: Long = 2600L,
        throttleMs: Long = 0L,
        minimizedOverride: Boolean? = null
    ) {
        val now = System.currentTimeMillis()
        if (throttleMs > 0L && (now - lastResizeShieldMs) < throttleMs) return
        lastResizeShieldMs = now
        val minFlag = minimizedOverride ?: latestMinimized
        val cmd = "if (window.__dragonSetMinimizedState) window.__dragonSetMinimizedState($minFlag); if (window.__dragonArmMediaShield) window.__dragonArmMediaShield($durationMs);"
        webViews.values.forEach { wv -> evalKeepAliveCommand(wv, cmd) }
    }

    fun toggleMinimizeWithMediaGuard(targetMinimized: Boolean) {
        var triggered = false
        fun performToggleOnce() {
            if (!triggered) {
                triggered = true
                onToggleMinimize()
            }
        }
        webViews.values.forEach { wv ->
            runCatching {
                wv.resumeTimers()
                wv.onResume()
            }
        }
        val shieldDur = if (targetMinimized) 3600 else 1100
        val script = "if (!window.__dragonFloatingMediaGuardInstalled) { $FLOATING_MEDIA_KEEPALIVE_JS } if (window.__dragonSetMinimizedState) window.__dragonSetMinimizedState($targetMinimized); if (window.__dragonArmMediaShield) window.__dragonArmMediaShield($shieldDur);"
        runCatching {
            activeWebView.evaluateJavascript(script) {
                performToggleOnce()
            }
        }.onFailure {
            performToggleOnce()
        }
        coroutineScope.launch {
            delay(50L)
            performToggleOnce()
        }
    }

    // Register live resume hook with DragonFloatingBrowserService so switching to another app
    // or MainActivity going to background immediately keeps floating video/audio playing.
    DisposableEffect(Unit) {
        DragonFloatingBrowserService.registerKeepAliveResumeHandler {
            protectPlaybackAcrossWindowTransition(durationMs = 3000L)
        }
        onDispose {
            DragonFloatingBrowserService.registerKeepAliveResumeHandler(null)
        }
    }

    LaunchedEffect(activeTab.id) {
        canGoBack = activeWebView.canGoBack()
        canGoForward = activeWebView.canGoForward()
        addressInput = activeWebView.url ?: activeTab.url
        onActiveUrlChanged(activeWebView.url ?: activeTab.url)
    }

    // Sync minimized / restored state cleanly without blocking user's Play/Pause tap after restore.
    LaunchedEffect(isMinimized) {
        val shieldDur = if (isMinimized) 3200L else 1000L
        protectPlaybackAcrossWindowTransition(durationMs = shieldDur, minimizedOverride = isMinimized)
        if (!isMinimized) {
            delay(140L)
            webViews.values.forEach { wv ->
                runCatching {
                    wv.resumeTimers()
                    wv.onResume()
                    wv.requestLayout()
                    wv.invalidate()
                }
            }
        } else {
            delay(260L)
            webViews.values.forEach { wv ->
                evalKeepAliveCommand(
                    wv,
                    "if (window.__dragonSetMinimizedState) window.__dragonSetMinimizedState(true); if (window.__dragonEnsureMediaPlaying) window.__dragonEnsureMediaPlaying();"
                )
            }
        }
    }

    // Whenever the floating window size changes (including very small sizes), verify playback
    // once resizing settles without spamming V8 during active drags.
    LaunchedEffect(windowWidthDp, windowHeightDp) {
        if (isMinimized) return@LaunchedEffect
        delay(140L)
        webViews.values.forEach { wv ->
            evalKeepAliveCommand(
                wv,
                "if (window.__dragonEnsureMediaPlaying) window.__dragonEnsureMediaPlaying();"
            )
        }
        delay(420L)
        webViews.values.forEach { wv ->
            evalKeepAliveCommand(
                wv,
                "if (window.__dragonEnsureMediaPlaying) window.__dragonEnsureMediaPlaying();"
            )
        }
    }

    // =========================================================================
    // PERSISTENT ROOT CONTAINER:
    // WebView ALWAYS stays attached at alpha 1f inside the window even when minimized
    // (clipped behind the opaque circular bubble) so HWUI/SurfaceFlinger/Chromium
    // NEVER detach, cull, or pause YouTube & HTML5 video playback!
    // =========================================================================
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (!isMinimized) {
                        Modifier
                            .alpha(windowAlpha)
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.5.dp, NeonCyan.copy(alpha = 0.65f), RoundedCornerShape(18.dp))
                    } else {
                        Modifier
                            .padding(5.dp)
                            .clip(CircleShape)
                    }
                )
        ) {
            // Top Chrome wrapped in a single slot so toggling compact omnibox NEVER detaches the sibling WebView!
            if (!isMinimized) {
                Column(modifier = Modifier.fillMaxWidth()) {
                // 1. DRAGGABLE WINDOW TITLE BAR
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                onDragWindow(dragAmount.x, dragAmount.y)
                            }
                        }
                        .padding(
                            horizontal = if (isCompactWindow) 6.dp else 8.dp,
                            vertical = if (isCompactWindow) 2.dp else 5.dp
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                AsyncImage(
                    model = remember(themedContext) {
                        ImageRequest.Builder(themedContext)
                            .data(R.drawable.ic_dragon_browser_v2_1795623003158)
                            .size(96, 96)
                            .crossfade(false)
                            .build()
                    },
                    contentDescription = "Dragon Browser Logo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .border(0.8.dp, DragonRed.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isCompactWindow) activeTab.title else "DRAGON BROWSER",
                        fontSize = if (isCompactWindow) 10.sp else 11.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.4.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!isCompactWindow) {
                        Text(
                            text = activeTab.title,
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Compact-mode toggle for URL/nav bar so small floating windows maximize video space
                if (isCompactWindow) {
                    IconButton(
                        onClick = { showCompactOmnibox = !showCompactOmnibox },
                        modifier = Modifier
                            .size(26.dp)
                            .testTag("floating_browser_compact_omnibox_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = if (showCompactOmnibox) "Hide Address Bar" else "Show Address Bar",
                            tint = if (showCompactOmnibox) NeonCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }

                // Keyboard / Web Page Typing Focus Toggle (auto-releases on outside tap)
                IconButton(
                    onClick = { onInputFocusNeeded(!isInputFocusEnabled) },
                    modifier = Modifier
                        .size(if (isCompactWindow) 26.dp else 28.dp)
                        .testTag("floating_browser_keyboard_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Keyboard,
                        contentDescription = if (isInputFocusEnabled) "Release Keyboard Focus" else "Enable Typing in Browser",
                        tint = if (isInputFocusEnabled) NeonCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                }
                // Opacity cycler
                IconButton(
                    onClick = { opacityStep = (opacityStep + 1) % 3 },
                    modifier = Modifier
                        .size(28.dp)
                        .testTag("floating_browser_opacity_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Opacity,
                        contentDescription = "Window Opacity",
                        tint = if (opacityStep > 0) NeonCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                }
                // Minimize to Bubble (arms minimized media guard inside V8 BEFORE shrinking window)
                IconButton(
                    onClick = { toggleMinimizeWithMediaGuard(targetMinimized = true) },
                    modifier = Modifier
                        .size(28.dp)
                        .testTag("floating_browser_minimize_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Remove,
                        contentDescription = "Minimize to Bubble",
                        tint = WarningOrange,
                        modifier = Modifier.size(17.dp)
                    )
                }
                // Expand to Full-Screen Dragon Browser
                IconButton(
                    onClick = { onExpandToFullScreen(activeWebView.url ?: activeTab.url) },
                    modifier = Modifier
                        .size(28.dp)
                        .testTag("floating_browser_expand_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.OpenInFull,
                        contentDescription = "Expand to Full Screen",
                        tint = SuccessGreen,
                        modifier = Modifier.size(15.dp)
                    )
                }
                // Close Floating Window
                IconButton(
                    onClick = onCloseFloating,
                    modifier = Modifier
                        .size(28.dp)
                        .testTag("floating_browser_close_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Floating Browser",
                        tint = DangerRed,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }

            // 2. COMPACT OMNIBOX & NAVIGATION BAR (Auto-collapses on very small windows to keep video large & playing)
            if (!isCompactWindow || showCompactOmnibox) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                IconButton(
                    onClick = { if (activeWebView.canGoBack()) activeWebView.goBack() },
                    enabled = canGoBack,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        modifier = Modifier.size(15.dp)
                    )
                }
                IconButton(
                    onClick = { if (activeWebView.canGoForward()) activeWebView.goForward() },
                    enabled = canGoForward,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Forward",
                        modifier = Modifier.size(15.dp)
                    )
                }

                // URL / Search Bar
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    border = BorderStroke(
                        0.9.dp,
                        if (isEditingAddress) NeonCyan else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(30.dp)
                        .clickable { onInputFocusNeeded(true) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicTextField(
                            value = addressInput,
                            onValueChange = { addressInput = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.SansSerif
                            ),
                            cursorBrush = SolidColor(NeonCyan),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(
                                onGo = {
                                    val target = normalizeBrowserInput(addressInput)
                                    addressInput = target
                                    isEditingAddress = false
                                    activeWebView.loadUrl(target)
                                    onInputFocusNeeded(false)
                                }
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .onFocusChanged { focusState ->
                                    isEditingAddress = focusState.isFocused
                                    if (focusState.isFocused) onInputFocusNeeded(true)
                                }
                                .testTag("floating_browser_url_input")
                        )
                        if (isEditingAddress && addressInput.isNotEmpty()) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear URL",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .size(15.dp)
                                    .clickable { addressInput = "" }
                            )
                        } else {
                            Icon(
                                imageVector = if (isLoadingPage) Icons.Default.Close else Icons.Default.Refresh,
                                contentDescription = if (isLoadingPage) "Stop" else "Reload",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .size(15.dp)
                                    .clickable {
                                        WebViewRendererGuard.resetCrashHistory("floating_tab_${activeTab.id}")
                                        if (isLoadingPage) {
                                            activeWebView.stopLoading()
                                        } else if (activeWebView.url == "about:blank" && activeTab.url != "about:blank") {
                                            activeWebView.loadUrl(activeTab.url)
                                        } else {
                                            activeWebView.reload()
                                        }
                                    }
                            )
                        }
                    }
                }

                // Desktop Mode toggle
                IconButton(
                    onClick = {
                        isDesktopMode = !isDesktopMode
                        val ua = if (isDesktopMode) {
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
                        } else {
                            WebSettings.getDefaultUserAgent(themedContext)
                        }
                        activeWebView.settings.userAgentString = ua
                        activeWebView.settings.useWideViewPort = true
                        activeWebView.settings.loadWithOverviewMode = true
                        activeWebView.reload()
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DesktopWindows,
                        contentDescription = "Toggle Desktop Site",
                        tint = if (isDesktopMode) NeonCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                }

                // Bookmarks button
                IconButton(
                    onClick = {
                        activePanel = if (activePanel == FloatingBrowserOverlayPanel.BOOKMARKS) {
                            FloatingBrowserOverlayPanel.NONE
                        } else {
                            FloatingBrowserOverlayPanel.BOOKMARKS
                        }
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Bookmarks,
                        contentDescription = "Bookmarks",
                        tint = if (activePanel == FloatingBrowserOverlayPanel.BOOKMARKS) WarningOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                }

                // Tabs Counter Pill
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (activePanel == FloatingBrowserOverlayPanel.TABS) NeonCyan.copy(alpha = 0.22f)
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(0.9.dp, NeonCyan.copy(alpha = 0.7f)),
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            activePanel = if (activePanel == FloatingBrowserOverlayPanel.TABS) {
                                FloatingBrowserOverlayPanel.NONE
                            } else {
                                FloatingBrowserOverlayPanel.TABS
                            }
                        }
                        .testTag("floating_browser_tabs_btn")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "${tabs.size}",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = NeonCyan
                        )
                    }
                }
            }
            } // end if (!isCompactWindow || showCompactOmnibox)

            // Loading progress bar
            if (isLoadingPage && pageProgress in 1..99) {
                LinearProgressIndicator(
                    progress = { pageProgress / 100f },
                    color = NeonCyan,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                )
            } else {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
                } // end top chrome Column
            } // end if (!isMinimized) header & omnibox

            // 3. LIVE WEBVIEW CONTENT AREA (Unconditionally composed in a persistent keyed slot so WebView is NEVER detached!)
            key("floating_webview_persistent_slot") {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                key(activeTab.id, webViewRecoverySeq) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            android.widget.FrameLayout(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                (activeWebView.parent as? ViewGroup)?.removeView(activeWebView)
                                addView(
                                    activeWebView,
                                    android.widget.FrameLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                )
                            }
                        },
                        update = { container ->
                            if (activeWebView.parent !== container) {
                                container.removeAllViews()
                                (activeWebView.parent as? ViewGroup)?.removeView(activeWebView)
                                container.addView(
                                    activeWebView,
                                    android.widget.FrameLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                )
                            }
                        },
                        onRelease = { container ->
                            container.removeAllViews()
                        }
                    )
                }

                // In-Window Tabs / Bookmarks Panel (zero android.app.Dialog!)
                if (!isMinimized && activePanel != FloatingBrowserOverlayPanel.NONE) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.56f))
                            .clickable { activePanel = FloatingBrowserOverlayPanel.NONE },
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Surface(
                            shape = RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.45f)),
                            modifier = Modifier
                                .fillMaxWidth(0.94f)
                                .heightIn(max = 260.dp)
                                .clickable(enabled = false) {}
                        ) {
                            when (activePanel) {
                                FloatingBrowserOverlayPanel.TABS -> {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "Floating Tabs (${tabs.size})",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = NeonCyan
                                            )
                                            Button(
                                                onClick = {
                                                    val newId = nextTabId++
                                                    val newTab = FloatingBrowserTab(
                                                        id = newId,
                                                        url = BrowserStateRepository.DEFAULT_HOME_URL,
                                                        title = "Google"
                                                    )
                                                    tabs = tabs + newTab
                                                    activeTabId = newId
                                                    activePanel = FloatingBrowserOverlayPanel.NONE
                                                },
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                                modifier = Modifier.height(28.dp)
                                            ) {
                                                Text("+ New Tab", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            items(tabs, key = { it.id }) { tab ->
                                                val isSelected = tab.id == activeTabId
                                                Surface(
                                                    shape = RoundedCornerShape(9.dp),
                                                    color = if (isSelected) NeonCyan.copy(alpha = 0.16f)
                                                    else MaterialTheme.colorScheme.surfaceContainerLow,
                                                    border = if (isSelected) BorderStroke(1.dp, NeonCyan) else null,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(9.dp))
                                                        .clickable {
                                                            activeTabId = tab.id
                                                            activePanel = FloatingBrowserOverlayPanel.NONE
                                                        }
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text(
                                                                text = tab.title,
                                                                fontSize = 11.5.sp,
                                                                fontWeight = FontWeight.SemiBold,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                            Text(
                                                                text = tab.url,
                                                                fontSize = 9.5.sp,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                        }
                                                        if (tabs.size > 1) {
                                                            IconButton(
                                                                onClick = {
                                                                    webViews.remove(tab.id)?.let { wv ->
                                                                        runCatching {
                                                                            wv.stopLoading()
                                                                            (wv.parent as? ViewGroup)?.removeView(wv)
                                                                            wv.destroy()
                                                                        }
                                                                    }
                                                                    val remaining = tabs.filterNot { it.id == tab.id }
                                                                    tabs = remaining
                                                                    if (activeTabId == tab.id) {
                                                                        activeTabId = remaining.last().id
                                                                    }
                                                                },
                                                                modifier = Modifier.size(24.dp)
                                                            ) {
                                                                Icon(
                                                                    imageVector = Icons.Default.Close,
                                                                    contentDescription = "Close Tab",
                                                                    tint = DangerRed,
                                                                    modifier = Modifier.size(14.dp)
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                FloatingBrowserOverlayPanel.BOOKMARKS -> {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "Bookmarks (${bookmarks.size})",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = WarningOrange
                                            )
                                            TextButton(
                                                onClick = {
                                                    val u = activeWebView.url ?: activeTab.url
                                                    val t = activeWebView.title ?: activeTab.title
                                                    browserRepo.addOrUpdateBookmark(u, t)
                                                    ApkPackageInstaller.showToastOnMain(themedContext, "Bookmarked $t")
                                                },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                                modifier = Modifier.height(26.dp)
                                            ) {
                                                Text("+ Save Page", fontSize = 10.5.sp)
                                            }
                                        }
                                        if (bookmarks.isEmpty()) {
                                            Text(
                                                text = "No bookmarks saved yet.",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(vertical = 12.dp)
                                            )
                                        } else {
                                            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                items(bookmarks, key = { it.id }) { bm ->
                                                    Surface(
                                                        shape = RoundedCornerShape(8.dp),
                                                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clip(RoundedCornerShape(8.dp))
                                                            .clickable {
                                                                activeWebView.loadUrl(bm.url)
                                                                addressInput = bm.url
                                                                activePanel = FloatingBrowserOverlayPanel.NONE
                                                            }
                                                    ) {
                                                        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                                                            Text(
                                                                text = bm.title,
                                                                fontSize = 11.sp,
                                                                fontWeight = FontWeight.SemiBold,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                            Text(
                                                                text = bm.url,
                                                                fontSize = 9.5.sp,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                FloatingBrowserOverlayPanel.NONE -> {}
                            }
                        }
                    }
                }
            }
            } // end key("floating_webview_persistent_slot")

            if (!isMinimized) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

                // 4. BOTTOM STATUS + MANUAL RESIZE BAR (Bottom-Left + Bottom Edge + Bottom-Right Corner)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (isCompactWindow) 24.dp else 30.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Bottom-Left manual resize corner
                    Box(
                        modifier = Modifier
                            .size(if (isCompactWindow) 26.dp else 30.dp)
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = { setResizingMediaGuard(true) },
                                    onDragEnd = { setResizingMediaGuard(false) },
                                    onDragCancel = { setResizingMediaGuard(false) },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        setResizingMediaGuard(true, throttleMs = 280L)
                                        onResizeWindow(dragAmount.x, dragAmount.y, true)
                                    }
                                )
                            }
                            .testTag("floating_browser_resize_bl"),
                        contentAlignment = Alignment.Center
                    ) {
                        BrowserCornerResizeGrip(
                            isLeft = true,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                        )
                    }

                    // Bottom edge height resize + quick status + size preset toggle
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = { setResizingMediaGuard(true) },
                                    onDragEnd = { setResizingMediaGuard(false) },
                                    onDragCancel = { setResizingMediaGuard(false) },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        setResizingMediaGuard(true, throttleMs = 280L)
                                        onResizeWindow(0f, dragAmount.y, false)
                                    }
                                )
                            }
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (isInputFocusEnabled) "Keyboard Active" else if (isCompactWindow) "Mini Mode" else "Multitask Ready",
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (isInputFocusEnabled) NeonCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = NeonCyan.copy(alpha = 0.14f),
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    setResizingMediaGuard(true)
                                    sizePresetIndex = (sizePresetIndex + 1) % 3
                                    onApplySizePreset(sizePresetIndex)
                                    coroutineScope.launch {
                                        delay(260L)
                                        setResizingMediaGuard(false)
                                    }
                                }
                                .testTag("floating_browser_size_preset")
                        ) {
                            Text(
                                text = "${windowWidthDp}×${windowHeightDp}dp",
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = NeonCyan,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    // Bottom-Right primary 2D manual resize handle
                    Box(
                        modifier = Modifier
                            .size(if (isCompactWindow) 28.dp else 34.dp)
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = { setResizingMediaGuard(true) },
                                    onDragEnd = { setResizingMediaGuard(false) },
                                    onDragCancel = { setResizingMediaGuard(false) },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        setResizingMediaGuard(true, throttleMs = 280L)
                                        onResizeWindow(dragAmount.x, dragAmount.y, false)
                                    }
                                )
                            }
                            .testTag("floating_browser_resize_br"),
                        contentAlignment = Alignment.Center
                    ) {
                        BrowserCornerResizeGrip(isLeft = false, color = NeonCyan)
                    }
                }
            }
        }

        if (!isMinimized) {
            // Invisible Right-Edge horizontal resize strip
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(9.dp)
                    .fillMaxHeight(0.74f)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { setResizingMediaGuard(true) },
                            onDragEnd = { setResizingMediaGuard(false) },
                            onDragCancel = { setResizingMediaGuard(false) },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                setResizingMediaGuard(true, throttleMs = 280L)
                                onResizeWindow(dragAmount.x, 0f, false)
                            }
                        )
                    }
            )
        } else {
            // =========================================================================
            // MINIMIZED FLOATING DRAGON BROWSER BUBBLE (Renders ON TOP while WebView
            // stays alive & attached underneath so video/audio NEVER pauses!)
            // =========================================================================
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(3.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF0B0F1E))
                    .border(2.dp, DragonRed.copy(alpha = 0.9f), CircleShape)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            onDragWindow(dragAmount.x, dragAmount.y)
                        }
                    }
                    .clickable {
                        toggleMinimizeWithMediaGuard(targetMinimized = false)
                    }
                    .testTag("floating_browser_bubble"),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = remember(themedContext) {
                        ImageRequest.Builder(themedContext)
                            .data(R.drawable.ic_dragon_browser_v2_1795623003158)
                            .size(144, 144)
                            .crossfade(false)
                            .build()
                    },
                    contentDescription = "Restore Floating Dragon Browser",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(3.dp)
                        .clip(CircleShape)
                )
            }
        }
    }
}

@Composable
private fun BrowserCornerResizeGrip(isLeft: Boolean, color: Color) {
    Canvas(modifier = Modifier.size(16.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 2.dp.toPx()
        if (isLeft) {
            drawLine(color, Offset(2f, h * 0.35f), Offset(w * 0.65f, h - 2f), stroke, StrokeCap.Round)
            drawLine(color, Offset(2f, h * 0.7f), Offset(w * 0.3f, h - 2f), stroke, StrokeCap.Round)
        } else {
            drawLine(color, Offset(w * 0.35f, h - 2f), Offset(w - 2f, h * 0.35f), stroke, StrokeCap.Round)
            drawLine(color, Offset(w * 0.7f, h - 2f), Offset(w - 2f, h * 0.7f), stroke, StrokeCap.Round)
        }
    }
}
