alter table order_query.feed_stream
    add column settlement_through bigint not null default 0 check (settlement_through >= 0);

-- 자금 계보가 다른 체결을 기존 원장의 source_event_id 전역 유일 키와 섞지 않는다.
create table order_query.settlement_transaction (
    namespace varchar(64) not null,
    market_id varchar(256) not null,
    result_sequence bigint not null check (result_sequence > 0),
    settlement_id varchar(64) not null,
    source_event_id text not null,
    transaction_type varchar(32) not null default 'SETTLEMENT' check (transaction_type = 'SETTLEMENT'),
    engine_sequence bigint not null check (engine_sequence > 0),
    maker_order_id text not null,
    taker_order_id text not null,
    maker_user_id text not null,
    taker_user_id text not null,
    taker_side varchar(4) not null check (taker_side in ('BUY', 'SELL')),
    base_asset_id text not null,
    quote_asset_id text not null,
    price bigint not null check (price > 0),
    quantity bigint not null check (quantity > 0),
    maker_fee bigint not null check (maker_fee >= 0),
    taker_fee bigint not null check (taker_fee >= 0),
    occurred_at timestamptz not null,
    primary key (namespace, settlement_id),
    unique (namespace, market_id, engine_sequence),
    foreign key (namespace, market_id) references order_query.feed_stream(namespace, market_id)
);

create index settlement_transaction_result_idx
    on order_query.settlement_transaction (namespace, market_id, result_sequence);

create table order_query.settlement_posting (
    namespace varchar(64) not null,
    settlement_id varchar(64) not null,
    posting_sequence integer not null check (posting_sequence > 0),
    account_id text not null,
    asset_id text not null,
    side varchar(6) not null check (side in ('DEBIT', 'CREDIT')),
    amount bigint not null check (amount > 0),
    primary key (namespace, settlement_id, posting_sequence),
    foreign key (namespace, settlement_id) references order_query.settlement_transaction(namespace, settlement_id)
);
