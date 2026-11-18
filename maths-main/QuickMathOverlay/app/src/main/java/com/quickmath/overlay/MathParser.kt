package com.quickmath.overlay

import java.math.BigDecimal

/**
 * One line of OCR text plus where it sits on screen (pixels).
 * [left]/[right] are -1 when unknown; they are used to tell a stacked question
 * (numbers lined up in a column) from unrelated text elsewhere on the screen.
 */
data class OcrLine(
    val text: String,
    val top: Int = 0,
    val bottom: Int = 40,
    val left: Int = -1,
    val right: Int = -1
) {
    val height: Int get() = bottom - top
}

data class Detection(val expression: String, val answer: String)

/** [rejected] = how many math-looking runs were dropped because they were unclear or unsafe. */
data class ScanResult(val detection: Detection?, val rejected: Int)

/**
 * Arithmetic finder + evaluator. No Android dependencies (the logic lives in
 * MathParserCore.java so it can be unit-tested on a normal JVM).
 *
 * Design rule: when in doubt, show nothing. Dates, phone numbers, "5 + 3 4", tight "-" or "/",
 * divide by zero, letters glued to numbers, ... are rejected instead of guessed.
 *
 * Handles inline expressions ("12 + 5 - 3 = ?") and stacked questions of up to 30 lines:
 *     23
 *   + 44
 *   - 46
 */
object MathParser {

    val ALL_OPS: Set<Char> = setOf('+', '-', '*', '/')

    fun scan(lines: List<OcrLine>, allowed: Set<Char> = ALL_OPS): ScanResult =
        MathParserCore.INSTANCE.scan(lines, allowed)

    /** Plain digits, no exponent, at most 6 decimals, no trailing zeros. */
    fun format(v: BigDecimal): String = MathParserCore.INSTANCE.format(v)
}
