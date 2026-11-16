-- Prices (docs/pricing.md §1.1-1.6, §7; ADR 0008): the organization's Catalog of Resources with
-- per-organization Units and Families, a Cost history per Resource, and the catalog columns on supplier.
-- The former material / price_observation rows are converted and the old tables dropped.
-- Portable to H2 (tests): no expression or partial indexes. A plain unique (organization_id, code)
-- on supplier already lets many rows have a null code.

create table catalog (
    id uuid not null,
    organization_id uuid not null,
    currency varchar(3) not null default 'MXN' check ((currency in ('MXN'))),
    created_at timestamp(6) with time zone not null,
    primary key (id),
    constraint uk_catalog_organization unique (organization_id)
);

create table unit (
    id uuid not null,
    organization_id uuid not null,
    code varchar(20) not null,
    description varchar(120),
    source varchar(20) not null check ((source in ('SEED','MANUAL','NEODATA_CONSTRUBASE'))),
    archived_at timestamp(6) with time zone,
    primary key (id),
    constraint uk_unit_org_code unique (organization_id, code)
);

create table family (
    id uuid not null,
    organization_id uuid not null,
    code varchar(40) not null,
    name varchar(160) not null,
    source varchar(20) not null check ((source in ('SEED','MANUAL','NEODATA_CONSTRUBASE'))),
    archived_at timestamp(6) with time zone,
    primary key (id),
    constraint uk_family_org_code unique (organization_id, code)
);

alter table supplier add column code varchar(40);
alter table supplier add column rfc varchar(13);
alter table supplier add column city varchar(160);
alter table supplier add column state varchar(80);
alter table supplier add column address varchar(255);
alter table supplier add column neighborhood varchar(120);
alter table supplier add column postal_code varchar(10);
alter table supplier add column phone varchar(40);
alter table supplier add column mobile varchar(40);
alter table supplier add column fax varchar(40);
alter table supplier add column email varchar(160);
alter table supplier add column contact varchar(160);
alter table supplier add column notes varchar(500);
alter table supplier add column is_default boolean not null default false;
alter table supplier add column source varchar(20) not null default 'MANUAL';
alter table supplier add constraint supplier_source_check check ((source in ('MANUAL','CSV','NEODATA_CONSTRUBASE')));
alter table supplier add constraint uk_supplier_org_code unique (organization_id, code);

create table resource (
    id uuid not null,
    organization_id uuid not null,
    code varchar(40) not null,
    type varchar(10) not null check ((type in ('MATERIAL','LABOR','EQUIPMENT','WORK_ITEM'))),
    name varchar(160) not null,
    description text,
    unit_id uuid not null,
    family_id uuid,
    supplier_id uuid,
    brand varchar(80),
    is_percentage boolean not null default false,
    percentage_base smallint,
    is_composite boolean not null default false,
    is_hourly_cost boolean not null default false,
    discontinued boolean not null default false,
    reference varchar(120),
    sap_code varchar(40),
    technical_sheet varchar(255),
    grouper varchar(40),
    procedure text,
    source varchar(20) not null check ((source in ('MANUAL','CSV','NEODATA_CONSTRUBASE'))),
    current_cost numeric(14,2),
    cost_updated_at timestamp(6) with time zone,
    imported_cost numeric(14,2),
    imported_cost_on date,
    missing_from_import_at timestamp(6) with time zone,
    archived_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null,
    primary key (id),
    constraint uk_resource_org_code unique (organization_id, code)
);

create index ix_resource_org_type_code on resource (organization_id, type, code);
create index ix_resource_org_name on resource (organization_id, name);

create table resource_cost_history (
    id uuid not null,
    organization_id uuid not null,
    resource_id uuid not null,
    amount numeric(14,2) not null check ((amount >= 0)),
    effective_from timestamp(6) with time zone not null,
    source varchar(20) not null check ((source in ('MANUAL','CSV','NEODATA_CONSTRUBASE','FASAR'))),
    recorded_by_membership_id uuid,
    supplier_id uuid,
    sku varchar(64),
    note varchar(160),
    primary key (id)
);

create index ix_resource_cost_history_resource on resource_cost_history (resource_id, effective_from desc);
create index ix_resource_cost_history_org on resource_cost_history (organization_id);
create index ix_unit_org on unit (organization_id);
create index ix_family_org on family (organization_id);

alter table if exists catalog add constraint fk_catalog_organization foreign key (organization_id) references organization;
alter table if exists unit add constraint fk_unit_organization foreign key (organization_id) references organization;
alter table if exists family add constraint fk_family_organization foreign key (organization_id) references organization;
alter table if exists resource add constraint fk_resource_organization foreign key (organization_id) references organization;
alter table if exists resource add constraint fk_resource_unit foreign key (unit_id) references unit;
alter table if exists resource add constraint fk_resource_family foreign key (family_id) references family;
alter table if exists resource add constraint fk_resource_supplier foreign key (supplier_id) references supplier;
alter table if exists resource_cost_history add constraint fk_resource_cost_history_organization foreign key (organization_id) references organization;
alter table if exists resource_cost_history add constraint fk_resource_cost_history_resource foreign key (resource_id) references resource;
alter table if exists resource_cost_history add constraint fk_resource_cost_history_membership foreign key (recorded_by_membership_id) references organization_membership;
alter table if exists resource_cost_history add constraint fk_resource_cost_history_supplier foreign key (supplier_id) references supplier;

-- §7.1 One Catalog per organization that has materials or suppliers.
insert into catalog (id, organization_id, currency, created_at)
select gen_random_uuid(), o.organization_id, 'MXN', current_timestamp
from (select organization_id from material union select organization_id from supplier) o;

-- §7.2 The old unit and category enums become seeded Units and Families of organizations with materials.
insert into unit (id, organization_id, code, description, source)
select gen_random_uuid(), o.organization_id, u.code, u.description, 'SEED'
from (select distinct organization_id from material) o
cross join (values
    ('PZA', 'Pieza'), ('SACO', 'Saco'), ('M2', 'Metro cuadrado'), ('M3', 'Metro cúbico'),
    ('KG', 'Kilogramo'), ('LT', 'Litro'), ('M', 'Metro')
) as u(code, description);

insert into family (id, organization_id, code, name, source)
select gen_random_uuid(), o.organization_id, f.code, f.name, 'SEED'
from (select distinct organization_id from material) o
cross join (values
    ('CEMENT_AGGREGATES', 'Cemento y agregados'), ('STEEL', 'Acero'), ('WOOD', 'Madera'),
    ('ELECTRICAL', 'Eléctrico'), ('PLUMBING', 'Plomería'), ('FINISHES', 'Acabados')
) as f(code, name);

-- §7.3 Materials keep their id; the code is generated in name order.
insert into resource (id, organization_id, code, type, name, unit_id, family_id, brand, source,
                      archived_at, created_at, updated_at)
select m.id, m.organization_id, m.code, 'MATERIAL', m.name, u.id, f.id, m.brand, 'MANUAL',
       m.archived_at, current_timestamp, current_timestamp
from (select material.*,
             'MAT-' || lpad(cast(row_number() over (partition by organization_id
                 order by name, brand nulls first, id) as varchar), 6, '0') as code,
             case unit when 'PIECE' then 'PZA' when 'BAG' then 'SACO' when 'SQUARE_METER' then 'M2'
                       when 'CUBIC_METER' then 'M3' when 'KILOGRAM' then 'KG' when 'LITER' then 'LT'
                       when 'METER' then 'M' end as unit_code
      from material) m
join unit u on u.organization_id = m.organization_id and u.code = m.unit_code
join family f on f.organization_id = m.organization_id and f.code = m.category;

-- §7.4 Observations become Cost history. Effective time: the observation day in Mexico City plus the
-- time of day the row was created, so same-day rows keep their order. Written with a fixed -06:00
-- offset (Mexico City since 2022) instead of AT TIME ZONE so the script means the same on H2.
insert into resource_cost_history (id, organization_id, resource_id, amount, effective_from, source,
                                   recorded_by_membership_id, supplier_id, sku)
select p.id, p.organization_id, p.material_id, p.price,
       cast(cast(p.observed_on as varchar) || ' ' || cast(cast(p.created_at as time(6)) as varchar) || '-06:00'
            as timestamp(6) with time zone),
       p.source, null, p.supplier_id, p.sku
from price_observation p;

-- §7.5 The current cost is the latest history entry.
update resource set
    current_cost = (select h.amount from resource_cost_history h where h.resource_id = resource.id
                    order by h.effective_from desc, h.id desc limit 1),
    cost_updated_at = (select max(h.effective_from) from resource_cost_history h where h.resource_id = resource.id)
where exists (select 1 from resource_cost_history h where h.resource_id = resource.id);

-- §7.7 The old catalog is gone.
drop table price_observation;
drop table material;
