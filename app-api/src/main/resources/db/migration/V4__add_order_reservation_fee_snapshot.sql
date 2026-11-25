alter table order_reservations
    add column fee_product_type varchar(32) not null default 'SPOT',
    add column fee_tier varchar(32) not null default 'NORMAL',
    add column fee_schedule_version bigint not null default 1,
    add column maker_fee_rate_ppm bigint not null default 0,
    add column taker_fee_rate_ppm bigint not null default 0,
    add column initial_fee_reserve_amount bigint not null default 0,
    add column remaining_fee_reserve_amount bigint not null default 0;

alter table order_reservations
    add constraint ck_order_reservations_fee_product_type
        check (
            fee_product_type in (
                'SPOT',
                'PERPETUAL_FUTURES',
                'DATED_FUTURES'
            )
        ),
    add constraint ck_order_reservations_fee_tier
        check (
            fee_tier in (
                'NORMAL',
                'VIP',
                'VVIP',
                'VVVIP'
            )
        ),
    add constraint ck_order_reservations_fee_schedule_version_positive
        check (fee_schedule_version > 0),
    add constraint ck_order_reservations_maker_fee_rate_range
        check (
            maker_fee_rate_ppm >= 0
            and maker_fee_rate_ppm <= 1000000
        ),
    add constraint ck_order_reservations_taker_fee_rate_range
        check (
            taker_fee_rate_ppm >= 0
            and taker_fee_rate_ppm <= 1000000
        ),
    add constraint ck_order_reservations_initial_fee_reserve_range
        check (
            initial_fee_reserve_amount >= 0
            and initial_fee_reserve_amount <= reserved_amount
        ),
    add constraint ck_order_reservations_remaining_fee_reserve_range
        check (
            remaining_fee_reserve_amount >= 0
            and remaining_fee_reserve_amount <= initial_fee_reserve_amount
            and remaining_fee_reserve_amount <= remaining_amount
        );

alter table order_reservations
    alter column fee_product_type drop default,
    alter column fee_tier drop default,
    alter column fee_schedule_version drop default,
    alter column maker_fee_rate_ppm drop default,
    alter column taker_fee_rate_ppm drop default,
    alter column initial_fee_reserve_amount drop default,
    alter column remaining_fee_reserve_amount drop default;
