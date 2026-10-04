# #71 초기 자금을 원장과 잔고에 함께 준비하기

**단계: #71 로컬 구현·검증 완료. 아래는 합의한 계약이며 실제 코드·실행 결과는 [구현 흐름과 검증 기록](development-balance-review.md)에서 구분해 확인한다. PR·병합은 별도 단계다.**

[이슈 #71](https://github.com/0Chord/coin-exchange/issues/71) · 상위 [#42](https://github.com/0Chord/coin-exchange/issues/42) · 공유 계약 [#43](https://github.com/0Chord/coin-exchange/issues/43)

확인 기준: `feature/phase-2/integration`의 **`d30df546b38b84133070fc5c7254dbca8a160340`**, 2026-10-03. 기본 작업 폴더의 미채택 검사 초안과 구분해 해당 커밋의 코드를 읽었다.

## 이번 작은 PR에서 만들 결과

**개발용 계정에 1,000을 준비하면, 잔고 1,000과 그 출처를 설명하는 원장 한 건이 함께 남는다. 같은 요청을 반복해도 추가 지급하지 않는다.**

읽을 흐름은 다음 세 가지다.

1. **최초 준비:** `seed-1 / buyer / KRW / 1,000` → 기존 기록·계정 확인 → 출처 차변 1,000 + 사용자 AVAILABLE 대변 1,000 → 잔고 `1,000 / 0`과 함께 커밋.
2. **같은 요청:** 이미 준비된 `seed-1`을 같은 입력으로 재호출 → 원본 기록을 대조 → 추가 분개·지급 없이 이미 완료된 결과.
3. **충돌·실패:** 같은 ID의 다른 입력, 이미 사용한 계정의 새 개시, 저장 실패 → 이유를 구분 → 새 원장·잔고 변화가 함께 남지 않음. 커밋 결과가 불명확한 장애는 별도로 표시.

한 요청은 **한 사용자·한 자산**이다. 여러 계정·자산의 일괄 준비는 #49가 이 단위를 순서대로 호출하며, 전체 일괄 롤백은 이번 보장이 아니다.

## 먼저 확인할 계약

| 항목 | 이번 기준 |
| --- | --- |
| 돈의 의미 | 개발 시연용 개시 자금. 실제 입금 완료나 외부 은행 자금을 뜻하지 않음 |
| 초기 상태 | 잔고 행이 없거나, available=0·hold=0이고 관련 원장·예약 이력이 없는 계정만 최초 지급 |
| 원장 종류 | **구현 계약:** `OPENING`. 출처 `SYSTEM:{asset}:DEVELOPMENT_FUNDING`, 사용자 `USER:{user}:{asset}:AVAILABLE` |
| 준비 식별자 | **구현 계약:** 원본 ID `OPENING:{preparationId}`. preparationId 하나는 한 사용자·자산·금액을 식별하며 다른 입력에 재사용 불가 |
| 재호출 | 같은 ID·같은 입력의 완전한 개시 기록은 재지급 없이 반환. 이후 거래로 바뀐 잔고를 초기값으로 덮어쓰지 않음 |
| 수령 자산 0 잔고 | 별도 `ensureReceivingBalance(user, asset)` 동작으로 없는 행만 `0 / 0` 생성. 기존 행은 그대로 두고 원장은 만들지 않음 |
| 외부 연결 | 명시적 UseCase 호출만 준비. HTTP·CLI·시작 시 실행은 #49에서 연결 |
| 사람의 판단 | 분개 방향·같은 요청의 의미·실패 뒤 남는 사실을 확인. 구조 검사 통과만으로 금전 원자성을 판정하지 않음 |

### 확정한 반환 계약

최초와 재호출 모두 준비 ID, 사용자·자산·준비 금액, 원본 원장 ID·처리 시각, 최초/이미 완료 구분만 반환한다. 현재 available/hold는 #63에서 따로 조회한다. 원본과 현재 잔고를 함께 반환하는 대안에는 조회 시점과 개시 당시 금액을 구분하는 추가 계약이 필요하다. A02·A03·A14에서 원본 보존과 현재 잔고를 덮어쓰지 않는지 확인한다.

<details markdown="1">
<summary>현재 코드에서 재사용할 것과 추가할 것</summary>

| 확인한 실제 코드 | 현재 보장 / 이번 차이 |
| --- | --- |
| [LedgerTransaction.kt](https://github.com/0Chord/coin-exchange/blob/d30df546b38b84133070fc5c7254dbca8a160340/domain-ledger/src/main/kotlin/com/exchange/core/ledger/LedgerTransaction.kt) | 항목 목록을 보호하고 자산별 차변=대변을 검증. 그대로 재사용 |
| [LedgerPosting.kt](https://github.com/0Chord/coin-exchange/blob/d30df546b38b84133070fc5c7254dbca8a160340/domain-ledger/src/main/kotlin/com/exchange/core/ledger/LedgerPosting.kt) | 양수 분개만 허용. 0 잔고 행 준비에 빈 분개를 만들지 않음 |
| [LedgerTransactionType.kt](https://github.com/0Chord/coin-exchange/blob/d30df546b38b84133070fc5c7254dbca8a160340/domain-ledger/src/main/kotlin/com/exchange/core/ledger/LedgerTransactionType.kt) | 현재 RESERVE/RELEASE/SETTLEMENT/REVERSAL만 존재. OPENING 추가 필요 |
| [PostgresLedgerTransactionStore.kt](https://github.com/0Chord/coin-exchange/blob/d30df546b38b84133070fc5c7254dbca8a160340/app-api/src/main/kotlin/com/exchange/core/api/ledger/infrastructure/persistence/PostgresLedgerTransactionStore.kt) | 헤더·항목 저장, source_event_id UNIQUE, 기존 Spring 트랜잭션 참여. 원장 writer를 재사용 |
| [PostgresBalanceStore.kt](https://github.com/0Chord/coin-exchange/blob/d30df546b38b84133070fc5c7254dbca8a160340/app-api/src/main/kotlin/com/exchange/core/api/ledger/infrastructure/persistence/PostgresBalanceStore.kt) | 기존 행의 reserve/release/consumeHold/credit. 초기 행 생성·개시 이력 확인은 없음. 기존 UPDATE … RETURNING 유지 |
| [V2 잔고](https://github.com/0Chord/coin-exchange/blob/d30df546b38b84133070fc5c7254dbca8a160340/app-api/src/main/resources/db/migration/V2__create_balance_projection.sql), [V5 원장](https://github.com/0Chord/coin-exchange/blob/d30df546b38b84133070fc5c7254dbca8a160340/app-api/src/main/resources/db/migration/V5__create_ledger_tables.sql) | 잔고 PK=(사용자, 자산), 원본 ID UNIQUE, 분개 양수·자산별 조회 인덱스. 기존 migration은 수정하지 않고 다음 V7에서 OPENING 종류 허용 |
| [LedgerPersistenceConfig.kt](https://github.com/0Chord/coin-exchange/blob/d30df546b38b84133070fc5c7254dbca8a160340/app-api/src/main/kotlin/com/exchange/core/api/config/LedgerPersistenceConfig.kt) | ledger.persistence.enabled=true에서 @Bean 조립. 생성만으로 SQL을 실행하지 않는 방식 유지 |

**이번에 추가할 좁은 책임:** 개시 입력과 계정 상태의 순수 판단, 초기 행 준비·잠금·원본 기록 대조를 수행하는 저장 계약, 명시적 UseCase. 별도 준비 이력 테이블이나 범용 충전·이벤트 소싱 플랫폼은 만들지 않는다. 개시 원장의 헤더와 두 항목을 완료 근거로 사용한다.

</details>

<details markdown="1">
<summary>입력 → 판단 → 저장 → 결과를 자세히 읽기</summary>

### 최초 양수 지급

1. UseCase가 준비 ID·사용자·자산·최소 단위 금액을 받는다. 개시 금액은 `1..Long.MAX_VALUE`. 음수·0 개시 금액, 공백 ID, DB 길이 초과는 저장 전에 거절한다. 0 행 준비는 별도 동작이다.
2. 원본 ID의 개시 기록을 먼저 확인한다. 있으면 아래 재호출 판단으로 이동한다. 없으면 대상 잔고 행을 `0 / 0`으로 없는 경우에만 준비하고 사용자·자산 행을 잠근다.
3. 잠금 뒤 원본 기록을 **다시** 읽는다. 기다리는 동안 다른 요청이 먼저 완료했을 수 있다. 대상 계정의 available/hold, AVAILABLE·HOLD 원장 항목, 해당 사용자·자산의 모든 상태의 예약 이력도 확인한다.
4. 원본 기록이 없고 잔고가 `0 / 0`, 관련 이력이 없을 때만 개시 계획을 적용한다. 잔고 행 부재여도 원장·예약 이력이 남아 있으면 자동으로 복원하거나 새로 지급하지 않는다.
5. 같은 자산·같은 금액의 출처 DEBIT와 사용자 AVAILABLE CREDIT 두 항목을 기존 LedgerTransaction으로 검증·추가하고, 잠근 0 잔고에 금액을 반영한다. 기존 Balance.credit·저장소 credit의 계산/UPDATE를 재사용할 수 있다.
6. 원장 헤더·두 항목·잔고 행 준비와 지급을 **한 DB 트랜잭션**으로 커밋한다. 커밋 뒤 준비 완료 결과를 반환한다. SQL 하나라는 뜻이 아니다.

사용자·자산 문자열은 기존 `USER:{user}:{asset}:AVAILABLE` 규칙을 따른다. 이번 새 준비 입력에서는 구분자인 `:`를 user/asset에 허용하지 않고 각 DB 컬럼 최대 64자와 생성 계정명 길이를 검사한다. 공통 UserId/AssetId와 기존 주문 API의 규칙은 변경하지 않는다. preparationId는 원본 ID를 생성한 결과가 varchar(128)에 맞아야 하며 임의 trim·대소문자 변환을 하지 않는다.

### 같은 요청의 재호출

동일 원본의 `OPENING` 종류, 원장 ID, 두 항목의 계정·자산·방향·금액을 읽어 요청과 대조한다. 준비 ID만 일치한다고 성공으로 바꾸지 않는다.

- 같은 입력 + 완전한 기록 + 해당 잔고 행 존재 → 이미 완료. 원본 거래 ID·시각 유지, 추가 기록·지급 없음.
- 원본 기록은 같은데 사용자·자산·금액이 다름 → 요청 충돌. 기존 기록·두 대상 잔고 유지.
- 원본 개시 기록의 종류·항목이 손상됐거나 잔고 행이 사라짐 → 저장 상태 불일치. 자동 보정·추가 지급 없음.
- 준비 후 잔고가 거래로 `700 / 300` 또는 `700 / 0`이 됐음 → 유효한 같은 요청은 여전히 이미 완료. 현재 금액을 1,000으로 되돌리지 않음. 전체 원장·잔고 대조는 #74의 책임.

최초 처리 시 생성하는 원장 UUID·처리 시각은 재호출 일치 비교의 입력이 아니다. 같은 입력의 재호출이 새로운 시각·UUID 때문에 충돌해서는 안 된다.

### 수령 자산의 0 잔고 행

`ensureReceivingBalance(user, asset)`는 체결 후 자산을 받을 DB 행만 준비한다. 없는 행은 0/0으로 생성하고, 있는 행은 값과 updated_at을 건드리지 않는다. 원장 거래·항목·개시 준비 ID를 만들지 않는다. 기존 비영 잔고도 초기화하지 않는다. 이 동작은 지급 완료 근거가 아니며, 이력 없는 0 행은 이후 정상 개시 대상이 될 수 있다.

### 신규 지급의 충돌

새 준비 ID라도 대상 계정에 개시·거래 원장, 예약 이력, 비영 available/hold가 있으면 개시 거절이다. 돈을 전부 사용해 다시 0이 된 계정도 이력이 남아 있으므로 재개시하지 않는다. 판단 범위는 **대상 사용자·자산**이며 다른 사용자·자산의 기록 때문에 거절하지 않는다.

원장 없는 비영 잔고를 이행하거나 직접 SQL의 과거 모든 변경을 추정하지 않는다. 원장·예약을 지우거나 직접 잔고를 바꾸는 코드까지 이 API가 방지한다고 약속하지 않는다.

</details>

<details markdown="1">
<summary>동시 요청·롤백·응답 유실 경계</summary>

**구현 계약:** PostgreSQL Read Committed에서 대상 잔고 PK와 `INSERT … ON CONFLICT (user_id, asset_id) DO NOTHING`을 사용해 없는 0 행을 준비하고, 그다음 `SELECT … FOR UPDATE`로 잠근 뒤 기록을 재확인한다. 충돌 시 기존 금액을 덮어쓰는 upsert는 사용하지 않는다.

| 동시 입력 | 기대 결과 |
| --- | --- |
| 같은 ID·같은 사용자/자산/금액 | 한 요청이 최초 완료, 다른 요청은 이미 완료. 거래 1건·항목 2건·지급 1회 |
| 다른 ID·같은 사용자/자산 | 먼저 완료한 준비만 지급. 뒤 요청은 개시 충돌. 합계가 2배가 되지 않음 |
| 같은 ID·다른 금액 | 먼저 완료한 입력만 보존. 뒤 요청은 요청 충돌 |
| 같은 ID·다른 사용자/자산 | 대상 행 잠금이 달라도 source_event_id UNIQUE로 하나만 커밋. 실패 쪽의 새 0 행·분개·지급도 롤백 |
| 다른 ID·다른 사용자/자산 | 각각의 준비가 독립적으로 완료. 여러 계정 전체가 한 트랜잭션이라는 보장은 없음 |

기존 원장 writer의 고유 제약 예외를 실패한 트랜잭션 안에서 잡고 계속 읽지 않는다. 알려진 source_event_id 중복이면 **그 시도가 완전히 롤백된 뒤**, 새 읽기 경계에서 커밋된 원본을 확인하고 같은 입력/충돌을 판정한다. PK 중복·다른 제약·연결 오류까지 이미 완료로 처리하지 않는다. 롤백 뒤 읽기 실패·완전한 근거 부재도 성공이 아니다.

이 처리를 위해 PostgresDevelopmentBalanceStore가 `TransactionTemplate` 등 기존 Spring 트랜잭션 도구로 한 준비의 경계를 소유하는 안을 추천한다. UseCase는 외부 트랜잭션 없이 호출하고, 준비 호출을 외부 일괄 트랜잭션에 중첩하는 사용은 이 포트에서 거절한다. 그래야 결과가 이미 커밋됐다는 의미와 실패 후 조회 경계가 분명하다. 같은 DataSource/트랜잭션 매니저와 기존 원장·잔고 Bean을 사용한다. 일반 reserve/release/settle의 기존 트랜잭션 정책은 바꾸지 않는다.

- **확인된 저장 실패·롤백:** 임시 0 행, 지급, 원장 헤더·일부 항목 모두 새로 남지 않는다. 원래 있던 0/비영 행과 다른 계정은 유지된다. 같은 요청으로 다시 시도할 수 있다.
- **응답만 유실:** 이미 커밋된 원본으로 같은 요청을 다시 확인해 이미 완료를 반환한다. 재지급하지 않는다.
- **커밋 결과 불명:** 미지급·전체 롤백이라고 단정하지 않고 결과 확인 필요로 오류를 전달한다. 자동 보상 지급·ID 변경 재시도 없음. 같은 ID의 후속 호출에서 완전한 근거를 다시 확인한다.
- **시퀀스 번호:** 롤백 뒤 bigserial 번호에 빈칸이 생길 수 있다. 연속 posting_id나 다음 번호까지 원상복구되는 것을 완료 조건으로 삼지 않는다.

이 설계의 근거는 [PostgreSQL 16 Read Committed와 충돌 INSERT](https://www.postgresql.org/docs/16/transaction-iso.html#XACT-READ-COMMITTED), [행 잠금](https://www.postgresql.org/docs/16/explicit-locking.html#LOCKING-ROWS), [Spring TransactionTemplate](https://docs.spring.io/spring-framework/reference/data-access/transaction/programmatic.html#tx-prog-template)이다. **새 코드에서 이 흐름이 동작하는지는 아직 실험하지 않았으며 아래 실제 DB 테스트로 확인해야 한다.**

</details>

<details markdown="1">
<summary>책임·이름·파일 위치와 Bean 연결</summary>

다음 새 이름·파일은 **구현 배치 제안**이다. 모든 함수 본문을 미리 고정하지 않는다.

| 위치·후보 | 책임 |
| --- | --- |
| domain-ledger / `com.exchange.core.ledger` / `OpeningBalance.kt` 등 | 초기 입력, 같은 요청 비교, 최초 지급 가능한 상태, 양수 개시 분개 계획을 순수 객체/함수로 판단. 새 객체 반환, DB 호출 없음 |
| 같은 코어 / `DevelopmentBalanceStore` | 준비 한 건의 원자 저장·재호출·0 행 준비 계약. Spring/JDBC/SQL 예외가 공개 타입에 나타나지 않음 |
| app-api / `com.exchange.core.api.ledger.application` / `PrepareDevelopmentBalanceUseCase` | 명시적 진입점. 입력 준비·순수 규칙·저장 계약을 연결. SQL과 구체 PostgreSQL 구현 직접 참조 없음 |
| app-api / `com.exchange.core.api.ledger.infrastructure.persistence` / `PostgresDevelopmentBalanceStore` | 기존 테이블 조회·행 생성·잠금·트랜잭션 종료·중복 재조회. 업무 판단은 도메인 규칙에 위임하고 기존 원장 writer/잔고 변경 재사용 |
| `LedgerPersistenceConfig` | 기존 영속화 조건에서 포트 구현·UseCase를 @Bean으로 조립. @Service/@Component 자동 등록 없음. Bean 생성으로 지급/SQL을 실행하지 않음 |
| `V7__allow_opening_ledger_transactions.sql` 후보 | 기존 타입 CHECK에 OPENING 추가. 기존 4종류·데이터·키·항목 제약 유지. 적용된 V1~V6 변경·테이블 삭제 없음 |

파일 폴더도 package와 일치시킨다. 현재 허용 목록에는 ledger/application이 없으므로 ProjectLayoutPolicy에 **그 정확한 경로와 이유 하나**를 추가한다. 새 하위 폴더 전체를 와일드카드로 허용하지 않는다.

현재 [NamingRules.useCases](https://github.com/0Chord/coin-exchange/blob/d30df546b38b84133070fc5c7254dbca8a160340/architecture-tests/src/test/kotlin/com/exchange/architecture/rules/NamingRules.kt#L83)는 order-application만 허용한다. 이번에는 **UseCase의 위치에 order-application과 ledger-application을 허용**하고 기존 Service/Coordinator 위치는 바꾸지 않는다. 파일 형식과 위치의 통과가 업무 책임의 적절성까지 보장하지 않는다는 기존 의미 리뷰 경계도 유지한다.

[ProductionBoundaryInputs](https://github.com/0Chord/coin-exchange/blob/d30df546b38b84133070fc5c7254dbca8a160340/architecture-tests/src/test/kotlin/com/exchange/architecture/support/ProductionBoundaryInputs.kt)는 현재 주문·매칭 application만 고른다. 여기에 ledger/application과 그 구체 UseCase 선택을 연결한다. 새 Store 포트는 현재 ProductionScope의 포트 목록에 포함한다. **허용된 ledger UseCase 통과, 순수 코어의 UseCase 금지, ledger UseCase의 구체 Postgres 구현 의존 실패, 새 포트 공개 계약 평가**를 관련 구조 회귀로 확인한다. 기존 검사에 필요한 연결만 추가하고, 클래스마다 새 수동 이름·위치 등록 체계나 범용 규칙 엔진을 만들지 않는다.

PrepareDevelopmentBalanceUseCase는 ledger 도메인 모듈에 두지 않는다. Amount·Balance·LedgerTransaction과 새 순수 판단은 코어, 실행 조율은 app-api라는 기존 합의를 따른다.

</details>

<details markdown="1">
<summary>수용 사례 — 독립 기대값과 금지할 변화</summary>

각 사례는 새로운 테스트의 **기대값**이며 실행 결과가 아니다. DB 단위 금액은 설명용 정수다.

| ID | 초기 상태·입력 | 기대 결과와 근거 |
| --- | --- | --- |
| A01 최초 준비 | buyer/KRW 없음, seed-1로 1,000 | available=1,000·hold=0. OPENING 거래 1건, 출처 DEBIT 1,000 + 사용자 AVAILABLE CREDIT 1,000. 각 자산 균형 |
| A02 순차 반복 | A01 뒤 같은 입력 2회 | 같은 원장 ID·시각의 이미 완료 결과. 거래/항목 1/2, 잔고 1,000/0 유지 |
| A03 사용 후 반복 | A01 뒤 기존 업무로 잔고 700/300 또는 700/0, 같은 seed-1 | 원본 준비 금액 1,000·원장 ID·시각을 담은 이미 완료 결과. 현재 available/hold는 반환하지 않음. DB 잔고 그대로, 추가 1,000 없음 |
| A04 같은 ID 다른 입력 | seed-1 완료 뒤 금액 2,000 또는 다른 사용자/자산 | 요청 충돌. 기존 분개·잔고 유지, 새 대상 행도 남기지 않음 |
| A05 새 ID 재개시 | 개시 후 새 seed-2, 또는 잔고가 다시 0이지만 원장·예약 이력 있음 | 개시 충돌. 추가 분개·지급 없음. 금액 0만으로 미사용이라 판단하지 않음 |
| A06 설명 없는 잔고 | 원장 없이 100/0 또는 0/100; 잔고는 없지만 원장/예약 이력 존재 | 개시 충돌/불일치 이유를 구분. 잔고 초기화·기록 삭제·자동 이행 없음 |
| A07 수령 0 행 | 행 없음에 ensure, 이어 같은 ensure | 0/0 행 하나, 원장 0건. 기존 거래 후 ensure도 값·updated_at 유지 |
| A08 0 행 뒤 개시 | 이력 없는 기존 0/0 행에 1,000 | 정상 개시 1건. 0 행 존재 자체로 거절하지 않음 |
| A09 동시 요청 | 위 동시성 표의 5개 조합 | DB 결과·각 호출 결과를 함께 검증. 각 계정은 한 번만 지급되며 source ID 충돌도 유효 입력 대조 |
| A10 원장 저장 실패 | 헤더/첫 항목 저장 뒤 오류 | 원장 헤더·모든 항목·새 0 행·잔고 지급 전체 롤백. 복구된 정상 writer로 같은 요청 재시도 시 한 번 성공 |
| A11 잔고 반영 실패 | 원장 추가 후 잔고 writer가 오류 | 원장도 0건, 기존 0 행/다른 계정 그대로. 실패를 잡고 부분 성공을 반환하지 않음 |
| A12 완료 기록 불일치 | 개시 항목 하나 누락·종류/방향 손상·잔고 행 소실 | 저장 상태 불일치. 재지급·자동 복원 없음. 읽기 실패도 이미 완료가 아님 |
| A13 입력·상한 | blank/길이 초과/구분자 충돌, 음수·0 개시; 양수 Long.MAX_VALUE | 잘못된 입력은 저장 전 거절. 허용 상한은 0 행의 개시로 정확히 저장, 덧셈 오버플로가 숨겨지지 않음 |
| A14 응답 유실 | A01 커밋 뒤 응답을 사용하지 않고 같은 ID 재호출 | A02와 같은 결과. 실제 프로세스를 죽였다는 증거로 사용하지 않음 |
| A15 조립·이행 | ledger 설정 true/false/미설정, V1~V6 데이터에 V7 적용 | true면 명시적 준비 Bean, false/미설정이면 없음. 생성 시 SQL 0회. 기존 4종 원장·분개와 잔고 유지 |

롤백 테스트는 실제 Spring Bean·동일 트랜잭션에 참여하는 기존 writer를 호출한다. 테스트 메서드의 자동 롤백을 끄고, 호출이 끝난 뒤 **새 연결/읽기에서** DB를 확인한다. 첫 항목 저장 후 예외를 던지는 테스트용 writer 등으로 실패 위치를 제한할 수 있으며, 항상 실패하는 mock만으로 DB 원자성을 입증하지 않는다. 동시 테스트는 두 별도 연결과 기존 CountDownLatch 방식으로 경합을 만들고 sleep으로 순서를 맞추지 않는다.

</details>

<details markdown="1">
<summary>한 PR 안의 작은 구현 순서와 완료 기준</summary>

**예상 브랜치:** `feat/development-balances/71`. 아직 만들지 않았다. 구현 시 최신 통합에서 실제 작업 폴더를 먼저 안내한다.

1. **단위 1 — 첫 1,000과 반복:** 순수 개시 분개·입력 비교 사례 → V7·저장 원자성·UseCase·Bean 연결 → A01/A02/A07/A08/A13/A15. 최초 준비가 만드는 DB 사실을 읽는다.
2. **단위 2 — 충돌과 실패:** A03~A06/A09~A12/A14 → 잠금·source ID 경합·롤백 뒤 조회 구현 → 보존할 잔고와 금지된 추가 지급을 확인한다.

두 단위는 같은 개시 계약의 정상/실패 경계이며 한 PR로 묶는다. 단위 1만 성공한 상태를 #71 완료로 표시하지 않는다. 구현에서는 테스트 작성 → 기대값 검토 → 구현 → 검증을 연결하고 필요한 독립 리뷰를 적용한다.

### 검증 수준과 예상 실행

- 코어: 기존 LedgerTransactionTest/BalanceTest 재사용 + 새 개시 규칙 테스트. 기대값은 1,000의 두 분개와 위 상태표에서 정한다.
- application: 진입점이 포트를 호출하며 SQL·구현을 알지 않는지, 잘못된 입력이 쓰기를 호출하지 않는지 확인.
- PostgreSQL: 기존 PostgresTestConfiguration의 **postgres:16-alpine**, Flyway, Testcontainers 재사용. 동시성·원장과 잔고 원자성·기존 데이터 이행 확인.
- Bean·구조: 기존 설정 테스트를 확장하고 ARCH-01/04/05/06 등 실제 대상에 새 경계를 포함. 구조 검사와 금전 DB 테스트의 보장을 구분.
- 기존 reserve/release/consumeHold/credit, LedgerTransaction 저장·정산 회귀를 관련 범위에서 유지하고 기존 PR CI로 전체 검사.

아래 `WORKTREE`는 **구현 때 확인할 실제 절대 경로로 교체할 자리**다. 기본 폴더에서 바로 실행하면 새 코드를 검증하는 것이 아니다.

```bash
cd /실제/구현/작업폴더/exchange-core
./gradlew :domain-ledger:test
./gradlew :app-api:test --tests '*DevelopmentBalance*' --tests '*PostgresLedgerTransactionStoreTest' --tests '*PostgresBalanceStoreTest' --tests '*LedgerPersistenceConfigurationTest'
./gradlew :architecture-tests:verifyArchitectureReport
```

새 테스트 파일·필터 이름은 구현에서 확정한다. 현재는 이 테스트를 추가하거나 실행하지 않았다.

### 이 PR의 완료

- 양수 개시 한 건의 원장·잔고 원자성, 같은 ID 반복, 새 ID 재개시 거절, 동시성 표, 실패 후 롤백·재시도를 실제 근거로 확인.
- 0 행은 분개 없이 없는 행만 생성하고 기존 행을 초기화하지 않음.
- V7 이행, 명시적 Bean, 새 폴더·포트의 기존 구조 검사 연결 확인.
- 원본 결과·현재 잔고·커밋 결과 불명을 구분해 설명하고 실행/미실행을 표시.
- 기존 HTTP·거래 규칙·정산 분개에 동작 변경 없음. 실제 새 기능의 코드·테스트·이 설명을 같은 PR에서 검토.

**제외:** 실제 입출금, 임의 충전 HTTP API, 초기화 CLI·시작 훅(#49), 여러 계정 전체 원자 준비, 기존 비영 잔고 자동 개시 이행, 원장/잔고 삭제·덮어쓰기, 예약/취소 원장(#72/73), 전체 대조(#74), 접수·주문장 복구(#50~59), 실제 서버 강제 종료(#67~70), MARKET/IOC, 새로운 검사 플랫폼.

**선행 근거:** #43의 공유 계약은 PR #75로 통합됐다. 이 문서는 #71의 구현 계약이며 이슈·보드 상태를 자동 변경하지 않는다.

**설계와 실행의 구분:** 이 문서는 통합 기준 d30df54에서 확정한 계약이다. 실제 구현과 A01~A15 실행 근거는 [구현 흐름](development-balance-review.md) 및 [검증 기록](development-balance-verification.json)에서 확인한다. 실제 네트워크 단절·프로세스 강제 종료 실험은 포함하지 않는다.

</details>
