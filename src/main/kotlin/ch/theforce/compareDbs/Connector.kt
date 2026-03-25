package ch.theforce.compareDbs

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoDatabase
import org.bson.Document
import org.slf4j.LoggerFactory
import java.net.URI

private val logger = LoggerFactory.getLogger("ch.theforce.compareDbs.Connector")

/**
 * Holds a connected MongoDB client together with the target database handle.
 *
 * @property client the underlying [MongoClient]; must be closed after use
 * @property database the database selected from the connection URI
 * @property displayUri the original URI with the password component redacted, safe for logging
 */
data class DatabaseConnection(
    val client: MongoClient,
    val database: MongoDatabase,
    val displayUri: String
)

/**
 * Opens and validates a MongoDB connection from a full connection URI.
 *
 * The URI must include the database name as the path component, e.g.
 * `mongodb://host:27017/mydb` or `mongodb+srv://user:pass@cluster/mydb`.
 *
 * @param uri the full MongoDB connection string including database name
 * @return a [DatabaseConnection] with an active, ping-verified connection
 * @throws IllegalArgumentException if the URI contains no database name
 * @throws RuntimeException if the server cannot be reached within the connect timeout
 */
fun connect(uri: String): DatabaseConnection {
    val displayUri = redactPassword(uri)
    logger.debug("Connecting to: {}", displayUri)

    val client = MongoClients.create(uri)

    // Extract the database name from the URI path
    val dbName = extractDatabaseName(uri)
        ?: throw IllegalArgumentException(
            "No database name found in URI: $displayUri\n" +
            "Please include the database name in the URI path, e.g. mongodb://host:27017/mydb"
        )

    val database = client.getDatabase(dbName)

    // Ping to verify connectivity early, before any sampling begins
    try {
        database.runCommand(Document("ping", 1))
        logger.debug("Ping successful: {} (db={})", displayUri, dbName)
    } catch (e: Exception) {
        client.close()
        throw RuntimeException("Cannot connect to $displayUri: ${e.message}", e)
    }

    return DatabaseConnection(client, database, displayUri)
}

/**
 * Retrieves all collection and view names from a database.
 *
 * @param database the MongoDB database to inspect
 * @return a map of name to type string ("collection" or "view")
 */
fun getCollectionNames(database: MongoDatabase): Map<String, String> {
    val result = mutableMapOf<String, String>()
    database.listCollections().forEach { doc ->
        val name = doc.getString("name") ?: return@forEach
        val type = doc.getString("type") ?: "collection"
        result[name] = type
    }
    return result
}

/**
 * Extracts the database name from a MongoDB URI by parsing the path component.
 *
 * Handles both `mongodb://` and `mongodb+srv://` schemes. Returns null if
 * the path is absent or empty (e.g. `mongodb://host/`).
 */
private fun extractDatabaseName(uri: String): String? {
    return try {
        // Normalize srv URIs so java.net.URI can parse them
        val normalized = if (uri.startsWith("mongodb+srv://")) {
            uri.replace("mongodb+srv://", "http://")
        } else {
            uri.replace("mongodb://", "http://")
        }
        val path = URI(normalized).path?.trimStart('/')?.substringBefore('?')
        if (path.isNullOrBlank()) null else path
    } catch (e: Exception) {
        // Fallback: grab the segment after the last '/' before any '?'
        uri.substringAfterLast('/').substringBefore('?').takeIf { it.isNotBlank() }
    }
}

/**
 * Returns the URI with the password component replaced by `***`.
 *
 * Input:  `mongodb://user:secret@host:27017/db`
 * Output: `mongodb://user:***@host:27017/db`
 *
 * If the URI has no password or cannot be parsed, it is returned unchanged.
 */
fun redactPassword(uri: String): String {
    return try {
        val schemeEnd = uri.indexOf("://")
        if (schemeEnd == -1) return uri
        val afterScheme = uri.substring(schemeEnd + 3)
        // Use lastIndexOf to correctly handle passwords containing '@'
        val atIndex = afterScheme.lastIndexOf('@')
        if (atIndex == -1) return uri
        val userInfo = afterScheme.substring(0, atIndex)
        val colonIndex = userInfo.indexOf(':')
        if (colonIndex == -1) return uri
        val passwordStart = schemeEnd + 3 + colonIndex + 1
        val passwordEnd = schemeEnd + 3 + atIndex
        uri.substring(0, passwordStart) + "***" + uri.substring(passwordEnd)
    } catch (e: Exception) {
        uri
    }
}
