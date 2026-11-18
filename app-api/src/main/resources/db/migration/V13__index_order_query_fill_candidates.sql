-- 주문이 포함된 적용 기록에서 해당 매칭 원문만 찾아 체결 목록을 읽는다.
create index order_query_fill_candidates_idx
    on order_query.applied_batch using gin ((batch_payload -> 'records') jsonb_path_ops);
