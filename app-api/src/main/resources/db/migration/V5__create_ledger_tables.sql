create table ledger_transactions
(
    ledger_transaction_id varchar(64)              not null,
    source_event_id       varchar(128)             not null,
    transaction_type      varchar(32)              not null,
    occurred_at           timestamp with time zone not null,
    created_at            timestamp with time zone not null default current_timestamp,

    constraint pk_ledger_transactions
        primary key (ledger_transaction_id),

    constraint uk_ledger_transactions_source_event
        unique (source_event_id),

    constraint ck_ledger_transactions_type
        check (
            transaction_type in (
                'RESERVE',
                'RELEASE',
                'SETTLEMENT',
                'REVERSAL'
            )
        )
);

create table ledger_postings
(
    posting_id            bigserial                not null,
    ledger_transaction_id varchar(64)              not null,
    posting_sequence      integer                  not null,
    account_id            varchar(256)             not null,
    asset_id              varchar(64)              not null,
    side                  varchar(6)               not null,
    amount                bigint                   not null,
    created_at            timestamp with time zone not null default current_timestamp,

    constraint pk_ledger_postings
        primary key (posting_id),

    constraint fk_ledger_postings_transaction
        foreign key (ledger_transaction_id)
            references ledger_transactions (ledger_transaction_id),

    constraint uk_ledger_postings_transaction_sequence
        unique (ledger_transaction_id, posting_sequence),

    constraint ck_ledger_postings_sequence_positive
        check (posting_sequence > 0),

    constraint ck_ledger_postings_side
        check (side in ('DEBIT', 'CREDIT')),

    constraint ck_ledger_postings_amount_positive
        check (amount > 0)
);

create index idx_ledger_postings_account_asset
    on ledger_postings (account_id, asset_id);
