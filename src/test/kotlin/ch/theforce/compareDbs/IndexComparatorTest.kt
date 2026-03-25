package ch.theforce.compareDbs

import org.bson.Document
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class IndexComparatorTest {

    private fun makeIndex(
        name: String,
        key: Map<String, Any>,
        unique: Boolean = false,
        sparse: Boolean = false,
        expireAfterSeconds: Int? = null,
        partialFilter: Document? = null
    ): IndexSpec {
        val expireAfterSecondsInt: Int? = expireAfterSeconds
        return IndexSpec(
            name = name,
            key = key,
            indexType = classifyIndexType(key, expireAfterSecondsInt),
            unique = unique,
            sparse = sparse,
            expireAfterSeconds = expireAfterSecondsInt,
            partialFilter = partialFilter,
            rawOptions = Document()
        )
    }

    // ── classifyIndexType ─────────────────────────────────────────────────────

    @Test
    fun `single ascending field is classified as SINGLE`() {
        val type = classifyIndexType(mapOf("email" to 1), null)
        assertEquals(IndexType.SINGLE, type)
    }

    @Test
    fun `single descending field is classified as SINGLE`() {
        val type = classifyIndexType(mapOf("createdAt" to -1), null)
        assertEquals(IndexType.SINGLE, type)
    }

    @Test
    fun `two fields are classified as COMPOUND`() {
        val type = classifyIndexType(mapOf("email" to 1, "tenantId" to 1), null)
        assertEquals(IndexType.COMPOUND, type)
    }

    @Test
    fun `field with value text is classified as TEXT`() {
        val type = classifyIndexType(mapOf("description" to "text"), null)
        assertEquals(IndexType.TEXT, type)
    }

    @Test
    fun `single field with expireAfterSeconds is classified as TTL`() {
        val type = classifyIndexType(mapOf("createdAt" to 1), 3600)
        assertEquals(IndexType.TTL, type)
    }

    @Test
    fun `field with value hashed is classified as HASHED`() {
        val type = classifyIndexType(mapOf("userId" to "hashed"), null)
        assertEquals(IndexType.HASHED, type)
    }

    @Test
    fun `field with value 2dsphere is classified as GEOSPATIAL_2DSPHERE`() {
        val type = classifyIndexType(mapOf("location" to "2dsphere"), null)
        assertEquals(IndexType.GEOSPATIAL_2DSPHERE, type)
    }

    @Test
    fun `field with value 2d is classified as GEOSPATIAL_2D`() {
        val type = classifyIndexType(mapOf("loc" to "2d"), null)
        assertEquals(IndexType.GEOSPATIAL_2D, type)
    }

    @Test
    fun `wildcard key name is classified as WILDCARD`() {
        val type = classifyIndexType(mapOf("\$**" to 1), null)
        assertEquals(IndexType.WILDCARD, type)
    }

    // ── compareIndexes ────────────────────────────────────────────────────────

    @Test
    fun `index present in A but not B is reported as missing in B`() {
        val indexA = makeIndex("email_1", mapOf("email" to 1))
        val diff = compareIndexes(listOf(indexA), emptyList())

        assertEquals(1, diff.onlyInA.size)
        assertEquals("email_1", diff.onlyInA[0].name)
        assertTrue(diff.onlyInB.isEmpty())
    }

    @Test
    fun `index present in B but not A is reported as added in B`() {
        val indexB = makeIndex("email_1", mapOf("email" to 1))
        val diff = compareIndexes(emptyList(), listOf(indexB))

        assertEquals(1, diff.onlyInB.size)
        assertEquals("email_1", diff.onlyInB[0].name)
        assertTrue(diff.onlyInA.isEmpty())
    }

    @Test
    fun `same index in both databases produces no diff`() {
        val index = makeIndex("email_1", mapOf("email" to 1), unique = true)
        val diff = compareIndexes(listOf(index), listOf(index))

        assertTrue(diff.isEmpty)
    }

    @Test
    fun `same key but unique option changed is reported as option diff`() {
        val indexA = makeIndex("email_1", mapOf("email" to 1), unique = false)
        val indexB = makeIndex("email_1", mapOf("email" to 1), unique = true)

        val diff = compareIndexes(listOf(indexA), listOf(indexB))

        assertEquals(1, diff.optionChanges.size)
        assertFalse(diff.optionChanges[0].first.unique)
        assertTrue(diff.optionChanges[0].second.unique)
    }

    @Test
    fun `same key but expireAfterSeconds changed is reported as option diff`() {
        val indexA = makeIndex("ts_1", mapOf("createdAt" to 1), expireAfterSeconds = 3600)
        val indexB = makeIndex("ts_1", mapOf("createdAt" to 1), expireAfterSeconds = 7200)

        val diff = compareIndexes(listOf(indexA), listOf(indexB))

        assertEquals(1, diff.optionChanges.size)
        assertEquals(3600, diff.optionChanges[0].first.expireAfterSeconds)
        assertEquals(7200, diff.optionChanges[0].second.expireAfterSeconds)
    }

    @Test
    fun `index identity is based on key, not index name`() {
        // Same key, different name — should be treated as the same index
        val indexA = makeIndex("old_name", mapOf("email" to 1))
        val indexB = makeIndex("new_name", mapOf("email" to 1))

        val diff = compareIndexes(listOf(indexA), listOf(indexB))

        // Names differ but since options are equal the diff should be empty
        assertTrue(diff.isEmpty)
    }

    @Test
    fun `empty index lists produce empty diff`() {
        val diff = compareIndexes(emptyList(), emptyList())
        assertTrue(diff.isEmpty)
    }

    @Test
    fun `same partial filter with different field insertion order produces no diff`() {
        val filterA = Document("status", "active").append("age", Document("\$gt", 18))
        val filterB = Document("age", Document("\$gt", 18)).append("status", "active")
        val indexA = makeIndex("partial_idx", mapOf("email" to 1), partialFilter = filterA)
        val indexB = makeIndex("partial_idx", mapOf("email" to 1), partialFilter = filterB)

        val diff = compareIndexes(listOf(indexA), listOf(indexB))

        assertTrue(diff.isEmpty, "Same partial filter content must not produce a diff regardless of field order")
    }
}
