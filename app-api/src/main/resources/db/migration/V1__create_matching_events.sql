create table matching_events
(
    id                 bigserial primary key,
    market_id          varchar(64)              not null,
    engine_sequence    bigint                   not null,
    event_type         varchar(64)              not null,

    order_id           varchar(64),
    user_id            varchar(64),
    maker_order_id     varchar(64),
    taker_order_id     varchar(64),
    maker_user_id      varchar(64),
    taker_user_id      varchar(64),

    side               varchar(16),
    price              bigint,
    quantity           bigint,
    remaining_quantity bigint,
    reason             varchar(512),

    payload_json       text                     not null,
    created_at         timestamp with time zone not null,

    constraint uk_matching_events_market_sequence
        unique (market_id, engine_sequence)
);

create index idx_matching_events_market_sequence
    on matching_events (market_id, engine_sequence);

create index idx_matching_events_order_id
    on matching_events (order_id);

create index idx_matching_events_user_id
    on matching_events (user_id);

create index idx_matching_events_maker_order_id
    on matching_events (maker_order_id);

create index idx_matching_events_taker_order_id
    on matching_events (taker_order_id);