package com.photerest.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSearchRulesTest {
    @Test fun widePhotosUseFullLine() {
        assertEquals(SearchResultStyle.WIDE, searchResultStyle(1.25f))
        assertEquals(SearchResultStyle.WIDE, searchResultStyle(4f / 3f))
        assertEquals(SearchResultStyle.WIDE, searchResultStyle(16f / 9f))
        assertEquals(SearchResultStyle.WIDE, searchResultStyle(3f))
    }

    @Test fun squareAndPortraitPhotosStayNarrow() {
        assertEquals(SearchResultStyle.NARROW, searchResultStyle(1.24f))
        assertEquals(SearchResultStyle.NARROW, searchResultStyle(1f))
        assertEquals(SearchResultStyle.NARROW, searchResultStyle(3f / 4f))
        assertEquals(SearchResultStyle.NARROW, searchResultStyle(9f / 16f))
    }

    @Test fun invalidAspectFallsBackToSquare() {
        assertEquals(SearchResultStyle.NARROW, searchResultStyle(Float.NaN))
        assertEquals(SearchResultStyle.NARROW, searchResultStyle(0f))
        assertEquals(SearchResultStyle.NARROW, searchResultStyle(-2f))
        assertEquals(1f, effectiveSearchAspect(Float.NaN), 0f)
        assertEquals(1f, effectiveSearchAspect(0f), 0f)
    }

    @Test fun displayAspectClampsExtremes() {
        assertEquals(0.8f, searchResultDisplayAspect(0.8f), 0.0001f)
        assertEquals(WIDE_RESULT_MAX_ASPECT, searchResultDisplayAspect(8f), 0.0001f)
        assertEquals(NARROW_RESULT_MIN_ASPECT, searchResultDisplayAspect(0.1f), 0.0001f)
        assertEquals(1f, searchResultDisplayAspect(1f), 0.0001f)
        assertTrue(searchResultDisplayAspect(3.5f) <= WIDE_RESULT_MAX_ASPECT)
    }
}
