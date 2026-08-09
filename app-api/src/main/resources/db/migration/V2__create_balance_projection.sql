create table balance_projection
(
    user_id    varchar(64)              not null,
    asset_id   varchar(64)              not null,
    available  bigint                   not null default 0,
    hold       bigint                   not null default 0,
    updated_at timestamp with time zone not null default current_timestamp,

    constraint pk_balance_projection
        primary key (user_id, asset_id),

    constraint ck_balance_projection_available_non_negative
        check (available >= 0),

    constraint ck_balance_projection_hold_non_negative
        check (hold >= 0)
);