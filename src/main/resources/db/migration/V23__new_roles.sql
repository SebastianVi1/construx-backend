-- Add Resident, Site Manager, and Superintendent seeded roles to every existing organization.
-- New organizations already get them from seeded-roles.properties.
-- Custom roles are untouched.

insert into organization_role (id, organization_id, code, name, system_role, seeded)
select gen_random_uuid(), o.id, r.code, r.name, false, true
from organization o
cross join (values
    ('RESIDENT',      'Residente'),
    ('SITE_MANAGER',  'Jefe de Obra'),
    ('SUPERINTENDENT','Superintendente')
) as r(code, name)
where not exists (
    select 1 from organization_role existing
    where existing.organization_id = o.id and existing.code = r.code
);

insert into role_permission (id, role_id, action, scope)
select gen_random_uuid(), r.id, g.action, g.scope
from organization_role r
join (values
    ('RESIDENT', 'VIEW_PROJECT',    'PROJECT'),
    ('RESIDENT', 'VIEW_GOAL',       'PROJECT'),
    ('RESIDENT', 'MANAGE_GOAL',     'PROJECT'),
    ('RESIDENT', 'VIEW_ACTIVITY',   'PROJECT'),
    ('RESIDENT', 'MANAGE_ACTIVITY', 'PROJECT'),
    ('RESIDENT', 'ASSIGN_ACTIVITY', 'PROJECT'),
    ('RESIDENT', 'VIEW_PLAN',       'PROJECT'),
    ('RESIDENT', 'MANAGE_PLAN',     'PROJECT'),

    ('SITE_MANAGER', 'VIEW_PROJECT',    'PROJECT'),
    ('SITE_MANAGER', 'MANAGE_PROJECT',  'PROJECT'),
    ('SITE_MANAGER', 'VIEW_GOAL',       'PROJECT'),
    ('SITE_MANAGER', 'MANAGE_GOAL',     'PROJECT'),
    ('SITE_MANAGER', 'VIEW_ACTIVITY',   'PROJECT'),
    ('SITE_MANAGER', 'MANAGE_ACTIVITY', 'PROJECT'),
    ('SITE_MANAGER', 'ASSIGN_ACTIVITY', 'PROJECT'),
    ('SITE_MANAGER', 'VIEW_PLAN',       'PROJECT'),
    ('SITE_MANAGER', 'MANAGE_PLAN',     'PROJECT'),

    ('SUPERINTENDENT', 'VIEW_PROJECT',    'ORGANIZATION'),
    ('SUPERINTENDENT', 'VIEW_GOAL',       'ORGANIZATION'),
    ('SUPERINTENDENT', 'MANAGE_GOAL',     'ORGANIZATION'),
    ('SUPERINTENDENT', 'VIEW_ACTIVITY',   'ORGANIZATION'),
    ('SUPERINTENDENT', 'MANAGE_ACTIVITY', 'ORGANIZATION'),
    ('SUPERINTENDENT', 'ASSIGN_ACTIVITY', 'ORGANIZATION'),
    ('SUPERINTENDENT', 'VIEW_PLAN',       'ORGANIZATION'),
    ('SUPERINTENDENT', 'MANAGE_PLAN',     'ORGANIZATION'),
    ('SUPERINTENDENT', 'VIEW_PRICES',     'ORGANIZATION'),
    ('SUPERINTENDENT', 'VIEW_BUDGET',     'ORGANIZATION')
) as g(code, action, scope) on g.code = r.code
where r.seeded = true
  and not exists (select 1 from role_permission p where p.role_id = r.id and p.action = g.action);
