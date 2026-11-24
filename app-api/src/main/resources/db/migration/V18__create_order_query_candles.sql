-- 차트 쪽 진행 행을 잠가 집계 작업을 직렬화한다. 원본 진행 행은 잠그지 않는다.
create table order_query.candle_progress (
    namespace varchar(64) not null,
    market_id varchar(256) not null,
    projected_through bigint not null check (projected_through >= 0),
    bootstrap_through bigint not null check (bootstrap_through >= 0),
    primary key (namespace, market_id)
);

create table order_query.candle (
    namespace varchar(64) not null,
    market_id varchar(256) not null,
    interval_code varchar(3) not null check (interval_code in ('1m', '5m', '15m', '1h', '1d', '1w', '1mo', '1y')),
    bucket_start timestamptz not null check (isfinite(bucket_start)),
    open bigint not null,
    high bigint not null,
    low bigint not null check (low > 0),
    close bigint not null,
    volume numeric(38, 0) not null check (volume > 0 and volume <> 'NaN'::numeric),
    first_occurred_at timestamptz not null check (isfinite(first_occurred_at)),
    first_engine_sequence bigint not null check (first_engine_sequence > 0),
    last_occurred_at timestamptz not null check (isfinite(last_occurred_at)),
    last_engine_sequence bigint not null check (last_engine_sequence > 0),
    primary key (namespace, market_id, interval_code, bucket_start),
    foreign key (namespace, market_id) references order_query.candle_progress(namespace, market_id),
    check (open between low and high and close between low and high),
    check (first_occurred_at >= bucket_start and
        (first_occurred_at, first_engine_sequence) <= (last_occurred_at, last_engine_sequence))
);
