package com.example.ui.screens

import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.browser.BrowserExtension
import com.example.data.browser.SavedPasswordEntry
import kotlin.math.roundToInt
import com.example.data.browser.WebConsoleLogEntry
import com.example.data.browser.WebNetworkLogEntry
import com.example.data.downloader.DnsProviderMode
import com.example.data.downloader.WarpDnsTelemetry
import com.example.ui.theme.DangerRed
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningOrange
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class InspectorDomNode(
    val tag: String,
    val idAttr: String,
    val classAttr: String,
    val selector: String,
    val textSnippet: String,
    val attrsSummary: String,
    val depth: Int,
    val childCount: Int,
    val rectLabel: String
)

data class InspectorSelectedElement(
    val tag: String,
    val idAttr: String,
    val classAttr: String,
    val selector: String,
    val rectLabel: String,
    val textContent: String,
    val outerHtmlPreview: String,
    val attributes: List<Pair<String, String>>,
    val computedStyles: List<Pair<String, String>>
)

data class InspectorResourceTiming(
    val name: String,
    val initiatorType: String,
    val durationMs: Long,
    val transferBytes: Long
)

data class InspectorSnapshot(
    val url: String = "",
    val title: String = "",
    val domain: String = "",
    val protocol: String = "",
    val readyState: String = "",
    val compatMode: String = "",
    val charset: String = "",
    val viewportLabel: String = "",
    val userAgent: String = "",
    val dnsMs: Long = 0L,
    val tcpMs: Long = 0L,
    val ttfbMs: Long = 0L,
    val domInteractiveMs: Long = 0L,
    val totalLoadMs: Long = 0L,
    val totalElements: Int = 0,
    val scriptCount: Int = 0,
    val stylesheetCount: Int = 0,
    val imageCount: Int = 0,
    val linkCount: Int = 0,
    val formCount: Int = 0,
    val iframeCount: Int = 0,
    val metaTags: List<Pair<String, String>> = emptyList(),
    val localStorageItems: List<Pair<String, String>> = emptyList(),
    val sessionStorageItems: List<Pair<String, String>> = emptyList(),
    val documentCookie: String = "",
    val domNodes: List<InspectorDomNode> = emptyList(),
    val selectedElement: InspectorSelectedElement? = null,
    val resourceTimings: List<InspectorResourceTiming> = emptyList(),
    val htmlSourcePreview: String = "",
    val totalHtmlLength: Int = 0,
    val queryMatchCount: Int = 0,
    val queryError: String = ""
)

internal const val INSPECTOR_ERROR_HOOK_JS: String = """
(function(){
    if (window.__dragonInspectorHooked) return;
    window.__dragonInspectorHooked = true;
    try {
        window.addEventListener('error', function(ev) {
            try {
                console.error((ev.message || 'Uncaught Error') + (ev.filename ? ' (' + ev.filename + ':' + ev.lineno + ')' : ''));
            } catch(e) {}
        });
        window.addEventListener('unhandledrejection', function(ev) {
            try {
                var r = ev && ev.reason ? (ev.reason.message || String(ev.reason)) : 'Unhandled Promise rejection';
                console.error('UnhandledRejection: ' + r);
            } catch(e) {}
        });
    } catch(e) {}
})();
"""

internal fun buildInspectorSnapshotJs(
    query: String = "",
    selectedSelector: String = "",
    includeHtmlSource: Boolean = false
): String {
    val safeQuery = JSONObject.quote(query.trim())
    val safeSel = JSONObject.quote(selectedSelector.trim())
    return """
    (function(){
        try {
            var q = $safeQuery;
            var selTarget = $safeSel;
            var buildSel = function(el) {
                if (!el || el.nodeType !== 1) return '';
                if (el.id) {
                    try {
                        var idEsc = (window.CSS && CSS.escape) ? CSS.escape(el.id) : el.id.replace(/[^a-zA-Z0-9_-]/g, '\\$&');
                        return '#' + idEsc;
                    } catch(e) { return el.tagName.toLowerCase(); }
                }
                var parts = [];
                var cur = el;
                var guard = 0;
                while (cur && cur.nodeType === 1 && guard < 6) {
                    var part = cur.tagName.toLowerCase();
                    if (cur.id) {
                        parts.unshift('#' + cur.id);
                        break;
                    }
                    if (cur.parentElement) {
                        var sibs = Array.prototype.filter.call(cur.parentElement.children, function(c) {
                            return c.tagName === cur.tagName;
                        });
                        if (sibs.length > 1) {
                            part += ':nth-of-type(' + (sibs.indexOf(cur) + 1) + ')';
                        }
                    }
                    parts.unshift(part);
                    if (part === 'body' || part === 'html') break;
                    cur = cur.parentElement;
                    guard++;
                }
                return parts.join(' > ');
            };

            var perfTiming = (window.performance && performance.timing) ? performance.timing : null;
            var navStart = perfTiming ? (perfTiming.navigationStart || 0) : 0;
            var dnsMs = (perfTiming && navStart > 0 && perfTiming.domainLookupEnd >= perfTiming.domainLookupStart)
                ? Math.max(0, perfTiming.domainLookupEnd - perfTiming.domainLookupStart) : 0;
            var tcpMs = (perfTiming && navStart > 0 && perfTiming.connectEnd >= perfTiming.connectStart)
                ? Math.max(0, perfTiming.connectEnd - perfTiming.connectStart) : 0;
            var ttfbMs = (perfTiming && navStart > 0 && perfTiming.responseStart >= navStart)
                ? Math.max(0, perfTiming.responseStart - navStart) : 0;
            var domInteractiveMs = (perfTiming && navStart > 0 && perfTiming.domInteractive >= navStart)
                ? Math.max(0, perfTiming.domInteractive - navStart) : 0;
            var totalLoadMs = (perfTiming && navStart > 0 && perfTiming.loadEventEnd >= navStart)
                ? Math.max(0, perfTiming.loadEventEnd - navStart) : 0;
            if (totalLoadMs === 0 && window.performance && performance.getEntriesByType) {
                var navEntries = performance.getEntriesByType('navigation');
                if (navEntries && navEntries.length > 0) {
                    var ne = navEntries[0];
                    dnsMs = Math.round(Math.max(0, (ne.domainLookupEnd || 0) - (ne.domainLookupStart || 0)));
                    tcpMs = Math.round(Math.max(0, (ne.connectEnd || 0) - (ne.connectStart || 0)));
                    ttfbMs = Math.round(Math.max(0, (ne.responseStart || 0)));
                    domInteractiveMs = Math.round(Math.max(0, (ne.domInteractive || 0)));
                    totalLoadMs = Math.round(Math.max(0, (ne.loadEventEnd || ne.duration || 0)));
                }
            }

            var metaList = [];
            var metas = document.querySelectorAll('meta, link[rel="canonical"], link[rel="icon"]');
            for (var mi = 0; mi < metas.length && metaList.length < 40; mi++) {
                var m = metas[mi];
                if (m.tagName === 'META') {
                    var k = m.getAttribute('name') || m.getAttribute('property') || m.getAttribute('http-equiv') || (m.getAttribute('charset') ? 'charset' : '');
                    var v = m.getAttribute('content') || m.getAttribute('charset') || '';
                    if (k || v) metaList.push({ k: k || 'meta', v: v });
                } else {
                    metaList.push({ k: 'link[' + (m.getAttribute('rel') || '') + ']', v: m.getAttribute('href') || '' });
                }
            }

            var lsItems = [];
            try {
                for (var li = 0; li < localStorage.length && li < 60; li++) {
                    var lk = localStorage.key(li);
                    lsItems.push({ k: String(lk), v: String(localStorage.getItem(lk) || '').slice(0, 500) });
                }
            } catch(e) {}

            var ssItems = [];
            try {
                for (var si = 0; si < sessionStorage.length && si < 60; si++) {
                    var sk = sessionStorage.key(si);
                    ssItems.push({ k: String(sk), v: String(sessionStorage.getItem(sk) || '').slice(0, 500) });
                }
            } catch(e) {}

            var matchedEls = [];
            var queryErr = '';
            var totalMatched = 0;
            if (q && q.length > 0) {
                var isCssLike = /^[#.[\w:-]+/.test(q) && (q.indexOf('#') >= 0 || q.indexOf('.') >= 0 || q.indexOf('[') >= 0 || q.indexOf('>') >= 0 || /^[a-zA-Z0-9]+$/.test(q));
                var foundSet = [];
                if (isCssLike) {
                    try {
                        var qres = document.querySelectorAll(q);
                        totalMatched = qres.length;
                        for (var qi = 0; qi < qres.length && foundSet.length < 90; qi++) {
                            foundSet.push({ el: qres[qi], depth: 0 });
                        }
                    } catch(err) {
                        queryErr = String(err.message || err);
                    }
                }
                if (foundSet.length === 0 && !queryErr) {
                    var qLow = q.toLowerCase();
                    var all = document.querySelectorAll('*');
                    for (var ai = 0; ai < all.length; ai++) {
                        var candidate = all[ai];
                        var tName = candidate.tagName.toLowerCase();
                        var idVal = (candidate.id || '').toLowerCase();
                        var clsVal = (typeof candidate.className === 'string' ? candidate.className : '').toLowerCase();
                        var txtVal = (candidate.innerText || '').trim().slice(0, 120).toLowerCase();
                        if (tName.indexOf(qLow) >= 0 || idVal.indexOf(qLow) >= 0 || clsVal.indexOf(qLow) >= 0 || txtVal.indexOf(qLow) >= 0) {
                            totalMatched++;
                            if (foundSet.length < 90) foundSet.push({ el: candidate, depth: 0 });
                        }
                    }
                }
                matchedEls = foundSet;
            } else {
                var walk = function(node, depth) {
                    if (!node || node.nodeType !== 1 || matchedEls.length >= 90 || depth > 5) return;
                    if (node.id && String(node.id).indexOf('__dragon_inspector') === 0) return;
                    matchedEls.push({ el: node, depth: depth });
                    var ch = node.children;
                    if (ch) {
                        for (var i = 0; i < ch.length && matchedEls.length < 90; i++) {
                            walk(ch[i], depth + 1);
                        }
                    }
                };
                walk(document.documentElement, 0);
                totalMatched = document.querySelectorAll('*').length;
            }

            var domNodes = matchedEls.map(function(entry) {
                var el = entry.el;
                var rect = el.getBoundingClientRect ? el.getBoundingClientRect() : { width: 0, height: 0, left: 0, top: 0 };
                var cls = typeof el.className === 'string' ? el.className.trim() : '';
                var attrParts = [];
                if (el.attributes) {
                    for (var k = 0; k < el.attributes.length && attrParts.length < 4; k++) {
                        var at = el.attributes[k];
                        if (at.name !== 'id' && at.name !== 'class' && at.name !== 'style') {
                            attrParts.push(at.name + '="' + String(at.value || '').slice(0, 28) + '"');
                        }
                    }
                }
                var txt = '';
                if (el.childNodes && el.childNodes.length <= 3) {
                    txt = (el.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 60);
                }
                return {
                    tag: el.tagName.toLowerCase(),
                    idAttr: el.id || '',
                    classAttr: cls.slice(0, 80),
                    selector: buildSel(el),
                    textSnippet: txt,
                    attrsSummary: attrParts.join(' '),
                    depth: entry.depth,
                    childCount: el.children ? el.children.length : 0,
                    rectLabel: Math.round(rect.width) + 'x' + Math.round(rect.height)
                };
            });

            var selectedObj = null;
            if (selTarget) {
                try {
                    var targetEl = document.querySelector(selTarget);
                    if (targetEl) {
                        var sRect = targetEl.getBoundingClientRect ? targetEl.getBoundingClientRect() : { width: 0, height: 0, left: 0, top: 0 };
                        var sCls = typeof targetEl.className === 'string' ? targetEl.className.trim() : '';
                        var sAttrs = [];
                        if (targetEl.attributes) {
                            for (var ai2 = 0; ai2 < targetEl.attributes.length && ai2 < 24; ai2++) {
                                var a2 = targetEl.attributes[ai2];
                                sAttrs.push({ k: a2.name, v: String(a2.value || '').slice(0, 300) });
                            }
                        }
                        var cs = window.getComputedStyle ? window.getComputedStyle(targetEl) : null;
                        var styleKeys = ['display', 'position', 'width', 'height', 'color', 'backgroundColor', 'fontSize', 'fontFamily', 'margin', 'padding', 'zIndex', 'opacity', 'visibility'];
                        var sStyles = [];
                        if (cs) {
                            for (var si2 = 0; si2 < styleKeys.length; si2++) {
                                var sk2 = styleKeys[si2];
                                sStyles.push({ k: sk2, v: String(cs[sk2] || '') });
                            }
                        }
                        selectedObj = {
                            tag: targetEl.tagName.toLowerCase(),
                            idAttr: targetEl.id || '',
                            classAttr: sCls,
                            selector: buildSel(targetEl),
                            rectLabel: Math.round(sRect.width) + '×' + Math.round(sRect.height) + ' @ (' + Math.round(sRect.left) + ',' + Math.round(sRect.top) + ')',
                            textContent: (targetEl.innerText || targetEl.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 400),
                            outerHtmlPreview: String(targetEl.outerHTML || '').slice(0, 1800),
                            attributes: sAttrs,
                            computedStyles: sStyles
                        };
                    }
                } catch(e) {}
            }

            var resTimings = [];
            try {
                if (window.performance && performance.getEntriesByType) {
                    var rEntries = performance.getEntriesByType('resource') || [];
                    for (var ri = 0; ri < rEntries.length && resTimings.length < 80; ri++) {
                        var re = rEntries[ri];
                        resTimings.push({
                            name: String(re.name || ''),
                            initiatorType: String(re.initiatorType || 'other'),
                            durationMs: Math.round(re.duration || 0),
                            transferBytes: Math.round(re.transferSize || re.encodedBodySize || 0)
                        });
                    }
                }
            } catch(e) {}

            var rawHtml = ${if (includeHtmlSource) "String(document.documentElement ? document.documentElement.outerHTML : '')" else "''"};
            var htmlLen = document.documentElement && document.documentElement.outerHTML ? document.documentElement.outerHTML.length : 0;

            return JSON.stringify({
                url: String(location.href || ''),
                title: String(document.title || ''),
                domain: String(location.hostname || ''),
                protocol: String(location.protocol || '').replace(':', ''),
                readyState: String(document.readyState || ''),
                compatMode: String(document.compatMode || ''),
                charset: String(document.characterSet || 'UTF-8'),
                viewportLabel: window.innerWidth + '×' + window.innerHeight + ' @' + (window.devicePixelRatio || 1).toFixed(2) + 'x',
                userAgent: String(navigator.userAgent || ''),
                dnsMs: dnsMs,
                tcpMs: tcpMs,
                ttfbMs: ttfbMs,
                domInteractiveMs: domInteractiveMs,
                totalLoadMs: totalLoadMs,
                totalElements: document.querySelectorAll('*').length,
                scriptCount: document.scripts ? document.scripts.length : document.querySelectorAll('script').length,
                stylesheetCount: document.querySelectorAll('link[rel="stylesheet"], style').length,
                imageCount: document.images ? document.images.length : document.querySelectorAll('img').length,
                linkCount: document.links ? document.links.length : document.querySelectorAll('a[href]').length,
                formCount: document.forms ? document.forms.length : document.querySelectorAll('form').length,
                iframeCount: document.querySelectorAll('iframe').length,
                metaTags: metaList,
                localStorageItems: lsItems,
                sessionStorageItems: ssItems,
                documentCookie: String(document.cookie || ''),
                domNodes: domNodes,
                selectedElement: selectedObj,
                resourceTimings: resTimings,
                htmlSourcePreview: rawHtml.slice(0, 16000),
                totalHtmlLength: htmlLen,
                queryMatchCount: totalMatched,
                queryError: queryErr
            });
        } catch(e) {
            return JSON.stringify({ queryError: String(e && e.message ? e.message : e) });
        }
    })();
    """.trimIndent()
}

internal fun buildElementPickerJs(enabled: Boolean): String {
    if (!enabled) {
        return """
        (function(){
            try {
                window.__dragonPickerActive = false;
                var box = document.getElementById('__dragon_inspector_box');
                if (box) box.remove();
                var lbl = document.getElementById('__dragon_inspector_lbl');
                if (lbl) lbl.remove();
                if (window.__dragonPickerHandler) {
                    document.removeEventListener('click', window.__dragonPickerHandler, true);
                    document.removeEventListener('touchstart', window.__dragonPickerMoveHandler, true);
                    window.__dragonPickerHandler = null;
                }
            } catch(e) {}
        })();
        """.trimIndent()
    }
    return """
    (function(){
        try {
            if (window.__dragonPickerHandler) {
                document.removeEventListener('click', window.__dragonPickerHandler, true);
                document.removeEventListener('touchstart', window.__dragonPickerMoveHandler, true);
            }
            window.__dragonPickerActive = true;
            var box = document.getElementById('__dragon_inspector_box');
            if (!box) {
                box = document.createElement('div');
                box.id = '__dragon_inspector_box';
                box.style.cssText = 'position:fixed;pointer-events:none;z-index:2147483646;border:2px solid #00E5FF;background:rgba(0,229,255,0.16);border-radius:4px;display:none;transition:all 80ms ease;';
                (document.documentElement || document.body).appendChild(box);
            }
            var lbl = document.getElementById('__dragon_inspector_lbl');
            if (!lbl) {
                lbl = document.createElement('div');
                lbl.id = '__dragon_inspector_lbl';
                lbl.style.cssText = 'position:fixed;pointer-events:none;z-index:2147483647;background:#04131B;color:#00E5FF;border:1px solid #00E5FF;padding:2px 6px;font:bold 10px/1.3 monospace;border-radius:4px;display:none;max-width:85vw;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;';
                (document.documentElement || document.body).appendChild(lbl);
            }
            var buildSel = function(el) {
                if (!el || el.nodeType !== 1) return '';
                if (el.id) {
                    var idEsc = (window.CSS && CSS.escape) ? CSS.escape(el.id) : el.id;
                    return '#' + idEsc;
                }
                var parts = [];
                var cur = el;
                var guard = 0;
                while (cur && cur.nodeType === 1 && guard < 6) {
                    var part = cur.tagName.toLowerCase();
                    if (cur.id) { parts.unshift('#' + cur.id); break; }
                    if (cur.parentElement) {
                        var sibs = Array.prototype.filter.call(cur.parentElement.children, function(c) { return c.tagName === cur.tagName; });
                        if (sibs.length > 1) part += ':nth-of-type(' + (sibs.indexOf(cur) + 1) + ')';
                    }
                    parts.unshift(part);
                    if (part === 'body' || part === 'html') break;
                    cur = cur.parentElement;
                    guard++;
                }
                return parts.join(' > ');
            };
            var showOverlay = function(el) {
                if (!el || !el.getBoundingClientRect) return;
                var r = el.getBoundingClientRect();
                box.style.display = 'block';
                box.style.left = r.left + 'px';
                box.style.top = r.top + 'px';
                box.style.width = Math.max(4, r.width) + 'px';
                box.style.height = Math.max(4, r.height) + 'px';
                var tagLabel = el.tagName.toLowerCase() + (el.id ? '#' + el.id : '') + (typeof el.className === 'string' && el.className.trim() ? '.' + el.className.trim().split(/\s+/)[0] : '');
                lbl.textContent = tagLabel + ' (' + Math.round(r.width) + '×' + Math.round(r.height) + ')';
                lbl.style.display = 'block';
                lbl.style.left = Math.max(4, Math.min(window.innerWidth - 180, r.left)) + 'px';
                lbl.style.top = (r.top > 24 ? (r.top - 22) : Math.min(window.innerHeight - 24, r.bottom + 4)) + 'px';
            };
            window.__dragonPickerMoveHandler = function(ev) {
                if (!window.__dragonPickerActive) return;
                var t = ev.target;
                if (t && t.id && String(t.id).indexOf('__dragon_inspector') === 0) return;
                showOverlay(t);
            };
            window.__dragonPickerHandler = function(ev) {
                if (!window.__dragonPickerActive) return;
                var t = ev.target;
                if (t && t.id && String(t.id).indexOf('__dragon_inspector') === 0) return;
                ev.preventDefault();
                ev.stopPropagation();
                ev.stopImmediatePropagation();
                showOverlay(t);
                var sel = buildSel(t);
                window.__dragonPickerActive = false;
                document.removeEventListener('click', window.__dragonPickerHandler, true);
                document.removeEventListener('touchstart', window.__dragonPickerMoveHandler, true);
                if (window.DragonInspectorBridge && window.DragonInspectorBridge.onInspectorElementSelected) {
                    window.DragonInspectorBridge.onInspectorElementSelected(sel);
                }
            };
            document.addEventListener('touchstart', window.__dragonPickerMoveHandler, true);
            document.addEventListener('click', window.__dragonPickerHandler, true);
        } catch(e) {}
    })();
    """.trimIndent()
}

internal fun buildHighlightElementJs(selector: String): String {
    val safeSel = JSONObject.quote(selector)
    return """
    (function(){
        try {
            var el = document.querySelector($safeSel);
            if (!el) return;
            if (el.scrollIntoView) el.scrollIntoView({ behavior: 'smooth', block: 'center' });
            var box = document.getElementById('__dragon_inspector_box');
            if (!box) {
                box = document.createElement('div');
                box.id = '__dragon_inspector_box';
                box.style.cssText = 'position:fixed;pointer-events:none;z-index:2147483646;border:2px solid #00E5FF;background:rgba(0,229,255,0.20);border-radius:4px;';
                (document.documentElement || document.body).appendChild(box);
            }
            var updatePos = function() {
                var r = el.getBoundingClientRect();
                box.style.display = 'block';
                box.style.left = r.left + 'px';
                box.style.top = r.top + 'px';
                box.style.width = Math.max(4, r.width) + 'px';
                box.style.height = Math.max(4, r.height) + 'px';
            };
            updatePos();
            setTimeout(updatePos, 350);
            setTimeout(function() { if (box && !window.__dragonPickerActive) box.style.display = 'none'; }, 2800);
        } catch(e) {}
    })();
    """.trimIndent()
}

internal fun buildElementMutationJs(selector: String, action: String): String {
    val safeSel = JSONObject.quote(selector)
    val safeAction = JSONObject.quote(action)
    return """
    (function(){
        try {
            var act = $safeAction;
            if (act === 'outline_all') {
                var st = document.getElementById('__dragon_outline_style');
                if (st) { st.remove(); return 'Outline removed'; }
                st = document.createElement('style');
                st.id = '__dragon_outline_style';
                st.textContent = '* { outline: 1px solid rgba(0, 229, 255, 0.45) !important; }';
                (document.head || document.documentElement).appendChild(st);
                return 'Outlines enabled';
            }
            var el = document.querySelector($safeSel);
            if (!el) return 'Element not found';
            if (act === 'hide') {
                el.style.setProperty('display', 'none', 'important');
                return 'Element hidden';
            } else if (act === 'remove') {
                el.remove();
                return 'Element removed';
            } else if (act === 'scroll') {
                el.scrollIntoView({ behavior: 'smooth', block: 'center' });
                return 'Scrolled into view';
            }
            return 'OK';
        } catch(e) { return 'Error: ' + e.message; }
    })();
    """.trimIndent()
}

internal fun parseInspectorSnapshotJson(rawResult: String?): InspectorSnapshot {
    if (rawResult.isNullOrBlank() || rawResult == "null") return InspectorSnapshot()
    val decoded = runCatching {
        if (rawResult.startsWith("\"")) org.json.JSONTokener(rawResult).nextValue()?.toString() else rawResult
    }.getOrDefault(rawResult).orEmpty()
    val root = runCatching { JSONObject(decoded) }.getOrNull() ?: return InspectorSnapshot()

    fun parseKvArray(arr: JSONArray?): List<Pair<String, String>> {
        if (arr == null) return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val k = o.optString("k", "")
            val v = o.optString("v", "")
            if (k.isNotEmpty()) out.add(k to v)
        }
        return out
    }

    val domArr = root.optJSONArray("domNodes")
    val domList = mutableListOf<InspectorDomNode>()
    if (domArr != null) {
        for (i in 0 until domArr.length()) {
            val o = domArr.optJSONObject(i) ?: continue
            domList.add(
                InspectorDomNode(
                    tag = o.optString("tag", "div"),
                    idAttr = o.optString("idAttr", ""),
                    classAttr = o.optString("classAttr", ""),
                    selector = o.optString("selector", ""),
                    textSnippet = o.optString("textSnippet", ""),
                    attrsSummary = o.optString("attrsSummary", ""),
                    depth = o.optInt("depth", 0),
                    childCount = o.optInt("childCount", 0),
                    rectLabel = o.optString("rectLabel", "")
                )
            )
        }
    }

    val selObj = root.optJSONObject("selectedElement")
    val selected = selObj?.let { s ->
        InspectorSelectedElement(
            tag = s.optString("tag", ""),
            idAttr = s.optString("idAttr", ""),
            classAttr = s.optString("classAttr", ""),
            selector = s.optString("selector", ""),
            rectLabel = s.optString("rectLabel", ""),
            textContent = s.optString("textContent", ""),
            outerHtmlPreview = s.optString("outerHtmlPreview", ""),
            attributes = parseKvArray(s.optJSONArray("attributes")),
            computedStyles = parseKvArray(s.optJSONArray("computedStyles"))
        )
    }

    val resArr = root.optJSONArray("resourceTimings")
    val resList = mutableListOf<InspectorResourceTiming>()
    if (resArr != null) {
        for (i in 0 until resArr.length()) {
            val o = resArr.optJSONObject(i) ?: continue
            resList.add(
                InspectorResourceTiming(
                    name = o.optString("name", ""),
                    initiatorType = o.optString("initiatorType", "other"),
                    durationMs = o.optLong("durationMs", 0L),
                    transferBytes = o.optLong("transferBytes", 0L)
                )
            )
        }
    }

    return InspectorSnapshot(
        url = root.optString("url", ""),
        title = root.optString("title", ""),
        domain = root.optString("domain", ""),
        protocol = root.optString("protocol", ""),
        readyState = root.optString("readyState", ""),
        compatMode = root.optString("compatMode", ""),
        charset = root.optString("charset", "UTF-8"),
        viewportLabel = root.optString("viewportLabel", ""),
        userAgent = root.optString("userAgent", ""),
        dnsMs = root.optLong("dnsMs", 0L),
        tcpMs = root.optLong("tcpMs", 0L),
        ttfbMs = root.optLong("ttfbMs", 0L),
        domInteractiveMs = root.optLong("domInteractiveMs", 0L),
        totalLoadMs = root.optLong("totalLoadMs", 0L),
        totalElements = root.optInt("totalElements", 0),
        scriptCount = root.optInt("scriptCount", 0),
        stylesheetCount = root.optInt("stylesheetCount", 0),
        imageCount = root.optInt("imageCount", 0),
        linkCount = root.optInt("linkCount", 0),
        formCount = root.optInt("formCount", 0),
        iframeCount = root.optInt("iframeCount", 0),
        metaTags = parseKvArray(root.optJSONArray("metaTags")),
        localStorageItems = parseKvArray(root.optJSONArray("localStorageItems")),
        sessionStorageItems = parseKvArray(root.optJSONArray("sessionStorageItems")),
        documentCookie = root.optString("documentCookie", ""),
        domNodes = domList,
        selectedElement = selected,
        resourceTimings = resList,
        htmlSourcePreview = root.optString("htmlSourcePreview", ""),
        totalHtmlLength = root.optInt("totalHtmlLength", 0),
        queryMatchCount = root.optInt("queryMatchCount", 0),
        queryError = root.optString("queryError", "")
    )
}

@Composable
fun BrowserInspectorDialog(
    webView: WebView?,
    pageUrl: String,
    pageTitle: String,
    dnsMode: DnsProviderMode,
    warpTelemetry: WarpDnsTelemetry,
    extensions: List<BrowserExtension>,
    consoleLogs: List<WebConsoleLogEntry>,
    networkLogs: List<WebNetworkLogEntry>,
    isInspectorEnabled: Boolean = false,
    onToggleInspectorEnabled: (Boolean) -> Unit = {},
    initialSelectedSelector: String = "",
    initialTab: Int = 0,
    onStartVisualElementPicker: () -> Unit,
    onClearConsoleLogs: () -> Unit,
    onRecordEvalConsoleMessage: (level: String, message: String) -> Unit,
    onClearNetworkLogs: () -> Unit,
    onClearSiteCookies: () -> Unit,
    onOpenUrlInNewTab: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var selectedTab by remember(initialTab) { mutableStateOf(initialTab.coerceIn(0, 4)) }
    var domQuery by remember { mutableStateOf("") }
    var activeSelector by remember(initialSelectedSelector) { mutableStateOf(initialSelectedSelector) }
    var includeHtmlSource by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(isInspectorEnabled) }
    var snapshot by remember { mutableStateOf(InspectorSnapshot(url = pageUrl, title = pageTitle)) }
    var refreshNonce by remember { mutableStateOf(0) }

    var consoleLevelFilter by remember { mutableStateOf("ALL") }
    var jsReplInput by remember { mutableStateOf("") }
    var networkCategoryFilter by remember { mutableStateOf("ALL") }
    var networkSearchQuery by remember { mutableStateOf("") }

    fun refreshSnapshot(fetchSource: Boolean = includeHtmlSource) {
        if (!isInspectorEnabled) {
            isRefreshing = false
            return
        }
        val wv = webView
        if (wv == null) {
            isRefreshing = false
            return
        }
        isRefreshing = true
        val script = buildInspectorSnapshotJs(
            query = domQuery,
            selectedSelector = activeSelector,
            includeHtmlSource = fetchSource
        )
        wv.evaluateJavascript(script) { raw ->
            snapshot = parseInspectorSnapshotJson(raw)
            isRefreshing = false
        }
    }

    LaunchedEffect(webView, pageUrl, refreshNonce, domQuery, activeSelector, includeHtmlSource, isInspectorEnabled) {
        refreshSnapshot(includeHtmlSource)
    }

    val matchedExtensions = remember(extensions, pageUrl) {
        extensions.filter { ext ->
            ext.enabled && ext.contentScripts.any { cs ->
                cs.matches.any { pat ->
                    pat == "<all_urls>" || pat == "*://*/*" ||
                        run {
                            val cleanHost = snapshot.domain.ifBlank { runCatching { android.net.Uri.parse(pageUrl).host.orEmpty() }.getOrDefault("") }
                            cleanHost.isNotBlank() && pat.contains(cleanHost, ignoreCase = true)
                        }
                }
            }
        }
    }

    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .testTag("browser_inspector_floating_container")
    ) {
        val maxW = maxWidth
        val maxH = maxHeight
        val defaultW = (maxW * 0.90f).coerceIn(250.dp, 540.dp)
        val defaultH = (maxH * 0.56f).coerceIn(230.dp, 560.dp)

        var winWidth by remember { mutableStateOf(defaultW) }
        var winHeight by remember { mutableStateOf(defaultH) }
        var offsetX by remember { mutableStateOf(((maxW - defaultW) / 2f).coerceAtLeast(4.dp)) }
        var offsetY by remember { mutableStateOf((maxH - defaultH - 8.dp).coerceAtLeast(6.dp)) }
        var isMinimized by remember { mutableStateOf(false) }
        var isMaximized by remember { mutableStateOf(false) }

        val curMaxW by rememberUpdatedState(maxW)
        val curMaxH by rememberUpdatedState(maxH)
        val curIsMax by rememberUpdatedState(isMaximized)

        val clampedW = if (isMaximized) (maxW - 12.dp).coerceAtLeast(240.dp) else winWidth.coerceIn(240.dp, (maxW - 8.dp).coerceAtLeast(240.dp))
        val clampedH = if (isMaximized) (maxH - 12.dp).coerceAtLeast(210.dp) else winHeight.coerceIn(190.dp, (maxH - 8.dp).coerceAtLeast(190.dp))
        val curClampedW by rememberUpdatedState(clampedW)
        val curClampedH by rememberUpdatedState(clampedH)

        val effX = if (isMaximized) 6.dp else offsetX.coerceIn(4.dp, (maxW - clampedW - 4.dp).coerceAtLeast(4.dp))
        val effY = if (isMaximized) 6.dp else offsetY.coerceIn(4.dp, (maxH - clampedH - 4.dp).coerceAtLeast(4.dp))
        val curEffX by rememberUpdatedState(effX)
        val curEffY by rememberUpdatedState(effY)

        if (isMinimized) {
            val pillX = offsetX.coerceIn(6.dp, (maxW - 180.dp).coerceAtLeast(6.dp))
            val pillY = offsetY.coerceIn(6.dp, (maxH - 48.dp).coerceAtLeast(6.dp))
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = Color(0xFF061620).copy(alpha = 0.95f),
                border = BorderStroke(1.2.dp, NeonCyan),
                shadowElevation = 10.dp,
                modifier = Modifier
                    .offset {
                        IntOffset(
                            with(density) { pillX.roundToPx() },
                            with(density) { pillY.roundToPx() }
                        )
                    }
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val dx = with(density) { dragAmount.x.toDp() }
                            val dy = with(density) { dragAmount.y.toDp() }
                            offsetX = (offsetX + dx).coerceIn(4.dp, (curMaxW - 180.dp).coerceAtLeast(4.dp))
                            offsetY = (offsetY + dy).coerceIn(4.dp, (curMaxH - 48.dp).coerceAtLeast(4.dp))
                        }
                    }
                    .clip(RoundedCornerShape(22.dp))
                    .clickable { isMinimized = false }
                    .testTag("inspector_minimized_pill")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Code,
                        contentDescription = "Restore Page Inspector",
                        tint = NeonCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    val errCount = consoleLogs.count { it.level == "ERROR" }
                    Text(
                        text = if (errCount > 0) "Inspector ($errCount ERR)" else "Inspector",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )
                    Icon(
                        imageVector = Icons.Default.OpenInFull,
                        contentDescription = "Expand",
                        tint = NeonCyan,
                        modifier = Modifier.size(14.dp)
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Inspector",
                            tint = Color.White.copy(alpha = 0.75f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        } else {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
                border = BorderStroke(1.2.dp, NeonCyan.copy(alpha = 0.55f)),
                tonalElevation = 10.dp,
                shadowElevation = 14.dp,
                modifier = Modifier
                    .offset {
                        IntOffset(
                            with(density) { effX.roundToPx() },
                            with(density) { effY.roundToPx() }
                        )
                    }
                    .size(width = clampedW, height = clampedH)
                    .testTag("browser_inspector_dialog")
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Draggable Title Bar Header
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f))
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        if (curIsMax) return@detectDragGestures
                                        val dx = with(density) { dragAmount.x.toDp() }
                                        val dy = with(density) { dragAmount.y.toDp() }
                                        offsetX = (offsetX + dx).coerceIn(4.dp, (curMaxW - curClampedW - 4.dp).coerceAtLeast(4.dp))
                                        offsetY = (offsetY + dy).coerceIn(4.dp, (curMaxH - curClampedH - 4.dp).coerceAtLeast(4.dp))
                                    }
                                }
                                .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 5.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DragIndicator,
                                    contentDescription = "Drag Inspector Window",
                                    tint = NeonCyan.copy(alpha = 0.8f),
                                    modifier = Modifier.size(16.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Page Inspector",
                                        fontWeight = FontWeight.Black,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = snapshot.domain.ifBlank {
                                            runCatching { android.net.Uri.parse(pageUrl).host }.getOrNull() ?: pageUrl
                                        },
                                        fontSize = 9.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(
                                    checked = isInspectorEnabled,
                                    onCheckedChange = onToggleInspectorEnabled,
                                    modifier = Modifier
                                        .scale(0.66f)
                                        .height(26.dp)
                                        .testTag("switch_page_inspector_window")
                                )
                                IconButton(
                                    onClick = onStartVisualElementPicker,
                                    enabled = isInspectorEnabled,
                                    modifier = Modifier
                                        .size(34.dp)
                                        .testTag("btn_inspector_header_picker")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AdsClick,
                                        contentDescription = "Pick Element on Page",
                                        tint = if (isInspectorEnabled) NeonCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { refreshNonce++ },
                                    enabled = isInspectorEnabled,
                                    modifier = Modifier
                                        .size(34.dp)
                                        .testTag("btn_inspector_refresh")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Refresh Inspection Data",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { isMinimized = true },
                                    modifier = Modifier
                                        .size(34.dp)
                                        .testTag("btn_inspector_minimize")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Remove,
                                        contentDescription = "Minimize to Pill",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { isMaximized = !isMaximized },
                                    modifier = Modifier
                                        .size(34.dp)
                                        .testTag("btn_inspector_maximize")
                                ) {
                                    Icon(
                                        imageVector = if (isMaximized) Icons.Default.CloseFullscreen else Icons.Default.OpenInFull,
                                        contentDescription = if (isMaximized) "Restore Window" else "Maximize Window",
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                                IconButton(
                                    onClick = onDismiss,
                                    modifier = Modifier
                                        .size(34.dp)
                                        .testTag("btn_close_inspector")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Close Inspector",
                                        modifier = Modifier.size(17.dp)
                                    )
                                }
                            }
                        }

                // Navigation Tabs
                val tabSpecs = listOf(
                    0 to "Overview",
                    1 to "Elements",
                    2 to "Console (${consoleLogs.size})",
                    3 to "Network (${networkLogs.size})",
                    4 to "Storage"
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    tabSpecs.forEach { (idx, label) ->
                        FilterChip(
                            selected = selectedTab == idx,
                            onClick = { selectedTab = idx },
                            label = {
                                Text(
                                    text = label,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                            },
                            modifier = Modifier
                                .height(32.dp)
                                .testTag("tab_inspector_$idx")
                        )
                    }
                }

                if (isRefreshing) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = NeonCyan
                    )
                } else {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }

                // Tab Content Area
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(10.dp)
                ) {
                    if (!isInspectorEnabled) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(8.dp)
                                .testTag("inspector_paused_panel"),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = WarningOrange.copy(alpha = 0.14f),
                                border = BorderStroke(1.dp, WarningOrange.copy(alpha = 0.45f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PauseCircleOutline,
                                        contentDescription = null,
                                        tint = WarningOrange,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Page Inspection is OFF",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = WarningOrange
                                        )
                                        Text(
                                            text = "DOM snapshotting, console error capture, network logging, and element picking are paused.",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            Button(
                                onClick = { onToggleInspectorEnabled(true) },
                                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.testTag("btn_turn_on_page_inspector")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = Color(0xFF04131B),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Turn Page Inspection ON",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFF04131B)
                                )
                            }
                        }
                    } else when (selectedTab) {
                        // ==================== TAB 0: OVERVIEW ====================
                        0 -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .testTag("inspector_overview_panel"),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Summary Hero
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.25f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = snapshot.title.ifBlank { pageTitle }.ifBlank { "Untitled Page" },
                                                fontSize = 13.5.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            val isHttps = snapshot.protocol.equals("https", true) || pageUrl.startsWith("https://", true)
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = (if (isHttps) SuccessGreen else WarningOrange).copy(alpha = 0.16f)
                                            ) {
                                                Text(
                                                    text = if (isHttps) "HTTPS" else snapshot.protocol.ifBlank { "HTTP" }.uppercase(Locale.US),
                                                    fontSize = 9.5.sp,
                                                    fontWeight = FontWeight.Black,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = if (isHttps) SuccessGreen else WarningOrange,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                        Text(
                                            text = snapshot.url.ifBlank { pageUrl },
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = NeonCyan,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            modifier = Modifier.horizontalScroll(rememberScrollState())
                                        ) {
                                            InspectorMiniBadge(
                                                text = "State: ${snapshot.readyState.ifBlank { "complete" }}",
                                                color = SuccessGreen
                                            )
                                            if (snapshot.viewportLabel.isNotBlank()) {
                                                InspectorMiniBadge(
                                                    text = snapshot.viewportLabel,
                                                    color = NeonCyan
                                                )
                                            }
                                            InspectorMiniBadge(
                                                text = "DNS: ${dnsMode.title.substringBefore('(').trim()}",
                                                color = if (dnsMode == DnsProviderMode.SYSTEM_DEFAULT) {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                } else {
                                                    WarningOrange
                                                }
                                            )
                                        }
                                    }
                                }

                                // Performance Timing Metrics
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "PAGE LOAD & TIMING METRICS",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Black,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            InspectorMetricTile("DNS Lookup", "${snapshot.dnsMs}ms", NeonCyan, Modifier.weight(1f))
                                            InspectorMetricTile("TCP Connect", "${snapshot.tcpMs}ms", NeonCyan, Modifier.weight(1f))
                                            InspectorMetricTile("TTFB", "${snapshot.ttfbMs}ms", WarningOrange, Modifier.weight(1f))
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            InspectorMetricTile("DOM Ready", "${snapshot.domInteractiveMs}ms", SuccessGreen, Modifier.weight(1f))
                                            InspectorMetricTile("Total Load", "${snapshot.totalLoadMs}ms", SuccessGreen, Modifier.weight(1f))
                                            InspectorMetricTile(
                                                "DNS Hosts",
                                                "${warpTelemetry.resolvedHostCount}",
                                                MaterialTheme.colorScheme.primary,
                                                Modifier.weight(1f)
                                            )
                                        }
                                    }
                                }

                                // DOM & Asset Inventory Grid (tappable to jump into Elements filter)
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "DOM & ASSET BREAKDOWN (TAP TO FILTER ELEMENTS)",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Black,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            InspectorClickableCountTile("Elements", snapshot.totalElements, NeonCyan, Modifier.weight(1f)) {
                                                domQuery = ""
                                                selectedTab = 1
                                            }
                                            InspectorClickableCountTile("Scripts", snapshot.scriptCount, WarningOrange, Modifier.weight(1f)) {
                                                domQuery = "script"
                                                selectedTab = 1
                                            }
                                            InspectorClickableCountTile("Styles", snapshot.stylesheetCount, Color(0xFFA855F7), Modifier.weight(1f)) {
                                                domQuery = "style, link[rel=\"stylesheet\"]"
                                                selectedTab = 1
                                            }
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            InspectorClickableCountTile("Images", snapshot.imageCount, SuccessGreen, Modifier.weight(1f)) {
                                                domQuery = "img"
                                                selectedTab = 1
                                            }
                                            InspectorClickableCountTile("Links", snapshot.linkCount, NeonCyan, Modifier.weight(1f)) {
                                                domQuery = "a[href]"
                                                selectedTab = 1
                                            }
                                            InspectorClickableCountTile("Forms/Iframes", snapshot.formCount + snapshot.iframeCount, DangerRed, Modifier.weight(1f)) {
                                                domQuery = "form, iframe"
                                                selectedTab = 1
                                            }
                                        }
                                    }
                                }

                                // Document & Environment Details
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = "ENVIRONMENT & EXTENSIONS",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Black,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        InspectorKeyValueLine("Compat / Charset", "${snapshot.compatMode.ifBlank { "CSS1Compat" }} • ${snapshot.charset}")
                                        InspectorKeyValueLine("DOM HTML Size", "${snapshot.totalHtmlLength / 1024} KB (${snapshot.totalHtmlLength} chars)")
                                        InspectorKeyValueLine("Active Matched Exts", "${matchedExtensions.size} (${matchedExtensions.joinToString(", ") { it.name }.ifBlank { "None on this origin" }})")
                                        InspectorKeyValueLine("User-Agent", snapshot.userAgent.ifBlank { webView?.settings?.userAgentString.orEmpty() })
                                    }
                                }
                            }
                        }

                        // ==================== TAB 1: ELEMENTS / DOM ====================
                        1 -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("inspector_elements_panel"),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Search / CSS Selector Query Bar + Visual Picker Trigger
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = domQuery,
                                        onValueChange = { domQuery = it },
                                        placeholder = {
                                            Text(
                                                text = "CSS selector (div.card, #id) or text",
                                                fontSize = 11.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        },
                                        singleLine = true,
                                        textStyle = LocalTextStyle.current.copy(
                                            fontSize = 11.5.sp,
                                            fontFamily = FontFamily.Monospace
                                        ),
                                        trailingIcon = {
                                            if (domQuery.isNotEmpty()) {
                                                IconButton(onClick = { domQuery = "" }, modifier = Modifier.size(28.dp)) {
                                                    Icon(Icons.Default.Clear, contentDescription = "Clear Query", modifier = Modifier.size(16.dp))
                                                }
                                            }
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("input_inspector_dom_query")
                                    )
                                    FilledTonalButton(
                                        onClick = onStartVisualElementPicker,
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                        modifier = Modifier
                                            .defaultMinSize(minHeight = 48.dp)
                                            .testTag("btn_inspector_pick_element")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.AdsClick,
                                            contentDescription = "Pick Element",
                                            tint = NeonCyan,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Pick", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                // Quick Selector Filter Chips + Source Toggle + Outline All
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    listOf(
                                        "" to "Tree",
                                        "h1, h2, h3" to "Headings",
                                        "a[href]" to "Links",
                                        "img, video" to "Media",
                                        "button, input, form" to "Inputs",
                                        "script, iframe" to "Scripts"
                                    ).forEach { (qVal, label) ->
                                        FilterChip(
                                            selected = domQuery == qVal && !includeHtmlSource,
                                            onClick = {
                                                includeHtmlSource = false
                                                domQuery = qVal
                                            },
                                            label = { Text(label, fontSize = 10.sp) },
                                            modifier = Modifier.height(28.dp)
                                        )
                                    }
                                    FilterChip(
                                        selected = includeHtmlSource,
                                        onClick = { includeHtmlSource = !includeHtmlSource },
                                        label = { Text("Page Source", fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                                        modifier = Modifier
                                            .height(28.dp)
                                            .testTag("btn_toggle_page_source")
                                    )
                                    AssistChip(
                                        onClick = {
                                            webView?.evaluateJavascript(buildElementMutationJs("", "outline_all")) { msg ->
                                                val clean = msg?.trim('"') ?: "Toggled outlines"
                                                Toast.makeText(context, clean, Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        label = { Text("Outline", fontSize = 10.sp) },
                                        modifier = Modifier.height(28.dp)
                                    )
                                }

                                if (snapshot.queryError.isNotBlank()) {
                                    Text(
                                        text = "Selector error: ${snapshot.queryError}",
                                        fontSize = 10.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = DangerRed
                                    )
                                }

                                // Page HTML Source View
                                if (includeHtmlSource) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        Column(modifier = Modifier.fillMaxSize().padding(10.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "DOCUMENT HTML (${snapshot.totalHtmlLength} chars)",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Black,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = NeonCyan
                                                )
                                                TextButton(
                                                    onClick = {
                                                        clipboard.setText(AnnotatedString(snapshot.htmlSourcePreview))
                                                        Toast.makeText(context, "HTML source copied", Toast.LENGTH_SHORT).show()
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                    modifier = Modifier.height(28.dp)
                                                ) {
                                                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(13.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text("Copy HTML", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                            Column(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .verticalScroll(rememberScrollState())
                                            ) {
                                                Text(
                                                    text = snapshot.htmlSourcePreview.ifBlank { "Loading document source..." },
                                                    fontSize = 10.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        // Selected Element Inspector Card
                                        snapshot.selectedElement?.let { selEl ->
                                            item(key = "selected_el_${selEl.selector}") {
                                                Surface(
                                                    shape = RoundedCornerShape(12.dp),
                                                    color = NeonCyan.copy(alpha = 0.08f),
                                                    border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.55f)),
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .testTag("inspector_selected_element_card")
                                                ) {
                                                    Column(
                                                        modifier = Modifier.padding(10.dp),
                                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            horizontalArrangement = Arrangement.SpaceBetween,
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Text(
                                                                text = "SELECTED: <${selEl.tag}> • ${selEl.rectLabel}",
                                                                fontSize = 10.5.sp,
                                                                fontWeight = FontWeight.Black,
                                                                fontFamily = FontFamily.Monospace,
                                                                color = NeonCyan,
                                                                modifier = Modifier.weight(1f)
                                                            )
                                                            IconButton(
                                                                onClick = { activeSelector = "" },
                                                                modifier = Modifier.size(24.dp)
                                                            ) {
                                                                Icon(Icons.Default.Close, contentDescription = "Deselect", modifier = Modifier.size(14.dp))
                                                            }
                                                        }
                                                        Text(
                                                            text = selEl.selector,
                                                            fontSize = 10.sp,
                                                            fontFamily = FontFamily.Monospace,
                                                            color = MaterialTheme.colorScheme.onSurface
                                                        )

                                                        // Action buttons on selected element
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .horizontalScroll(rememberScrollState()),
                                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                        ) {
                                                            FilledTonalButton(
                                                                onClick = {
                                                                    webView?.evaluateJavascript(buildHighlightElementJs(selEl.selector), null)
                                                                    Toast.makeText(context, "Highlighted on page", Toast.LENGTH_SHORT).show()
                                                                },
                                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                                modifier = Modifier.height(28.dp)
                                                            ) {
                                                                Text("Highlight", fontSize = 10.sp)
                                                            }
                                                            FilledTonalButton(
                                                                onClick = {
                                                                    clipboard.setText(AnnotatedString(selEl.selector))
                                                                    Toast.makeText(context, "CSS selector copied", Toast.LENGTH_SHORT).show()
                                                                },
                                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                                modifier = Modifier.height(28.dp)
                                                            ) {
                                                                Text("Copy Selector", fontSize = 10.sp)
                                                            }
                                                            FilledTonalButton(
                                                                onClick = {
                                                                    clipboard.setText(AnnotatedString(selEl.outerHtmlPreview))
                                                                    Toast.makeText(context, "Element HTML copied", Toast.LENGTH_SHORT).show()
                                                                },
                                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                                modifier = Modifier.height(28.dp)
                                                            ) {
                                                                Text("Copy HTML", fontSize = 10.sp)
                                                            }
                                                            FilledTonalButton(
                                                                onClick = {
                                                                    webView?.evaluateJavascript(buildElementMutationJs(selEl.selector, "hide")) {
                                                                        refreshNonce++
                                                                    }
                                                                },
                                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                                modifier = Modifier.height(28.dp)
                                                            ) {
                                                                Text("Hide", fontSize = 10.sp, color = WarningOrange)
                                                            }
                                                            FilledTonalButton(
                                                                onClick = {
                                                                    webView?.evaluateJavascript(buildElementMutationJs(selEl.selector, "remove")) {
                                                                        activeSelector = ""
                                                                        refreshNonce++
                                                                    }
                                                                },
                                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                                modifier = Modifier.height(28.dp)
                                                            ) {
                                                                Text("Remove", fontSize = 10.sp, color = DangerRed)
                                                            }
                                                        }

                                                        if (selEl.attributes.isNotEmpty()) {
                                                            Text(
                                                                text = "Attributes: " + selEl.attributes.joinToString(" | ") { "${it.first}=\"${it.second}\"" },
                                                                fontSize = 9.5.sp,
                                                                fontFamily = FontFamily.Monospace,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                maxLines = 4,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                        }
                                                        if (selEl.computedStyles.isNotEmpty()) {
                                                            Text(
                                                                text = "Computed: " + selEl.computedStyles.joinToString("; ") { "${it.first}: ${it.second}" },
                                                                fontSize = 9.5.sp,
                                                                fontFamily = FontFamily.Monospace,
                                                                color = SuccessGreen,
                                                                maxLines = 4,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                        }
                                                        if (selEl.outerHtmlPreview.isNotBlank()) {
                                                            Surface(
                                                                shape = RoundedCornerShape(8.dp),
                                                                color = MaterialTheme.colorScheme.surface
                                                            ) {
                                                                Text(
                                                                    text = selEl.outerHtmlPreview.take(650),
                                                                    fontSize = 9.5.sp,
                                                                    fontFamily = FontFamily.Monospace,
                                                                    modifier = Modifier.padding(8.dp),
                                                                    maxLines = 8,
                                                                    overflow = TextOverflow.Ellipsis
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        item {
                                            Text(
                                                text = "Showing ${snapshot.domNodes.size} of ${snapshot.queryMatchCount} nodes (tap node to inspect)",
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        items(snapshot.domNodes, key = { "${it.selector}_${it.depth}_${it.tag}_${it.rectLabel}" }) { node ->
                                            val isSelected = node.selector.isNotBlank() && node.selector == activeSelector
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = if (isSelected) NeonCyan.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainer,
                                                border = if (isSelected) BorderStroke(1.dp, NeonCyan.copy(alpha = 0.5f)) else null,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(start = (node.depth.coerceIn(0, 6) * 8).dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable {
                                                        if (node.selector.isNotBlank()) {
                                                            activeSelector = node.selector
                                                            webView?.evaluateJavascript(buildHighlightElementJs(node.selector), null)
                                                        }
                                                    }
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                                ) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        val label = buildString {
                                                            append("<").append(node.tag)
                                                            if (node.idAttr.isNotBlank()) append("#").append(node.idAttr)
                                                            if (node.classAttr.isNotBlank()) {
                                                                append(".").append(node.classAttr.trim().replace(Regex("\\s+"), "."))
                                                            }
                                                            append(">")
                                                        }
                                                        Text(
                                                            text = label,
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            fontFamily = FontFamily.Monospace,
                                                            color = NeonCyan,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                            modifier = Modifier.weight(1f)
                                                        )
                                                        Text(
                                                            text = "${node.rectLabel}${if (node.childCount > 0) " • ${node.childCount}ch" else ""}",
                                                            fontSize = 9.sp,
                                                            fontFamily = FontFamily.Monospace,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                    if (node.attrsSummary.isNotBlank()) {
                                                        Text(
                                                            text = node.attrsSummary,
                                                            fontSize = 9.5.sp,
                                                            fontFamily = FontFamily.Monospace,
                                                            color = WarningOrange,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                    if (node.textSnippet.isNotBlank()) {
                                                        Text(
                                                            text = "\"${node.textSnippet}\"",
                                                            fontSize = 10.sp,
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
                        }

                        // ==================== TAB 2: CONSOLE & JS REPL ====================
                        2 -> {
                            val filteredLogs = remember(consoleLogs, consoleLevelFilter) {
                                val list = when (consoleLevelFilter) {
                                    "ERROR" -> consoleLogs.filter { it.level == "ERROR" }
                                    "WARN" -> consoleLogs.filter { it.level == "WARN" }
                                    "LOG" -> consoleLogs.filter { it.level == "LOG" || it.level == "INFO" || it.level == "EVAL" }
                                    else -> consoleLogs
                                }
                                list.reversed()
                            }
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("inspector_console_panel"),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                        listOf("ALL", "ERROR", "WARN", "LOG").forEach { lvl ->
                                            FilterChip(
                                                selected = consoleLevelFilter == lvl,
                                                onClick = { consoleLevelFilter = lvl },
                                                label = { Text(lvl, fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                                                modifier = Modifier.height(28.dp)
                                            )
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                        IconButton(
                                            onClick = {
                                                val allText = consoleLogs.joinToString("\n") {
                                                    "[${it.level}] ${it.message}${if (it.source.isNotBlank()) " (${it.source}:${it.lineNumber})" else ""}"
                                                }
                                                clipboard.setText(AnnotatedString(allText))
                                                Toast.makeText(context, "Console logs copied", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy Console Logs", modifier = Modifier.size(16.dp))
                                        }
                                        IconButton(
                                            onClick = onClearConsoleLogs,
                                            modifier = Modifier
                                                .size(32.dp)
                                                .testTag("btn_clear_console_logs")
                                        ) {
                                            Icon(Icons.Default.DeleteSweep, contentDescription = "Clear Console", tint = DangerRed, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }

                                if (filteredLogs.isEmpty()) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainer,
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(20.dp),
                                            verticalArrangement = Arrangement.Center,
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Terminal,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(32.dp)
                                            )
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = "No console messages yet",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = "Run JavaScript below or interact with the page.",
                                                fontSize = 10.5.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                } else {
                                    LazyColumn(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        items(filteredLogs, key = { it.id }) { entry ->
                                            val levelColor = when (entry.level) {
                                                "ERROR" -> DangerRed
                                                "WARN" -> WarningOrange
                                                "EVAL" -> NeonCyan
                                                else -> SuccessGreen
                                            }
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = levelColor.copy(alpha = 0.08f),
                                                border = BorderStroke(0.6.dp, levelColor.copy(alpha = 0.35f)),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween
                                                    ) {
                                                        Text(
                                                            text = entry.level,
                                                            fontSize = 9.5.sp,
                                                            fontWeight = FontWeight.Black,
                                                            fontFamily = FontFamily.Monospace,
                                                            color = levelColor
                                                        )
                                                        Text(
                                                            text = timeFormat.format(Date(entry.timestampMs)),
                                                            fontSize = 9.sp,
                                                            fontFamily = FontFamily.Monospace,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                    Text(
                                                        text = entry.message,
                                                        fontSize = 11.sp,
                                                        fontFamily = FontFamily.Monospace,
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                    if (entry.source.isNotBlank()) {
                                                        Text(
                                                            text = "${entry.source.substringAfterLast('/')}:${entry.lineNumber}",
                                                            fontSize = 9.sp,
                                                            fontFamily = FontFamily.Monospace,
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

                                // Quick Snippet Chips
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    listOf(
                                        "document.title",
                                        "document.images.length",
                                        "document.scripts.length",
                                        "JSON.stringify(Object.keys(localStorage))",
                                        "location.href"
                                    ).forEach { snippet ->
                                        AssistChip(
                                            onClick = { jsReplInput = snippet },
                                            label = {
                                                Text(
                                                    text = snippet.substringAfterLast("JSON.stringify(").removeSuffix(")"),
                                                    fontSize = 9.5.sp,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                            },
                                            modifier = Modifier.height(26.dp)
                                        )
                                    }
                                }

                                // Interactive JS REPL Input Bar
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = jsReplInput,
                                        onValueChange = { jsReplInput = it },
                                        placeholder = {
                                            Text("> Execute JavaScript in page...", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                        },
                                        singleLine = true,
                                        textStyle = LocalTextStyle.current.copy(fontSize = 11.5.sp, fontFamily = FontFamily.Monospace),
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("input_inspector_js_repl")
                                    )
                                    FilledTonalButton(
                                        onClick = {
                                            val code = jsReplInput.trim()
                                            if (code.isNotEmpty() && webView != null) {
                                                onRecordEvalConsoleMessage("EVAL", "> $code")
                                                val wrapped = "(function(){try{var __r=(0,eval)(${JSONObject.quote(code)});if(typeof __r==='object'&&__r!==null){try{return JSON.stringify(__r);}catch(e){return String(__r);}}return String(__r);}catch(err){return '__ERR__:'+String(err&&err.message?err.message:err);}})();"
                                                webView.evaluateJavascript(wrapped) { raw ->
                                                    val decoded = runCatching {
                                                        org.json.JSONTokener(raw ?: "null").nextValue()?.toString()
                                                    }.getOrDefault(raw ?: "undefined").orEmpty()
                                                    if (decoded.startsWith("__ERR__:")) {
                                                        onRecordEvalConsoleMessage("ERROR", decoded.removePrefix("__ERR__:"))
                                                    } else {
                                                        onRecordEvalConsoleMessage("LOG", "< $decoded")
                                                    }
                                                }
                                                jsReplInput = ""
                                            }
                                        },
                                        enabled = jsReplInput.isNotBlank() && webView != null,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier
                                            .defaultMinSize(minHeight = 48.dp)
                                            .testTag("btn_run_inspector_js")
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Run JS", modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }

                        // ==================== TAB 3: NETWORK & RESOURCES ====================
                        3 -> {
                            val timingByUrl = remember(snapshot.resourceTimings) {
                                snapshot.resourceTimings.associateBy { it.name }
                            }
                            val filteredNetwork = remember(networkLogs, networkCategoryFilter, networkSearchQuery) {
                                networkLogs.filter { entry ->
                                    val matchesCat = when (networkCategoryFilter) {
                                        "BLOCKED" -> entry.statusLabel.contains("Block", true) || entry.statusLabel.contains("DNR", true)
                                        "SCRIPT_XHR" -> entry.resourceType in setOf("Script", "XHR/Fetch", "Document")
                                        "MEDIA" -> entry.resourceType in setOf("Image", "Media", "Font")
                                        "TUNNEL" -> entry.statusLabel.contains("Tunnel", true)
                                        else -> true
                                    }
                                    val q = networkSearchQuery.trim().lowercase(Locale.US)
                                    val matchesQ = q.isBlank() || entry.url.lowercase(Locale.US).contains(q) || entry.domain.lowercase(Locale.US).contains(q)
                                    matchesCat && matchesQ
                                }.reversed()
                            }

                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("inspector_network_panel"),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = networkSearchQuery,
                                        onValueChange = { networkSearchQuery = it },
                                        placeholder = { Text("Filter requests by URL or domain", fontSize = 11.sp) },
                                        singleLine = true,
                                        textStyle = LocalTextStyle.current.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = onClearNetworkLogs,
                                        modifier = Modifier
                                            .size(38.dp)
                                            .testTag("btn_clear_network_logs")
                                    ) {
                                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear Network Log", tint = DangerRed, modifier = Modifier.size(18.dp))
                                    }
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    listOf(
                                        "ALL" to "All (${networkLogs.size})",
                                        "BLOCKED" to "Blocked (${networkLogs.count { it.statusLabel.contains("Block", true) || it.statusLabel.contains("DNR", true) }})",
                                        "SCRIPT_XHR" to "JS / XHR / Doc",
                                        "MEDIA" to "Media / Img",
                                        "TUNNEL" to "DNS Tunnel"
                                    ).forEach { (cat, label) ->
                                        FilterChip(
                                            selected = networkCategoryFilter == cat,
                                            onClick = { networkCategoryFilter = cat },
                                            label = { Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                                            modifier = Modifier.height(28.dp)
                                        )
                                    }
                                }

                                if (filteredNetwork.isEmpty()) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainer,
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(20.dp),
                                            verticalArrangement = Arrangement.Center,
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Icon(Icons.Default.WifiFind, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text("No matching network requests captured", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            Text("Reload or browse on this tab to inspect HTTP/HTTPS & blocked tracker requests.", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                } else {
                                    LazyColumn(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        items(filteredNetwork, key = { it.id }) { req ->
                                            val timing = timingByUrl[req.url]
                                            val statusColor = when {
                                                req.statusLabel.contains("Block", true) || req.statusLabel.contains("DNR", true) -> DangerRed
                                                req.statusLabel.contains("Tunnel", true) -> WarningOrange
                                                else -> SuccessGreen
                                            }
                                            Surface(
                                                shape = RoundedCornerShape(9.dp),
                                                color = MaterialTheme.colorScheme.surfaceContainer,
                                                border = BorderStroke(0.6.dp, statusColor.copy(alpha = 0.35f)),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(9.dp))
                                                    .clickable {
                                                        clipboard.setText(AnnotatedString(req.url))
                                                        Toast.makeText(context, "Request URL copied", Toast.LENGTH_SHORT).show()
                                                    }
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(8.dp),
                                                    verticalArrangement = Arrangement.spacedBy(3.dp)
                                                ) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                                            InspectorMiniBadge(req.method, NeonCyan)
                                                            InspectorMiniBadge(req.resourceType, MaterialTheme.colorScheme.secondary)
                                                            InspectorMiniBadge(req.statusLabel, statusColor)
                                                        }
                                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                                            if (timing != null) {
                                                                val sizeKb = if (timing.transferBytes > 0) " • ${timing.transferBytes / 1024}KB" else ""
                                                                Text(
                                                                    text = "${timing.durationMs}ms$sizeKb",
                                                                    fontSize = 9.sp,
                                                                    fontFamily = FontFamily.Monospace,
                                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                                )
                                                            }
                                                            IconButton(
                                                                onClick = { onOpenUrlInNewTab(req.url) },
                                                                modifier = Modifier.size(22.dp)
                                                            ) {
                                                                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open Request URL", modifier = Modifier.size(13.dp), tint = NeonCyan)
                                                            }
                                                        }
                                                    }
                                                    Text(
                                                        text = req.url,
                                                        fontSize = 10.sp,
                                                        fontFamily = FontFamily.Monospace,
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // ==================== TAB 4: STORAGE & META ====================
                        4 -> {
                            val cookieRaw = remember(snapshot.documentCookie, pageUrl, refreshNonce) {
                                val cmCookie = runCatching { CookieManager.getInstance().getCookie(pageUrl) }.getOrNull().orEmpty()
                                cmCookie.ifBlank { snapshot.documentCookie }
                            }
                            val parsedCookies = remember(cookieRaw) {
                                cookieRaw.split(";")
                                    .mapNotNull { piece ->
                                        val trimmed = piece.trim()
                                        val eq = trimmed.indexOf('=')
                                        if (eq > 0) trimmed.substring(0, eq).trim() to trimmed.substring(eq + 1).trim() else null
                                    }
                            }

                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .testTag("inspector_storage_panel"),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Cookies Card
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "COOKIES (${parsedCookies.size})",
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.Black,
                                                fontFamily = FontFamily.Monospace,
                                                color = WarningOrange
                                            )
                                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                if (cookieRaw.isNotBlank()) {
                                                    TextButton(
                                                        onClick = {
                                                            clipboard.setText(AnnotatedString(cookieRaw))
                                                            Toast.makeText(context, "Cookies copied", Toast.LENGTH_SHORT).show()
                                                        },
                                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                                        modifier = Modifier.height(26.dp)
                                                    ) { Text("Copy", fontSize = 10.sp) }
                                                }
                                                TextButton(
                                                    onClick = {
                                                        onClearSiteCookies()
                                                        refreshNonce++
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                                    modifier = Modifier.height(26.dp)
                                                ) { Text("Clear", fontSize = 10.sp, color = DangerRed) }
                                            }
                                        }
                                        if (parsedCookies.isEmpty()) {
                                            Text("No cookies set for this origin.", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        } else {
                                            parsedCookies.forEach { (k, v) ->
                                                InspectorStorageRow(
                                                    keyName = k,
                                                    value = v,
                                                    onCopy = {
                                                        clipboard.setText(AnnotatedString("$k=$v"))
                                                        Toast.makeText(context, "Cookie copied", Toast.LENGTH_SHORT).show()
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }

                                // localStorage Card
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "LOCAL STORAGE (${snapshot.localStorageItems.size})",
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.Black,
                                                fontFamily = FontFamily.Monospace,
                                                color = NeonCyan
                                            )
                                            if (snapshot.localStorageItems.isNotEmpty()) {
                                                TextButton(
                                                    onClick = {
                                                        webView?.evaluateJavascript("try{localStorage.clear();}catch(e){}") { refreshNonce++ }
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                                    modifier = Modifier.height(26.dp)
                                                ) { Text("Clear", fontSize = 10.sp, color = DangerRed) }
                                            }
                                        }
                                        if (snapshot.localStorageItems.isEmpty()) {
                                            Text("Empty localStorage.", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        } else {
                                            snapshot.localStorageItems.forEach { (k, v) ->
                                                InspectorStorageRow(
                                                    keyName = k,
                                                    value = v,
                                                    onCopy = {
                                                        clipboard.setText(AnnotatedString(v))
                                                        Toast.makeText(context, "Copied value", Toast.LENGTH_SHORT).show()
                                                    },
                                                    onDelete = {
                                                        webView?.evaluateJavascript("try{localStorage.removeItem(${JSONObject.quote(k)});}catch(e){}") { refreshNonce++ }
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }

                                // sessionStorage Card
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "SESSION STORAGE (${snapshot.sessionStorageItems.size})",
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.Black,
                                                fontFamily = FontFamily.Monospace,
                                                color = SuccessGreen
                                            )
                                            if (snapshot.sessionStorageItems.isNotEmpty()) {
                                                TextButton(
                                                    onClick = {
                                                        webView?.evaluateJavascript("try{sessionStorage.clear();}catch(e){}") { refreshNonce++ }
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                                    modifier = Modifier.height(26.dp)
                                                ) { Text("Clear", fontSize = 10.sp, color = DangerRed) }
                                            }
                                        }
                                        if (snapshot.sessionStorageItems.isEmpty()) {
                                            Text("Empty sessionStorage.", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        } else {
                                            snapshot.sessionStorageItems.forEach { (k, v) ->
                                                InspectorStorageRow(
                                                    keyName = k,
                                                    value = v,
                                                    onCopy = {
                                                        clipboard.setText(AnnotatedString(v))
                                                        Toast.makeText(context, "Copied value", Toast.LENGTH_SHORT).show()
                                                    },
                                                    onDelete = {
                                                        webView?.evaluateJavascript("try{sessionStorage.removeItem(${JSONObject.quote(k)});}catch(e){}") { refreshNonce++ }
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }

                                // Meta & Link Tags Card
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Text(
                                            text = "META & HEAD TAGS (${snapshot.metaTags.size})",
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.Black,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        if (snapshot.metaTags.isEmpty()) {
                                            Text("No <meta> tags detected.", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        } else {
                                            snapshot.metaTags.forEach { (k, v) ->
                                                InspectorKeyValueLine(label = k, value = v)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

                    // Right Edge Resize Strip
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .width(10.dp)
                            .fillMaxHeight()
                            .padding(top = 42.dp, bottom = 32.dp)
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    if (curIsMax) isMaximized = false
                                    val dx = with(density) { dragAmount.x.toDp() }
                                    winWidth = (winWidth + dx).coerceIn(240.dp, (curMaxW - curEffX - 4.dp).coerceAtLeast(240.dp))
                                }
                            }
                    )

                    // Bottom Edge Resize Strip
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .height(10.dp)
                            .fillMaxWidth()
                            .padding(end = 32.dp)
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    if (curIsMax) isMaximized = false
                                    val dy = with(density) { dragAmount.y.toDp() }
                                    winHeight = (winHeight + dy).coerceIn(190.dp, (curMaxH - curEffY - 4.dp).coerceAtLeast(190.dp))
                                }
                            }
                    )

                    // Bottom-Right Corner Free Resize Handle
                    Box(
                        contentAlignment = Alignment.BottomEnd,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(34.dp)
                            .clip(RoundedCornerShape(topStart = 10.dp, bottomEnd = 16.dp))
                            .background(NeonCyan.copy(alpha = 0.14f))
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    if (curIsMax) isMaximized = false
                                    val dx = with(density) { dragAmount.x.toDp() }
                                    val dy = with(density) { dragAmount.y.toDp() }
                                    winWidth = (winWidth + dx).coerceIn(240.dp, (curMaxW - curEffX - 4.dp).coerceAtLeast(240.dp))
                                    winHeight = (winHeight + dy).coerceIn(190.dp, (curMaxH - curEffY - 4.dp).coerceAtLeast(190.dp))
                                }
                            }
                            .testTag("inspector_resize_handle")
                    ) {
                        Icon(
                            imageVector = Icons.Default.OpenInFull,
                            contentDescription = "Resize Inspector Window",
                            tint = NeonCyan,
                            modifier = Modifier
                                .padding(5.dp)
                                .size(14.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InspectorMiniBadge(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.16f),
        border = BorderStroke(0.6.dp, color.copy(alpha = 0.45f))
    ) {
        Text(
            text = text,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = color,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
            maxLines = 1
        )
    }
}

@Composable
private fun InspectorMetricTile(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                text = value,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                color = accent,
                maxLines = 1
            )
            Text(
                text = label,
                fontSize = 9.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun InspectorClickableCountTile(
    label: String,
    count: Int,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(0.6.dp, accent.copy(alpha = 0.35f)),
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                text = "$count",
                fontSize = 13.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                color = accent
            )
            Text(
                text = label,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun InspectorKeyValueLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 84.dp, max = 125.dp)
        )
        Text(
            text = value.ifBlank { "—" },
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun InspectorStorageRow(
    keyName: String,
    value: String,
    onCopy: () -> Unit,
    onDelete: (() -> Unit)? = null
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = keyName,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = NeonCyan,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = value,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(14.dp))
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = DangerRed, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

internal fun buildAutofillPasswordJs(username: String, password: String): String {
    val safeU = JSONObject.quote(username)
    val safeP = JSONObject.quote(password)
    return """
    (function(){
        try {
            var uVal = $safeU;
            var pVal = $safeP;
            var setNativeVal = function(el, val) {
                if (!el) return;
                var proto = el.constructor && el.constructor.prototype;
                var desc = proto ? Object.getOwnPropertyDescriptor(proto, 'value') : null;
                if (desc && desc.set) desc.set.call(el, val);
                else el.value = val;
                el.dispatchEvent(new Event('input', { bubbles: true }));
                el.dispatchEvent(new Event('change', { bubbles: true }));
            };
            var passEl = document.querySelector('input[type="password"]:not([disabled])');
            if (passEl && pVal) setNativeVal(passEl, pVal);
            var userEl = document.querySelector('input[type="email"]:not([disabled]), input[name*="user" i]:not([disabled]), input[name*="email" i]:not([disabled]), input[name*="login" i]:not([disabled]), input[autocomplete="username"]:not([disabled]), input[type="text"]:not([disabled])');
            if (userEl && uVal) setNativeVal(userEl, uVal);
            return (userEl || passEl) ? 'Filled login form' : 'No login inputs found on page';
        } catch(e) { return 'Autofill failed'; }
    })();
    """.trimIndent()
}

@Composable
fun BrowserPasswordsDialog(
    passwords: List<SavedPasswordEntry>,
    activePageUrl: String,
    googleSyncState: com.example.data.browser.GoogleSyncState = com.example.data.browser.GoogleSyncState(),
    onToggleAutoSyncPasswords: (Boolean) -> Unit = {},
    onOpenGoogleSyncDialog: () -> Unit = {},
    onSyncNowClick: () -> Unit = {},
    onAddManualPassword: (url: String, username: String, password: String, title: String) -> Unit = { _, _, _, _ -> },
    onImportFromStorageClick: () -> Unit,
    onOpenGooglePasswordExportClick: () -> Unit = {},
    onFillOnPage: (SavedPasswordEntry) -> Unit,
    onDeleteEntry: (String) -> Unit,
    onRemoveLocalPasswords: () -> Unit = {},
    onClearAll: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var searchQuery by remember { mutableStateOf("") }
    var revealedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showAddForm by remember { mutableStateOf(false) }
    var showRemoveLocalConfirm by remember { mutableStateOf(false) }

    val activeHost = remember(activePageUrl) {
        runCatching { android.net.Uri.parse(activePageUrl).host?.lowercase(Locale.US)?.removePrefix("www.") }.getOrNull().orEmpty()
    }

    var newSiteUrl by remember(activePageUrl) {
        mutableStateOf(if (activePageUrl.startsWith("http", true)) activePageUrl else "")
    }
    var newUsername by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var newTitle by remember(activeHost) { mutableStateOf(activeHost) }

    val filtered = remember(passwords, searchQuery) {
        val q = searchQuery.trim().lowercase(Locale.US)
        if (q.isBlank()) passwords
        else passwords.filter {
            it.domain.lowercase(Locale.US).contains(q) ||
                it.title.lowercase(Locale.US).contains(q) ||
                it.username.lowercase(Locale.US).contains(q) ||
                it.url.lowercase(Locale.US).contains(q)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("browser_passwords_dialog"),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Password, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(20.dp))
                    Text("Synced & Saved Passwords (${passwords.size})", fontWeight = FontWeight.Black, fontSize = 15.sp)
                }
                if (passwords.isNotEmpty()) {
                    TextButton(onClick = onClearAll) {
                        Text("Clear All", color = DangerRed, fontSize = 11.5.sp)
                    }
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Google Account Auto-Sync Banner for Passwords
                val syncEmail = googleSyncState.effectiveAccountEmail
                val isSyncActive = googleSyncState.autoSyncEnabled && googleSyncState.autoSyncPasswords
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (googleSyncState.isBrowserGoogleConnected && isSyncActive) {
                        SuccessGreen.copy(alpha = 0.12f)
                    } else {
                        NeonCyan.copy(alpha = 0.10f)
                    },
                    border = BorderStroke(
                        1.dp,
                        if (googleSyncState.isBrowserGoogleConnected && isSyncActive) SuccessGreen.copy(alpha = 0.45f)
                        else NeonCyan.copy(alpha = 0.40f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onOpenGoogleSyncDialog() }
                        .testTag("passwords_google_sync_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudSync,
                            contentDescription = "Google Password Sync",
                            tint = if (googleSyncState.isBrowserGoogleConnected && isSyncActive) SuccessGreen else NeonCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (!syncEmail.isNullOrBlank()) {
                                    "Google Auto-Sync • $syncEmail"
                                } else if (googleSyncState.hasWebViewGoogleSession) {
                                    "Google Account Active in Browser"
                                } else {
                                    "Google Account Password Auto-Sync"
                                },
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = when {
                                    !googleSyncState.syncErrorMessage.isNullOrBlank() ->
                                        googleSyncState.syncErrorMessage
                                    isSyncActive && !googleSyncState.isCloudAuthConnected && googleSyncState.isBrowserGoogleConnected ->
                                        "Browser session only • Tap here to enable Cloud Sync"
                                    isSyncActive ->
                                        "Auto-syncing passwords & passkeys (${passwords.size} synced)"
                                    else ->
                                        "Tap to sign in or enable Google password sync"
                                },
                                fontSize = 9.5.sp,
                                color = if (!googleSyncState.syncErrorMessage.isNullOrBlank()) DangerRed else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(
                            onClick = onSyncNowClick,
                            modifier = Modifier
                                .size(30.dp)
                                .testTag("btn_passwords_sync_now")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Sync Passwords Now",
                                tint = NeonCyan,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Switch(
                            checked = isSyncActive,
                            onCheckedChange = { onToggleAutoSyncPasswords(it) },
                            modifier = Modifier
                                .height(26.dp)
                                .testTag("switch_password_google_auto_sync")
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onImportFromStorageClick,
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color(0xFF04131B)),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 40.dp)
                            .testTag("btn_import_passwords_from_storage")
                    ) {
                        Icon(Icons.Default.UploadFile, contentDescription = "Import Passwords", modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Import CSV/JSON", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { showAddForm = !showAddForm },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 40.dp)
                            .testTag("btn_toggle_add_password_form")
                    ) {
                        Icon(
                            imageVector = if (showAddForm) Icons.Default.Close else Icons.Default.Add,
                            contentDescription = "Add Password",
                            tint = SuccessGreen,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (showAddForm) "Cancel" else "Add Password",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = SuccessGreen,
                            maxLines = 1
                        )
                    }
                }

                OutlinedButton(
                    onClick = { showRemoveLocalConfirm = true },
                    enabled = passwords.isNotEmpty(),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(
                        1.dp,
                        if (passwords.isNotEmpty()) DangerRed.copy(alpha = 0.55f)
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = DangerRed,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("btn_remove_local_passwords")
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = "Remove Local Passwords",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (passwords.isNotEmpty()) {
                            "Remove Local Passwords (${passwords.size})"
                        } else {
                            "Remove Local Passwords"
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }

                if (showAddForm) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        border = BorderStroke(1.dp, SuccessGreen.copy(alpha = 0.45f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("add_password_form_card")
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "New Password (auto-syncs to Google Account)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = SuccessGreen
                            )
                            OutlinedTextField(
                                value = newSiteUrl,
                                onValueChange = { newSiteUrl = it },
                                placeholder = { Text("Website URL or domain (e.g. github.com)", fontSize = 11.sp) },
                                singleLine = true,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("input_new_password_url")
                            )
                            OutlinedTextField(
                                value = newUsername,
                                onValueChange = { newUsername = it },
                                placeholder = { Text("Username or Email", fontSize = 11.sp) },
                                singleLine = true,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("input_new_password_username")
                            )
                            OutlinedTextField(
                                value = newPassword,
                                onValueChange = { newPassword = it },
                                placeholder = { Text("Password", fontSize = 11.sp) },
                                singleLine = true,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("input_new_password_secret")
                            )
                            Button(
                                onClick = {
                                    if (newUsername.isNotBlank() || newPassword.isNotBlank()) {
                                        onAddManualPassword(
                                            newSiteUrl.trim().ifBlank { activePageUrl },
                                            newUsername.trim(),
                                            newPassword,
                                            newTitle.trim()
                                        )
                                        newUsername = ""
                                        newPassword = ""
                                        showAddForm = false
                                    }
                                },
                                enabled = newUsername.isNotBlank() || newPassword.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen, contentColor = Color.White),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("btn_save_new_password")
                            ) {
                                Icon(Icons.Default.CloudDone, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Save & Sync Password", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search site or username...", fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                if (filtered.isEmpty()) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        Text(
                            text = if (passwords.isEmpty()) {
                                "No saved passwords in Cloud Vault yet. Sign in on any site, add manually, or export CSV from passwords.google.com/options (downloaded CSV/JSON auto-imports & syncs)."
                            } else {
                                "No matching credentials."
                            },
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (passwords.isEmpty()) {
                            OutlinedButton(
                                onClick = onOpenGooglePasswordExportClick,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.45f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("btn_passwords_export_google")
                            ) {
                                Icon(Icons.Default.CloudDownload, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Export from passwords.google.com/options",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = NeonCyan,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 270.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filtered, key = { it.id }) { entry ->
                            val isRevealed = revealedIds.contains(entry.id)
                            val domClean = entry.domain.lowercase(Locale.US).removePrefix("www.")
                            val isSiteMatch = activeHost.isNotBlank() && domClean.isNotBlank() &&
                                (activeHost == domClean || activeHost.endsWith(".$domClean") || domClean.endsWith(".$activeHost"))
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSiteMatch) NeonCyan.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                border = if (isSiteMatch) BorderStroke(1.dp, NeonCyan.copy(alpha = 0.5f)) else null,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = entry.title.ifBlank { entry.domain.ifBlank { "Saved Login" } },
                                                fontSize = 12.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "${entry.domain.ifBlank { entry.url }} • ${entry.importedFrom}",
                                                fontSize = 9.5.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(
                                                onClick = {
                                                    revealedIds = if (isRevealed) revealedIds - entry.id else revealedIds + entry.id
                                                },
                                                modifier = Modifier.size(30.dp)
                                            ) {
                                                Icon(
                                                    imageVector = if (isRevealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                    contentDescription = if (isRevealed) "Hide Password" else "Show Password",
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                            IconButton(
                                                onClick = { onDeleteEntry(entry.id) },
                                                modifier = Modifier.size(30.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.DeleteOutline,
                                                    contentDescription = "Delete Password",
                                                    tint = DangerRed,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }

                                    val isPasskeyEntry = com.example.data.browser.BrowserPasskeyManager.isPasskeySecret(entry.password)
                                    Text(
                                        text = "User: ${entry.username.ifBlank { "—" }}",
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = if (isPasskeyEntry) {
                                                "Key: ${com.example.data.browser.BrowserPasskeyManager.formatSecretForDisplay(entry.password, isRevealed)}"
                                            } else {
                                                "Pass: ${if (isRevealed) entry.password else "•".repeat(entry.password.length.coerceIn(6, 14))}"
                                            },
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = if (isPasskeyEntry) NeonCyan else if (isRevealed) WarningOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        if (isPasskeyEntry) {
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = NeonCyan.copy(alpha = 0.16f)
                                            ) {
                                                Text(
                                                    text = "PASSKEY",
                                                    fontSize = 8.5.sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = NeonCyan,
                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        FilledTonalButton(
                                            onClick = {
                                                clipboard.setText(AnnotatedString(entry.username))
                                                Toast.makeText(context, "Username copied", Toast.LENGTH_SHORT).show()
                                            },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier.height(28.dp)
                                        ) {
                                            Text("Copy User", fontSize = 10.sp)
                                        }
                                        FilledTonalButton(
                                            onClick = {
                                                clipboard.setText(AnnotatedString(entry.password))
                                                Toast.makeText(context, "Password copied", Toast.LENGTH_SHORT).show()
                                            },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier.height(28.dp)
                                        ) {
                                            Text("Copy Pass", fontSize = 10.sp)
                                        }
                                        FilledTonalButton(
                                            onClick = { onFillOnPage(entry) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier.height(28.dp)
                                        ) {
                                            Text("Fill Page", fontSize = 10.sp, color = SuccessGreen, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )

    if (showRemoveLocalConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveLocalConfirm = false },
            modifier = Modifier.testTag("confirm_remove_local_passwords_dialog"),
            icon = {
                Icon(
                    imageVector = Icons.Default.DeleteSweep,
                    contentDescription = "Remove Local Passwords",
                    tint = DangerRed
                )
            },
            title = {
                Text("Remove Local Passwords?", fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    "Remove all ${passwords.size} locally stored password(s) from this device? Passwords already synced to your Google Cloud account will not be deleted.",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRemoveLocalConfirm = false
                        onRemoveLocalPasswords()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed, contentColor = Color.White),
                    modifier = Modifier.testTag("btn_confirm_remove_local_passwords")
                ) {
                    Text("Remove Local", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveLocalConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun BrowserGoogleSyncDialog(
    syncState: com.example.data.browser.GoogleSyncState,
    passwordsCount: Int,
    onSignInWithGoogleClick: () -> Unit,
    onSignInViaBrowserTabClick: () -> Unit,
    onOpenGooglePasswordManagerClick: () -> Unit = {},
    onSyncNowClick: () -> Unit,
    onSignOutClick: () -> Unit,
    onToggleMasterAutoSync: (Boolean) -> Unit,
    onToggleAutoSyncPasswords: (Boolean) -> Unit,
    onToggleAutoCapturePasswords: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val formatter = remember { java.text.SimpleDateFormat("MMM dd, HH:mm:ss", Locale.US) }
    val effectiveEmail = syncState.effectiveAccountEmail
    val effectiveName = syncState.effectiveAccountName
    val isConnected = syncState.isBrowserGoogleConnected

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("browser_google_sync_dialog"),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CloudSync,
                    contentDescription = null,
                    tint = if (isConnected && syncState.autoSyncEnabled) SuccessGreen else NeonCyan,
                    modifier = Modifier.size(22.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text("Google Account Auto-Sync", fontWeight = FontWeight.Black, fontSize = 16.5.sp)
                    Text(
                        text = if (!effectiveEmail.isNullOrBlank()) {
                            "Signed in as $effectiveEmail"
                        } else if (syncState.hasWebViewGoogleSession) {
                            "Google Account connected in browser"
                        } else {
                            "Sync passwords & passkeys across your Google account"
                        },
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Account Card
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isConnected) {
                        SuccessGreen.copy(alpha = 0.12f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    },
                    border = BorderStroke(
                        1.dp,
                        if (isConnected) SuccessGreen.copy(alpha = 0.45f)
                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = if (isConnected) Icons.Default.AccountCircle else Icons.Default.PersonOutline,
                                contentDescription = null,
                                tint = if (isConnected) SuccessGreen else NeonCyan,
                                modifier = Modifier.size(28.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = effectiveName ?: if (isConnected) "Google Account Connected" else "Not Signed In",
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = effectiveEmail
                                    ?: if (isConnected) "Active Browser Google session • $passwordsCount 🔑"
                                    else "Sign in to Google in browser or link your Google Account",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (isConnected) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (syncState.isCloudAuthConnected) {
                                        SuccessGreen.copy(alpha = 0.16f)
                                    } else {
                                        WarningOrange.copy(alpha = 0.16f)
                                    },
                                    modifier = Modifier.padding(top = 2.dp)
                                ) {
                                    Text(
                                        text = if (syncState.isCloudAuthConnected) {
                                            "Cloud Sync Active (Firestore)"
                                        } else {
                                            "Browser Session Only • Tap Enable Cloud Sync below"
                                        },
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (syncState.isCloudAuthConnected) SuccessGreen else WarningOrange,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        if (syncState.isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = NeonCyan
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!isConnected) {
                            Button(
                                onClick = onSignInWithGoogleClick,
                                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color(0xFF04131B)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("btn_google_sync_sign_in")
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Sign In & Sync", fontSize = 11.5.sp, fontWeight = FontWeight.ExtraBold)
                            }
                            OutlinedButton(
                                onClick = onSignInViaBrowserTabClick,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("btn_google_sync_browser_login")
                            ) {
                                Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Browser Sign-In", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Button(
                                onClick = onSyncNowClick,
                                enabled = !syncState.isSyncing,
                                colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen, contentColor = Color.White),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("btn_google_sync_now")
                            ) {
                                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Sync Now", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = onSignOutClick,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.testTag("btn_google_sync_sign_out")
                            ) {
                                Text("Sign Out", fontSize = 11.sp, color = DangerRed, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    if (isConnected && !syncState.isCloudAuthConnected) {
                        Button(
                            onClick = onSignInWithGoogleClick,
                            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color(0xFF04131B)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("btn_google_sync_sign_in")
                        ) {
                            Icon(Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Enable Cloud Sync (Google Auth)", fontSize = 11.5.sp, fontWeight = FontWeight.ExtraBold)
                        }
                    }

                    OutlinedButton(
                        onClick = onOpenGooglePasswordManagerClick,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("btn_open_google_password_manager")
                    ) {
                        Icon(Icons.Default.Password, contentDescription = null, modifier = Modifier.size(15.dp), tint = NeonCyan)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Export / View Google Passwords (passwords.google.com/options)",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = NeonCyan,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (passwordsCount == 0) {
                        Text(
                            text = "Chrome's built-in sync is restricted to Google Chrome. Any Google Passwords.csv downloaded in the browser auto-imports & syncs to your Cloud Vault.",
                            fontSize = 9.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

                // Auto-Sync Toggles
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Auto-Sync with Google Account", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    "Automatically sync passwords & passkeys when signed in",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = syncState.autoSyncEnabled,
                                onCheckedChange = onToggleMasterAutoSync,
                                modifier = Modifier.testTag("switch_google_auto_sync_master")
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Auto-Sync Passwords ($passwordsCount)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Syncs existing & newly saved passwords immediately",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = syncState.autoSyncPasswords,
                                enabled = syncState.autoSyncEnabled,
                                onCheckedChange = onToggleAutoSyncPasswords,
                                modifier = Modifier.testTag("switch_google_auto_sync_passwords")
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Auto-Save New Web Login Passwords", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Captures new website logins in browser & syncs to Google",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = syncState.autoCaptureWebPasswords,
                                onCheckedChange = onToggleAutoCapturePasswords,
                                modifier = Modifier.testTag("switch_google_auto_capture_passwords")
                            )
                        }
                    }
                }

                // Status / Telemetry Footer
                val statusText = syncState.syncErrorMessage
                    ?: syncState.syncStatusMessage
                    ?: if (syncState.lastSyncedAtMs > 0L) {
                        "Last synced: ${formatter.format(java.util.Date(syncState.lastSyncedAtMs))}"
                    } else {
                        "Real-time two-way sync ready for Passwords ($passwordsCount)"
                    }
                Text(
                    text = statusText,
                    fontSize = 10.5.sp,
                    color = if (syncState.syncErrorMessage != null) DangerRed else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}


