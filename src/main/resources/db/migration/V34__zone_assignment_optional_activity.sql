-- Assignments made by drag and drop carry no activity text.
alter table zone_assignment alter column activity drop not null;
alter table zone_assignment drop constraint if exists zone_assignment_activity_check;
alter table zone_assignment add constraint zone_assignment_activity_check
    check (activity is null or length(trim(activity)) > 0);
