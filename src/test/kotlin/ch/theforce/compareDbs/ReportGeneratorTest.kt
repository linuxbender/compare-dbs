package ch.theforce.compareDbs

import org.bson.Document
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ReportGeneratorTest {

    private val emptyResult = ComparisonResult(
        correlationId = "test-correlation-id-1234",
        onlyInA = emptyList(),
        onlyInB = emptyList(),
        viewsOnlyInA = emptyList(),
        viewsOnlyInB = emptyList(),
        collections = emptyList()
    )

    private fun makeCollectionResult(
        name: String,
        schemaDiff: SchemaDiff = SchemaDiff(emptyMap(), emptyMap(), emptyMap()),
        indexDiff: IndexDiff = IndexDiff(emptyList(), emptyList(), emptyList())
    ) = CollectionResult(
        name = name,
        schemaDiff = schemaDiff,
        indexDiff = indexDiff,
        sampleSizeA = 50,
        sampleSizeB = 50,
        totalDocsA = 100,
        totalDocsB = 100
    )

    private fun makeIndexSpec(name: String, key: Map<String, Any>) = IndexSpec(
        name = name,
        key = key,
        indexType = classifyIndexType(key, null),
        rawOptions = Document()
    )

    // ── Text report ───────────────────────────────────────────────────────────

    @Test
    fun `text report contains the correlation ID`() {
        val report = generateTextReport(emptyResult, "mongodb://a/db", "mongodb://b/db")
        assertTrue(report.contains("test-correlation-id-1234"), "Report should contain correlation ID")
    }

    @Test
    fun `text report shows REMOVED label for fields only in A`() {
        val schemaDiff = SchemaDiff(
            fieldsOnlyInA = mapOf("legacyId" to setOf("string")),
            fieldsOnlyInB = emptyMap(),
            typeChanges = emptyMap()
        )
        val result = emptyResult.copy(collections = listOf(makeCollectionResult("users", schemaDiff)))

        val report = generateTextReport(result, "a", "b")

        assertTrue(report.contains("[REMOVED]"), "Report should contain [REMOVED] label")
        assertTrue(report.contains("legacyId"))
    }

    @Test
    fun `text report shows ADDED label for fields only in B`() {
        val schemaDiff = SchemaDiff(
            fieldsOnlyInA = emptyMap(),
            fieldsOnlyInB = mapOf("externalRef" to setOf("string")),
            typeChanges = emptyMap()
        )
        val result = emptyResult.copy(collections = listOf(makeCollectionResult("users", schemaDiff)))

        val report = generateTextReport(result, "a", "b")

        assertTrue(report.contains("[ADDED]"), "Report should contain [ADDED] label")
        assertTrue(report.contains("externalRef"))
    }

    @Test
    fun `text report shows CHANGED label for type changes`() {
        val schemaDiff = SchemaDiff(
            fieldsOnlyInA = emptyMap(),
            fieldsOnlyInB = emptyMap(),
            typeChanges = mapOf("amount" to Pair(setOf("double"), setOf("decimal128")))
        )
        val result = emptyResult.copy(collections = listOf(makeCollectionResult("orders", schemaDiff)))

        val report = generateTextReport(result, "a", "b")

        assertTrue(report.contains("[CHANGED]"), "Report should contain [CHANGED] label")
        assertTrue(report.contains("amount"))
        assertTrue(report.contains("double"))
        assertTrue(report.contains("decimal128"))
    }

    @Test
    fun `text report shows no differences message when schemas are identical`() {
        val report = generateTextReport(emptyResult, "a", "b")
        assertTrue(report.contains("No differences found") || report.contains("structurally identical"))
    }

    @Test
    fun `text report shows MISSING IN B label for indexes only in A`() {
        val idx = makeIndexSpec("email_1", mapOf("email" to 1))
        val indexDiff = IndexDiff(onlyInA = listOf(idx), onlyInB = emptyList(), optionChanges = emptyList())
        val result = emptyResult.copy(collections = listOf(makeCollectionResult("users", indexDiff = indexDiff)))

        val report = generateTextReport(result, "a", "b")

        assertTrue(report.contains("MISSING IN B"), "Report should show MISSING IN B for index absent in B")
    }

    @Test
    fun `text report shows ADDED IN B label for indexes only in B`() {
        val idx = makeIndexSpec("email_1", mapOf("email" to 1))
        val indexDiff = IndexDiff(onlyInA = emptyList(), onlyInB = listOf(idx), optionChanges = emptyList())
        val result = emptyResult.copy(collections = listOf(makeCollectionResult("users", indexDiff = indexDiff)))

        val report = generateTextReport(result, "a", "b")

        assertTrue(report.contains("ADDED IN B"), "Report should show ADDED IN B for new index in B")
    }

    // ── HTML report ───────────────────────────────────────────────────────────

    @Test
    fun `HTML report contains correlation ID`() {
        val report = generateHtmlReport(emptyResult, "mongodb://a/db", "mongodb://b/db")
        assertTrue(report.contains("test-correlation-id-1234"))
    }

    @Test
    fun `HTML report is self-contained with no external resource links`() {
        val report = generateHtmlReport(emptyResult, "a", "b")

        // No CDN or external href/src references
        assertFalse(report.contains("https://cdn"), "Report must not reference external CDN")
        assertFalse(report.contains("http://cdn"), "Report must not reference external CDN")
        assertFalse(report.contains("<link rel"), "Report must not load external stylesheets")
        assertFalse(report.contains("<script src"), "Report must not load external scripts")
    }

    @Test
    fun `HTML report starts with DOCTYPE and contains html tag`() {
        val report = generateHtmlReport(emptyResult, "a", "b")
        assertTrue(report.trimStart().startsWith("<!DOCTYPE html>"))
        assertTrue(report.contains("<html"))
        assertTrue(report.contains("</html>"))
    }

    @Test
    fun `password in URI is redacted in text report header`() {
        val uriA = redactPassword("mongodb://user:secret@host:27017/db")
        val report = generateTextReport(emptyResult, uriA, "mongodb://b/db")

        assertFalse(report.contains("secret"), "Password must be redacted in report")
        assertTrue(report.contains("user:***"), "Redacted placeholder must appear in report")
    }

    @Test
    fun `HTML report escapes special characters in field paths and collection names`() {
        val schemaDiff = SchemaDiff(
            fieldsOnlyInA = mapOf("price<tax>" to setOf("double")),
            fieldsOnlyInB = mapOf("amount&fee" to setOf("int32")),
            typeChanges = emptyMap()
        )
        val result = emptyResult.copy(collections = listOf(makeCollectionResult("orders", schemaDiff)))
        val report = generateHtmlReport(result, "mongodb://a/db", "mongodb://b/db")

        assertFalse(report.contains("price<tax>"), "Unescaped < and > must not appear in HTML")
        assertFalse(report.contains("amount&fee"), "Unescaped & must not appear in HTML")
        assertTrue(report.contains("price&lt;tax&gt;"), "< and > in field path must be escaped")
        assertTrue(report.contains("amount&amp;fee"), "& in field path must be escaped")
    }

    @Test
    fun `password in URI is redacted in HTML report header`() {
        val uriA = redactPassword("mongodb://admin:p@ssw0rd@host:27017/mydb")
        val report = generateHtmlReport(emptyResult, uriA, "mongodb://b/db")

        assertFalse(report.contains("p@ssw0rd"), "Password must be redacted in HTML report")
    }
}
