package com.quickmath.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ML Kit often reads the division sign as "+" (or drops it). OpVision looks at the pixels
 * instead. The glyphs here are drawn synthetically: solid blocks for digits, and the
 * real shapes of the signs.
 */
class OperatorVisionTest {

    private val w = 260
    private val h = 60
    private val bg = 0xFF181818.toInt()
    private val fg = 0xFFFFFFFF.toInt()

    private class Canvas(val w: Int, val h: Int, bg: Int) {
        val px = IntArray(w * h) { bg }
        fun rect(x0: Int, y0: Int, x1: Int, y1: Int, c: Int) {
            for (y in y0..y1) for (x in x0..x1) px[y * w + x] = c
        }
    }

    private fun digits(c: Canvas, x: Int, count: Int, color: Int): Int {
        var cx = x
        repeat(count) { c.rect(cx, 15, cx + 22, 44, color); cx += 31 }
        return cx - 8
    }

    /** sign centred on y = 30, about 19 px wide; returns the x after it */
    private fun sign(c: Canvas, x: Int, kind: Char, color: Int): Int {
        when (kind) {
            '/' -> { c.rect(x, 29, x + 18, 31, color); c.rect(x + 7, 21, x + 11, 25, color); c.rect(x + 7, 35, x + 11, 39, color) }
            '+' -> { c.rect(x, 29, x + 18, 31, color); c.rect(x + 8, 21, x + 10, 39, color) }
            '-' -> c.rect(x + 3, 29, x + 14, 31, color)
            '*' -> for (i in 0..14) { c.rect(x + i, 22 + i, x + i + 1, 23 + i, color); c.rect(x + 14 - i, 22 + i, x + 15 - i, 23 + i, color) }
        }
        return x + 19
    }

    private fun read(sign: Char, ocr: String): String? {
        val c = Canvas(w, h, bg)
        val sx = 60
        val after = sign(c, sx, sign, fg)
        val end = digits(c, after + 18, 2, fg)
        return OpVision.rebuild(c.px, w, h, sx, 15, end, 44, ocr, 0)
    }

    @Test fun divisionReadAsPlusIsCorrected() = assertEquals("/ 14", read('/', "+ 14"))
    @Test fun divisionReadAsMinusIsCorrected() = assertEquals("/ 14", read('/', "- 14"))
    @Test fun divisionSignDroppedByOcrIsRestored() = assertEquals("/ 14", read('/', "14"))
    @Test fun divisionGluedToNumber() = assertEquals("/ 14", read('/', "+14"))
    @Test fun plusStaysPlus() = assertEquals("+ 14", read('+', "+ 14"))
    @Test fun plusReadAsDivision() = assertEquals("+ 14", read('+', "\u00F7 14"))
    @Test fun timesReadAsPlus() = assertEquals("* 14", read('*', "+ 14"))
    @Test fun minusSignDropped() = assertEquals("- 14", read('-', "14"))
    @Test fun minusReadAsPlus() = assertEquals("- 14", read('-', "+ 14"))

    @Test fun plainNumberIsLeftAlone() {
        val c = Canvas(w, h, bg)
        val end = digits(c, 60, 3, fg)
        assertEquals("112", OpVision.rebuild(c.px, w, h, 60, 15, end, 44, "112", 0))
    }

    @Test fun darkTextOnLightBackgroundWorksToo() {
        val c = Canvas(w, h, 0xFFF0F0F0.toInt())
        val after = sign(c, 60, '/', 0xFF202020.toInt())
        val end = digits(c, after + 18, 2, 0xFF202020.toInt())
        assertEquals("/ 14", OpVision.rebuild(c.px, w, h, 60, 15, end, 44, "+ 14", 0))
    }
}
