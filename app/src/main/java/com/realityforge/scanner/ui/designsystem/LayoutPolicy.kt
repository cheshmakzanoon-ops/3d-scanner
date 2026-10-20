package com.realityforge.scanner.ui.designsystem

/** Logical dp breakpoints; font scaling may only reduce the column count. */
object LayoutPolicy {
    fun columns(widthDp: Float, fontScale: Float): Int {
        require(widthDp.isFinite() && widthDp >= 0f)
        require(fontScale.isFinite() && fontScale > 0f)
        val effective = widthDp / fontScale.coerceAtLeast(1f)
        return when { effective >= 1200f -> 3; effective >= 720f -> 2; else -> 1 }
    }
    fun horizontalPadding(widthDp: Float): Float = if (widthDp >= 600f) 32f else 16f
    const val MinimumTargetDp = 48f
    const val MaximumContentWidthDp = 1200f
}

/** WCAG relative luminance and contrast, using packed RGB without alpha. */
object Contrast {
    fun ratio(foreground: Int, background: Int): Double {
        fun luminance(rgb: Int): Double {
            fun linear(value: Int): Double {
                val s = value / 255.0
                return if (s <= 0.04045) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
            }
            return .2126 * linear((rgb shr 16) and 255) + .7152 * linear((rgb shr 8) and 255) + .0722 * linear(rgb and 255)
        }
        val a = luminance(foreground); val b = luminance(background)
        return (maxOf(a, b) + .05) / (minOf(a, b) + .05)
    }
}
