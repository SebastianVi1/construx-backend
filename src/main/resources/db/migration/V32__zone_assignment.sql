create table zone_assignment (
    id uuid primary key,
    zone_id uuid not null references floor_plan_zone(id),
    membership_id uuid references organization_membership(id),
    crew_id uuid references crew(id),
    equipment_id uuid references equipment(id),
    activity varchar(255) not null check (length(trim(activity)) > 0),
    start_date date not null,
    end_date date,
    shift varchar(20) not null check (shift in ('MORNING','AFTERNOON','FULL_DAY')),
    assigned_by_membership_id uuid not null references organization_membership(id),
    created_at timestamp with time zone not null,
    closed_at timestamp with time zone,
    constraint ck_zone_assignment_resource check (
        num_nonnulls(membership_id, crew_id, equipment_id) = 1
    )
);
