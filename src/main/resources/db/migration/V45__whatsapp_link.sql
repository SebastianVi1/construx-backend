-- Linked numbers (Field updates contract §2, §5 rules 1-3): a WhatsApp sender id proven to belong to one account by a
-- one-time code sent from that number. One number per user and one user per number.
create table whatsapp_link (
    id uuid primary key,
    user_account_id uuid not null,
    wa_id varchar(20) not null,
    language varchar(2) not null,
    linked_at timestamp(6) with time zone not null,
    constraint uk_whatsapp_link_user unique (user_account_id),
    constraint uk_whatsapp_link_wa_id unique (wa_id),
    constraint ck_whatsapp_link_language check (language in ('es', 'en')),
    constraint fk_whatsapp_link_user foreign key (user_account_id) references "user-account" (id) on delete cascade
);

-- The one active link code of a user: only its SHA-256 is kept. A new code replaces the old one; using it deletes it.
create table whatsapp_link_code (
    user_account_id uuid primary key,
    code_hash varchar(64) not null,
    language varchar(2) not null,
    expires_at timestamp(6) with time zone not null,
    constraint ck_whatsapp_link_code_language check (language in ('es', 'en')),
    constraint fk_whatsapp_link_code_user foreign key (user_account_id) references "user-account" (id) on delete cascade
);

create index ix_whatsapp_link_code_hash on whatsapp_link_code (code_hash);
create index ix_whatsapp_link_code_expires_at on whatsapp_link_code (expires_at);
