-- Only Owner and Finance change money: seeded Administrator roles keep VIEW_BUDGET but lose MANAGE_BUDGET.
-- Finance assigns phase and task budgets, so its seeded roles can now see phases and tasks.
-- Custom roles are left as their admins configured them.

delete from role_permission p
where p.action = 'MANAGE_BUDGET'
  and p.role_id in (select r.id from organization_role r where r.code = 'ADMINISTRATOR' and r.seeded);

insert into role_permission (id, role_id, action, scope)
select gen_random_uuid(), r.id, g.action, 'ORGANIZATION'
from organization_role r
join (values ('VIEW_GOAL'), ('VIEW_ACTIVITY')) as g(action) on true
where r.code = 'FINANCE' and r.seeded
  and not exists (select 1 from role_permission p where p.role_id = r.id and p.action = g.action);
