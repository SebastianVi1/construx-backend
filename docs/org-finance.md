# Organization finance

Company-wide view of site budgets and spending: one summary and one monthly series. Read-only; nothing here writes. Module `modules/finance`. Vocabulary: a site is a `Project`, an expense is an `Expense` (see `projects.md`, "Site expenses"). Decision record: ADR 0006 (`.agents/adr/0006-org-finance-counts-budgeted-sites.md`).

| Method | Path | Permission |
|---|---|---|
| GET | `/api/organizations/{organizationId}/finance/summary` | `VIEW_BUDGET` |
| GET | `/api/organizations/{organizationId}/finance/expenses/monthly?from=&to=` | `VIEW_BUDGET` |

## Checks, in this order

1. `MembershipGuard.requireActive(userId, organizationId)`: no active membership, or no such organization, returns `404` (same body for both).
2. `DomainAuthorization.require(userId, organizationId, null, null, PermissionAction.VIEW_BUDGET)`: a role without `VIEW_BUDGET` returns `403 FORBIDDEN`. `VIEW_BUDGET` is organization-scope only (Owner, Administrator, Finance). Passing `projectId = null` makes any future `PROJECT` or `OWN` scope fail closed with `403`, which is intended: these totals cover every site.
3. Every query filters by `organizationId`. No query reads an expense or site of another organization.

A task Lead without `VIEW_BUDGET` gets `403` here; the Lead exception of ADR 0005 applies to one task, not to company totals.

## Which sites count (ADR 0006)

- **Included sites**: every site of the organization whose status is not `ARCHIVED`, the same default as `GET /projects`.
- **Budgeted site**: an included site whose `allocatedBudget` is not null (zero is a budget).
- Company totals (`totalBudget`, `totalSpent`, `totalRemaining`, `executionPct`, `sitesOverBudget`, `sitesNearLimit`, `byCategory`, and every monthly `spent`) count **budgeted sites only**, so they reconcile: `sum(byCategory.amount) = totalSpent`, and the monthly bars are measured against the same `totalBudget`.
- Sites without a budget still appear in `sites` with their own `spent` and status `NO_BUDGET`.

## Budget status of a site

Computed in Java with exact `BigDecimal` comparisons on `spent` and `allocatedBudget`, never on the rounded percentage:

| Status | Rule |
|---|---|
| `NO_BUDGET` | `allocatedBudget` is null |
| `OVER_BUDGET` | `spent > allocatedBudget` |
| `NEAR_LIMIT` | not over, and `spent * 100 >= allocatedBudget * 80` (80 % up to and including 100 %) |
| `OK` | otherwise |

A zero budget with zero spent is `OK`; a zero budget with any spending is `OVER_BUDGET`. Put this rule in one place (a small enum with a static `of(budget, spent)` factory, for example `SiteBudgetStatus`) and unit-test the boundaries: 79.99 %, exactly 80 %, exactly 100 %, 100.01 %, zero budget.

**Execution %** = `spent / budget * 100`, rounded half-up to one decimal, serialized as a JSON number. It is `null` when the budget is null or zero (no division by zero), at site level and company level alike.

Money fields have scale 2 (`0.00`, never `0`), like `GET /projects/{projectId}/budget`.

## GET `/finance/summary`

`200`:

```json
{
  "totalBudget": 500000.00,
  "totalSpent": 320000.00,
  "totalRemaining": 180000.00,
  "executionPct": 64.0,
  "sitesOverBudget": 1,
  "sitesNearLimit": 2,
  "byCategory": [
    { "category": "MATERIALS", "amount": 180000.00 },
    { "category": "LABOR", "amount": 95000.00 },
    { "category": "EQUIPMENT", "amount": 30000.00 },
    { "category": "OTHER", "amount": 15000.00 }
  ],
  "sites": [
    {
      "siteId": "0b6c…",
      "siteName": "Torre Norte",
      "allocatedBudget": 200000.00,
      "spent": 190000.00,
      "remaining": 10000.00,
      "executionPct": 95.0,
      "status": "NEAR_LIMIT"
    },
    {
      "siteId": "7e21…",
      "siteName": "Bodega Sur",
      "allocatedBudget": null,
      "spent": 4200.00,
      "remaining": null,
      "executionPct": null,
      "status": "NO_BUDGET"
    }
  ]
}
```

| Field | Rule |
|---|---|
| `totalBudget` | Sum of `allocatedBudget` of budgeted sites; `0.00` when there are none |
| `totalSpent` | Sum of expenses of budgeted sites |
| `totalRemaining` | `totalBudget - totalSpent`; negative on an overrun |
| `executionPct` | Company execution %, `null` when `totalBudget` is zero |
| `sitesOverBudget` / `sitesNearLimit` | Count of sites with that status (by definition only budgeted sites) |
| `byCategory` | Always the four categories in enum order `MATERIALS, LABOR, EQUIPMENT, OTHER`, `0.00` for unused ones, budgeted sites only |
| `sites` | Every included site, ordered by name. `remaining` is `allocatedBudget - spent` (negative on overrun, `null` without a budget) |

An organization with no sites returns `200` with zeros, `executionPct: null`, the four categories at `0.00`, and `sites: []`.

## GET `/finance/expenses/monthly`

Query parameters, both optional ISO dates (`YYYY-MM-DD`):

- `from`: default January 1 of the current year.
- `to`: default today.
- "Today" is `LocalDate.now()` in the server zone (no `Clock` bean exists yet; inject one if tests need a fixed date).

Expenses count when `from <= spentOn <= to`, inclusive. `months` lists every calendar month from the month of `from` to the month of `to`, ascending, including months with no expenses as `0.00`. A partial first or last month counts only the days inside the range.

`200`:

```json
{
  "totalBudget": 500000.00,
  "months": [
    { "month": "2026-01", "spent": 42000.00 },
    { "month": "2026-02", "spent": 0.00 }
  ]
}
```

`totalBudget` is the same value as in the summary. `month` is `YYYY-MM`.

Errors:

| Case | Response |
|---|---|
| `from` or `to` not a valid date | `400 INVALID_ARGUMENT` (already mapped by `GlobalExceptionHandler` for parameter type mismatches) |
| `from` after `to` (after defaults are applied) | `400 INVALID_ARGUMENT` (service throws `IllegalArgumentException`) |
| Range longer than 36 months | `400 INVALID_ARGUMENT` |

Validate the range in the service after the membership and permission checks, so a non-member always gets `404` and a member without the permission `403`, whatever the parameters.

## Implementation shape (for Backend Dev)

- `OrgFinanceController` at `/api/organizations/{organizationId}/finance`, delegating only; `@RequestParam(required = false) LocalDate from, to`, defaults resolved in the service so they are tested in one place. Return the service records directly or map to response records; never return entities.
- `OrgFinanceService` (`@Service`, constructor injection) with `summary(userId, organizationId)` and `monthlyExpenses(userId, organizationId, from, to)`, both `@Transactional(readOnly = true)`. Records: `OrgFinanceSummary`, `SiteFinance`, `MonthlyExpenseSummary`, `MonthlySpend`; reuse `ExpenseService.CategoryTotal` for categories.
- Sites come through the project module, not its repository: add an internal loader to `ProjectService` next to `load(...)`, e.g. `List<Project> inOrganization(UUID organizationId)` returning non-archived sites ordered by name via the existing `ProjectRepository.findInOrganization`. (This adds `ProjectService.java` to the edit surface; see the handoff.)
- `ExpenseRepository`, three aggregate queries, all filtered by organization, no N+1:
  - `sumByProjectInOrganization(organizationId)`: `select e.project.id, sum(e.amount) ... where e.project.organization.id = :organizationId group by e.project.id`. Sites missing from the result spent `0.00`.
  - `sumByCategoryInOrganization(organizationId)`: same filter plus `e.project.allocatedBudget is not null and e.project.status <> ARCHIVED`, grouped by `e.category`.
  - `sumByMonthInOrganization(organizationId, from, to)`: same budgeted-site filter plus `e.spentOn between :from and :to`, grouped by `year(e.spentOn), month(e.spentOn)`. Use HQL `year()` / `month()` instead of `FUNCTION('DATE_TRUNC', ...)`: they are portable across PostgreSQL and H2 and return integers that map straight to `YearMonth`. Fill missing months in Java by iterating `YearMonth.from(from)` to `YearMonth.from(to)`.
  - Prefer small interface projections or constructor expressions over `Object[]` if it stays simple; `Object[]` matches the existing `sumByCategory` and is acceptable.
- Status rule and execution % live in one small type, not inline in the service loop.

## Tests (QA)

`OrgFinanceControllerTest`, same infrastructure as `ExpenseControllerTest`:

- Summary `200` with budgeted, over-budget, near-limit and unbudgeted sites: totals exclude the unbudgeted site, `sum(byCategory) = totalSpent`, counts, per-site status and `null`s for `NO_BUDGET`.
- An archived site is absent from `sites` and from every total.
- Expenses of another organization never appear.
- `403` for a member whose role lacks `VIEW_BUDGET` (Member, Worker) on both endpoints.
- `404` for a non-member and for an unknown organization on both endpoints.
- Monthly with `from`/`to`: gap months are `0.00`, the range bounds are inclusive.
- Monthly without parameters: first month is January of the current year, last is the current month.
- Monthly `400` for `from > to` and for a malformed date.
- Unit test of the status rule boundaries listed above.
