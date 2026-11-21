package com.photerest.app

/** Result tile sizing rules for the local search grid, kept pure so they are easy to test. */
internal enum class SearchResultStyle { WIDE, NARROW }

internal const val WIDE_RESULT_ASPECT = 1.25f
internal const val WIDE_RESULT_MAX_ASPECT = 2.4f
internal const val NARROW_RESULT_MIN_ASPECT = 0.55f

internal fun effectiveSearchAspect(aspect: Float): Float =
    if (aspect.isFinite() && aspect > 0f) aspect else 1f

/**
 * Wide/panoramic photos span the full staggered-grid line (Option A/B-style banners);
 * square and portrait photos stay in one lane (Option C-style small tiles).
 */
internal fun searchResultStyle(aspect: Float): SearchResultStyle =
    if (effectiveSearchAspect(aspect) >= WIDE_RESULT_ASPECT) SearchResultStyle.WIDE else SearchResultStyle.NARROW

/** Display ratio, clamped so extremely wide/tall photos cannot explode the grid row height. */
internal fun searchResultDisplayAspect(aspect: Float): Float {
    val safe = effectiveSearchAspect(aspect)
    return if (searchResultStyle(safe) == SearchResultStyle.WIDE) {
        safe.coerceIn(WIDE_RESULT_ASPECT, WIDE_RESULT_MAX_ASPECT)
    } else {
        safe.coerceIn(NARROW_RESULT_MIN_ASPECT, WIDE_RESULT_ASPECT)
    }
}
