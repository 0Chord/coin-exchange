# 하나씩 읽는 이름 검사 · ARCH-05 구현 흐름

상태: **개별 규칙과 Bean 조립 구현 · 로컬 구조 317개 통과**. 2026-10-01, 브랜치 `feat/naming-placement-check/19`, 기준 `fa85119` 이후 변경이다. 전체 구조 회귀와 빌드가 통과했다. 원격 게시·CI·독립 리뷰·병합 상태는 [PR #32](https://github.com/0Chord/coin-exchange/pull/32)에서 별도로 확인한다.

## Service 이름과 자동 Bean 등록은 별개다

`OrderFundingService`라는 **이름은 허용**한다. 클래스에 `@Service`를 붙여 **자동으로 Bean을 등록하는 방식은 금지**한다. 업무 객체는 config의 `@Bean`에서 만들고 필요한 협력자를 연결한다. HTTP 객체의 `@RestController`·`@RestControllerAdvice`는 사용자 선택에 따라 유지한다.

이번 추가 흐름은 **운영 클래스 읽기 → 자동 등록 어노테이션 검사 + Bean 팩토리 선언 위치 검사 → 통과 또는 정확한 클래스·메서드 위반 보고**다. 역할 이름을 모르더라도 `@Component OrderWorker`는 잡는다. 정상 config와 HTTP 예외는 통과시키고, HTTP 클래스에 `@Service`까지 추가하면 위반을 보고한다.

| 예제 | 이번 추가 검사의 기대 결과 |
| --- | --- |
| 어노테이션 없는 서비스 + config의 `@Bean` | 통과 |
| `@Service CancelOrderUseCase` | 자동 등록 위반. 기존 이름 검사만 통과하는 것과 구분 |
| `@Component OrderWorker` / `@Repository` 저장 구현 | 자동 등록 위반 |
| Component를 포함하는 합성 어노테이션 | 자동 등록 위반. ArchUnit의 기본 기능으로 확인 |
| `@RestController` / `@RestControllerAdvice` | 기존 HTTP 자동 등록 유지 |
| `@RestController @Service` | Service 금지 위반 유지 |
| config 밖이나 Configuration 없는 클래스의 `@Bean` | 팩토리 선언 위치 위반 |

이 표의 클래스는 **검사기를 시험하는 예제**다. Spring 서버·DB를 실행하지 않는다. 현재 운영 코드에도 두 조립 규칙을 독립적으로 적용하며, 제품의 실제 DI 성공 여부를 실행 검증한 것으로 확대하지 않는다.

문답 기록: 사용자 “Bean으로만 DI 조립” → AI가 HTTP 객체 범위를 Ask → 사용자 “RestController, RestControllerAdvice는 그대로”. 업무 자동 등록을 금지하고 표준 HTTP·설정·시작점 예외를 유지하는 것으로 반영했다. 테스트 기대값 검토와 실행은 AI가 수행하며, 사용자가 코드를 검토 완료한 것으로 기록하지 않는다.

### 이번 규칙이 실제로 판단하는 순서

1. `P05`가 기존 수집기로 **운영 main 출력과 모듈 소속**을 읽는다. 누락·중복·빈 입력 또는 정책 오류가 있으면 준비 실패로 중단한다.
2. 모듈별로 `BeanAssemblyRules.noAutomaticBusinessRegistration()`을 실행한다. 클래스의 어노테이션마다 Component 계열인지 확인한다. 표준 HTTP·Configuration·Boot 어노테이션은 허용하고, 다른 Component 계열 어노테이션이 하나라도 있으면 그 클래스의 위반을 보고한다. 어노테이션 선언 자체는 Bean 객체가 아니므로 이 검사에서 제외한다.
3. 앞 검사가 통과하면 `configFactories()`로 Bean 메서드를 선택한다. 표준 `@Bean`과 이를 포함하는 합성 어노테이션 모두 해당한다. 선언 클래스가 app-api의 정확한 config package에 있고 `@Configuration`을 직접 사용했는지 확인한다. Bean 메서드가 없는 모듈은 이 팩토리 규칙의 대상 없음으로 허용한다.
4. 첫 규칙 위반에서 해당 `check()`가 실패하므로 P05는 전체 성공을 보고하지 않는다. 뒤의 규칙·모듈도 전부 평가했다고 표시하지 않는다. 끝까지 위반이 없을 때만 P05 성공을 출력한다.

읽을 코드는 [두 Bean 조립 규칙](../architecture-tests/src/test/kotlin/com/exchange/architecture/rules/BeanAssemblyRules.kt), [10개 기대값 테스트](../architecture-tests/src/test/kotlin/com/exchange/architecture/BeanAssemblyRuleTest.kt), [인위적 정상·위반 예제](../architecture-tests/src/test/kotlin/com/exchange/architecture/fixtures/beanassembly/BeanAssemblyFixtures.kt), [실제 운영 적용 P05](../architecture-tests/src/test/kotlin/com/exchange/architecture/ProductionArchitectureTest.kt)다. 새로운 역할 분류기·등록 목록·Spring 실행기는 만들지 않았다.

추가 테스트 10개와 실제 운영 검사 P01~P05 5개가 통과했다. 최종 전체 빌드에서도 구조 317개가 모두 실행·통과했다. P05는 운영 113개 타입을 검사했다. 준비용 미구현 규칙에서는 금지 사례 7개가 위반을 놓쳐 assertion 실패한 것을 먼저 확인했다. 그 실패는 환경·컴파일 오류가 아니라 아직 규칙을 구현하지 않아 금지 사례가 통과한 것이었다.

## 두 위치 검사는 왜 필요한가

사용자 질문: **“왜 위치 검사가 두 가지인지 설명이 더 필요해”**.

| 검사 | 확인할 사실 | Controller를 persistence에 옮긴 경우 |
| --- | --- | --- |
| 파일 검사 | 실제 부모 폴더가 허용 목록에 있는가? package가 그 폴더와 일치하는가? | package까지 persistence로 바꾸면 파일 검사는 통과한다 |
| Controller 위치 검사 | Controller가 `app-api`의 주문 HTTP 영역에 있는가? | persistence는 HTTP 영역이 아니므로 위치 위반이다 |

두 검사는 중복이 아니다. 파일 검사는 모든 원본에 적용하고, Controller 규칙은 역할 단서가 있는 타입에 적용한다. 반대로 package는 HTTP인데 실제 파일만 persistence로 옮기면 파일 검사가 불일치를 잡는다.

**문답 기록:** AI는 위 두 반례로 설명했다. 사용자가 추가 설명을 요청한 사실을 기록하며, 이해 완료나 승인으로 표시하지 않는다. 이 문답은 이미 허용한 구현을 계속하기 위한 재승인이 아니다.

## 이번 변경의 전체 지도

**Gradle이 main 전체 발견 → 입력 확인 → 개별 이름 규칙 / 실제 원본 폴더 검사 → 위반·준비 오류·대상 없음 보고**.

| 읽을 흐름 | 입력 → 판단 → 결과 | 실제 근거 |
| --- | --- | --- |
| Controller 한 묶음 | 전체 타입에서 이름 또는 표준 어노테이션 선택 → 이름·어노테이션·HTTP 위치 확인 → 대상명과 위반 보고 | NamingRules.controllers, NamingPlacementContractTest |
| 나머지 이름 규칙 | UseCase·Service·Config 등의 직접 단서 → 규칙마다 독립 검사 → 여러 규칙이 동시에 적용될 수 있음 | NamingRules, NamingPlacementRuleTest |
| Store·Publisher 관계 | 실제 직접/간접 포트 상속 → 포트 영역과 구현 영역 대조 → 정상·영역 위반·상위 정의 누락 | NamingPlacementPortTest |
| 원본 검사 | Gradle의 실제 main 파일 → package 구문 읽기와 정확한 경로 비교 → 폴더 위반 또는 읽기 준비 오류 | SourcePlacement, NamingPlacementIntegrationTest, SourcePlacementContractTest |
| 재실행·보고 | 원본·정책 변경 → Gradle Test 재실행 → ArchUnit 결과와 JUnit 보고서 | NamingPlacementGradleAutomaticTest, NamingPlacementGradleWiringTest |

이 변경은 **개발 검증 도구**다. 주문 제출·취소·DB 저장 동작은 바꾸지 않는다. 테스트의 Controller·Store는 정상/위반을 재현하는 예제이며 제품 기능을 추가한 것이 아니다.

## 먼저 완료한 Controller 흐름

1. 입력은 올바른 HTTP 폴더만이 아니라 **전체 운영 타입**이다.
2. `Controller` 접미사 또는 직접 표준 `Controller`/`RestController` 어노테이션이 있으면 선택한다.
3. 이름에 업무 대상이 있는지, 표준 어노테이션이 있는지, 모듈·package가 HTTP 영역인지 검사한다.
4. ArchUnit의 `evaluate`가 조건 위반을 모으고 `check`는 위반이 있으면 테스트를 실패시킨다.
5. 대상이 없으면 `targets=0`이다. 전체 입력이 비었다는 준비 오류와 구분한다.

| 실제 테스트 예제 | 기대와 실행 결과 |
| --- | --- |
| 정상 HttpOrderController | 선택되어 위반 없음 |
| RestController가 붙은 OrderEndpoint | 이름 위반. 잘못된 이름이라도 선택에서 빠지지 않음 |
| 어노테이션 없는 OrderController | 선언 조건 위반 |
| 업무 접두사가 없는 Controller | 이름 위반 |
| 다른 허용 폴더 / 다른 모듈 | 각각 package / module 위반 |
| 단서 없는 Helper·Kt·달러 이름 | 이름 규칙 대상 없음. 의미는 리뷰하고 파일 검사 유지 |
| Service 어노테이션만 있는 Manager | 이름 규칙에는 선택하지 않음. 별도 Bean 조립 검사에서는 자동 등록 위반 |
| extra 루트 추가 | Endpoint 이름 위반 유지 |
| Configuration과 RestController가 겹친 DualController | 양쪽 규칙 실행. 역할 충돌 하나로 나머지 검사 중단하지 않음 |
| 프로젝트 전용 합성 어노테이션 | 재귀 분류하지 않음 |
| 개별 ArchRule.check | 정상 통과, 이름 위반 반례에서 AssertionError와 대상 확인 |

기대값은 합의한 명세의 대상·위반 조건에서 가져왔다. 구현 반환값을 정답으로 복제하지 않았다. 테스트 검토는 같은 AI가 수행했으므로 독립 리뷰라고 부르지 않는다.

## Red에서 실제로 바뀐 계약

`NewHelper` 하나를 전달한 회귀 테스트는 기존 코드에서 `unclassified` 위반 때문에 실패했다. 준비나 컴파일 오류가 아니라 **기존 판단과 새 명세의 차이**가 확인된 Red다. 개별 규칙 구현 후 동일한 기대값이 통과했다.

자동 역할 추론을 없앤 결과, 단서 없는 이름을 자동 거절하는 보장은 제외된다. data/value/record의 의미 분류와 생성 타입 소유자·원본 연결 그래프도 만들지 않는다. 업무 책임과 이름의 적절성은 사람의 리뷰로 남긴다.

## 나머지 이름 규칙을 읽는 순서

같은 타입에 서로 다른 단서가 있으면 **각 이름 규칙을 모두 실행**한다. 먼저 맞은 하나의 역할로 나머지를 지우지 않는다. `@Service`만 붙은 Manager를 이름 검사에서 Service 역할로 추론하지 않으며, UseCase·Service·Coordinator 접미사는 자기 규칙의 단서다. **이름 검사를 통과해도 별도 Bean 조립 검사의 금지를 면제하지 않는다.** 기존 이름 테스트의 `@Service CancelOrderUseCase`는 이름·위치만 확인하는 예제이며, 새 Bean 조립 검사에 넣으면 금지 위반이다.

| 단서 | 실제 판단 | 관측할 결과 |
| --- | --- | --- |
| UseCase·Service·Coordinator 접미사 | 업무 접두사, 구체 클래스/object, 각 application 영역 | 이름·선언·모듈·package 위반을 각각 보고 |
| Calculator·Resolver 접미사 | 클래스/일반 인터페이스, fee 또는 order 코어 영역 | 다른 코어 모듈이라고 자동 허용하지 않음 |
| Config 이름 / 직접 Configuration | 이름, Configuration, config 영역 | 이름만 Config이면 어노테이션 없는 선언 위반 |
| 직접 SpringBootApplication | Application 이름과 앱 루트 | Boot의 표준 Configuration 의미는 Config 선택에서 제외 |
| 직접 Advice / Entity | ExceptionHandler / Entity 이름과 자기 영역 | 어노테이션 단서의 정상·틀린 이름·위치 검사 |
| Request·Response / ErrorResponse 접미사 | 각각 HTTP / 공통 오류 영역 | 클래스가 data인지 추론하지 않음. ErrorResponse는 일반 Response 규칙에서 분리 |

`data class DataUseCase`는 이번의 단순 선언 검사에서 일반 구체 클래스처럼 판단한다. 이전의 data/value/record 구분 보장은 의도적으로 제외했다. 이름이 실제 업무 책임에 맞는지는 리뷰한다.

근거: [개별 ArchRule 함수](../architecture-tests/src/test/kotlin/com/exchange/architecture/rules/NamingRules.kt), [정상·반례 테스트](../architecture-tests/src/test/kotlin/com/exchange/architecture/NamingPlacementRuleTest.kt).

## Store 관계: 이름의 의미 대신 실제 상속을 본다

**구현 타입 → 실제 내부 Store 포트 → 포트의 module/package → 대응 저장 영역 → 구현의 이름과 위치**.

`PostgresWalletStore`의 Wallet 단어로 ledger라고 추측하지 않는다. WalletStore가 domain-ledger의 허용 포트 영역에 있으면 정책의 `domain-ledger → ledger-persistence`를 따라간다. 간접 상속도 ArchUnit이 읽은 관계로 확인한다.

| 분기 | 실제 결과 |
| --- | --- |
| 같은 영역의 포트 둘 | 정상. 하나의 저장 영역으로 합침 |
| main/extra 루트의 서로 다른 위치 ID가 같은 module/package를 가리킴 | 정상. ID 개수 때문에 모호하다고 거절하지 않음 |
| 대응 위치 ID가 실제로 다른 module/package를 가리킴 | Store 영역 위반 |
| 이름만 Postgres…Store이고 내부 포트 구현이 없음 | 선언·구현 관계 위반 |
| 읽을 수 있는 포트가 허용 package 밖에 있음 | 포트 위치 위반 + 구현 매핑 위반. 임의의 정상 영역을 추측하지 않음 |
| 필요한 내부 포트·상위 정의 또는 영역 매핑 누락 | 준비 오류. 부분 위반 목록을 성공 증거로 사용하지 않음 |
| 외부 라이브러리의 Store/Publisher를 구현한 단서 없는 Adapter | 내부 포트 구현 대상으로 선택하지 않음 |

Publisher는 실제 내부 포트 관계를 확인하고 Persistent/NoOp 이름의 대응 위치를 검사한다. Repository는 접미사 또는 Spring Data 상속으로 선택한 뒤 **일반 인터페이스·실제 상속·Repository 이름·영속 위치**를 확인한다.

검증 중 외부 라이브러리의 포트를 내부 포트로 잘못 선택하는 반례를 추가했다. 수정 전 실제 assertion 실패를 확인했고, 내부 운영 입력에 있는 포트만 선택 근거로 사용하도록 고친 뒤 통과했다.

근거: [포트 영역 매핑](../architecture-tests/src/test/kotlin/com/exchange/architecture/policy/PortPlacementPolicy.kt), [동등 영역·외부 포트·간접 상속 테스트](../architecture-tests/src/test/kotlin/com/exchange/architecture/NamingPlacementPortTest.kt).

## 파일 검사와 타입 검사를 어떻게 합치는가

파일은 Gradle의 main 원본 목록에서 받는다. **정책 목록에서 파일을 찾아 만드는 방식이 아니다.** 각 파일의 구문을 읽어 package와 실제 부모 폴더를 비교하고, 소스 루트·정확한 허용 폴더·생산 작업도 확인한다. 클래스 없는 파일에도 적용한다.

- 허용하지 않은 `application/internal`에 새 파일: 폴더 위반.
- package는 그대로 두고 파일만 이동: 실제 폴더/package 불일치.
- 허용 목록에 있는 persistence에 package까지 맞춰 Controller 이동: 파일 조건 충족, Controller 위치 위반.
- 파일 삭제·구문 오류·소속 중복·외부 심볼릭 링크: 준비 오류.
- 유효한 전체 입력에 해당 이름 규칙의 대상이 없음: 그 규칙의 대상 0으로 보고. 전체 입력이 비었거나 필수 모듈이 누락된 것과 구분.

Kotlin의 익명·local·synthetic 타입과 파일 운반 kind 2/4/5만 이름 검사에서 제외한다. 이 필터를 기존 공통 수집기에 적용하지 않으므로 다른 ARCH의 의존·호출 검사 대상은 줄이지 않는다. named nested·companion은 자신의 이름 단서로 검사한다.

**제외한 보장:** 모든 컴파일 타입과 원본 파일의 일대일 연결, 다중 파일의 소유 그래프·metadata 본문 무결성. 대신 실제 원본 전체의 독립적인 폴더 검사를 유지한다. 두 허용 루트에 같은 파일명이 있어도 각각 실제 파일을 검사한다.

근거: [원본 검사](../architecture-tests/src/test/kotlin/com/exchange/architecture/support/SourcePlacement.kt), [두 검사 결합](../architecture-tests/src/test/kotlin/com/exchange/architecture/rules/NamingPlacement.kt), [위치 구분과 이동 테스트](../architecture-tests/src/test/kotlin/com/exchange/architecture/NamingPlacementIntegrationTest.kt), [입력·Kotlin 필터 테스트](../architecture-tests/src/test/kotlin/com/exchange/architecture/NamingPlacementScopeTest.kt).

## 변경되면 다시 검사되는가

실제 임시 Gradle 프로젝트에 소스만 추가하여 전체 출력 수집과 검사 재실행을 확인했다. 정상 AmendOrderUseCase는 새 대상에 포함된다. 단서 없는 OrderManager는 이름 검사 대상이 아니지만 원본 파일로 계속 검사된다. 접두사 없는 UseCase는 새 대상에 포함되고 이름 위반으로 테스트 작업이 실패한다.

폴더 정책만 바꾸면 다시 실행하여 새 package·허용 폴더 조건을 적용한다. 정책의 이유만 바꾼 경우도 다시 실행하여 이전 결과를 재사용하지 않는다. TestKit의 별도 Test 프로세스에서 같은 검사기를 호출하며, 여기서만 쓰는 예제 정책 상수를 운영 설정 언어로 도입하지 않는다.

근거: [실제 재실행과 새 대상](../architecture-tests/src/test/kotlin/com/exchange/architecture/NamingPlacementGradleAutomaticTest.kt), [소스 루트·생산 작업·파일 이동](../architecture-tests/src/test/kotlin/com/exchange/architecture/NamingPlacementGradleWiringTest.kt).

## 기존 PR 리뷰의 두 지적

- 동등한 Store 위치 ID를 모호하다고 거절한 지적은 **유지하는 계약의 결함 수정**이다. 동등 module/package는 합치고 다른 영역은 위반으로 검증했다.
- DATA/ANY 루트가 HTTP 데이터 이름 조건을 지운 지적은 **전체 DTO 추론을 제외하는 보장 변경**으로 처리했다. 원래의 모든 DATA/HTTP 규칙을 그대로 고쳤다고 주장하지 않는다. 대신 extra 루트를 추가해도 활성 Controller 이름 위반이 남는 회귀를 검증했다.

## 어떤 파일이 어떤 흐름에 속하는가

| 구분 | 파일과 책임 |
| --- | --- |
| 개별 검사 규칙 | NamingRules: 위 모든 이름·위치·실제 상속의 ArchRule 함수 |
| Bean 조립 검사 | BeanAssemblyRules: 업무 자동 등록 금지 + config Bean 팩토리 위치. ProductionArchitectureTest의 P05로 운영에 적용 |
| 입력·결과 | NamingPlacementScope: 모듈 소속, 정책 오류, 대상과 결과. NamingPlacement: 타입 검사와 원본 검사 결합 |
| 영역·폴더 정책 | PortPlacementPolicy, ProjectLayoutPolicy: 포트 매핑과 정확한 16개 폴더. 역할 목록은 제거 |
| 예제와 테스트 | NamingFixtures, AutomaticRoleFixtures: 고의 반례/정상 선언. NamingPlacementContract/Rule/Port/Scope/Integration/SimplificationTest와 SourcePlacementContractTest: 각 기대값 검증 |
| 조립 예제와 테스트 | BeanAssemblyFixtures, BeanAssemblyRuleTest: 정상 config·금지 어노테이션·HTTP 예외·합성 어노테이션·잘못된 팩토리 선언 |
| 실제 Gradle 테스트 | NamingPlacementGradleAutomatic/GradleWiring/GradleScenario: 새 소스·정책 변경과 결과 보고 |
| 빌드 | architecture-tests/build.gradle.kts: 기존 파서와 예제 의존성의 이유 설명을 현재 범위에 맞춤 |
| 제거 | NamingTypeFacts와 전체 자동 분류·metadata 그래프 테스트. 새로운 의미 분류기로 대체하지 않음 |
| 기록 | 상세 명세·이 흐름 문서·실행 근거. 이전 기록은 문서의 보존 영역에 남김 |

## 최종 실행 근거와 남은 범위

최종 명령: `./gradlew build --offline --continue`. **BUILD SUCCESSFUL**, 30개 작업 중 2개 실행·28개 UP-TO-DATE였다.

| 범위 | 결과 | 이번 실행 여부 |
| --- | --- | --- |
| ARCH-05 예제·회귀 | 79개 통과 | 기존 69개 + Bean 조립 예제 10개 |
| 운영 Bean 조립 | 1개 통과 | P05에서 현재 운영 113개 타입에 실제 적용 |
| 다른 구조 검사 | 237개 통과 | 동일 테스트 작업에서 실행. 해당 규칙·테스트의 코드는 변경하지 않음 |
| 제품 테스트 | 기존 243개 성공 기록 | 입력 변경이 없어 UP-TO-DATE. 이번에 DB 테스트를 재실행한 근거로 사용하지 않음 |
| 전체 빌드 | 성공 | 새 구조 검사 결과와 변경 없는 제품 산출물을 포함 |

실행한 구조 테스트는 실패·오류·skip 0개다. 빌드 시작 시 기록한 코드·빌드 입력 96개 해시가 종료 후 일치한다. 문서·HTML 갱신은 이후 설명 변경이며 테스트를 약화한 변경이 아니다. [명령·집계·소스 해시](architecture-05-verification.json)를 확인할 수 있다.

첫 Helper 회귀와 외부 포트 선택 반례는 수정 전 assertion 실패를 확인했다. 잘못 이름 붙인 Entity 반례는 테스트 예제 오류로 바로잡았으며 제품 결함의 Red로 세지 않는다. 테스트 기대값·완료 검토는 같은 AI의 명세 대조이며 독립 PR 리뷰로 표시하지 않는다.

#19/#32는 검사기와 예제 검증을 완료했고, Bean 조립 검사는 현재 운영 코드에도 활성화했다. #20~21에서 실제 운영 이름·폴더 이행과 나머지 ARCH-05 활성화를 한다. 이름의 업무 의미와 책임 이동은 사람의 리뷰가 필요하다. Nebula·PIT·SonarQube·Trivy는 이번 작업에 추가하지 않았다.

## 실제 코드와 테스트

아래 코드는 현재 작업 파일이다. 큰 파일은 필요한 함수만 먼저 찾아 읽고, 위 흐름과 연결한다.

<!-- ARCH05_IMPL_SOURCES_START -->
<!-- ARCH05_IMPL_SOURCES_END -->

<!-- ARCH05_REVIEW_BEFORE_SIMPLIFICATION -->
# 등록 없이 새 코드를 검사하는 흐름 · ARCH-05

상태: **자동 분류 구현 · 로컬 검증 완료**. 브랜치 `feat/naming-placement-check/19`, 비교 기준 `3250fd2`의 개정 명세다. 클래스별 등록 입력을 제거했다. 현재 코드와 최종 실행 근거를 연결했다. 원격 CI·독립 리뷰·병합 상태는 [PR #32](https://github.com/0Chord/coin-exchange/pull/32)에서 별도로 확인한다.

## 이번에 달라진 것

예전에는 새 클래스를 만들면 검사 설정에도 클래스 이름·역할·위치를 써야 했다. 이제는 **제품 소스만 추가하면 기존 공통 규칙이 자동 검사한다.** `NamingFixtures.kt`에 실제 제품 클래스를 복제할 필요도 없다.

| 새로 추가한 코드 | 판단 | 결과 |
| --- | --- | --- |
| 정상 위치의 `AmendOrderUseCase` | UseCase 이름과 일반 실행 클래스 형태 | 자동 포함, 통과 |
| `OrderManager` | 공통 역할을 정할 근거가 없음 | `unclassified` 위반 |
| `data class FooUseCase` | UseCase 이름인데 데이터 선언 | `roleShape` 위반 |
| `@RestController FooUseCase` | Controller와 UseCase 근거가 충돌 | `roleConflict` 위반 |
| 읽을 수 없는 클래스·원본·필요한 메타 정보 | 판정에 필요한 입력이 불완전 | 준비 오류, 준수 판정 중단 |

**파일 이름 하나하나를 맞추는 정답표는 없다.** Calculator를 Resolver로 바꾼 것이 업무상 옳은지, 두 허용 영역 사이에서 책임을 옮긴 것이 맞는지는 리뷰한다. 형식·위치가 맞는 변경을 의미 위반까지 잡는다고 주장하지 않는다.

## 전체 흐름

**전체 클래스·원본 발견 → 입력 확인 → 역할 분류 → 이름·위치·원본 비교 → 통과·위반·준비 오류 보고**

| 읽을 단위 | 구체적인 입력 → 판단 → 결과 | 실제 코드 |
| --- | --- | --- |
| 새 파일 자동 포함 | Gradle main 출력 전체를 읽음 → 기존 목록과 별개로 발견 → 새 클래스도 검사 | MainSourceSnapshot, ProductionScopeImporter, main-sources.gradle.kts |
| 역할 판단 | 접미사·선언 형태·상속·어노테이션을 함께 읽음 → 모든 역할 신호 비교 → 하나면 검사, 충돌/미분류는 위반 | RoleNamingPlacement, NamingTypeFacts |
| 허용 위치 | 자동 역할 → 공통 폴더 정책 → 모듈·package·소스 루트 비교 | NamingClassificationPolicy, ProjectLayoutPolicy |
| Kotlin 보조 타입 | 실제 생성 관계·다중 파일 part 확인 → 소유자 또는 모든 원본 연결 | NamingTypeFacts, NamingPlacement |
| 파일 검사 | 실제 소스 파싱 → package와 정확한 부모 폴더 비교 → 이동·미허용 경로 탐지 | SourcePackageParser, SourcePlacement |
| 다시 실행 | 소스·폴더 정책·명명 정책 입력 변경 → Gradle Test 재실행 → 변경된 결과 | NamingPlacementGradleAutomaticTest |

제품 주문 처리·DB 저장 코드는 바뀌지 않았다. 검사기는 파일과 바이트코드를 읽고, 테스트는 임시 프로젝트의 예제를 컴파일한다. 제품 객체를 생성하거나 제품 메서드를 실행하지 않는다.

## 1. 새 클래스가 생겼을 때

`AmendOrderUseCase.java`만 임시 프로젝트에 추가한다. Gradle이 컴파일한 **출력 폴더 전체**를 기존 수집기가 읽는다. 검사기는 `UseCase` 접미사를 발견하고 일반 실행 클래스인지 확인한다. 허용된 module/package/원본 폴더면 통과하며 보고서의 평가 목록에 새 클래스가 남는다.

동일한 방식으로 `OrderManager.java`를 추가하면 읽기는 성공하지만 분류가 안 된다. 그래서 준비 오류가 아니라 **읽은 코드의 규칙 위반**으로 실패한다. 파일을 못 읽는 상황은 별도 준비 오류다. 이 구분 때문에 ‘아무것도 검사하지 않고 위반 0개’인 결과를 통과로 보지 않는다.

근거: [자동 추가 기대값](../architecture-tests/src/test/kotlin/com/exchange/architecture/NamingPlacementAutoClassificationTest.kt), [실제 Gradle 소스 추가](../architecture-tests/src/test/kotlin/com/exchange/architecture/NamingPlacementGradleAutomaticTest.kt), [별도 Test 프로세스의 검사 호출](../architecture-tests/src/test/kotlin/com/exchange/architecture/NamingPlacementGradleScenario.kt).

## 2. 이름만 바꿔서 역할을 속일 수 있는가

검사기는 먼저 맞은 규칙 하나로 끝내지 않는다. 업무 접미사와 기술 어노테이션·상속의 근거를 전부 모은다.

- `@Service CancelOrderUseCase`: UseCase로 분류한다. Spring Service 어노테이션 자체는 위치를 허가하지 않는다.
- `@Service OrderManager`: 별도 역할 근거가 없으므로 미분류다.
- `UseCase.Result`가 data class: Result는 DATA로 독립 분류한다. 바깥 이름을 물려받지 않는다.
- 이름만 `Request`인 일반 클래스: 실제 데이터 구조가 없으므로 DATA로 면제하지 않는다.
- data/value/record/enum: 내부 작업·영속·도메인 데이터 위치를 검사한다. HTTP 데이터에는 Request/Response, 오류 데이터에는 ErrorResponse 이름 기준을 더 적용한다.
- domain-*의 일반 코어: 새 Domain 접미사를 강제하지 않는다. UseCase 같은 예약 역할 신호가 있으면 일반 코어로 숨기지 않는다.

근거: [판단 코드](../architecture-tests/src/test/kotlin/com/exchange/architecture/rules/RoleNamingPlacement.kt), [구조와 이름 반례](../architecture-tests/src/test/kotlin/com/exchange/architecture/NamingPlacementAutoRolesTest.kt), [명명 조건과 한계](../architecture-tests/src/test/kotlin/com/exchange/architecture/NamingPlacementContractTest.kt).

## 3. 저장·발행 구현의 소속은 어떻게 아는가

`PostgresWalletStore`라는 이름에서 Wallet의 뜻을 추측하지 않는다. **실제 구현한 Store 인터페이스의 모듈**을 확인한다. ledger의 포트이면 정책에서 ledger 저장 위치로 연결한다.

| 입력 | 결과 |
| --- | --- |
| 같은 영역의 Store 포트 둘 구현 | 하나의 저장 역할로 합침 |
| order·ledger 포트 동시 구현 | 영역 충돌 |
| Postgres 이름은 맞지만 Store 포트 구현이 없음 | 선언 형태 위반 |
| 포트 정의를 읽을 수 없음 | 준비 오류 |
| 포트가 틀린 폴더에 있음 | 구현 관계는 유지하고 포트 자신의 위치 위반 보고 |
| `JpaRepository` 실제 상속 인터페이스 | Repository 역할. 이름뿐인 Repository는 형태 위반 |
| Persistent/NoOp 발행 구현 | 포트 구현과 각 접두사의 지정 위치 모두 확인 |

근거: [위치 정책](../architecture-tests/src/test/kotlin/com/exchange/architecture/policy/ProjectLayoutPolicy.kt), [공통 이름 정책](../architecture-tests/src/test/kotlin/com/exchange/architecture/policy/NamingClassificationPolicy.kt), [포트·영역 테스트](../architecture-tests/src/test/kotlin/com/exchange/architecture/NamingPlacementPortRolesTest.kt).

## 4. 보이지 않는 Kotlin 클래스도 검사하는가

컴파일러가 만드는 companion·익명·lambda 보조 타입은 실제 메타데이터와 소유 관계가 있어야 소유자에게 연결한다. 소유자가 없으면 준비 오류다. 이름에 `Kt`나 `$`가 들어갔다는 이유로 제외하지 않는다.

최상위 함수와 `@file:JvmName`은 파일 메타데이터로 분류한다. 다중 파일 함수 묶음은 facade 하나의 임의 원본을 고르지 않고 **모든 part와 원본 파일**을 확인한다. part 하나가 없거나 다른 모듈 소속이면 판정을 중단한다. Boot 시작 위치의 함수는 같은 원본에 시작점이 있는지도 확인한다.

메타데이터·상위 계층·내부 합성 어노테이션 정의를 못 읽으면 일반 클래스라고 추측하지 않는다. 외부 라이브러리 전체를 대상에 포함하거나 제품을 실행하는 방식은 사용하지 않는다.

근거: [원본·메타 정보 읽기](../architecture-tests/src/test/kotlin/com/exchange/architecture/support/NamingTypeFacts.kt), [생성 소유 관계](../architecture-tests/src/test/kotlin/com/exchange/architecture/NamingPlacementScopeTest.kt), [다중 파일·Java record·합성 어노테이션](../architecture-tests/src/test/kotlin/com/exchange/architecture/NamingPlacementMetadataTest.kt).

## 5. 폴더는 어디까지 허용하는가

ProjectLayoutPolicy의 정확한 경로 16개를 유지한다. 새 하위 폴더나 새 source root는 자동 허용하지 않는다. 새 경로가 필요하면 해당 위치의 **공통 역할·이유**를 추가한다. 타입별 등록으로 돌아가지 않는다.

Kotlin PSI/JDK 파서로 실제 package를 읽어 원본 부모 폴더와 비교한다. 파일을 옮겼지만 package가 같아도 탐지한다. typealias처럼 클래스 파일이 없는 원본도 보존한다. 한 파일에 여러 타입이 있어도 같은 파일 위반을 반복하지 않는다.

같은 역할·package에 소스 루트 두 개를 명시하면 둘 다 허용한다. 다른 역할에만 허용한 루트는 우회 경로가 아니다. 원본 후보가 없거나 둘이면 임의 선택 없이 준비 오류다.

근거: [소스 비교](../architecture-tests/src/test/kotlin/com/exchange/architecture/support/SourcePlacement.kt), [최종 결과 결합](../architecture-tests/src/test/kotlin/com/exchange/architecture/rules/NamingPlacement.kt), [컴파일 원본 연결 테스트](../architecture-tests/src/test/kotlin/com/exchange/architecture/NamingPlacementIntegrationTest.kt), [구문·경로 테스트](../architecture-tests/src/test/kotlin/com/exchange/architecture/SourcePlacementContractTest.kt).

## 6. 정책만 고치면 이전 통과 결과를 재사용하는가

main 소스의 경로·내용뿐 아니라 정책 Kotlin 파일도 Gradle Test의 입력이다. TestKit은 실제 Test 작업에 현재 검사기 코드를 연결한다. 소스만 추가하거나 정책 값만 바꾼 뒤, 재실행 여부와 **결과의 새 대상·위반 항목**까지 대조한다. 단순히 입력 목록이 바뀌었다는 것만 검사하지 않는다.

예제 정책을 수정했을 때 이름 기준만 바꾼 경우는 이름 위반, 폴더만 바꾼 경우는 위치 위반이어야 한다. 클래스별 정답 목록을 새로 작성하지 않는다.

근거: [실제 Test 작업 검증](../architecture-tests/src/test/kotlin/com/exchange/architecture/NamingPlacementGradleAutomaticTest.kt), [main 전달·생성 소스 검증](../architecture-tests/src/test/kotlin/com/exchange/architecture/NamingPlacementGradleWiringTest.kt), [Gradle 입력](../architecture-tests/gradle/main-sources.gradle.kts).

## 기대값 검토와 실행 근거

- 첫 AUTO-01/02/03/24는 기존 등록 기반 코드에서 **4개 모두 assertion 실패**했다. 등록 목록 없이 정상 검사할 수 없었던 것이 원인이다. API 제거 뒤 같은 기대값이 통과했다.
- 역할 경계 9개 중 8개가 최소 구현에서 assertion 실패했다. 선언 형태·기술 역할·데이터·도메인 분류를 구현한 뒤 통과했다. 어노테이션만으로 허용하지 않는 기존 1개는 이미 통과했다.
- 이어서 포트·Kotlin 생성·원본·실제 Gradle 추가 사례를 확인했다. 이미 동작하는 경계에 인위적인 실패를 만들지 않았다.
- 기대값은 명세의 항목·타입·허용 경로에서 작성했다. 구현의 분류 결과를 정답 생성기로 사용하지 않았다. 입력 불완전과 읽은 코드의 위반을 분리했다.
- TestKit 설정 스크립트의 `java` 이름 충돌은 테스트 환경 구성 오류로 수정했다. 이것을 제품 동작의 Red 근거로 세지 않는다.

최종 명령은 `./gradlew build --no-daemon --continue --stacktrace --rerun-tasks`다. **30개 작업을 실제 재실행해 551개 테스트가 모두 통과**했다. 실패·오류·건너뛴 테스트는 0개다.

| 검증 범위 | 통과 | 확인한 내용 |
| --- | ---: | --- |
| ARCH-05 | 71 | 자동 포함·역할·포트·메타 정보·원본 연결·실제 Gradle 재실행 |
| 다른 구조 검사 | 237 | 기존 ARCH 규칙의 회귀 |
| 제품 테스트 | 243 | 기존 도메인·API·DB 통합 테스트 |
| 전체 | 551 | 기본 환경에서 전체 build, Docker DB 테스트 포함 |

전체 실행을 시작할 때 기록한 구조 검사 소스 95개의 해시와 종료 후 코드가 일치한다. 이후 변경은 설명과 실행 기록뿐이다. [실행 근거](architecture-05-verification.json)에 명령·집계·테스트 묶음·소스 해시를 남겼다. 이전 등록 기반 구현의 525개 기록은 [이전 커밋](https://github.com/0Chord/coin-exchange/blob/1a5e37722c629c8dcc8f3551a3dddcfa46ce53e0/engineering/architecture-05-verification.json)에 보존되며 이번 통과 근거에 합산하지 않았다.

## 어디까지 끝내는 작업인가

#19에서 검사기와 예제를 구현하는 범위다. **운영 코드의 이름·폴더 이동과 ARCH-05 활성화는 #20~21**이다. 다른 ARCH 규칙의 등록 체계 전체를 교체하지 않는다.

새 클래스는 기존 규칙으로 자동 검사한다. 새 역할·기술·정확한 경로를 도입할 때는 공통 정책과 해당 반례를 함께 바꾼다. 역할의 실제 업무 책임과 이름의 의미는 사람이 리뷰한다. 이 변경의 테스트 통과를 사람의 이해·독립 PR 리뷰·병합 완료로 표시하지 않는다.

## 변경 파일과 실제 원문

| 구분 | 변경과 책임 |
| --- | --- |
| 검증 도구 | NamingPlacementScope, NamingTypeFacts, RoleNamingPlacement, NamingPlacement: 전체 입력·분류·원본 연결·결과 |
| 공통 정책 | NamingClassificationPolicy, ProjectLayoutPolicy: 이름 조건·포트 영역·정확한 위치 |
| 테스트 | NamingPlacement*Test, SourcePlacementContractTest: 위의 각 흐름과 반례 |
| 테스트 도우미 | NamingPlacementGradleScenario: 임시 프로젝트의 Test 프로세스에서 실제 검사기 호출 |
| 예제 | naming 폴더의 NamingFixtures, AutomaticRoleFixtures, NamedFunctions, MultiOne, MultiTwo: 정상·위반·생성 구조 |
| 빌드 | architecture-tests/build.gradle.kts: 실제 Boot/Spring Data 예제 의존성·TestKit 실행 클래스패스. main-sources.gradle.kts는 기존 기반 재사용 |
| 설명 | 이 문서·상세 명세·실행 근거. 로컬 HTML은 이 문서와 실제 소스를 렌더링 |

<!-- ARCH05_IMPL_SOURCES_START -->
HTML에서는 다음 위치에 현재 원문을 문법 색상·줄 번호·내용 해시와 함께 표시한다.
<!-- ARCH05_IMPL_SOURCES_END -->
