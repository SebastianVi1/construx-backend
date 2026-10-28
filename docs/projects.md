# Sites, phases and tasks

Vocabulary: a site is a `Project`, a phase is a `Goal`, a task is an `Activity`, and a person on a site is a `ProjectAccess`. Every path is under `/api/organizations/{organizationId}`. Each request checks, in this order: active membership (else `404`, same as a missing organization), that the resource belongs to this organization and parent (else `404`), then the permission and its scope through `DomainAuthorization` (else `403`). Scopes: `ORGANIZATION` = every site, `PROJECT` = only sites where the caller has a `ProjectAccess`, `OWN` = only tasks whose team includes the caller. Role permissions come from `seeded-roles.properties` (see `organizations.md`).

## Sites

| Method | Path | Permission |
|---|---|---|
| POST | `/projects` | `MANAGE_PROJECT` |
| GET | `/projects?status=` | `VIEW_PROJECT`, list filtered by scope |
| GET | `/projects/{projectId}` | `VIEW_PROJECT` on the site |
| PATCH | `/projects/{projectId}` | `MANAGE_PROJECT` on the site |
| PUT | `/projects/{projectId}/budget` | `MANAGE_BUDGET` |
| GET | `/projects/{projectId}/budget` | `VIEW_BUDGET` |

Body: `{ "name", "address", "startDate", "expectedEndDate", "status" }`; only `name` is required on POST. PATCH also accepts `"clear": ["startDate", "expectedEndDate"]` to remove a date (null or a missing date keeps it; clearing and setting the same date returns `400`). `status` is `PLANNED` (default), `ACTIVE`, `ON_HOLD`, `COMPLETED` or `ARCHIVED`. There is no delete: archive the site. The list hides `ARCHIVED` unless `?status=ARCHIVED`. PATCH changes only the fields sent; send `"address": ""` to clear the address. An end date before the start date returns `400 INVALID_ARGUMENT`. Responses include `activityCount` and `doneCount`, which are `null` when the caller cannot view that site's tasks.

Budget: `PUT /projects/{projectId}/budget` with `{ "allocatedBudget": 250000.50 }` sets the site's allocated budget and returns the site; `{ "allocatedBudget": null }` clears it. The field is required: a body without it (for example `{}`) returns `400 INVALID_ARGUMENT` and keeps the stored value. The amount is in the global currency (USD for now), cannot be negative and allows at most 12 integer digits and 2 decimals (else `400`). PATCH never changes the budget. Responses include `allocatedBudget`, which is `null` when it is not set or when the caller lacks `VIEW_BUDGET`. The site budget cannot drop below, or be cleared under, the sum of its phase budgets (`400 BUDGET_EXCEEDED`). `VIEW_BUDGET` is organization-scope only and belongs to Owner, Administrator and Finance; `MANAGE_BUDGET` belongs to Owner and Finance only (`V13__project_budget.sql` backfilled both; `V15__budget_permissions.sql` takes `MANAGE_BUDGET` back from seeded Administrator roles and gives seeded Finance roles `VIEW_GOAL` and `VIEW_ACTIVITY`).

## Site expenses

| Method | Path | Permission |
|---|---|---|
| GET | `/projects/{projectId}/expenses` | `VIEW_BUDGET` |
| POST | `/projects/{projectId}/expenses` | `MANAGE_BUDGET` |
| PATCH | `/projects/{projectId}/expenses/{expenseId}` | `MANAGE_BUDGET` |
| DELETE | `/projects/{projectId}/expenses/{expenseId}` | `MANAGE_BUDGET` |

Module `modules/finance`, table `expense` (`V14__site_expenses.sql`). Only Owner and Finance record, edit and delete expenses; Administrator lost that with `MANAGE_BUDGET` in `V15__budget_permissions.sql` and keeps read access through `VIEW_BUDGET`. Body: `{ "amount", "spentOn", "description", "category" }`, all required on POST and optional on PATCH (a missing field keeps the stored value). `amount` is greater than 0 with at most 12 integer digits and 2 decimals; `spentOn` is a date; `description` has 1 to 160 characters; `category` is `MATERIALS`, `LABOR`, `EQUIPMENT` or `OTHER`. Anything else returns `400`. The list is newest first (`spentOn`, then creation). DELETE returns `204`; an expense of another site returns `404`.

Every expense is subtracted from the site budget. `GET /projects/{projectId}/budget` returns `{ "allocatedBudget", "spent", "remaining", "allocatedToPhases", "unallocated", "byCategory": [{ "category", "amount" }] }`: `allocatedToPhases` is the sum of the phase budgets, `unallocated` is `allocatedBudget - allocatedToPhases` (`null` without a budget), `spent` is the sum of the site's expenses, `remaining` is `allocatedBudget - spent` (negative on an overrun, `null` when no budget is set), and `byCategory` always lists the four categories in the order above, with `0` for unused ones.

## People on a site

| Method | Path | Permission |
|---|---|---|
| GET | `/projects/{projectId}/access` | `VIEW_PROJECT` on the site |
| POST | `/projects/{projectId}/access` | `MANAGE_PROJECT` on the site |
| DELETE | `/projects/{projectId}/access/{accessId}` | `MANAGE_PROJECT` on the site |

POST body: `{ "membershipId" }` of a member of this organization. A response row is `{ accessId, membershipId, userId, fullName, role: { code, name } }`, without email because workers can read it. Only active members are listed. Assigning someone twice returns `409 DATA_CONFLICT`; a suspended member returns `400`. Removing someone who still has tasks on the site returns `409 STATE_CONFLICT`: reassign the tasks first. Owners and administrators can manage every site but need an access of their own to be assigned tasks.

## Phases

| Method | Path | Permission |
|---|---|---|
| POST | `/projects/{projectId}/goals` | `MANAGE_GOAL` on the site |
| GET | `/projects/{projectId}/goals` | `VIEW_GOAL` on the site, ordered by `dueAt` |
| GET | `/projects/{projectId}/goals/{goalId}` | `VIEW_GOAL` on the site |
| PATCH | `/projects/{projectId}/goals/{goalId}` | `MANAGE_GOAL` on the site |
| DELETE | `/projects/{projectId}/goals/{goalId}` | `MANAGE_GOAL` on the site |
| POST | `/projects/{projectId}/goals/{goalId}/shift` | `MANAGE_GOAL` on the site |
| PUT | `/projects/{projectId}/goals/{goalId}/budget` | `MANAGE_BUDGET` |

Body: `{ "name", "startAt", "dueAt" }` (ISO-8601 instants), all required on POST and optional on PATCH. `startAt` cannot be after `dueAt`, cannot move after its earliest task start, and `dueAt` cannot move before its latest task (`400 INVALID_ARGUMENT`); when PATCH sends both dates they are checked as a pair. `POST .../shift` with `{ "days": n }` (non-zero, -3650..3650) moves the phase and all its tasks by `n` days in one transaction and returns the phase; `days: 0` is `400 INVALID_ARGUMENT`. Deleting a phase that still has tasks returns `409 STATE_CONFLICT`. Responses include `activityCount`, `doneCount` (null without task visibility) and `completed` (has tasks and all are done). Phases and tasks also take an optional `color` (`#rrggbb`, stored lowercase, `null` = default) on POST and PATCH; PATCH `"color": ""` resets it, any other value is `400 VALIDATION_FAILED`.

Phase budget: `PUT .../goals/{goalId}/budget` with `{ "allocatedBudget": 50000 }` carves the phase budget out of the site budget and returns the phase; `null` clears it. Same body and amount rules as the site budget, and POST/PATCH never change it. The phases of a site cannot hold more than the site budget (`400 BUDGET_EXCEEDED`), a phase budget needs a site budget (`400 BUDGET_PARENT_MISSING`), and a phase budget cannot drop below its tasks' budgets (`400 BUDGET_EXCEEDED`). Responses include `allocatedBudget`, `allocatedToActivities` (sum of its task budgets) and `unallocated` (`allocatedBudget - allocatedToActivities`, `null` without a budget); all three are `null` without `VIEW_BUDGET`. Deleting a phase frees its budget. Changes lock the site row, then the phase row, so concurrent requests cannot break the cap.

## Tasks

`...` = `/projects/{projectId}/goals/{goalId}/activities`.

| Method | Path | Permission |
|---|---|---|
| POST | `...` | `MANAGE_ACTIVITY` and `ASSIGN_ACTIVITY` on the site |
| GET | `...?status=&assigneeAccessId=` | `VIEW_ACTIVITY` on the site (`OWN` scope sees only tasks they are on) |
| GET | `.../{activityId}` | `VIEW_ACTIVITY` (team members pass `OWN`) |
| PATCH | `.../{activityId}` | `MANAGE_ACTIVITY` on the site |
| PUT | `.../{activityId}/assignees` | `ASSIGN_ACTIVITY` on the site |
| POST | `.../{activityId}/start` | `MANAGE_ACTIVITY`; `OWN` passes for any team member |
| POST | `.../{activityId}/complete` | `MANAGE_ACTIVITY`; `OWN` passes for any team member |
| DELETE | `.../{activityId}` | `MANAGE_ACTIVITY` on the site |
| PUT | `.../{activityId}/budget` | `MANAGE_BUDGET` |
| GET | `/activities/mine?status=` | any `VIEW_ACTIVITY` or `MANAGE_ACTIVITY` |

POST body: `{ "name", "startAt", "dueAt", "assignees": [{ "accessId", "taskRole" }] }`, all required. A task has a team (ADR 0005): one or more people, each a `ProjectAccess` of this site (`404` otherwise) with an active membership, nobody twice, and exactly one `LEAD`; `taskRole` is `LEAD`, `FOREMAN` or `CREW`. An empty list or a member without `accessId`/`taskRole` is `400 VALIDATION_FAILED`; no Lead, two Leads, a duplicate person or an inactive member is `400 INVALID_ARGUMENT`. A task lies inside its phase: `startAt` cannot be after `dueAt`, before the phase's `startAt`, and `dueAt` cannot be after the phase's (`400`). PATCH accepts `{ "name", "startAt", "dueAt", "color" }`, checking both dates together; use `PUT .../assignees` with `{ "assignees": [...] }` (same rules) to replace the whole team; people who stay keep their place and only change role. Being a task's Lead does not allow changing its team. Status moves `TODO` → `IN_PROGRESS` (`start`) and `TODO`/`IN_PROGRESS` → `DONE` (`complete`, sets `completedAt`); other moves return `409 STATE_CONFLICT` and a done task cannot be reopened in v1. A worker (`MANAGE_ACTIVITY` at `OWN`) can only start and complete tasks whose team includes them, whatever their task role; every other change returns `403`. `/activities/mine` lists the tasks whose team includes the caller across all sites of the organization, ordered by `dueAt`, and `?assigneeAccessId=` matches any team member.

Response: `{ id, goalId, goalName, projectId, projectName, name, startAt, dueAt, color, status, completedAt, assignees: [{ accessId, membershipId, fullName, taskRole }], allocatedBudget }`. `assignees` is ordered `LEAD`, `FOREMAN`, `CREW`, then by name; `fullName` is null when the person has no profile. The team lives in `activity_assignee` (`V17__activity_team.sql`, which made every earlier assignee the Lead of their task).

Task budget: `PUT .../{activityId}/budget` with `{ "allocatedBudget" }` carves the task budget out of its phase budget (same rules one level down: the tasks of a phase cannot hold more than its budget, `400 BUDGET_EXCEEDED`; the phase needs a budget first, `400 BUDGET_PARENT_MISSING`). Responses include `allocatedBudget`, `null` when unset or without `VIEW_BUDGET`. Deleting a task frees its budget. Phase and task budgets live in `goal.allocated_budget` and `activity.allocated_budget` (`V16__phase_activity_budget.sql`).


## Gantt bar colors

Phases and tasks accept an optional `color` on POST and PATCH. Use a six-digit
hex color such as `#3b82f6`; responses return lowercase hex or `null` for the default.
On PATCH, omission or `null` keeps the existing color, while `""` resets it.
Invalid colors return 400. Color edits use the existing phase/task edit permissions.
Each record stores its own color, shared by every viewer of the project; changes
to phase colors do not recolor tasks. Migration `V10__bar_colors.sql` adds nullable
columns without changing existing records' default appearance.
