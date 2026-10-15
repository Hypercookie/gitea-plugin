package com.github.hypercookie.giteaplugin.pullrequest.review

import com.intellij.openapi.util.text.StringUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** [LineMapping.compare] needs the platform's `ComparisonManager`, hence a
 * platform test. Documents always use `\n`; file texts from the API may not. */
class LineMappingLineSeparatorTest : BasePlatformTestCase() {

    private val crlf = "first\r\nsecond\r\nthird\r\n"
    private val document = "first\nsecond\nthird\n"

    fun testRawCrlfTextDiffersOnEveryLine() {
        val blocks = LineMapping.compare(crlf, document)
        assertEquals(listOf(ChangedBlock(0, 3, 0, 3)), blocks)
    }

    fun testNormalizedCrlfTextMatchesDocument() {
        assertEmpty(LineMapping.compare(StringUtil.convertLineSeparators(crlf), document))
    }

    fun testNormalizedCrlfTextKeepsRealChanges() {
        val blocks = LineMapping.compare(StringUtil.convertLineSeparators(crlf), "first\nchanged\nthird\n")
        assertEquals(listOf(ChangedBlock(1, 2, 1, 2)), blocks)
    }
}
