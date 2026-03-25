package ch.theforce.compareDbs

import com.mongodb.client.MongoCollection
import org.bson.Document
import org.bson.types.Decimal128
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import java.util.Date

private val logger = LoggerFactory.getLogger("ch.theforce.compareDbs.SchemaInferrer")

/** Maximum number of array elements inspected per field during schema extraction. */
private const val MAX_ARRAY_ELEMENTS = 5

/**
 * Infers the schema of a MongoDB collection by sampling documents and
 * extracting all field paths with their observed BSON types.
 *
 * **Sampling strategy:**
 * - When `totalDocs <= sampleSize`, every document in the collection is used.
 * - When `totalDocs > sampleSize`, a uniform interval `n = totalDocs / sampleSize`
 *   is calculated and every n-th document is collected in a single cursor pass.
 *   This provides deterministic, evenly distributed coverage of the full collection.
 *
 * Field paths follow dot-notation for embedded documents (e.g. `address.city`)
 * and `[]`-notation for array element fields (e.g. `items[].price`).
 *
 * @param collection the MongoDB collection to sample
 * @param sampleSize maximum number of documents to inspect
 * @return map of dot-notation field paths to the set of BSON type names
 *         observed at that path (e.g. `{"address.city" -> setOf("string")}`)
 */
fun inferSchema(collection: MongoCollection<Document>, sampleSize: Int): Pair<Map<String, Set<String>>, Int> {
    val totalDocs = collection.countDocuments()
    logger.debug("inferSchema: totalDocs={}, sampleSize={}", totalDocs, sampleSize)

    if (totalDocs == 0L) {
        logger.debug("inferSchema: collection is empty, skipping")
        return Pair(emptyMap(), 0)
    }

    val sampled = mutableListOf<Document>()

    if (totalDocs <= sampleSize) {
        logger.debug("inferSchema: full scan (totalDocs <= sampleSize)")
        collection.find().forEach { sampled += it }
    } else {
        val interval = totalDocs / sampleSize
        logger.debug("inferSchema: interval sampling, interval={}", interval)
        var counter = 0L
        collection.find().forEach { doc ->
            if (counter % interval == 0L && sampled.size < sampleSize) {
                sampled += doc
            }
            counter++
        }
    }
    logger.debug("inferSchema: sampled {} docs", sampled.size)

    val schema = mutableMapOf<String, MutableSet<String>>()
    for (doc in sampled) {
        val fields = extractFields(doc, "")
        for ((path, type) in fields) {
            schema.getOrPut(path) { mutableSetOf() }.add(type)
        }
    }

    return Pair(schema, sampled.size)
}

/**
 * Recursively extracts all field paths and their BSON type names from a document.
 *
 * - Embedded documents produce a parent path typed as `"object"` plus child paths.
 * - Arrays produce a path typed as `"array"` plus element paths using `[]` notation.
 *   Only the first [MAX_ARRAY_ELEMENTS] elements are inspected to keep sampling fast.
 * - Scalar values are mapped to their BSON type name via [bsonTypeName].
 * - Null values are recorded as type `"null"` to distinguish optional fields.
 *
 * @param doc the document (or embedded document) to traverse
 * @param prefix dot-notation prefix accumulated from parent levels; empty for the root document
 * @return map of full field paths to their single BSON type name at this document level
 */
fun extractFields(doc: Document, prefix: String): Map<String, String> {
    val result = mutableMapOf<String, String>()

    for ((key, value) in doc) {
        if (key == "_id" && prefix.isEmpty()) {
            result["_id"] = bsonTypeName(value)
            continue
        }

        val path = if (prefix.isEmpty()) key else "$prefix.$key"

        when (value) {
            is Document -> {
                result[path] = "object"
                result.putAll(extractFields(value, path))
            }
            is List<*> -> {
                result[path] = "array"
                value.take(MAX_ARRAY_ELEMENTS).forEach { element ->
                    when (element) {
                        is Document -> result.putAll(extractFields(element, "$path[]"))
                        null -> result["$path[]"] = "null"
                        else -> result["$path[]"] = bsonTypeName(element)
                    }
                }
            }
            null -> result[path] = "null"
            else -> result[path] = bsonTypeName(value)
        }
    }

    return result
}

/**
 * Maps a Java/BSON value to its canonical BSON type name string.
 *
 * | Java/Kotlin type           | Returned name  |
 * |----------------------------|----------------|
 * | `String`                   | `string`       |
 * | `Int` / `Integer`          | `int32`        |
 * | `Long`                     | `int64`        |
 * | `Double`                   | `double`       |
 * | `Boolean`                  | `bool`         |
 * | `Date`                     | `date`         |
 * | `ObjectId`                 | `objectId`     |
 * | `Decimal128`               | `decimal128`   |
 * | `Document`                 | `object`       |
 * | `List<*>`                  | `array`        |
 * | `org.bson.types.Binary`    | `binData`      |
 * | `org.bson.BsonRegularExpression` | `regex` |
 * | `null`                     | `null`         |
 * | anything else              | `unknown`      |
 *
 * @param value the value whose type should be identified
 * @return the BSON type name string
 */
fun bsonTypeName(value: Any?): String = when (value) {
    null -> "null"
    is String -> "string"
    is Int -> "int32"
    is Long -> "int64"
    is Double -> "double"
    is Boolean -> "bool"
    is Date -> "date"
    is ObjectId -> "objectId"
    is Decimal128 -> "decimal128"
    is Document -> "object"
    is List<*> -> "array"
    is org.bson.types.Binary -> "binData"
    is org.bson.BsonRegularExpression -> "regex"
    else -> "unknown(${value::class.simpleName})"
}
