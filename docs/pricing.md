# Prices: the cost catalog (v1 contract)

Status: proposed, 2026-11-15. Author: Lead Architect. Decisions: ADR 0008 (the catalog is the licensee's own import), ADR 0009 (labor cost accrues per Check-in day). Vocabulary in `GLOSSARY.md`, section "Prices". This document replaces the former "Material prices" contract (ADR 0004, kept for history).

Each organization has exactly one **Catalog** of **Resources** (Materials, Labor categories, Gangs, Equipment rates, Work items), with per-organization **Units** and **Families**, **Suppliers**, a **Cost history** per Resource, optional **FASAR parameters**, and **Catalog imports** from the PU Win+ Excel export. On the Site side, a person on a Site may have a **Labor rate**, and the Site's finance shows an **Accrued labor cost** computed from Check-ins. Module `modules/pricing` for the catalog; `modules/project` for the labor rate on a `ProjectAccess`; `modules/finance` for the accrual and catalog expenses. The former `Material`, `PriceObservation`, `MaterialUnit` and `MaterialCategory` disappear (see "Migration of existing data").

Items marked **(architect's choice)** are details the product decisions left open and are decided here; change them through the Architect.

## 0. Conventions

- Every path is under `/api/organizations/{organizationId}`, or under `/api/organizations/{organizationId}/projects/{projectId}` for the Site side. Checks run in this order, as in `v1-api.md` §0.1: not an active member or a Site of another organization -> `404`; every referenced row loaded with an organization-filtered query (another tenant's id -> `404`); then `scope.require(action)` on the `OrgScope` / `SiteScope` -> `403`. Services, never controllers, call `require`. List queries filter by `organization_id` in SQL.
- Permissions: no new `PermissionAction`. Catalog reads `VIEW_PRICES`; catalog writes, costs, FASAR and imports `MANAGE_PRICES` (both `ORGANIZATION` scope only, as today). Per-person costs and the accrual `VIEW_BUDGET` (`ORGANIZATION` only). Setting a Labor rate `MANAGE_PROJECT` on the Site (`ORGANIZATION`, or `PROJECT` with access to the Site). Recording an expense from the catalog follows the existing expense rules (`MANAGE_BUDGET`, or the task's Lead).
- Money is a JSON string with exactly two decimals (`"245.50"`), MXN, as the current pricing API. Costs from the catalog are costs to the company (Neodata `Costo0`, direct cost); whether they include IVA depends on how the licensee keeps its catalog and is not interpreted. Quantities are JSON strings with up to six decimals (`"0.0300"`); factors (FSR, FSBC, Ps, contribution rates) JSON strings with up to eight decimals. Dates `YYYY-MM-DD`, instants ISO-8601 UTC.
- Pagination **(architect's choice)**: `?page=0&size=50` (`size` 1..200, default 50); response `{ "content": [...], "page": 0, "size": 50, "totalElements": 34869, "totalPages": 698 }`. `sort=<field>,<asc|desc>`, one field, fields listed per endpoint; an unknown field -> `400 VALIDATION_FAILED`. Lists not marked "paged" are plain arrays.
- Errors: RFC 7807 problem with `code`. Besides the global codes (`VALIDATION_FAILED`, `INVALID_ARGUMENT`, `FORBIDDEN`, `NOT_FOUND`, `STATE_CONFLICT`, `DATA_CONFLICT`), this feature uses the codes listed under each endpoint, all raised through the existing `PricingExceptionHandler` pattern (`PricingConflictException` -> `409`, `PricingRuleException` -> `400`).
- Text fields are trimmed and inner whitespace collapsed (`PricingText`). Codes are trimmed and upper-cased, never otherwise normalized **(architect's choice)**: `MO011`, `301-ARE-0101`, `1:4`, `%MO1` are all valid codes.

## 1. Data model (Flyway V38 and later)

Only `V38` is fixed: it is the catalog core (B1 in the delivery plan: tables 1.1 to 1.6 without the import-related columns, the supplier changes of 1.4, and the data migration of section 7). Every later table or column belongs to the migration of its slice (see the delivery plan in the architect's ticket) and takes the next free `V<n>` at implementation time; where this document says "its slice's migration" that is what is meant.

All tables carry `organization_id uuid not null references organization(id)` and an index on it unless stated. The Catalog is identified by the organization (one row per organization), so child tables reference `organization_id`, not a `catalog_id` **(architect's choice: one FK less, and every tenant filter stays `organization_id = ?`)**.

### 1.1 `catalog`

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `organization_id` | uuid not null, **unique** | one Catalog per organization |
| `currency` | varchar(3) not null default `'MXN'` | only `MXN` in v1 |
| `created_at` | timestamptz not null | |
| `last_import_id` | uuid null, FK `catalog_import` (added by the import slice's migration) | last `DONE` import |

Created implicitly by the first write (first import or first hand-made Resource, Unit, Family or Supplier) and by the V38 data migration for organizations that already have `material` or `supplier` rows. A read of a missing Catalog answers with empty counts, never `404`.

### 1.2 `unit`

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `organization_id` | uuid not null | |
| `code` | varchar(20) not null | Neodata `Unidades.Unidad` (`M2`, `JOR`, `HOR`, `%`) |
| `description` | varchar(120) null | `"Sin descripción"` imports as null |
| `source` | varchar(20) not null | `SEED`, `MANUAL`, `NEODATA_CONSTRUBASE` |
| `archived_at` | timestamptz null | |

Unique `(organization_id, code)`. Seeded from the old enum for every organization with materials: `PIECE`->`PZA` "Pieza", `BAG`->`SACO` "Saco", `SQUARE_METER`->`M2` "Metro cuadrado", `CUBIC_METER`->`M3` "Metro cúbico", `KILOGRAM`->`KG` "Kilogramo", `LITER`->`LT` "Litro", `METER`->`M` "Metro". Neodata spelling variants (`HR`, `HRS`, `HOR`) import as distinct Units; merging aliases is phase 2 **(architect's choice: merging would rewrite codes the file keeps using)**.

### 1.3 `family`

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `organization_id` | uuid not null | |
| `code` | varchar(40) not null | Neodata `Familias.Familia` |
| `name` | varchar(160) not null | |
| `source` | varchar(20) not null | `SEED`, `MANUAL`, `NEODATA_CONSTRUBASE` |
| `archived_at` | timestamptz null | |

Unique `(organization_id, code)`. Seeded from the old enum: `CEMENT_AGGREGATES` "Cemento y agregados", `STEEL` "Acero", `WOOD` "Madera", `ELECTRICAL` "Eléctrico", `PLUMBING` "Plomería", `FINISHES` "Acabados" (code = old enum value, so the migration is a straight join).

### 1.4 `supplier` (changed)

Kept with its columns (`name`, `branch`, `website`, `normalized_key`, `archived_at`). Added:

| Column | Type | Notes |
|---|---|---|
| `code` | varchar(40) null | Neodata `Proveedores.Proveedor`; unique `(organization_id, code)` where not null (partial index) |
| `rfc` | varchar(13) null | |
| `city`, `state`, `address`, `neighborhood`, `postal_code` | varchar(160/80/255/120/10) null | `Ciudad`, `Estado`, `Direccion`, `Colonia`, `CodigoPostal` |
| `phone`, `mobile`, `fax` | varchar(40) null | |
| `email` | varchar(160) null | |
| `contact` | varchar(160) null | |
| `notes` | varchar(500) null | `Observaciones` |
| `is_default` | boolean not null default false | `ProveedorDefault` |
| `source` | varchar(20) not null default `'MANUAL'` | `MANUAL`, `CSV`, `NEODATA_CONSTRUBASE` |

Imported suppliers match by `code`; hand-made ones keep the name + branch uniqueness. Rule (decided): an imported supplier whose `RazonSocial` collides with an existing hand-made name + branch key is created as a second row with `branch` = its code; the import never merges by name and never fails on a supplier name.

### 1.5 `resource`

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `organization_id` | uuid not null | |
| `code` | varchar(40) not null | unique `(organization_id, code)` |
| `type` | varchar(10) not null | `MATERIAL`, `LABOR`, `EQUIPMENT`, `WORK_ITEM` (Neodata Tipo 1, 2, 3, 4) |
| `name` | varchar(160) not null | Neodata `Descripcion` (short) |
| `description` | text null | `Descripcion completa` |
| `unit_id` | uuid not null FK `unit` | |
| `family_id` | uuid null FK `family` | |
| `supplier_id` | uuid null FK `supplier` | |
| `brand` | varchar(80) null | kept from Material; never imported |
| `is_percentage` | boolean not null default false | Neodata `%` = 1 |
| `percentage_base` | smallint null | Neodata `Tipo %`; only `2` (labor amount) is supported |
| `is_composite` | boolean not null default false | has Unit price analysis lines; cost is derived |
| `is_hourly_cost` | boolean not null default false | `EsCostoHorario` |
| `discontinued` | boolean not null default false | `InsumoDescontinuado` |
| `reference`, `sap_code`, `technical_sheet`, `grouper` | varchar(120/40/255/40) null | `Referencia`, `CodigoSap`, `FichaTecnica`, `Agrupador` (`0` -> null) |
| `procedure` | text null | `Procedimiento` |
| `source` | varchar(20) not null | `MANUAL`, `CSV`, `NEODATA_CONSTRUBASE` |
| `current_cost` | numeric(14,2) null | materialized: latest history entry for simple Resources, derived for composites; null until any cost exists |
| `cost_updated_at` | timestamptz null | |
| `imported_cost` | numeric(14,2) null | file `Costo0` as imported (verification for composites) |
| `imported_cost_on` | date null | file `Fecha0`; `1900-01-01` -> null |
| `missing_from_import_at` | timestamptz null | set when a previous import had the code and the latest one does not; cleared when it reappears |
| `archived_at` | timestamptz null | |
| `created_at`, `updated_at` | timestamptz not null | |

Indexes: `(organization_id, type, code)`, `(organization_id, lower(name))`. A **Gang** is `type = LABOR` with `is_composite = true` (cuadrilla: lines of Labor categories). **Simple** Resource = `is_composite = false`.

Decimals **(architect's choice)**: costs are money and stored with 2 decimals (`numeric(14,2)`), matching the existing expense and budget columns and Neodata's `decimalesredondeo`; a file cost with more decimals is rounded half-up and counted in the import report (`roundedCosts`). Quantities of analyses are `numeric(18,6)`: Neodata rounds concept quantities to 4 (`decimalesredondeoconceptos`) and percentage fractions to more, so 6 keeps every file value exactly while staying far from the explosion precision (8) that only phase 2 needs.

### 1.6 `resource_cost_history`

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `organization_id` | uuid not null | |
| `resource_id` | uuid not null FK `resource` | simple Resources only (composites have no history; their cost is a function of their components') |
| `amount` | numeric(14,2) not null, `>= 0` | zero allowed (a free Resource or a `%` placeholder) |
| `effective_from` | timestamptz not null | always the server time of the write, except in the V38 migration |
| `source` | varchar(20) not null | `MANUAL`, `CSV`, `NEODATA_CONSTRUBASE`, `FASAR` (wage recalculation) |
| `recorded_by_membership_id` | uuid null FK `organization_membership` | null for imports run by the job and for migrated rows without an author |
| `import_id` | uuid null FK `catalog_import` (added by the import slice's migration) | |
| `supplier_id` | uuid null FK `supplier` | kept from migrated observations; optional on manual entries |
| `sku` | varchar(64) null | migrated observations |
| `note` | varchar(160) null | manual entries |

Index `(resource_id, effective_from desc)`. Rows are immutable: no update, no delete endpoint (ADR 0009). The **current cost** of a simple Resource is the row with the greatest `effective_from`; the **cost on day D** is the row with the greatest `effective_from <= end of D` (section 6).

### 1.7 `unit_price_analysis_line`

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `organization_id` | uuid not null | |
| `parent_resource_id` | uuid not null FK `resource` | the Work item, Gang or composite insumo (`Matrices.CodigoM`) |
| `component_resource_id` | uuid not null FK `resource` | `Matrices.CodigoI` |
| `line_no` | int not null | `Renglon`; unique `(parent_resource_id, line_no)` |
| `operator` | char(1) not null | `*` consumption, `/` output (rendimiento) |
| `quantity` | numeric(18,6) not null, `> 0` | |
| `expression` | varchar(120) null | `Expresion` |
| `amount` | numeric(14,2) null | materialized line amount from the last derivation (display only) |

Index `(component_resource_id)` for "where used" and for recomputation. Lines are written only by the import in v1 (editing analyses is phase 2).

### 1.8 `labor_category_detail`

One row per simple `LABOR` Resource (Gangs have none). PK `resource_id`.

| Column | Type | Notes |
|---|---|---|
| `resource_id` | uuid PK FK `resource` | |
| `organization_id` | uuid not null | |
| `nominal_wage` | numeric(12,2) not null, `>= 0` | Sn, `SalarioBaseM0` |
| `fsr` | numeric(12,8) not null, `>= 1` | the factor in use |
| `fsr_source` | varchar(10) not null | `IMPORTED` (file), `MANUAL` (typed), `COMPUTED` (from FASAR parameters) |
| `fsbc` | numeric(12,8) null | factor salario base de cotización in use |
| `sbc` | numeric(12,2) null | salario base de cotización in use |
| `imported_fsr`, `imported_real_wage`, `imported_fsbc`, `imported_sbc` | numeric(12,8) / (12,2) / (12,8) / (12,2) null | file values kept for verification |
| `per_diem`, `meals_lodging`, `funeral_expenses`, `other_benefits` | numeric(12,2) not null default 0 | `ViaticosM0`, `AlimentacionYHospedajeM0`, `GastosDeSepelioM0`, `OtrasPrestacionesM0` |
| `fasar_type` | smallint null | `TipoFasarM0` |
| `fsr_mismatch` | boolean not null default false | last verification result (section 5.4) |

Real wage (the Resource's `current_cost`) = `round(nominal_wage * fsr, 2) + per_diem + meals_lodging + funeral_expenses + other_benefits` **(architect's choice: the four extra benefits are daily amounts the company pays and Neodata adds them to the daily labor cost; the verification against `SalarioRealM0` tells whether the file agrees, and a mismatch is reported, not hidden)**. Every change of the real wage writes a `resource_cost_history` row.

### 1.9 `equipment_rate_detail`, `equipment_operation_line`, `equipment_consumption_line`, `equipment_standby_percentage`

`equipment_rate_detail`: one row per `EQUIPMENT` Resource that appears in `DetalleMaquinaria`. PK `resource_id`, `organization_id`, and every file column stored as data, named in English: `power_unit varchar(10)`, `fixed_charge_code`, `acquisition_value_code`, `standby_code`, `reserve_code`, `tires_code`, `special_parts_code`, `fuel_code`, `lubricant_code`, `other_energy_code` (all `varchar(40)` null, Resource codes kept as text because they may point at rows of any type **(architect's choice)**), `tires_life_hours`, `special_parts_life_hours`, `economic_life_hours`, `hours_per_year`, `oil_change_hours` (`numeric(12,2)` null), `calculation_type smallint`, `power numeric(12,4)`, `operation_factor numeric(10,6)`, `fuel_type smallint`, `fuel_factor`, `other_energy_factor`, `lubricant_factor` (`numeric(12,8)`), `crankcase_capacity numeric(12,4)`, `salvage_pct`, `insurance_pct`, `maintenance_factor`, `interest_rate` (`numeric(10,6)`, fractions), `brand`, `model`, `serial_number`, `capacity`, `location`, `useful_life`, `owner`, `availability` (`varchar(120)` null), `commercial_value numeric(14,2)`, `fuel_qty_per_hour`, `lubricant_qty_per_hour` (`numeric(12,6)`). `Costo1/2` and `TasaInteresM1/M2` are ignored.

`equipment_operation_line` (`OperacionCH`) and `equipment_consumption_line` (`ConsumosCH`): `id`, `organization_id`, `equipment_resource_id` FK, `component_resource_id` FK, `operator char(1)`, `quantity numeric(18,6)`, `line_no int`. Unique `(equipment_resource_id, component_resource_id)`.

`equipment_standby_percentage` (`PorcentajesCh`, catalog-level): `id`, `organization_id`, `charge varchar(40)`, `standby_pct numeric(8,6)`, `reserve_pct numeric(8,6)`; unique `(organization_id, charge)`.

Hourly cost in v1 is the imported `Costo0` (a simple `EQUIPMENT` Resource with a normal cost history), editable like any simple Resource. Nothing is recomputed from these tables (D8); the UI warns that changing an operator's wage does not change hourly costs.

### 1.10 `fasar_parameter_set` and `fasar_contribution`

One `fasar_parameter_set` per organization (`organization_id` unique), replaced in place by `PUT /fasar` and by a FASAR workbook import.

| Column | Type | Notes |
|---|---|---|
| `id`, `organization_id` | | |
| `year` | smallint null | `AÑO PARA CAPTURA DE FACTORES` |
| `uma` | numeric(10,4) not null | daily UMA in pesos (`uma_cap`) |
| `minimum_wage` | numeric(10,2) not null | |
| `sum_employer_and_worker` | boolean not null default true | `Patron + Trabajado` = 1 |
| `calendar_days` | numeric(7,3) not null | DICAL |
| `sundays`, `vacation_days`, `monday_days`, `holidays`, `weather_days`, `custom_days`, `leave_sick_days`, `union_days` | numeric(7,3) not null default 0 | DIDOM, DIVAC, DILUN, DIFEO, DIPEC, DIPCO, DIPEN, DISIN (days not worked) |
| `worked_sundays` | numeric(7,3) not null default 0 | the day base of the Sunday premium (Sundays actually worked); `sundays` above are rest days and do not earn the premium |
| `bonus_days` | numeric(7,3) not null | aguinaldo (DIAGI) |
| `vacation_premium_pct`, `sunday_premium_pct` | numeric(10,8) not null | fractions (0.25) |
| `other_charges_pct` | numeric(10,8) not null default 0 | OC |
| `excess_threshold_uma` | numeric(6,2) not null default 3 | "excedente de tres UMA" |
| `sbc_cap_uma` | numeric(6,2) not null default 25 | `Limite superior grupo 2` |
| `factor_rounding` | smallint not null default 6 | `REDONDEO PARA FACTORES` |
| `complete` | boolean not null | false when built only from `N_Campos Generales` (section 5.3) |
| `source` | varchar(20) not null | `FASAR_FILE`, `GENERAL_FIELDS`, `MANUAL` |
| `version` | int not null | +1 on every replace |
| `updated_at`, `updated_by_membership_id` | | |

`fasar_contribution`: `set_id` FK, `concept varchar(40)`, `employer_rate numeric(10,8)`, `worker_rate numeric(10,8)`, unique `(set_id, concept)`. The twelve fixed concepts: `RISK`, `FIXED_FEE`, `EXCESS_3_UMA`, `CASH_BENEFITS`, `PENSIONERS_MEDICAL`, `DISABILITY_LIFE`, `RETIREMENT`, `UNEMPLOYMENT_OLD_AGE`, `DAYCARE`, `INFONAVIT`, `PAYROLL_TAX`, `OTHER_CHARGES` (Neodata `a)TablaFactores` rows I to V, INFONAVIT, ISN, Otros cargos). All must be present on a complete set.

### 1.11 `catalog_import`

| Column | Type | Notes |
|---|---|---|
| `id`, `organization_id` | | |
| `status` | varchar(12) not null | `PENDING`, `VALIDATING`, `VALIDATED`, `IMPORTING`, `DONE`, `FAILED`, `CANCELLED` |
| `file_key`, `file_name`, `file_size`, `file_sha256` | varchar(255) / varchar(255) / bigint / char(64) | Presupuesto workbook; sha-256 computed server-side after upload |
| `fasar_file_key`, `fasar_file_name`, `fasar_file_size`, `fasar_file_sha256` | same, null | optional FASAR workbook |
| `uploaded_by_membership_id` | uuid not null FK | |
| `created_at`, `validated_at`, `confirmed_at`, `finished_at` | timestamptz | |
| `licence_accepted_at`, `licence_accepted_by_membership_id` | null until confirm | |
| `licence_statement` | varchar(20) null | `LICENCE_V1` (the statement text key, see 4.4) |
| `summary` | jsonb null | dry-run summary (4.5) |
| `failure_code`, `failure_message`, `failure_sheet`, `failure_row`, `failure_column` | varchar(40) / varchar(500) / varchar(40) / int / varchar(40) null | |

`CANCELLED` is an **(architect's choice)** terminal state for a `PENDING` or `VALIDATED` job the user abandons; a `VALIDATED` job also becomes `CANCELLED` automatically after 24 hours so it stops blocking new imports. At most one job per organization in `PENDING`, `VALIDATING`, `VALIDATED` or `IMPORTING` (partial unique index on `organization_id` where status in those four).

### 1.12 Changes to other modules

`expense` (`modules/finance`, migration of the expense-from-catalog slice): `resource_id uuid null FK resource`, `quantity numeric(18,6) null`, `unit_cost numeric(14,2) null`; check `((resource_id is null) = (quantity is null)) and ((resource_id is null) = (unit_cost is null))`. The snapshot never changes after creation.

`labor_rate_assignment` (`modules/project`, migration of the labor-rate slice): the Labor rate of a person on a Site, historized.

| Column | Type | Notes |
|---|---|---|
| `id`, `organization_id` | | |
| `project_id` | uuid not null FK `project` | |
| `membership_id` | uuid not null FK `organization_membership` | keyed by membership, not by `project_access`, so removing someone from the Site keeps the accrual history **(architect's choice)** |
| `kind` | varchar(10) not null | `CATALOG`, `FIXED`, `NONE` |
| `labor_category_resource_id` | uuid null FK `resource` | required for `CATALOG`, a simple `LABOR` Resource of the organization |
| `daily_amount` | numeric(12,2) null, `>= 0` | required for `FIXED` |
| `effective_from` | timestamptz not null | server time of the write |
| `set_by_membership_id` | uuid not null FK | |

Index `(project_id, membership_id, effective_from desc)`. Rows are immutable; the current rate is the latest row. `ProjectAccess` itself gains no column.

`equipment` (`modules/crew`, migration of the expense-from-catalog slice): `equipment_rate_resource_id uuid null FK resource`.

Module seams (`ModuleBoundariesTest`, ArchUnit). Check-ins live in `modules/attendance` (`CheckIn`, `AttendanceService`), not in `project`. The edges this feature needs, all through service methods, never by sharing a repository:
- `project -> pricing`: labor category lookup for `PUT .../labor-rate` (`ResourceService`).
- `finance -> pricing`: the Resource and its current cost for an expense from the catalog; the cost history of a labor category for the accrual.
- `finance -> project`: `labor_rate_assignment` rows of a Site (through `ProjectService`/a `LaborRateService` in `project`).
- `finance -> attendance`: person-days of a Site, e.g. `AttendanceService.personDays(projectId, from, to)` returning distinct `(membershipId, day)` of non-removed Check-ins.
- `crew -> pricing`: the Equipment rate behind a piece of equipment.
`LaborAccrual` (the rule of §6.2) lives in `finance` and receives plain value lists (person-days, rate rows, cost history rows) and returns the per-day amounts, so it is unit-tested without JPA; `LaborCostService` gathers the inputs through the services above.

## 2. Catalog endpoints (`modules/pricing`)

Base `/api/organizations/{organizationId}`.

### 2.1 Catalog summary

| Method | Path | Permission |
|---|---|---|
| GET | `/catalog` | `VIEW_PRICES` |

```json
{ "exists": true, "currency": "MXN",
  "counts": { "MATERIAL": 24375, "LABOR": 85, "EQUIPMENT": 145, "WORK_ITEM": 10264, "suppliers": 9, "units": 51, "families": 17 },
  "missingFromImport": 12,
  "lastImport": { "id": "uuid", "status": "DONE", "finishedAt": "2026-11-15T18:02:11Z", "fileName": "..._Presupuesto.xlsx" },
  "fasar": { "status": "COMPLETE", "year": 2019, "uma": "86.8800", "minimumWage": "123.22", "updatedAt": "..." },
  "activeImport": null }
```
`exists: false` with zero counts and nulls when the organization has no Catalog yet. `fasar.status`: `NONE`, `PARTIAL` (from `N_Campos Generales`, not recomputable), `COMPLETE`. `activeImport` is the job in `PENDING`/`VALIDATING`/`VALIDATED`/`IMPORTING`, as the `ImportResponse` of 4.3, or null.

### 2.2 Units and Families

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/units?includeArchived=` | `VIEW_PRICES` | array ordered by `code`; `{ id, code, description, source, archivedAt, resourceCount }` |
| POST | `/units` | `MANAGE_PRICES` | `{ code (1..20), description? (..120) }`. 201. Duplicate -> `409 DUPLICATE_UNIT` |
| PATCH | `/units/{unitId}` | `MANAGE_PRICES` | `{ description? }`; `""` clears. Code is immutable. 200 |
| POST | `/units/{unitId}/archive` | `MANAGE_PRICES` | idempotent; archived units cannot be chosen for new Resources (`409 ARCHIVED_UNIT`) |
| GET | `/families?includeArchived=` | `VIEW_PRICES` | array ordered by `code`; `{ id, code, name, source, archivedAt, resourceCount }` |
| POST | `/families` | `MANAGE_PRICES` | `{ code (1..40), name (1..160) }`. 201. Duplicate -> `409 DUPLICATE_FAMILY` |
| PATCH | `/families/{familyId}` | `MANAGE_PRICES` | `{ name? }`. 200 |
| POST | `/families/{familyId}/archive` | `MANAGE_PRICES` | idempotent; `409 ARCHIVED_FAMILY` on use |

### 2.3 Suppliers (kept, extended)

Existing routes and rules stand (`GET /suppliers`, `POST`, `PATCH`, `POST .../archive`; name + branch uniqueness, `DUPLICATE_SUPPLIER`, `ARCHIVED_SUPPLIER`, website validation). Body and response gain the optional fields of 1.4: `code` (read-only for imported suppliers, `409 IMPORTED_FIELD` if patched), `rfc`, `city`, `state`, `address`, `neighborhood`, `postalCode`, `phone`, `mobile`, `fax`, `email`, `contact`, `notes`, `isDefault`, `source`. `GET /suppliers` adds `query` (contains over name, code, contact) and `resourceCount`.

### 2.4 Resources

`ResourceResponse` (list row):
```json
{ "id": "uuid", "code": "301-ARE-0101", "type": "MATERIAL", "name": "Arena de mina", "unit": { "id": "uuid", "code": "M3" },
  "family": { "id": "uuid", "code": "301", "name": "AGREGADOS" }, "supplier": { "id": "uuid", "name": "VARIOS" }, "brand": null,
  "isPercentage": false, "isComposite": false, "isHourlyCost": false, "discontinued": false,
  "currentCost": "290.00", "costUpdatedAt": "2026-11-15T18:02:11Z", "source": "NEODATA_CONSTRUBASE",
  "missingFromImportAt": null, "archivedAt": null }
```

`ResourceDetailResponse` = `ResourceResponse` + `description`, `reference`, `sapCode`, `technicalSheet`, `procedure`, `grouper`, `importedCost`, `importedCostOn`, `createdAt`, `updatedAt`, plus one of:
- `laborDetail` (simple `LABOR`): `{ nominalWage, fsr, fsrSource, fsbc, sbc, perDiem, mealsLodging, funeralExpenses, otherBenefits, fasarType, imported: { fsr, realWage, fsbc, sbc }, fsrMismatch, realWage }`; `realWage` equals `currentCost`.
- `equipmentDetail` (`EQUIPMENT` with a detail row): every 1.9 column in camelCase, `operationLines: [{ lineNo, component: { id, code, name, type, unit }, operator, quantity }]`, `consumptionLines: [...]`, `standbyPercentages` is not here (see 2.6).
- `analysis` (composites of any type): `{ derivedCost, importedCost, mismatch, laborSubtotal, lines: [{ lineNo, component: { id, code, name, type, unitCode, isPercentage, currentCost }, operator, quantity, expression, amount }] }`, lines ordered by `lineNo`. `mismatch` per 3.3.
- `history` (simple Resources): the last 100 Cost history entries newest first **(architect's choice: hand edits are rare; the full list is at 2.5)**: `{ id, amount, effectiveFrom, source, recordedBy: { membershipId, fullName } | null, importId, supplier: { id, name } | null, sku, note }`.
- `usedIn`: `{ count, sample: [{ id, code, name, type }] }` with up to 10 parents using this Resource **(architect's choice)**.

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/resources` (paged) | `VIEW_PRICES` | Query: `type` (repeatable), `query` (case-insensitive contains over `code`, `name`, `description`), `familyId`, `unitId`, `supplierId`, `composite` (`true`/`false`; `type=LABOR&composite=true` = Gangs), `includeArchived` (default false), `missingFromImport` (`true` only those flagged), `discontinued` (default: both). Sort fields: `code` (default asc), `name`, `currentCost`, `costUpdatedAt`. 200 page of `ResourceResponse`. |
| GET | `/resources/{resourceId}` | `VIEW_PRICES` | also archived. 200 `ResourceDetailResponse`. |
| POST | `/resources` | `MANAGE_PRICES` | Creates a **simple** `MATERIAL`, `LABOR` or `EQUIPMENT` Resource (4.1 below). 201 `ResourceDetailResponse`. `type: WORK_ITEM` or `isComposite` -> `400 VALIDATION_FAILED` (phase 2). |
| PATCH | `/resources/{resourceId}` | `MANAGE_PRICES` | `{ name?, description?, unitId?, familyId?, supplierId?, brand?, discontinued?, reference?, sapCode?, technicalSheet?, procedure?, laborDetail?: { nominalWage?, fsr?, perDiem?, mealsLodging?, funeralExpenses?, otherBenefits? } }`. `""` clears an optional text. Allowed on every type, including imported ones (the next import overwrites them, 4.6). `laborDetail` only on simple `LABOR` (`400`). A wage change writes a history row and recomputes derived costs. 200. |
| POST | `/resources/{resourceId}/archive` | `MANAGE_PRICES` | idempotent; archived Resources are hidden from pickers and lists by default but keep deriving costs for their parents and keep rating labor days. 200. |
| POST | `/resources/{resourceId}/costs` | `MANAGE_PRICES` | `{ amount, supplierId?, sku?, note? }` -> one history row `source MANUAL`, `effectiveFrom` = now. Composite -> `409 DERIVED_COST`; simple `LABOR` -> `409 LABOR_COST_FROM_WAGE` (change `nominalWage` or the FASAR parameters instead); archived -> `409 ARCHIVED_RESOURCE`. Recomputes every parent's derived cost. 201 `ResourceDetailResponse`. No PUT/PATCH/DELETE on history (`405`). |
| GET | `/resources/{resourceId}/costs` (paged) | `VIEW_PRICES` | full history newest first; `from`/`to` optional instants. Sort fixed (`effectiveFrom desc`). |

Create body (POST `/resources`):
```json
{ "type": "MATERIAL", "code": "MAN-CEM-001", "name": "Cemento gris 50 kg", "description": null, "unitId": "uuid", "familyId": "uuid",
  "supplierId": null, "brand": "Cemex", "cost": "245.50",
  "laborDetail": { "nominalWage": "350.00", "fsr": "1.70", "perDiem": "0.00", "mealsLodging": "0.00", "funeralExpenses": "0.00", "otherBenefits": "0.00" } }
```
- `code` 1..40 after trim, upper-cased, unique (`409 DUPLICATE_CODE`). `name` 1..160. `unitId` required and not archived.
- `MATERIAL` / `EQUIPMENT`: `cost` required (`>= 0`, two decimals, `< 10^12`, else `400 INVALID_ARGUMENT` with the existing `REQUIRED`/`INVALID`/`TOO_MANY_DECIMALS` rule codes); writes the first history row `MANUAL`. `laborDetail` -> `400`.
- `LABOR`: `cost` must be absent (`400`); `laborDetail.nominalWage` required. If the FASAR set is `COMPLETE`, `fsr` is ignored and computed (`fsrSource COMPUTED`); otherwise `fsr` is required, `1 <= fsr <= 3` (`fsrSource MANUAL`). The real wage (1.8) becomes the first history row.
- `brand` only on `MATERIAL` (`400` otherwise). Materials have no uniqueness on name + unit + brand any more: the code is the identity (ADR 0008).

### 2.5 Cost history rules

- `effectiveFrom` is always the server time of the write; the API never accepts an effective date (ADR 0009). Migrated observations are the one exception (section 7).
- A new entry with the same amount as the current one is still stored (it records who confirmed the cost and when) **(architect's choice)**, except during import, where "unchanged" writes nothing.
- The current cost is denormalized to `resource.current_cost`; both are written in the same transaction.

### 2.6 FASAR parameters

| Method | Path | Permission |
|---|---|---|
| GET | `/fasar` | `VIEW_PRICES` |
| PUT | `/fasar` | `MANAGE_PRICES` |
| GET | `/equipment-standby-percentages` | `VIEW_PRICES` (array of `{ charge, standbyPct, reservePct }`, read-only in v1) |

`FasarResponse`:
```json
{ "status": "COMPLETE", "source": "FASAR_FILE", "version": 2, "year": 2019, "uma": "86.8800", "minimumWage": "123.22",
  "sumEmployerAndWorker": true,
  "days": { "calendar": "365", "sundays": "52", "workedSundays": "0", "vacation": "6", "mondays": "0", "holidays": "7.5", "weather": "6", "custom": "2", "leaveSick": "3", "union": "0", "bonus": "15" },
  "premiums": { "vacationPct": "0.25", "sundayPct": "0.25", "otherChargesPct": "0" },
  "caps": { "excessThresholdUma": "3", "sbcCapUma": "25", "factorRounding": 6 },
  "contributions": [ { "concept": "RISK", "employerRate": "0.0758875", "workerRate": "0" }, { "concept": "FIXED_FEE", "employerRate": "0.204", "workerRate": "0" }, "... twelve concepts" ],
  "derived": { "tp": "..." , "tl": "...", "tpOverTl": "...", "fsbc": "..." },
  "laborCategories": { "total": 40, "computed": 40, "mismatches": 2 },
  "updatedAt": "...", "updatedBy": { "membershipId": "uuid", "fullName": "Ana Perez" } }
```
`GET` with no set -> `200 { "status": "NONE" }` and nulls. `PUT` takes the same body without `status`, `source`, `version`, `derived`, `laborCategories`, `updatedAt`, `updatedBy`; every field required (full replacement), all twelve concepts present, rates `0..1`, days `>= 0`, `calendar > 0`, `tl > 0` after the formula (else `400 INVALID_ARGUMENT`). It stores the set as `MANUAL`, `complete = true`, `version + 1`, then recomputes every simple `LABOR` Resource (section 5), writes a `FASAR` history row for every changed real wage, recomputes derived costs, and answers `200 FasarResponse` plus `"recomputation": { "laborCategories": 40, "realWagesChanged": 37, "workItemsRecomputed": 10264 }`. Runs in one transaction; `PUT` while an import is `IMPORTING` -> `409 IMPORT_IN_PROGRESS`.

## 3. Derived cost algorithm

Runs for the whole organization after any write that can change a cost (import confirm, manual cost, wage change, FASAR replace). In Java, in the same transaction, from two queries: all simple Resources' `current_cost` and all analysis lines of the organization **(architect's choice: 82 000 lines fit in memory and a full pass is a second; incremental invalidation can come later without changing results)**.

### 3.1 Definitions

- `cost(r)` for a simple Resource = `current_cost`, or `0.00` when it has no history yet (and the parent is flagged `incomplete`, see 3.3).
- For a composite `p` with lines `L`: 
  - `laborSubtotal(p) = Σ amount(l)` over lines whose component is of type `LABOR` and is not a percentage Resource (simple categories and Gangs alike).
  - `amount(l)`: operator `*` -> `round2(quantity × cost(component))`; operator `/` -> `round2(cost(component) ÷ quantity)` (`quantity > 0` is enforced at import); component `isPercentage` with `percentageBase = 2` -> `round2(quantity × laborSubtotal(p))`, evaluated after all non-percentage lines of `p`.
  - `cost(p) = Σ amount(l)` (sum of already-rounded line amounts; no second rounding).
- `round2` = half-up to two decimals (`RoundingMode.HALF_UP`), matching Neodata's per-line `decimalesredondeo` = 2 and the money columns.
- Recursion: components that are composites are derived first (memoized depth-first). A cycle is impossible after import (the validator rejects it, 4.5 `ANALYSIS_CYCLE`), but the engine still guards with a visited set and fails the transaction with `IllegalStateException` if one appears.

### 3.2 Writes

For every composite: `resource.current_cost = cost(p)`, `cost_updated_at = now` only when the value changed, `unit_price_analysis_line.amount` per line. Composites get no `resource_cost_history` rows.

### 3.3 Verification against the file

After an import or any recomputation, a composite with `imported_cost` not null is compared: `mismatch = |cost(p) − imported_cost| > max(0.50, 0.005 × imported_cost)` **(architect's choice: 50 centavos absorbs the per-line rounding drift of long analyses; 0.5 % absorbs it on expensive items; anything bigger means a formula or data difference worth a warning)**. `incomplete = true` when any component in the subtree has no cost. Both are reported in `analysis` (2.4) and counted in the import summary (`workItemCostMismatches`).

## 4. Catalog import

### 4.1 Upload

| Method | Path | Permission |
|---|---|---|
| POST | `/catalog-imports/uploads` | `MANAGE_PRICES` |

Body `{ "kind": "PRESUPUESTO" | "FASAR", "fileName", "contentType", "sizeBytes" }`. Same presigned pattern as site plans (`shared/storage/ObjectStorage`): 201 `{ "uploadId": "uuid", "uploadUrl": "https://...", "headers": { "Content-Type": "..." }, "expiresAt": "..." }`, URL valid 10 minutes. Key `orgs/{organizationId}/catalog-imports/{uploadId}/{random}.{xlsx|xls}`. Rules: `PRESUPUESTO` needs `contentType` `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` and a `.xlsx` name; `FASAR` accepts that or `application/vnd.ms-excel` with `.xls` (else `400 IMPORT_FILE_TYPE`); `sizeBytes` 1..52 428 800 (50 MB, else `400 IMPORT_FILE_TOO_LARGE`). An upload slot not used by a job within 1 hour is garbage-collected (bucket lifecycle rule, as plans). Upload slots are rows of a small `catalog_import_upload` table (`id`, `organization_id`, `kind`, `file_key`, `file_name`, `content_type`, `size_bytes`, `created_by_membership_id`, `created_at`, `consumed_at`) **(architect's choice: avoids half-filled `catalog_import` rows)**.

### 4.2 Create the job

| Method | Path | Permission |
|---|---|---|
| POST | `/catalog-imports` | `MANAGE_PRICES` |

Body `{ "presupuestoUploadId": "uuid", "fasarUploadId": "uuid | null" }`. Checks: both uploads belong to this organization and are unconsumed (`404`); S3 holds each object with the announced size (`400 IMPORT_UPLOAD_MISSING` / `IMPORT_UPLOAD_MISMATCH`); no other job of this organization in a non-terminal state (`409 IMPORT_IN_PROGRESS`, body includes `activeImportId`). Computes sha-256 of each object (streamed), stores the job `PENDING`, marks the uploads consumed, and schedules validation (`@Async` executor dedicated to imports, pool size 1 **(architect's choice: one import at a time per instance; the DB uniqueness makes it one per organization)**). 202 `ImportResponse`.

### 4.3 Read

| Method | Path | Permission |
|---|---|---|
| GET | `/catalog-imports/{importId}` | `MANAGE_PRICES` |
| GET | `/catalog-imports` (paged, newest first) | `MANAGE_PRICES` |

`ImportResponse`:
```json
{ "id": "uuid", "status": "VALIDATED",
  "file": { "name": "..._Presupuesto.xlsx", "sizeBytes": 18234567, "sha256": "..." },
  "fasarFile": { "name": "..._FasarEstandar.xls", "sizeBytes": 234567, "sha256": "..." },
  "uploadedBy": { "membershipId": "uuid", "fullName": "Ana Perez" },
  "createdAt": "...", "validatedAt": "...", "confirmedAt": null, "finishedAt": null,
  "licence": null,
  "summary": { "...": "see 4.5" },
  "failure": null }
```
Rule (decided): import jobs, with their file names, hashes and uploader, are readable only with `MANAGE_PRICES`; `VIEW_PRICES` sees only `lastImport`/`activeImport` on `GET /catalog`. `licence` after confirm: `{ "statement": "LICENCE_V1", "acceptedAt": "...", "acceptedBy": { ... } }`. `failure` when `FAILED`: `{ "code": "MISSING_COLUMN", "message": "Hoja Matrices: falta la columna 'CodigoI'", "sheet": "Matrices", "row": 1, "column": "CodigoI" }`; `message` is Spanish, built from the code and the location so the dashboard can also render its own EN/ES text from `code`. The dashboard polls every 2 s while `PENDING`/`VALIDATING`/`IMPORTING`.

### 4.4 Confirm and cancel

| Method | Path | Permission |
|---|---|---|
| POST | `/catalog-imports/{importId}/confirm` | `MANAGE_PRICES` |
| POST | `/catalog-imports/{importId}/cancel` | `MANAGE_PRICES` |

Confirm body `{ "licenceAccepted": true, "statement": "LICENCE_V1" }`. `licenceAccepted` must be `true` and `statement` must be the current key (`400 LICENCE_NOT_ACCEPTED`); the dashboard shows the text: EN "I hold a valid licence for this catalog and import it for my company's internal use." / ES "Cuento con una licencia vigente de este catálogo y lo importo para uso interno de mi empresa." Job not `VALIDATED` -> `409 STATE_CONFLICT`. Stores acceptance (who, when, key), sets `IMPORTING`, schedules the write (4.6). 202 `ImportResponse`. Cancel: `PENDING` or `VALIDATED` -> `CANCELLED`, 200; any other state -> `409 STATE_CONFLICT`.

### 4.5 Validation (state `VALIDATING`) and the dry-run summary

The worker streams the Presupuesto workbook with Apache POI `XSSF` streaming (SAX), then the FASAR workbook with `HSSF` if present (small file, 2.6/2.3/2.4 blocks only). Required sheets and columns (header row 1, exact names, case-sensitive after trim): `CatalogoGeneral` (`Codigo`, `Descripcion completa`, `Descripcion`, `Unidad`, `Tipo`, `Familia`, `Agrupador`, `%`, `Tipo %`, `Proveedor`, `FichaTecnica`, `Referencia`, `CodigoSap`, `InsumoDescontinuado`, `Procedimiento`, `InsumoIntegrado`, `EsCostoHorario`, `Costo0`, `Fecha0`), `Unidades` (`Unidad`, `Descripcion`), `Familias` (`Familia`, `Descripcion`), `Proveedores` (`Proveedor`, `RazonSocial`, and the 1.4 columns), `Matrices` (`CodigoM`, `CodigoI`, `Renglon`, `/`, `Cantidad`, `Expresion`), `DetalleManoObra` (`Codigo`, `SalarioBaseM0`, `FasarM0`, `SalarioRealM0`, `FactorSalarioIntegradoM0`, `SalarioIntegradoM0`, `ViaticosM0`, `AlimentacionYHospedajeM0`, `GastosDeSepelioM0`, `OtrasPrestacionesM0`, `TipoFasarM0`), `DetalleMaquinaria` (all 3.9 columns), `OperacionCH`, `ConsumosCH` (`CodigoM`, `CodigoI`, `/`, `cantidad`), `PorcentajesCh` (`Cargo`, `Espera`, `Reserva`), `N_Campos Generales` (`NOMBRE DE CELDA`, `DESCRIPCION`, `VALOR`). `Partidas` and `Presupuesto` are ignored even when present. Extra columns are ignored.

The job fails (`FAILED`, nothing written) on the first of: `WORKBOOK_UNREADABLE`, `MISSING_SHEET`, `MISSING_COLUMN`, `DUPLICATE_CODE` (same `Codigo` twice in `CatalogoGeneral`), `BLANK_CODE`, `UNSUPPORTED_TYPE` (`Tipo` not 1..4), `BLANK_UNIT` (a resource row with an empty `Unidad`), `UNKNOWN_CODE` (any `CodigoM`/`CodigoI`/`Codigo` of `Matrices`, `DetalleManoObra`, `DetalleMaquinaria`, `OperacionCH`, `ConsumosCH` absent from `CatalogoGeneral`), `INVALID_NUMBER` (non-numeric or negative `Costo0`/`Cantidad`, `Cantidad = 0`), `INVALID_OPERATOR` (not `*` or `/`), `INVALID_DATE` (`Fecha0` unparsable; both `yyyy/MM/dd` and `dd/MM/yyyy` are accepted, `1900-01-01` and Excel serial 1 mean null), `UNSUPPORTED_PERCENTAGE_BASE` (`%` = 1 with `Tipo %` not 2), `ANALYSIS_CYCLE` (a composite reaches itself), `UNSUPPORTED_CURRENCY` (`primeramoneda` in `N_Campos Generales` present and not `PESOS`/`M.N.`), `TOO_MANY_ROWS` (over 200 000 rows in `CatalogoGeneral` or 500 000 in `Matrices` **(architect's choice: ~5x the real export)**), and the FASAR codes `FASAR_MISSING_SHEET`, `FASAR_MISSING_BLOCK` (the `d)Salarios` header row 6, `a)TablaFactores` rows, `b) CapturaDatos` rows 16-38 or the `uma`/`uma_cap` named cells not found), `FASAR_INVALID_NUMBER`, `FASAR_UNKNOWN_CODE` (a `d)Salarios` code absent from `CatalogoGeneral` as type 2 is a **warning**, not a failure **(architect's choice)**). Failure records `sheet`, `row`, `column`.

Warnings never fail the job. Codes: `UNIT_NOT_LISTED` (a `Unidad` used by a resource row but absent from `Unidades`: the Unit is created with `code` = the cell value, `description` null, `source NEODATA_CONSTRUBASE`; Neodata's sheet has spelling duplicates, so this is expected), `UNKNOWN_FAMILY` (resource imported with no Family), `UNKNOWN_SUPPLIER` (no Supplier), `COST_ROUNDED` (more than 2 decimals), `QUANTITY_ROUNDED` (more than 6), `WORK_ITEM_COST_MISMATCH` (3.3), `LABOR_COST_MISMATCH` (`Costo0` ≠ `SalarioRealM0` beyond 0.05), `FSR_MISMATCH` and `REAL_WAGE_MISMATCH` (5.4), `FASAR_CATEGORY_NOT_IN_CATALOG`, `FASAR_YEAR_OLD` (FASAR year older than the current year − 1), `GENERAL_FIELDS_PERCENT_SCALE` (the `%/100` correction of 5.3 was applied), `NAME_TRUNCATED` (over 160 chars after trim).

Merge classification (per Resource code, against the organization's current Catalog): `new` (not present), `costChanged` (present, simple, file `Costo0` ≠ `current_cost` after rounding; or composite whose derived cost will change), `unchanged`, `missingFromFile` (present with `source = NEODATA_CONSTRUBASE` and absent from the file; hand-made `MANUAL`/`CSV` Resources are never flagged). Units, Families and Suppliers: `new` / `unchanged` by code.

Summary stored in `catalog_import.summary` and returned in `ImportResponse.summary`:
```json
{ "rows": { "CatalogoGeneral": 34869, "Matrices": 82051, "DetalleManoObra": 40, "DetalleMaquinaria": 60, "OperacionCH": 52, "ConsumosCH": 18 },
  "resources": { "MATERIAL": { "new": 24375, "costChanged": 0, "unchanged": 0, "missingFromFile": 0 },
                 "LABOR": { "new": 85, "costChanged": 0, "unchanged": 0, "missingFromFile": 0 },
                 "EQUIPMENT": { "new": 145, "costChanged": 0, "unchanged": 0, "missingFromFile": 0 },
                 "WORK_ITEM": { "new": 10264, "costChanged": 0, "unchanged": 0, "missingFromFile": 0 } },
  "units": { "new": 44, "unchanged": 7 }, "families": { "new": 11, "unchanged": 6 }, "suppliers": { "new": 9, "unchanged": 0 },
  "fasar": { "provided": true, "year": 2019, "uma": "86.8800", "minimumWage": "123.22", "laborCategories": 40, "fsrMismatches": 0, "realWageMismatches": 0 },
  "warnings": { "total": 23, "byCode": { "WORK_ITEM_COST_MISMATCH": 20, "UNKNOWN_FAMILY": 3 },
                "sample": [ { "code": "WORK_ITEM_COST_MISMATCH", "sheet": "CatalogoGeneral", "row": 1200, "resourceCode": "10301-001", "detail": { "imported": "1006.42", "derived": "1006.90" } } ] },
  "changes": { "sample": [ { "resourceCode": "MO011", "type": "LABOR", "name": "PEON", "from": "592.70", "to": "610.00" } ] },
  "missingFromFile": { "sample": [ { "resourceCode": "301-ARE-0199", "type": "MATERIAL", "name": "..." } ] } }
```
Each `sample` holds at most 200 entries **(architect's choice)**; totals are in the counts. The dry run also builds the derived costs in memory to classify composites and compute mismatches, so a `VALIDATED` job already knows the exact outcome of confirm.

### 4.6 Write (state `IMPORTING`)

The worker re-reads both files from S3 (the sha-256 must match the stored one, else `FAILED` `FILE_CHANGED`), re-runs validation (the Catalog may have changed since the dry run; the stored summary is refreshed), then in **one transaction**, in this order: Catalog row (create if missing); Units, Families, Suppliers by code (new rows `NEODATA_CONSTRUBASE`; existing ones get `description`/`name`/supplier fields overwritten from the file **(architect's choice: the file is the master for imported rows)**); Resources by code (new rows, or overwrite of every descriptive field of 1.5 from the file, `source = NEODATA_CONSTRUBASE`, `missing_from_import_at = null`, `imported_cost`/`imported_cost_on` refreshed); for simple Resources whose `Costo0` differs from `current_cost`, one `resource_cost_history` row (rule, decided: the file wins over a hand-edited cost; the dry run lists it under `costChanged` and the user's only protection is not confirming) (`NEODATA_CONSTRUBASE`, `effective_from = now`, `import_id`); `labor_category_detail` rows replaced from `DetalleManoObra` (and the FASAR workbook, section 5), `equipment_rate_detail` + lines replaced from `DetalleMaquinaria`/`OperacionCH`/`ConsumosCH`, `equipment_standby_percentage` replaced; analysis lines of every parent present in `Matrices` replaced wholesale (parents absent from `Matrices` keep their lines); `fasar_parameter_set` replaced when a FASAR file was provided, or created `GENERAL_FIELDS`/`complete = false` when none exists yet (5.3); `missing_from_import_at = now` on imported Resources absent from the file; derived costs recomputed (section 3); `catalog.last_import_id`; job `DONE`, `finished_at`. Any exception -> rollback, `FAILED` with `IMPORT_WRITE_FAILED` and the message logged, never the stack trace in the response.

Expected volume (35 000 Resources, 82 000 lines): use JDBC batch inserts (`spring.jpa.properties.hibernate.jdbc.batch_size=500`, ordered inserts) or `JdbcTemplate.batchUpdate` for the bulk tables; target under two minutes on the dev database **(architect's choice; non-functional)**.

## 5. FASAR computation (jornada normal 8 h only)

### 5.1 Inputs

From the `fasar_parameter_set` of the organization (1.10). Te (horas extras) = 0 in v1.

### 5.2 Formulas (RLOPSRM art. 190-191, as Neodata's `e)` and `f)` sheets)

```
vacation_premium_days = vacation_days × vacation_premium_pct
sunday_premium_days   = worked_sundays × sunday_premium_pct
Tp    = calendar_days + bonus_days + vacation_premium_days + sunday_premium_days
Tl    = calendar_days − (sundays + vacation_days + monday_days + holidays + weather_days + custom_days + leave_sick_days + union_days)
FSBC  = Tp / calendar_days
SBC   = min(Sn × FSBC, sbc_cap_uma × uma)
rate(c) = employer_rate(c) + worker_rate(c)        if sum_employer_and_worker, else employer_rate(c)
cuota(RISK)              = rate × SBC
cuota(FIXED_FEE)         = rate × uma
cuota(EXCESS_3_UMA)      = rate × max(0, SBC − excess_threshold_uma × uma)
cuota(CASH_BENEFITS, PENSIONERS_MEDICAL, DISABILITY_LIFE, RETIREMENT, UNEMPLOYMENT_OLD_AGE, DAYCARE, INFONAVIT, PAYROLL_TAX) = rate × SBC
SP    = Σ cuota(c)                                  (OTHER_CHARGES is not a cuota; it enters FSR directly)
Ps    = SP / SBC
FSR   = round(Ps × (Tp / Tl) + (Tp / Tl) + other_charges_pct, factor_rounding)
Real wage = round(Sn × FSR, 2) + per_diem + meals_lodging + funeral_expenses + other_benefits
```
The Sunday premium is earned only on Sundays worked, not on the rest-day Sundays that reduce `Tl`: with the values documented in `csv_architecture.md` §2.6/2.8 (DICAL 365, aguinaldo 15, vacaciones 6 × 25 %, prima dominical base 0) `Tp = 381.5` and `FSBC = 381.5 / 365 = 1.045205`, which is what `d)Salarios` shows; counting all 52 Sundays would give 394.5 and 1.0808. When the FASAR workbook is imported, `b) CapturaDatos` row 2.3 "días equivalentes" of the prima dominical is read directly as `sunday_premium_days` (and `worked_sundays` is back-computed as `días equivalentes ÷ sunday_premium_pct`, 0 when the percentage is 0); row 2.2 likewise for the vacation premium as a cross-check. `Tp/Tl`, `FSBC`, `Ps` are kept at 8 decimals internally (`MathContext(20)` for the divisions) and only `FSR` is rounded to `factor_rounding` (Neodata's "REDONDEO PARA FACTORES"); `FSBC` and `SBC` are stored rounded to 8 and 2 decimals for display. The minimum-wage floor on `SBC` is **not** applied **(architect's choice: Neodata lists the minimum wage among the "topes" but its `f)` sheet does not clamp; the verification of 5.4 will show if a licensee's file disagrees)**. A `SBC = 0` (nominal wage 0) gives `Ps = 0`.

### 5.2a Test data and the real workbooks

The real Neodata workbooks (`ConstruBASE_1-4-2026_Hr10Mn56Xn_Presupuesto.xlsx`, `..._FasarEstandar_UMAXn_PESOS.xls`) exist only on the user's machine, outside the repository, and **must never be committed** or copied into fixtures (ADR 0008: Construx ships no Neodata data). Automated tests of the parser, the derived-cost engine and the FASAR computation use a small synthetic workbook built inside the test with POI (a dozen resources, a few analyses, three labor categories, one equipment rate) and parameterized expected values. The expected FSR/FSBC/real wage of a test are the values the test itself derives from its own parameters, never numbers copied from the licensee's file. Running the import against the real export is a manual verification step the orchestrator performs with the user, checking that the summary reports zero `FSR_MISMATCH`/`REAL_WAGE_MISMATCH` and few `WORK_ITEM_COST_MISMATCH` warnings.

### 5.3 Without a FASAR workbook

When the import has no FASAR file and the organization has no `COMPLETE` set, a `PARTIAL` set is built from `N_Campos Generales`: `minimum_wage` <- `Salario Mínimo`; contributions `RETIREMENT` <- `Sar`, `INFONAVIT` <- `Invonavit` (sic), `RISK` <- `Riesgo de trabajo`, `PAYROLL_TAX` <- `Impuesto sobre nomina`, `other_charges_pct` <- `Otros cargos`, `sum_employer_and_worker` <- `Patron + Trabajado`; every other field null/0 and `complete = false`. **The `%/100` bug** (`csv_architecture.md` §3.1): these cells are stored as percentages with the wrong scale (`200%` means 2 %, `759%` means 7.59 %). Rule: read the raw cell value `v`; if `v > 1` (i.e. displayed above 100 %) divide by 100 and emit `GENERAL_FIELDS_PERCENT_SCALE`; a value `<= 1` is already a fraction. Labor categories then keep `fsr_source = IMPORTED` with the file's `FasarM0`, and `GET /catalog` shows `fasar.status = PARTIAL` so the UI says "FSR imported, not recomputable". A later FASAR import or `PUT /fasar` turns every simple `LABOR` Resource to `COMPUTED`.

### 5.4 Verification

For every simple `LABOR` Resource after an import with a FASAR file, after `PUT /fasar`, and after a wage edit: `fsr_mismatch = |FSR_computed − imported_fsr| > 0.0005` or `|real_wage_computed − imported_real_wage| > 0.05` **(architect's choice: half a unit in the 4th decimal of the factor and 5 centavos on the wage, i.e. one rounding step on each)**. Reported as `FSR_MISMATCH` / `REAL_WAGE_MISMATCH` warnings in the import summary and as `fsrMismatch` on the Resource. In an import the file values compared (and kept in `imported_fsr` / `imported_real_wage`) are the FASAR workbook's `d)Salarios` FSR and salario real when the category is listed there, else `DetalleManoObra`'s `FasarM0` / `SalarioRealM0`; the nominal wage is always `DetalleManoObra`'s. An import without a FASAR workbook into an organization that already has a `COMPLETE` set also computes its categories from that set.

### 5.5 Which value is the cost

With a `COMPLETE` set the real wage of 5.2 is the Resource's cost (`fsr_source = COMPUTED`), and the file's `Costo0`/`SalarioRealM0` are kept only in `imported_*`. With a `PARTIAL` or no set, the cost is `Costo0` from the file (`LABOR_COST_MISMATCH` when it differs from `SalarioRealM0`), or `Sn × fsr` for a hand-made category.

## 6. Labor rate on a Site and accrued labor cost

### 6.1 Labor rate (`modules/project`)

Base `/api/organizations/{organizationId}/projects/{projectId}`. `LaborRate` object:
```json
{ "kind": "CATALOG", "laborCategory": { "id": "uuid", "code": "MO011", "name": "PEON", "realWage": "610.00" }, "dailyAmount": "610.00",
  "effectiveFrom": "2026-11-15T18:02:11Z", "setBy": { "membershipId": "uuid", "fullName": "Ana Perez" } }
```
`dailyAmount` is the amount in force now: the category's current cost for `CATALOG`, the fixed amount for `FIXED`. `kind: "NONE"` has null `laborCategory`/`dailyAmount`.

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/access` (existing) | `VIEW_PROJECT` on the Site | each `AccessResponse` gains `laborRate` (`LaborRate` or null) **only when the caller holds `VIEW_BUDGET` or `MANAGE_PROJECT` on the Site**; otherwise the field is null. Rule (decided): whoever may set a rate can read it; nobody else sees per-person costs. |
| GET | `/access/{accessId}/labor-rate` | `MANAGE_PROJECT` on the Site, or `VIEW_BUDGET` | 200 `{ "current": LaborRate | null, "suggested": LaborRate + "fromProject": { "id", "name" } | null, "history": [LaborRate...] }`. `suggested` = the member's latest non-`NONE` rate on any other Site of the organization (by `effectiveFrom`), for pre-filling (D9). |
| PUT | `/access/{accessId}/labor-rate` | `MANAGE_PROJECT` on the Site | Body `{ "kind": "CATALOG", "laborCategoryId": "uuid" }` or `{ "kind": "FIXED", "dailyAmount": "650.00" }` or `{ "kind": "NONE" }`. Inserts a `labor_rate_assignment` row effective now (idempotent when identical to the current one: no row, 200). `laborCategoryId` must be a simple, non-archived `LABOR` Resource of this organization (`404` if another tenant, `400 INVALID_ARGUMENT` if composite/archived/other type). `dailyAmount` `>= 0`, two decimals. 200 `AccessResponse` with `laborRate`. |

`accessId` is resolved by `(organizationId, projectId, accessId)`. The rate row is keyed by `(project_id, membership_id)` of that access. Removing the access (`DELETE /access/{accessId}`) leaves rate and accrual history intact; re-adding the person later makes the old current row apply again (the suggestion will show it).

### 6.2 Accrual semantics (ADR 0009)

- **Day**: `check_in.check_in_day`, already a calendar date in `app.time-zone` (`America/Mexico_City` by default). "End of day D" = `D + 1 day at 00:00` in that zone, as an instant.
- **Counting check-ins**: a person-day `(project_id, membership_id, D)` counts once when at least one `check_in` row exists with that key and `removed_at is null`. Several Check-ins the same day are one jornada; a Check-in on another Site the same day is another jornada there.
- **Rate for the day**: the `labor_rate_assignment` row for `(project_id, membership_id)` with the greatest `effective_from < end of D`. `CATALOG` -> the `resource_cost_history` row of the category with the greatest `effective_from < end of D` -> `amount`; none -> `0.00` and `unrated = true` (reason `NO_COST_YET`). `FIXED` -> `daily_amount`. `NONE` or no row -> `0.00`, `unrated = true` (reason `NO_RATE`). Rule (decided): unrated person-days are counted at `0.00` and flagged, never excluded, so missing rates stay visible in the totals and the drill-down. A rate set at 15:00 on D applies to D and later, never to D − 1.
- **Accrued labor cost of a Site** over `[from, to]` = Σ rate over counted person-days with `from <= D <= to`. Future days never have Check-ins (attendance only accepts today), so "to = today" is the natural upper bound.
- The query joins `check_in` (distinct `membership_id, check_in_day`) with the latest rate row per `(membership, day)` and the latest cost row per `(category, day)`; a lateral join / window function in SQL, or load the Site's rate rows and the referenced cost histories (small) and resolve in Java **(architect's choice: Java resolution; the per-Site data is small and the rule stays in one tested class, `LaborAccrual`)**.

### 6.3 Endpoints (`modules/finance`)

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/labor-cost?from=&to=` | `VIEW_BUDGET` | `from` default: earliest Check-in of the Site; `to` default today; `from > to` or a range over 366 days -> `400 INVALID_ARGUMENT`. 200 below. |
| GET | `/labor-cost/days?membershipId=&from=&to=` (paged, `size` max 200, sort fixed `day desc, name`) | `VIEW_BUDGET` | per person per day rows. |

`GET /labor-cost`:
```json
{ "from": "2026-11-01", "to": "2026-11-15", "total": "48360.00", "personDays": 82, "unratedPersonDays": 3,
  "people": [ { "membershipId": "uuid", "fullName": "Ana Perez", "days": 12, "amount": "7320.00", "unratedDays": 0,
                "currentRate": { "kind": "CATALOG", "laborCategory": { "id": "uuid", "code": "MO011", "name": "PEON" }, "dailyAmount": "610.00" } } ] }
```
`people` ordered by `amount desc, fullName`; a person who has left the company keeps their name. `GET /labor-cost/days` content rows: `{ "day": "2026-11-14", "membershipId", "fullName", "rate": { "kind", "laborCategory", "dailyAmount" } | null, "amount": "610.00", "unrated": false, "unratedReason": null }`.

Implementation notes: when `from` is absent it is the Site's first Check-in day, but never more than 366 days before `to` and never after it, so the default read never fails the range rule; without Check-ins it is `to`. `page`/`size` out of range on `/labor-cost/days` -> `400 INVALID_ARGUMENT`. In `people[].currentRate`, `dailyAmount` is the category's current cost (`CATALOG`), the fixed amount (`FIXED`) or null (`NONE`); a person without any rate row has `currentRate: null`. In `/days`, `rate.dailyAmount` is that day's amount, null for `NONE` and for `NO_COST_YET`; `rate` is null for a day without a rate row.

### 6.4 Budget integration

`GET /projects/{projectId}/budget` (existing, `VIEW_BUDGET`) gains `laborAccrued` (all time) and `recordedExpenses`; `spent = recordedExpenses + laborAccrued`; `remaining = allocatedBudget − spent`; `byCategory` keeps the four categories and `LABOR` **includes** the accrual so that `Σ byCategory = spent`. Phase and task budgets (`GET .../goals/{goalId}/budget`, `.../activities/{activityId}/budget`) are unchanged: the accrual is a Site-level figure (a Check-in is not tied to a task). Rule (decided): `GET /finance/summary` and `/finance/expenses/monthly` (org-finance.md, ADR 0006) count the accrual of budgeted, non-archived Sites in `totalSpent`, `byCategory.LABOR`, each Site's `spent` and `status`, and in the month of each person-day. The budget status rule itself (`SiteBudgetStatus`) is unchanged; it only receives a bigger `spent`.

### 6.5 Expense from catalog (`modules/finance`)

`POST /projects/{projectId}/expenses` and `POST .../activities/{activityId}/expenses` (existing permissions) accept:
```json
{ "resourceId": "uuid", "quantity": "12.5000", "spentOn": "2026-11-14", "description": "Cemento gris, 12 sacos y medio" }
```
When `resourceId` is present: `quantity` required (`> 0`, up to 6 decimals); `amount` and `category` must be absent (`400 INVALID_ARGUMENT`, code `CATALOG_EXPENSE_FIELDS`); the Resource is loaded by `(organizationId, resourceId)` (`404`), must be `MATERIAL`, `EQUIPMENT` or `LABOR` and not archived (`400 RESOURCE_NOT_EXPENSABLE` for `WORK_ITEM`, `409 ARCHIVED_RESOURCE`), may be composite (a Gang's derived cost is a valid unit cost); `unitCost` = the Resource's `current_cost` at that moment (`null` cost -> `400 RESOURCE_WITHOUT_COST`); `amount = round2(quantity × unitCost)` (`> 0` enforced by the entity, so a zero-cost Resource -> `400`); `category` = `MATERIAL -> MATERIALS`, `EQUIPMENT -> EQUIPMENT`, `LABOR -> LABOR`; `description` defaults to the Resource name when absent. The response gains `resource: { id, code, name, type, unitCode } | null`, `quantity`, `unitCost`. `PATCH` on a catalog expense accepts `spentOn` and `description` only; `amount`, `category`, `resourceId`, `quantity` -> `400 CATALOG_EXPENSE_LOCKED`. `DELETE` unchanged.

### 6.6 Equipment link (`modules/crew`)

The existing equipment create/update body gains `equipmentRateResourceId` (nullable, must be an `EQUIPMENT` Resource of the organization, `404`/`400`), responses gain `equipmentRate: { id, code, name, hourlyCost } | null`. When `equipmentRateResourceId` is set in a request that does not send `dailyCostCents`, `dailyCostCents = round(hourlyCost × 8 × 100)`; a sent `dailyCostCents` wins. The link is a pointer, not a snapshot: later hourly cost changes do not change `dailyCostCents` (the UI offers "update from rate").

Implementation notes (both slices): on equipment `PATCH` an absent `equipmentRateResourceId` keeps the link, `""` removes it (keeping `dailyCostCents`) and an id links that rate; sending the linked id again without `dailyCostCents` is the "update from rate" action. A Resource of another type answers `400 NOT_AN_EQUIPMENT_RATE`. `hourlyCost` is the rate's `currentCost` (two decimals; `null` while it has none, and then nothing is prefilled). For §6.5, a `quantity` without `resourceId` is `400 CATALOG_EXPENSE_FIELDS`, so is a `PATCH` sending `resourceId`/`quantity` to a typed expense; the response `quantity` is the stored value without trailing zeros (`"12.5"`) and `unitCost` has two decimals.

## 7. Migration of existing data (V38, in the same migration as the new tables)

Existing rows are test data (D1); the script converts them without special cases:

1. `insert into catalog` one row per organization having any `material` or `supplier` row.
2. `unit`: for each such organization, the seven seeded Units of 1.2 (`source SEED`). `family`: the six seeded Families of 1.3.
3. `resource` from `material`: `code = 'MAT-' || lpad(row_number() over (partition by organization_id order by name, brand nulls first, id)::text, 6, '0')` **(architect's choice: the old rows have no code)**, `type = 'MATERIAL'`, `name`, `brand`, `unit_id` by `(organization_id, mapped code)`, `family_id` by `(organization_id, category)`, `source = 'MANUAL'`, `archived_at`, `created_at = now()`. Keep the old `material.id` as the new `resource.id` so nothing else needs remapping.
4. `resource_cost_history` from `price_observation`: `id`, `resource_id = material_id`, `amount = price`, `effective_from = (observed_on::timestamp) at time zone 'America/Mexico_City'` (the one place a non-"now" effective time exists; same-day rows are ordered by `created_at` by adding `created_at`'s time-of-day: `effective_from = (observed_on + created_at::time) at time zone 'America/Mexico_City'`), `source` (`MANUAL`/`CSV` kept), `supplier_id`, `sku`, `recorded_by_membership_id = null`.
5. `resource.current_cost`/`cost_updated_at` from the latest history row per resource.
6. `supplier`: add the new columns (1.4), `source = 'MANUAL'`.
7. `drop table price_observation; drop table material;`. The Java classes `Material`, `MaterialUnit`, `MaterialCategory`, `PriceObservation`, `PriceSource`, their repositories, services, controllers and `CsvParser`/`PriceImportService` are deleted; `PricingText`, the exception types and `PricingExceptionHandler` stay.

The per-supplier comparison (`latestPrices`, `cheapestSupplierId`) is gone; a Resource's history shows which Supplier each entry came from.

## 8. Removed endpoints

`GET/POST/PATCH /materials...`, `POST /price-observations`, `DELETE /price-observations/{id}`, `POST /price-observations/import` answer `404` after V38 (routes removed). The dashboard's "Materiales" feature is replaced by "Precios" in the same release.

## 9. Phase 2 (explicitly out of v1)

Estimates and Sections with quantities (`Partidas`, `Presupuesto` sheets); indirectos, financiamiento, utilidad and precio de venta; explosión de insumos; creating or editing Work items, Gangs and analysis lines; hourly cost recomputation from `equipment_rate_detail`; FASAR horas extras (Te > 0, `c)Horas Extras`, `g)`/`h)` sheets); currencies 1 and 2; `.mdf` or "Intercambio" import and other vendors; Resource to floor-plan zone link; automatic equipment accrual from zone assignments; Unit alias merging (`HR`/`HRS`/`HOR`); per-Supplier price comparison for a Material; "where used" beyond the 10-row sample; incremental derived-cost invalidation; cleanup job for abandoned uploads and `VALIDATED` jobs beyond the 24 h auto-cancel.
