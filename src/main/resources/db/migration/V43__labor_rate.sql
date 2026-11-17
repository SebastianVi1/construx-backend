-- The Labor rate of a person on a Site, historized (docs/pricing.md §1.12, §6.1).
-- Keyed by membership, not by project_access, so removing someone from the Site keeps the history.
-- Rows are immutable; the current rate is the latest row.

create table labor_rate_assignment (
    id uuid not null,
    organization_id uuid not null,
    project_id uuid not null,
    membership_id uuid not null,
    kind varchar(10) not null check ((kind in ('CATALOG','FIXED','NONE'))),
    labor_category_resource_id uuid,
    daily_amount numeric(12,2) check ((daily_amount >= 0)),
    effective_from timestamp(6) with time zone not null,
    set_by_membership_id uuid not null,
    primary key (id),
    constraint ck_labor_rate_assignment_kind check (
        ((kind = 'CATALOG') = (labor_category_resource_id is not null))
        and ((kind = 'FIXED') = (daily_amount is not null)))
);

create index ix_labor_rate_assignment_member on labor_rate_assignment (project_id, membership_id, effective_from desc);
create index ix_labor_rate_assignment_membership on labor_rate_assignment (membership_id, effective_from desc);

alter table if exists labor_rate_assignment add constraint fk_labor_rate_assignment_organization foreign key (organization_id) references organization;
alter table if exists labor_rate_assignment add constraint fk_labor_rate_assignment_project foreign key (project_id) references project;
alter table if exists labor_rate_assignment add constraint fk_labor_rate_assignment_membership foreign key (membership_id) references organization_membership;
alter table if exists labor_rate_assignment add constraint fk_labor_rate_assignment_category foreign key (labor_category_resource_id) references resource;
alter table if exists labor_rate_assignment add constraint fk_labor_rate_assignment_set_by foreign key (set_by_membership_id) references organization_membership;
