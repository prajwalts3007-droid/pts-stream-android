package online.ptsmovies.stream

/** Effective width accounts for Android font scaling, not just screen pixels. */
internal fun stackActions(widthDp: Float, fontScale: Float) = widthDp / fontScale.coerceAtLeast(1f) < 280f
internal fun libraryColumns(widthDp: Float, fontScale: Float, list: Boolean): Int =
    if (list) 1 else (widthDp / (144f * fontScale.coerceIn(1f, 1.5f))).toInt().coerceIn(1, 6)
internal fun compactMiniActions(widthDp: Float, fontScale: Float) = widthDp / fontScale.coerceAtLeast(1f) < 380f
