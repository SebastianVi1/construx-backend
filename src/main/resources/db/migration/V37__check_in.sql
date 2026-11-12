-- Attendance: one row per Check-in (ADR 0007). No check-out; a later row the same day moves the person.
-- zone_id and the removed_* columns are part of the record from the start; they stay null until the zone
-- Check-in and removal features use them.
create table check_in (
    id uuid primary key,
    project_id uuid not null references project(id),
    membership_id uuid not null references organization_membership(id),
    zone_id uuid references floor_plan_zone(id),
    check_in_day date not null,
    checked_in_at timestamp with time zone not null,
    recorded_by_membership_id uuid not null references organization_membership(id),
    removed_at timestamp with time zone,
    removed_by_membership_id uuid references organization_membership(id),
    constraint ck_check_in_removal check ((removed_at is null) = (removed_by_membership_id is null))
);
create index idx_check_in_project_day on check_in(project_id, check_in_day);
create index idx_check_in_membership_day on check_in(membership_id, check_in_day);
