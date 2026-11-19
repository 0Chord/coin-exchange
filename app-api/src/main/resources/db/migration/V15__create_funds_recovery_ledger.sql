-- 자금 클러스터에서 함께 적용한 분개를 승인된 순서와 로그 위치로 보존한다.
create table order_query.funds_ledger_batch (
    namespace varchar(64) not null references order_query.feed_consumer(namespace),
    sequence bigint not null check (sequence > 0),
    log_position bigint not null check (log_position > 0),
    transactions jsonb not null check (jsonb_typeof(transactions) = 'array' and jsonb_array_length(transactions) > 0),
    primary key (namespace, sequence),
    unique (namespace, log_position)
);
