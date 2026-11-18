-- Prices, composition slice (docs/pricing.md §1.7-1.9): Unit price analysis lines, Labor category detail and
-- Equipment rate detail with its operation, consumption and standby tables. Written by the import; v1 has no
-- endpoint that edits an analysis. Portable to H2 (tests).

create table unit_price_analysis_line (
    id uuid not null,
    organization_id uuid not null,
    parent_resource_id uuid not null,
    component_resource_id uuid not null,
    line_no integer not null,
    operator char(1) not null check ((operator in ('*','/'))),
    quantity numeric(18,6) not null check ((quantity > 0)),
    expression varchar(120),
    amount numeric(14,2),
    primary key (id),
    constraint uk_analysis_line_parent_no unique (parent_resource_id, line_no)
);

create index ix_analysis_line_org on unit_price_analysis_line (organization_id);
create index ix_analysis_line_component on unit_price_analysis_line (component_resource_id);

create table labor_category_detail (
    resource_id uuid not null,
    organization_id uuid not null,
    nominal_wage numeric(12,2) not null check ((nominal_wage >= 0)),
    fsr numeric(12,8) not null check ((fsr >= 1)),
    fsr_source varchar(10) not null check ((fsr_source in ('IMPORTED','MANUAL','COMPUTED'))),
    fsbc numeric(12,8),
    sbc numeric(12,2),
    imported_fsr numeric(12,8),
    imported_real_wage numeric(12,2),
    imported_fsbc numeric(12,8),
    imported_sbc numeric(12,2),
    per_diem numeric(12,2) not null default 0,
    meals_lodging numeric(12,2) not null default 0,
    funeral_expenses numeric(12,2) not null default 0,
    other_benefits numeric(12,2) not null default 0,
    fasar_type smallint,
    fsr_mismatch boolean not null default false,
    primary key (resource_id)
);

create index ix_labor_category_detail_org on labor_category_detail (organization_id);

create table equipment_rate_detail (
    resource_id uuid not null,
    organization_id uuid not null,
    power_unit varchar(10),
    fixed_charge_code varchar(40),
    acquisition_value_code varchar(40),
    standby_code varchar(40),
    reserve_code varchar(40),
    tires_code varchar(40),
    special_parts_code varchar(40),
    fuel_code varchar(40),
    lubricant_code varchar(40),
    other_energy_code varchar(40),
    tires_life_hours numeric(12,2),
    special_parts_life_hours numeric(12,2),
    economic_life_hours numeric(12,2),
    hours_per_year numeric(12,2),
    oil_change_hours numeric(12,2),
    calculation_type smallint,
    power numeric(12,4),
    operation_factor numeric(10,6),
    fuel_type smallint,
    fuel_factor numeric(12,8),
    other_energy_factor numeric(12,8),
    lubricant_factor numeric(12,8),
    crankcase_capacity numeric(12,4),
    salvage_pct numeric(10,6),
    insurance_pct numeric(10,6),
    maintenance_factor numeric(10,6),
    interest_rate numeric(10,6),
    brand varchar(120),
    model varchar(120),
    serial_number varchar(120),
    capacity varchar(120),
    location varchar(120),
    useful_life varchar(120),
    owner varchar(120),
    availability varchar(120),
    commercial_value numeric(14,2),
    fuel_qty_per_hour numeric(12,6),
    lubricant_qty_per_hour numeric(12,6),
    primary key (resource_id)
);

create index ix_equipment_rate_detail_org on equipment_rate_detail (organization_id);

create table equipment_operation_line (
    id uuid not null,
    organization_id uuid not null,
    equipment_resource_id uuid not null,
    component_resource_id uuid not null,
    operator char(1) not null check ((operator in ('*','/'))),
    quantity numeric(18,6) not null,
    line_no integer not null,
    primary key (id),
    constraint uk_equipment_operation_line unique (equipment_resource_id, component_resource_id)
);

create index ix_equipment_operation_line_org on equipment_operation_line (organization_id);

create table equipment_consumption_line (
    id uuid not null,
    organization_id uuid not null,
    equipment_resource_id uuid not null,
    component_resource_id uuid not null,
    operator char(1) not null check ((operator in ('*','/'))),
    quantity numeric(18,6) not null,
    line_no integer not null,
    primary key (id),
    constraint uk_equipment_consumption_line unique (equipment_resource_id, component_resource_id)
);

create index ix_equipment_consumption_line_org on equipment_consumption_line (organization_id);

create table equipment_standby_percentage (
    id uuid not null,
    organization_id uuid not null,
    charge varchar(40) not null,
    standby_pct numeric(8,6) not null,
    reserve_pct numeric(8,6) not null,
    primary key (id),
    constraint uk_equipment_standby_percentage unique (organization_id, charge)
);

alter table if exists unit_price_analysis_line add constraint fk_analysis_line_organization foreign key (organization_id) references organization;
alter table if exists unit_price_analysis_line add constraint fk_analysis_line_parent foreign key (parent_resource_id) references resource;
alter table if exists unit_price_analysis_line add constraint fk_analysis_line_component foreign key (component_resource_id) references resource;
alter table if exists labor_category_detail add constraint fk_labor_category_detail_organization foreign key (organization_id) references organization;
alter table if exists labor_category_detail add constraint fk_labor_category_detail_resource foreign key (resource_id) references resource;
alter table if exists equipment_rate_detail add constraint fk_equipment_rate_detail_organization foreign key (organization_id) references organization;
alter table if exists equipment_rate_detail add constraint fk_equipment_rate_detail_resource foreign key (resource_id) references resource;
alter table if exists equipment_operation_line add constraint fk_equipment_operation_line_organization foreign key (organization_id) references organization;
alter table if exists equipment_operation_line add constraint fk_equipment_operation_line_equipment foreign key (equipment_resource_id) references resource;
alter table if exists equipment_operation_line add constraint fk_equipment_operation_line_component foreign key (component_resource_id) references resource;
alter table if exists equipment_consumption_line add constraint fk_equipment_consumption_line_organization foreign key (organization_id) references organization;
alter table if exists equipment_consumption_line add constraint fk_equipment_consumption_line_equipment foreign key (equipment_resource_id) references resource;
alter table if exists equipment_consumption_line add constraint fk_equipment_consumption_line_component foreign key (component_resource_id) references resource;
alter table if exists equipment_standby_percentage add constraint fk_equipment_standby_percentage_organization foreign key (organization_id) references organization;
