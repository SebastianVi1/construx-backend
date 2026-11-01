-- Organization-shared navigation; plans retain their existing project and storage key.
create table plan_folder (
    id uuid primary key,
    organization_id uuid not null references organization(id),
    parent_id uuid references plan_folder(id),
    name varchar(120) not null check (length(trim(name)) > 0),
    constraint ck_plan_folder_not_self check (parent_id is null or parent_id <> id)
);
create index ix_plan_folder_org on plan_folder(organization_id);
create index ix_plan_folder_parent on plan_folder(parent_id);
alter table project_plan add column folder_id uuid references plan_folder(id);
create index ix_project_plan_folder on project_plan(folder_id);
