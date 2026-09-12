package com.clipmind.android.reading

import com.clipmind.android.network.dto.*
import org.junit.Assert.*
import org.junit.Test

class ReadingContractTest {
    private fun valid() = ClientAnalysis("ark","model","认知",AnalysisInterpretation("summary","application","reflection"),emptyList(),2,emptyList(),listOf("a","b","c"),value="high",questions=listOf("What applies?","What is a counterexample?"))
    @Test fun requiresBoundedQuestionsAndVerifiedBookIdentity() {
        assertTrue(ReadingContract.valid(valid()))
        assertFalse(ReadingContract.valid(valid().copy(questions=emptyList())))
        assertFalse(ReadingContract.valid(valid().copy(primaryTag="invented")))
        assertFalse(ReadingContract.valid(valid().copy(books=listOf(AnalysisBook("fake","author")))))
        assertFalse(ReadingContract.valid(valid().copy(books=listOf(AnalysisBook("book","author",true,"/works/OL1W","direct")))))
    }
    @Test fun vectorsRequireFiniteNonzeroAndMatchingDimensions() {
        val a = listOf(1.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0)
        val b = listOf(0.0,1.0,0.0,0.0,0.0,0.0,0.0,0.0)
        assertEquals(1.0,VectorMath.cosine(a,a),1e-8)
        assertEquals(0.0,VectorMath.cosine(a,b),1e-8)
        assertEquals(-1.0,VectorMath.cosine(a,a.map { -it }),1e-8)
        assertFalse(VectorMath.valid(List(8) { 0.0 }))
        assertFalse(VectorMath.valid(a.toMutableList().also { it[0]=Double.NaN }))
        assertTrue(runCatching { VectorMath.cosine(a,a+0.0) }.isFailure)
    }
    @Test fun enhancedArticlesRequireCompleteContextAndCurrentSourceEvidence() {
        val source = "Practice requires feedback."
        val article = ReadingArticle("Feedback", "https://sspai.com/post/1", "Summary", "2026-09-13T00:00:00Z",
            "extends", "Explains how feedback improves practice.", "Deliberate practice requires targeted feedback.", source)
        assertTrue(ReadingContract.valid(valid().copy(articles = listOf(article)), source))
        for (bad in listOf(article.copy(sourceQuote = "Invented source statement."), article.copy(sourceQuote = "Practice"),
            article.copy(quote = "fragment"), article.copy(reason = null), article.copy(relation = "same_topic"))) {
            assertFalse(ReadingContract.valid(valid().copy(articles = listOf(bad)), source))
        }
        assertFalse(ReadingContract.valid(valid().copy(articles = listOf(article, article)), source))
        val old = com.google.gson.Gson().fromJson("""{"title":"Old","url":"https://sspai.com/post/2","summary":"Summary","checked_at":"2026-09-08T00:00:00Z"}""", ReadingArticle::class.java)
        assertTrue(ReadingContract.valid(valid().copy(articles = listOf(old)), source))
    }
    @Test fun rejectsDuplicateKeywordsAndUntrustedArticleLinks() {
        assertFalse(ReadingContract.valid(valid().copy(keywords=listOf("a", "A", "b"))))
        for (url in listOf("https://sspai.com.attacker.invalid/a", "https://user@sspai.com/a", "https://sspai.com:8443/a", "http://sspai.com/a")) {
            assertFalse(ReadingContract.valid(valid().copy(articles=listOf(ReadingArticle("title",url,"summary","2026-09-08T00:00:00Z")))))
        }
        assertTrue(ReadingContract.valid(valid().copy(articles=listOf(ReadingArticle("title","https://sspai.com/post/1","summary","2026-09-08T00:00:00Z")))))
    }
}
