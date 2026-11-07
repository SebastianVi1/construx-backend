create table audit_event (
    id uuid primary key,
    entity_type varchar(80) not null,
    entity_id uuid not null,
    action varchar(40) not null,
    actor_membership_id uuid references organization_membership(id),
    before_state text,
    after_state text,
    occurred_at timestamp with time zone not null
);
create index idx_audit_event_entity on audit_event(entity_type, entity_id);
