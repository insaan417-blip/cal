package com.quickmath.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MathParserTest {

    private fun solve(text: String, ops: Set<Char> = MathParser.ALL_OPS): String? =
        MathParser.scan(listOf(OcrLine(text)), ops).detection?.answer

    @Test fun addition() = assertEquals("1443", solve("847 + 596"))
    @Test fun subtractionAscii() = assertEquals("447", solve("925 - 478"))
    @Test fun subtractionUnicodeMinus() = assertEquals("447", solve("925 \u2212 478"))
    @Test fun multiplicationSymbol() = assertEquals("864", solve("36 \u00D7 24"))
    @Test fun multiplicationLetterX() = assertEquals("864", solve("36 x 24"))
    @Test fun multiplicationCapitalXTight() = assertEquals("864", solve("36X24"))
    @Test fun multiplicationStar() = assertEquals("864", solve("36*24"))
    @Test fun divisionSymbol() = assertEquals("72", solve("864 \u00F7 12"))
    @Test fun divisionSlash() = assertEquals("72", solve("864 / 12"))
    @Test fun bigProduct() = assertEquals("504812", solve("847 \u00D7 596"))

    @Test fun decimals() = assertEquals("5.75", solve("3.5 + 2.25"))
    @Test fun decimalNoFloatError() = assertEquals("0.3", solve("0.1 + 0.2"))
    @Test fun leadingDotDecimal() = assertEquals("0.75", solve(".5 + .25"))
    @Test fun negativeFirst() = assertEquals("-2", solve("-5 + 3"))
    @Test fun doubleNegative() = assertEquals("8", solve("5 - -3"))
    @Test fun bracketedNegative() = assertEquals("-24", solve("(-4) \u00D7 6"))
    @Test fun precedence() = assertEquals("14", solve("2 + 3 \u00D7 4"))
    @Test fun brackets() = assertEquals("20", solve("(2 + 3) \u00D7 4"))
    @Test fun repeatingDivision() = assertEquals("3.333333", solve("10 \u00F7 3"))
    @Test fun exactDecimalDivision() = assertEquals("0.125", solve("1 \u00F7 8"))
    @Test fun divisionByZeroShowsNothing() = assertNull(solve("5 \u00F7 0"))
    @Test fun noOverflow() = assertEquals(
        "999999999999999998000000000000000001",
        solve("999999999999999999 \u00D7 999999999999999999")
    )
    @Test fun tooManyDigitsRejected() = assertNull(solve("1234567890123456789 + 1"))

    @Test fun westernThousands() = assertEquals("1235", solve("1,234 + 1"))
    @Test fun indianThousands() = assertEquals("100001", solve("1,00,000 + 1"))
    @Test fun trailingEqualsAndQuestionMark() = assertEquals("1443", solve("847 + 596 = ?"))
    @Test fun equalsWithShownAnswer() = assertEquals("1443", solve("847 + 596 = 1443"))
    @Test fun questionNumberPrefix() = assertEquals("1443", solve("12) 847 + 596"))
    @Test fun labelPrefix() = assertEquals("1443", solve("Solve: 847 + 596"))

    @Test fun dateRejected() = assertNull(solve("2026-10-31"))
    @Test fun slashDateRejected() = assertNull(solve("31/10/2026"))
    @Test fun timeRejected() = assertNull(solve("10:30 + 5"))
    @Test fun lettersGluedRejected() = assertNull(solve("A5 + 3"))
    @Test fun tightMinusRejected() = assertNull(solve("925-478"))
    @Test fun tightSlashRejected() = assertNull(solve("864/12"))
    @Test fun splitNumberRejected() = assertNull(solve("5 + 3 4"))
    @Test fun decimalCommaRejected() = assertNull(solve("10,5 + 3"))
    @Test fun singleNumberRejected() = assertNull(solve("-45"))
    @Test fun plainTextRejected() = assertNull(solve("Hello world, 3 new messages"))

    @Test fun disabledOperationIgnored() =
        assertNull(solve("36 \u00D7 24", setOf('+', '-', '/')))

    @Test fun largestFontWins() {
        val lines = listOf(OcrLine("2 + 2", 10, 30), OcrLine("847 + 596", 300, 380))
        assertEquals("1443", MathParser.scan(lines).detection?.answer)
    }

    @Test fun stackedLayout() {
        val lines = listOf(OcrLine("847", 300, 340), OcrLine("+ 596", 350, 390))
        assertEquals("1443", MathParser.scan(lines).detection?.answer)
    }

    @Test fun differentSizedLinesNotJoined() {
        val lines = listOf(OcrLine("Score 10", 10, 20), OcrLine("- 5 lives left", 300, 380))
        assertNull(MathParser.scan(lines).detection)
    }

    @Test fun expressionTextIsCanonical() {
        val d = MathParser.scan(listOf(OcrLine("36 x 24"))).detection
        assertEquals("36 \u00D7 24", d?.expression)
    }
}
