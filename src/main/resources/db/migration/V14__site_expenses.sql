-- Site expenses: money spent on a project, subtracted from its allocated budget (V13).
-- Amounts are in the organization's currency (MXN), like the budget and material prices.

create table expense (
    id uuid not null,
    project_id uuid not null,
    amount numeric(14,2) not null check (amount > 0),
    spent_on date not null,
    description varchar(160) not null,
    category varchar(20) not null check ((category in ('MATERIALS','LABOR','EQUIPMENT','OTHER'))),
    created_at timestamp(6) with time zone not null,
    primary key (id),
    constraint fk_expense_project foreign key (project_id) references project (id)
);

create index ix_expense_project on expense (project_id);
