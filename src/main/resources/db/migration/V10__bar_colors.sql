-- Optional bar color for phases and tasks in the schedule, as #rrggbb. Null means the default color.
alter table goal add column color varchar(7);
alter table activity add column color varchar(7);
