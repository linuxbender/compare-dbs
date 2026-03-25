package ch.theforce.compareDbs

import org.bson.Document

/**
 * Represents a single MongoDB index with its key specification and options.
 *
 * @property name the index name as stored in MongoDB
 * @property key ordered map of field name to direction (1, -1) or type ("text", "hashed", etc.)
 * @property indexType the classified index type (single, compound, text, ttl, etc.)
 * @property unique whether the index enforces uniqueness
 * @property sparse whether the index only includes documents that have the indexed field
 * @property expireAfterSeconds TTL in seconds; null if the index is not a TTL index
 * @property partialFilter partial filter expression document; null if not a partial index
 * @property rawOptions the full raw index document from MongoDB for any additional options
 */
data class IndexSpec(
    val name: String,
    val key: Map<String, Any>,
    val indexType: IndexType,
    val unique: Boolean = false,
    val sparse: Boolean = false,
    val expireAfterSeconds: Int? = null,
    val partialFilter: Document? = null,
    val rawOptions: Document
)

/**
 * Classified MongoDB index types derived from the index key specification.
 */
enum class IndexType {
    /** Single ascending or descending field index */
    SINGLE,
    /** Multiple fields, all ascending or descending */
    COMPOUND,
    /** At least one field indexed as "text" for full-text search */
    TEXT,
    /** Single field with expireAfterSeconds — automatically deletes documents */
    TTL,
    /** Field indexed as "2d" for legacy coordinate pairs */
    GEOSPATIAL_2D,
    /** Field indexed as "2dsphere" for GeoJSON objects */
    GEOSPATIAL_2DSPHERE,
    /** Field indexed as "hashed" for hash-based sharding */
    HASHED,
    /** Wildcard index covering all fields or a sub-path */
    WILDCARD,
    /** Any other index type not explicitly classified */
    UNKNOWN
}

/**
 * The result of comparing schemas between two collections.
 *
 * @property fieldsOnlyInA fields present in database A but absent in database B,
 *   mapped to the set of BSON type names observed in A
 * @property fieldsOnlyInB fields present in database B but absent in database A,
 *   mapped to the set of BSON type names observed in B
 * @property typeChanges fields present in both databases but with differing type sets,
 *   mapped to a pair of (typesInA, typesInB)
 */
data class SchemaDiff(
    val fieldsOnlyInA: Map<String, Set<String>>,
    val fieldsOnlyInB: Map<String, Set<String>>,
    val typeChanges: Map<String, Pair<Set<String>, Set<String>>>
) {
    /** Returns true when there are no differences between the two schemas. */
    val isEmpty: Boolean
        get() = fieldsOnlyInA.isEmpty() && fieldsOnlyInB.isEmpty() && typeChanges.isEmpty()
}

/**
 * The result of comparing indexes between two collections.
 *
 * @property onlyInA indexes present in database A but absent in database B
 * @property onlyInB indexes present in database B but absent in database A
 * @property optionChanges indexes with the same key but differing options,
 *   each entry is a pair of (indexFromA, indexFromB)
 */
data class IndexDiff(
    val onlyInA: List<IndexSpec>,
    val onlyInB: List<IndexSpec>,
    val optionChanges: List<Pair<IndexSpec, IndexSpec>>
) {
    /** Returns true when there are no differences between the two index sets. */
    val isEmpty: Boolean
        get() = onlyInA.isEmpty() && onlyInB.isEmpty() && optionChanges.isEmpty()
}

/**
 * Comparison result for a single collection present in both databases.
 *
 * @property name the collection name
 * @property schemaDiff field-level schema differences
 * @property indexDiff index-level differences
 * @property sampleSizeA number of documents actually sampled from database A
 * @property sampleSizeB number of documents actually sampled from database B
 * @property totalDocsA total document count in database A
 * @property totalDocsB total document count in database B
 */
data class CollectionResult(
    val name: String,
    val schemaDiff: SchemaDiff,
    val indexDiff: IndexDiff,
    val sampleSizeA: Int,
    val sampleSizeB: Int,
    val totalDocsA: Long,
    val totalDocsB: Long
)

/**
 * The top-level result of a full database comparison run.
 *
 * @property correlationId unique UUID v4 identifying this comparison run
 * @property onlyInA collection names present only in database A
 * @property onlyInB collection names present only in database B
 * @property viewsOnlyInA view names present only in database A
 * @property viewsOnlyInB view names present only in database B
 * @property collections per-collection comparison results for collections present in both databases
 */
data class ComparisonResult(
    val correlationId: String,
    val onlyInA: List<String>,
    val onlyInB: List<String>,
    val viewsOnlyInA: List<String>,
    val viewsOnlyInB: List<String>,
    val collections: List<CollectionResult>
) {
    /** Collections that have at least one schema or index difference. */
    val collectionsWithDiffs: List<CollectionResult>
        get() = collections.filter { !it.schemaDiff.isEmpty || !it.indexDiff.isEmpty }

    /** Total number of differences across all collections, views, and index changes. */
    val totalDiffCount: Int
        get() = onlyInA.size + onlyInB.size + viewsOnlyInA.size + viewsOnlyInB.size +
                collections.sumOf {
                    it.schemaDiff.fieldsOnlyInA.size +
                    it.schemaDiff.fieldsOnlyInB.size +
                    it.schemaDiff.typeChanges.size +
                    it.indexDiff.onlyInA.size +
                    it.indexDiff.onlyInB.size +
                    it.indexDiff.optionChanges.size
                }
}
