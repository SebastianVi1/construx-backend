# Material prices

Each organization keeps its own catalog of materials and suppliers and records the prices it sees (ADR 0004). Prices are entered by hand or by CSV; nothing is scraped. Every path is under `/api/organizations/{organizationId}`. Each request checks, in this order: active membership (else `404`, the same as a missing organization), that the resource belongs to this organization (else `404`), then the permission through `DomainAuthorization` (else `403`). Both price actions have `ORGANIZATION` scope only. Seeded roles: Owner, Administrator and Finance hold `VIEW_PRICES` and `MANAGE_PRICES`; Member holds `VIEW_PRICES`; Worker holds neither.

Money is a JSON string with two decimals (`"245.50"`), in MXN including IVA. Dates are ISO (`2026-10-01`), timestamps are ISO instants.

## Materials

| Method | Path | Permission |
|---|---|---|
| GET | `/materials?query=&category=&includeArchived=` | `VIEW_PRICES` |
| GET | `/materials/{materialId}` | `VIEW_PRICES` |
| POST | `/materials` | `MANAGE_PRICES` |
| PATCH | `/materials/{materialId}` | `MANAGE_PRICES` |
| POST | `/materials/{materialId}/archive` | `MANAGE_PRICES` |

Body: `{ "name", "unit", "category", "brand" }`. `name`, `unit` and `category` are required on POST. Units: `PIECE`, `BAG`, `SQUARE_METER`, `CUBIC_METER`, `KILOGRAM`, `LITER`, `METER` (pieza, saco, m², m³, kg, litro, metro). Categories: `CEMENT_AGGREGATES`, `STEEL`, `WOOD`, `ELECTRICAL`, `PLUMBING`, `FINISHES`. An unknown unit or category returns `400`. Names and brands are trimmed and inner whitespace is collapsed. PATCH changes only the fields sent; `"brand": ""` clears the brand.

A material is unique per organization on name + unit + brand, ignoring case and extra whitespace. The rule is stored as a `normalized_key` column with a unique constraint. Creating or renaming into an existing material returns `409` with code `DUPLICATE_MATERIAL`. Archived materials still count, so an archived material blocks a new one with the same identity.

The list is ordered by name. `query` is a case-insensitive contains search over name and brand; `category` filters. Archived materials are hidden unless `includeArchived=true`. Each row is `{ id, name, unit, category, brand, archivedAt, latestPrices, cheapestSupplierId }`. `latestPrices` holds one entry per non-archived supplier: its newest observation (latest `observedOn`, then the latest recorded). Entries are ordered cheapest first and each has `cheapest: true` when its price equals the lowest one, so ties are all marked. `cheapestSupplierId` is the first entry's supplier, or `null` when there are no prices. The detail adds `history`: every observation of the material, archived suppliers included, newest first.

There is no delete. Archiving keeps the history readable and blocks new prices for the material (`409 ARCHIVED_MATERIAL`). Archiving twice keeps the first `archivedAt`.

## Suppliers

| Method | Path | Permission |
|---|---|---|
| GET | `/suppliers?includeArchived=` | `VIEW_PRICES` |
| POST | `/suppliers` | `MANAGE_PRICES` |
| PATCH | `/suppliers/{supplierId}` | `MANAGE_PRICES` |
| POST | `/suppliers/{supplierId}/archive` | `MANAGE_PRICES` |

Body: `{ "name", "branch", "website" }`, with only `name` required. A supplier is one store or branch. It is unique per organization on name + branch, ignoring case and extra whitespace; a duplicate returns `409 DUPLICATE_SUPPLIER`. `website`, when present, must be an `http` or `https` URL with a host (else `400 INVALID_ARGUMENT`). PATCH changes only the fields sent; an empty `branch` or `website` clears it. The list is ordered by name and hides archived suppliers unless `includeArchived=true`. An archived supplier drops out of `latestPrices` and cannot receive new prices (`409 ARCHIVED_SUPPLIER`).

## Price observations

| Method | Path | Permission |
|---|---|---|
| POST | `/price-observations` | `MANAGE_PRICES` |
| DELETE | `/price-observations/{observationId}` | `MANAGE_PRICES` |

Body: `{ "materialId", "supplierId", "price", "observedOn", "sku" }`, with all fields except `sku` required. `price` may be a JSON number or a decimal string. It must be greater than zero, have at most two decimals and fit `numeric(12,2)`, else `400 INVALID_ARGUMENT`. `observedOn` cannot be after today in UTC, which is ahead of every Mexican time zone. A material or supplier of another organization returns `404`. The response is `{ id, materialId, supplierId, supplierName, supplierBranch, price, observedOn, sku, source, createdAt }`. `source` is always `MANUAL` here; CSV import sets `CSV`, and a `source` field in the body is ignored.

Observations are immutable. There is no PUT or PATCH route (`405`): fix a mistake by deleting the observation (`204`) and recording a new one.

## CSV import

| Method | Path | Permission |
|---|---|---|
| POST | `/price-observations/import?dryRun=true\|false` | `MANAGE_PRICES` |

The request is `multipart/form-data` with one part `file`: UTF-8 CSV, optional BOM, `,` separator, RFC 4180 quoting (quoted fields may hold commas, `""` quotes and line breaks), CRLF or LF. `dryRun` defaults to `true`. The first line must be the header `material,unit,category,brand,supplier,branch,price,observed_on,sku` (exact order, case-insensitive). Blank lines are skipped. A wrong header, a file with no rows, more than 2000 rows, more than 1 MB, text that is not UTF-8, unbalanced quotes or a missing `file` part answer `400 VALIDATION_FAILED` with a readable `detail`.

Each row is checked with the same model rules as manual entry:
- `unit` and `category` take the enum codes (`BAG`, `CEMENT_AGGREGATES`, ...) or the Spanish labels, ignoring case and accents: `pieza`, `saco`, `m²`/`m2`, `m³`/`m3`, `kg`, `litro`, `metro`; `cemento y agregados`, `acero`, `madera`, `eléctrico`, `plomería`, `acabados`.
- `price` is plain digits with an optional `.` and at most two decimals, greater than zero; `$` and thousands separators are invalid. `observed_on` is `YYYY-MM-DD`, not after today in UTC.
- The material is matched on the same normalized name + unit + brand key as manual creation, the supplier on name + branch. Missing ones are created (the CSV `category` is used only for a new material; suppliers get no website). Rows that name the same new material or supplier share one new row. An archived match is a row error.

Response (200 for a dry run and for a successful import):

```json
{ "valid": true, "imported": false,
  "summary": { "rows": 1, "newMaterials": 0, "newSuppliers": 1, "observations": 1 },
  "rows": [ { "line": 2, "material": "Cemento gris 50 kg", "supplier": "Home Depot", "branch": "Tlalpan",
    "price": "245.50", "observedOn": "2026-10-20", "newMaterial": false, "newSupplier": true, "errors": [] } ] }
```

`line` is the physical line where the row starts (the header is line 1). `material`, `supplier` and `branch` show the matched catalog names, or the cleaned input. `price` has two decimals when valid and echoes the input otherwise. `newMaterial` / `newSupplier` are true on every row whose material or supplier is not in the catalog yet; `summary` counts each new one once, over valid rows. `errors` lists `{ field, code }` in column order: `field` is a column name or `row`; `code` is `REQUIRED`, `INVALID`, `FUTURE_DATE`, `TOO_MANY_DECIMALS`, `ARCHIVED_MATERIAL`, `ARCHIVED_SUPPLIER` or `COLUMNS` (wrong number of fields). `observations` counts valid rows.

The import is all or nothing in one transaction. `dryRun=false` writes only when every row is valid: the observations get `source = CSV` and `imported` is `true`. With any invalid row it answers `422` with the same body (`valid: false`, `imported: false`) and writes nothing.

Note: `spring.servlet.multipart.max-file-size` and `max-request-size` are set to 2 MB so files between 1 and 2 MB reach this endpoint and get the `400` above; anything over 2 MB is refused by the multipart resolver with `413` before the endpoint runs.
