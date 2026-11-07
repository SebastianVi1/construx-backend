create table floor_plan_zone (
    id uuid primary key,
    floor_plan_id uuid not null references floor_plan(id),
    name varchar(120) not null check (length(trim(name)) > 0),
    description varchar(500),
    status varchar(20) not null default 'NOT_STARTED'
        check (status in ('NOT_STARTED','IN_PROGRESS','ON_HOLD','DONE')),
    polygon_points jsonb not null,
    area_m2 double precision not null check (area_m2 >= 0),
    perimeter_m double precision not null check (perimeter_m >= 0),
    version bigint not null default 0,
    created_by_membership_id uuid not null references organization_membership(id),
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    deleted_at timestamp with time zone
);
