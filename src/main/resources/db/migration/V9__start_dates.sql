-- Phases and tasks get a real start date. Existing rows are backfilled so every
-- task still lies inside its phase: a task starts on its due date, and a phase
-- starts on its earliest task due date, or on its own due date when it is empty.
alter table goal add column start_at timestamp(6) with time zone;
alter table activity add column start_at timestamp(6) with time zone;

update activity set start_at = due_at;
update goal set start_at = least(goal.due_at,
        coalesce((select min(a.due_at) from activity a where a.goal_id = goal.id), goal.due_at));

alter table goal alter column start_at set not null;
alter table activity alter column start_at set not null;
