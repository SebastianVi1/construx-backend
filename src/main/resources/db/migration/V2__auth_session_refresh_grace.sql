alter table if exists auth_session add column if not exists previous_refresh_hash varchar(64);
alter table if exists auth_session add column if not exists rotated_at timestamp(6) with time zone;
