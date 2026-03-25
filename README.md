# compare-dbs

Compares schema, field types, and indexes between two MongoDB databases.

## Build

```bash
mvn package
```

Produces `target/compare-dbs.jar`.

## Usage

```bash
java -jar target/compare-dbs.jar \
  --uri-a "mongodb://localhost:27017/db_old" \
  --uri-b "mongodb://localhost:27017/db_new"
```

## Options

| Option | Default | Description |
|--------|---------|-------------|
| `--uri-a` | required | MongoDB URI for database A, including database name |
| `--uri-b` | required | MongoDB URI for database B, including database name |
| `--sample-size` | `200` | Max documents to sample per collection |
| `--output` | stdout | Write HTML report to this file instead of plain text |
| `--collections` | all | Comma-separated list of collections to compare |
| `--parallelism` | `4` | Max collections compared in parallel |
| `--save-report` | off | Save result to `_comparisonReports` collection in database A |

## Examples

Print a text diff to stdout:
```bash
java -jar compare-dbs.jar \
  --uri-a "mongodb://user:pass@host:27017/prod" \
  --uri-b "mongodb://user:pass@host:27017/staging"
```

Generate an HTML report for specific collections:
```bash
java -jar compare-dbs.jar \
  --uri-a "mongodb://localhost:27017/prod" \
  --uri-b "mongodb://localhost:27017/staging" \
  --collections "users,orders,products" \
  --output report.html
```

## Exit codes

| Code | Meaning |
|------|---------|
| `0` | No differences found |
| `1` | Differences detected |
| `2` | Connection or configuration error |
