# Getting-started guide

The dashboard shows new members a "Getting started" checklist. Each step ticks itself off when the member really does the action, and it can start a short spotlight tour on its page. The backend only stores each member's progress. Which steps exist, who sees them, and their text all live in the dashboard (`construx-dashboard/src/features/guide`), so adding a step needs no backend change.

## Model

Progress belongs to one `OrganizationMembership`, which is one user in one company. A user who belongs to two companies has two separate progress records.

- `guide_progress`: `organization_membership_id` (primary key, references `organization_membership(id)`, cascades on delete), `dismissed_at` (nullable), `updated_at`.
- `guide_progress_step`: (`organization_membership_id`, `step_id`) primary key, `status` `COMPLETED` | `SKIPPED`.

When no `guide_progress` row exists, the member has never seen the guide. The dashboard opens the guide by itself in that case, and only then.

Migration `V40__guide_progress.sql` creates both tables. It also inserts a row with `dismissed_at = now()` for every membership that already exists, so people who already use the app never see the guide open by itself.

## Endpoints

All three endpoints sit under `/api/organizations/{organizationId}` and take an `OrgScope`. A non-member or an unknown company gets `404`. Any active member may call them, and no permission is required, because a member only ever reads and writes their **own** progress. The path has no membership id. Both `PUT`s require CSRF, like every unsafe request.

### `GET /guide`

```json
{ "started": true, "steps": { "create-site": "COMPLETED", "invite-member": "SKIPPED" },
  "dismissedAt": null, "updatedAt": "2026-11-20T10:00:00Z" }
```

With no row it returns `{"started": false, "steps": {}, "dismissedAt": null, "updatedAt": null}`. A `GET` never creates a row.

### `PUT /guide/steps/{stepId}`

Body `{"status": "COMPLETED" | "SKIPPED"}` → `200` with the same body as `GET`. Creates the progress row if it is missing. Calling it again with the same status changes nothing (except `updatedAt`). A new status replaces the old one, so a skipped step can later be completed.

- `stepId` must match `^[a-z][a-z0-9-]{0,63}$`, otherwise `400 GUIDE_STEP_INVALID`.
- At most 50 steps per member, otherwise `400 GUIDE_TOO_MANY_STEPS`. This cap stops a caller from growing the table without limit.

### `PUT /guide`

Body `{"dismissed": true | false}` → `200` with the same body as `GET`. Creates the progress row if it is missing. `true` sets `dismissedAt` to now, unless it is already set. `false` clears it. When the dashboard opens the guide by itself, it sends `{"dismissed": false}` so that the guide doesn't open by itself again.

## Not in scope

There is no usage tracking: `guide_progress` is enough to see where people stop. There is no admin view of other members' progress, no sample data, and no step list on the server.
