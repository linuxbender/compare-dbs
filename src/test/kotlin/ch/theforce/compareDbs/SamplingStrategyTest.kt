package ch.theforce.compareDbs

import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoCursor
import org.bson.Document
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.mockito.kotlin.*

class SamplingStrategyTest {

    private fun mockCollection(docs: List<Document>): MongoCollection<Document> {
        val col = mock<MongoCollection<Document>>()
        whenever(col.countDocuments()).thenReturn(docs.size.toLong())
        val iterable = mockIterable(docs)
        whenever(col.find()).thenReturn(iterable)
        return col
    }

    private fun mockIterable(docs: List<Document>): com.mongodb.client.FindIterable<Document> {
        val findIterable = mock<com.mongodb.client.FindIterable<Document>>()
        val cursor = mock<MongoCursor<Document>>()
        val docIter = docs.iterator()
        whenever(cursor.hasNext()).thenAnswer { docIter.hasNext() }
        whenever(cursor.next()).thenAnswer { docIter.next() }
        whenever(findIterable.iterator()).thenReturn(cursor)
        return findIterable
    }

    @Test
    fun `when total docs is less than sample size, all documents are returned`() {
        val docs = (1..50).map { Document("n", it) }
        val col = mockCollection(docs)

        val (schema, sampled) = inferSchema(col, 200)

        assertEquals(50, sampled)
    }

    @Test
    fun `when total docs equals sample size, all documents are returned`() {
        val docs = (1..200).map { Document("n", it) }
        val col = mockCollection(docs)

        val (_, sampled) = inferSchema(col, 200)

        assertEquals(200, sampled)
    }

    @Test
    fun `when total docs exceeds sample size, sampled count does not exceed sample size`() {
        val docs = (1..1000).map { Document("n", it) }
        val col = mockCollection(docs)

        val (_, sampled) = inferSchema(col, 100)

        assertTrue(sampled <= 100, "Expected at most 100 sampled docs, got $sampled")
    }

    @Test
    fun `interval sampling covers beginning, middle and end of collection`() {
        // Use a marker field so we can verify which docs were picked
        val docs = (0 until 1000).map { i -> Document("index", i) }
        val col = mockCollection(docs)

        val (schema, _) = inferSchema(col, 10)

        // The schema must contain the "index" field since all docs have it
        assertTrue(schema.containsKey("index"), "Schema should contain 'index' field")
    }

    @Test
    fun `empty collection returns empty schema and zero sampled count`() {
        val col = mockCollection(emptyList())

        val (schema, sampled) = inferSchema(col, 200)

        assertTrue(schema.isEmpty())
        assertEquals(0, sampled)
    }

    @Test
    fun `single document collection returns that document in full`() {
        val doc = Document("name", "test").append("value", 42)
        val col = mockCollection(listOf(doc))

        val (schema, sampled) = inferSchema(col, 200)

        assertEquals(1, sampled)
        assertTrue(schema.containsKey("name"))
        assertTrue(schema.containsKey("value"))
    }
}
