package ch.theforce.compareDbs

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

// ── Plain-text report ─────────────────────────────────────────────────────────

/**
 * Generates a human-readable plain-text comparison report suitable for console output or CI logs.
 *
 * Labels used:
 * - `[REMOVED]`  — field or index present in A, missing in B
 * - `[ADDED]`    — field or index present in B, missing in A
 * - `[CHANGED]`  — field present in both but with differing BSON type sets
 * - `[OPTIONAL]` — field present in both with the same mixed type set (e.g. `{string, null}`)
 *
 * @param result the full comparison result to render
 * @param uriA display URI for database A (password already redacted)
 * @param uriB display URI for database B (password already redacted)
 * @return the formatted report as a plain string
 */
fun generateTextReport(result: ComparisonResult, uriA: String, uriB: String): String {
    val sb = StringBuilder()
    val ts = LocalDateTime.now().format(TIMESTAMP_FORMAT)

    sb.appendLine("=".repeat(70))
    sb.appendLine("  MongoDB Comparison Report")
    sb.appendLine("=".repeat(70))
    sb.appendLine("  Run ID  : ${result.correlationId}")
    sb.appendLine("  Generated: $ts")
    sb.appendLine("  A: $uriA")
    sb.appendLine("  B: $uriB")
    sb.appendLine("=".repeat(70))

    // Collections overview
    sb.appendLine()
    sb.appendLine("--- Collections ---")
    if (result.onlyInA.isNotEmpty())
        sb.appendLine("  Only in A : ${result.onlyInA.joinToString(", ")}")
    if (result.onlyInB.isNotEmpty())
        sb.appendLine("  Only in B : ${result.onlyInB.joinToString(", ")}")
    if (result.viewsOnlyInA.isNotEmpty())
        sb.appendLine("  Views only in A: ${result.viewsOnlyInA.joinToString(", ")}")
    if (result.viewsOnlyInB.isNotEmpty())
        sb.appendLine("  Views only in B: ${result.viewsOnlyInB.joinToString(", ")}")
    sb.appendLine("  In both   : ${result.collections.size} collection(s)")

    // Per-collection detail
    for (col in result.collections.sortedBy { it.name }) {
        sb.appendLine()
        sb.appendLine("--- ${col.name} ---")
        sb.appendLine("  Sampled: A=${col.sampleSizeA}/${col.totalDocsA} docs  B=${col.sampleSizeB}/${col.totalDocsB} docs")

        // Schema
        val sd = col.schemaDiff
        if (sd.isEmpty) {
            sb.appendLine("  Schema : OK")
        } else {
            sb.appendLine("  Schema differences:")
            sd.fieldsOnlyInA.forEach { (path, types) ->
                sb.appendLine("    [REMOVED]  ${path.padEnd(50)} ${types.sorted().joinToString(", ")}")
            }
            sd.fieldsOnlyInB.forEach { (path, types) ->
                sb.appendLine("    [ADDED]    ${path.padEnd(50)} ${types.sorted().joinToString(", ")}")
            }
            sd.typeChanges.forEach { (path, pair) ->
                val (ta, tb) = pair
                sb.appendLine("    [CHANGED]  ${path.padEnd(50)} A={${ta.sorted().joinToString()}}  B={${tb.sorted().joinToString()}}")
            }
        }

        // Indexes
        val id = col.indexDiff
        if (id.isEmpty) {
            sb.appendLine("  Indexes: OK")
        } else {
            sb.appendLine("  Index differences:")
            id.onlyInA.forEach { idx ->
                sb.appendLine("    [MISSING IN B]  ${idx.name}  type=${idx.indexType.name.lowercase()}  key=${idx.key}${if (idx.unique) "  unique" else ""}${if (idx.sparse) "  sparse" else ""}${if (idx.expireAfterSeconds != null) "  ttl=${idx.expireAfterSeconds}s" else ""}")
            }
            id.onlyInB.forEach { idx ->
                sb.appendLine("    [ADDED IN B]    ${idx.name}  type=${idx.indexType.name.lowercase()}  key=${idx.key}${if (idx.unique) "  unique" else ""}${if (idx.sparse) "  sparse" else ""}${if (idx.expireAfterSeconds != null) "  ttl=${idx.expireAfterSeconds}s" else ""}")
            }
            id.optionChanges.forEach { (a, b) ->
                sb.appendLine("    [OPTION DIFF]   ${a.name}  key=${a.key}")
                if (a.unique != b.unique)
                    sb.appendLine("      unique: A=${a.unique}  B=${b.unique}")
                if (a.sparse != b.sparse)
                    sb.appendLine("      sparse: A=${a.sparse}  B=${b.sparse}")
                if (a.expireAfterSeconds != b.expireAfterSeconds)
                    sb.appendLine("      expireAfterSeconds: A=${a.expireAfterSeconds}  B=${b.expireAfterSeconds}")
                if (a.partialFilter?.toJson() != b.partialFilter?.toJson())
                    sb.appendLine("      partialFilter: A=${a.partialFilter?.toJson()}  B=${b.partialFilter?.toJson()}")
            }
        }
    }

    // Summary
    sb.appendLine()
    sb.appendLine("=".repeat(70))
    val schemaDiffs = result.collections.count { !it.schemaDiff.isEmpty }
    val indexDiffs  = result.collections.count { !it.indexDiff.isEmpty }
    val missingCols = result.onlyInA.size + result.onlyInB.size
    if (result.totalDiffCount == 0) {
        sb.appendLine("  RESULT: No differences found — databases are structurally identical.")
    } else {
        sb.appendLine("  RESULT: Differences found!")
        if (missingCols > 0) sb.appendLine("    Collections missing: $missingCols")
        if (schemaDiffs > 0) sb.appendLine("    Collections with schema diffs: $schemaDiffs")
        if (indexDiffs  > 0) sb.appendLine("    Collections with index diffs : $indexDiffs")
        sb.appendLine("    Total diff items: ${result.totalDiffCount}")
    }
    sb.appendLine("=".repeat(70))

    return sb.toString()
}

// ── HTML report ───────────────────────────────────────────────────────────────

/**
 * Generates a self-contained HTML comparison report with inline CSS and no external dependencies.
 *
 * The report is suitable for offline use (no CDN links) and can be opened directly in a browser.
 * Color coding:
 * - Red row    → removed (in A, not in B)
 * - Green row  → added (in B, not in A)
 * - Yellow row → type changed
 * - Blue row   → index option changed
 *
 * @param result the full comparison result to render
 * @param uriA display URI for database A (password already redacted)
 * @param uriB display URI for database B (password already redacted)
 * @return complete HTML document as a string
 */
fun generateHtmlReport(result: ComparisonResult, uriA: String, uriB: String): String {
    val ts = LocalDateTime.now().format(TIMESTAMP_FORMAT)
    val schemaDiffs = result.collections.count { !it.schemaDiff.isEmpty }
    val indexDiffs  = result.collections.count { !it.indexDiff.isEmpty }
    val missingCols = result.onlyInA.size + result.onlyInB.size

    return buildString {
        appendLine("""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>MongoDB Comparison Report — ${result.correlationId.take(8)}</title>
<style>
  body { font-family: 'Segoe UI', Arial, sans-serif; background:#f5f5f5; color:#222; margin:0; padding:20px; }
  h1 { color:#1a1a2e; }
  .meta { background:#fff; border-radius:6px; padding:16px; margin-bottom:16px; box-shadow:0 1px 3px rgba(0,0,0,.1); }
  .meta table { border-collapse:collapse; width:100%; }
  .meta td { padding:4px 12px; }
  .meta td:first-child { font-weight:600; width:140px; color:#555; }
  .summary { display:flex; gap:12px; flex-wrap:wrap; margin-bottom:16px; }
  .badge { border-radius:20px; padding:6px 16px; font-weight:700; font-size:14px; }
  .badge-ok   { background:#d4edda; color:#155724; }
  .badge-warn { background:#fff3cd; color:#856404; }
  .badge-err  { background:#f8d7da; color:#721c24; }
  .collection { background:#fff; border-radius:6px; margin-bottom:12px; box-shadow:0 1px 3px rgba(0,0,0,.1); overflow:hidden; }
  .col-header { background:#1a1a2e; color:#fff; padding:10px 16px; font-weight:700; display:flex; justify-content:space-between; align-items:center; cursor:pointer; }
  .col-header .ok-badge { font-size:12px; background:#28a745; border-radius:10px; padding:2px 10px; }
  .col-header .diff-badge { font-size:12px; background:#dc3545; border-radius:10px; padding:2px 10px; }
  .col-body { padding:12px 16px; }
  .section-title { font-weight:600; color:#444; margin:8px 0 4px; font-size:13px; text-transform:uppercase; letter-spacing:.5px; }
  table.diff { width:100%; border-collapse:collapse; font-size:13px; margin-bottom:8px; }
  table.diff th { background:#eee; text-align:left; padding:5px 8px; }
  table.diff td { padding:4px 8px; border-bottom:1px solid #f0f0f0; font-family:monospace; }
  tr.removed td { background:#fde8e8; }
  tr.added   td { background:#e8fde8; }
  tr.changed td { background:#fff9e6; }
  tr.option  td { background:#e8f0fe; }
  .label { font-weight:700; font-size:11px; border-radius:4px; padding:1px 6px; }
  .label-r { background:#dc3545; color:#fff; }
  .label-a { background:#28a745; color:#fff; }
  .label-c { background:#fd7e14; color:#fff; }
  .label-o { background:#6c757d; color:#fff; }
  .label-b { background:#0d6efd; color:#fff; }
  .ok-text { color:#28a745; font-weight:600; }
  .col-list { background:#fff; border-radius:6px; padding:12px 16px; margin-bottom:12px; box-shadow:0 1px 3px rgba(0,0,0,.1); }
  details > summary { list-style:none; }
  details > summary::-webkit-details-marker { display:none; }
</style>
</head>
<body>
<h1>MongoDB Comparison Report</h1>""")

        // Meta table
        appendLine("""<div class="meta"><table>
  <tr><td>Run ID</td><td>${result.correlationId}</td></tr>
  <tr><td>Generated</td><td>$ts</td></tr>
  <tr><td>Database A</td><td>${uriA.escapeHtml()}</td></tr>
  <tr><td>Database B</td><td>${uriB.escapeHtml()}</td></tr>
</table></div>""")

        // Summary badges
        appendLine("""<div class="summary">""")
        appendLine(badge("Collections in both", result.collections.size.toString(), "ok"))
        appendLine(badge("Only in A", result.onlyInA.size.toString(), if (result.onlyInA.isEmpty()) "ok" else "err"))
        appendLine(badge("Only in B", result.onlyInB.size.toString(), if (result.onlyInB.isEmpty()) "ok" else "warn"))
        appendLine(badge("Schema diffs", schemaDiffs.toString(), if (schemaDiffs == 0) "ok" else "err"))
        appendLine(badge("Index diffs",  indexDiffs.toString(),  if (indexDiffs  == 0) "ok" else "warn"))
        appendLine(badge("Total diffs",  result.totalDiffCount.toString(), if (result.totalDiffCount == 0) "ok" else "err"))
        appendLine("</div>")

        // Collections only in A / B
        if (result.onlyInA.isNotEmpty() || result.onlyInB.isNotEmpty() ||
            result.viewsOnlyInA.isNotEmpty() || result.viewsOnlyInB.isNotEmpty()) {
            appendLine("""<div class="col-list">""")
            appendLine("""<div class="section-title">Collections &amp; Views — existence diff</div>""")
            appendLine("""<table class="diff"><tr><th>Status</th><th>Name</th><th>Type</th></tr>""")
            result.onlyInA.forEach { appendLine("""<tr class="removed"><td><span class="label label-r">ONLY IN A</span></td><td>${it.escapeHtml()}</td><td>collection</td></tr>""") }
            result.onlyInB.forEach { appendLine("""<tr class="added"><td><span class="label label-a">ONLY IN B</span></td><td>${it.escapeHtml()}</td><td>collection</td></tr>""") }
            result.viewsOnlyInA.forEach { appendLine("""<tr class="removed"><td><span class="label label-r">ONLY IN A</span></td><td>${it.escapeHtml()}</td><td>view</td></tr>""") }
            result.viewsOnlyInB.forEach { appendLine("""<tr class="added"><td><span class="label label-a">ONLY IN B</span></td><td>${it.escapeHtml()}</td><td>view</td></tr>""") }
            appendLine("</table></div>")
        }

        // Per-collection detail
        for (col in result.collections.sortedBy { it.name }) {
            val hasDiff = !col.schemaDiff.isEmpty || !col.indexDiff.isEmpty
            appendLine("""<div class="collection">""")
            appendLine("""<details${if (hasDiff) " open" else ""}>""")
            appendLine("""<summary><div class="col-header">
  <span>${col.name.escapeHtml()}</span>
  <span class="${if (hasDiff) "diff" else "ok"}-badge">${if (hasDiff) "DIFF" else "OK"}</span>
</div></summary>""")
            appendLine("""<div class="col-body">""")
            appendLine("""<p style="color:#666;font-size:12px">Sampled: A=${col.sampleSizeA}/${col.totalDocsA} docs &nbsp;|&nbsp; B=${col.sampleSizeB}/${col.totalDocsB} docs</p>""")

            // Schema section
            appendLine("""<div class="section-title">Schema</div>""")
            val sd = col.schemaDiff
            if (sd.isEmpty) {
                appendLine("""<p class="ok-text">&#10003; No schema differences</p>""")
            } else {
                appendLine("""<table class="diff"><tr><th>Status</th><th>Field Path</th><th>Type(s) A</th><th>Type(s) B</th></tr>""")
                sd.fieldsOnlyInA.forEach { (path, types) ->
                    appendLine("""<tr class="removed"><td><span class="label label-r">REMOVED</span></td><td>${path.escapeHtml()}</td><td>${types.sorted().joinToString()}</td><td>—</td></tr>""")
                }
                sd.fieldsOnlyInB.forEach { (path, types) ->
                    appendLine("""<tr class="added"><td><span class="label label-a">ADDED</span></td><td>${path.escapeHtml()}</td><td>—</td><td>${types.sorted().joinToString()}</td></tr>""")
                }
                sd.typeChanges.forEach { (path, pair) ->
                    val (ta, tb) = pair
                    appendLine("""<tr class="changed"><td><span class="label label-c">CHANGED</span></td><td>${path.escapeHtml()}</td><td>${ta.sorted().joinToString()}</td><td>${tb.sorted().joinToString()}</td></tr>""")
                }
                appendLine("</table>")
            }

            // Indexes section
            appendLine("""<div class="section-title">Indexes</div>""")
            val id = col.indexDiff
            if (id.isEmpty) {
                appendLine("""<p class="ok-text">&#10003; No index differences</p>""")
            } else {
                appendLine("""<table class="diff"><tr><th>Status</th><th>Name</th><th>Type</th><th>Key</th><th>Options</th></tr>""")
                id.onlyInA.forEach { idx ->
                    appendLine("""<tr class="removed"><td><span class="label label-r">MISSING IN B</span></td><td>${idx.name.escapeHtml()}</td><td>${idx.indexType.name.lowercase()}</td><td>${idx.key.toString().escapeHtml()}</td><td>${indexOptions(idx)}</td></tr>""")
                }
                id.onlyInB.forEach { idx ->
                    appendLine("""<tr class="added"><td><span class="label label-a">ADDED IN B</span></td><td>${idx.name.escapeHtml()}</td><td>${idx.indexType.name.lowercase()}</td><td>${idx.key.toString().escapeHtml()}</td><td>${indexOptions(idx)}</td></tr>""")
                }
                id.optionChanges.forEach { (a, b) ->
                    appendLine("""<tr class="option"><td><span class="label label-b">OPTION DIFF</span></td><td>${a.name.escapeHtml()}</td><td>${a.indexType.name.lowercase()}</td><td>${a.key.toString().escapeHtml()}</td><td>${optionDiff(a, b)}</td></tr>""")
                }
                appendLine("</table>")
            }

            appendLine("</div></details></div>")
        }

        appendLine("</body></html>")
    }
}

// ── HTML helpers ──────────────────────────────────────────────────────────────

private fun String.escapeHtml(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

private fun badge(label: String, value: String, level: String): String =
    """<span class="badge badge-$level">$label: $value</span>"""

private fun indexOptions(idx: IndexSpec): String {
    val parts = mutableListOf<String>()
    if (idx.unique) parts += "unique"
    if (idx.sparse) parts += "sparse"
    if (idx.expireAfterSeconds != null) parts += "ttl=${idx.expireAfterSeconds}s"
    if (idx.partialFilter != null) parts += "partial"
    return if (parts.isEmpty()) "—" else parts.joinToString(", ")
}

private fun optionDiff(a: IndexSpec, b: IndexSpec): String {
    val parts = mutableListOf<String>()
    if (a.unique != b.unique) parts += "unique: ${a.unique}→${b.unique}"
    if (a.sparse != b.sparse) parts += "sparse: ${a.sparse}→${b.sparse}"
    if (a.expireAfterSeconds != b.expireAfterSeconds)
        parts += "ttl: ${a.expireAfterSeconds}s→${b.expireAfterSeconds}s"
    if (a.partialFilter != b.partialFilter)
        parts += "partialFilter changed"
    return parts.joinToString(", ")
}
