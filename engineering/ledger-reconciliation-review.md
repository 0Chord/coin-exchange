# #74 원장·잔고·예약 대조 — 구현 읽기

**로컬 구현·실행 검증·독립 리뷰 완료. 사람의 검토는 미완료. PR은 아직 게시하지 않았다.**

작업 폴더: `/Users/0chord/.codex/worktrees/issue74-ledger-reconciliation/exchange-core` · 브랜치 `feat/ledger-reconciliation/74`.
기준은 PR #80이 병합된 통합 브랜치 `eecbf481`이다. 기존에는 돈을 저장하는 기능만 있었고, 이번에는 기록을 더해 실제 잔고와 대조하는 내부 호출을 추가했다.

## 먼저 볼 세 흐름

| 흐름 | 입력 → 판단 → 결과 | 연결 파일 |
| --- | --- | --- |
| 같은 시점의 자료 읽기 | 마켓·두 자산 → 읽기 전용 트랜잭션 → 원장·잔고·예약을 전부 읽음 | 저장 포트·PostgreSQL 구현·Bean 연결 |
| 세 금액 따로 비교하기 | 완전한 자료 → 사용자·자산 합집합 → 원장 available/hold와 예약 합계를 각각 DB와 비교 | 순수 판단·입력·결과 |
| 실패와 불일치 구분하기 | 조회 실패 / 읽은 기록의 이상 / 다른 마켓 예약 → 검증 불가 또는 불일치 | 유즈케이스·저장소·순수 판단 |

**HTTP API는 없다. 돈을 수정하거나 멈춘 마켓을 재개하지 않는다.** 결과를 복구 후 재개 판단과 연결하는 것은 #58이다.

## 1. 같은 시점의 자료를 전부 읽는다

호출자가 BTC-KRW와 BTC·KRW를 넘긴다 → 유즈케이스가 저장소에 읽기를 요청한다 → 저장소는 외부 트랜잭션이 열려 있으면 거절한다 → 새 `REPEATABLE_READ` 트랜잭션에서 실제 DB 읽기 전용 모드를 설정한다 → 원장·잔고·예약 조회가 전부 끝나면 순수 판단으로 넘긴다.

원장 머리글과 분개는 LEFT JOIN으로 함께 읽는다. 분개가 없는 머리글도 조회 결과에 남아야 손상을 발견할 수 있기 때문이다. 양수 금액·분개 순서·자산별 차변/대변 균형을 확인한다. 이미 읽을 수 있는 손상은 원본 식별자를 문제 목록에 남긴다. 사용자 잔고와 비교하지 않는 시스템 자금·수수료 항목도 거래 균형 검증에는 들어간다.

잔고와 예약은 BTC·KRW만 읽는다. 예약은 다른 마켓까지 확인해서 같은 자산의 hold가 다른 마켓에 묶였는지 확인한다. 원장 머리글은 현재 작은 개발 DB에서 전체를 확인하며 N+1 조회를 만들지 않았다.

## 2. 총액이 같아도 각각 비교한다

처음 1,000 → 300 예약이면 원장 기준 700/300, DB도 700/300, 활성 예약은 300이다. 이 경우 **일치**다. DB가 800/200이면 총액은 그대로지만 사용 가능 금액 +100, 원장 대비 hold −100, 예약 대비 hold −100을 각각 **불일치**로 보고한다.

원장 사용자 계정은 CREDIT−DEBIT로 합산한다. BTC와 KRW는 따로 더하고, 큰 합계는 BigInteger로 계산한다. 사용자 ID에 콜론이 있어도 자산과 AVAILABLE/HOLD 끝부분을 맞춰 해석한다.

원장·잔고·예약 중 한 곳에라도 등장한 사용자·자산을 검사한다. DB 행이 없으면 없는 값으로 보고한다. 원장 없이 실제 0 잔고 행만 있어도 검사 대상이므로 **일치**다. 세 자료 모두 없을 때만 **대상 없음**이다. 읽을 수 있는 손상 자료가 있으면 정상 자료만 더한 부분 원장 합계를 정답처럼 표시하지 않는다. 손상된 거래에만 남은 사용자도 식별할 수 있다면 검사 대상과 누락 보고에 남긴다.

## 3. 조회 중 실패하면 왜 결과를 버릴까

**질문:** 원장 조회는 성공했지만 예약 조회가 실패하면 무엇을 더 자세히 볼까?

**사용자 실제 답변:** “조회 도중 실패했을 때 검증 불가가 되는 이유 (추천)”

원장 조회 성공 → 잔고 조회 성공 → 예약 조회 SQL 실패 → 저장소의 트랜잭션 종료 → 유즈케이스가 **검증 불가 / 예약 조회 실패 이유**를 반환한다. 앞서 읽은 숫자와 차이 목록은 반환하지 않는다.

예약을 못 읽었으므로 DB hold가 실제 활성 예약과 같은지 아직 판단할 수 없다. 두 숫자만 우연히 같더라도 세 비교가 끝나지 않았다. 따라서 일부 성공을 일치나 대상 없음으로 바꾸면 안 된다. 재시도는 이번 유즈케이스가 자동으로 수행하지 않는다. 원래 돈과 기록은 변경하지 않는다.

| 조건 | 결과 | 중단 뒤 상태 |
| --- | --- | --- |
| 모든 읽기와 비교가 정상 | 일치 또는 대상 없음 | 돈과 기록 그대로 |
| 금액 차이·행 누락·읽을 수 있는 손상 | 불일치 + 대상/항목/차이 또는 원본 | 돈과 기록 그대로 |
| 연결·SQL·트랜잭션 실패 | 검증 불가 + 조회 단계/원인 | 부분 숫자는 버림; 돈과 기록 그대로 |
| 외부 트랜잭션 안에서 호출 | 검증 불가 + 호출 경계 오류 | 자료 조회를 시작하지 않음 |
| 같은 자산의 다른 마켓 활성 예약 | 검증 불가 + 범위 미지원 | hold 소유 범위를 단정하지 않음 |

이번 실제 실패 테스트는 원장·잔고를 읽은 뒤 **예약 테이블 이름을 테스트에서 임시 변경해 SQL 실패**를 발생시킨다. 테스트가 끝나면 이름을 되돌린다. 실제 검사 코드가 테이블을 바꾸는 것은 아니다.

## 4. 테스트를 읽는 순서

아래는 새 테스트 **28개 정의**의 모든 사례 설명이다. 순수 계산 13개, 실제 PostgreSQL 15개다. 일부 정의 안에는 입력 반복이 있다. 원장 손상은 3종류, hold 부족·초과는 2종류, 조회 미변경은 2회 반복한다. 정의 수와 반복 실행 횟수는 구분한다.

DB 공통 준비는 테스트별 기존 자료를 지우고, 실제 개시·예약·정산·반환 서비스를 통해 새 자료를 만드는 것이다. PostgreSQL 16 Testcontainers와 실제 Spring Bean을 사용한다. HTTP·MockMvc는 사용하지 않는다. 동시 조회 테스트의 JDBC 하위 클래스는 실제 쿼리 결과를 만든 다음 대기 지점만 제공한다. 가짜 잔고나 가짜 DB 응답을 반환하지 않는다.


## 순수 계산 테스트

### 순수 계산 1. 원장과 잔고와 활성 예약 700 300 300은 일치한다

- **Given — 준비:** 원장 1,000과 예약 300, DB 700/300을 준비한다.
- **When — 실행:** 순수 판단에 자료를 넘긴다.
- **Then — 확인:** 일치·대상 1개·700/300/300·빈 차이 목록을 확인한다.

[실제 테스트와 assertion 보기](/Users/0chord/.codex/worktrees/issue74-ledger-reconciliation/exchange-core/domain-ledger/src/test/kotlin/com/exchange/core/ledger/LedgerReconciliationTest.kt:21)

### 순수 계산 2. DB available만 701이면 사용자와 자산과 차이 1을 보고한다

- **Given — 준비:** 같은 예약 자료에서 DB available만 701이다.
- **When — 실행:** 비교한다.
- **Then — 확인:** buyer/KRW의 AVAILABLE 기대 700·실제 701·차이 +1 한 건이다.

[실제 테스트와 assertion 보기](/Users/0chord/.codex/worktrees/issue74-ledger-reconciliation/exchange-core/domain-ledger/src/test/kotlin/com/exchange/core/ledger/LedgerReconciliationTest.kt:38)

### 순수 계산 3. 총액이 같아도 available hold 예약의 세 차이를 따로 보고한다

- **Given — 준비:** 원장 700/300·예약 300인데 DB는 800/200이다.
- **When — 실행:** 비교한다.
- **Then — 확인:** AVAILABLE +100, HOLD −100, RESERVATION_HOLD −100을 각각 확인한다.

[실제 테스트와 assertion 보기](/Users/0chord/.codex/worktrees/issue74-ledger-reconciliation/exchange-core/domain-ledger/src/test/kotlin/com/exchange/core/ledger/LedgerReconciliationTest.kt:52)

### 순수 계산 4. 빈 대상과 원장 없는 영 잔고는 서로 다른 결과다

- **Given — 준비:** 모두 빈 자료와 원장 없는 실제 0 잔고를 각각 준비한다.
- **When — 실행:** 두 자료를 차례로 비교한다.
- **Then — 확인:** 빈 자료는 대상 없음, 0 행은 일치·대상 1개다.

[실제 테스트와 assertion 보기](/Users/0chord/.codex/worktrees/issue74-ledger-reconciliation/exchange-core/domain-ledger/src/test/kotlin/com/exchange/core/ledger/LedgerReconciliationTest.kt:69)

### 순수 계산 5. 원장과 예약에만 있는 사용자도 누락된 잔고를 보고한다

- **Given — 준비:** 원장·예약에만 있고 DB 행이 없는 사용자와 고아 예약을 준비한다.
- **When — 실행:** 각각 비교한다.
- **Then — 확인:** 누락된 잔고를 표시하고 DB 값을 null로 유지한다.

[실제 테스트와 assertion 보기](/Users/0chord/.codex/worktrees/issue74-ledger-reconciliation/exchange-core/domain-ledger/src/test/kotlin/com/exchange/core/ledger/LedgerReconciliationTest.kt:83)

### 순수 계산 6. Long 범위를 넘는 원장 합계와 차이도 정확히 계산한다

- **Given — 준비:** Long 최대 금액의 개시를 두 번 기록한 자료를 준비한다.
- **When — 실행:** 원장을 합산한다.
- **Then — 확인:** 합계 18,446,744,073,709,551,614와 차이가 BigInteger로 정확하다.

[실제 테스트와 assertion 보기](/Users/0chord/.codex/worktrees/issue74-ledger-reconciliation/exchange-core/domain-ledger/src/test/kotlin/com/exchange/core/ledger/LedgerReconciliationTest.kt:104)

### 순수 계산 7. 역분개도 합산하며 자산 간 차이를 상쇄하지 않는다

- **Given — 준비:** 개시와 반대 방향 역분개, BTC·KRW의 서로 다른 차이를 준비한다.
- **When — 실행:** 역분개와 두 자산 자료를 각각 비교한다.
- **Then — 확인:** 역분개로 합계 0이 되며 BTC −1과 KRW +1을 상쇄하지 않는다.

[실제 테스트와 assertion 보기](/Users/0chord/.codex/worktrees/issue74-ledger-reconciliation/exchange-core/domain-ledger/src/test/kotlin/com/exchange/core/ledger/LedgerReconciliationTest.kt:121)

### 순수 계산 8. 종료된 예약은 대상에 남지만 활성 hold에 더하지 않는다

- **Given — 준비:** 원장 없는 실제 0 잔고와 금액 0인 RELEASED·SETTLED 예약을 준비한다.
- **When — 실행:** 비교한다.
- **Then — 확인:** 대상 1개가 유지되며 종료된 예약은 활성 합계에 들어가지 않아 예약 합계 0으로 일치한다.

[실제 테스트와 assertion 보기](/Users/0chord/.codex/worktrees/issue74-ledger-reconciliation/exchange-core/domain-ledger/src/test/kotlin/com/exchange/core/ledger/LedgerReconciliationTest.kt:164)

### 순수 계산 9. 같은 자산의 다른 마켓 활성 예약은 비교 범위를 지원하지 않는다

- **Given — 준비:** 같은 자산의 ACTIVE 예약이 다른 마켓에 있다.
- **When — 실행:** 비교한다.
- **Then — 확인:** 범위 미지원 검증 불가이며 금액 목록은 비어 있다.

[실제 테스트와 assertion 보기](/Users/0chord/.codex/worktrees/issue74-ledger-reconciliation/exchange-core/domain-ledger/src/test/kotlin/com/exchange/core/ledger/LedgerReconciliationTest.kt:177)

### 순수 계산 10. 손상된 기록이 있으면 부분 합계를 원장 잔고로 제시하지 않는다

- **Given — 준비:** 읽힌 기록에 손상 원본을 문제 목록으로 전달한다.
- **When — 실행:** 비교한다.
- **Then — 확인:** 불일치·원본 유지·원장 금액 null을 확인한다. 부분 합계를 정답으로 내놓지 않는다.

[실제 테스트와 assertion 보기](/Users/0chord/.codex/worktrees/issue74-ledger-reconciliation/exchange-core/domain-ledger/src/test/kotlin/com/exchange/core/ledger/LedgerReconciliationTest.kt:193)

### 순수 계산 11. 사용자 ID의 콜론은 보존하고 계정 자산 불일치는 손상으로 보고한다

- **Given — 준비:** 콜론이 포함된 사용자 계정과 계정/분개 자산이 다른 계정을 준비한다.
- **When — 실행:** 정상과 손상 자료를 각각 비교한다.
- **Then — 확인:** 콜론 사용자 식별은 일치하며, 자산 불일치는 기록 이상으로 보고한다.

[실제 테스트와 assertion 보기](/Users/0chord/.codex/worktrees/issue74-ledger-reconciliation/exchange-core/domain-ledger/src/test/kotlin/com/exchange/core/ledger/LedgerReconciliationTest.kt:212)

### 순수 계산 12. 손상된 예약은 무시하지 않고 검사 입력 목록도 외부 수정으로 바뀌지 않는다

- **Given — 준비:** 금액이 남은 종료 예약과 복사 전 입력 목록을 준비한다.
- **When — 실행:** 외부 목록을 지운 뒤 비교하고 마지막에 보관 목록 수정도 시도한다.
- **Then — 확인:** 스냅샷 자료는 유지되고 내부 목록 수정은 거절되며 예약 손상은 불일치다.

[실제 테스트와 assertion 보기](/Users/0chord/.codex/worktrees/issue74-ledger-reconciliation/exchange-core/domain-ledger/src/test/kotlin/com/exchange/core/ledger/LedgerReconciliationTest.kt:241)

### 순수 계산 13. 같은 자산과 와일드카드 범위는 입력에서 거절한다

- **Given — 준비:** 동일 자산 두 개 또는 별표가 든 범위를 준비한다.
- **When — 실행:** 범위를 생성한다.
- **Then — 확인:** 잘못된 입력은 IllegalArgumentException으로 거절된다.

[실제 테스트와 assertion 보기](/Users/0chord/.codex/worktrees/issue74-ledger-reconciliation/exchange-core/domain-ledger/src/test/kotlin/com/exchange/core/ledger/LedgerReconciliationTest.kt:253)


## 실제 DB 테스트

### 실제 DB 1. 초기 자금과 예약을 실제 DB에서 대조하고 반복 조회는 모든 저장값을 유지한다

- **Given — 준비:** 실제 OPENING 1,000과 RESERVE 300을 저장한다.
- **When — 실행:** 전체 DB 내용을 보관하고 내부 대조를 두 번 호출한다.
- **Then — 확인:** 일치·700/300/300, 차이 없음, 네 테이블의 값과 시각이 모두 그대로다.

[실제 테스트와 assertion 보기](/Users/0chord/.codex/worktrees/issue74-ledger-reconciliation/exchange-core/app-api/src/test/kotlin/com/exchange/core/api/ledger/infrastructure/persistence/PostgresLedgerReconciliationStoreTest.kt:129)

### 실제 DB 2. DB 사용 가능 금액만 1 늘어나면 원장 기준과 차이 1을 보고한다

- **Given — 준비:** 실제 예약 직후 available만 테스트 SQL로 701로 바꾼다.
- **When — 실행:** 내부 대조를 호출한다.
- **Then — 확인:** 불일치·AVAILABLE 기대 700·실제 701·차이 +1이다.

[실제 테스트와 assertion 보기](/Users/0chord/.codex/worktrees/issue74-ledger-reconciliation/exchange-core/app-api/src/test/kotlin/com/exchange/core/api/ledger/infrastructure/persistence/PostgresLedgerReconciliationStoreTest.kt:145)

### 실제 DB 3. 한 개 체결하고 두 개 취소한 뒤 네 잔고와 예약 합계를 대조한다

- **Given — 준비:** 구매자 KRW 1,000·판매자 BTC 10과 수령 0 행을 준비한다.
- **When — 실행:** BUY 3개 예약 → SELL 1개 예약 → 1개 정산 → 남은 BUY 취소 → 대조한다. 이후 구매자 KRW available만 901로 바꾸고 다시 대조한다.
- **Then — 확인:** 일치·구매자 KRW 900/BTC 1·판매자 KRW 100/BTC 9·활성 구매 예약 0이고 대조 전후 DB는 같다. 손상 뒤에는 원장 900·DB 901·차이 +1이며 검사 후 손상 자료도 자동 수정되지 않는다.

[실제 테스트와 assertion 보기](/Users/0chord/.codex/worktrees/issue74-ledger-reconciliation/exchange-core/app-api/src/test/kotlin/com/exchange/core/api/ledger/infrastructure/persistence/PostgresLedgerReconciliationStoreTest.kt:190)

### 실제 DB 4. 수수료와 가격 개선 반환이 있는 실제 정산도 자산별로 일치한다

- **Given — 준비:** 구매자 KRW 1,000,000·판매자 BTC 10, maker 0.5%·taker 1% 정책이다.
- **When — 실행:** BUY 2×100,000·SELL 2×90,000을 예약하고 2×90,000 정산 후 대조한다.
- **Then — 확인:** 일치·구매자 KRW 818,200/BTC 2·판매자 KRW 179,100/BTC 8·hold 모두 0·시스템 수수료 2,700이다.

[실제 테스트와 assertion 보기](/Users/0chord/.codex/worktrees/issue74-ledger-reconciliation/exchange-core/app-api/src/test/kotlin/com/exchange/core/api/ledger/infrastructure/persistence/PostgresLedgerReconciliationStoreTest.kt:212)

### 실제 DB 5. 반환 원장만 삭제하거나 효과를 중복 기록하면 정상 잔고와 다른 합계를 보고한다

- **Given — 준비:** 부분 체결·취소가 끝나 DB 900/0이다.
- **When — 실행:** 다른 ID로 RELEASE 효과를 복제해 대조한 뒤 원본과 복제 RELEASE를 지우고 다시 대조한다.
- **Then — 확인:** 중복은 원장 1,100/−200, 누락은 700/200으로 각각 불일치다. 중간 정상 상태를 다시 검사한 테스트는 아니다.

[실제 테스트와 assertion 보기](/Users/0chord/.codex/worktrees/issue74-ledger-reconciliation/exchange-core/app-api/src/test/kotlin/com/exchange/core/api/ledger/infrastructure/persistence/PostgresLedgerReconciliationStoreTest.kt:231)

### 실제 DB 6. 분개 없는 머리글과 일부 삭제와 계정 자산 손상은 부분 합계로 통과시키지 않는다

- **Given — 준비:** 개시 기록을 준비하고 header-only·한 분개 삭제·계정 자산 오류를 한 종류씩 만든다.
- **When — 실행:** 각 손상마다 DB 내용을 보관하고 대조한다.
- **Then — 확인:** 세 경우 모두 불일치·원본 기록 이상을 표시하고 검사 전후 DB가 같다.

[실제 테스트와 assertion 보기](/Users/0chord/.codex/worktrees/issue74-ledger-reconciliation/exchange-core/app-api/src/test/kotlin/com/exchange/core/api/ledger/infrastructure/persistence/PostgresLedgerReconciliationStoreTest.kt:258)

### 실제 DB 7. 잔고 행을 삭제해도 원장과 예약 사용자를 검사 대상에서 없애지 않는다

- **Given — 준비:** 원장·예약은 있으나 잔고 행을 지운다.
- **When — 실행:** 대조한 뒤 원장도 지워 예약만 남겨 다시 대조한다.
- **Then — 확인:** 두 경우 모두 사용자 대상과 MISSING_BALANCE가 남고 없는 잔고는 null이다.

[실제 테스트와 assertion 보기](/Users/0chord/.codex/worktrees/issue74-ledger-reconciliation/exchange-core/app-api/src/test/kotlin/com/exchange/core/api/ledger/infrastructure/persistence/PostgresLedgerReconciliationStoreTest.kt:275)

### 실제 DB 8. 빈 대상과 실제 영 잔고 그리고 관련 없는 자산을 구분한다

- **Given — 준비:** 빈 DB, USDT 기록만 있는 DB, KRW 0 잔고 행을 차례로 준비한다.
- **When — 실행:** 각 단계에서 BTC/KRW를 대조한다.
- **Then — 확인:** 앞의 두 단계는 대상 없음, KRW 0 행은 일치·대상 1개다.

[실제 테스트와 assertion 보기](/Users/0chord/.codex/worktrees/issue74-ledger-reconciliation/exchange-core/app-api/src/test/kotlin/com/exchange/core/api/ledger/infrastructure/persistence/PostgresLedgerReconciliationStoreTest.kt:289)

### 실제 DB 9. 외부 트랜잭션과 다른 마켓의 활성 예약은 검증 불가다

- **Given — 준비:** 외부 트랜잭션 호출과 ETH-KRW 활성 예약을 각각 준비한다.
- **When — 실행:** 외부 트랜잭션 안에서 대조한 뒤 다른 마켓 예약 자료를 대조한다.
- **Then — 확인:** 둘 다 검증 불가지만 OUTER_TRANSACTION과 OTHER_MARKET_HOLD 원인이 구분된다.

[실제 테스트와 assertion 보기](/Users/0chord/.codex/worktrees/issue74-ledger-reconciliation/exchange-core/app-api/src/test/kotlin/com/exchange/core/api/ledger/infrastructure/persistence/PostgresLedgerReconciliationStoreTest.kt:298)

### 실제 DB 10. 예약 조회 SQL이 실패하면 앞서 읽은 원장과 잔고도 결과에서 버린다

- **Given — 준비:** 원장·잔고·예약이 정상이고 테이블 이름을 임시 변경한다.
- **When — 실행:** 실제 대조 SQL을 실행하고 finally에서 예약 테이블 이름을 되돌린다.
- **Then — 확인:** 예약 조회 단계의 검증 불가·빈 금액/차이 목록·원본 DB 내용 유지다.

[실제 테스트와 assertion 보기](/Users/0chord/.codex/worktrees/issue74-ledger-reconciliation/exchange-core/app-api/src/test/kotlin/com/exchange/core/api/ledger/infrastructure/persistence/PostgresLedgerReconciliationStoreTest.kt:311)

### 실제 DB 11. 첫 조회 뒤 예약이 커밋돼도 나머지 조회는 이전 스냅샷을 읽는다

- **Given — 준비:** 개시 1,000만 있다. 첫 원장 SELECT 뒤 읽기 스레드를 latch에서 대기시킨다.
- **When — 실행:** 별도 연결의 실제 예약 서비스가 300을 커밋한다 → 읽기를 재개한다 → 다음 대조도 호출한다.
- **Then — 확인:** 첫 대조는 1,000/0/0 일치, 다음 대조는 700/300/300으로 일치한다. 실제 DB 격리 repeatable read와 읽기 전용 on을 확인한다. sleep은 사용하지 않는다.

[실제 테스트와 assertion 보기](/Users/0chord/.codex/worktrees/issue74-ledger-reconciliation/exchange-core/app-api/src/test/kotlin/com/exchange/core/api/ledger/infrastructure/persistence/PostgresLedgerReconciliationStoreTest.kt:329)

### 실제 DB 12. 총액이 같아도 분류 오류와 예약 대비 hold 부족 초과를 각각 보고한다

- **Given — 준비:** 정상 예약 300 뒤 DB를 800/200으로 바꾸고, 그 다음 700/299·700/301로 바꾼다.
- **When — 실행:** 각 상태에서 대조한다.
- **Then — 확인:** 첫 상태의 세 차이는 +100/−100/−100이다. hold 299·301은 원장/예약 대비 각각 −1·+1이다.

[실제 테스트와 assertion 보기](/Users/0chord/.codex/worktrees/issue74-ledger-reconciliation/exchange-core/app-api/src/test/kotlin/com/exchange/core/api/ledger/infrastructure/persistence/PostgresLedgerReconciliationStoreTest.kt:380)

### 실제 DB 13. 기존 원장이 허용한 빈 식별자에 새 제약을 덧붙이지 않는다

- **Given — 준비:** 기존 저장 포트로 빈 거래 ID·빈 사건 ID인 정상 원장을 저장하고 DB 100/0을 준비한다.
- **When — 실행:** 기존 포트 조회와 신규 대조를 차례로 호출한다.
- **Then — 확인:** 기존 조회는 성공하며 대조도 일치·원장 available 100이다. 기존 저장 계약에 새 ID 제약을 덧붙이지 않는다.

[실제 테스트와 assertion 보기](/Users/0chord/.codex/worktrees/issue74-ledger-reconciliation/exchange-core/app-api/src/test/kotlin/com/exchange/core/api/ledger/infrastructure/persistence/PostgresLedgerReconciliationStoreTest.kt:401)

### 실제 DB 14. 손상된 원장에만 남은 사용자도 대상과 누락 잔고를 보고한다

- **Given — 준비:** 개시 1,000에서 시스템 분개와 DB 잔고를 지워 손상된 사용자 분개만 남긴다.
- **When — 실행:** 대조한다.
- **Then — 확인:** 불일치·buyer/KRW 대상 1개·원장 계산 불가·잔고 null·원본 이상과 MISSING_BALANCE를 함께 보고한다.

[실제 테스트와 assertion 보기](/Users/0chord/.codex/worktrees/issue74-ledger-reconciliation/exchange-core/app-api/src/test/kotlin/com/exchange/core/api/ledger/infrastructure/persistence/PostgresLedgerReconciliationStoreTest.kt:422)

### 실제 DB 15. 모든 조회 뒤 트랜잭션 종료가 실패하면 완료 단계와 검증 불가를 보고한다

- **Given — 준비:** 개시·예약 기록과 실제 PostgreSQL을 준비하고, 실제 트랜잭션 관리자에 종료 실패를 주입하는 얇은 제어 장치를 둔다.
- **When — 실행:** 실제 원장·잔고·예약 조회가 끝난 뒤 관리자 종료 호출에서 예외를 발생시킨다.
- **Then — 확인:** 종료 지점 도달·검증 불가·트랜잭션 완료 단계 표시·빈 숫자/차이 목록·DB 내용 유지다. 실제 네트워크 장애를 발생시키는 테스트는 아니다.

[실제 테스트와 assertion 보기](/Users/0chord/.codex/worktrees/issue74-ledger-reconciliation/exchange-core/app-api/src/test/kotlin/com/exchange/core/api/ledger/infrastructure/persistence/PostgresLedgerReconciliationStoreTest.kt:436)

## 실행 기록과 남은 확인

첫 순수/DB 테스트는 새 클래스가 없어 컴파일에서 실패했다. 이는 금액 비교에서 발생한 Red와 구분한다. 첫 DB 실행에서는 신규 SQL의 컬럼명을 잘못 사용해 검증 불가가 반환됐다. 스키마에 맞게 수정한 뒤 최초 순수 계산 13개와 PostgreSQL 12개가 통과했다. 독립 리뷰 반례 2개도 추가했다. DB 데이터는 실제 서비스로 준비하고 기대 숫자는 명세의 고정 계산에서 가져왔다.

| 실제 실행 | 결과와 근거 |
| --- | --- |
| 새 순수 계산 | 13개 통과. ledger 전체 38개, 실패·오류·skip 0 |
| 새 실제 DB | 최종 15개 통과, 실패·오류·skip 0 |
| 기존 개시·예약·반환·정산·Bean 회귀 | 최초 선택 실행의 103개 중 새 DB 12개를 제외한 기존 91개 통과. 이후 변경은 읽기 경로와 새 테스트·포트 검사 등록에 한정 |
| 운영 구조 검사 | 371개 통과; P01~P09 실제 실행·성공 확인 작업 통과 |
| 공통 ktlintCheck·diff 공백 검사 | 통과 |

```bash
cd /Users/0chord/.codex/worktrees/issue74-ledger-reconciliation/exchange-core
./gradlew :domain-ledger:test :app-api:test --tests '*PostgresLedgerReconciliationStoreTest' ktlintCheck :architecture-tests:verifyArchitectureReport
```

[최종 DB 테스트 HTML](/Users/0chord/.codex/worktrees/issue74-ledger-reconciliation/exchange-core/app-api/build/reports/tests/test/index.html) · [ledger 테스트 HTML](/Users/0chord/.codex/worktrees/issue74-ledger-reconciliation/exchange-core/domain-ledger/build/reports/tests/test/index.html) · [구조 테스트 HTML](/Users/0chord/.codex/worktrees/issue74-ledger-reconciliation/exchange-core/architecture-tests/build/reports/tests/test/index.html). 이 빌드 보고서는 로컬 생성물이며 나중 실행하면 갱신된다.

**독립 검토 기록:** 작성 대화를 상속하지 않은 별도 에이전트가 고정 checkout `3160989`에서 직접 반례를 실행해 세 결함을 확인했다. 새 ID 제약 때문에 정상 기존 원장을 불일치로 판단한 점, 손상된 원장만 가진 사용자가 대상에서 빠진 점, 새 포트 검사 등록 누락이다. 범위를 축소하지 않고 실제 회귀 테스트와 공통 대상 보존 로직·기존 포트 목록을 수정했다. 수정본 재리뷰에서 종료 실패의 진단 단계가 마지막 예약 조회로 남는 P3도 확인했다. 실제 DB 읽기 후 관리자 종료 예외를 제어 주입하는 사례를 추가해 의도한 진단 assertion 실패를 확인하고 완료 단계 표시를 수정했다. 최종 구현 커밋 `b1360da7674fa12fe0edd31c3cae51532ec13afc`을 별도 checkout에서 재리뷰했고, 확인된 미해결 결함은 없었다. 리뷰어가 최종 실제 DB 테스트 15개와 공통 린트를 직접 실행했다. 직전 커밋에서 직접 실행한 ledger 전체 38개와 운영·역할 검사 13개, P01~P09 근거는 마지막 변경이 종료 단계 표시 한 줄인 점을 확인하고 재사용했다. 최종 테스트 링크 28개와 실제 코드 발췌·해시 8개도 소스와 대조했다. 이는 모든 잠재 결함이 없다는 보장은 아니다.

SQL 조회 실패·외부 트랜잭션·타 마켓 hold는 실제 실행했다. 트랜잭션 종료 예외는 실제 DB 읽기 뒤 관리자 예외를 제어해 처리 경계를 실행했다. 실제 네트워크 연결 단절이나 DB 자체의 종료 장애는 주입하지 않았다. 전체 app-api 테스트·JMH·부하 측정·원격 CI는 이번에 실행하지 않았다.

최종 DB 사례 추가 후에는 아래 범위만 재실행했다. 위 구조 371개와 기존 회귀는 앞선 성공 근거를 재사용하며, 최종 커밋에서 전체 검사를 반복 실행한 것으로 표시하지 않는다.

```bash
cd /Users/0chord/.codex/worktrees/issue74-ledger-reconciliation/exchange-core
./gradlew :app-api:test --tests '*PostgresLedgerReconciliationStoreTest' ktlintCheck
```

## 변경 파일과 근거 코드

코드 발췌는 아래 파일의 생성 당시 실제 내용이다. 자동 갱신되는 소스가 아니다. 문법을 전부 외우기보다 조회 경계·조건 분기·차이의 부호를 읽으면 된다.

<details markdown="1"><summary>결과 전달과 실패 변환 — 실제 코드 펼치기</summary>

소스 근거 · [전체 파일](/Users/0chord/.codex/worktrees/issue74-ledger-reconciliation/exchange-core/app-api/src/main/kotlin/com/exchange/core/api/ledger/application/ReconcileLedgerUseCase.kt:1) · 내용 해시 `1068d532cf56` · 1~20행

```kotlin
package com.exchange.core.api.ledger.application

import com.exchange.core.ledger.LedgerReconciliation
import com.exchange.core.ledger.LedgerReconciliationReport
import com.exchange.core.ledger.LedgerReconciliationScope
import com.exchange.core.ledger.LedgerReconciliationStore
import com.exchange.core.ledger.LedgerReconciliationUnavailableException
import com.exchange.core.ledger.ReconciliationStatus

/** 읽기가 모두 끝난 뒤 비교한다. 조회 실패는 불일치나 빈 대상과 구분한다. */
class ReconcileLedgerUseCase(
    private val store: LedgerReconciliationStore,
) {
    fun reconcile(scope: LedgerReconciliationScope): LedgerReconciliationReport =
        try {
            LedgerReconciliation().compare(scope, store.read(scope))
        } catch (failure: LedgerReconciliationUnavailableException) {
            LedgerReconciliationReport(scope, ReconciliationStatus.UNAVAILABLE, failure = failure.failure, detail = failure.message)
        }
}

```

</details>

<details markdown="1"><summary>같은 DB 시점과 전체 읽기 — 실제 코드 펼치기</summary>

소스 근거 · [전체 파일](/Users/0chord/.codex/worktrees/issue74-ledger-reconciliation/exchange-core/app-api/src/main/kotlin/com/exchange/core/api/ledger/infrastructure/persistence/PostgresLedgerReconciliationStore.kt:1) · 내용 해시 `0cefebc2604f` · 1~201행

```kotlin
package com.exchange.core.api.ledger.infrastructure.persistence

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.UserId
import com.exchange.core.ledger.Balance
import com.exchange.core.ledger.LedgerPosting
import com.exchange.core.ledger.LedgerPostingSide
import com.exchange.core.ledger.LedgerReconciliation
import com.exchange.core.ledger.LedgerReconciliationScope
import com.exchange.core.ledger.LedgerReconciliationSnapshot
import com.exchange.core.ledger.LedgerReconciliationStore
import com.exchange.core.ledger.LedgerReconciliationUnavailableException
import com.exchange.core.ledger.LedgerTransaction
import com.exchange.core.ledger.LedgerTransactionType
import com.exchange.core.ledger.ReconciliationDifference
import com.exchange.core.ledger.ReconciliationFailure
import com.exchange.core.ledger.ReconciliationItem
import com.exchange.core.ledger.ReservationHold
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionException
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.time.Instant

/**
 * 원장·잔고·예약을 PostgreSQL의 한 읽기 전용 스냅샷에서 가져온다.
 * 낮은 격리 수준의 외부 트랜잭션에 합류하지 않으며, 조회 실패 시 전체 결과를 버린다.
 * 읽을 수 있지만 손상된 기록은 원본 식별자와 함께 불일치 근거로 보존한다.
 */
class PostgresLedgerReconciliationStore(
    private val jdbc: NamedParameterJdbcTemplate,
    transactionManager: PlatformTransactionManager,
) : LedgerReconciliationStore {
    private val transaction =
        TransactionTemplate(transactionManager).apply {
            isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
            isReadOnly = true
        }

    override fun read(scope: LedgerReconciliationScope): LedgerReconciliationSnapshot {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw LedgerReconciliationUnavailableException(ReconciliationFailure.OUTER_TRANSACTION, "대조 검사는 외부 트랜잭션 밖에서 호출해야 합니다")
        }
        var stage = "읽기 전용 트랜잭션 준비"
        try {
            return checkNotNull(
                transaction.execute {
                    // Spring의 힌트에만 의존하지 않고 실제 PostgreSQL 트랜잭션에 쓰기를 금지한다.
                    jdbc.jdbcTemplate.execute("SET TRANSACTION READ ONLY")
                    val problems = mutableListOf<ReconciliationDifference>()
                    stage = "원장 조회"
                    val rows = jdbc.query(LEDGER_SQL, emptyMap<String, Any>()) { row, _ -> rawLedger(row) }
                    val transactions =
                        rows.groupBy { it.id }.mapNotNull { (id, entries) ->
                            val owners =
                                entries
                                    .mapNotNull { entry ->
                                        val asset = entry.asset?.takeIf { it.isNotBlank() }?.let(::AssetId) ?: return@mapNotNull null
                                        val user =
                                            entry.account?.let { LedgerReconciliation.parseUserAccount(it, asset)?.first }
                                                ?: return@mapNotNull null
                                        user to asset
                                    }.distinct()
                            record(problems, "원장/$id", owners) {
                                val header = entries.first()
                                require(entries.map { it.sequence } == (1..entries.size).toList()) { "분개가 누락됐거나 순서가 유효하지 않습니다" }
                                LedgerTransaction(
                                    header.id,
                                    header.source,
                                    LedgerTransactionType.valueOf(header.type),
                                    header.time,
                                    entries.map { entry ->
                                        LedgerPosting(
                                            requireNotNull(entry.account),
                                            AssetId(requireNotNull(entry.asset)),
                                            LedgerPostingSide.valueOf(requireNotNull(entry.side)),
                                            Amount(requireNotNull(entry.amount)),
                                        )
                                    },
                                )
                            }
                        }
                    val parameters = mapOf("assets" to scope.assets.map { it.value })
                    stage = "잔고 조회"
                    val balances =
                        jdbc
                            .query(
                                "select user_id, asset_id, available, hold from balance_projection where asset_id in (:assets) order by user_id, asset_id",
                                parameters,
                            ) { row, _ ->
                                record(problems, "잔고/${row.getString("user_id")}/${row.getString("asset_id")}", owners(row)) {
                                    Balance(
                                        UserId(row.getString("user_id")),
                                        AssetId(row.getString("asset_id")),
                                        Amount(row.getLong("available")),
                                        Amount(row.getLong("hold")),
                                    )
                                }
                            }.filterNotNull()
                    stage = "예약 조회"
                    val reservations =
                        jdbc
                            .query(
                                "select market_id, order_id, user_id, asset_id, status, remaining_amount from order_reservations where asset_id in (:assets) order by market_id, order_id",
                                parameters,
                            ) { row, _ ->
                                record(problems, "예약/${row.getString("market_id")}/${row.getString("order_id")}", owners(row)) {
                                    ReservationHold(
                                        row.getString("market_id"),
                                        row.getString("order_id"),
                                        UserId(row.getString("user_id")),
                                        AssetId(row.getString("asset_id")),
                                        row.getString("status"),
                                        row.getLong("remaining_amount"),
                                    )
                                }
                            }.filterNotNull()
                    stage = "트랜잭션 완료"
                    LedgerReconciliationSnapshot(transactions, balances, reservations, problems)
                },
            )
        } catch (failure: DataAccessException) {
            throw LedgerReconciliationUnavailableException(
                ReconciliationFailure.DB_READ_FAILED,
                "$stage 실패: ${failure.mostSpecificCause.message}",
                failure,
            )
        } catch (failure: TransactionException) {
            throw LedgerReconciliationUnavailableException(
                ReconciliationFailure.DB_READ_FAILED,
                "$stage 트랜잭션 실패: ${failure.message}",
                failure,
            )
        }
    }

    private fun <T> record(
        problems: MutableList<ReconciliationDifference>,
        source: String,
        owners: List<Pair<UserId, AssetId>> = emptyList(),
        convert: () -> T,
    ): T? =
        try {
            convert()
        } catch (failure: IllegalArgumentException) {
            if (owners.isEmpty()) {
                problems += ReconciliationDifference(ReconciliationItem.INVALID_RECORD, source = source, reason = failure.message)
            } else {
                for ((user, asset) in owners) {
                    problems +=
                        ReconciliationDifference(ReconciliationItem.INVALID_RECORD, user, asset, source = source, reason = failure.message)
                }
            }
            null
        }

    private fun owners(row: ResultSet): List<Pair<UserId, AssetId>> {
        val user = row.getString("user_id").takeIf { it.isNotBlank() } ?: return emptyList()
        val asset = row.getString("asset_id").takeIf { it.isNotBlank() } ?: return emptyList()
        return listOf(UserId(user) to AssetId(asset))
    }

    private fun rawLedger(row: ResultSet) =
        RawLedger(
            row.getString("ledger_transaction_id"),
            row.getString("source_event_id"),
            row.getString("transaction_type"),
            row.getTimestamp("occurred_at").toInstant(),
            row.getObject("posting_sequence")?.let { (it as Number).toInt() },
            row.getString("account_id"),
            row.getString("asset_id"),
            row.getString("side"),
            row.getObject("amount")?.let { (it as Number).toLong() },
        )

    private data class RawLedger(
        val id: String,
        val source: String,
        val type: String,
        val time: Instant,
        val sequence: Int?,
        val account: String?,
        val asset: String?,
        val side: String?,
        val amount: Long?,
    )

    companion object {
        private const val LEDGER_SQL = """
            select t.ledger_transaction_id, t.source_event_id, t.transaction_type, t.occurred_at,
                   p.posting_sequence, p.account_id, p.asset_id, p.side, p.amount
            from ledger_transactions t left join ledger_postings p on p.ledger_transaction_id=t.ledger_transaction_id
            order by t.ledger_transaction_id, p.posting_sequence
        """
    }
}

```

</details>

<details markdown="1"><summary>순수 합계와 세 비교 — 실제 코드 펼치기</summary>

소스 근거 · [전체 파일](/Users/0chord/.codex/worktrees/issue74-ledger-reconciliation/exchange-core/domain-ledger/src/main/kotlin/com/exchange/core/ledger/LedgerReconciliation.kt:1) · 내용 해시 `8531c4ccb506` · 1~171행

```kotlin
package com.exchange.core.ledger

import com.exchange.core.common.AssetId
import com.exchange.core.common.UserId
import java.math.BigInteger

/** 전달받은 기록만으로 사용자·자산별 세 금액을 대조한다. 저장소나 외부 호출은 없다. */
class LedgerReconciliation {
    fun compare(
        scope: LedgerReconciliationScope,
        snapshot: LedgerReconciliationSnapshot,
    ): LedgerReconciliationReport {
        val reservations = snapshot.reservations.filter { it.assetId in scope.assets }
        if (reservations.any { it.status == "ACTIVE" && it.marketId != scope.marketId.value }) {
            return LedgerReconciliationReport(
                scope,
                ReconciliationStatus.UNAVAILABLE,
                failure = ReconciliationFailure.OTHER_MARKET_HOLD,
                detail = "같은 자산에 다른 마켓의 활성 예약이 있어 hold 소유 범위를 검증할 수 없습니다",
            )
        }
        val problems = snapshot.problems.toMutableList()
        val totals = mutableMapOf<AccountKey, MutableMap<ReconciliationItem, BigInteger>>()
        for (transaction in snapshot.transactions) {
            for (posting in transaction.postings) {
                val key =
                    parseUserAccount(posting.accountId, posting.assetId)?.let { (user, item) ->
                        AccountKey(user, posting.assetId) to
                            item
                    }
                if (key == null) {
                    if (posting.accountId !in
                        setOf(
                            "SYSTEM:${posting.assetId.value}:DEVELOPMENT_FUNDING",
                            "SYSTEM:${posting.assetId.value}:FEE_REVENUE",
                        )
                    ) {
                        problems +=
                            ReconciliationDifference(
                                ReconciliationItem.INVALID_RECORD,
                                assetId = posting.assetId,
                                source = transaction.ledgerTransactionId,
                                reason = "계정과 분개 자산을 해석할 수 없습니다: ${posting.accountId}",
                            )
                    }
                    continue
                }
                if (key.first.assetId !in scope.assets) continue
                val amount = BigInteger.valueOf(posting.amount.value)
                val signed = if (posting.side == LedgerPostingSide.CREDIT) amount else -amount
                val account = totals.getOrPut(key.first) { mutableMapOf() }
                account[key.second] = account.getOrDefault(key.second, BigInteger.ZERO) + signed
            }
        }
        val balances = snapshot.balances.filter { it.assetId in scope.assets }
        if (balances.distinctBy { AccountKey(it.userId, it.assetId) }.size != balances.size) {
            problems += ReconciliationDifference(ReconciliationItem.INVALID_RECORD, reason = "같은 사용자·자산 잔고가 중복되었습니다")
        }
        val balancesByKey = balances.associateBy { AccountKey(it.userId, it.assetId) }
        for (reservation in reservations) {
            if (reservation.status !in setOf("ACTIVE", "SETTLED", "RELEASED") ||
                reservation.remainingAmount < 0 ||
                (reservation.status != "ACTIVE" && reservation.remainingAmount != 0L)
            ) {
                problems +=
                    ReconciliationDifference(
                        ReconciliationItem.INVALID_RECORD,
                        reservation.userId,
                        reservation.assetId,
                        source = "${reservation.marketId}/${reservation.orderId}",
                        reason = "예약 상태와 남은 금액이 유효하지 않습니다",
                    )
            }
        }
        val keys =
            (
                totals.keys + balancesByKey.keys + reservations.map { AccountKey(it.userId, it.assetId) } +
                    problems.mapNotNull { problem ->
                        val user = problem.userId
                        val asset = problem.assetId
                        if (user != null && asset != null && asset in scope.assets) AccountKey(user, asset) else null
                    }
            ).distinct()
                .sortedWith(compareBy({ it.userId.value }, { it.assetId.value }))
        val differences = problems.toMutableList()
        val accounts =
            keys.map { key ->
                val balance = balancesByKey[key]
                // 손상된 기록이 있으면 정상 기록만의 부분 합계를 원장 잔고로 제시하지 않는다.
                val ledgerAvailable = if (problems.isEmpty()) totals[key]?.get(ReconciliationItem.AVAILABLE) ?: BigInteger.ZERO else null
                val ledgerHold = if (problems.isEmpty()) totals[key]?.get(ReconciliationItem.HOLD) ?: BigInteger.ZERO else null
                val held =
                    reservations
                        .filter { it.userId == key.userId && it.assetId == key.assetId && it.status == "ACTIVE" }
                        .fold(BigInteger.ZERO) { sum, reservation -> sum + BigInteger.valueOf(reservation.remainingAmount) }
                val account =
                    ReconciliationAccount(
                        key.userId,
                        key.assetId,
                        ledgerAvailable,
                        ledgerHold,
                        held,
                        balance?.let { BigInteger.valueOf(it.available.value) },
                        balance?.let { BigInteger.valueOf(it.hold.value) },
                    )
                if (balance == null) {
                    differences +=
                        ReconciliationDifference(
                            ReconciliationItem.MISSING_BALANCE,
                            key.userId,
                            key.assetId,
                            reason = "원장 또는 예약 근거의 DB 잔고 행이 없습니다",
                        )
                } else {
                    difference(account, ReconciliationItem.AVAILABLE, ledgerAvailable, account.available)?.let(differences::add)
                    difference(account, ReconciliationItem.HOLD, ledgerHold, account.hold)?.let(differences::add)
                    difference(account, ReconciliationItem.RESERVATION_HOLD, held, account.hold)?.let(differences::add)
                }
                account
            }
        val status =
            when {
                differences.isNotEmpty() -> ReconciliationStatus.MISMATCHED
                keys.isEmpty() -> ReconciliationStatus.EMPTY
                else -> ReconciliationStatus.MATCHED
            }
        return LedgerReconciliationReport(
            scope,
            status,
            accounts,
            differences.sortedWith(
                compareBy({ it.userId?.value ?: "" }, { it.assetId?.value ?: "" }, { it.item.name }, { it.source ?: "" }),
            ),
        )
    }

    private fun difference(
        account: ReconciliationAccount,
        item: ReconciliationItem,
        expected: BigInteger?,
        actual: BigInteger?,
    ): ReconciliationDifference? =
        if (expected != null && actual != null && expected != actual) {
            ReconciliationDifference(item, account.userId, account.assetId, expected, actual)
        } else {
            null
        }

    companion object {
        /** 손상 거래에서도 식별 가능한 사용자는 같은 해석 규칙으로 검사 대상에 보존한다. */
        fun parseUserAccount(
            accountId: String,
            assetId: AssetId,
        ): Pair<UserId, ReconciliationItem>? {
            if (!accountId.startsWith("USER:")) return null
            for (item in listOf(ReconciliationItem.AVAILABLE, ReconciliationItem.HOLD)) {
                val suffix = ":${assetId.value}:${item.name}"
                if (accountId.endsWith(suffix)) {
                    val user = accountId.removePrefix("USER:").removeSuffix(suffix)
                    if (user.isNotBlank()) return UserId(user) to item
                }
            }
            return null
        }
    }

    private data class AccountKey(
        val userId: UserId,
        val assetId: AssetId,
    )
}

```

</details>

<details markdown="1"><summary>네 결과와 차이 표현 — 실제 코드 펼치기</summary>

소스 근거 · [전체 파일](/Users/0chord/.codex/worktrees/issue74-ledger-reconciliation/exchange-core/domain-ledger/src/main/kotlin/com/exchange/core/ledger/LedgerReconciliationReport.kt:1) · 내용 해시 `104b747921b2` · 1~44행

```kotlin
package com.exchange.core.ledger

import com.exchange.core.common.AssetId
import com.exchange.core.common.UserId
import java.math.BigInteger

enum class ReconciliationStatus { MATCHED, MISMATCHED, UNAVAILABLE, EMPTY }

enum class ReconciliationItem { AVAILABLE, HOLD, RESERVATION_HOLD, MISSING_BALANCE, INVALID_RECORD }

enum class ReconciliationFailure { DB_READ_FAILED, OUTER_TRANSACTION, OTHER_MARKET_HOLD }

/** 금액 차이는 실제 DB 값에서 비교 기준을 뺀 값이다. 누락/손상은 0으로 꾸미지 않는다. */
data class ReconciliationDifference(
    val item: ReconciliationItem,
    val userId: UserId? = null,
    val assetId: AssetId? = null,
    val expected: BigInteger? = null,
    val actual: BigInteger? = null,
    val source: String? = null,
    val reason: String? = null,
) {
    val delta: BigInteger? get() = if (expected != null && actual != null) actual - expected else null
}

data class ReconciliationAccount(
    val userId: UserId,
    val assetId: AssetId,
    val ledgerAvailable: BigInteger?,
    val ledgerHold: BigInteger?,
    val reservationHold: BigInteger,
    val available: BigInteger?,
    val hold: BigInteger?,
)

/** MATCHED는 읽은 자금 기록의 일치이며, 주문 복구 완료나 거래 재개 허가는 아니다. */
data class LedgerReconciliationReport(
    val scope: LedgerReconciliationScope,
    val status: ReconciliationStatus,
    val accounts: List<ReconciliationAccount> = emptyList(),
    val differences: List<ReconciliationDifference> = emptyList(),
    val failure: ReconciliationFailure? = null,
    val detail: String? = null,
)

```

</details>

<details markdown="1"><summary>범위·입력 자료 보관 — 실제 코드 펼치기</summary>

소스 근거 · [전체 파일](/Users/0chord/.codex/worktrees/issue74-ledger-reconciliation/exchange-core/domain-ledger/src/main/kotlin/com/exchange/core/ledger/LedgerReconciliationSnapshot.kt:1) · 내용 해시 `1a8be1a201ad` · 1~45행

```kotlin
package com.exchange.core.ledger

import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.UserId
import java.util.Collections

/** 한 마켓의 두 자산을 검사한다. 잔고 자체는 마켓별로 나뉘지 않는다. */
data class LedgerReconciliationScope(
    val marketId: MarketId,
    val baseAssetId: AssetId,
    val quoteAssetId: AssetId,
) {
    init {
        require(baseAssetId != quoteAssetId) { "서로 다른 두 자산이 필요합니다" }
        require(listOf(marketId.value, baseAssetId.value, quoteAssetId.value).none { '*' in it }) {
            "검사 범위에 와일드카드를 사용할 수 없습니다"
        }
    }

    val assets: Set<AssetId> get() = setOf(baseAssetId, quoteAssetId)
}

/** 주문 모듈 타입을 의존하지 않고, 예약 소유자·상태·남은 예약액만 전달한다. */
data class ReservationHold(
    val marketId: String,
    val orderId: String,
    val userId: UserId,
    val assetId: AssetId,
    val status: String,
    val remainingAmount: Long,
)

/** 같은 DB 시점에서 완전히 읽은 자료. 읽을 수 있지만 손상된 기록은 문제 목록에 보존한다. */
class LedgerReconciliationSnapshot(
    transactions: List<LedgerTransaction>,
    balances: List<Balance>,
    reservations: List<ReservationHold>,
    problems: List<ReconciliationDifference> = emptyList(),
) {
    val transactions: List<LedgerTransaction> = Collections.unmodifiableList(transactions.toList())
    val balances: List<Balance> = Collections.unmodifiableList(balances.toList())
    val reservations: List<ReservationHold> = Collections.unmodifiableList(reservations.toList())
    val problems: List<ReconciliationDifference> = Collections.unmodifiableList(problems.toList())
}

```

</details>

<details markdown="1"><summary>기술을 노출하지 않는 읽기 포트 — 실제 코드 펼치기</summary>

소스 근거 · [전체 파일](/Users/0chord/.codex/worktrees/issue74-ledger-reconciliation/exchange-core/domain-ledger/src/main/kotlin/com/exchange/core/ledger/LedgerReconciliationStore.kt:1) · 내용 해시 `e837c6267400` · 1~12행

```kotlin
package com.exchange.core.ledger

/** 같은 DB 시점의 기록을 읽는다. 중간 실패 시 부분 결과를 반환하지 않는다. */
interface LedgerReconciliationStore {
    fun read(scope: LedgerReconciliationScope): LedgerReconciliationSnapshot
}

class LedgerReconciliationUnavailableException(
    val failure: ReconciliationFailure,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

```

</details>

<details markdown="1"><summary>기존 설정 안의 Bean 연결 — 실제 코드 펼치기</summary>

소스 근거 · [전체 파일](/Users/0chord/.codex/worktrees/issue74-ledger-reconciliation/exchange-core/app-api/src/main/kotlin/com/exchange/core/api/config/LedgerPersistenceConfig.kt:1) · 내용 해시 `7c2541590004` · 1~172행

```kotlin
package com.exchange.core.api.config

import com.exchange.core.api.ledger.application.PrepareDevelopmentBalanceUseCase
import com.exchange.core.api.ledger.application.ReconcileLedgerUseCase
import com.exchange.core.api.ledger.infrastructure.persistence.PostgresBalanceStore
import com.exchange.core.api.ledger.infrastructure.persistence.PostgresDevelopmentBalanceStore
import com.exchange.core.api.ledger.infrastructure.persistence.PostgresLedgerReconciliationStore
import com.exchange.core.api.ledger.infrastructure.persistence.PostgresLedgerTransactionStore
import com.exchange.core.api.order.application.OrderFundingService
import com.exchange.core.api.order.application.OrderReservationReleaseService
import com.exchange.core.api.order.application.TradeSettlementService
import com.exchange.core.api.order.infrastructure.persistence.PostgresOrderReservationStore
import com.exchange.core.fee.TradingFeeCalculator
import com.exchange.core.fee.TradingFeeReserveCalculator
import com.exchange.core.ledger.BalanceStore
import com.exchange.core.ledger.DevelopmentBalanceStore
import com.exchange.core.ledger.LedgerReconciliationStore
import com.exchange.core.ledger.LedgerTransactionStore
import com.exchange.core.order.BuyOrderFundingQuoteCalculator
import com.exchange.core.order.OrderFillSettlementCalculator
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.order.OrderReservationStore
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager

/**
 * PostgreSQL 기반 잔고, 주문 예약과 원장 저장 기능을 조립하는 Spring 구성.
 *
 * `exchange.ledger.persistence.enabled=true`일 때만 활성화된다. [BalanceStore],
 * [OrderReservationStore]와 [LedgerTransactionStore]가 같은 DataSource와 Spring 트랜잭션을
 * 사용한다. 주문 예약 생성과 hold 변경, 예약 해제와 hold 반환을 각각 원자적으로 처리하며,
 * 체결 정산에서는 양쪽 예약·잔고 변경과 수수료를 포함한 원장 기록을 함께 커밋하거나 롤백한다.
 * 주문 예약 생성은 RESERVE 원장까지 함께 기록한다. 취소 해제도 RELEASE 원장과 함께 기록한다.
 */
@Configuration
@ConditionalOnProperty(
    name = ["exchange.ledger.persistence.enabled"],
    havingValue = "true",
)
class LedgerPersistenceConfig {
    /**
     * `balance_projection`을 조건부 UPDATE로 변경하는 잔고 저장소를 등록한다.
     *
     * @param jdbcTemplate Spring이 구성한 PostgreSQL named-parameter template
     * @return [BalanceStore] 포트의 PostgreSQL 구현체
     */
    @Bean
    fun balanceStore(jdbcTemplate: NamedParameterJdbcTemplate): BalanceStore = PostgresBalanceStore(jdbcTemplate)

    /**
     * `order_reservations` 테이블을 사용하는 주문별 예약 저장소를 등록한다.
     *
     * @param jdbcTemplate Spring이 구성한 PostgreSQL named-parameter template
     * @return [OrderReservationStore] 포트의 PostgreSQL 구현체
     */
    @Bean
    fun orderReservationStore(jdbcTemplate: NamedParameterJdbcTemplate): OrderReservationStore = PostgresOrderReservationStore(jdbcTemplate)

    /**
     * 주문 접수 전에 필요 자금을 계산하고 잔고 hold·예약·RESERVE 원장을 함께 만드는 서비스를
     * 등록한다.
     *
     * @param balanceStore 사용자·자산별 잔고 변경 포트
     * @param orderReservationStore 주문별 예약 저장 포트
     * @return 주문 자금 예약 application service
     */
    @Bean
    fun orderFundingService(
        balanceStore: BalanceStore,
        orderReservationStore: OrderReservationStore,
        ledgerTransactionStore: LedgerTransactionStore,
    ): OrderFundingService =
        OrderFundingService(
            calculator =
                OrderReservationCalculator(
                    buyOrderFundingQuoteCalculator =
                        BuyOrderFundingQuoteCalculator(
                            feeReserveCalculator = TradingFeeReserveCalculator(),
                        ),
                ),
            balanceStore = balanceStore,
            reservationStore = orderReservationStore,
            ledgerTransactionStore = ledgerTransactionStore,
        )

    /**
     * 취소된 주문의 남은 예약·잔고와 반환 원장을 함께 저장하는 서비스를 등록한다.
     *
     * @param balanceStore 사용자·자산별 잔고 변경 포트
     * @param orderReservationStore 주문별 예약 저장 포트
     * @param ledgerTransactionStore 반환 원장의 조회·추가 포트
     * @return 주문 예약 해제 application service
     */
    @Bean
    fun orderReservationReleaseService(
        balanceStore: BalanceStore,
        orderReservationStore: OrderReservationStore,
        ledgerTransactionStore: LedgerTransactionStore,
    ): OrderReservationReleaseService =
        OrderReservationReleaseService(
            balanceStore = balanceStore,
            reservationStore = orderReservationStore,
            ledgerTransactionStore = ledgerTransactionStore,
        )

    /**
     * 한 체결의 양쪽 예약·잔고 변경과 원장 기록을 함께 실행하는 서비스를 등록한다.
     *
     * @param balanceStore 사용자·자산별 체결 잔고 변경 포트
     * @param orderReservationStore maker와 taker의 주문별 예약 저장 포트
     * @param ledgerTransactionStore 양쪽 자산 이동과 거래소 수수료 수익을 기록하는 원장 저장 포트
     * @return 양쪽 정산과 원장 저장을 하나의 트랜잭션으로 실행하는 application service
     */
    @Bean
    fun tradeSettlementService(
        balanceStore: BalanceStore,
        orderReservationStore: OrderReservationStore,
        ledgerTransactionStore: LedgerTransactionStore,
    ): TradeSettlementService =
        TradeSettlementService(
            calculator =
                OrderFillSettlementCalculator(
                    tradingFeeCalculator = TradingFeeCalculator(),
                    tradingFeeReserveCalculator = TradingFeeReserveCalculator(),
                ),
            balanceStore = balanceStore,
            reservationStore = orderReservationStore,
            ledgerTransactionStore = ledgerTransactionStore,
        )

    /**
     * 원장 거래와 항목을 함께 추가하는 PostgreSQL 저장소를 등록한다.
     *
     * @param jdbcTemplate 기존 잔고 및 주문 예약 저장소와 같은 DataSource를 사용하는 template
     * @return [LedgerTransactionStore] 포트의 PostgreSQL 구현체
     */
    @Bean
    fun ledgerTransactionStore(jdbcTemplate: NamedParameterJdbcTemplate): LedgerTransactionStore =
        PostgresLedgerTransactionStore(jdbcTemplate)

    /** 명시적인 개발용 개시 호출에만 원장·잔고의 원자 저장을 제공한다. */
    @Bean
    fun developmentBalanceStore(
        jdbcTemplate: NamedParameterJdbcTemplate,
        transactionManager: PlatformTransactionManager,
        ledgerTransactionStore: LedgerTransactionStore,
        balanceStore: BalanceStore,
    ): DevelopmentBalanceStore =
        PostgresDevelopmentBalanceStore(
            jdbcTemplate,
            transactionManager,
            ledgerTransactionStore,
            balanceStore,
        )

    @Bean
    fun prepareDevelopmentBalanceUseCase(developmentBalanceStore: DevelopmentBalanceStore) =
        PrepareDevelopmentBalanceUseCase(developmentBalanceStore)

    /** 거래 쓰기 경로와 분리된 읽기 전용 대조 저장소를 조립한다. */
    @Bean
    fun ledgerReconciliationStore(
        jdbcTemplate: NamedParameterJdbcTemplate,
        transactionManager: PlatformTransactionManager,
    ): LedgerReconciliationStore = PostgresLedgerReconciliationStore(jdbcTemplate, transactionManager)

    @Bean
    fun reconcileLedgerUseCase(ledgerReconciliationStore: LedgerReconciliationStore) = ReconcileLedgerUseCase(ledgerReconciliationStore)
}

```

</details>

<details markdown="1"><summary>기존 포트 검사 대상 추가 — 실제 코드 펼치기</summary>

소스 근거 · [전체 파일](/Users/0chord/.codex/worktrees/issue74-ledger-reconciliation/exchange-core/architecture-tests/src/test/kotlin/com/exchange/architecture/support/ProductionScope.kt:1) · 내용 해시 `0926cfe19d6f` · 1~89행

```kotlin
package com.exchange.architecture.support

import java.io.File
import java.nio.file.Path

/**
 * 운영 코드의 수집·역할 기준. 이 목록은 실제 Gradle 모듈을 발견하는 근거로 사용하지 않는다.
 * 새 모듈의 등록 누락을 잡으려면 발견 목록을 별도로 받아야 한다.
 */
object ProductionScope {
    // 어노테이션을 없애거나 저장 기술을 바꿔도 영속 모델의 포트 노출을 계속 검사한다.
    val persistenceTypes = setOf("com.exchange.core.api.matching.infrastructure.persistence.MatchingEventEntity")

    val requiredTypes =
        mapOf(
            "domain-common" to setOf("com.exchange.core.common.Amount"),
            "domain-fee" to setOf("com.exchange.core.fee.TradingFeeCalculator"),
            "domain-order" to setOf("com.exchange.core.order.OrderReservation"),
            "domain-ledger" to setOf("com.exchange.core.ledger.Balance"),
            "domain-matching" to setOf("com.exchange.core.matching.MatchingEngine"),
            "app-api" to setOf("com.exchange.core.ExchangeCoreApplication"),
        )
    val roles =
        RoleRegistration(
            domainModules = requiredTypes.keys - "app-api",
            externalPorts =
                setOf(
                    "com.exchange.core.order.OrderReservationStore",
                    "com.exchange.core.ledger.BalanceStore",
                    "com.exchange.core.ledger.DevelopmentBalanceStore",
                    "com.exchange.core.ledger.LedgerTransactionStore",
                    "com.exchange.core.ledger.LedgerReconciliationStore",
                    "com.exchange.core.api.matching.application.port.MatchingEventStore",
                    "com.exchange.core.api.matching.application.port.MatchingEventPublisher",
                ),
            executors =
                setOf(
                    "com.exchange.core.matching.MarketCommandProcessor",
                    "com.exchange.core.matching.InMemoryMarketCommandProcessor",
                    "com.exchange.core.matching.MarketWorker",
                    // 이 파일의 최상위 함수는 실행기의 failedFuture 보조 함수뿐이므로 실행기 역할로 둔다.
                    "com.exchange.core.matching.MarketCommandProcessorKt",
                ),
            reviewedPureInterfaces =
                setOf(
                    "com.exchange.core.matching.MatchingCommand",
                    "com.exchange.core.matching.MatchingEvent",
                ),
        )

    fun inventory() =
        GradleModuleInventory(
            discoveredModules = csv("architecture.discoveredJvmProjects"),
            productionModules = csv("architecture.registration.production"),
            nonProductionModules = csv("architecture.registration.nonProduction"),
        )

    fun nonProductionTargets() =
        IsolationInputs.targets(
            System.getProperty("architecture.sourceInventory"),
            System.getProperty("architecture.sourceOutputs"),
            inventory(),
        )

    fun outputs(): List<ModuleOutput> =
        System
            .getProperties()
            .stringPropertyNames()
            .filter { it.startsWith("architecture.outputs.") }
            .sorted()
            .map {
                ModuleOutput(it.removePrefix("architecture.outputs."), paths(it))
            }

    fun expectations() =
        ScopeExpectations(
            requiredTypesByModule = requiredTypes,
            requiredRoles = mapOf("ports" to roles.externalPorts, "executors" to roles.executors),
            forbiddenRoots = paths("architecture.forbiddenOutputs").toSet(),
            projectPackagePrefixes = setOf("com.exchange.core.", "com.exchange.architecture."),
            forbiddenTypePrefixes = setOf("com.exchange.architecture."),
        )

    private fun csv(property: String) = required(property).split(',').filter { it.isNotBlank() }.toSet()

    private fun paths(property: String) = required(property).split(File.pathSeparator).filter { it.isNotBlank() }.map(Path::of)

    private fun required(property: String) = requireNotNull(System.getProperty(property)) { "Gradle must provide $property" }
}

```

</details>

테스트 파일 2개는 위 모든 사례에 연결했다. ProductionScope는 개발 검증 도구의 기존 포트 검사 등록이며, 새 읽기 인터페이스 하나를 기존 ARCH-01·06 대상에 연결했다. 명세는 계약 근거이고 이 문서는 자연어 흐름·테스트 안내다. 빌드·SQL migration·기존 금전 이동 코드 변경은 없다. 설정 파일에서는 대조 Bean 두 개와 오래된 RELEASE 주석만 갱신했다.

## 사람이 판단할 범위

금액 대조가 일치해도 주문장·취소 이벤트·복구 상태가 정상이라는 뜻은 아니다. 돈이 함께 잘못 기록돼 합계만 맞는 오류나 모든 근거가 같이 삭제된 상황을 전부 잡을 수 없다. 결과를 어떻게 읽고 거래 재개에 연결할지는 #58에서 결정한다. 이번 학습 문답은 조회 실패 설명 선호를 반영했으며, 사용자가 모든 코드를 이해하거나 승인했다고 기록하지 않았다.
