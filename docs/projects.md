# Sites, phases and tasks

Vocabulary: a site is a `Project`, a phase is a `Goal`, a task is an `Activity`, and a person on a site is a `ProjectAccess`. Every path is under `/api/organizations/{organizationId}`. Each request checks, in this order: active membership (else `404`, same as a missing organization), that the resource belongs to this organization and parent (else `404`), then the permission and its scope through `DomainAuthorization` (else `403`). Scopes: `ORGANIZATION` = every site, `PROJECT` = only sites where the caller has a `ProjectAccess`, `OWN` = only tasks assigned to the caller. Role permissions come from `seeded-roles.properties` (see `organizations.md`).

## Sites

| Method | Path | Permission |
|---|---|---|
| POST | `/projects` | `MANAGE_PROJECT` |
| GET | `/projects?status=` | `VIEW_PROJECT`, list filtered by scope |
| GET | `/projects/{projectId}` | `VIEW_PROJECT` on the site |
| PATCH | `/projects/{projectId}` | `MANAGE_PROJECT` on the site |

Body: `{ "name", "address", "startDate", "expectedEndDate", "status" }`; only `name` is required on POST. `status` is `PLANNED` (default), `ACTIVE`, `ON_HOLD`, `COMPLETED` or `ARCHIVED`. There is no delete: archive the site. The list hides `ARCHIVED` unless `?status=ARCHIVED`. PATCH changes only the fields sent; send `"address": ""` to clear the address. An end date before the start date returns `400 INVALID_ARGUMENT`. Responses include `activityCount` and `doneCount`, which are `null` when the caller cannot view that site's tasks.

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

Body: `{ "name", "dueAt" }` (ISO-8601 instant), both required on POST. A phase's `dueAt` cannot move before its latest task (`400 INVALID_ARGUMENT`). Deleting a phase that still has tasks returns `409 STATE_CONFLICT`. Responses include `activityCount`, `doneCount` (null without task visibility) and `completed` (has tasks and all are done).

## Tasks

`...` = `/projects/{projectId}/goals/{goalId}/activities`.

| Method | Path | Permission |
|---|---|---|
| POST | `...` | `MANAGE_ACTIVITY` and `ASSIGN_ACTIVITY` on the site |
| GET | `...?status=&assigneeAccessId=` | `VIEW_ACTIVITY` on the site (`OWN` scope sees only own tasks) |
| GET | `.../{activityId}` | `VIEW_ACTIVITY` (own tasks pass `OWN`) |
| PATCH | `.../{activityId}` | `MANAGE_ACTIVITY` on the site |
| PUT | `.../{activityId}/assignee` | `ASSIGN_ACTIVITY` on the site |
| POST | `.../{activityId}/start` | `MANAGE_ACTIVITY`; `OWN` passes for the assignee |
| POST | `.../{activityId}/complete` | `MANAGE_ACTIVITY`; `OWN` passes for the assignee |
| DELETE | `.../{activityId}` | `MANAGE_ACTIVITY` on the site |
| GET | `/activities/mine?status=` | any `VIEW_ACTIVITY` or `MANAGE_ACTIVITY` |

POST body: `{ "name", "dueAt", "assigneeAccessId" }`, all required; the assignee must be a `ProjectAccess` of this site (`404` otherwise). A task's `dueAt` cannot be after its phase's (`400`). PATCH accepts `{ "name", "dueAt" }` only; use `PUT .../assignee` with `{ "assigneeAccessId" }` to reassign. Status moves `TODO` → `IN_PROGRESS` (`start`) and `TODO`/`IN_PROGRESS` → `DONE` (`complete`, sets `completedAt`); other moves return `409 STATE_CONFLICT` and a done task cannot be reopened in v1. A worker (`MANAGE_ACTIVITY` at `OWN`) can only start and complete tasks assigned to them; every other change returns `403`. `/activities/mine` lists the caller's own tasks across all sites of the organization, ordered by `dueAt`.

Response: `{ id, goalId, projectId, name, dueAt, status, completedAt, assignee: { accessId, membershipId, fullName } }`.
