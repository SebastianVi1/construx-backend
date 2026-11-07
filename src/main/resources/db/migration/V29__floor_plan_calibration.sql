create table floor_plan_calibration (
    id uuid primary key,
    floor_plan_id uuid not null unique references floor_plan(id),
    meters_per_px double precision not null check (meters_per_px > 0),
    point_a_x double precision not null,
    point_a_y double precision not null,
    point_b_x double precision not null,
    point_b_y double precision not null,
    length_meters double precision not null check (length_meters > 0),
    calibrated_by_membership_id uuid not null references organization_membership(id),
    created_at timestamp with time zone not null
);
