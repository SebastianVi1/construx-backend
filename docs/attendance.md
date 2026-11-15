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

## Against the plan

Attendance of a day compares that day's Check-ins with the zone plan. Nothing but Check-ins is stored (ADR 0007).

- **Expected**: a person with an open zone assignment covering the day in a live zone of the Site, directly or as
  a member of an assigned Crew (the Crew's members as they are now). Open means not closed, started on or before
  the day and not ended before it (the end date is included). Equipment is never expected.
- A person with several such assignments has one expected zone: that of the assignment that started earliest.
- `PRESENT`: expected, with an active Check-in that day. Checked in to another zone than the expected one is still
  `PRESENT` and planned; the row carries both zones.
- `ABSENT`: expected, with no active Check-in. Derived on every read, never stored; `checkIn` is null.
- `UNPLANNED`: an active Check-in without being expected. Always allowed.
- Assignments in a deleted zone or on another Site expect nobody. An expected member who is suspended or has left
  and has no Check-in is left out rather than shown absent.
- A past day is read against the assignments as they stand now: closing an assignment today also stops it from
  counting on earlier days.
- The floor plan module owns the plan side: `SiteZones.expected(siteId, day)`. It does not depend on `attendance`.

## Rules

- A person may check in on several Sites in one day and is present on each.
- A Check-in is made in a zone of the Site or in no zone. There is no check-out: a later Check-in the same day to
  another zone (or to no zone) adds a record and moves the person; the earlier ones stay as history.
- A person's row shows their current Check-in, the latest of the day that was not removed; its zone is their
  current zone, and no zone means present with no zone.
- A Check-in is made for a person: the caller himself or herself, or, by someone answering for them, another
  active member of the company, with or without access to the Site. Its recorder is the member who made it.
- Checking in to the zone (or no zone) of the person's current Check-in stores nothing and returns that
  Check-in. Going back to an earlier zone after a move is a new Check-in.
- Only a live zone of a floor plan of this Site can be checked in to. A Check-in keeps its zone after the zone is
  deleted and keeps showing the zone's name.
- A Check-in made by mistake can be removed, only on the day it belongs to. The row is kept and marked with who
  removed it and when (`removed_at`, `removed_by_membership_id`); it is never deleted. A removed Check-in is
  ignored everywhere: the person falls back to their previous Check-in of the day (and its zone), or is no longer
  present, and may check in again (a new Check-in is stored, also to the zone of the removed one).
- Past days are locked: no removal and no backdating.
- `name` is the profile's full name, or the account email when there is no profile name.
- Check-ins are not written to the audit table; the table is itself the log.

## Permissions

No permission action of its own. Reading Attendance and checking oneself in need `VIEW_PROJECT` on the Site:
`ORGANIZATION` scope, or `PROJECT` scope with access to the Site. Checking in someone else needs `VIEW_PROJECT`
on the Site and one of: `MANAGE_PLAN` on the Site, for any active member of the company; or being the leader of
a Crew the person belongs to (the leader still opens the Site like anybody else). A member of the company
without access to the Site cannot check in there, only be checked in by someone with that right. Removing a
Check-in needs `VIEW_PROJECT` and one of: being the member who recorded it, or `MANAGE_PLAN` on the Site. The
person checked in by someone else cannot remove that Check-in unless they hold `MANAGE_PLAN`.

## Endpoints

Base path `/api/organizations/{organizationId}/projects/{projectId}/attendance`. Checks run in this order:
unauthenticated -> 401, non-member or inactive member -> 404, unknown Site or Site of another company -> 404,
missing `VIEW_PROJECT` -> 403. `POST` and `DELETE` need the CSRF header.

| Method and path | Notes |
|---|---|
| `GET /` | The Attendance of a day. Optional `?date=YYYY-MM-DD`; without it, today. A past day is read-only. A date after today or one that is not a real `YYYY-MM-DD` day -> 400 (`code` `INVALID_ARGUMENT`). 200. |
| `GET /zones` | The zones a person can check in to: live zones of all floor plans of the Site, ordered by floor plan name and then as drawn. Needs only `VIEW_PROJECT`, not `VIEW_PLAN`. 200 with a list of `zone` objects (below), empty without floor plans. |
| `POST /check-ins` | Checks the caller, or another person, in today. Body `{ "membershipId": "…", "zoneId": "…" }`; `membershipId` null or absent means the caller, `zoneId` null or absent, `{}` or no body mean no zone. 201 with the Check-in, also when it already existed. Unknown or deleted zone, or a zone of another Site -> 404 and nothing is stored. |
| `POST /roll-calls` | Checks selected active members of an expected Crew in today. Body `{ "crewId": "…", "membershipIds": ["…"], "zoneId": "…" }`; `zoneId` null means no zone. Empty lists or membership IDs outside the Crew -> 400 with no writes; unknown Crew or invalid zone -> 404; callers must lead the Crew or hold `MANAGE_PLAN` (403). Duplicate IDs are processed once. Each member's current same-zone Check-in is returned without another write. The request validates all members before storing any Check-in and runs transactionally. 201 with a list of Check-ins. |
| `DELETE /check-ins/{checkInId}` | Removes a Check-in. 204, no body. |

`POST /check-ins` answers, in this order: unknown, suspended, departed or other-company member -> 404 (nothing
stored); caller neither a `MANAGE_PLAN` holder nor the leader of a Crew the person belongs to -> 403; unknown or
deleted zone, or a zone of another Site -> 404 (nothing stored); otherwise 201.

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
      "planned": true,
      "expectedZone": { "id": "…", "name": "Slab B", "floorPlanId": "…", "floorPlanName": "Level 1" },
      "checkIn": {
        "id": "…",
        "membershipId": "…",
        "zone": { "id": "…", "name": "Slab A", "floorPlanId": "…", "floorPlanName": "Level 1" },
        "date": "2026-03-10",
        "checkedInAt": "2026-03-10T15:00:00Z",
        "recordedBy": { "membershipId": "…", "name": "Ana Perez" }
      },
      "removable": true,
      "checkInAllowed": false
    }
  ],
  "me": {
    "membershipId": "…",
    "expectedZone": { "id": "…", "name": "Slab B", "floorPlanId": "…", "floorPlanName": "Level 1" },
    "canCheckInOthers": true
  }
}
```

`people` is ordered by name, ignoring case. `people` has everyone with a Check-in
that day and everyone expected. `state` is `PRESENT`, `ABSENT` or `UNPLANNED` (above). `planned` is true for
`PRESENT` and `ABSENT`; `expectedZone` is where the plan expects the person and null when not planned. `checkIn`
is the person's current Check-in and null for `ABSENT`; `checkIn.zone` is their current zone. `today` is false
for a past day. `removable` is true when the
caller may remove the row's Check-in now: the day is today and the caller recorded it or holds `MANAGE_PLAN`; it
is always false on a past day. `avatarUrl` is a download link
valid for one hour, or null. `checkIn.zone` is null for present with no zone.
`checkInAllowed` is true only for an absent person the caller may check in today; it is false for
other rows and past days. A Crew leader sees it only for members of a Crew they lead.
`me.membershipId` is the caller, so a client finds its own row in `people`; `me.expectedZone` is where the plan
expects the caller that day (null when not expected), for preselecting the zone when checking in;
`me.canCheckInOthers` is true for a member who may record a Check-in for someone else (`MANAGE_PLAN`, or leading
a Crew); the page uses each row's `checkInAllowed` for the action. The body of `POST /check-ins` is the `checkIn` object above.

`me.rollCallCrews` lists today's expected Crews the caller may take roll for. A plan manager sees every expected Crew; a Crew leader sees only Crews they lead. Each entry has `crewId`, `name`, `expectedZone`, and `members`; each member has `membershipId`, `name`, and their current `checkIn`, or null when absent. Past Attendance reads return an empty `rollCallCrews` list. The submitted `zoneId` may differ from or be null instead of `expectedZone`.

## Site Report

The Site Report PDF ends with a section "Asistencia", after the team: the days with at least one Check-in, the
average people per day (person-days over those days, one decimal) and, per person, the days present and the last
day present, most days first and then by name. A person counts once per day, removed Check-ins are ignored, and
people who have left the company keep their name. A Site without Check-ins prints "Sin registros de entrada." and
no table. The report module gets these figures from `AttendanceService.summary(SiteScope)` only.
