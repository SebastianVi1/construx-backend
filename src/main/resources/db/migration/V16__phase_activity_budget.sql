-- Optional budgets carved out of the parent's: phases from the site budget, tasks from the phase budget.
-- The allocation cap is enforced by the application; the database only rejects negative amounts.

alter table goal add column allocated_budget numeric(14,2);
alter table goal add constraint goal_allocated_budget_check check (allocated_budget >= 0);

alter table activity add column allocated_budget numeric(14,2);
alter table activity add constraint activity_allocated_budget_check check (allocated_budget >= 0);
