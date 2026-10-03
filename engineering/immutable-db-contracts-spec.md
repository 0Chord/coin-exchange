# #22 상세 명세 — 불변 상태 전이와 DB 잔고 갱신 계약

대상: [#22 불변 상태 전이와 원자적 DB 잔고 갱신 계약 검증](https://github.com/0Chord/coin-exchange/issues/22). 상위: #18. 필수 선행: #19. 기존 권장 순서: #21·#23 다음. 확인일: **2026-10-03**.

기준: `feature/phase-2/integration`의 **`ea3a3f446a5809db80eae18467db337947678574`** — PR #37 병합 후 코드. 작업 폴더: `/Users/0chord/.codex/worktrees/issue22-contract-spec/exchange-core`, 구현 브랜치: `test/immutable-db-contracts/22`.

**상태: 기존 합의를 기준 코드·테스트와 대조한 상세 명세.** 명세 작성 단계에서는 제품 코드·테스트를 수정하거나 실행하지 않았다. 이후 사용자의 구현 요청으로 단위 1~3의 로컬 검증을 완료했다. 2026-10-03 HTTP 대표 사례 1개 포함을 확정했고 로컬 검증까지 완료했다. 구현·실행 결과는 [구현 읽기](immutable-db-contracts-review.md)에 기록한다. 아래의 ‘기존’과 소스 행 번호는 기준 커밋의 테스트 내용을 뜻하며 그 자체가 새 실행 결과는 아니다.

## 먼저 볼 결과

이미 정한 계약을 새로 설계하는 작업이 아니다. **잔고·주문 예약·원장의 상태가 규칙대로 바뀌고, 실패한 DB 작업이 그 트랜잭션 안에서 되돌아가는지** 기존 테스트와 연결한다. 기존 사례는 재사용하고 아래에 지정한 빈틈만 보강한다.

읽는 순서는 다음과 같다.

1. 도메인은 원본을 유지하고 새 결과를 반환한다. 결과에서도 소유자·자산·주문 당시 정보는 유지한다.
2. DB 잔고는 조건부 `UPDATE … RETURNING`으로 바꾼다. 먼저 읽어 계산한 값을 무조건 저장하는 방식으로 바꾸지 않는다.
3. 예약, 체결 한 건의 정산, 예약 해제는 각각 별도 트랜잭션이다. 실패한 단위의 변경만 롤백한다.
4. 같은 주문의 중복 해제와 서로 다른 주문의 잔고 경쟁은 기대 결과가 다르다.

## 무엇을 유지하고 어디까지 확인하는가

**기존 합의:** `Balance`·`OrderReservation`의 불변 상태 전이, 수수료 정책 스냅샷·소수 나머지, 조건부 SQL과 `RETURNING`, 예약 행의 `FOR UPDATE`, config의 `@Bean`과 실제 트랜잭션 프록시, 기존 API·이벤트·실패 전달을 유지한다. 저장 구현이 `Balance.release()`를 직접 호출하지 않는 것은 의도한 설계다.

**이번 범위:** 기존 테스트의 수용 사례 매핑, 원본·변하지 않을 필드의 어설션 보강, 정확한 한도·금액 덧셈 경계, 서비스 수준의 경쟁·중복·롤백 빈틈과 합의한 HTTP 대표 사례 1개. 아래 단위를 한 PR 안에서 순서대로 검증한다.

**제외:** 새 SQL/트랜잭션/재시도·보상 정책, 모든 객체 불변화, 매칭 엔진·실행기 변경, 새 일반 검증 하네스·역할 등록표·검사 도구, 수수료/단위/반올림 기능 변경, 모든 실패 조합을 HTTP로 복제하기. Java/reflection 접근 차단은 #23의 Kotlin 접근 경계를 확대하는 별도 과제다.

## 작은 구현·검증 단위

| 단위 | 다음에 만들 작은 결과 | 재사용·보강할 파일 | 끝났다고 판단할 근거 |
| --- | --- | --- | --- |
| 1. 원본과 거래·수수료 경계 | 정상·거절 뒤 원본 전체와 고정 정보 유지. 거래 예약과 수수료 예약을 혼용하는 입력 거절 | `BalanceTest`, `OrderReservationTest`, `OrderFillSettlementCalculatorTest`, `LedgerTransactionTest` | 아래 순수 도메인 사례. 기존 분할 수수료 기대값 유지 |
| 2. 도메인과 SQL의 같은 금액 계약 | 동일한 초기값·입력에 같은 금액 결과 또는 상태 보존 실패. 정확한 전액 예약과 덧셈 초과 확인 | 기존 `PostgresBalanceStoreTest`에 직접 추가. 도메인 사례와 독립 상수 기대값으로 대조 | 반환 객체와 실제 DB 행을 함께 확인. 기존 SQL 유지 |
| 3. 트랜잭션 안의 경쟁·중복·실패 | 예약 경쟁의 패자 INSERT 제거, 서로 다른 해제의 패자 UPDATE 롤백, 정산의 hold 부족·성공 이벤트 재호출에서 추가 반영 없음 | 기존 `OrderFundingServiceTest`, `OrderReservationReleaseServiceTest`, `TradeSettlementServiceTest` | 현재 실패 트랜잭션의 예약·잔고·원장을 비교. 이전 성공은 보존 |
| 4. HTTP 부분 체결 후 취소 | BUY 3개 중 1개 체결 후 2개 취소. 이미 체결된 자산·수수료 유지 | 기존 `OrderLifecycleE2ETest`에 1개 대표 사례 | HTTP 처리·Bean·매칭·DB 연결의 중간/최종 상태. 사용자 답변으로 포함 확정 |

**첫 입력은 단위 1이다.** 정상·거절 원본 검사는 기존 사례의 어설션을 보강하고, 거래 500/수수료 5인 예약에서 거래 501 또는 수수료 6을 줄이려는 입력을 거절하는 사례를 추가한다. 준비된 수용 사례는 후속 `spec-implementer`의 테스트 작성 → 기대값 검토 → 구현 필요성 판단 → 실행 검증 입력으로 사용한다. 이번 명세 요청이 그 구현 허가는 아니다.

<details markdown="1">
<summary>현재 책임과 입력 → 판단 → 결과 흐름</summary>

## 현재 책임과 위치

| 현재 코드 | 책임 | 외부 경계 |
| --- | --- | --- |
| [Balance](https://github.com/0Chord/coin-exchange/blob/ea3a3f446a5809db80eae18467db337947678574/domain-ledger/src/main/kotlin/com/exchange/core/ledger/Balance.kt#L39) | 예약·해제·소비·지급 금액을 검사하고 새 잔고 반환 | DB 호출 없음. 값은 최소 단위 정수 |
| [OrderReservation](https://github.com/0Chord/coin-exchange/blob/ea3a3f446a5809db80eae18467db337947678574/domain-order/src/main/kotlin/com/exchange/core/order/OrderReservation.kt#L230) | ACTIVE에서 부분/전량 체결 또는 해제, 거래/수수료 예약 분리 | 최초 주문 정보·정책을 유지. 생성 검사는 상태 전이와 구분 |
| [LedgerTransaction](https://github.com/0Chord/coin-exchange/blob/ea3a3f446a5809db80eae18467db337947678574/domain-ledger/src/main/kotlin/com/exchange/core/ledger/LedgerTransaction.kt#L32) | 자산별 차변·대변 균형 검사, 입력 목록 복사·노출 목록 보호 | 생성 이후 검증된 분개 목록이 외부 변경으로 달라지지 않음 |
| [PostgresBalanceStore](https://github.com/0Chord/coin-exchange/blob/ea3a3f446a5809db80eae18467db337947678574/app-api/src/main/kotlin/com/exchange/core/api/ledger/infrastructure/persistence/PostgresBalanceStore.kt#L35) | 잔고 조건과 변경을 같은 SQL에 묶고 변경 직후 값 반환 | UPDATE 0건이면 행 부재와 금액 부족을 조회로 구분. 실패 진단 조회까지 전부 단일 SQL이라고 하지 않음 |
| [PostgresOrderReservationStore](https://github.com/0Chord/coin-exchange/blob/ea3a3f446a5809db80eae18467db337947678574/app-api/src/main/kotlin/com/exchange/core/api/order/infrastructure/persistence/PostgresOrderReservationStore.kt#L172) | 예약 생성·잠금 조회·현재 상태 갱신 | 최초 정보는 UPDATE하지 않음. 변조 입력을 비교하여 거절하는 새 정책은 추가하지 않음 |
| [자금 예약](https://github.com/0Chord/coin-exchange/blob/ea3a3f446a5809db80eae18467db337947678574/app-api/src/main/kotlin/com/exchange/core/api/order/application/OrderFundingService.kt#L57) | 요구액 계산 → 예약 INSERT → available에서 hold로 이동 | TX-FUND: 한 예약 호출. 원장은 기록하지 않음 |
| [체결 정산](https://github.com/0Chord/coin-exchange/blob/ea3a3f446a5809db80eae18467db337947678574/app-api/src/main/kotlin/com/exchange/core/api/order/application/TradeSettlementService.kt#L64) | 두 예약 잠금 → 계획/균형 검사 → 원장 → maker/taker 반영 | TX-TRADE: 한 체결 호출. 여러 체결 전체가 한 트랜잭션은 아님 |
| [예약 해제](https://github.com/0Chord/coin-exchange/blob/ea3a3f446a5809db80eae18467db337947678574/app-api/src/main/kotlin/com/exchange/core/api/order/application/OrderReservationReleaseService.kt#L40) | 예약 잠금 → 중복 확인 → 새 RELEASED 예약 저장 → hold 반환 | TX-RELEASE: 한 주문 해제. 원장은 기록하지 않음 |

## 정상 흐름과 실패 시 남는 상태

**예약:** 주문 값 → 요구 금액과 수수료 예약 계산 → 예약 행 생성 → 조건부 잔고 이동 → 새 예약 반환. 금액 부족이나 잔고 행 부재이면 이 호출에서 만든 예약 INSERT도 롤백하며 엔진 진입은 #23의 사전 콜백 경계에서 막힌다.

**정산:** 이미 매칭된 체결 → maker/taker 예약 잠금 → 각 정산 계획 → 자산별 균형을 검사한 원장 저장 → 양쪽 예약·잔고 변경 → 완료. 뒤쪽 hold 소비나 지급이 실패하면 이번 원장과 양쪽 변경도 롤백한다. 앞서 별도 호출로 성공한 체결, 이미 저장된 매칭 이벤트, 메모리 주문장은 되돌리지 않는다.

**해제:** 취소 성공 이벤트 → 예약 잠금 → 이미 RELEASED면 추가 반환 없이 기존 결과 반환 → ACTIVE면 도메인 전이 → 예약 UPDATE와 잔고 반환 → 완료. 반환 실패는 예약 UPDATE도 롤백한다. 앞선 엔진 취소·이벤트 저장까지 복구하지 않는다.

### 같은 ‘다시 요청’이라도 기대가 다른 이유

| 입력 | 유지할 결과 |
| --- | --- |
| RELEASED 예약에 `OrderReservation.release()` 직접 호출 | ACTIVE만 허용하므로 거절 |
| RELEASED 예약에 해제 Service 재호출 | Service가 먼저 중복을 확인. 기존 결과 반환, 잔고 추가 증가 없음 |
| 같은 주문을 Funding Service로 다시 예약 | 중복 예약 거절. 두 번째 hold 이동 없음 |
| 이미 성공한 체결을 Settlement Service에 다시 전달 | 추가 반영 없이 거절. 정상 결과 재생·자동 재시도를 새로 보장하지 않음 |
| 실패한 체결의 원인을 고친 뒤 직접 재호출 | 기존 분할 정산 테스트에서 별도 검증. 실행기의 실패 상태 자동 해제를 뜻하지 않음 |

같은 주문 해제 경쟁은 두 호출 모두 정상 결과를 받을 수 있다. 서로 다른 예약이 실제 hold보다 많은 반환을 요구하는 경쟁에서는 성공한 예약만 해제되고 패자의 예약 변경은 롤백해야 한다. 후자는 안전 경계 검증용으로 의도적으로 예약 합과 hold가 불일치하는 초기 DB를 만든다. 정상 운영에서 그 불일치가 발생했다는 주장이나 새 복구 정책은 아니다.

</details>

<details markdown="1">
<summary>이미 검증하는 사례와 이번 보강 대상</summary>

## 소스 기준 매핑 — 기존 사례 재사용

| 계약 | 현재 테스트·어설션 | #22 처리 |
| --- | --- | --- |
| Balance 정상/부족 원본 유지 | [BalanceTest](https://github.com/0Chord/coin-exchange/blob/ea3a3f446a5809db80eae18467db337947678574/domain-ledger/src/test/kotlin/com/exchange/core/ledger/BalanceTest.kt#L12)의 reserve/release/consumeHold/credit 정상·부족, credit overflow | 기존 원본 금액 확인 유지. 전체 원본·결과 식별자 보존 어설션 보강 |
| 예약 생성과 부분/전량/해제·비ACTIVE 거절 | [OrderReservationTest](https://github.com/0Chord/coin-exchange/blob/ea3a3f446a5809db80eae18467db337947678574/domain-order/src/test/kotlin/com/exchange/core/order/OrderReservationTest.kt#L149) 149·163·189·212·283·370·417·483행의 사례 | 기존 사례에 원본/중간 결과 전체 보존과 최초 필드 보존 보강. 생성 `copy` 검사를 실제 전이로 설명하지 않음 |
| 분할 수수료와 남은 수수료 예약 | [TradingFeeCalculatorTest](https://github.com/0Chord/coin-exchange/blob/ea3a3f446a5809db80eae18467db337947678574/domain-fee/src/test/kotlin/com/exchange/core/fee/TradingFeeCalculatorTest.kt#L96), [OrderFillSettlementCalculatorTest](https://github.com/0Chord/coin-exchange/blob/ea3a3f446a5809db80eae18467db337947678574/domain-order/src/test/kotlin/com/exchange/core/order/OrderFillSettlementCalculatorTest.kt#L505) | 기존 독립 상수 기대값 재사용. 입력 예약·중간 예약 보존 어설션만 보강 |
| SQL 정상·부족·행 부재·동시 합 초과 | [PostgresBalanceStoreTest](https://github.com/0Chord/coin-exchange/blob/ea3a3f446a5809db80eae18467db337947678574/app-api/src/test/kotlin/com/exchange/core/api/ledger/infrastructure/persistence/PostgresBalanceStoreTest.kt#L73) 73·90·111·128·150·176·194·217·244·267·294·352행 | 기존 사례 재사용. `reserve=available`과 덧셈 초과의 반환/저장 결과만 보강 |
| 저장된 최초 정보와 현재 상태 | [PostgresOrderReservationStoreTest](https://github.com/0Chord/coin-exchange/blob/ea3a3f446a5809db80eae18467db337947678574/app-api/src/test/kotlin/com/exchange/core/api/order/infrastructure/persistence/PostgresOrderReservationStoreTest.kt#L168) update·부분/전량 체결·수수료 포함 저장 조회 | 정상 전이 뒤 객체 equality 재사용. 최초 정보 변조를 거절하는 정책은 만들지 않음 |
| 원장 INSERT 중 후반 실패·source event 중복 | [PostgresLedgerTransactionStoreTest](https://github.com/0Chord/coin-exchange/blob/ea3a3f446a5809db80eae18467db337947678574/app-api/src/test/kotlin/com/exchange/core/api/ledger/infrastructure/persistence/PostgresLedgerTransactionStoreTest.kt#L132) 132·180행 | 거래/분개 롤백과 중복 거절 재사용. Service 재호출의 추가 변경 없음은 아래에서 보강 |
| 자금 부족이면 새 예약도 롤백, 같은 주문 중복 동결 거절 | [OrderFundingServiceTest](https://github.com/0Chord/coin-exchange/blob/ea3a3f446a5809db80eae18467db337947678574/app-api/src/test/kotlin/com/exchange/core/api/order/application/OrderFundingServiceTest.kt#L111) 111·135행 | 재사용. 정확한 전액 예약·행 부재 INSERT 롤백·다른 주문 경쟁 보강 |
| 예약 해제 정상·중복·부재·부족 롤백·같은 주문 경쟁 | [OrderReservationReleaseServiceTest](https://github.com/0Chord/coin-exchange/blob/ea3a3f446a5809db80eae18467db337947678574/app-api/src/test/kotlin/com/exchange/core/api/order/application/OrderReservationReleaseServiceTest.kt#L94) 94·119·142·163·198행 | 재사용. 부족 실패의 예약 전체 equality와 서로 다른 예약 경쟁 보강 |
| 한 체결 정상, 양쪽 후반 지급 실패 롤백 | [TradeSettlementServiceTest](https://github.com/0Chord/coin-exchange/blob/ea3a3f446a5809db80eae18467db337947678574/app-api/src/test/kotlin/com/exchange/core/api/order/application/TradeSettlementServiceTest.kt#L157) 157·370·598행 | 재사용. 실제 hold 부족으로 후반 실패하는 변형 보강 |
| 이전 체결 성공 보존·다음 체결 실패 롤백, 부분 체결 뒤 해제 | [TradeSettlementServiceTest](https://github.com/0Chord/coin-exchange/blob/ea3a3f446a5809db80eae18467db337947678574/app-api/src/test/kotlin/com/exchange/core/api/order/application/TradeSettlementServiceTest.kt#L805) 805·842행 | 이미 있는 계약. 신규 ‘후반 실패’ 테스트를 다시 복제하지 않음 |
| 실제 HTTP 전량 체결·미체결 BUY/SELL 취소 | [OrderLifecycleE2ETest](https://github.com/0Chord/coin-exchange/blob/ea3a3f446a5809db80eae18467db337947678574/app-api/src/test/kotlin/com/exchange/core/api/order/application/OrderLifecycleE2ETest.kt#L97) 97·257·349행 | 재사용. 부분 체결 후 취소는 선택한 대표 1개만 추가 |

`release=hold`, `consumeHold=hold`의 정확한 소진은 각각 기존 해제 Service 정상 사례와 정산 Service 정상 사례에도 있다. 저장소 직접 테스트에 없다는 이유로 계약 전체가 미검증이라고 하지 않는다.

## 순수 도메인 수용 사례

정상 사례의 기대 금액은 아래의 초기 상태와 정수 이동에서 정한다. DB 결과를 도메인 실행 결과와만 비교하거나 구현 계산식을 그대로 복사하지 않는다.

| 초기 상태·입력 | 기대 결과·보존 | 상태/근거 |
| --- | --- | --- |
| Balance `(available=600, hold=400)`, release 150 | 새 결과 `(750,250)`, 원본 `(600,400)`, user/asset 유지, 총액 1,000 유지 | 기존 정상 사례 어설션 보강 |
| Balance `(7,2)`에서 reserve 7, 별도 `(2,7)`에서 release 7/consumeHold 7 | 각각 `(0,9)` / `(9,0)` / `(2,0)`. 원본·식별자 유지 | 정확한 소진 보강. consume은 총액을 보존하는 이동이 아님 |
| Balance `(1,MAX)`에서 reserve 1, 별도 `(MAX,1)`에서 release 1 | 도메인은 `ArithmeticException`, 원본 전체 유지 | 두 이동의 덧셈 초과 신규. 기존 credit overflow 재사용 |
| ACTIVE BUY 예약: 거래500+수수료5=총505, 수량5. 부분 체결 2에서 거래감소200/수수료감소2 | 수량3, 총303, 수수료3, ACTIVE. 원본·최초 정보·정책 유지 | 기존 수수료 포함 부분 전이 보강 |
| 같은 예약에서 거래감소501, 또는 별도 입력으로 수수료감소6 | `IllegalArgumentException`, 원본 전체 유지 | 거래500/수수료5의 각 장부 한도. 총505보다 작다는 이유로 거래501을 허용하면 안 됨 |
| 정상/전량/해제 및 거절, 다음 계산에 앞선 예약 결과 사용 | 최초·중간 입력 객체 전체 유지. market/order/user/side/asset/price/최초수량/최초예약/정책 보존 | 기존 전이·계산기 어설션 보강. 전량 뒤 SETTLED, 해제 뒤 RELEASED 유지 |
| KRW 차변7/대변7의 mutable 입력 목록으로 원장 생성 후 입력 목록 변경 | 원장에는 최초 두 분개 유지. 노출 목록 변경도 허용하지 않음 | 기존 원장 정상 생성 사례 확장. 자산별 균형 검사 재사용 |

`MAX`는 `Long.MAX_VALUE`다. 원본 금액 합계를 검증할 때 테스트 계산 자체를 Long overflow로 실패시키지 않는다. 개별 필드가 유효한 큰 금액 fixture와 수학적 합을 구분한다. `Amount` 음수 생성 거절과 FeeRate/FeeRemainder의 생성 경계는 기존 값 테스트를 재사용한다. Balance의 0 금액 허용 정책을 새로 금지하지 않는다.

분할 수수료 기준은 이미 독립 상수로 검증돼 있다. 가격51, 수량 `[1,1,1,2]`, taker1%에서 청구액 `[0,1,0,1]`, 나머지 `[510000,20000,530000,550000]`, 마지막 총 청구2·나머지550000이다. 같은 계산을 새로 복제하지 않고 최초·중간 예약 보존을 연결한다. 소수 나머지를 실제 hold 금액으로 더하지 않는다.

## DB·Service 수용 사례

| 초기 상태·입력 | 기대 결과·금지할 변화 | #22 처리 |
| --- | --- | --- |
| Balance `(7,2)`, DB reserve 7 | 반환 `(0,9)`, DB도 `(0,9)`, 사용자/자산 유지 | SQL 정확한 전액 예약 신규. release/consume 정확한 소진은 기존 Service 결과 재사용 |
| 현재 Funding fixture의 BUY 거래500+수수료5, available505/hold0 | 예약 1건, available0/hold505 | Service 전액 예약 신규. 도메인/SQL의 `>=` 한도 근거 |
| SQL `(1,MAX)` reserve1, 별도 `(MAX,1)` release1 | 산술 초과로 실패, 해당 DB 행의 금액·식별자 유지 | 도메인과 실패 의미 대조. SQL 오류를 `ArithmeticException`으로 변환하는 정책은 추가하지 않음 |
| 잔고 행이 없는 새 주문의 Funding 호출 | `BalanceNotFoundException`, 먼저 INSERT한 해당 예약도 없음 | 기존 저장소 행 부재·Service 부족 롤백에서 빠진 연결 1개 |
| available1000/hold0, 서로 다른 BUY 2개가 각각 거래700+수수료7=707 요구 | 성공1·부족1. 잔고 `(293,707)`, 승자 예약 1건, 패자 예약 없음 | Service 예약 경쟁 신규. 승자의 주문 ID는 고정하지 않음 |
| available900/hold100, 서로 다른 ACTIVE 예약 각각70 해제 경쟁 | 성공1·부족1. 잔고 `(970,30)`. 승자는 RELEASED/0, 패자는 기존 ACTIVE/70 전체 유지 | Service 해제 경쟁 신규. 의도적 불일치 DB fixture임을 명시 |
| 기존 수수료 없는 정산 fixture에서 BUY 현금 `(800,179)`·SELL 자산 `(8,2)`로 준비. SELL maker/BUY taker, 가격90·수량2로 체결 | taker 현금180 소비에서 `InsufficientHoldException`. maker에 앞서 반영한 변경과 이번 원장·분개도 롤백. 양쪽 예약·전체 잔고가 호출 직전과 동일하며 BUY hold는179 유지 | 기존 hold200을179로 줄이는 것은 호출 전 준비다. 실패 뒤200으로 복구한다고 기대하지 않음. 기존 후반 지급 행 부재 롤백의 업무 부족 변형 1개 |
| 기존 수량5의 분할 정산 fixture에서 첫 수량1 체결 성공 후, 같은 체결을 Service에 다시 전달 | source event 중복으로 거절, 예약·잔고·원장/분개가 첫 성공 직후와 동일 | 재호출 수량1을 계산할 잔량4를 유지하여 원장 중복 분기까지 도달. ACTIVE 여부만 확인하면 잔량 부족으로 먼저 거절될 수 있음. 정상 반복 응답/자동 재시도 추가 안 함 |
| 같은 주문 해제 2번 또는 동시 2번 | 둘 다 정상 결과 가능, 실제 잔고 반환 1번 | 기존 테스트 재사용. 위 ‘서로 다른 예약’의 성공1·실패1과 혼동 금지 |
| 두 번째 분할 정산의 지급 행 부재 | 첫 정산 기록은 남고 두 번째 변경만 롤백. 실패 직전에 없앤 행은 계속 부재 | 기존 842행의 전체 snapshot·원장 개수 어설션 재사용 |

SQL 산술 초과 사례는 기존 동시성 테스트처럼 `@Transactional(propagation = Propagation.NOT_SUPPORTED)`로 테스트 전체를 감싸는 트랜잭션을 끈다. 저장소 호출의 실패·롤백이 끝난 뒤 DB 행을 다시 조회한다. SQL 오류를 잡은 뒤 이미 실패한 테스트 트랜잭션 안에서 바로 조회하다가 생기는 추가 오류를 잔고 변경 실패로 혼동하지 않는다. 제품의 트랜잭션 설정은 바꾸지 않는다.

경쟁 검사는 기존 Executor·CountDownLatch·Future 도우미와 제한된 대기를 재사용한다. sleep만으로 순서를 추정하지 않고 완료된 결과 수·오류·저장 상태로 판정한다. 같은 두 요청의 모든 스케줄이나 다중 마켓 deadlock/복구까지 보장하지 않는다.

## 이번 완료 조건에 넣지 않는 후보

모든 미작성 분기를 이번 필수 테스트로 늘리지 않는다. BUY/SELL 예약 자산 불일치, fee 정책 map 방어복사, 원장 BigInteger 합산의 추가 상한 사례, 최초 예약 메타데이터를 변조한 update 입력, 각 Service의 모든 행 부재 조합, 두 정산의 전체 경쟁 조합은 별도 후보다. 현재 계약을 새로 바꾸지 않으며 구체 결함이 발견되면 영향받는 범위만 다시 판단한다. 저장된 최초 필드 보존은 기존 정상 update equality로 대조한다.

</details>

<details markdown="1">
<summary>HTTP 대표 사례와 범위 결정</summary>

## 포함 확정 — 부분 체결 후 취소

기준 커밋의 HTTP 테스트는 전량 체결과 미체결 BUY/SELL 취소를 검증한다. 부분 체결 뒤 해제는 Service 수준에는 있지만 HTTP·매칭·DB 연결 사례가 빠져 있어, 기존 `OrderLifecycleE2ETest`에 BUY 부분 체결 후 취소 1개를 추가하기로 합의했다. 기존 MockMvc로 JSON·Spring HTTP 처리 경로를 실행하고 실제 PostgreSQL을 조회한다. 외부 TCP 연결이나 별도 웹 서버를 시험하는 범위는 아니다. SELL 조합·모든 오류를 HTTP로 복제하지 않는다.

고정 조건은 기존 테스트 Bean의 BTC scale0, maker0.5%·taker1%다. 구매자 현금1,000,000·BTC0, 판매자 현금0·BTC10. SELL 가격90,000/수량1을 먼저 제출하고, BUY 지정가100,000/수량3을 제출한 뒤 BUY를 취소한다.

| 관측 시점 | 기대 상태·근거 |
| --- | --- |
| BUY 예약 | 거래300,000+수수료3,000=303,000 동결 |
| 1개 체결 후 | BUY 체결 대금90,000+수수료900=90,900 소비. 남은 거래200,000+수수료2,000=202,000 동결. 가격 개선과 미사용 수수료10,100 반환. 구매자 현금707,100/hold202,000·BTC1, 예약 ACTIVE/수량2 |
| 남은 BUY 취소 후 | 구매자 현금909,100/hold0·BTC1, 예약 RELEASED/남은금액0·남은수량2. 이미 체결한 1개·수수료900은 유지 |
| 판매자·원장 | SELL은 SETTLED. 판매자 BTC9·현금89,550. maker 수수료450+taker900=거래소 수수료1,350. 정산 거래 1건과 자산별 차대 균형 유지. 취소는 정산 원장을 추가하지 않음 |
| 이벤트 | SELL 주문장 진입, BUY 체결과 남은 주문장 진입, BUY 취소가 저장됨. HTTP JSON·기존 이벤트 변환 유지 |

최종 현금 기대값은 `1,000,000 - 90,000 - 900 = 909,100`에서 정한다. 구현 계산기 반환값을 그대로 테스트 정답으로 쓰지 않는다. 중간 금액은 지정가·남은수량·고정 수수료율의 독립 상수로 확인한다.

### HTTP 범위 결정

2026-10-03 기존 MockMvc·PostgreSQL·고정 수수료 정책을 재사용하여 대표 HTTP 사례 1개를 포함하기로 확정했다. 서비스 수준의 검증과 HTTP 요청부터 저장까지의 연결을 함께 확인한다. SELL 조합이나 모든 오류 조합으로 범위를 늘리지 않는다. 실행 결과는 구현 읽기에 기록하며 사람의 PR 검토와 구분한다.

</details>

<details markdown="1">
<summary>실행 계획·완료 기준·후속 인계</summary>

## 예정 검증과 결과 읽기

아래 명령은 **이번에 실행한 결과가 아니라 후속 구현의 실행 계획**이다. JDK25·Gradle Wrapper를 사용한다. 이 폴더가 아닌 Desktop 원본에서 상대 경로 명령을 실행한 결과를 이 변경의 검증으로 쓰지 않는다.

순수 도메인 — Docker 없이:

```bash
cd /Users/0chord/.codex/worktrees/issue22-contract-spec/exchange-core
./gradlew :domain-ledger:test :domain-order:test :domain-fee:test --no-daemon --rerun-tasks --console=plain
```

저장·트랜잭션 — Docker/PostgreSQL Testcontainers와 실제 Spring Bean:

```bash
cd /Users/0chord/.codex/worktrees/issue22-contract-spec/exchange-core
./gradlew :app-api:test \
  --tests '*PostgresBalanceStoreTest' \
  --tests '*PostgresOrderReservationStoreTest' \
  --tests '*PostgresLedgerTransactionStoreTest' \
  --tests '*OrderFundingServiceTest' \
  --tests '*OrderReservationReleaseServiceTest' \
  --tests '*TradeSettlementServiceTest' \
  --no-daemon --rerun-tasks --console=plain
```

확정된 HTTP 1개와 기존 HTTP suite를 함께 실행:

```bash
cd /Users/0chord/.codex/worktrees/issue22-contract-spec/exchange-core
./gradlew :app-api:test --tests '*OrderLifecycleE2ETest' --no-daemon --rerun-tasks --console=plain
```

최종 회귀는 기존 `build`와 P01~P09 보고서 명령을 재사용한다. 변경된 작업은 다시 실행하고 변경 없는 결과는 직전 실행 근거·소스와 대조해 재사용한다. 전체를 실제 재실행해야 할 때는 `--rerun-tasks`를 붙인다. 새 결과 해석 도구를 만들지 않는다.

```bash
cd /Users/0chord/.codex/worktrees/issue22-contract-spec/exchange-core
./gradlew build :architecture-tests:verifyArchitectureReport --no-daemon --continue --console=plain
```

먼저 실패 이유를 읽는다. JDK·Docker·컨테이너 연결·컴파일 실패는 준비 문제, 기대 상태/반환/원장 불일치는 해당 계약의 실패다. 테스트 미실행·필터 누락·skip을 통과로 기록하지 않는다. 환경 재시도는 원래 실패와 코드 변경 여부를 함께 보존한다.

보고서는 기존 `<module>/build/reports/tests/test/index.html`와 `<module>/build/test-results/test/TEST-*.xml`이다. 구현 결과에는 실행 커밋/현재 변경 내용, 대상 suite·테스트명, 실제 실행/UP-TO-DATE/미실행, 실패 원인, 결과·남은 한계를 연결한다. 기존 PR #37 CI 성공은 기준점의 근거이며 새 #22 사례의 실행 증거가 아니다.

## 완료 기준

- 위 매핑에서 기존/어설션 보강/신규/후속을 구분하고, 지정한 단위의 사례를 실제 테스트명과 연결한다.
- 원본·중간 결과·식별자·최초 정보·정책 보존 및 거래/수수료 한도 거절을 독립 기대값으로 확인한다.
- 도메인·SQL의 같은 금액 의미, 정확한 전액 예약, 산술 초과의 상태 보존을 확인한다. 구현 방식·예외 종류를 억지로 통일하지 않는다.
- 예약 경쟁과 서로 다른 해제 경쟁에서 성공한 상태만 커밋되고, 실패한 호출의 예약 변경이 남지 않는다.
- 한 정산의 hold 부족·중복 호출은 추가 잔고/예약/원장 반영이 없다. 기존 분할 후반 실패가 이전 성공을 유지한다는 증거를 재사용한다.
- 확정한 HTTP 대표1개의 범위를 결정 기록과 실제 결과에 연결한다. 외부 TCP나 모든 실패 조합까지 검증했다고 확대하지 않는다.
- 필수 suite·회귀 결과와 환경/미실행을 구분한다. 정상·실패 흐름을 자연어 → 실제 코드 → 테스트 → 결과로 사람이 따라갈 수 있게 기존 HTML에 연결한다.
- 의도한 SQL·트랜잭션·API·Bean·처리 정책을 유지한다. 발견한 결함은 구체 반례와 영향부터 설명하고 필요한 최소 수정만 제안한다.

이번에는 구현을 수정하지 않아도 새 회귀 사례가 처음부터 통과할 수 있다. 기존에 올바른 동작을 억지로 깨뜨려 TDD Red를 만들지 않는다. 실제 결함이 재현되면 그 이유로 실패하는 테스트부터 잡고, 기대값 검토 후 수정한다. 통과하는 회귀도 잘못된 결과를 구별할 어설션인지 확인한다.

## 후속 인계와 문서 관계

이슈 #22와 이 문서가 입력이다. 첫 결과는 단위 1의 기존 사례 어설션 보강과 거래/수수료 한도 반례다. 새 아키텍처·폴더 이동·검사 규칙은 필요하지 않다. #23의 큐·실패·시간 초과 정책은 그대로 두고, #24가 최종 주석을 정리하며 #25가 CI 적용·읽기 안내를 최종 대조한다.

[공통 컨벤션](conventions-design.md), [개발·주문 흐름](flow-and-scope-contract.md), [검사 기반 후속 책임표](architecture-check-spec.md)와 기존 #22 이슈의 범위·제외·완료 기준을 이어받았다. 과거 공통 초안에 있던 상태·SQL 상세 사례도 같은 입력이다. 별도 파일 유무를 명세 유무로 판단하지 않는다.

이 명세는 로컬 구현·검증의 기준이다. PR·CI·병합과 이슈·보드의 현재 상태는 GitHub에서 별도로 확인한다.

</details>
