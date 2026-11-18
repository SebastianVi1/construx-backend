-- Prices, FASAR slice (docs/pricing.md §1.10): one FASAR parameter set per organization, replaced in place by
-- PUT /fasar (and later by the FASAR workbook import), with its twelve contribution rates. Portable to H2.

create table fasar_parameter_set (
    id uuid not null,
    organization_id uuid not null,
    "year" smallint,
    uma numeric(10,4) not null,
    minimum_wage numeric(10,2) not null,
    sum_employer_and_worker boolean not null default true,
    calendar_days numeric(7,3) not null,
    sundays numeric(7,3) not null default 0,
    worked_sundays numeric(7,3) not null default 0,
    vacation_days numeric(7,3) not null default 0,
    monday_days numeric(7,3) not null default 0,
    holidays numeric(7,3) not null default 0,
    weather_days numeric(7,3) not null default 0,
    custom_days numeric(7,3) not null default 0,
    leave_sick_days numeric(7,3) not null default 0,
    union_days numeric(7,3) not null default 0,
    bonus_days numeric(7,3) not null,
    vacation_premium_pct numeric(10,8) not null,
    sunday_premium_pct numeric(10,8) not null,
    other_charges_pct numeric(10,8) not null default 0,
    excess_threshold_uma numeric(6,2) not null default 3,
    sbc_cap_uma numeric(6,2) not null default 25,
    factor_rounding smallint not null default 6,
    complete boolean not null,
    source varchar(20) not null check ((source in ('FASAR_FILE','GENERAL_FIELDS','MANUAL'))),
    version integer not null,
    updated_at timestamp(6) with time zone not null,
    updated_by_membership_id uuid,
    primary key (id),
    constraint uk_fasar_parameter_set_org unique (organization_id)
);

create table fasar_contribution (
    set_id uuid not null,
    concept varchar(40) not null,
    employer_rate numeric(10,8) not null,
    worker_rate numeric(10,8) not null,
    primary key (set_id, concept)
);

alter table if exists fasar_parameter_set add constraint fk_fasar_set_organization foreign key (organization_id) references organization;
alter table if exists fasar_parameter_set add constraint fk_fasar_set_membership foreign key (updated_by_membership_id) references organization_membership;
alter table if exists fasar_contribution add constraint fk_fasar_contribution_set foreign key (set_id) references fasar_parameter_set;
