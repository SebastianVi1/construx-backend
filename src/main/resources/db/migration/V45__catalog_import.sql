-- Prices, import slice (docs/pricing.md §1.11, §4.1): upload slots and Catalog import jobs, the last import of
-- a Catalog and the import behind a Cost history entry. Portable to H2 (tests): instead of a partial unique
-- index, active_organization_id holds the organization only while the job is PENDING, VALIDATING, VALIDATED
-- or IMPORTING (null once terminal), and a plain unique constraint on it allows one active job per organization.

create table catalog_import_upload (
    id uuid not null,
    organization_id uuid not null,
    kind varchar(12) not null check ((kind in ('PRESUPUESTO','FASAR'))),
    file_key varchar(255) not null,
    file_name varchar(255) not null,
    content_type varchar(120) not null,
    size_bytes bigint not null,
    created_by_membership_id uuid not null,
    created_at timestamp(6) with time zone not null,
    consumed_at timestamp(6) with time zone,
    primary key (id)
);

create index ix_catalog_import_upload_org on catalog_import_upload (organization_id);

create table catalog_import (
    id uuid not null,
    organization_id uuid not null,
    active_organization_id uuid,
    status varchar(12) not null
        check ((status in ('PENDING','VALIDATING','VALIDATED','IMPORTING','DONE','FAILED','CANCELLED'))),
    file_key varchar(255) not null,
    file_name varchar(255) not null,
    file_size bigint not null,
    file_sha256 char(64) not null,
    fasar_file_key varchar(255),
    fasar_file_name varchar(255),
    fasar_file_size bigint,
    fasar_file_sha256 char(64),
    uploaded_by_membership_id uuid not null,
    created_at timestamp(6) with time zone not null,
    validated_at timestamp(6) with time zone,
    confirmed_at timestamp(6) with time zone,
    finished_at timestamp(6) with time zone,
    licence_accepted_at timestamp(6) with time zone,
    licence_accepted_by_membership_id uuid,
    licence_statement varchar(20),
    summary jsonb,
    failure_code varchar(40),
    failure_message varchar(500),
    failure_sheet varchar(40),
    failure_row integer,
    failure_column varchar(40),
    primary key (id),
    constraint uk_catalog_import_active unique (active_organization_id),
    constraint ck_catalog_import_active check (
        (status in ('PENDING','VALIDATING','VALIDATED','IMPORTING') and active_organization_id = organization_id)
        or (status in ('DONE','FAILED','CANCELLED') and active_organization_id is null))
);

create index ix_catalog_import_org_created on catalog_import (organization_id, created_at desc);

alter table catalog add column last_import_id uuid;
alter table resource_cost_history add column import_id uuid;

alter table if exists catalog_import_upload add constraint fk_catalog_import_upload_organization foreign key (organization_id) references organization;
alter table if exists catalog_import_upload add constraint fk_catalog_import_upload_membership foreign key (created_by_membership_id) references organization_membership;
alter table if exists catalog_import add constraint fk_catalog_import_organization foreign key (organization_id) references organization;
alter table if exists catalog_import add constraint fk_catalog_import_uploader foreign key (uploaded_by_membership_id) references organization_membership;
alter table if exists catalog_import add constraint fk_catalog_import_licence_membership foreign key (licence_accepted_by_membership_id) references organization_membership;
alter table if exists catalog add constraint fk_catalog_last_import foreign key (last_import_id) references catalog_import;
alter table if exists resource_cost_history add constraint fk_resource_cost_history_import foreign key (import_id) references catalog_import;
