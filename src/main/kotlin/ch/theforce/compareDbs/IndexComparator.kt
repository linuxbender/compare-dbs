package ch.theforce.compareDbs

import com.mongodb.client.MongoCollection
import org.bson.Document

/** The name of the mandatory default `_id` index — always skipped during comparison. */
private const val ID_INDEX_NAME = "_id_"

/**
 * Reads all user-defined indexes from a collection and classifies them.
 *
 * The `_id_` index is always present in every MongoDB collection and is never
 * structurally interesting for a migration comparison, so it is silently skipped.
 *
 * @param collection the MongoDB collection whose indexes are to be read
 * @return list of [IndexSpec] objects, one per non-`_id_` index
 */
fun getIndexes(collection: MongoCollection<Document>): List<IndexSpec> {
    return collection.listIndexes()
        .filter { it.getString("name") != ID_INDEX_NAME }
        .map { doc -> parseIndexSpec(doc) }
}

/**
 * Compares two lists of indexes and produces a structured diff.
 *
 * Two indexes are considered the **same index** when their `key` maps are equal.
 * The index name is not used for identity because names can change during a migration
 * (e.g. when an index is dropped and re-created with a different name).
 *
 * @param indexesA indexes from database A
 * @param indexesB indexes from database B
 * @return an [IndexDiff] describing which indexes are missing, added, or have changed options
 */
fun compareIndexes(indexesA: List<IndexSpec>, indexesB: List<IndexSpec>): IndexDiff {
    val mapA = indexesA.associateBy { normalizeKey(it.key) }
    val mapB = indexesB.associateBy { normalizeKey(it.key) }

    val allKeys = mapA.keys + mapB.keys

    val onlyInA = mutableListOf<IndexSpec>()
    val onlyInB = mutableListOf<IndexSpec>()
    val optionChanges = mutableListOf<Pair<IndexSpec, IndexSpec>>()

    for (key in allKeys) {
        val a = mapA[key]
        val b = mapB[key]

        when {
            a != null && b == null -> onlyInA += a
            a == null && b != null -> onlyInB += b
            a != null && b != null && !optionsEqual(a, b) -> optionChanges += Pair(a, b)
        }
    }

    return IndexDiff(
        onlyInA = onlyInA.sortedBy { it.name },
        onlyInB = onlyInB.sortedBy { it.name },
        optionChanges = optionChanges.sortedBy { it.first.name }
    )
}

/**
 * Classifies an index key map into an [IndexType].
 *
 * Classification rules (evaluated in priority order):
 * 1. Any key value `"text"` → [IndexType.TEXT]
 * 2. Any key value `"2dsphere"` → [IndexType.GEOSPATIAL_2DSPHERE]
 * 3. Any key value `"2d"` → [IndexType.GEOSPATIAL_2D]
 * 4. Any key value `"hashed"` → [IndexType.HASHED]
 * 5. Any key name contains `\$**` → [IndexType.WILDCARD]
 * 6. Single key with `expireAfterSeconds` → [IndexType.TTL]
 * 7. Single key → [IndexType.SINGLE]
 * 8. Multiple keys, all numeric directions → [IndexType.COMPOUND]
 * 9. Anything else → [IndexType.UNKNOWN]
 *
 * @param key the index key map from MongoDB (e.g. `{"email": 1}`)
 * @param expireAfterSeconds whether the index has a TTL setting
 * @return the classified [IndexType]
 */
fun classifyIndexType(key: Map<String, Any>, expireAfterSeconds: Int?): IndexType {
    val values = key.values.map { it.toString() }
    val keyNames = key.keys

    return when {
        values.any { it == "text" } -> IndexType.TEXT
        values.any { it == "2dsphere" } -> IndexType.GEOSPATIAL_2DSPHERE
        values.any { it == "2d" } -> IndexType.GEOSPATIAL_2D
        values.any { it == "hashed" } -> IndexType.HASHED
        keyNames.any { it.contains("\$**") } -> IndexType.WILDCARD
        key.size == 1 && expireAfterSeconds != null -> IndexType.TTL
        key.size == 1 -> IndexType.SINGLE
        key.size > 1 -> IndexType.COMPOUND
        else -> IndexType.UNKNOWN
    }
}

// ── Internal helpers ──────────────────────────────────────────────────────────

/**
 * Parses a raw MongoDB index document into an [IndexSpec].
 */
private fun parseIndexSpec(doc: Document): IndexSpec {
    val keyDoc = doc.get("key", Document::class.java) ?: Document()
    val key = keyDoc.entries.associate { (k, v) -> k to (v ?: 1) }
    val expireAfterSeconds = doc.getInteger("expireAfterSeconds")
    val partialFilter = doc.get("partialFilterExpression", Document::class.java)

    return IndexSpec(
        name = doc.getString("name") ?: "",
        key = key,
        indexType = classifyIndexType(key, expireAfterSeconds),
        unique = doc.getBoolean("unique", false),
        sparse = doc.getBoolean("sparse", false),
        expireAfterSeconds = expireAfterSeconds,
        partialFilter = partialFilter,
        rawOptions = doc
    )
}

/**
 * Produces a stable string key from an index key map for use as a map key.
 * Entries are sorted by field name to ensure consistent comparison.
 */
private fun normalizeKey(key: Map<String, Any>): String =
    key.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" }

/**
 * Checks whether two [IndexSpec] instances have the same effective options
 * (unique, sparse, TTL, partial filter). Index names are ignored.
 *
 * Partial filters are compared structurally (field-by-field) rather than via
 * [org.bson.Document.toJson], which is sensitive to key insertion order and
 * would produce false diffs when the same filter was built with a different
 * field order.
 */
private fun optionsEqual(a: IndexSpec, b: IndexSpec): Boolean =
    a.unique == b.unique &&
    a.sparse == b.sparse &&
    a.expireAfterSeconds == b.expireAfterSeconds &&
    documentsEqual(a.partialFilter, b.partialFilter)

/**
 * Recursively compares two [org.bson.Document] instances for structural equality,
 * independent of key insertion order.
 */
private fun documentsEqual(a: org.bson.Document?, b: org.bson.Document?): Boolean {
    if (a == null && b == null) return true
    if (a == null || b == null) return false
    if (a.keys != b.keys) return false
    return a.keys.all { key ->
        val va = a[key]
        val vb = b[key]
        if (va is org.bson.Document && vb is org.bson.Document) documentsEqual(va, vb)
        else va == vb
    }
}
