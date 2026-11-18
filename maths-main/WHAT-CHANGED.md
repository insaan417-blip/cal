# What changed in this version

Files changed (all in QuickMathOverlay/app/src/main/java/com/quickmath/overlay/):
- MathParser.kt        rewritten as a thin wrapper (OcrLine now also stores left/right position)
- MathParserCore.java  NEW - the parser: stacked questions up to 30 lines, mixed + and -, fewer "unclear" skips
- OpVision.java        NEW - reads + - x ÷ from the screenshot pixels (OCR often turns ÷ into +)
- Fix.java             NEW - glue: ML Kit result + screenshot -> corrected lines
- CaptureService.kt    processOnce() now calls Fix.buildLines(text, bitmap) before recycling the bitmap

Tests added (app/src/test/...): StackedQuestionTest.kt, OperatorVisionTest.kt.
All 44 existing parser tests still pass.

Also fixed: .github/workflows/main.yml ran `gradle` in the repo root, but the Gradle project is in
QuickMathOverlay/, so the build could not start. It now finds the project folder by itself.

The APK is signed with the same keystore as before, so it installs over your current app.
