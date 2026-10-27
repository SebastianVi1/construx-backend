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
