package ch.theforce.compareDbs

import org.bson.Document
import org.bson.types.Decimal128
import org.bson.types.ObjectId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.math.BigDecimal
import java.util.Date

class SchemaInferrerTest {

    // ── bsonTypeName ──────────────────────────────────────────────────────────

    @Test
    fun `string value maps to type string`() {
        assertEquals("string", bsonTypeName("hello"))
    }

    @Test
    fun `Int value maps to int32`() {
        assertEquals("int32", bsonTypeName(42))
    }

    @Test
    fun `Long value maps to int64`() {
        assertEquals("int64", bsonTypeName(42L))
    }

    @Test
    fun `Double value maps to double`() {
        assertEquals("double", bsonTypeName(3.14))
    }

    @Test
    fun `Boolean value maps to bool`() {
        assertEquals("bool", bsonTypeName(true))
    }

    @Test
    fun `Date value maps to date`() {
        assertEquals("date", bsonTypeName(Date()))
    }

    @Test
    fun `ObjectId value maps to objectId`() {
        assertEquals("objectId", bsonTypeName(ObjectId()))
    }

    @Test
    fun `Decimal128 value maps to decimal128`() {
        assertEquals("decimal128", bsonTypeName(Decimal128(BigDecimal("1.5"))))
    }

    @Test
    fun `null maps to null`() {
        assertEquals("null", bsonTypeName(null))
    }

    @Test
    fun `Document maps to object`() {
        assertEquals("object", bsonTypeName(Document("x", 1)))
    }

    @Test
    fun `List maps to array`() {
        assertEquals("array", bsonTypeName(listOf(1, 2, 3)))
    }

    // ── extractFields ─────────────────────────────────────────────────────────

    @Test
    fun `scalar fields at root level are extracted with correct type`() {
        val doc = Document("name", "Alice").append("age", 30)

        val fields = extractFields(doc, "")

        assertEquals("string", fields["name"])
        assertEquals("int32", fields["age"])
    }

    @Test
    fun `nested document fields use dot-notation paths`() {
        val address = Document("city", "Bern").append("zip", "3000")
        val doc = Document("address", address)

        val fields = extractFields(doc, "")

        assertEquals("object", fields["address"])
        assertEquals("string", fields["address.city"])
        assertEquals("string", fields["address.zip"])
    }

    @Test
    fun `deeply nested embeddings (3 levels) are fully traversed`() {
        val inner = Document("value", 1)
        val mid   = Document("inner", inner)
        val outer = Document("mid", mid)
        val doc   = Document("outer", outer)

        val fields = extractFields(doc, "")

        assertTrue(fields.containsKey("outer"))
        assertTrue(fields.containsKey("outer.mid"))
        assertTrue(fields.containsKey("outer.mid.inner"))
        assertTrue(fields.containsKey("outer.mid.inner.value"))
    }

    @Test
    fun `array field is typed as array and element fields use bracket notation`() {
        val items = listOf(
            Document("price", 9.99),
            Document("price", 14.5)
        )
        val doc = Document("items", items)

        val fields = extractFields(doc, "")

        assertEquals("array", fields["items"])
        assertEquals("double", fields["items[].price"])
    }

    @Test
    fun `null field value is recorded as null type`() {
        val doc = Document("deletedAt", null)

        val fields = extractFields(doc, "")

        assertEquals("null", fields["deletedAt"])
    }

    @Test
    fun `array of primitives records element type with bracket notation`() {
        val doc = Document("tags", listOf("a", "b", "c"))

        val fields = extractFields(doc, "")

        assertEquals("array", fields["tags"])
        assertEquals("string", fields["tags[]"])
    }

    @Test
    fun `empty document returns empty map`() {
        val fields = extractFields(Document(), "")
        // Only _id might be absent; either way, no crash
        assertTrue(fields.isEmpty() || fields.keys.all { it == "_id" })
    }

    @Test
    fun `prefix is prepended to all extracted field paths`() {
        val doc = Document("x", 1)

        val fields = extractFields(doc, "parent")

        assertTrue(fields.containsKey("parent.x"))
        assertFalse(fields.containsKey("x"))
    }
}
