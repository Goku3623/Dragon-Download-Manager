package com.example

import com.example.data.browser.DesktopViewportController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DesktopViewportControllerTest {

    @Test
    fun portraitPhonePlanFitsEdgeToEdgeWithZeroOverflow() {
        val plan = DesktopViewportController.computeViewportPlan(viewDipWidth = 393, viewDipHeight = 760)
        assertFalse(plan.isLandscape)
        assertEquals(1024, plan.baseDesktopWidth)
        assertEquals(1440, plan.maxAutoDesktopWidth)
        // Rendered width in DIPs must never exceed physical WebView DIP width
        val renderedDipWidth = plan.baseDesktopWidth * plan.initialScale
        assertTrue("Expected rendered width <= 393dp, got $renderedDipWidth", renderedDipWidth <= 393.0)
        assertTrue("Expected rendered width > 392dp for crisp fit, got $renderedDipWidth", renderedDipWidth > 392.0)
    }

    @Test
    fun compactPortraitPhonePlanFitsEdgeToEdge() {
        val plan = DesktopViewportController.computeViewportPlan(viewDipWidth = 360, viewDipHeight = 680)
        assertFalse(plan.isLandscape)
        assertEquals(1024, plan.baseDesktopWidth)
        assertTrue(plan.baseDesktopWidth * plan.initialScale <= 360.0)
        assertTrue(plan.initialScale in 0.35..0.36)
    }

    @Test
    fun landscapePhonePlanUsesWideDesktopWidthAndFitsEdgeToEdge() {
        val plan = DesktopViewportController.computeViewportPlan(viewDipWidth = 844, viewDipHeight = 360)
        assertTrue(plan.isLandscape)
        assertEquals(1280, plan.baseDesktopWidth)
        assertEquals(1520, plan.maxAutoDesktopWidth)
        val renderedDipWidth = plan.baseDesktopWidth * plan.initialScale
        assertTrue("Expected rendered width <= 844dp, got $renderedDipWidth", renderedDipWidth <= 844.0)
        assertTrue("Expected rendered width > 843dp for full landscape fit, got $renderedDipWidth", renderedDipWidth > 843.0)
    }

    @Test
    fun largeLandscapeTabletUsesNativeOneToOneScale() {
        val plan = DesktopViewportController.computeViewportPlan(viewDipWidth = 1366, viewDipHeight = 900)
        assertTrue(plan.isLandscape)
        assertEquals(1366, plan.baseDesktopWidth)
        assertEquals(1.0, plan.initialScale, 0.0001)
    }

    @Test
    fun rewriteUrlUpgradesKnownMobileHostsWhenEnablingDesktopMode() {
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            DesktopViewportController.rewriteUrlForDesktopToggle(
                "https://m.youtube.com/watch?v=dQw4w9WgXcQ&app=m",
                enablingDesktop = true
            )
        )
        assertEquals(
            "https://en.wikipedia.org/wiki/Android",
            DesktopViewportController.rewriteUrlForDesktopToggle(
                "https://en.m.wikipedia.org/wiki/Android",
                enablingDesktop = true
            )
        )
        assertEquals(
            "https://x.com/home",
            DesktopViewportController.rewriteUrlForDesktopToggle(
                "https://mobile.twitter.com/home",
                enablingDesktop = true
            )
        )
        assertNull(
            DesktopViewportController.rewriteUrlForDesktopToggle(
                "https://www.youtube.com/watch?v=abc",
                enablingDesktop = true
            )
        )
        assertNull(
            DesktopViewportController.rewriteUrlForDesktopToggle(
                "https://m.youtube.com/watch?v=abc",
                enablingDesktop = false
            )
        )
    }

    @Test
    fun syncInvocationScriptIncludesViewportAndPipLockHooks() {
        val script = DesktopViewportController.buildSyncInvocationScript(
            desktopMode = true,
            viewDipWidth = 393,
            viewDipHeight = 760,
            forceRefit = true,
            measureOverflow = true
        )
        assertTrue(script.contains("window.__dragonSyncDesktopViewport(true, 393, 760, true, true)"))
        assertTrue(script.contains("__dragonSetPipViewportLock"))
        assertTrue(script.contains("__dragonDesktopViewportState"))
    }
}
