create table plan_pin (
    id uuid primary key,
    floor_plan_id uuid not null references floor_plan(id),
    kind varchar(20) not null check (kind in ('ISSUE','PHOTO')),
    x double precision not null check (x >= 0),
    y double precision not null check (y >= 0),
    title varchar(120),
    description varchar(500),
    status varchar(20) check (status in ('OPEN','RESOLVED','CLOSED')),
    assignee_membership_id uuid references organization_membership(id),
    created_by_membership_id uuid not null references organization_membership(id),
    version bigint not null default 0,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    resolved_at timestamp with time zone,
    closed_at timestamp with time zone,
    deleted_at timestamp with time zone,
    constraint ck_plan_pin_issue_fields check (
        (kind = 'ISSUE' and status is not null and title is not null)
        or (kind = 'PHOTO' and status is null and assignee_membership_id is null)
    )
);
create index idx_plan_pin_floor_plan on plan_pin(floor_plan_id);

create table plan_pin_photo (
    id uuid primary key,
    pin_id uuid not null references plan_pin(id),
    storage_key varchar(300) not null unique,
    phase varchar(20) not null check (phase in ('REPORT','RESOLUTION')),
    content_type varchar(100) not null,
    size_bytes bigint not null check (size_bytes > 0),
    uploaded_by_membership_id uuid not null references organization_membership(id),
    created_at timestamp with time zone not null
);
create index idx_plan_pin_photo_pin on plan_pin_photo(pin_id);
