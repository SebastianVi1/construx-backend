-- Getting-started guide progress, one row per membership (docs/guide.md). No row means the member never saw the guide.
-- Steps live in a child table, not an array column, so this also runs on H2 in PostgreSQL mode.
create table guide_progress (
    organization_membership_id uuid primary key,
    dismissed_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone not null,
    constraint fk_guide_progress_membership foreign key (organization_membership_id)
        references organization_membership(id) on delete cascade
);

create table guide_progress_step (
    organization_membership_id uuid not null,
    step_id varchar(64) not null,
    status varchar(10) not null,
    constraint pk_guide_progress_step primary key (organization_membership_id, step_id),
    constraint ck_guide_progress_step_status check (status in ('COMPLETED', 'SKIPPED')),
    constraint fk_guide_progress_step_progress foreign key (organization_membership_id)
        references guide_progress(organization_membership_id) on delete cascade
);

-- People who already use the app start with the guide hidden, so it never opens by itself for them.
insert into guide_progress (organization_membership_id, dismissed_at, updated_at)
select id, current_timestamp, current_timestamp from organization_membership;
