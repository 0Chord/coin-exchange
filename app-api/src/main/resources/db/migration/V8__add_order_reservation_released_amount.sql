-- 과거 RELEASED의 알 수 없는 반환액은 null로 보존한다. 금액을 추측해 채우지 않는다.
alter table order_reservations add column released_amount bigint;

alter table order_reservations add constraint ck_order_reservation_released_amount
    check (released_amount is null or
        (status = 'RELEASED' and released_amount > 0 and released_amount <= reserved_amount));
