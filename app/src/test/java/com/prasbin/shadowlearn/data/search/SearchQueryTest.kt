package com.prasbin.shadowlearn.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Query sanitization: user input must never reach MATCH syntax raw. */
class SearchQueryTest {

    @Test
    fun emptyQueryProducesNoTerms() {
        assertTrue(SearchQuery.parse("").isBlank)
        assertTrue(SearchQuery.parse("   ").isBlank)
        assertTrue(SearchQuery.parse("\t\n").isBlank)
    }

    @Test
    fun punctuationOnlyQueryProducesNoTerms() {
        assertTrue(SearchQuery.parse("!!!").isBlank)
        assertTrue(SearchQuery.parse("...---:::()").isBlank)
    }

    @Test
    fun specialCharactersAreStripped() {
        // These are MATCH-syntax metacharacters; none may reach the expression.
        val q = SearchQuery.parse("\"neural..network\" (gradient*) OR -optimization")
        assertEquals(listOf("neural", "network", "gradient", "or", "optimization"), q.terms)
        assertFalse(q.toMatchExpression().contains("\""))
        assertFalse(q.toMatchExpression().contains("("))
        assertFalse(q.toMatchExpression().contains(")"))
        assertFalse(q.toMatchExpression().contains("|"))
    }

    @Test
    fun singleTermParses() {
        val q = SearchQuery.parse("  Neural  ")
        assertEquals(listOf("neural"), q.terms)
        assertEquals("neural*", q.toMatchExpression())
    }

    @Test
    fun multipleWordsParseAsAndExpression() {
        val q = SearchQuery.parse("gradient descent")
        assertEquals(listOf("gradient", "descent"), q.terms)
        // Space-separated prefixes = FTS4 implicit AND — the one form the
        // Android framework SQLite honors (explicit `AND` is not an operator
        // there). FTS5 parses the same space form as AND too.
        assertEquals("gradient* descent*", q.toMatchExpression())
    }

    @Test
    fun digitsAndMixedTokens() {
        val q = SearchQuery.parse("CS201 lecture1 2024")
        assertEquals(listOf("cs201", "lecture1", "2024"), q.terms)
    }

    @Test
    fun unicodeLettersAreKept() {
        val q = SearchQuery.parse("Übung machine")
        assertEquals(listOf("übung", "machine"), q.terms)
    }

    @Test
    fun duplicatesAreDeduplicatedPreservingOrder() {
        val q = SearchQuery.parse("network network neural network neural")
        assertEquals(listOf("network", "neural"), q.terms)
    }

    @Test
    fun termCountIsCapped() {
        val q = SearchQuery.parse("a b c d e f g h i j k")
        assertEquals(SearchQuery.MAX_TERMS, q.terms.size)
    }

    @Test
    fun overlongTokenIsTruncated() {
        val q = SearchQuery.parse("a".repeat(500) + " neural")
        assertTrue(q.terms[0].length <= SearchQuery.MAX_TERM_LENGTH)
        assertTrue(q.terms.first().startsWith("a"))
    }

    @Test
    fun mixedBlankTokensLeadToBlankQuery() {
        assertTrue(SearchQuery.parse("---  /// \u0000").isBlank)
    }
}