-- Initial schema, generated from the JPA entities with Hibernate schema generation.
-- Existing databases built by ddl-auto are baselined at version 1 and skip this file.
-- Add changes in V2__<description>.sql and later; never edit applied migrations.

create table activity (completed_at timestamp(6) with time zone, due_at timestamp(6) with time zone not null, assignee_access_id uuid not null, goal_id uuid not null, id uuid not null, status varchar(20) not null check ((status in ('TODO','IN_PROGRESS','DONE'))), name varchar(160) not null, primary key (id));
create table auth_session (expires_at timestamp(6) with time zone not null, revoked_at timestamp(6) with time zone, id uuid not null, user_account_id uuid not null, refresh_hash varchar(64) not null, primary key (id));
create table email_verification (expires_at timestamp(6) with time zone not null, used_at timestamp(6) with time zone, id uuid not null, user_account_id uuid not null, token_hash varchar(64) not null unique, primary key (id));
create table goal (due_at timestamp(6) with time zone not null, id uuid not null, project_id uuid not null, name varchar(160) not null, primary key (id));
create table google_identity (id uuid not null, user_account_id uuid not null, subject varchar(255) not null unique, primary key (id));
create table organization (created_by_id uuid not null, id uuid not null, status varchar(20) not null check ((status in ('ACTIVE','INACTIVE'))), name varchar(160) not null, primary key (id));
create table organization_invitation (accepted_at timestamp(6) with time zone, expires_at timestamp(6) with time zone not null, id uuid not null, invited_by_membership_id uuid not null, organization_id uuid not null, role_id uuid not null, status varchar(20) not null check ((status in ('PENDING','ACCEPTED','REVOKED'))), email varchar(254) not null, token_hash varchar(255) not null unique, primary key (id));
create table organization_membership (id uuid not null, organization_id uuid not null, role_id uuid not null, user_account_id uuid not null, status varchar(20) not null check ((status in ('ACTIVE','SUSPENDED','LEFT'))), primary key (id), constraint uk_membership_org_user unique (organization_id, user_account_id));
create table organization_role (system_role boolean not null, id uuid not null, organization_id uuid not null, code varchar(64) not null, name varchar(100) not null, primary key (id), constraint uk_role_org_code unique (organization_id, code), constraint uk_role_org_name unique (organization_id, name));
create table project (id uuid not null, organization_id uuid not null, name varchar(160) not null, primary key (id));
create table project_access (id uuid not null, organization_membership_id uuid not null, project_id uuid not null, primary key (id), constraint uk_project_access_member unique (project_id, organization_membership_id));
create table role_permission (id uuid not null, role_id uuid not null, scope varchar(20) not null check ((scope in ('OWN','PROJECT','ORGANIZATION'))), action varchar(40) not null check ((action in ('MANAGE_ORGANIZATION','MANAGE_ROLES','MANAGE_MEMBERS','VIEW_PROJECT','MANAGE_PROJECT','VIEW_GOAL','MANAGE_GOAL','VIEW_ACTIVITY','MANAGE_ACTIVITY','ASSIGN_ACTIVITY'))), primary key (id), constraint uk_role_action unique (role_id, action));
create table "user-account" (email_verified boolean not null, id uuid not null, status varchar(20) not null check ((status in ('ACTIVE','DISABLED'))), email varchar(254) not null unique, password varchar(255), primary key (id));
create table user_profile (phone_number varchar(16) not null, user_account_id uuid not null, full_name varchar(160) not null, primary key (user_account_id));
create index ix_invitation_org_email on organization_invitation (organization_id, email);
alter table if exists activity add constraint FKatauhx2b32mlr3akwe8ma1rwm foreign key (assignee_access_id) references project_access;
alter table if exists activity add constraint FKftj5wwqovyli7ywe7v3ulowf7 foreign key (goal_id) references goal;
alter table if exists auth_session add constraint FKa8ss18o8uj93k732qrx6cfd9u foreign key (user_account_id) references "user-account";
alter table if exists email_verification add constraint FKoan7itrahy39kwvpif1ejcqy5 foreign key (user_account_id) references "user-account";
alter table if exists goal add constraint FK5i1ypwvt74nb7r9b6irfwm7jf foreign key (project_id) references project;
alter table if exists google_identity add constraint FKb6d724nxlrajwq8aue3641d4g foreign key (user_account_id) references "user-account";
alter table if exists organization add constraint FKdu9uov75jfo9wukkob3twmx6o foreign key (created_by_id) references "user-account";
alter table if exists organization_invitation add constraint FKgrhv8572y7wkn1ydcg6eaa4iw foreign key (invited_by_membership_id) references organization_membership;
alter table if exists organization_invitation add constraint FK10u1t8pmwo0vm4fhsw2ama40i foreign key (organization_id) references organization;
alter table if exists organization_invitation add constraint FK8ihwaqjre72ed5yeh0cclbht3 foreign key (role_id) references organization_role;
alter table if exists organization_membership add constraint FKilbvmqpyw1rljm62ciwmw2pu9 foreign key (organization_id) references organization;
alter table if exists organization_membership add constraint FKfyg5opi9sbdr6yrxx0ecm8i9s foreign key (role_id) references organization_role;
alter table if exists organization_membership add constraint FKfx808n6057d7yfmex7ydw1dt4 foreign key (user_account_id) references "user-account";
alter table if exists organization_role add constraint FKt51xpvlldba9k2j266ln6ropu foreign key (organization_id) references organization;
alter table if exists project add constraint FK56l08pkdkr9onlugrsj27t6a3 foreign key (organization_id) references organization;
alter table if exists project_access add constraint FKrh8dp22ai2xriuvju9ctww9uq foreign key (organization_membership_id) references organization_membership;
alter table if exists project_access add constraint FKswveelo48084m6wvgrcv7t3jf foreign key (project_id) references project;
alter table if exists role_permission add constraint FKglea2mppapj6k988j1kptv49g foreign key (role_id) references organization_role;
alter table if exists user_profile add constraint FK2xo1v9ifsrwgr8nuvw1gwgf93 foreign key (user_account_id) references "user-account" on delete cascade;
