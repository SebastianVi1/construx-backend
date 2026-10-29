-- Site plan permissions: allow the two new actions, then grant them to the roles that already exist, matching
-- what seeded-roles.properties gives new organizations. Owner, Administrator: both, organization-wide.
-- Member: both, on the sites they are assigned to. Worker: view only, on their sites. Finance and custom
-- roles get nothing; their admins grant it explicitly.

alter table role_permission drop constraint if exists role_permission_action_check;
alter table role_permission add constraint role_permission_action_check check (action in (
    'MANAGE_ORGANIZATION', 'MANAGE_ROLES', 'MANAGE_MEMBERS', 'VIEW_PROJECT', 'MANAGE_PROJECT',
    'VIEW_GOAL', 'MANAGE_GOAL', 'VIEW_ACTIVITY', 'MANAGE_ACTIVITY', 'ASSIGN_ACTIVITY',
    'VIEW_PRICES', 'MANAGE_PRICES', 'VIEW_BUDGET', 'MANAGE_BUDGET', 'VIEW_PLAN', 'MANAGE_PLAN'));

insert into role_permission (id, role_id, action, scope)
select gen_random_uuid(), r.id, g.action, g.scope
from organization_role r
join (values
    ('OWNER', 'VIEW_PLAN', 'ORGANIZATION'), ('OWNER', 'MANAGE_PLAN', 'ORGANIZATION'),
    ('ADMINISTRATOR', 'VIEW_PLAN', 'ORGANIZATION'), ('ADMINISTRATOR', 'MANAGE_PLAN', 'ORGANIZATION'),
    ('MEMBER', 'VIEW_PLAN', 'PROJECT'), ('MEMBER', 'MANAGE_PLAN', 'PROJECT'),
    ('WORKER', 'VIEW_PLAN', 'PROJECT')
) as g(code, action, scope) on g.code = r.code
where ((r.code = 'OWNER' and r.system_role) or (r.code <> 'OWNER' and r.seeded))
  and not exists (select 1 from role_permission p where p.role_id = r.id and p.action = g.action);
