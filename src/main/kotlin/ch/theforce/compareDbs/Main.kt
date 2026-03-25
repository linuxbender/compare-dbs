package ch.theforce.compareDbs

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.mongodb.client.MongoCollection
import org.bson.Document
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Entry point for the MongoDB database comparison tool.
 *
 * Connects to two MongoDB databases (A and B), compares their collection structure,
 * field schemas, and indexes in parallel using OpenJDK 21 virtual threads, then
 * produces a plain-text or HTML report. Optionally persists the result to MongoDB A.
 *
 * Usage:
 * ```
 * java -jar compare-dbs.jar \
 *   --uri-a "mongodb://localhost:27017/db_old" \
 *   --uri-b "mongodb://localhost:27017/db_new" \
 *   [--sample-size 200] \
 *   [--output report.html] \
 *   [--collections currency,accounts] \
 *   [--parallelism 4] \
 *   [--save-report]
 * ```
 *
 * Exit codes:
 * - `0` — no differences found
 * - `1` — differences detected
 * - `2` — connection or configuration error
 */
fun main(args: Array<String>) = CompareDbsCommand().main(args)

private class CompareDbsCommand : CliktCommand(
    name = "compare-dbs",
    help = "Compare schema, fields and indexes between two MongoDB databases."
) {
    private val uriA by option("--uri-a", help = "MongoDB URI for database A (source), including database name")
        .default("")
    private val uriB by option("--uri-b", help = "MongoDB URI for database B (target), including database name")
        .default("")
    private val sampleSize by option("--sample-size", help = "Max documents to sample per collection (default: 200)")
        .int().default(200)
    private val outputFile by option("--output", help = "Write HTML report to this file; if omitted, plain text is printed to stdout")
    private val collectionsFilter by option("--collections", help = "Comma-separated list of collections to compare; all if omitted")
    private val parallelism by option("--parallelism", help = "Max collections compared in parallel (default: 4)")
        .int().default(4)
    private val saveReport by option("--save-report", help = "Save the comparison result document to MongoDB A (_comparisonReports collection)")
        .flag(default = false)

    override fun run() {
        if (uriA.isBlank() || uriB.isBlank()) {
            echo("ERROR: --uri-a and --uri-b are required.", err = true)
            exitProcess(2)
        }
        if (!isValidMongoUri(uriA)) {
            echo("ERROR: --uri-a must start with mongodb:// or mongodb+srv://", err = true)
            exitProcess(2)
        }
        if (!isValidMongoUri(uriB)) {
            echo("ERROR: --uri-b must start with mongodb:// or mongodb+srv://", err = true)
            exitProcess(2)
        }
        if (sampleSize < 1) {
            echo("ERROR: --sample-size must be at least 1 (got $sampleSize)", err = true)
            exitProcess(2)
        }
        if (parallelism < 1) {
            echo("ERROR: --parallelism must be at least 1 (got $parallelism)", err = true)
            exitProcess(2)
        }

        val correlationId = UUID.randomUUID().toString()
        echo("Run ID: $correlationId")

        // Connect
        echo("Connecting to databases…")
        val connA = try { connect(uriA) } catch (e: Exception) {
            echo("ERROR connecting to A: ${e.message}", err = true); exitProcess(2)
        }
        val connB = try { connect(uriB) } catch (e: Exception) {
            connA.client.close()
            echo("ERROR connecting to B: ${e.message}", err = true); exitProcess(2)
        }

        try {
            echo("A: ${connA.displayUri}")
            echo("B: ${connB.displayUri}")

            // Enumerate collections & views
            val namesA = getCollectionNames(connA.database)
            val namesB = getCollectionNames(connB.database)

            val filter = collectionsFilter?.split(",")?.map { it.trim() }?.toSet()

            val collectionsA = namesA.filter { (_, t) -> t == "collection" }.keys
                .let { if (filter != null) it.filter { n -> n in filter } else it.toList() }
            val collectionsB = namesB.filter { (_, t) -> t == "collection" }.keys
                .let { if (filter != null) it.filter { n -> n in filter } else it.toList() }

            val viewsA = namesA.filter { (_, t) -> t == "view" }.keys.toList()
            val viewsB = namesB.filter { (_, t) -> t == "view" }.keys.toList()

            val setA = collectionsA.toSet()
            val setB = collectionsB.toSet()
            val inBoth = setA.intersect(setB).sorted()

            echo("Collections: ${setA.size} in A, ${setB.size} in B, ${inBoth.size} in both — comparing with sample-size=$sampleSize, parallelism=$parallelism")

            // Compare collections in parallel using JDK 21 virtual threads
            val executor = Executors.newVirtualThreadPerTaskExecutor()
            val semaphore = Semaphore(parallelism)

            val futures = inBoth.map { name ->
                executor.submit<CollectionResult> {
                    semaphore.acquire()
                    try {
                        compareCollection(
                            name,
                            connA.database.getCollection(name),
                            connB.database.getCollection(name),
                            sampleSize,
                            executor
                        )
                    } finally {
                        semaphore.release()
                    }
                }
            }

            val collectionResults = futures.map { it.get() }
            executor.shutdown()
            executor.awaitTermination(30, TimeUnit.SECONDS)

            val result = ComparisonResult(
                correlationId = correlationId,
                onlyInA = (setA - setB).sorted(),
                onlyInB = (setB - setA).sorted(),
                viewsOnlyInA = (viewsA.toSet() - viewsB.toSet()).sorted(),
                viewsOnlyInB = (viewsB.toSet() - viewsA.toSet()).sorted(),
                collections = collectionResults
            )

            // Persist to MongoDB A if requested
            if (saveReport) {
                saveReportToMongo(connA.database.getCollection("_comparisonReports"), result, connA.displayUri, connB.displayUri)
                echo("Report saved to MongoDB A → _comparisonReports (correlationId: $correlationId)")
            }

            // Generate and output report
            if (outputFile != null) {
                val html = generateHtmlReport(result, connA.displayUri, connB.displayUri)
                File(outputFile!!).writeText(html, Charsets.UTF_8)
                echo("HTML report written to: $outputFile")
            } else {
                print(generateTextReport(result, connA.displayUri, connB.displayUri))
            }

            exitProcess(if (result.totalDiffCount == 0) 0 else 1)

        } finally {
            connA.client.close()
            connB.client.close()
        }
    }
}

internal fun isValidMongoUri(uri: String): Boolean =
    uri.startsWith("mongodb://") || uri.startsWith("mongodb+srv://")

/**
 * Compares a single collection between the two databases.
 *
 * Schema sampling for database A and database B is performed concurrently using
 * two virtual threads submitted to the shared [executor].
 *
 * @param name the collection name
 * @param collA the collection handle from database A
 * @param collB the collection handle from database B
 * @param sampleSize maximum documents to sample per database
 * @param executor the virtual-thread executor to use for parallel sampling
 * @return a [CollectionResult] with schema and index diffs
 */
private fun compareCollection(
    name: String,
    collA: MongoCollection<Document>,
    collB: MongoCollection<Document>,
    sampleSize: Int,
    executor: java.util.concurrent.ExecutorService
): CollectionResult {
    // Sample schemas in parallel
    val futureSchemaA = executor.submit<Pair<Map<String, Set<String>>, Int>> { inferSchema(collA, sampleSize) }
    val futureSchemaB = executor.submit<Pair<Map<String, Set<String>>, Int>> { inferSchema(collB, sampleSize) }

    val (schemaA, sampledA) = futureSchemaA.get()
    val (schemaB, sampledB) = futureSchemaB.get()

    val totalA = collA.countDocuments()
    val totalB = collB.countDocuments()

    val schemaDiff = compareSchemas(schemaA, schemaB)
    val indexDiff  = compareIndexes(getIndexes(collA), getIndexes(collB))

    return CollectionResult(
        name = name,
        schemaDiff = schemaDiff,
        indexDiff = indexDiff,
        sampleSizeA = sampledA,
        sampleSizeB = sampledB,
        totalDocsA = totalA,
        totalDocsB = totalB
    )
}

/**
 * Persists the comparison result as a document in the `_comparisonReports` collection of database A.
 *
 * The document includes the correlation ID, timestamp, summary counts, and full per-collection
 * diff details. Multiple runs accumulate in the collection to form a complete run history.
 *
 * @param reportsColl the target `_comparisonReports` collection in database A
 * @param result the full comparison result to persist
 * @param uriA display URI for database A (password redacted)
 * @param uriB display URI for database B (password redacted)
 */
private fun saveReportToMongo(
    reportsColl: MongoCollection<Document>,
    result: ComparisonResult,
    uriA: String,
    uriB: String
) {
    val details = result.collections.map { col ->
        Document("collection", col.name)
            .append("sampleSizeA", col.sampleSizeA)
            .append("sampleSizeB", col.sampleSizeB)
            .append("totalDocsA", col.totalDocsA)
            .append("totalDocsB", col.totalDocsB)
            .append("schema", Document()
                .append("removed", col.schemaDiff.fieldsOnlyInA.map { (p, t) ->
                    Document("path", p).append("types", t.sorted())
                })
                .append("added", col.schemaDiff.fieldsOnlyInB.map { (p, t) ->
                    Document("path", p).append("types", t.sorted())
                })
                .append("changed", col.schemaDiff.typeChanges.map { (p, pair) ->
                    Document("path", p)
                        .append("typesA", pair.first.sorted())
                        .append("typesB", pair.second.sorted())
                })
            )
            .append("indexes", Document()
                .append("missingInB", col.indexDiff.onlyInA.map { idx ->
                    Document("name", idx.name)
                        .append("key", Document(idx.key.mapValues { (_, v) -> v }))
                        .append("indexType", idx.indexType.name.lowercase())
                        .append("unique", idx.unique)
                        .append("sparse", idx.sparse)
                        .append("expireAfterSeconds", idx.expireAfterSeconds)
                })
                .append("addedInB", col.indexDiff.onlyInB.map { idx ->
                    Document("name", idx.name)
                        .append("key", Document(idx.key.mapValues { (_, v) -> v }))
                        .append("indexType", idx.indexType.name.lowercase())
                        .append("unique", idx.unique)
                        .append("sparse", idx.sparse)
                        .append("expireAfterSeconds", idx.expireAfterSeconds)
                })
                .append("optionChanges", col.indexDiff.optionChanges.map { (a, b) ->
                    Document("name", a.name)
                        .append("key", Document(a.key.mapValues { (_, v) -> v }))
                        .append("unique_A", a.unique).append("unique_B", b.unique)
                        .append("sparse_A", a.sparse).append("sparse_B", b.sparse)
                        .append("ttl_A", a.expireAfterSeconds).append("ttl_B", b.expireAfterSeconds)
                })
            )
    }

    val doc = Document()
        .append("correlationId", result.correlationId)
        .append("timestamp", java.util.Date())
        .append("uriA", uriA)
        .append("uriB", uriB)
        .append("summary", Document()
            .append("collectionsOnlyInA", result.onlyInA)
            .append("collectionsOnlyInB", result.onlyInB)
            .append("viewsOnlyInA", result.viewsOnlyInA)
            .append("viewsOnlyInB", result.viewsOnlyInB)
            .append("collectionsWithSchemaDiff", result.collections.filter { !it.schemaDiff.isEmpty }.map { it.name })
            .append("collectionsWithIndexDiff", result.collections.filter { !it.indexDiff.isEmpty }.map { it.name })
            .append("totalDiffs", result.totalDiffCount)
        )
        .append("details", details)

    reportsColl.insertOne(doc)
}
