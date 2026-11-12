# Attendance

A **Check-in** records one person present on a Site at a moment, plus who recorded it. **Attendance** is who is on
a Site on one day, built from that day's Check-ins. There is no check-out and there are no hours: Attendance must
not be used for pay (ADR 0007). Module `modules/attendance` (`CheckIn`, `AttendanceService`,
`AttendanceController`), schema `V37__check_in.sql`. Other modules reach it only through `AttendanceService`.

## The day

A Check-in belongs to the calendar date of the configured time zone (`app.time-zone`, env `APP_TIME_ZONE`, default
`America/Mexico_City`) at the moment it is made; the exact moment is stored too (`checkedInAt`, UTC). Only the
current day accepts Check-ins. Clients never send their own "today": they read Attendance and use the `date` it
returns.

## Rules

- A person may check in on several Sites in one day and is present on each.
- Checking in again the same day with nothing changed stores nothing and returns the Check-in already made.
- A person's row shows their current Check-in, the latest of the day that was not removed.
- A Check-in made by mistake can be removed, only on the day it belongs to. The row is kept and marked with who
  removed it and when (`removed_at`, `removed_by_membership_id`); it is never deleted. A removed Check-in is
  ignored everywhere: the person falls back to their previous Check-in of the day, or is no longer present, and
  may check in again (a new Check-in is stored).
- Past days are locked: no removal and no backdating.
- `name` is the profile's full name, or the account email when there is no profile name.
- Check-ins are not written to the audit table; the table is itself the log.

## Permissions

No permission action of its own. Reading Attendance and checking oneself in need `VIEW_PROJECT` on the Site:
`ORGANIZATION` scope, or `PROJECT` scope with access to the Site. Removing a Check-in needs `VIEW_PROJECT` and
one of: being the member who recorded it, or `MANAGE_PLAN` on the Site. The person checked in by someone else
cannot remove that Check-in unless they hold `MANAGE_PLAN`.

## Endpoints

Base path `/api/organizations/{organizationId}/projects/{projectId}/attendance`. Checks run in this order:
unauthenticated -> 401, non-member or inactive member -> 404, unknown Site or Site of another company -> 404,
missing `VIEW_PROJECT` -> 403. `POST` and `DELETE` need the CSRF header.

| Method and path | Notes |
|---|---|
| `GET /` | Today's Attendance. 200. |
| `POST /check-ins` | Checks the caller in today. No body needed (`{}` is accepted). 201 with the Check-in, also when it already existed. |
| `DELETE /check-ins/{checkInId}` | Removes a Check-in. 204, no body. |

`DELETE /check-ins/{checkInId}` answers, in this order: unknown Check-in or one of another Site -> 404; caller
neither its recorder nor a `MANAGE_PLAN` holder -> 403; already removed -> 204 with no change (the first removal
is kept); Check-in of another day -> 409 (`code` `STATE_CONFLICT`); otherwise 204.

Attendance:

```json
{
  "date": "2026-03-10",
  "today": true,
  "people": [
    {
      "membershipId": "…",
      "name": "Ana Perez",
      "avatarUrl": null,
      "state": "PRESENT",
      "checkIn": {
        "id": "…",
        "membershipId": "…",
        "date": "2026-03-10",
        "checkedInAt": "2026-03-10T15:00:00Z",
        "recordedBy": { "membershipId": "…", "name": "Ana Perez" }
      },
      "removable": true
    }
  ],
  "me": { "membershipId": "…" }
}
```

`people` is ordered by name, ignoring case. `state` is always `PRESENT` for now. `removable` is true when the
caller may remove the row's Check-in now: the day is today and the caller recorded it or holds `MANAGE_PLAN`; it
is always false on a past day. `avatarUrl` is a download link
valid for one hour, or null. `me.membershipId` is the caller, so a client finds its own row in `people`. The body
of `POST /check-ins` is the `checkIn` object above.
