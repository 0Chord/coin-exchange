# #71 초기 자금 준비 — 구현 흐름 읽기

**상태: #71의 로컬 구현·실행 검증·독립 재리뷰 완료. 사람의 이해·검토 및 PR 게시·병합은 별도 상태다.**

브랜치: `feat/development-balances/71` · 비교 기준: `d30df546b38b84133070fc5c7254dbca8a160340`

## 이번에 바뀐 것

전에는 이미 존재하는 잔고만 갱신할 수 있었다. 이제 명시적으로 `seed-1 / buyer / KRW / 1000`을 호출하면 **원장과 잔고를 함께 준비**한다. 서버 시작이나 Bean 생성만으로 돈을 지급하지 않는다. HTTP·CLI 연결은 #49에서 한다.

먼저 읽을 전체 흐름은 아래 여섯 가지다.

| 흐름 | 입력 → 판단 → 결과 |
| --- | --- |
| 첫 지급 | 양수 입력 → 미사용 계정 확인 → 출처 차변·사용자 대변과 잔고 1000을 함께 커밋 |
| 같은 요청 | seed-1 재호출 → 원본 종류·항목·입력·잔고 존재 대조 → 원본 결과만, 재지급 없음 |
| 다른 입력·재개시 | 같은 ID의 다른 값 또는 사용한 계정에 새 ID → 충돌 → 새 기록·지급 없음 |
| 동시 요청 | 같은 계정은 행 잠금, 다른 계정의 같은 ID는 DB 고유 제약 → 한 번만 지급 |
| 실패·응답 유실 | 쓰기 중 실패는 함께 롤백. 본문이 끝난 뒤 커밋 결과가 불명이면 확인 필요 → 같은 ID로 다시 확인 |
| 수령 0 잔고·Bean | 없는 행만 0 생성, 기존 행은 유지. 설정 true에서 조립만 하며 SQL·지급 없음 |

**원본 결과와 현재 잔고는 다르다.** 1000을 받은 뒤 거래해서 700이 되어도 재호출 결과는 처음 준비한 1000이다. 현재 잔고 700은 별도 조회(#63) 책임이다.

## 첫 1000을 준비할 때

UseCase가 준비 ID·사용자·자산·금액을 받아 순수 `OpeningBalance`를 만든다. 빈 ID·길이 초과·계정 구분자 충돌·0 금액은 DB를 호출하기 전에 거절한다.

저장 포트는 한 건의 트랜잭션을 시작한다. 원본이 없으면 없는 잔고 행만 0으로 만들고, 그 행을 잠근다. 잠금을 기다리는 동안 다른 요청이 완료했을 수 있으므로 원본을 다시 읽는다. 현재 available/hold가 0이고 대상 계정의 원장·예약 이력이 없을 때만 지급한다.

순수 도메인이 만든 **SYSTEM:KRW:DEVELOPMENT_FUNDING DEBIT 1000 / USER:buyer:KRW:AVAILABLE CREDIT 1000**을 기존 원장 writer로 저장하고 기존 BalanceStore.credit으로 지급한다. 둘이 함께 커밋된 뒤에만 최초 결과를 반환한다.

DB에 처음 생긴 0 행도 이 트랜잭션 안에 있다. 원장 또는 지급이 실패하면 그 새 행까지 롤백한다. 원래 있던 0 행·다른 계정은 유지한다.

## 같은 요청과 충돌을 구분할 때

- **같은 ID·같은 입력:** 저장된 OPENING과 정확한 두 항목을 대조하고 잔고가 존재하면 이미 완료를 반환한다. 원본 원장 ID·시각을 유지한다.
- **같은 ID·다른 사용자·자산·금액:** 요청 충돌이다. 실패한 새 대상 잔고를 만들지 않는다.
- **다른 ID·이미 사용한 계정:** 개시 충돌이다. 거래 후 잔고가 다시 0이어도 이력이 남아 있으므로 새 지급을 하지 않는다.
- **완료 항목 손상·잔고 소실:** 저장 상태 불일치다. 자동 복원하거나 이미 완료로 숨기지 않는다.
- **읽기 SQL 실패:** 원본 확인을 못 했으므로 성공을 반환하지 않는다.

현재 잔고와 원장 전체가 맞는지 다시 계산하는 감사 기능은 #74다. 이 구현은 새 지급이 가능한지와 원본 완료 근거를 확인한다.

## 동시에 호출할 때

| 두 요청 | 관측해야 하는 결과 |
| --- | --- |
| 같은 ID·같은 입력 | 최초 1개, 이미 완료 1개. 거래 1·항목 2·지급 1회 |
| 다른 ID·같은 계정 | 한 건만 최초, 다른 건은 개시 충돌 |
| 같은 ID·같은 계정·다른 금액 | 한 금액만 보존, 다른 건은 요청 충돌 |
| 같은 ID·다른 계정 | 원본 ID 고유 제약으로 한 건만 커밋. 실패 계정의 새 0 행까지 롤백 |
| 다른 ID·다른 계정 | 독립적으로 두 건 성공. 전체 일괄 원자성은 보장하지 않음 |

고유 제약 실패를 잡고 **이미 실패한 트랜잭션 안에서 계속 읽지 않는다.** 해당 시도가 롤백된 뒤 새 트랜잭션에서 커밋된 원본을 확인한다. 원본 ID 고유 제약만 이 경로를 사용하며 원장 기본키 중복까지 성공으로 처리하지 않는다.

## 쓰기 실패와 응답 실패를 구분할 때

1. **원장 첫 항목 뒤 DB 제약 실패:** 헤더·첫 항목·새 잔고 행 모두 롤백된다. 같은 ID로 정상 writer를 다시 호출하면 한 번 준비된다.
2. **원장 저장 후 잔고 쓰기 실패:** 지급도 원장도 롤백된다. 원래 0 행의 값·시각과 다른 계정은 보존된다.
3. **커밋은 됐지만 응답 확인이 실패:** 확인 필요 오류를 전달한다. 미지급이라고 단정하거나 보상 지급하지 않는다. 같은 ID 재호출로 저장된 원본을 확인한다.
4. **호출자가 성공 응답만 잃음:** 같은 ID로 호출해 이미 완료를 받는다.

커밋 오류는 예외 이름만으로 구분하지 않는다. 저장 본문을 끝낸 뒤 발생한 오류는 JPA가 데이터 접근 예외로 바꾸더라도 확인 필요로 반환한다. 본문에서 일어난 SQL 실패는 원래 실패로 전달한다. 고유 제약 뒤 원본을 읽는 새 트랜잭션에도 같은 경계를 적용한다.

테스트의 커밋 후 오류는 실제 DB 커밋 뒤 테스트용 트랜잭션 매니저가 오류를 던지는 방식이다. Spring의 원래 트랜잭션 예외와 HibernateJpaDialect가 실제로 변환한 데이터 접근 예외를 각각 확인한다. 실제 네트워크 단절이나 서버 강제 종료를 실험한 것은 아니다. 그 실험은 #67~70이다.

## 0 잔고와 조립만 할 때

`ensureReceivingBalance`는 수령할 자산의 없는 행만 0으로 만든다. 이미 available/hold가 있으면 그대로 두며 갱신 시각도 바꾸지 않는다. 원장이나 준비 ID는 만들지 않는다. 이력 없는 0 행에는 나중에 정상 개시가 가능하다.

`exchange.ledger.persistence.enabled=true`면 config가 기존 잔고·원장 Bean, 같은 트랜잭션 매니저를 새 Store와 UseCase에 연결한다. false/미설정에서는 둘을 만들지 않는다. @Service/@Component를 추가하지 않았고 Bean 생성 중 SQL은 실행하지 않는다.

## 구조 검사에서 추가한 범위

ledger/application **정확한 폴더 하나**를 허용했다. UseCase는 주문·ledger application에서만 허용하고, Service·Coordinator 규칙은 유지한다. ledger UseCase도 실제 ARCH-04 대상으로 선택하므로 Postgres 구현을 직접 참조하면 실패한다. 새 DevelopmentBalanceStore 포트도 ARCH-01·06 대상에 포함한다. 이름·폴더 통과가 돈의 원자성을 증명하지는 않는다.

## 실제 검증 결과

- 첫 도메인·DB 테스트 작성 직후에는 없는 API로 컴파일 실패했다. 행동 수준 Red로 기록하지 않는다.
- 개시 분개 4개 테스트와 첫 지급·반복·0 행·Bean 검증은 실행 통과했다.
- 새 ledger 대상 선택·명명·포트 연결 테스트는 연결 전 실제 assertion에서 실패했다. 기존 검사 입력을 연결한 뒤 전체 구조 검사 371개가 통과했다.
- DB 롤백은 실제 Spring writer·동일 트랜잭션·호출 후 새 DB 읽기로 확인한다. 자동 테스트 롤백에 의존하지 않는다.
- 동시 요청은 두 스레드·두 DB 트랜잭션과 latch로 겹치게 한다. 같은 ID·다른 계정은 두 원장 INSERT 직전에 만나게 하여 고유 제약 경로를 강제한다.
- 독립 리뷰에서 커밋 오류 분류와 새 허용 폴더에 대한 기존 예제 설정 누락을 지적했다. 커밋 오류는 실패 테스트로 재현한 뒤 본문 종료 여부에 따라 분류하도록 수정했다. 폴더 예제는 ledger 위치와 두 허용 package 기대값을 함께 반영했다. 최종 독립 재리뷰에서 두 지적이 해소됐고 추가로 확인된 범위 안 결함이 없음을 확인했다. 사람의 검토 완료를 대신 표시하지 않는다.

### 최종 실행과 독립 리뷰

- 최종 `build`와 `:architecture-tests:verifyArchitectureReport` 성공. XML 합계 **676개**, 실패·오류·skip **0개**. 변경 없는 검사는 유효한 이전 실행 결과를 재사용했다.
- 제품 app-api 117개, 구조 371개, fee 37개, ledger 25개, matching 74개, order 52개. 필수 운영 P01~P09 실행·성공 확인.
- 독립 리뷰는 작성 대화를 상속하지 않은 에이전트가 별도 고정 checkout에서 진행했다. 최종 snapshot `abf252f640e19a3b1023b6fae2ef7ac18a65f8b3`의 제품·테스트·검사 변경 21개 파일이 현재 작업 파일과 일치한다.
- 독립 재리뷰 직접 실행: 구조 371개 + 필수 보고서, app-api 68개, 기존 독립 커밋 반례. 코어 25개는 첫 리뷰의 실행 근거 재사용. A01~A15 충족을 확인했고 추가 미해결 결함이 없다.
- [검증 기록과 파일 해시](development-balance-verification.json). 게시 전 로컬 실행 근거이며, 원격 CI 상태는 PR에서 별도로 확인한다. 실행 보고서를 열거나 테스트가 통과한 것을 사람의 이해 완료로 기록하지 않는다.

저장소를 받은 뒤 저장소 루트에서 실행한다.

```sh
./gradlew build :architecture-tests:verifyArchitectureReport --continue
```

## 명세 사례와 실제 검사 연결

| 명세 | 확인하는 근거 |
| --- | --- |
| A01~A03 첫 지급·반복·사용 후 반복 | 실제 DB의 원장 1건·분개 2건, 원본 ID·시각 유지, 현재 700/300·700/0 보존 |
| A04~A06 입력·재개시·이력 충돌 | 실패 대상의 새 행·추가 분개 없음. 0 잔고만 보고 새 지급하지 않음 |
| A07~A08 수령 0 행·이후 지급 | 없는 행만 생성, 기존 updated_at 유지, 이력 없는 0 행은 개시 가능 |
| A09 다섯 동시 조합 | 두 DB 트랜잭션의 호출 결과와 최종 행·금액 함께 확인 |
| A10~A12 저장 실패·불일치 | 부분 저장 실제 롤백, 원래 행 유지, 손상·조회 실패를 성공으로 숨기지 않음 |
| A13~A14 입력 상한·응답 유실 | 잘못된 입력 무쓰기, Long.MAX_VALUE 정확 저장, 같은 ID로 원본 확인 |
| A15 조립·이행·구조 연결 | 조건부 Bean과 생성 시 SQL 없음, V6→V7의 기존 데이터·제약 보존, 실제 구조 검사 대상 포함 |
| 커밋 결과 불명 | 실제 커밋 뒤 두 종류의 예외를 주입하고 확인 필요·재지급 없음 확인 |

검사 이름과 실제 코드 연결은 아래의 DB·도메인·Bean 테스트를 펼쳐 확인한다. 실제 네트워크 단절·프로세스 강제 종료, HTTP·CLI 호출, 전체 원장과 잔고 재계산은 이 실행의 보장에 포함하지 않는다.

## 변경 파일과 읽을 근거

아래 링크는 이 PR의 실제 파일이다. 전체 코드의 중복 복사 대신 역할·흐름과 원문 위치를 연결한다. 파일 해시는 검증 기록에 있다.

| 성격과 역할 | 실제 코드·검사 |
| --- | --- |
| 제품 실행 · 입력·분개·계정 상태 판단 | [OpeningBalance.kt](../domain-ledger/src/main/kotlin/com/exchange/core/ledger/OpeningBalance.kt#L1) |
| 제품 실행 · 저장 계약 | [DevelopmentBalanceStore.kt](../domain-ledger/src/main/kotlin/com/exchange/core/ledger/DevelopmentBalanceStore.kt#L1) |
| 제품 실행 · 개시 종류 | [LedgerTransactionType.kt](../domain-ledger/src/main/kotlin/com/exchange/core/ledger/LedgerTransactionType.kt#L1) |
| 제품 실행 · 명시적 진입점 | [PrepareDevelopmentBalanceUseCase.kt](../app-api/src/main/kotlin/com/exchange/core/api/ledger/application/PrepareDevelopmentBalanceUseCase.kt#L1) |
| 제품 실행 · 잠금·원본 재조회·커밋·오류 | [PostgresDevelopmentBalanceStore.kt](../app-api/src/main/kotlin/com/exchange/core/api/ledger/infrastructure/persistence/PostgresDevelopmentBalanceStore.kt#L1) |
| 제품 실행 · 기존 config의 새 Bean 연결 | [LedgerPersistenceConfig.kt](../app-api/src/main/kotlin/com/exchange/core/api/config/LedgerPersistenceConfig.kt#L143) |
| DB 이행 · 기존 데이터 보존 | [V7__allow_opening_ledger_transactions.sql](../app-api/src/main/resources/db/migration/V7__allow_opening_ledger_transactions.sql#L1) |
| 검증 도구 · 정확한 ledger 폴더 | [ProjectLayoutPolicy.kt](../architecture-tests/src/test/kotlin/com/exchange/architecture/policy/ProjectLayoutPolicy.kt#L1) |
| 검증 도구 · UseCase 허용 위치 | [NamingRules.kt](../architecture-tests/src/test/kotlin/com/exchange/architecture/rules/NamingRules.kt#L79) |
| 검증 도구 · 실제 UseCase 대상 선택 | [ProductionBoundaryInputs.kt](../architecture-tests/src/test/kotlin/com/exchange/architecture/support/ProductionBoundaryInputs.kt#L1) |
| 검증 도구 · 실제 포트 대상 | [ProductionScope.kt](../architecture-tests/src/test/kotlin/com/exchange/architecture/support/ProductionScope.kt#L1) |
| 테스트 · 도메인의 독립 기대값 | [OpeningBalanceTest.kt](../domain-ledger/src/test/kotlin/com/exchange/core/ledger/OpeningBalanceTest.kt#L1) |
| 테스트 · 입력 거절과 저장 계약 전달 | [PrepareDevelopmentBalanceUseCaseTest.kt](../app-api/src/test/kotlin/com/exchange/core/api/ledger/application/PrepareDevelopmentBalanceUseCaseTest.kt#L1) |
| 테스트 · 실제 DB 정상·충돌·실패·동시성·이행 | [PostgresDevelopmentBalanceStoreTest.kt](../app-api/src/test/kotlin/com/exchange/core/api/ledger/infrastructure/persistence/PostgresDevelopmentBalanceStoreTest.kt#L1) |
| 테스트 · 설정 true/false와 생성 시 SQL 없음 | [LedgerPersistenceConfigurationTest.kt](../app-api/src/test/kotlin/com/exchange/core/api/config/LedgerPersistenceConfigurationTest.kt#L1) |
| 테스트 · 새 실제 경계와 반례 | [ProductionBoundaryInputsTest.kt](../architecture-tests/src/test/kotlin/com/exchange/architecture/ProductionBoundaryInputsTest.kt#L50) |
| 테스트 · 코어 UseCase 금지 유지 | [NamingPlacementRuleTest.kt](../architecture-tests/src/test/kotlin/com/exchange/architecture/NamingPlacementRuleTest.kt#L23) |
| 테스트 · 위치 검사 예제의 명시적 정책 | [NamingPlacementIntegrationTest.kt](../architecture-tests/src/test/kotlin/com/exchange/architecture/NamingPlacementIntegrationTest.kt#L1) |
| 검증 예제 · 허용 폴더 목록 확인 | [NamingPlacementScopeTest.kt](../architecture-tests/src/test/kotlin/com/exchange/architecture/NamingPlacementScopeTest.kt#L1) |
| 검증 예제 · Gradle 검사 예제 설정 | [NamingPlacementGradleScenario.kt](../architecture-tests/src/test/kotlin/com/exchange/architecture/NamingPlacementGradleScenario.kt#L1) |
| 검증 예제 · 정책 변경 시 자동 재검사 | [NamingPlacementGradleAutomaticTest.kt](../architecture-tests/src/test/kotlin/com/exchange/architecture/NamingPlacementGradleAutomaticTest.kt#L1) |

## 첫 리뷰 판단

처음 1,000을 준비하고 기존 예약 동작으로 available/hold가 700/300이 됐을 때 같은 준비 ID로 다시 호출한다. 기대는 **최초 준비액·원장 ID·시각 유지, 추가 지급 없음, 현재 700/300 보존**이다. 현재 잔고를 초기값으로 복구하면 사용한 자금을 되돌려 잘못 지급할 수 있다.

- [원본 준비 결과](../domain-ledger/src/main/kotlin/com/exchange/core/ledger/OpeningBalance.kt#L71)는 현재 잔고를 담지 않는다.
- [반복 요청 판단](../app-api/src/main/kotlin/com/exchange/core/api/ledger/infrastructure/persistence/PostgresDevelopmentBalanceStore.kt#L80)은 원본 입력과 잔고 존재를 대조한다.
- [사용 후 재호출 검사](../app-api/src/test/kotlin/com/exchange/core/api/ledger/infrastructure/persistence/PostgresDevelopmentBalanceStoreTest.kt#L86)는 실제 예약·hold 소비 뒤 상태와 원본 결과를 확인한다.

이 판단 하나가 동시 요청·부분 저장 실패·커밋 결과 불명·0 잔고·이행 검토를 대신하지 않는다. 나머지 분기는 위 전체 흐름과 명세 A01~A15로 이어서 확인한다.
