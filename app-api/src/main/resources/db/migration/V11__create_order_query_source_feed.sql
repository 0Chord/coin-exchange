create table order_query.feed_consumer (
    namespace varchar(64) primary key references order_query.query_progress(namespace),
    consumer_id varchar(256) not null,
    configuration_payload jsonb not null,
    admission_through bigint not null default 0 check (admission_through >= 0),
    initial_sources_verified boolean not null default false,
    bootstrap_complete boolean not null default false,
    blocked_reason varchar(256)
);

create table order_query.feed_stream (
    namespace varchar(64) not null references order_query.feed_consumer(namespace),
    market_id varchar(256) not null,
    matching_through bigint not null default 0 check (matching_through >= 0),
    financial_through bigint not null default 0 check (financial_through >= 0),
    primary key (namespace, market_id)
);

-- #61의 과거 maker/taker 체결 사실을 포함한 확정 원본 bytes를 그대로 보존한다.
create table order_query.source_receipt (
    namespace varchar(64) not null references order_query.feed_consumer(namespace),
    source_kind varchar(16) not null check (source_kind in ('ADMISSION', 'MATCHING')),
    source_key varchar(256) not null,
    source_sequence bigint not null check (source_sequence > 0),
    canonical_payload bytea not null check (octet_length(canonical_payload) > 0),
    classification varchar(32) not null check (classification in ('PROJECTED', 'UNSUPPORTED_QUERY_MARKET')),
    first_revision bigint not null check (first_revision > 0),
    applied_at timestamptz not null default current_timestamp,
    primary key (namespace, source_kind, source_key, source_sequence),
    unique (namespace, first_revision)
);

-- 완료된 과거 주문을 읽지 않고 현재 결과의 정산 대기 행만 찾는다.
create index order_query_pending_financial_idx
    on order_query.order_record
        (namespace, market_id, (cast(record_payload ->> 'lastAffectedResultSequence' as bigint)))
    where record_payload ->> 'state' <> 'MATCHING_PENDING'
        and cast(record_payload ->> 'financialThrough' as bigint) <
            cast(record_payload ->> 'lastAffectedResultSequence' as bigint);
