# Floor plan pins

A pin marks a spot on a floor plan and carries photos. An `ISSUE` is a punch-list item tracked until closed; a
`PHOTO` is a progress record with no status and no assignee. Module `modules/floorplan` (`PlanPin`,
`PlanPinPhoto`, `PlanPinService`, `PlanPinController`), schema `V36__plan_pin.sql`.

`x` and `y` are pixels of the rendered plan image (`x` to the right, `y` up from the bottom edge, as the viewer's
Leaflet `CRS.Simple` map gives them), so a pin does not need the plan to be calibrated. The plan stores no
image size, so the API only rejects negative or non-finite coordinates; the dashboard keeps pins inside the image.
The zone of a pin (`zoneId`, `zoneName`) is worked out on every read: zones are drawn in metres, so the pin is
converted with the plan's `metersPerPx` and matched against the live zones. It follows zones that are redrawn and
a plan that is recalibrated; where zones overlap the first one drawn wins; an uncalibrated plan has no zones.

## Photos

Same flow as plans and avatars: the file never passes through the API.

1. `POST .../pins/photo-uploads` with `{contentType, sizeBytes}` returns `{key, uploadUrl, headers, expiresAt}`
   (valid 10 minutes). JPEG, PNG or WebP, at most 5 MB.
2. The client sends `PUT uploadUrl` with the file and exactly the returned `headers`.
3. The `key` is passed in `photoKeys` (create, status) or `keys` (add photos). The API accepts a key only if it
   was issued for this floor plan, the file exists with an allowed type and size, and no photo already uses it.

A pin holds at most 10 photos. Each photo has a `phase`: `REPORT` (what was found) or `RESOLUTION` (the fix).
Reads return presigned `GET` URLs valid for one hour. Uploads that are never attached stay in the bucket.

Storage key: `orgs/{organizationId}/projects/{projectId}/floor-plans/{floorPlanId}/pins/{random uuid}`.

## Issue lifecycle

`OPEN` -> `RESOLVED` -> `CLOSED`. The assignee or a manager resolves. Only a manager closes (also straight from
`OPEN`) or reopens (from `RESOLVED` or `CLOSED`, which clears `resolvedAt` and `closedAt`).

## Assignee and zone staff

Giving an issue to someone (on create or on edit) also puts them in the zone the pin sits in, exactly as a drag
onto the zone does: a zone assignment from today, full day, with the issue title as its activity. Nothing is added
when they already have an open assignment in that zone or the pin is outside every zone. The assignment is never
removed by the pin: it stays when the issue is resolved, closed, reassigned or deleted.

## Permissions

No permission actions of their own. "Manager" is `MANAGE_PLAN`; "reporter" is the member who created the pin.

| Action | Who |
|---|---|
| List, read, audit | `VIEW_PLAN` |
| Request a photo upload, create a pin without an assignee | `VIEW_PLAN` |
| Edit, delete, add `REPORT` photos, delete `REPORT` photos | Manager, or the reporter while the pin is a `PHOTO` or an `OPEN` issue |
| Delete `RESOLUTION` photos | Manager |
| Set or change the assignee | Manager; the assignee must have access to the Site |
| Resolve, add `RESOLUTION` photos | Manager or the assignee |
| Close, reopen | Manager |

Every pin carries `can: {edit, assign, resolve, close, reopen, delete}` for the caller, computed with these rules.

## Endpoints

Base path `/api/organizations/{organizationId}/projects/{projectId}/floor-plans/{floorPlanId}/pins`. Checks run
in this order: non-member -> 404, unknown site -> 404, missing `VIEW_PLAN` -> 403, unknown plan or pin -> 404,
then the rules above -> 403.

| Method and path | Notes |
|---|---|
| `POST /photo-uploads` | Body `{contentType, sizeBytes}`. 201. The plan must be `READY`. |
| `POST /` | Body `{kind, x, y, title?, description?, assigneeMembershipId?, photoKeys?}`. 201 with the pin and its photos. `title` is required for an `ISSUE`; a `PHOTO` needs at least one photo. |
| `GET /` | Filters `kind`, `status`, `assignee=me`, `zoneId`. Newest first. Each pin has `photoCount` and `coverUrl`; `photos` is null. |
| `GET /{pinId}` | The pin with `photos: [{id, phase, url, createdAt}]`. |
| `PATCH /{pinId}` | Body `{title, description, x, y, assigneeMembershipId, version}`. Replaces all of them; `version` must be the pin's current one. |
| `POST /{pinId}/status` | Body `{status, photoKeys?}`. Photos sent with `RESOLVED` or `CLOSED` are `RESOLUTION` photos. |
| `POST /{pinId}/photos` | Body `{keys, phase}`. |
| `DELETE /{pinId}/photos/{photoId}` | 204. Deletes the file. The last photo of a `PHOTO` pin cannot be deleted. |
| `DELETE /{pinId}` | 204. Soft delete; the photos stay in the bucket. |
| `GET /{pinId}/audit` | Audit trail (`CREATE`, `UPDATE`, `STATUS`, `PHOTO_ADD`, `PHOTO_DELETE`, `DELETE`), newest first. |

## Error codes

400 unless noted: `FLOOR_PLAN_NOT_READY`, `PIN_OUT_OF_BOUNDS`, `PIN_TITLE_REQUIRED`, `PIN_TITLE_TOO_LONG` (120),
`PIN_DESCRIPTION_TOO_LONG` (500), `PIN_PHOTO_REQUIRED`, `PIN_PHOTO_LIMIT`, `PIN_PHOTO_INVALID`,
`PIN_INVALID_TRANSITION`, `PIN_NOT_AN_ISSUE`, `PIN_ASSIGNEE_NO_ACCESS`, `PIN_CONFLICT` (409).
