# 공통 개발 컨벤션

2026-10-01 기준. 합의한 책임·이름·배치·문서화 기준을 정리한다. 코드 대조 기준은 통합 브랜치 `feature/phase-2/integration`의 `4e3fa45`다. 제품 코드·검사 코드를 이번 문서 작업에서 변경하지 않았다.

목표 기준과 운영 적용 상태는 다르다. 현재 적용표·실행 근거·후속 책임은 [#19 마무리 명세](architecture-check-spec.md), 정상·실패 순서는 [개발·주문 흐름](flow-and-scope-contract.md)에서 확인한다.

## 책임과 유지할 계약

| 책임 | 기준 | 확인·후속 작업 |
| --- | --- | --- |
| 순수 도메인 | 금액·수수료·예약·주문 판단은 전달받은 값과 상태로 수행한다. Spring·웹·DB 구현에 의존하거나 외부 I/O를 수행하지 않는다 | ARCH-01 운영 검사. 판단이 실제로 도메인에 있는지는 의미 리뷰 |
| 애플리케이션 | 필요한 상태를 준비하고 도메인 판단·예약·매칭·저장·정산 순서를 조율한다 | ARCH-04 예제 검증. 실제 적용은 #20~21 |
| HTTP API | 요청·응답을 변환하고 업무 진입점을 호출한다. 저장 구현·정산 협력자를 직접 호출하지 않는다 | ARCH-03 예제 검증. 실제 적용은 #20 |
| 인프라 | 포트를 DB 기술에 연결한다. 의도한 조건부 SQL·잠금·트랜잭션 계약을 보존한다 | 타입 계약은 ARCH-06 운영 검사. 실제 저장은 #21~22 |
| 불변 상태 | `Balance`·`OrderReservation` 등의 전이는 도메인이 검사하고 새 객체를 반환한다. 원본 유지와 전이 불변식을 확인한다 | 도메인 테스트와 #22. 복사 자체를 일괄 금지하지 않는다 |
| 가변 주문장·실행기 | 매칭 상태 판단과 큐·스레드·콜백 실행 책임을 구분한다. 모든 객체에 불변을 강제하지 않는다 | 상태 소유·접근·실패 후 진행은 #23 |

도메인에 `Store` 인터페이스를 선언하는 것과 도메인 판단 중 DB를 호출하는 것은 다르다. 필요한 외부 상태는 애플리케이션이 준비한다. 실행기의 콜백이 예약·저장 작업을 호출해도 순수 매칭 엔진의 외부 호출을 허용한다는 뜻은 아니다.

[PostgresBalanceStore](../app-api/src/main/kotlin/com/exchange/core/api/ledger/persistence/PostgresBalanceStore.kt)의 조건부 `UPDATE … RETURNING`은 갱신 조건 확인과 변경 직후 잔고 반환을 한 SQL로 처리하려는 설계다. 이를 읽기→수정→별도 조회로 교체하지 않는다. 생성 시 값 검증과 상태 전이 검증, DB에서의 동시 갱신 보장은 각각 구분한다.

## 이름을 맞추는 기준

이름은 역할을 드러내고 같은 개념에는 같은 용어를 쓴다. 아래는 목표 명명 기준이며 기존 Service 파일이 이미 UseCase로 변경됐다는 뜻은 아니다.

| 역할 | 이름 기준·예 | 의미 경계 |
| --- | --- | --- |
| 외부에서 호출하는 업무 진입점 | 동작+대상+`UseCase`: `SubmitOrderUseCase`, `CancelOrderUseCase` | 하나의 목적을 조율하는 진입점 |
| 내부 예약·정산 작업 | 책임+`Service`: `OrderFundingService`, `TradeSettlementService` | 유즈케이스가 호출하는 협력 작업. 자동 Bean 등록과는 무관 |
| 실행 연결 | 대상+`Coordinator`: `MatchingCoordinator` | 실행 순서를 연결하는 책임. 기존 실행기의 `Processor`와 구분 |
| 계산 | 대상+`Calculator`: `TradingFeeCalculator` | 계산 결과 산출 |
| 기준에 따른 선택 | 대상+`Resolver`: `FeeTierResolver` | 등급·정책 선택. 계산기와 억지로 같은 이름을 쓰지 않음 |
| 저장 포트 | 대상+`Store`: `OrderReservationStore` | 우리 코드가 요구하는 저장 계약 |
| 저장 포트 구현 | 기술+대상+`Store`: `PostgresBalanceStore`, `JpaMatchingEventStore` | SQL·JPA 구현과 변환 |
| Spring Data 인터페이스 | 대상+`Repository`: `MatchingEventRepository` | 저장 구현 안에서 사용하는 프레임워크 도구. JDBC에 추가할 의무 없음 |
| 발행 포트·구현 | 대상+`Publisher`, 구현 책임을 앞에 표시 | 저장 발행과 NoOp의 보장은 다름 |

‘내부 협력자’는 호출 관계에서의 역할이다. 인터페이스 구현체만을 뜻하지 않는다. 새 역할이 필요하면 책임과 기존 역할과의 차이를 설명한다.

| 개념 | 공통 용어 | 구분할 것 |
| --- | --- | --- |
| 주문 제출 / 취소 | `submit` / `cancel` | 제출과 접수 성공, 취소와 자금 해제 |
| 자금 예약 / 해제 | `reserve` / `release` | `funding`은 확보 작업, `reservation`은 주문별 기록 |
| 체결 정산 | `settle` / `settlement` | 매칭의 가격·수량 결정 |
| 계산 / 선택 | `calculate` / `resolve` | 수치 계산과 정책·등급 선택 |
| 사용 가능 / 예약 잔고 | `available` / `hold` | 사용자 잔고와 주문별 예약 기록 |

ARCH-05는 이름·어노테이션·상속·포트 구현 관계로 확인 가능한 개별 규칙을 검사한다. 일반 `OrderManager`를 이름만으로 거절하지 않는다. 허용 파일 위치는 검사하고, 업무 책임·정확한 이름·동일 의미 여부는 리뷰한다. 전체 타입의 의미를 추론하거나 새 클래스마다 역할 등록을 요구하지 않는다.

다만 모듈의 소속과 일부 포트·실행기 경계는 [ProductionScope](../architecture-tests/src/test/kotlin/com/exchange/architecture/support/ProductionScope.kt)에 유지한다. 새 모듈·포트·실행 경계가 생기면 관련 수집·역할 기준을 검토한다. 이 목록은 일반 클래스의 이름 등록표가 아니다.

## DI는 config에서 명시적으로 조립한다

- 업무 객체는 `@Service`, `@Component`, `@Repository` 또는 이들을 합성한 자동 등록 어노테이션을 쓰지 않는다. 클래스 이름에 `Service`가 들어가는 것은 허용한다.
- 업무 Bean은 `app-api`의 `com.exchange.core.api.config`에서 직접 `@Configuration`을 붙인 클래스의 `@Bean` 메서드로 선언한다. 반환 타입만 보고 허용하지 않는다.
- 기존 HTTP `@RestController`·`@RestControllerAdvice`는 유지한다. 검사기는 표준 `@Controller`·`@ControllerAdvice`, `@Configuration`, `@SpringBootApplication`도 명시한 프레임워크 예외로 구분한다. 합성 자동 등록이 이 예외를 무제한 확장하는 것은 아니다.
- 트랜잭션 메타데이터와 실제 Spring 프록시·주입 성공은 구분한다. 구조 검사는 선언을 확인하며 컨텍스트를 띄워 조립을 실행하지 않는다.

[BeanAssemblyRules](../architecture-tests/src/test/kotlin/com/exchange/architecture/rules/BeanAssemblyRules.kt)는 현재 운영에 적용돼 있다. 실제 조립 예는 [OrderApplicationConfig](../app-api/src/main/kotlin/com/exchange/core/api/config/OrderApplicationConfig.kt), [LedgerPersistenceConfig](../app-api/src/main/kotlin/com/exchange/core/api/config/LedgerPersistenceConfig.kt)에서 읽는다.

## 목표 폴더와 확장 방법

`app-api`에는 API·application·infrastructure와 조립 코드가 있고, 도메인 모듈은 Kotlin 코어를 둔다. 허용 루트·폴더의 기준은 [ProjectLayoutPolicy](../architecture-tests/src/test/kotlin/com/exchange/architecture/policy/ProjectLayoutPolicy.kt) 한 곳이다. 아래는 현재 정책의 목표 위치이며, 운영 코드 이동·전체 폴더 검사 활성화는 #20~21에서 수행한다.

모든 루트는 현재 `src/main/kotlin`이다. 표의 경로는 그 루트 아래이며 정확한 폴더를 허용한다. 상위 폴더를 허용했다는 이유로 임의 하위 폴더가 허용되지는 않는다. `package` 선언과 실제 폴더도 일치해야 한다.

| 모듈 | 허용 폴더 | 책임 |
| --- | --- | --- |
| app-api | `com/exchange/core` | 앱 시작점 |
| app-api | `com/exchange/core/api/config` | 명시적 Bean 조립 |
| app-api | `com/exchange/core/api/common` | 공통 HTTP 오류 |
| app-api | `com/exchange/core/api/order/api` | 주문 HTTP·DTO |
| app-api | `com/exchange/core/api/order/application` | 주문 진입점·내부 작업 |
| app-api | `com/exchange/core/api/order/infrastructure/persistence` | 주문 예약 저장 |
| app-api | `com/exchange/core/api/ledger/infrastructure/persistence` | 잔고·원장 저장 |
| app-api | `com/exchange/core/api/matching/application` | 매칭 실행 연결 |
| app-api | `com/exchange/core/api/matching/application/port` | 매칭 저장·발행 계약 |
| app-api | `com/exchange/core/api/matching/infrastructure/persistence` | 매칭 영속화 |
| app-api | `com/exchange/core/api/matching/infrastructure/publish` | 미저장 발행 구현 |
| domain-common | `com/exchange/core/common` | 공통 값 |
| domain-fee | `com/exchange/core/fee` | 수수료 코어 |
| domain-order | `com/exchange/core/order` | 주문·예약·정산 코어와 저장 포트 |
| domain-ledger | `com/exchange/core/ledger` | 잔고·원장 코어와 저장 포트 |
| domain-matching | `com/exchange/core/matching` | 매칭 코어와 명시한 실행기 경계 |

새 폴더·생성 소스 루트가 필요하면 정책에 정확한 경로·역할·이유를 추가하고 정상/금지 폴더 사례를 대조한다. 소스 코드만 옮기거나 넓은 wildcard 예외를 추가해 우회하지 않는다. 파일 검사는 클래스 없는 파일도 확인하며, 개별 역할의 위치 검사는 별도로 적용한다.

예를 들어 Controller를 허용된 persistence 폴더에 package까지 맞춰 옮기면 파일·package 일치는 통과할 수 있지만 Controller 위치 규칙은 실패한다. 같은 ‘위치’여도 파일 체계와 역할 배치라는 서로 다른 계약이다.

## 직접 모듈 의존 방향

현재 [ARCH-02 정책](../architecture-tests/src/test/kotlin/com/exchange/architecture/rules/ModuleDependencyDirection.kt)은 코드 직접 참조와 Gradle 직접 프로젝트 선언을 모두 확인한다. 같은 모듈 내부를 제외한 허용 목적지는 다음과 같다.

| 출발 모듈 | 허용 목적지 |
| --- | --- |
| domain-common | 없음 |
| domain-fee | domain-common |
| domain-order | domain-common, domain-fee |
| domain-ledger | domain-common |
| domain-matching | domain-common, domain-order |
| app-api | 위 도메인 모듈 전체 |

테스트·벤치마크와 명시한 외부 테스트 도구의 운영 역의존은 ARCH-08이 별도로 검사한다. 전이 의존·동적 로딩 전체나 라이브러리의 업무 의미를 검사한다고 해석하지 않는다.

## 주석·학습 설명과 예외

설명 주석은 한국어로 쓰고 코드·외부 API 식별자는 원래 표기를 유지한다. Kotlin 호출 계약은 필요할 때 KDoc으로 쓴다. 줄마다 동작을 번역하거나 모든 메서드에 형식적인 `@param`을 채우지 않는다.

| 처리 | 남기거나 바꿀 정보 |
| --- | --- |
| 유지 | 판단 이유, 값의 단위·반올림·불변식, 동시성·트랜잭션, 실패 후 남는 상태 |
| 축약 | 이름·코드를 그대로 반복하거나 같은 계약을 여러 곳에서 되풀이하는 설명 |
| 문서 이동 | 긴 배경·대안 비교·여러 클래스의 전체 흐름. 저장소 문서 링크를 남김 |
| 수정 | 실제 코드와 달라진 설명. 설명을 현재 동작에 맞추되 동작 결함을 숨기지 않음 |

수·길이를 줄이는 것을 완료 기준으로 삼지 않는다. timeout과 작업 취소가 다르다는 설명처럼 나중에 판단을 복원하는 근거를 보존한다. 실제 주석 일괄 정리는 #24에서 진행한다.

예외는 규칙·대상·이유·재검토 조건을 좁게 적는다. 전체 패키지 제외로 통과시키지 않는다. 현재 운영 미적용 규칙은 [적용표](architecture-check-spec.md)에 활성화 티켓을 연결하며, 준수한 것처럼 기록하지 않는다. 별도 형식 도구, PITEST·SonarQube·보안 도구 도입은 이 컨벤션의 완료 선행 조건이 아니다.

## 개발자가 판단할 근거

AI는 코드·테스트·설명을 만들고 실행 결과를 수집한다. 개발자는 목적·책임·허용할 실패 보장을 판단한다. 대표 정상·실패 사례에서 자연어 흐름을 핵심 코드까지 추적하고, 조건을 바꿨을 때 결과가 어떻게 달라지는지 확인한다. HTML을 열거나 테스트가 Green인 것만으로 사람의 이해·승인을 기록하지 않는다.

예전 전체 클래스 역할·배치표는 당시 이동 참고자료다. 이번 문서는 최신 기준을 고정하며, 그 표를 전 클래스 자동 등록표로 복원하지 않는다. 전체 표 재조사 질문은 직접 답변이 없었고, 이번 문서 정리 구현에 추가 완료 조건으로 넣지 않았다. 실제 이동 때 #20~21에서 대상 파일을 최신 코드와 대조한다.
