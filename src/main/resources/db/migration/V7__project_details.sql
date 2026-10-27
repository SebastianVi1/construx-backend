alter table project add column address varchar(255);
alter table project add column start_date date;
alter table project add column expected_end_date date;
alter table project add column status varchar(20) not null default 'PLANNED' check ((status in ('PLANNED','ACTIVE','ON_HOLD','COMPLETED','ARCHIVED')));
