package ch.theforce.compareDbs

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class SchemaComparatorTest {

    @Test
    fun `fields only in A are reported as removed`() {
        val schemaA = mapOf("legacyId" to setOf("string"))
        val schemaB = emptyMap<String, Set<String>>()

        val diff = compareSchemas(schemaA, schemaB)

        assertTrue(diff.fieldsOnlyInA.containsKey("legacyId"))
        assertTrue(diff.fieldsOnlyInB.isEmpty())
        assertTrue(diff.typeChanges.isEmpty())
    }

    @Test
    fun `fields only in B are reported as added`() {
        val schemaA = emptyMap<String, Set<String>>()
        val schemaB = mapOf("externalRef" to setOf("string"))

        val diff = compareSchemas(schemaA, schemaB)

        assertTrue(diff.fieldsOnlyInB.containsKey("externalRef"))
        assertTrue(diff.fieldsOnlyInA.isEmpty())
        assertTrue(diff.typeChanges.isEmpty())
    }

    @Test
    fun `fields with identical type sets in both databases are not reported`() {
        val schema = mapOf("name" to setOf("string"))

        val diff = compareSchemas(schema, schema)

        assertTrue(diff.isEmpty)
    }

    @Test
    fun `fields with different type sets are reported as changed`() {
        val schemaA = mapOf("amount" to setOf("double"))
        val schemaB = mapOf("amount" to setOf("decimal128"))

        val diff = compareSchemas(schemaA, schemaB)

        assertTrue(diff.typeChanges.containsKey("amount"))
        val (typesA, typesB) = diff.typeChanges["amount"]!!
        assertEquals(setOf("double"), typesA)
        assertEquals(setOf("decimal128"), typesB)
    }

    @Test
    fun `optional field with same mixed type set in both databases is not reported as changed`() {
        // Both A and B have {string, null} — this is an optional field, not a change
        val schema = mapOf("deletedAt" to setOf("date", "null"))

        val diff = compareSchemas(schema, schema)

        assertTrue(diff.typeChanges.isEmpty(), "Optional fields with same type set should not produce a type change")
        assertTrue(diff.isEmpty)
    }

    @Test
    fun `optional field where one side loses null is reported as changed`() {
        val schemaA = mapOf("deletedAt" to setOf("date", "null"))
        val schemaB = mapOf("deletedAt" to setOf("date"))  // now required in B

        val diff = compareSchemas(schemaA, schemaB)

        assertTrue(diff.typeChanges.containsKey("deletedAt"))
    }

    @Test
    fun `empty schemas produce empty diff`() {
        val diff = compareSchemas(emptyMap(), emptyMap())

        assertTrue(diff.isEmpty)
    }

    @Test
    fun `multiple removed, added and changed fields are all captured`() {
        val schemaA = mapOf(
            "kept"    to setOf("string"),
            "removed" to setOf("int32"),
            "changed" to setOf("double")
        )
        val schemaB = mapOf(
            "kept"    to setOf("string"),
            "added"   to setOf("bool"),
            "changed" to setOf("decimal128")
        )

        val diff = compareSchemas(schemaA, schemaB)

        assertEquals(setOf("removed"), diff.fieldsOnlyInA.keys)
        assertEquals(setOf("added"), diff.fieldsOnlyInB.keys)
        assertEquals(setOf("changed"), diff.typeChanges.keys)
    }
}
