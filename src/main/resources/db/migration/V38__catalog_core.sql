-- Cost catalog core (docs/pricing.md §1.1-§1.6, ADR 0008): one Catalog per organization with its Units, Families,
-- Resources and immutable Cost history, then the conversion of the former material prices (§7).
-- Import columns (catalog.last_import_id, resource_cost_history.import_id) come with the import slice.
-- Portable on PostgreSQL and H2: supplier codes use a plain unique constraint (NULLs are distinct, so suppliers
-- without a code are unaffected, as with a partial index) and the name index is on the plain name column.

create table catalog (
    id uuid not null,
    organization_id uuid not null,
    currency varchar(3) default 'MXN' not null,
    created_at timestamp(6) with time zone not null,
    primary key (id),
    constraint uk_catalog_organization unique (organization_id),
    constraint fk_catalog_organization foreign key (organization_id) references organization (id)
);

create table unit (
    id uuid not null,
    organization_id uuid not null,
    code varchar(20) not null,
    description varchar(120),
    source varchar(20) not null check ((source in ('SEED','MANUAL','NEODATA_CONSTRUBASE'))),
    archived_at timestamp(6) with time zone,
    primary key (id),
    constraint uk_unit_org_code unique (organization_id, code),
    constraint fk_unit_organization foreign key (organization_id) references organization (id)
);

create table family (
    id uuid not null,
    organization_id uuid not null,
    code varchar(40) not null,
    name varchar(160) not null,
    source varchar(20) not null check ((source in ('SEED','MANUAL','NEODATA_CONSTRUBASE'))),
    archived_at timestamp(6) with time zone,
    primary key (id),
    constraint uk_family_org_code unique (organization_id, code),
    constraint fk_family_organization foreign key (organization_id) references organization (id)
);

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
    is_percentage boolean default false not null,
    percentage_base smallint,
    is_composite boolean default false not null,
    is_hourly_cost boolean default false not null,
    discontinued boolean default false not null,
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
    constraint uk_resource_org_code unique (organization_id, code),
    constraint fk_resource_organization foreign key (organization_id) references organization (id),
    constraint fk_resource_unit foreign key (unit_id) references unit (id),
    constraint fk_resource_family foreign key (family_id) references family (id),
    constraint fk_resource_supplier foreign key (supplier_id) references supplier (id)
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
    primary key (id),
    constraint fk_cost_history_organization foreign key (organization_id) references organization (id),
    constraint fk_cost_history_resource foreign key (resource_id) references resource (id),
    constraint fk_cost_history_recorded_by foreign key (recorded_by_membership_id)
        references organization_membership (id),
    constraint fk_cost_history_supplier foreign key (supplier_id) references supplier (id)
);

create index ix_cost_history_organization on resource_cost_history (organization_id);
create index ix_cost_history_resource_effective on resource_cost_history (resource_id, effective_from desc);

-- §7.1 One Catalog per organization that already has materials or suppliers.
insert into catalog (id, organization_id, currency, created_at)
select gen_random_uuid(), o.id, 'MXN', current_timestamp
from organization o
where exists (select 1 from material m where m.organization_id = o.id)
   or exists (select 1 from supplier s where s.organization_id = o.id);

-- §7.2 The seven Units and six Families of the former enums.
insert into unit (id, organization_id, code, description, source)
select gen_random_uuid(), c.organization_id, u.code, u.description, 'SEED'
from catalog c
cross join (values
    ('PZA', 'Pieza'), ('SACO', 'Saco'), ('M2', 'Metro cuadrado'), ('M3', 'Metro cúbico'),
    ('KG', 'Kilogramo'), ('LT', 'Litro'), ('M', 'Metro')
) as u(code, description);

insert into family (id, organization_id, code, name, source)
select gen_random_uuid(), c.organization_id, f.code, f.name, 'SEED'
from catalog c
cross join (values
    ('CEMENT_AGGREGATES', 'Cemento y agregados'), ('STEEL', 'Acero'), ('WOOD', 'Madera'),
    ('ELECTRICAL', 'Eléctrico'), ('PLUMBING', 'Plomería'), ('FINISHES', 'Acabados')
) as f(code, name);

-- §7.3 Materials become MATERIAL Resources with the same id and a generated code.
insert into resource (id, organization_id, code, type, name, unit_id, family_id, brand, source, archived_at,
                      created_at, updated_at)
select m.id, m.organization_id,
       'MAT-' || lpad(cast(row_number() over (partition by m.organization_id
                                              order by m.name, m.brand nulls first, m.id) as varchar), 6, '0'),
       'MATERIAL', m.name, u.id, f.id, m.brand, 'MANUAL', m.archived_at, current_timestamp, current_timestamp
from material m
join (values
    ('PIECE', 'PZA'), ('BAG', 'SACO'), ('SQUARE_METER', 'M2'), ('CUBIC_METER', 'M3'),
    ('KILOGRAM', 'KG'), ('LITER', 'LT'), ('METER', 'M')
) as mu(old_unit, code) on mu.old_unit = m.unit
join unit u on u.organization_id = m.organization_id and u.code = mu.code
left join family f on f.organization_id = m.organization_id and f.code = m.category;

-- §7.4 Price observations become Cost history entries. effective_from is the observation day plus created_at's
-- time of day (in the session time zone), read as local time in America/Mexico_City. The text form
-- '<date> <time> America/Mexico_City' cast to timestamptz means the same on PostgreSQL and H2, unlike
-- "timestamp at time zone", which H2 reads in the session zone.
insert into resource_cost_history (id, organization_id, resource_id, amount, effective_from, source,
                                   recorded_by_membership_id, supplier_id, sku, note)
select p.id, p.organization_id, p.material_id, p.price,
       cast(cast(p.observed_on as varchar) || ' ' || cast(cast(p.created_at as time(6)) as varchar)
            || ' America/Mexico_City' as timestamp(6) with time zone),
       p.source, null, p.supplier_id, p.sku, null
from price_observation p;

-- §7.5 The current cost is the latest entry.
update resource
set current_cost = (select h.amount from resource_cost_history h where h.resource_id = resource.id
                    order by h.effective_from desc, h.id desc limit 1),
    cost_updated_at = (select max(h.effective_from) from resource_cost_history h where h.resource_id = resource.id)
where exists (select 1 from resource_cost_history h where h.resource_id = resource.id);

-- §7.6 Supplier columns of §1.4; existing suppliers are MANUAL.
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
alter table supplier add column is_default boolean default false not null;
alter table supplier add column source varchar(20) default 'MANUAL' not null
    check ((source in ('MANUAL','CSV','NEODATA_CONSTRUBASE')));
alter table supplier add constraint uk_supplier_org_code unique (organization_id, code);

-- §7.7 The former tables go.
drop table price_observation;
drop table material;
