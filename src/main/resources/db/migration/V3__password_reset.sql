create table password_reset (created_at timestamp(6) with time zone not null, expires_at timestamp(6) with time zone not null, used_at timestamp(6) with time zone, id uuid not null, user_account_id uuid not null, token_hash varchar(64) not null unique, primary key (id));
alter table if exists password_reset add constraint fk_password_reset_user foreign key (user_account_id) references "user-account" on delete cascade;
create index ix_password_reset_user on password_reset (user_account_id);
