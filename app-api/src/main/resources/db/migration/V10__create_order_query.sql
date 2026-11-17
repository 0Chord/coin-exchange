create schema if not exists order_query;

create table order_query.query_progress (
    namespace varchar(64) primary key,
    supported_markets jsonb not null,
    lifecycle varchar(16) not null check (lifecycle in ('INITIALIZING', 'READY', 'BLOCKED')),
    applied_revision bigint not null check (applied_revision >= 0),
    observed_revision bigint not null check (observed_revision >= applied_revision),
    covered_admission_sequence bigint not null check (covered_admission_sequence >= 0),
    updated_at timestamptz not null default current_timestamp
);

create table order_query.order_record (
    namespace varchar(64) not null references order_query.query_progress(namespace),
    market_id varchar(256) not null,
    order_id varchar(40) not null,
    admission_sequence bigint not null check (admission_sequence > 0),
    market_admission_sequence bigint not null check (market_admission_sequence > 0),
    record_payload jsonb not null,
    updated_at timestamptz not null default current_timestamp,
    primary key (namespace, market_id, order_id),
    unique (namespace, order_id),
    unique (namespace, admission_sequence),
    unique (namespace, market_id, market_admission_sequence)
);

create table order_query.applied_batch (
    namespace varchar(64) not null references order_query.query_progress(namespace),
    batch_id varchar(256) not null,
    batch_payload jsonb not null,
    first_revision bigint not null check (first_revision > 0),
    applied_at timestamptz not null default current_timestamp,
    primary key (namespace, batch_id),
    unique (namespace, first_revision)
);
