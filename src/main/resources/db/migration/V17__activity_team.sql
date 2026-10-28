-- A task has a team instead of one assignee. Every member holds a task role; the application keeps
-- exactly one LEAD per task. Each existing assignee becomes the Lead of their task.

create table activity_assignee (
    id uuid not null,
    activity_id uuid not null,
    access_id uuid not null,
    task_role varchar(20) not null,
    primary key (id),
    constraint uk_activity_assignee unique (activity_id, access_id),
    constraint activity_assignee_role_check check (task_role in ('LEAD', 'FOREMAN', 'CREW')),
    constraint fk_activity_assignee_activity foreign key (activity_id) references activity on delete cascade,
    constraint fk_activity_assignee_access foreign key (access_id) references project_access
);
create index ix_activity_assignee_access on activity_assignee (access_id);

insert into activity_assignee (id, activity_id, access_id, task_role)
select gen_random_uuid(), id, assignee_access_id, 'LEAD' from activity;

alter table activity drop column assignee_access_id;
