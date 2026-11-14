package com.photerest.app

import org.junit.Assert.*
import org.junit.Test

class VideoPreviewRulesTest {
    private fun frame(top: Float, bottom: Float, left: Float = 0f, right: Float = 400f) =
        PreviewRect(left, top, right, bottom)

    private val viewport = PreviewRect(0f, 0f, 400f, 1000f)

    @Test fun fullyVisibleCardCanPreview() {
        assertEquals(0, VideoPreviewRules.choose(listOf(frame(100f, 500f)), viewport))
    }

    @Test fun atLeastSixtyPercentVisibleIsAccepted() {
        // 1000 px tall card, 601 px (60.1%) inside the viewport. The 60% cut-off itself is float-based
        // inside the rule, so the test documents the accepted side of the threshold.
        assertEquals(0, VideoPreviewRules.choose(listOf(frame(-399f, 601f)), viewport))
    }

    @Test fun lessThanSixtyPercentIsRejected() {
        assertNull(VideoPreviewRules.choose(listOf(frame(-401f, 599f)), viewport))
        assertNull(VideoPreviewRules.choose(listOf(frame(-500f, 500f)), viewport))
        assertNull(VideoPreviewRules.choose(listOf(frame(800f, 1800f)), viewport))
    }

    @Test fun closestCentreWins() {
        val frames = listOf(frame(0f, 400f), frame(300f, 700f), frame(600f, 1000f))
        assertEquals(1, VideoPreviewRules.choose(frames, viewport))
    }

    @Test fun onlyFullyVisibleCandidatesCompeteForTheCentre() {
        // The centre-nearest card is only 40% visible, so the fully visible one wins.
        val frames = listOf(frame(-300f, 100f), frame(700f, 1100f))
        assertEquals(1, VideoPreviewRules.choose(frames, viewport))
    }

    @Test fun emptyOrDegenerateInputsHaveNoSelection() {
        assertNull(VideoPreviewRules.choose(emptyList(), viewport))
        assertNull(VideoPreviewRules.choose(listOf(frame(100f, 500f)), PreviewRect(0f, 0f, 0f, 0f)))
        assertNull(VideoPreviewRules.choose(listOf(frame(100f, 100f)), viewport))
        assertNull(VideoPreviewRules.choose(listOf(frame(2000f, 2400f)), viewport))
    }

    @Test fun videoTimeFormatsMinutesAndHours() {
        assertEquals("0:00", VideoPreviewRules.videoTime(0))
        assertEquals("0:59", VideoPreviewRules.videoTime(59_000))
        assertEquals("1:01", VideoPreviewRules.videoTime(61_000))
        assertEquals("10:00", VideoPreviewRules.videoTime(600_000))
        assertEquals("1:00:00", VideoPreviewRules.videoTime(3_600_000))
        assertEquals("1:01:01", VideoPreviewRules.videoTime(3_661_000))
        assertEquals("0:00", VideoPreviewRules.videoTime(-5_000))
    }
}
