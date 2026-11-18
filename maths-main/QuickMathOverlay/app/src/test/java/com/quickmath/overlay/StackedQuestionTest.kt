package com.quickmath.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Vertical questions: numbers in a column, the sign at the start of each following line. */
class StackedQuestionTest {

    /** Right-aligned column, 30 px tall lines, 60 px apart (like the quiz app on screen). */
    private fun stack(vararg t: String): List<OcrLine> =
        t.mapIndexed { i, s -> OcrLine(s, 200 + i * 60, 230 + i * 60, 330, 430) }

    private fun solve(lines: List<OcrLine>): String? = MathParser.scan(lines).detection?.answer

    @Test fun divisionOnTwoLines() = assertEquals("8", solve(stack("112", "\u00F7 14")))
    @Test fun multiplicationOnTwoLines() = assertEquals("616", solve(stack("88", "\u00D7 7")))
    @Test fun threeNumbers() = assertEquals("21", solve(stack("23", "+ 44", "- 46")))
    @Test fun threeNumbersMinusFirst() = assertEquals("79", solve(stack("46", "- 21", "+ 54")))
    @Test fun spaceAfterMinusLostByOcr() = assertEquals("21", solve(stack("23", "+44", "-46")))

    @Test fun eightNumbers() =
        assertEquals("47", solve(stack("10", "+ 20", "- 5", "+ 7", "- 3", "+ 11", "- 2", "+ 9")))

    @Test fun fifteenNumbers() = assertEquals(
        "105",
        solve(stack("50", "+ 12", "- 7", "+ 30", "- 4", "+ 9", "- 21", "+ 8", "- 3", "+ 15", "- 6", "+ 2", "- 11", "+ 40", "- 9"))
    )

    @Test fun twentyNumbers() = assertEquals(
        "0",
        solve(stack("1", "+ 2", "+ 3", "+ 4", "+ 5", "+ 6", "+ 7", "+ 8", "+ 9", "+ 10",
            "- 1", "- 2", "- 3", "- 4", "- 5", "- 6", "- 7", "- 8", "- 9", "- 10"))
    )

    @Test fun decimalsInAStack() = assertEquals("3.75", solve(stack("2.5", "+ 1.25")))
    @Test fun precedenceInAStack() = assertEquals("14", solve(stack("2", "+ 3", "\u00D7 4")))
    @Test fun negativeResult() = assertEquals("-4", solve(stack("5", "- 9")))
    @Test fun divisionByZeroShowsNothing() = assertNull(solve(stack("10", "\u00F7 0")))

    @Test fun answerBubbleOnTheLeftIsNotPartOfTheQuestion() {
        val lines = listOf(
            OcrLine("126", 150, 185, 40, 165),   // the floating bubble showing the previous answer
            OcrLine("112", 200, 230, 345, 430),
            OcrLine("\u00F7 14", 260, 290, 335, 430)
        )
        assertEquals("8", MathParser.scan(lines).detection?.answer)
    }

    @Test fun stackWithoutPositionInfoStillWorks() {
        val lines = listOf(OcrLine("23", 200, 230), OcrLine("+ 44", 260, 290), OcrLine("- 46", 320, 350))
        assertEquals("21", MathParser.scan(lines).detection?.answer)
    }

    @Test fun disabledOperationInAStackIgnored() =
        assertNull(MathParser.scan(stack("8", "\u00D7 7"), setOf('+', '-', '/')).detection)

    @Test fun unrelatedNumbersFarApartAreNotJoined() {
        val lines = listOf(OcrLine("23", 100, 130, 330, 430), OcrLine("+ 44", 600, 630, 330, 430))
        assertNull(MathParser.scan(lines).detection)
    }

    @Test fun tightMinusInsideOneLineStillRejected() = assertNull(solve(listOf(OcrLine("925-478"))))
    @Test fun dateStillRejected() = assertNull(solve(listOf(OcrLine("2026-10-31"))))
}
