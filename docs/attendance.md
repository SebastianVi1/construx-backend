# Attendance

A **Check-in** records one person present on a Site at a moment, optionally in a floor plan zone, plus who
recorded it. **Attendance** is who is on
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
- A Check-in is made in a zone of the Site or in no zone. There is no check-out: a later Check-in the same day to
  another zone (or to no zone) adds a record and moves the person; the earlier ones stay as history.
- A person's row shows their current Check-in, the latest of the day; its zone is their current zone, and no zone
  means present with no zone.
- Checking in to the zone (or no zone) of the person's latest Check-in that day stores nothing and returns that
  Check-in. Going back to an earlier zone after a move is a new Check-in.
- Only a live zone of a floor plan of this Site can be checked in to. A Check-in keeps its zone after the zone is
  deleted and keeps showing the zone's name.
- `name` is the profile's full name, or the account email when there is no profile name.
- Check-ins are not written to the audit table; the table is itself the log.

## Permissions

No permission action of its own. Reading Attendance and checking oneself in need `VIEW_PROJECT` on the Site:
`ORGANIZATION` scope, or `PROJECT` scope with access to the Site.

## Endpoints

Base path `/api/organizations/{organizationId}/projects/{projectId}/attendance`. Checks run in this order:
unauthenticated -> 401, non-member or inactive member -> 404, unknown Site or Site of another company -> 404,
missing `VIEW_PROJECT` -> 403. `POST` needs the CSRF header.

| Method and path | Notes |
|---|---|
| `GET /` | Today's Attendance. 200. |
| `GET /zones` | The zones a person can check in to: live zones of all floor plans of the Site, ordered by floor plan name and then as drawn. Needs only `VIEW_PROJECT`, not `VIEW_PLAN`. 200 with a list of `zone` objects (below), empty without floor plans. |
| `POST /check-ins` | Checks the caller in today. Body `{ "zoneId": "…" }`; `zoneId` null or absent, `{}` or no body mean no zone. 201 with the Check-in, also when it already existed. Unknown or deleted zone, or a zone of another Site -> 404 and nothing is stored. |

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
        "zone": { "id": "…", "name": "Slab A", "floorPlanId": "…", "floorPlanName": "Level 1" },
        "date": "2026-03-10",
        "checkedInAt": "2026-03-10T15:00:00Z",
        "recordedBy": { "membershipId": "…", "name": "Ana Perez" }
      }
    }
  ],
  "me": { "membershipId": "…" }
}
```

`people` is ordered by name, ignoring case. `state` is always `PRESENT` for now. `avatarUrl` is a download link
valid for one hour, or null. `checkIn.zone` is null for present with no zone.
`me.membershipId` is the caller, so a client finds its own row in `people`. The body
of `POST /check-ins` is the `checkIn` object above.
