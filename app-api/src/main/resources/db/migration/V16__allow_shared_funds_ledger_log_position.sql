-- 한 정산 전송에서 발생한 원장 묶음은 로그 위치를 공유하고 순번으로 구분한다.
alter table order_query.funds_ledger_batch drop constraint funds_ledger_batch_namespace_log_position_key;
