-- Material prices (ADR 0004): an organization's own catalog of materials and suppliers,
-- plus immutable price observations in MXN including IVA.
-- normalized_key holds the case- and whitespace-folded identity computed by the entities,
-- so uniqueness needs no functional or partial index. Archived rows keep their key.

create table material (
    id uuid not null,
    organization_id uuid not null,
    name varchar(160) not null,
    unit varchar(20) not null check ((unit in ('PIECE','BAG','SQUARE_METER','CUBIC_METER','KILOGRAM','LITER','METER'))),
    category varchar(30) not null check ((category in ('CEMENT_AGGREGATES','STEEL','WOOD','ELECTRICAL','PLUMBING','FINISHES'))),
    brand varchar(80),
    normalized_key varchar(300) not null,
    archived_at timestamp(6) with time zone,
    primary key (id),
    constraint uk_material_org_key unique (organization_id, normalized_key)
);

create table supplier (
    id uuid not null,
    organization_id uuid not null,
    name varchar(160) not null,
    branch varchar(120),
    website varchar(255),
    normalized_key varchar(300) not null,
    archived_at timestamp(6) with time zone,
    primary key (id),
    constraint uk_supplier_org_key unique (organization_id, normalized_key)
);

create table price_observation (
    id uuid not null,
    organization_id uuid not null,
    material_id uuid not null,
    supplier_id uuid not null,
    price numeric(12,2) not null check ((price > 0)),
    observed_on date not null,
    sku varchar(64),
    source varchar(10) not null check ((source in ('MANUAL','CSV'))),
    created_at timestamp(6) with time zone not null,
    primary key (id)
);

create index ix_price_observation_material on price_observation (material_id, observed_on);
create index ix_price_observation_supplier on price_observation (supplier_id);

alter table if exists material add constraint fk_material_organization foreign key (organization_id) references organization;
alter table if exists supplier add constraint fk_supplier_organization foreign key (organization_id) references organization;
alter table if exists price_observation add constraint fk_price_observation_organization foreign key (organization_id) references organization;
alter table if exists price_observation add constraint fk_price_observation_material foreign key (material_id) references material;
alter table if exists price_observation add constraint fk_price_observation_supplier foreign key (supplier_id) references supplier;
