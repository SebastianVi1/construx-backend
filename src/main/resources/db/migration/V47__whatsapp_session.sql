-- The menu conversation of one Linked number (Field updates contract §2, §5): the step it is on and the choices made
-- so far. At most one per number; it ends on YES, NO, a change found on YES, unlinking, or idle expiry. Pending
-- photos (whatsapp_pending_photo) hang off this row. user_account_id is who chose: a number that moved to another
-- account does not carry the old account's session over.
create table whatsapp_session (
    wa_id varchar(20) primary key,
    user_account_id uuid not null,
    step varchar(30) not null,
    organization_id uuid,
    project_id uuid,
    goal_id uuid,
    activity_id uuid,
    action varchar(10),
    note_text varchar(1000),
    task_page int not null default 0,
    updated_at timestamp(6) with time zone not null,
    constraint fk_whatsapp_session_user foreign key (user_account_id) references "user-account" (id) on delete cascade
);

create index ix_whatsapp_session_updated_at on whatsapp_session (updated_at);
