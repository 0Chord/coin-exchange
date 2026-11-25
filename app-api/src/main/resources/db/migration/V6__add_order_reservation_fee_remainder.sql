-- 기존 행의 기록 시작값을 0으로 채운다. 과거 체결의 소수 나머지를 복원하는 것은 아니다.
alter table order_reservations
    add column fee_remainder_numerator bigint not null default 0;

-- FeeRate.DENOMINATOR가 1,000,000인 표현 방식에서 나머지 분자의 범위를 제한한다.
alter table order_reservations
    add constraint ck_order_reservations_fee_remainder_range
        check (
            fee_remainder_numerator >= 0
            and fee_remainder_numerator < 1000000
        );

-- 이후 INSERT에서는 나머지 분자를 반드시 명시하도록 기본값을 제거한다.
alter table order_reservations
    alter column fee_remainder_numerator drop default;
