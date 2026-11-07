create table floor_plan (
    id uuid primary key,
    project_id uuid not null references project(id),
    name varchar(120) not null check (length(trim(name)) > 0),
    description varchar(500),
    level varchar(60),
    page_number int not null default 1 check (page_number >= 1),
    original_key varchar(300) not null unique,
    rendered_key varchar(300),
    status varchar(20) not null check (status in ('PENDING','PROCESSING','READY','ERROR')),
    error_reason varchar(500),
    uploader_membership_id uuid not null references organization_membership(id),
    created_at timestamp with time zone not null,
    completed_at timestamp with time zone
);
