-- WhatsApp webhook bookkeeping (Field updates contract §4.3, §5 rule 1). Neither table keeps message content.

-- Meta retries a webhook post with the same message id: one row per message already handled, kept for a few days.
create table whatsapp_processed_message (
    message_id varchar(255) primary key,
    wa_id varchar(20) not null,
    received_at timestamp(6) with time zone not null
);

create index idx_whatsapp_processed_message_received_at on whatsapp_processed_message (received_at);

-- When a number without a link was last told how to link, so it hears NOT_LINKED at most once per 24 hours.
create table whatsapp_unlinked_reply (
    wa_id varchar(20) primary key,
    replied_at timestamp(6) with time zone not null
);
