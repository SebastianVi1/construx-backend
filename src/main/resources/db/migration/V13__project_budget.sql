-- Site budget: an optional allocated amount per project, plus the two permissions that guard it.
-- Only Owner, Administrator and Finance get them, matching what seeded-roles.properties gives
-- new organizations. Custom roles get nothing; their admins grant it explicitly.

alter table project add column allocated_budget numeric(14,2);
alter table project add constraint project_allocated_budget_check check (allocated_budget >= 0);

alter table role_permission drop constraint if exists role_permission_action_check;
alter table role_permission add constraint role_permission_action_check check (action in (
    'MANAGE_ORGANIZATION', 'MANAGE_ROLES', 'MANAGE_MEMBERS', 'VIEW_PROJECT', 'MANAGE_PROJECT',
    'VIEW_GOAL', 'MANAGE_GOAL', 'VIEW_ACTIVITY', 'MANAGE_ACTIVITY', 'ASSIGN_ACTIVITY',
    'VIEW_PRICES', 'MANAGE_PRICES', 'VIEW_BUDGET', 'MANAGE_BUDGET'));

insert into role_permission (id, role_id, action, scope)
select gen_random_uuid(), r.id, g.action, 'ORGANIZATION'
from organization_role r
join (values
    ('OWNER', 'VIEW_BUDGET'), ('OWNER', 'MANAGE_BUDGET'),
    ('ADMINISTRATOR', 'VIEW_BUDGET'), ('ADMINISTRATOR', 'MANAGE_BUDGET'),
    ('FINANCE', 'VIEW_BUDGET'), ('FINANCE', 'MANAGE_BUDGET')
) as g(code, action) on g.code = r.code
where ((r.code = 'OWNER' and r.system_role) or (r.code <> 'OWNER' and r.seeded))
  and not exists (select 1 from role_permission p where p.role_id = r.id and p.action = g.action);
