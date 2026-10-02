# #20 상세 명세 — 주문 진입점·매칭 조율·운영 검사

대상: [#20 주문·매칭 유즈케이스 명명과 기능별 배치 정리](https://github.com/0Chord/coin-exchange/issues/20). 상위: #18. 선행: #19. 확인일: 2026-10-02.

**상태: 합의한 범위의 로컬 구현·검증 완료.** 테스트·실제 운영 연결의 결과는 [구현 기록](order-usecases-review.md)에 둔다. 최신 통합 브랜치 `feature/phase-2/integration`의 **`2ccd4250268a0b084811014bff06d80523ccd3c5`**를 직접 읽었다. 이 기준 커밋의 [병합 후 CI](https://github.com/0Chord/coin-exchange/actions/runs/36971836663)는 구조 검사와 전체 빌드 모두 성공했다. 이것은 기존 코드의 기준점이며 이번 설계의 실행 결과가 아니다.

## 먼저 볼 결과

거래 기능을 새로 만들지 않는다. **어디에서 주문 업무를 시작하고, 누가 실행을 연결하며, 그 경계를 실제 코드에서도 지키는지** 명확히 한다.

| 설계 기준점의 이름 | 이행 이름·위치 | 바뀌는 것 |
| --- | --- | --- |
| `OrderSubmissionService` | `order.application.SubmitOrderUseCase` | 주문 제출의 업무 진입점임을 표시 |
| `OrderCancellationService` | `order.application.CancelOrderUseCase` | 주문 취소의 업무 진입점임을 표시 |
| `MatchingApplicationService` | `matching.application.MatchingCoordinator` | 마켓 실행기와 전후 작업을 잇는 내부 협력자임을 표시 |
| `MatchingController` | `order.api.OrderController` | HTTP에서 받는 일이 주문 제출·취소임을 표시 |

표의 패키지 앞에는 모두 `com.exchange.core.api`가 붙고, 모듈은 `app-api`다. 목표 이름은 #20 로컬 구현에서 실제 파일로 이행했다.

`OrderFundingService`, `OrderReservationReleaseService`, `TradeSettlementService`는 내부 작업 이름을 유지하고 `order.application`으로 옮긴다. `MatchingResponse`·`MatchingEventResponse`와 JSON 필드는 유지한다. `UseCase` 인터페이스나 동일 책임의 구현체를 추가하지 않는다.

## 유지할 입력 → 판단 → 결과

**제출:** HTTP 요청을 명령으로 변환 → 제출 UseCase가 마켓·LIMIT/GTC 지원 여부 확인 → Coordinator가 같은 마켓 worker에 전달 → 자금 예약 → 매칭 → 이벤트 발행·저장 → 체결별 정산 → worker 결과 완료 → HTTP 응답 변환.

**취소:** HTTP 요청을 명령으로 변환 → 취소 UseCase가 Coordinator에 전달 → 같은 마켓 worker가 존재·소유자를 판단하고 취소 → 이벤트 발행·저장 → `OrderCancelled`인 경우에만 예약 해제 → 결과 완료 → HTTP 응답 변환. `OrderCancelRejected`도 이벤트로 반환되며 예약금은 해제하지 않는다.

**세 개의 DB 경계를 하나로 합치지 않는다.** 자금 예약, 이벤트 저장, 체결별 정산 또는 예약 해제는 서로 다른 경계다. 뒤쪽 실패가 앞선 엔진 상태·커밋을 모두 되돌린다는 보장을 추가하지 않는다. HTTP의 3초 대기 시간 초과도 worker 취소나 롤백으로 바꾸지 않는다.

## 작게 확인하는 구현 순서 — 추천

하나의 #20 작업을 아래 세 단위로 구현하고 각 단위 직후 코드·테스트·자연어 흐름을 보여준다. 기본 제안은 한 PR에 세 단위를 담는 것이다. 별도 PR로 나눌 경우에도 아래 경계와 중간 컴파일·기존 검사 통과를 유지하며 새 이슈를 발급하지 않는다.

| 단위 | 관측할 작은 결과 | 확인할 근거 |
| --- | --- | --- |
| 1. 매칭 조율자 | `MatchingCoordinator`로 이름·폴더 변경. 호출자와 MatchingConfig가 새 타입 사용 | 기존 이벤트 저장 실패·마켓 분리 테스트와 컴파일. 실행 순서·3초 대기·예외 전달 유지 |
| 2. 주문 진입점과 HTTP | 두 UseCase·세 내부 Service 이동. OrderController·DTO·매퍼 이동. Bean 연결 동기화 | 기존 HTTP 정상·거절·중복 취소 테스트와 실제 Spring 조립·주문 E2E |
| 3. 운영 검사 연결 | ARCH-03·04와 이번 ARCH-05 규칙이 실제 전체 수집 결과를 검사 | 새 타입 자동 포함·위반·누락 사례, 운영 XML의 실행 확인, 독립/전체 CI |

첫 테스트는 실제 운영 출력에서 `MatchingCoordinator`가 목표 패키지에 존재한다는 기대부터 잡는다. 변경 전에는 그 타입이 없다는 이유로 실패해야 한다. 컴파일 오류나 Docker 오류를 의도한 Red라고 쓰지 않는다. 이미 있는 저장 실패 테스트는 복제하지 않고 이름·참조를 동기화하여 재사용한다.

## 현재 결정과 제외 범위

**기존 합의:** 도메인 모듈은 Kotlin 코어를 유지한다. 업무 객체는 config의 명시적 `@Bean`으로 조립한다. `@Service`·`@Component`·`@Repository` 자동 등록은 금지하고 HTTP의 `@RestController`·`@RestControllerAdvice`는 유지한다. 일반 클래스마다 역할·이름을 등록하지 않는다.

**이번 문답으로 확정:** `OrderController`로 변경하고 기존 DTO 이름·JSON은 유지한다. 함수 `submit`·`cancel`·`process`도 이번에는 유지한다. HTTP URL·상태 코드·오류 메시지·이벤트 순서, Spring 트랜잭션 경계와 조건부 `UPDATE … RETURNING`을 보존한다.

**#21로 넘김:** 저장 포트·PostgreSQL/JPA 구현·Spring Data 인터페이스·발행 구현의 위치 이동 및 해당 이름/위치 규칙 활성화. **#22~23으로 넘김:** 새로운 DB·상태 소유·복구·동시성 보장의 설계와 확장. **#24로 넘김:** 일괄 주석 재작성. 이번에는 이동으로 깨지는 문서·KDoc 링크만 고친다.

<details markdown="1">
<summary>이름·폴더·Bean의 정확한 변경표</summary>

## 실제 파일과 목표 배치

아래 변경 전 코드 링크는 기준 커밋의 원문이다. 목표 경로는 #20 로컬 구현에서 이행했으며 실제 현재 코드·검증 결과는 구현 기록에 연결한다. 아래 경로의 루트는 `app-api/src/main/kotlin/com/exchange/core/api/`다.

| 현재 파일 | 목표 파일 | 책임·보존할 호출 |
| --- | --- | --- |
| [order/OrderSubmissionService.kt](https://github.com/0Chord/coin-exchange/blob/2ccd4250268a0b084811014bff06d80523ccd3c5/app-api/src/main/kotlin/com/exchange/core/api/order/OrderSubmissionService.kt) | `order/application/SubmitOrderUseCase.kt` | `submit(command)` → 예약·Coordinator·정산. 지원 조건 먼저 확인 |
| [order/OrderCancellationService.kt](https://github.com/0Chord/coin-exchange/blob/2ccd4250268a0b084811014bff06d80523ccd3c5/app-api/src/main/kotlin/com/exchange/core/api/order/OrderCancellationService.kt) | `order/application/CancelOrderUseCase.kt` | `cancel(command)` → Coordinator·취소 성공 시 해제 |
| [matching/MatchingApplicationService.kt](https://github.com/0Chord/coin-exchange/blob/2ccd4250268a0b084811014bff06d80523ccd3c5/app-api/src/main/kotlin/com/exchange/core/api/matching/MatchingApplicationService.kt) | `matching/application/MatchingCoordinator.kt` | `process`·beforeMatching·publisher·afterMatching·Future 대기 유지 |
| [matching/MatchingController.kt](https://github.com/0Chord/coin-exchange/blob/2ccd4250268a0b084811014bff06d80523ccd3c5/app-api/src/main/kotlin/com/exchange/core/api/matching/MatchingController.kt) | `order/api/OrderController.kt` | 요청·명령·응답 변환, 두 UseCase만 업무 진입으로 호출 |
| `matching/MatchingDtos.kt` | `order/api/MatchingDtos.kt` | SubmitOrderRequest·MatchingResponse·MatchingEventResponse 이름과 필드 유지 |
| `matching/MatchingEventResponseMapper.kt` | `order/api/MatchingEventResponseMapper.kt` | `MatchingEvent.toResponse()`와 각 이벤트 필드·순서 유지 |
| `order/OrderFundingService.kt` | `order/application/OrderFundingService.kt` | 계산 → 예약 생성 → 잔고 hold. `@Transactional` 유지 |
| `order/OrderReservationReleaseService.kt` | `order/application/OrderReservationReleaseService.kt` | 예약 잠금 → 도메인 해제 → 예약 갱신·잔고 반환. 트랜잭션 유지 |
| `order/TradeSettlementService.kt` | `order/application/TradeSettlementService.kt` | 체결별 양쪽 예약·잔고·수수료 원장. 내부 Service 유지 |

단순 전달용 인터페이스·기반 클래스·범용 UseCase 실행기를 만들지 않는다. `domain-*`에 application/infrastructure 폴더를 추가하지 않는다. `MarketCommandProcessor`·`InMemoryMarketCommandProcessor`와 worker는 이동하지 않는다.

## Bean 연결

조립 클래스의 위치와 책임 분배를 유지하고 필요한 반환 타입·매개변수·import·메서드 이름만 동기화한다. `@Qualifier`나 문자열 Bean 이름 참조가 실제로 있는지는 이행 전에 다시 검색한다. 현재 읽은 주문·매칭 호출부는 타입 주입이다.

| 조립 위치 | 목표 Bean 선언 | 유지할 조건 |
| --- | --- | --- |
| `config/OrderApplicationConfig.kt` | `submitOrderUseCase(): SubmitOrderUseCase`, `cancelOrderUseCase(): CancelOrderUseCase` | 기존 자금 작업·Coordinator·마켓·수수료 정책을 연결 |
| `config/MatchingConfig.kt` | `matchingCoordinator(): MatchingCoordinator` | 기존 processor와 MatchingEventPublisher 주입 |
| 같은 MatchingConfig | `marketCommandProcessor(): MarketCommandProcessor` | InMemory 구현, singleton 공유, `destroyMethod="close"` 유지 |
| 같은 MatchingConfig | 기존 `matchingEventPublisher()` | persistence=false/미지정 시 NoOp 선택 유지 |
| `config/MatchingPersistenceConfig.kt` | 기존 저장 포트·Persistent publisher Bean | persistence=true일 때만 활성화. 기존 이벤트 저장 구현 유지 |
| `config/LedgerPersistenceConfig.kt` | 기존 예약·해제·정산 Service Bean | ledger.persistence=true 조건·포트 반환 타입·실제 Spring 프록시 유지 |

예약·해제·정산 Bean을 OrderApplicationConfig로 옮기는 별도 조립 리팩터링은 하지 않는다. 조건부 등록을 실수로 무조건 등록으로 바꾸지 않는다. Controller는 자동 등록을 유지하며 별도 Controller `@Bean`을 추가하여 중복 등록하지 않는다.

검증은 새 두 UseCase와 Coordinator가 하나씩 조립되고 HTTP가 그 객체들을 사용하는지, 트랜잭션이 필요한 Service가 기존 프록시를 통해 호출되는지를 본다. 전체 테스트의 MarketDefinition·TradingFeePolicySnapshot은 기존 `ExchangeIntegrationTestConfiguration`이 제공한다. 그 환경의 조립 성공을 운영 마켓 설정·배포 기동 검증으로 확대하지 않는다.

호출부·테스트도 함께 고친다. MatchingControllerTest는 OrderControllerTest와 `order.api`로, MatchingApplicationServicePersistenceFailureTest는 MatchingCoordinatorPersistenceFailureTest와 `matching.application`으로 정리한다. MatchingPersistenceIntegrationTest·ExchangeCoreApplicationTests의 타입 주입, README·흐름 문서의 링크도 동기화한다. 기존 테스트의 기대값·검증 강도를 낮추지 않는다.

</details>

<details markdown="1">
<summary>ARCH-03·04·05를 실제 코드에 어디까지 적용하는가</summary>

## 재사용과 운영 활성화

설계 기준 커밋의 `ProductionArchitectureTest` P01~P05는 ARCH-01·02·06·08 및 Bean 조립을 검사했고, ARCH-03·04와 이름·폴더 전체 규칙은 예제에만 적용돼 있었다. 이번 구현은 P06~P08에서 실제 운영 적용을 추가했다. 기존 전체 출력 수집, `ProductionScopeImporter`, Gradle `main-sources.gradle.kts`, `MainSourceSnapshot`과 규칙 함수를 재사용한다. 새 라이브러리·언어·범용 역할 엔진을 추가하지 않는다.

| 규칙 | #20 운영 출발점·허용 | 금지·이번에 확인하는 결과 |
| --- | --- | --- |
| ARCH-03 | 전체 출력에서 발견한 Controller와 `order.api`의 DTO·매퍼·최상위 함수. Controller → 두 UseCase·허용 입력/출력 데이터·변환 | Controller/HTTP 보조 코드 → Store·구현·Coordinator·예약/정산 Service·엔진 직접 참조. 매퍼 안에 숨긴 호출도 위반 |
| ARCH-04 | `order.application`·`matching.application`의 업무·보조 코드. UseCase → 내부 Service·Coordinator, 업무 → 도메인·포트·MarketCommandProcessor 계약 | 업무 → HTTP·구체 저장/발행 구현·실행기 구현·config·DB/HTTP 기술·ApplicationContext 직접 참조 |
| ARCH-05 | 기존 개별 `controllers`, `useCases`, `services`, `coordinators`, `httpData` 규칙. 전체 출력에 적용해 다른 폴더로 이동한 대상도 발견 | 잘못된 이름/선언/역할 위치. 일반 OrderManager는 이름만으로 거절하지 않음 |
| 파일 위치 | Gradle이 발견한 운영 Kotlin/Java 원본 전체와 실제 package | 허용하지 않은 루트/폴더, package·파일 경로 불일치. 클래스 없는 파일도 검사 |

공통 오류 Advice는 기존 변환 책임과 예외 데이터만 사용한다. 파일 위치·기존 `advice`·`errorResponses`·`configurations`·`bootstrap` 규칙도 연결한다. Advice를 독립 업무 진입점으로 취급하지 않는다. Bean 금지 검사는 기존 P05를 유지한다. Calculator·Resolver, Store/Publisher·구현·Repository·Entity의 이름/역할 위치 검사를 이번에 일괄 켜지 않는다.

### 클래스별 등록을 늘리지 않는 입력 연결 — 추천 구현안

기존 ARCH-03·04 함수가 받는 역할 map은 **검사 시 수집된 타입에서 만드는 임시 입력**이다. 새 클래스마다 사람이 편집하는 등록표로 사용하지 않는다. 얇은 운영 연결 함수에서 다음 단서를 사용한다. 검사 의미를 추론하는 새 분류 프레임워크로 확장하지 않는다.

1. Controller는 표준·합성 Controller 어노테이션으로 전체 출력에서 발견한다. 이름 단서는 ARCH-05가 별도로 검사한다. HTTP 변환은 주문 API 패키지 전체를 읽고 파일 파사드·중첩 타입을 포함한다.
2. UseCase는 허용된 주문 application 패키지의 구체 `*UseCase` 선언이다. 그 외 업무·보조 코드는 application 패키지에서 선택한다. `OrderManager`도 해당 application 경계 검사에는 포함되지만 HTTP가 직접 호출할 UseCase는 아니다.
3. 기존 `ProductionScope.roles.externalPorts`와 실행기 진입 계약을 재사용한다. 구체 Store/Publisher 구현 관계, 영속 모델, 기존 기술 패키지는 금지 목적지로 구분한다. 포트 인터페이스와 구현체를 같은 허용 역할로 묶지 않는다. `MarketCommandProcessor` 계약은 허용하고 그 InMemory 구현·worker 직접 사용은 금지한다.
4. HTTP가 사용하는 공통 값 타입, MatchingCommand/MatchingEvent 계약과 그 하위 데이터, 현재 Side·OrderType·TimeInForce enum·도메인 예외를 입력/결과 데이터로 구분한다. MatchingEngine·OrderBook·Calculator를 도메인 모듈에 있다는 이유로 HTTP 데이터에 포함하지 않는다. 새로운 종류의 HTTP 공개 계약을 도입할 때만 이 데이터 경계를 검토한다.
5. config와 나머지 수집된 내부 타입의 소속도 준비 단계에서 확인한다. 충돌·미해석·수집 누락을 기본 허용으로 바꾸지 않는다. UseCase 이름을 붙인 Store 구현체처럼 단서가 충돌하면 준비 실패 또는 명시한 위반이며, 이름만으로 허용하지 않는다.

핵심 진입점 **OrderController·SubmitOrderUseCase·CancelOrderUseCase·MatchingCoordinator**는 존재 확인의 최소 기대다. 전체 대상 목록을 이 네 타입으로 제한하지 않는다. 새 AmendOrderUseCase가 허용 폴더에 추가되면 별도 클래스 등록 없이 검사 대상으로 들어가야 한다. 이 최소 기대는 현재 공개 진입점이 통째로 사라진 상황을 잡기 위한 것이며 업무 이름의 의미를 자동 증명하지 않는다.

등록 누락 사례는 이번 연결에서는 ‘새 클래스 이름을 등록하지 않았다’가 아니다. **발견한 원본·출력·참조 정의 또는 필요한 운영 검사 기록을 누락한 것**이다. 전체 application 보조 코드를 정상 이름 세 개만 검사하는 방식으로 숨기지 않는다.

### #21 이전 파일 이행 경계

최종 허용 목록은 기존 `ProjectLayoutPolicy.target`을 유지한다. #20에서 원본 전체를 검사할 때는 다음 **네 기존 폴더만** 이행 중 허용 위치로 더하는 정책을 같은 파일에 명시한다. 이유·후속 티켓·제거 조건은 #21 저장 이행 완료다.

| #20에서 유지할 기존 폴더 | #21 목표 |
| --- | --- |
| `com/exchange/core/api/order/persistence` | `order/infrastructure/persistence` |
| `com/exchange/core/api/ledger/persistence` | `ledger/infrastructure/persistence` |
| `com/exchange/core/api/matching/persistence` | `matching/infrastructure/persistence` 및 `matching/application/port` |
| `com/exchange/core/api/matching/publish` | `matching/application/port` 및 `matching/infrastructure/publish` |

모두 `app-api/src/main/kotlin`의 정확한 경로다. `api/**`·패키지 전체 제외를 추가하지 않는다. 이전 주문 application 루트 `api/order`, 이전 HTTP 루트 `api/matching`는 #20 완료 시 허용하지 않는다. 최종 정책을 덮어써 이행 위치를 영구 목표처럼 만들지 않는다.

이 네 폴더도 파일 읽기·package 일치 검사를 받는다. 그 안의 저장/발행 구현은 ARCH-03·04의 금지 목적지다. 미이행 이름·역할 위치 규칙만 #21로 남는 것이며, 저장 구현 직접 호출을 임시 허용하는 예외가 아니다.

### 입력 → 판단 → 보고

Gradle이 전체 운영 출력·main 원본을 전달 → 기존 수집기가 읽기·모듈 소속 확인 → 패키지/어노테이션/계약으로 검사 입력 준비 → 기존 ARCH-03·04와 선택한 ARCH-05·파일 규칙 평가 → HTML/XML에 검사 대상·위반·미평가·#21에 남긴 범위 보고.

운영 기록은 기존 P01~P05를 유지하고 **P06=실제 HTTP(ARCH-03), P07=실제 application(ARCH-04), P08=이번 이름·파일 위치(ARCH-05)**를 추가하는 안이다. `ArchitectureReportVerifier`와 그 테스트·Gradle 설명을 같은 단위에서 P01~P08 확인으로 확장한다. CI workflow·업로드 경로는 재사용한다.

누락·중복·skip·실패한 P06~P08은 결과 확인 작업도 거절해야 한다. 새 운영 검사가 0개였는데 기존 다섯 개만 성공해서 CI가 통과하는 상황을 막는다. 각 운영 검사는 독립적으로 준비하고 테스트 실행 순서에 의존하지 않는다.

기존 ARCH-03/04 입력 계약을 재사용한 운영 연결은 로컬 P06~P08과 정상·위반·누락 사례에서 실행했다. 전체 회귀·PR/CI·병합 여부는 구현 기록에서 구분한다. 첫 수용 사례에서 실제 Kotlin 생성 타입·일반 보조 코드·포트 구현 관계를 대조한다. 예상과 다른 허용이 드러나면 이 명세의 보장을 축소하거나 넓은 예외로 통과시키지 않고 원인과 대안을 사용자에게 알린다.

</details>

<details markdown="1">
<summary>정상·위반·누락 사례와 보존할 실패 경계</summary>

## 구조 수용 사례

| 입력·변경 | 기대 | 근거 |
| --- | --- | --- |
| 목표 폴더의 OrderController → SubmitOrderUseCase/CancelOrderUseCase → 협력자·포트 | 실제 대상 평가, 위반 0 | HTTP 변환과 업무 조율 구분 |
| Controller 또는 toResponse 최상위 함수 → BalanceStore/MatchingCoordinator/TradeSettlementService | ARCH-03 위반 | 포트·중간 함수로 업무 진입을 우회할 수 없음 |
| UseCase → BalanceStore·MarketCommandProcessor, config → PostgresBalanceStore·InMemory 구현 생성 | ARCH-04 통과 | 업무는 계약 사용, 구현 생성은 config 책임 |
| UseCase 또는 새 OrderManager → PostgresBalanceStore·Persistent publisher·ResponseEntity·ApplicationContext | ARCH-04 위반 | application 폴더의 일반 보조 코드도 동일 경계 적용 |
| Store 구현을 `BadUseCase`로 개명하여 application에 이동 | 준비 충돌/위반, 성공 아님 | 구현 관계를 허용 이름으로 덮어쓸 수 없음 |
| 정상 어노테이션·이름의 Controller를 네 허용 이행 persistence 폴더에 package까지 맞춰 이동 | 파일 검사는 통과할 수 있음. Controller 위치 검사 실패 | 물리 경로 허용과 책임 배치는 별개 |
| 새 AmendOrderUseCase·새 Controller 추가 | 별도 클래스 등록 없이 발견. 위치·직접 참조에 따라 판정 | 기존 클래스 목록만 검사하는 거짓 통과 방지 |
| 허용 application 폴더에 OrderManager 추가, 금지 의존 없음 | 폴더·ARCH-04 통과. 이름의 업무 의미는 리뷰 | 모든 클래스의 의미를 이름으로 추론하지 않는 기존 합의 |
| `.order.api` 아래 임의 helpers 하위 폴더 추가 또는 원본만 이동 | 파일 규칙 위반 | 정확한 허용 폴더·package 일치 계약 |
| 읽을 출력/원본/참조 정의 손상·누락, 핵심 진입점 삭제 | 준비 실패·미평가 또는 필수 대상 실패. 위반 0 성공 아님 | 검사하지 못한 상태와 준수를 구분 |
| 운영 P06~P08 중 하나가 XML에서 누락·중복·skip | 결과 확인 실패 | 검사 함수가 존재하는 것과 실제 성공을 구분 |

HTTP 허용 DTO·명령 생성은 정상 반대 사례로 유지한다. 대상을 줄이거나 test 디렉터리의 위반 예제를 운영 위반으로 보고하지 않는다. 기존 #19의 검사기 예제와 이번 실제 운영 준수 결과를 보고서에서 구분한다.

## HTTP·실제 조립·동작 수용 사례

URL은 `POST /api/markets/{marketId}/orders`, `DELETE /api/markets/{marketId}/orders/{orderId}?userId=...`를 유지한다. 기존 요청 7개 필드와 응답 events·각 이벤트의 nullable 필드·순번을 유지한다.

| 초기 상태·입력 | 보존할 결과·금지할 변화 | 재사용할 근거 |
| --- | --- | --- |
| 기존 테스트 마켓, BUY 지정가 100·수량 5, taker 수수료 1% | 200·ORDER_ENTERED_BOOK. 거래 대금 500+예약 수수료 5=hold 505, available 1,000,000→999,495 | 현재 ControllerTest의 독립 금액 예시. 변경 후 계산 결과를 기대값으로 복사하지 않음 |
| 위 미체결 주문 취소 | 200·ORDER_CANCELLED. 남은 예약 0·RELEASED, available 1,000,000·hold 0 | 기존 취소 HTTP·E2E |
| 없는 주문 또는 다른 사용자 취소 | 200·ORDER_CANCEL_REJECTED. 잔고·기존 예약 유지 | 기존 두 거절 HTTP 사례. 거절 이벤트 저장 자체까지 금지하지 않음 |
| 가격 0·MARKET 주문 또는 중복 접수 | 기존 400·message 유지. 예약/hold 추가 차감·새 매칭 없음 | 기존 입력 거절·중복 HTTP 사례 |
| 이미 취소한 주문을 다시 취소 | 기존 거절, 잔고 두 번 반환 없음 | 기존 중복 취소 HTTP 사례 |
| SELL/BUY 전량 체결 | 체결 이벤트·양쪽 예약/잔고·수수료 원장 동일 | OrderLifecycleE2ETest·TradeSettlementServiceTest |
| 기존 저장 오류 재현 | 원래 오류 전달·실패 마켓 후속 명령 거절·다른 마켓 계속 동작 | 현재 MatchingApplicationServicePersistenceFailureTest 세 사례 |
| 실제 config와 PostgreSQL, 테스트 마켓·정책 구성 | 새 타입 조립·주입 성공. 예약/해제/정산의 기존 트랜잭션 효과 유지 | ExchangeCoreApplicationTests와 기존 Service 통합 테스트 |

### 보존할 실패 경계

- 지원 조건·입력 변환 실패는 예약 전에 거절한다. 제출 UseCase의 마켓 확인을 취소 UseCase에 새로 추가하지 않는다.
- 예약 자체 실패: 예약 트랜잭션의 복구는 해당 Service 책임이며 엔진은 실행하지 않는다. worker가 DB를 대신 롤백한다고 쓰지 않는다.
- 예약 성공 후 엔진/이벤트 발행/정산 실패: 원인 예외를 호출자에게 전달한다. 남은 상태가 있을 수 있어 실패 마켓의 후속 명령을 거절한다. 자동 보상·재시도·전체 롤백은 추가하지 않는다.
- 취소 이벤트 저장 뒤 예약 반환 실패: 반환 DB 트랜잭션은 실패하지만 엔진 취소·앞선 이벤트 저장이 남을 수 있다. 성공 응답을 먼저 보내지 않는다.
- 3초 응답 대기 시간 초과: HTTP 대기는 끝나도 worker 실행 결과는 별개다. 취소·롤백·정확히 한 번 완료를 보장했다고 쓰지 않는다. interrupt flag 복구·ExecutionException 원인 전달도 유지한다.

구조 검사로 금액·순서·실제 Spring 프록시·SQL 원자성을 증명하지 않는다. 기존 검증이 없는 timeout 후 실제 상태·여러 체결 중 후반 실패의 새로운 보장은 #22~23 범위다. 이행 중 그 코드가 실질적으로 바뀌면 필요한 회귀 사례를 보강하고 범위 변경을 논의한다.

</details>

<details markdown="1">
<summary>실행·완료 기준·문답과 미검증 기록</summary>

## 구현 시 검증 계획

아래는 설계에서 정한 검증 계획이다. 실제 실행 명령·작업 폴더·결과는 구현 기록으로 분리한다. 중복 전체 실행을 줄이기 위해 이번에는 전체 build와 필수 보고서 확인을 같은 Gradle 호출로 실행하며, 각 검사의 실제 실행·실패·skip을 별도로 확인한다.

- 단위 1: app-api 컴파일, 이름·위치 첫 기대와 기존 Coordinator 저장 실패 테스트. DB 테스트에는 Docker가 필요하다.
- 단위 2: 이동한 OrderControllerTest, OrderLifecycleE2ETest, ExchangeCoreApplicationTests, 기존 예약·해제·정산 테스트. 테스트용 실제 조립과 PostgreSQL을 사용한다.
- 단위 3: `:architecture-tests:test --rerun-tasks` → `:architecture-tests:verifyArchitectureReport`, 이어 전체 `build --continue --stacktrace --rerun-tasks`. 기존 CI의 독립 구조 job과 전체 job 모두 확인한다.
- 기존 결과 경로: `architecture-tests/build/reports/tests/test/index.html`, `architecture-tests/build/test-results/test/TEST-com.exchange.architecture.ProductionArchitectureTest.xml`, `app-api/build/reports/tests/test/index.html`. 실제 실패 단계·커밋·명령·미실행 범위를 기록한다.

### 완료 기준

- [x] 새 세 업무 타입과 OrderController·DTO·매퍼·내부 Service가 목표 이름/폴더에 있고 이전 구현이 중복으로 남지 않는다.
- [x] config와 호출부·테스트의 타입·Bean 연결, persistence 조건·processor 종료·트랜잭션 프록시가 보존된다.
- [x] ARCH-03·04와 명시한 ARCH-05·원본 규칙이 실제 운영 출력을 평가하며 대상·파일 수가 기록된다. 새 클래스·위반·누락 사례를 대조한다.
- [x] P01~P08 실제 실행과 결과 확인이 연결된다. #21 이행 네 폴더와 미활성 규칙을 별도로 보고한다.
- [x] HTTP 계약·정상·거절·중복 취소·실제 조립·기존 DB 및 실패 전달 회귀를 통과한다. 기대값을 약하게 바꿔 통과시키지 않는다.
- [x] README·공통 흐름·현재 적용표·HTML 코드 링크를 새 이름과 일치시킨다. 기준 문서를 여러 개 복제하지 않는다.
- [ ] PR/CI/리뷰 근거와 사람의 흐름 판단을 구분해 기록한다. #20 완료를 #21~25 전체 완료로 확대하지 않는다.

### Ask 기록

| 항목 | 기록 |
| --- | --- |
| 질문 | 실제 `/orders` 제출·취소만 담당하는 MatchingController를 OrderController로 바꿀지, 응답 DTO 이름까지 바꿀지 |
| AI 제안·근거 | OrderController로 변경하고 기존 DTO 이름·JSON 유지. HTTP 책임을 드러내면서 변경량을 줄임 |
| 사용자 답변 | “OrderController로 변경하고 기존 DTO 이름·JSON은 유지 (추천)” |
| 사용자 이유 | 별도 이유 답변 없음. AI의 근거를 사용자의 발언으로 옮기지 않음 |
| 결정·반영 | 이름·파일 변경표, 기존 HTTP/JSON 수용 사례, 제외 범위에 반영 |

중요한 제품 범위·거래 보장의 미결정은 현재 없다. 세 단위·운영 입력 연결·P06~P08 식별자는 AI의 구체 구현 추천안이며 사용자 선택처럼 기록하지 않는다. 기존 구조 준비 API와 실제 Kotlin 출력 연결은 운영 출력·최상위 변환 함수·일반 보조 코드·포트 구현 충돌 사례에서 확인했다. 관련 수용 사례로 확인하며 예상과 다른 경계가 나오면 논의한다.

사용자의 구현 요청에 따라 테스트 작성 → 기대값 검토 → 구현 → 검증을 연결했다. GitHub 이슈 본문·Projects·PR 갱신, CI 실행·머지는 이번 로컬 구현 완료와 구분한다. 다음 단계는 최종 검증 근거와 흐름 검토 후 PR 게시다.

</details>
