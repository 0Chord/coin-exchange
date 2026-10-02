# #21 저장 포트·기술 구현의 이름과 배치 상세 명세

검증 기록: **합의한 세 단위의 로컬 구현·검증 완료 / 제품·DB 81개 + 구조 353개 통과**. 이 문서는 PR 게시 전 로컬 검증 시점의 기록이며, 게시 후 CI·리뷰·병합 상태는 GitHub PR에서 확인한다. [이슈 #21](https://github.com/0Chord/coin-exchange/issues/21)의 기존 범위와 우선순위를 유지한다.

기준은 `feature/phase-2/integration`의 `525a8cdc5861441fdfec50441e000755e36a1eea`이다. [#35](https://github.com/0Chord/coin-exchange/pull/35)가 병합된 뒤의 소스·설정·테스트를 읽었다. 설계 폴더는 `/Users/0chord/.codex/worktrees/issue21-storage-boundaries/exchange-core`, 구현 브랜치는 `refactor/storage-boundaries/21`이다. 과거 #20의 테스트 성공을 이번 이동의 실행 결과로 쓰지 않는다.

## 먼저 볼 결과

**저장 계약은 기술을 모르고, 구현은 외부 기술을 맡도록 위치를 정리한다. 기존 클래스 이름과 실제 거래 동작은 유지한다.** 새 저장 프레임워크나 클래스별 역할 등록표를 만들지 않는다.

| 작은 구현 단위 | 무엇이 달라지는가 | 그 단위의 확인 결과 |
| --- | --- | --- |
| 1. 포트와 발행 연결 | 매칭의 Store/Publisher 인터페이스와 두 발행 구현의 위치, config와 Coordinator의 import | 설정값에 맞는 Publisher Bean이 정확히 하나 선택되고, 발행 성공/실패 뒤 처리 순서가 유지됨 |
| 2. JDBC/JPA 저장 파일 | 주문·원장 JDBC와 매칭 JPA/Entity/Repository/직렬화 파일 위치 | 기존 SQL·JSON·잠금·트랜잭션과 실제 PostgreSQL 저장 결과가 유지됨 |
| 3. 검사와 임시 경로 종료 | P08에 기존 ARCH-05 전체 규칙 적용, 네 임시 폴더 제거 | 새 위치는 통과, 옛 위치·업무의 구현 직접 참조는 실패, 누락은 준비 오류 |

#21 PR 하나 안에서 위 세 단위를 구분해 검토한다. 단계마다 전체 설계를 다시 승인받지 않는다. 실제 SQL·외부 계약·실패 보장을 바꿔야 하는 발견이 생기면 해당 변경만 논의한다.

## 1. 이번에 유지하는 계약과 제외 범위

- 도메인 모듈은 Kotlin 코어다. `OrderReservationStore`, `BalanceStore`, `LedgerTransactionStore`의 도메인 선언·시그니처는 유지한다.
- 업무 객체는 포트를 사용한다. Controller는 UseCase를 거친다. 구체 구현 생성은 config의 명시적 `@Bean`에서 한다.
- `@Service`·업무 `@Component`·`@Repository` 자동 등록을 추가하지 않는다. 기존 Controller/Advice 및 Spring Data 인터페이스 생성은 유지한다.
- 성공한 조건부 `UPDATE … RETURNING`은 갱신된 잔고를 반환한다. 갱신 0건일 때 부재/부족 원인을 조회하는 기존 분기도 유지한다.
- 예약 생성·해제·체결별 정산, 원장 저장, 매칭 이벤트 목록 저장은 각자의 트랜잭션 경계를 유지한다. 요청 전체가 하나의 트랜잭션이라고 설명하지 않는다.
- API URL·JSON·금액·수수료 기대값, DB 테이블/컬럼/제약·마이그레이션, 이벤트 저장 순서, 실행기 중단·3초 대기 정책은 유지한다.
- 신규 기능, 포트 인터페이스/구현 쌍의 추가, JDBC의 Spring Data 전환, outbox/Kafka, 자동 보상·재처리·복구는 제외한다. 상태 접근·계약 빈틈의 새 보강은 #22~23, 주석 일괄 정리는 #24다.
- 폴더만 바뀌는 코드에 긴 주석을 추가하지 않는다. 이동으로 틀린 이름·링크나 보존할 계약을 설명하는 주석만 필요한 만큼 맞춘다.

## 2. 이름·폴더 이동표

아래 12개 운영 파일은 **단순 이름을 유지**하고 package와 실제 파일 경로를 함께 이동한다. 루트는 `app-api/src/main/kotlin/com/exchange/core/api/`이다. 새 하위 폴더를 임의로 더하지 않고 기존 `ProjectLayoutPolicy.target`의 정확한 허용 경로를 쓴다.

| 파일·역할 | 현재 폴더 | 목표 폴더 | 단위 |
| --- | --- | --- | --- |
| MatchingEventStore · 저장 포트 | matching/persistence | matching/application/port | 1 |
| MatchingEventPublisher · 발행 포트 | matching/publish | matching/application/port | 1 |
| PersistentMatchingEventPublisher · Store에 위임하는 영속화 구현 | matching/persistence | matching/infrastructure/persistence | 1 |
| NoOpMatchingEventPublisher · 저장·외부 발행 없이 정상 완료 | matching/publish | matching/infrastructure/publish | 1 |
| JpaMatchingEventStore · 목록 단위 JPA 저장 | matching/persistence | matching/infrastructure/persistence | 2 |
| MatchingEventRepository · Spring Data JPA 인터페이스 | matching/persistence | matching/infrastructure/persistence | 2 |
| MatchingEventEntity · DB 영속 모델 | matching/persistence | matching/infrastructure/persistence | 2 |
| MatchingEventPayload · 저장 JSON 변환 데이터/최상위 함수 | matching/persistence | matching/infrastructure/persistence | 2 |
| MatchingEventType · 저장 이벤트 enum | matching/persistence | matching/infrastructure/persistence | 2 |
| PostgresOrderReservationStore · 주문 예약 JDBC | order/persistence | order/infrastructure/persistence | 2 |
| PostgresBalanceStore · 잔고 조건부 갱신 JDBC | ledger/persistence | ledger/infrastructure/persistence | 2 |
| PostgresLedgerTransactionStore · 원장 JDBC | ledger/persistence | ledger/infrastructure/persistence | 2 |

포트와 구현의 차이는 이름만이 아니다. Store 포트는 `MatchingEvent` 같은 계약 값을 받는 인터페이스이고, Jpa Store는 그 값을 Entity로 바꿔 Repository를 호출하는 객체다. Repository는 Spring Data가 구현을 생성하는 인터페이스이며 도메인 저장 포트를 대신하지 않는다.

유지 위치: 도메인 저장 포트는 기존 `com.exchange.core.order`·`com.exchange.core.ledger`, 설정은 `com.exchange.core.api.config`, UseCase/업무 Service는 `order.application`, Coordinator는 `matching.application`이다. 도메인에 application/infrastructure 폴더를 추가하지 않는다.

`MatchingEventPayload`·`MatchingEventType`처럼 특정 이름 규칙의 선택 단서가 없는 타입도 원본 파일 경로·package 일치는 검사한다. 이름 의미는 리뷰한다. 모든 클래스를 Entity/Store 같은 접미사로 바꾸거나 직접 등록하지 않는다.

<details markdown="1">
<summary>현재 코드 근거와 호출·테스트 수정 대상</summary>

이동한 선언은 [매칭 Store](../app-api/src/main/kotlin/com/exchange/core/api/matching/application/port/MatchingEventStore.kt), [Publisher](../app-api/src/main/kotlin/com/exchange/core/api/matching/application/port/MatchingEventPublisher.kt), [Jpa Store](../app-api/src/main/kotlin/com/exchange/core/api/matching/infrastructure/persistence/JpaMatchingEventStore.kt), [JPA Entity](../app-api/src/main/kotlin/com/exchange/core/api/matching/infrastructure/persistence/MatchingEventEntity.kt), [Repository](../app-api/src/main/kotlin/com/exchange/core/api/matching/infrastructure/persistence/MatchingEventRepository.kt), [Postgres 잔고](../app-api/src/main/kotlin/com/exchange/core/api/ledger/infrastructure/persistence/PostgresBalanceStore.kt), [예약](../app-api/src/main/kotlin/com/exchange/core/api/order/infrastructure/persistence/PostgresOrderReservationStore.kt), [원장](../app-api/src/main/kotlin/com/exchange/core/api/ledger/infrastructure/persistence/PostgresLedgerTransactionStore.kt)에 있다.

- `MatchingCoordinator`와 `MatchingConfig`·`MatchingPersistenceConfig`·`LedgerPersistenceConfig`는 import/타입 연결을 함께 수정한다. Bean 메서드 이름·조건·종료 설정은 유지한다. `OrderApplicationConfig`의 업무 연결 구조는 유지한다.
- 저장 구현 테스트 3개, 매칭 persistence 테스트 4개는 대상의 새 폴더를 따라 이동한다. Coordinator 실패 테스트·HTTP 테스트 등 다른 호출 테스트는 필요한 import만 갱신한다.
- [ProductionScope](../architecture-tests/src/test/kotlin/com/exchange/architecture/support/ProductionScope.kt)의 5개 외부 포트 중 이동한 두 FQN과 `persistenceTypes`의 Entity FQN을 같은 단위에서 갱신한다. 기존 계약 목록의 경로 갱신이며 새 클래스별 등록표가 아니다.
- [ProductionBoundaryInputsTest](../architecture-tests/src/test/kotlin/com/exchange/architecture/ProductionBoundaryInputsTest.kt)의 옛 FQN·임시 폴더 사례도 최종 정책에 맞춘다. 최상위 함수·생성 타입의 수집 정책은 바꾸지 않는다.
- 현재 문서의 이동 파일 링크는 새 경로로 맞춘다. 과거 실행·리뷰 기록은 당시 커밋을 고정한 링크로 보존하며 새 실행 결과로 덮어쓰지 않는다.

</details>

## 3. 설정값 → 선택되는 Bean → 호출 순서 → 결과

### 매칭 이벤트 영속화가 켜진 경우

`exchange.matching.persistence.enabled=true` → `MatchingPersistenceConfig` 활성화 → `MatchingEventRepository`와 ObjectMapper로 `JpaMatchingEventStore` Bean 조립 → Store를 받는 `PersistentMatchingEventPublisher` Bean 선택 → `MatchingCoordinator`에 Publisher 포트로 주입한다. NoOp Bean은 만들어지지 않아야 한다.

명령은 같은 마켓 worker에서 **사전 예약 → 엔진 → Publisher.publish → Store.append → JSON/Entity 변환 → Repository.saveAll → 저장 호출 정상 반환 → 정산 또는 예약 해제 → Future 결과**로 이어진다. config가 직접 거래를 실행하지 않는다. 전달된 이벤트 순서·목록 경계와 원인 예외 전달을 바꾸지 않는다.

`JpaMatchingEventStore.append`의 기존 `@Transactional`과 실제 프록시 연결을 유지한다. 중복 순번 등으로 목록 뒤쪽 이벤트가 실패하면 목록 앞쪽 insert도 롤백돼야 한다. Entity를 옮겨 JPA 스캔이나 Repository 주입이 끊기면 수용 실패다. 기본 앱 패키지 `com.exchange.core` 아래로 이동하므로 새 스캔 설정은 우선 추가하지 않고 실제 context/DB 테스트로 확인한다.

### 꺼져 있거나 설정이 없는 경우

`false` 또는 설정 없음 → `MatchingConfig`의 `matchIfMissing=true` 조건 → `NoOpMatchingEventPublisher`가 Publisher 포트의 유일한 Bean → 이벤트를 저장·외부로 보내지 않고 정상 반환 → Coordinator는 기존 후속 callback과 결과 전달을 계속한다.

이는 **설정으로 선택한 미저장 경로**다. DB 오류를 삼키고 성공으로 바꾸는 fallback이 아니다. 다른 원장 저장 설정과 앱 자동 설정까지 꺼졌다는 뜻도 아니다. 이 경로의 새 조건 검사는 두 매칭 config만 대상으로 하는 작은 Spring context 검사로 설계한다. 전체 HTTP 앱의 무DB 기동을 완료 조건으로 추가하지 않는다.

### 원장·주문 저장 연결

`exchange.ledger.persistence.enabled=true` → `LedgerPersistenceConfig` 활성화 → 같은 JDBC template로 Balance/OrderReservation/LedgerTransaction Store의 PostgreSQL 구현 조립 → 기존 포트 타입으로 예약·해제·정산 Service에 주입한다. 이 조건이 false/없음이면 해당 config의 Bean은 없다는 기존 계약을 유지한다. 전체 앱 기동 가능 여부를 이 조건 검사만으로 보장하지 않는다.

`OrderApplicationConfig`의 마켓·수수료 정책 공급, `MatchingConfig`의 실행기 `destroyMethod="close"`, config 메서드와 Bean 이름을 유지한다. 업무 객체는 수동으로 `new`한 Store 대신 주입받은 계약을 사용하며, 실제 통합 검사에서는 트랜잭션 프록시 Bean을 거쳐 호출한다.

근거: [MatchingConfig](../app-api/src/main/kotlin/com/exchange/core/api/config/MatchingConfig.kt), [MatchingPersistenceConfig](../app-api/src/main/kotlin/com/exchange/core/api/config/MatchingPersistenceConfig.kt), [LedgerPersistenceConfig](../app-api/src/main/kotlin/com/exchange/core/api/config/LedgerPersistenceConfig.kt), [Coordinator](../app-api/src/main/kotlin/com/exchange/core/api/matching/application/MatchingCoordinator.kt).

### 실패해도 새 보장을 만들지 않는 경계

| 실패 위치 | 유지할 결과·남는 상태 | 다음 처리 |
| --- | --- | --- |
| 자금 예약 | 해당 DB 트랜잭션 롤백. 엔진 호출 전 거절 | 이 사전 실패만으로 마켓을 중단 상태로 바꾸지 않음 |
| 이벤트 변환/목록 저장 | 오류를 전달, 정산/해제 callback 미실행. 앞선 예약·엔진 메모리는 남을 수 있음 | 같은 마켓 후속 명령 거절, 다른 마켓은 계속 처리 |
| 체결별 정산/예약 해제 | 실패한 DB 작업 롤백. 이미 저장된 이벤트·엔진·앞서 커밋한 작업의 전체 보상은 없음 | 기존 오류·마켓 중단 경로 유지 |
| 3초 결과 대기 초과 | 대기만 종료. worker 취소·DB 롤백·자동 재시도 추가 없음 | 현재 정책 보존. 실제 재처리/복구는 #22~23의 별도 범위 |

영속 이벤트 저장과 원장 정산을 하나의 트랜잭션으로 합치지 않는다. NoOp 성공과 실제 저장 성공도 보고서에서 구분한다.

## 4. 남은 ARCH-05와 기존 검사 연결

구현 전 P08은 `duringOrderMigration`과 `inspectOrderTypes`로 이름 규칙 9개와 전체 main 파일을 검사했다. 이번 구현은 같은 수집기를 재사용해 **`ProjectLayoutPolicy.target` + `NamingRules.inspectTypes`**로 전환한다. 새 검사 엔진·범용 분류·클래스 등록표는 만들지 않는다.

| 이번에 활성화할 기존 규칙 | 정상 이름·위치 | 위반 예시 |
| --- | --- | --- |
| Calculator / Resolver | 순수 fee/order 코어의 기존 Calculator·Resolver | app-api 기술 폴더로 이동 |
| Store 포트 | 도메인의 기존 세 포트 또는 matching.application.port | matching.infrastructure에 인터페이스 배치 |
| Store 구현 | 실제 내부 Store 구현, Postgres/Jpa+대상+Store, 해당 포트에 대응하는 persistence | 잔고 구현을 order.persistence에 둠, 포트를 구현하지 않는 Postgres…Store |
| Publisher 포트 | matching.application.port의 인터페이스 | 기술 구현 폴더에 포트 배치 |
| Publisher 구현 | Persistent…Publisher는 matching.infrastructure.persistence, NoOp…Publisher는 matching.infrastructure.publish | 두 위치를 바꾸거나 실제 포트 구현이 없음 |
| Repository | Spring Data를 상속한 …Repository 인터페이스, matching.infrastructure.persistence | 같은 접미사의 일반 클래스나 다른 업무 위치 |
| Entity | @Entity와 …Entity 이름, 허용한 persistence 위치 | application에 Entity 배치 |

추가 8개 규칙과 기존 9개를 합친 **17개 규칙의 실행 목록**을 P08에서 남긴다. 기존 규칙이 있는 것과 운영 검사에서 실행한 것은 구분한다. 추가 규칙이 평가 목록에 없으면 완료로 처리하지 않도록 작은 확인을 둔다. 유형별 대상 수는 실행에서 확인하며 모든 클래스 수를 고정한 등록표로 바꾸지 않는다.

전체 main 원본은 계속 읽고 경로/package 일치를 검사한다. 네 임시 경로 `order/persistence`·`ledger/persistence`·`matching/persistence`·`matching/publish`를 마지막 단계에서 제거한다. 소스와 package를 함께 옛 경로로 되돌려도 실패해야 한다. 단순히 package 문자열만 검사하지 않는다.

P01~P08은 그대로 필수다. P03/ARCH-06에는 이동한 포트/Entity FQN을 반영하고 공개 계약의 기술 누출 금지를 유지한다. P06/ARCH-03은 HTTP의 Store/구현 직접 참조를 금지한다. P07/ARCH-04는 application의 구체 구현·Repository/Entity 직접 참조를 금지하되 config 조립은 허용한다. P05의 자동 업무 등록 금지와 명시적 config Bean 규칙도 유지한다. 보고서 확인기/CI를 새로 구축하거나 P09를 추가하지 않는다.

`PersistentMatchingEventPublisher`를 application 아래로 옮겨 Service처럼 숨기거나, 이름을 UseCase로 바꿔 허용 역할로 바꾸면 안 된다. 포트 구현이라는 단서와 위치 단서의 충돌은 기존 준비 오류로 처리한다. 저장 목록·영속 타입·참조 정의 누락도 위반 0건의 성공으로 기록하지 않는다.

근거: [최종 폴더 정책](../architecture-tests/src/test/kotlin/com/exchange/architecture/policy/ProjectLayoutPolicy.kt), [이름 규칙](../architecture-tests/src/test/kotlin/com/exchange/architecture/rules/NamingRules.kt), [포트 위치 대응](../architecture-tests/src/test/kotlin/com/exchange/architecture/policy/PortPlacementPolicy.kt), [P01~P08 진입점](../architecture-tests/src/test/kotlin/com/exchange/architecture/ProductionArchitectureTest.kt).

## 5. 수용 사례와 기대값 근거

다음은 이번 구현에서 대조한 계약이다. 실제 명령·테스트 집계와 한계는 [구현 기록](storage-boundaries-review.md)과 [실행 근거](storage-boundaries-verification.json)에 둔다. 예제의 예상 위반을 찾으면 **예제 테스트는 통과**하지만 실제 운영 코드 위반은 CI 실패여야 한다.

| 사례 | 초기 조건·입력 | 기대 결과와 근거 | 검증 |
| --- | --- | --- | --- |
| 설정 true | 두 매칭 config, 필요한 Repository/mapper 의존 제공 | Persistent Publisher 하나, NoOp 없음. config 조건을 그대로 보존 | 새 작은 Bean 조건 검사 + 기존 DB 연결 검사 |
| 설정 false / 없음 | 동일 config, 속성만 변경 | NoOp 하나, Jpa Store/Persistent Publisher 없음. 미저장 정상 완료 | 조건별 Bean 검사; DB 전체를 안 쓴다는 주장 제외 |
| JPA 정상·빈 목록 | 기존 네 이벤트 subtype / 빈 목록 | 같은 enum·컬럼·payload JSON·순서. 빈 목록은 저장 호출 없음 | 기존 Payload/Jpa Store 테스트 |
| 목록 부분 실패 | 기존 순번을 저장한 뒤 새 목록의 뒤쪽 순번을 중복 | 새 목록 앞쪽 row도 남지 않고 기존 row는 유지. 목록 트랜잭션 계약 | 기존 Persistent Publisher 실제 DB 회귀 |
| JDBC 잔고 예약 | available=1000, hold=0에서 300 예약 | 반환/저장 상태 700/300. available+hold 유지. 성공 후 추가 SELECT로 설계 전환하지 않음 | 기존 잔고 DB 테스트 + SQL/본문 변경 대조 |
| JDBC 잔고 거절·경합 | available 부족 / 잔고 없음 / 합계가 available 초과인 동시 예약 | 기존 원인 예외, 실패 요청의 갱신 없음. 초과 경합은 하나만 성공 | 기존 부족/부재/동시 갱신 DB 테스트 |
| 예약·원장·정산 | 예약 조회/갱신, 두 번째 원장 항목 실패, 지급 실패 | 잠금·기존 snapshot/수수료 나머지 유지, 담당 TX만 롤백 | 기존 예약/원장/업무 Service DB 테스트 |
| 최종 위치 정상 | 이동표와 맞는 main 경로/package, 실제 포트 구현 | P08 전체 규칙 통과. 새 허용 위치 추가 없이 기존 target 재사용 | 운영 P08 + 기존 이름/폴더 예제 |
| 잘못된 구현 위치 | BalanceStore 구현을 주문 persistence로 이동; NoOp를 영속 위치로 이동 | 읽기 성공 후 ARCH-05 영역/package 위반 | 기존 포트 예제 재사용/필요한 최종 정책 사례 보강 |
| 옛 폴더로 회귀 | 네 옛 경로 중 하나에 package도 맞춘 일반 Helper 추가 | 읽기는 성공하지만 allowedFolder 위반. 이름이 일반적이어도 폴더는 검사 | 최종 SourcePlacement 사례 |
| 의존 우회 | Controller→새 Store/구현, application→Repository/Entity | ARCH-03/04 위반. config→같은 구현은 정상 | 기존 운영 입력 연결 예제의 새 FQN |
| 누락 | 실제 포트 출력/Entity/상위 정의, 정책 매핑, 필수 P08 기록이 누락 | 준비 실패·미평가 또는 보고서 실패. 성공 0건으로 대체하지 않음 | 기존 수집·포트·XML 예제 |
| 일반 보조 이름 | 허용 persistence에 MatchingEventPayload/MatchingEventType, 허용 application에 OrderManager | 역할 미분류만으로 실패시키지 않음. 경로·실제 의존은 계속 검사 | 기존 일반 이름 사례와 의미 리뷰 |
| 실제 HTTP/Bean 회귀 | 기존 체결·취소·입력 거절 시나리오 | URL/응답/원장/예약 결과·오류 동일, 중복 Bean·주입 실패 없음 | 기존 Controller·E2E·context/DB 검사 |

잔고 1000/300 사례는 보존해야 할 산술 계약의 독립 예시이며, 새 구현을 실행해 얻은 값을 기대값으로 복사하지 않는다. 기존 테스트의 금액·수수료 기대값을 이동을 이유로 바꾸지 않는다.

## 6. 작은 실행 순서와 검증 근거

1. **포트·발행 연결:** 포트 두 개와 발행 구현 두 개를 이동하고 호출부/테스트 import, 외부 포트 FQN을 동시에 수정한다. 새 Bean 조건 사례는 true·false·설정 없음의 세 경우부터 작성한다. 기존 발행 성공/목록 롤백/Coordinator 실패/HTTP 검사를 재사용한다. 이 단계는 기존 네 이행 폴더와 부분 이름 검사를 유지해 중간 상태도 검사 가능하다.
2. **JDBC/JPA 저장:** 나머지 8개 파일과 해당 테스트를 기능별 persistence로 이동하고 Entity FQN·config 연결을 맞춘다. SQL 문자열, 변환 함수, @Transactional·open/proxy 조건, DB migration 파일은 유지한다. 잔고·예약·원장·JPA·업무 DB 테스트와 전체 주문 E2E로 확인한다. 남은 구현이 없는지 소스와 참조를 검색한다.
3. **전체 규칙·이행 종료:** P08을 전체 이름/최종 폴더 정책으로 바꾸고 사용처를 정리한 뒤 임시 `duringOrderMigration`·`inspectOrderTypes`를 제거한다. 관련 테스트를 최종 규칙의 정상·위반 기대값으로 바꾸되 과거 실행 기록은 유지한다. 네 옛 폴더 사례·옮긴 타입의 우회/누락을 확인하고 전체 검사·보고서·CI로 종료한다.

첫 단위는 관련 8개 검사를 실행했다. 최종 이동 후 제품·DB 81개와 구조 353개를 아래 두 작업으로 각각 재실행했고 필수 운영 P01~P08 성공을 확인했다. 같은 검증을 다시 실행할 때는 다음 작업 폴더를 사용한다.

~~~bash
cd /Users/0chord/.codex/worktrees/issue21-storage-boundaries/exchange-core
./gradlew :app-api:test --no-daemon --console=plain --rerun-tasks
./gradlew :architecture-tests:verifyArchitectureReport --no-daemon --console=plain --rerun-tasks
~~~

구조 검사에는 Docker가 필요 없고 app-api의 기존 PostgreSQL 테스트에는 Docker가 필요하다. 전체 build는 기존 CI가 담당하며, 로컬 위 명령을 도메인 테스트 전체 실행으로 세지 않는다. 단위별 테스트는 실제 변경된 클래스의 새 FQN으로 선택해 실행하며 선택 0건을 성공으로 쓰지 않는다.

기존 주요 근거: [발행 목록 롤백](../app-api/src/test/kotlin/com/exchange/core/api/matching/infrastructure/persistence/PersistentMatchingEventPublisherTest.kt), [매칭 DB 연결](../app-api/src/test/kotlin/com/exchange/core/api/matching/infrastructure/persistence/MatchingPersistenceIntegrationTest.kt), [Coordinator 저장 실패](../app-api/src/test/kotlin/com/exchange/core/api/matching/application/MatchingCoordinatorPersistenceFailureTest.kt), [잔고](../app-api/src/test/kotlin/com/exchange/core/api/ledger/infrastructure/persistence/PostgresBalanceStoreTest.kt), [원장](../app-api/src/test/kotlin/com/exchange/core/api/ledger/infrastructure/persistence/PostgresLedgerTransactionStoreTest.kt), [예약](../app-api/src/test/kotlin/com/exchange/core/api/order/infrastructure/persistence/PostgresOrderReservationStoreTest.kt), [주문 E2E](../app-api/src/test/kotlin/com/exchange/core/api/order/application/OrderLifecycleE2ETest.kt), [기존 포트 이름 사례](../architecture-tests/src/test/kotlin/com/exchange/architecture/NamingPlacementPortTest.kt).

수집 실패·구조 위반·JUnit 실패·Docker/기동 문제를 결과에서 구분한다. 로그와 app-api/architecture-tests의 HTML·XML, P01~P08 실행 여부, 변경 소스 기준, 미실행 사례를 연결한다. 전체 64개 main 파일 등의 과거 수는 성공 조건으로 고정하지 않고 이번 실행 대상 목록을 확인한다.

## 7. 완료 조건과 사람의 판단

- [x] 이동표 12개 파일의 경로와 package, 호출·테스트·Bean·포트/Entity FQN을 함께 맞췄다.
- [x] true/false/설정 없음의 Publisher 선택과 기존 config 조건·실행기 종료·실제 프록시/스캔 연결을 확인했다.
- [x] 기존 SQL·메서드 동작·DB 제약·JSON·API·금액 기대값을 보존한 근거가 있다.
- [x] P01~P08이 실제 실행됐고 P08은 17개 이름 규칙과 전체 main 원본을 평가했다. 새 저장 규칙의 미실행을 성공으로 쓰지 않는다.
- [x] 네 이행 폴더와 임시 진입점을 종료하고 정상·위반·누락 예제로 거짓 통과를 막았다.
- [x] 관련 실제 DB·업무·HTTP·실패 회귀의 로컬 실행 결과를 기준 커밋·현재 코드 해시와 함께 기록했다.
- [ ] 게시한 PR의 원격 CI 결과와 리뷰·병합을 확인한다. 로컬 통과는 이 항목의 완료 근거가 아니다.
- [x] 자연어 흐름에서 설정·Bean·포트/구현·TX·실패 결과를 실제 코드/테스트로 추적할 수 있다.

사람이 볼 판단은 세 가지다. **폴더 이동 외의 책임·SQL·보장이 바뀌지 않았는가**, **NoOp를 실제 저장 성공과 혼동하지 않는가**, **코어 포트와 기술 구현/Repository를 구분하는가**. 자동 검사는 정확한 업무 이름·모든 동적 의존·DB 계산·복구의 정확성을 대신 판단하지 않는다.

이번 명세와 구현 기록은 로컬 구현·검증 산출물이다. 이슈 종료·Projects 변경·PR 게시·원격 CI·병합 결과는 이 로컬 기록과 구분한다. 구현 중 새 스캔/조건/트랜잭션 문제가 드러나면 해당 단위에서 재현하고 최소 변경안을 논의한다. 이번 실제 검사에서는 추가 스캔 설정이나 계약 변경이 필요하지 않았다. 현재 합의한 로컬 구현 범위를 막는 미결정은 없다.

## 8. 설명과 판단 순서

설정값 → 선택되는 Bean → 호출 순서 → 결과 순서로 설명한다. 위치 이동 중 설정으로 선택한 미저장 정상 완료와, 영속화가 켜진 상태의 실제 저장 실패를 구분하기 위해서다. 3절의 설정별 연결과 5절의 Bean 수용 사례가 이 판단을 뒷받침한다. 새 미저장 정책이나 자동 fallback을 추가하지 않는다.
