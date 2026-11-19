-- Retire the pricing catalog while preserving organization data and shared permissions.
drop table resource_cost_history;
drop table resource;
drop table supplier;
drop table family;
drop table unit;
drop table catalog;
