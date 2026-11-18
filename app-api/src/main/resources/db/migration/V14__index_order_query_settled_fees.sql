-- 주문별 확정 수수료를 기존 정산 행에서 찾는다.
create index settlement_transaction_maker_order_idx
    on order_query.settlement_transaction (namespace, market_id, maker_order_id, result_sequence);

create index settlement_transaction_taker_order_idx
    on order_query.settlement_transaction (namespace, market_id, taker_order_id, result_sequence);
