create table crew (
    id uuid primary key,
    organization_id uuid not null references organization(id),
    name varchar(120) not null check (length(trim(name)) > 0),
    leader_membership_id uuid not null references organization_membership(id),
    created_at timestamp with time zone not null
);

create table crew_member (
    crew_id uuid not null references crew(id) on delete cascade,
    membership_id uuid not null references organization_membership(id),
    primary key (crew_id, membership_id)
);

create table equipment (
    id uuid primary key,
    organization_id uuid not null references organization(id),
    type varchar(80) not null check (length(trim(type)) > 0),
    identifier varchar(80) not null check (length(trim(identifier)) > 0),
    daily_cost_cents bigint check (daily_cost_cents is null or daily_cost_cents >= 0),
    active boolean not null default true,
    created_at timestamp with time zone not null
);
