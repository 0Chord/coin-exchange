create table order_reservations
(
    market_id          varchar(64)              not null,
    order_id           varchar(64)              not null,
    user_id            varchar(64)              not null,
    side               varchar(16)              not null,
    asset_id           varchar(64)              not null,
    limit_price        bigint                   not null,
    initial_quantity   bigint                   not null,
    remaining_quantity bigint                   not null,
    reserved_amount    bigint                   not null,
    remaining_amount   bigint                   not null,
    status              varchar(32)              not null,
    created_at          timestamp with time zone not null default current_timestamp,
    updated_at          timestamp with time zone not null default current_timestamp,

    constraint pk_order_reservations
        primary key (market_id, order_id),

    constraint ck_order_reservations_side
        check (side in ('BUY', 'SELL')),

    constraint ck_order_reservations_limit_price_positive
        check (limit_price > 0),

    constraint ck_order_reservations_initial_quantity_positive
        check (initial_quantity > 0),

    constraint ck_order_reservations_remaining_quantity_range
        check (
            remaining_quantity >= 0
            and remaining_quantity <= initial_quantity
        ),

    constraint ck_order_reservations_reserved_amount_positive
        check (reserved_amount > 0),

    constraint ck_order_reservations_remaining_amount_range
        check (
            remaining_amount >= 0
            and remaining_amount <= reserved_amount
        ),

    constraint ck_order_reservations_status
        check (status in ('ACTIVE', 'SETTLED', 'RELEASED'))
);
