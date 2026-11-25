package si.hafnar.entalt.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Automatic default for compact 1:1 square and 4:3 panels; explicit layout choices take precedence. */
fun defaultsToRoomDisplay(widthPx: Int, heightPx: Int): Boolean {
    val longer = max(widthPx, heightPx)
    val shorter = min(widthPx, heightPx)
    if (shorter <= 0 || longer > 1280 || shorter > 960) return false
    val ratio = longer.toDouble() / shorter
    return abs(ratio - 4.0 / 3.0) <= 0.08 || (longer <= 960 && abs(ratio - 1.0) <= 0.08)
}
