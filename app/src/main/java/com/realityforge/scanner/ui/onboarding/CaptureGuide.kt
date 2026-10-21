package com.realityforge.scanner.ui.onboarding

/** Content IDs are stable across locale changes and persisted onboarding versions. */
object CaptureGuide {
    const val VERSION = 1
    const val INNER_COLUMNS = 9
    const val INNER_ROWS = 6
    const val BOARD_ASSET = "onboarding/checkerboard-9x6.svg"
    val steps = listOf("subject", "overlap", "optics", "review", "calibration", "limitations", "privacy")
    fun clampPage(page: Int) = page.coerceIn(0,steps.lastIndex)
    fun boardIsBlack(column: Int,row: Int): Boolean {
        require(column in 0..INNER_COLUMNS && row in 0..INNER_ROWS)
        return (column+row)%2==0
    }
}
