-- 자금 원장의 조회 결과와 적용 순번은 같은 트랜잭션에서 반영한다.
create table order_query.balance_progress (
    namespace varchar(64) primary key references order_query.feed_consumer(namespace),
    projected_through bigint not null check (projected_through > 0)
);

create table order_query.balance_projection (
    namespace varchar(64) not null references order_query.feed_consumer(namespace),
    user_id varchar(64) not null,
    asset_id varchar(64) not null,
    available bigint not null check (available >= 0),
    reserved bigint not null check (reserved >= 0),
    primary key (namespace, user_id, asset_id)
);
