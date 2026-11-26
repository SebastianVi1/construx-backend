-- Wrong link codes per number (Field updates contract §4.1): 10 in an hour block the number for an hour, so codes
-- cannot be guessed. One row per number that sent a wrong code; the daily cleanup drops rows that no longer count.
create table whatsapp_wrong_code (
    wa_id varchar(20) primary key,
    window_start timestamp(6) with time zone not null,
    wrong_count integer not null,
    blocked_until timestamp(6) with time zone
);

create index idx_whatsapp_wrong_code_window_start on whatsapp_wrong_code (window_start);
