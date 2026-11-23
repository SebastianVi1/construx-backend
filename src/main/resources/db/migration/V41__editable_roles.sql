-- Editable roles (contract roles §2, ADR 0010). Built-in roles start uncustomized with no known actions; the
-- startup sync (BuiltInRoleSync) brings them to built-in-roles.yml on the first boot. Runs on H2 in PostgreSQL
-- mode too: no arrays, no ON CONFLICT.
alter table organization_role add column description varchar(280);
alter table organization_role add column customized boolean not null default false;
alter table organization_role add column version bigint not null default 0;

-- The catalog actions a Built-in role has already been reconciled against, so the sync only adds defaults for
-- actions new to a customized role. No check on action: new catalog actions need no migration here.
create table organization_role_known_action (
    role_id uuid not null,
    action varchar(40) not null,
    constraint pk_organization_role_known_action primary key (role_id, action),
    constraint fk_organization_role_known_action_role foreign key (role_id)
        references organization_role(id) on delete cascade
);
