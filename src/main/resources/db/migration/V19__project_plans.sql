-- Site plans: image files kept in private S3 storage. A row is PENDING from the moment the client asks for an
-- upload URL until the API has checked the file in storage, then READY. The id is part of the storage key.

create table project_plan (
    id uuid not null,
    project_id uuid not null,
    name varchar(120) not null,
    description varchar(500),
    original_file_name varchar(255) not null,
    content_type varchar(40) not null check ((content_type in ('image/jpeg','image/png','image/webp'))),
    size_bytes bigint not null check (size_bytes > 0),
    storage_key varchar(300) not null,
    status varchar(20) not null check ((status in ('PENDING','READY'))),
    uploaded_by_membership_id uuid,
    created_at timestamp(6) with time zone not null,
    completed_at timestamp(6) with time zone,
    primary key (id),
    constraint uk_project_plan_storage_key unique (storage_key),
    constraint fk_project_plan_project foreign key (project_id) references project (id),
    constraint fk_project_plan_uploaded_by foreign key (uploaded_by_membership_id) references organization_membership (id)
);

create index ix_project_plan_project_status on project_plan (project_id, status);
