-- An expense can be charged to one task of its site and records who created it, so a task's Lead can
-- record and edit their own purchases (ADR 0005). Earlier expenses have neither.

alter table expense add column activity_id uuid;
alter table expense add column created_by_membership_id uuid;
alter table expense add constraint fk_expense_activity foreign key (activity_id) references activity (id);
alter table expense add constraint fk_expense_created_by
    foreign key (created_by_membership_id) references organization_membership (id);

create index ix_expense_activity on expense (activity_id);
