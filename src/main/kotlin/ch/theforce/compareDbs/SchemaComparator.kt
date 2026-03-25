package ch.theforce.compareDbs

/**
 * Compares two inferred schemas and produces a structured diff.
 *
 * A field is considered:
 * - **Removed** (`fieldsOnlyInA`): present in schema A but absent in schema B.
 * - **Added** (`fieldsOnlyInB`): present in schema B but absent in schema A.
 * - **Changed** (`typeChanges`): present in both, but the sets of observed BSON types differ.
 * - **Identical / Optional**: present in both with the same type set (including mixed sets like
 *   `{string, null}`) — not reported, to avoid noise for genuinely optional fields.
 *
 * @param schemaA field-path-to-type-set map inferred from database A
 * @param schemaB field-path-to-type-set map inferred from database B
 * @return a [SchemaDiff] describing all differences
 */
fun compareSchemas(
    schemaA: Map<String, Set<String>>,
    schemaB: Map<String, Set<String>>
): SchemaDiff {
    val allPaths = schemaA.keys + schemaB.keys

    val fieldsOnlyInA = mutableMapOf<String, Set<String>>()
    val fieldsOnlyInB = mutableMapOf<String, Set<String>>()
    val typeChanges = mutableMapOf<String, Pair<Set<String>, Set<String>>>()

    for (path in allPaths) {
        val typesA = schemaA[path]
        val typesB = schemaB[path]

        when {
            typesA != null && typesB == null -> fieldsOnlyInA[path] = typesA
            typesA == null && typesB != null -> fieldsOnlyInB[path] = typesB
            typesA != null && typesB != null && typesA != typesB ->
                typeChanges[path] = Pair(typesA, typesB)
            // typesA == typesB → identical, skip
        }
    }

    return SchemaDiff(
        fieldsOnlyInA = fieldsOnlyInA.toSortedMap(),
        fieldsOnlyInB = fieldsOnlyInB.toSortedMap(),
        typeChanges = typeChanges.toSortedMap()
    )
}
