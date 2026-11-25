# ARCH-04 · 업무 코드가 저장 구현에 묶이지 않게 하기

**상태: 로컬 구현·검증 완료.** 기준 통합 `07d4e0501dc5de7bbc9f6709eaa04ae22eb7741c`, 브랜치 `test/application-boundary/19`. 로컬 검증 당시 소스 해시를 실행 기록에 보존한다. 게시 커밋과 원격 CI·리뷰·병합 상태는 PR 본문에서 확인한다.

## 먼저 볼 세 가지 결과

이전에는 업무 코드의 의존 경계를 검사하는 ARCH-04가 없었다. 이제 **유즈케이스와 내부 작업이 어떤 타입을 직접 사용하는지** 읽고 아래 세 결과를 구분한다. config에서 구현체를 만들어 포트로 연결하는 현재 방식은 유지한다.

| 입력 예시 | 판단 | 결과 |
| --- | --- | --- |
| 업무 → 저장 포트. config → 저장 구현 생성 → 업무에 주입 | 업무는 저장 계약만 알고, 구현 선택은 config가 맡음 | 예제 검사 통과 |
| 업무 → 구체 저장 구현, HTTP DTO 또는 config | 업무가 기술 구현·웹 표현·조립 책임에 직접 묶임 | ARCH-04 위반과 실제 출발·대상 표시 |
| 새 업무 파일이 역할 목록에 없음, 파일 손상, 업무/config 역할 충돌 | 검사 입력이 온전하지 않음 | 미평가. 위반 목록과 참조 수를 비우고 중단 |

**위반 예제를 잡으면 테스트는 통과한다.** 이것은 검사기의 판정 검증이다. 실제 주문 코드를 이 검사에 연결하는 작업은 #20–21이며, #19의 나머지 규칙까지 완료된 것은 아니다.

## 전체 흐름 지도

| 읽는 순서 | 시작 → 주요 분기 → 결과 | 연결된 코드·테스트 |
| --- | --- | --- |
| 1. 검사 준비 | 컴파일 출력 수집 → 실제 파일/가져온 목록 대조 → 역할 누락·충돌 확인 → 오류면 중단 | ProductionScopeImporter 재사용, ApplicationBoundaryScope, ScopeTest·ImportTest |
| 2. 역할에 따른 방향 판단 | 업무 출발점 선택 → 도메인·포트·협력자·실행기 계약 허용 / 구현·HTTP·config 금지 | ApplicationImplementationIndependence, ContractTest |
| 3. 외부 기술과 우회 판단 | 직접 참조의 DB·HTTP·컨테이너 여부 확인 → 필요한 상속을 못 읽으면 미평가 | ApplicationTechnologyPolicy, ContractTest·ImportTest |
| 4. 실제 참조와 보고 | 필드·서명·보조 코드·생성 코드·메서드 참조 → 관측한 출발/대상/위치 → 정렬한 결과 | ReferenceTest, 진단 자료 ArchitectureViolation 재사용 |

검사기는 예제를 실행하지 않고 컴파일된 코드를 읽는다. 예제에 `save`, `getBean`, SQL이 있어도 DB 저장·Bean 조회는 수행하지 않는다. 상태 변경이나 외부 요청을 새로 넣은 제품 코드는 없다.

## 1. 빠진 코드가 있으면 통과시키지 않는다

컴파일 출력과 별도로 지정한 발견 패키지·역할 목록을 받는다. `SubmissionService`와 `FundingService`는 업무, `Assembly`는 config, `FundsPort`는 포트로 등록한다. 이 이름은 테스트용 예제이며 제품 클래스의 개명이 아니다.

1. 기존 수집기가 실제 `.class` 목록과 읽은 타입 목록을 비교한다. 필수 파일 없음·손상·중복·읽기 실패는 원인과 대상을 남긴다.
2. 새 업무 helper나 설정 타입이 발견됐는데 등록이 없으면 준비 오류다. config 패키지 밖의 실제 `@Configuration`과 합성 설정 어노테이션도 대조한다. 어노테이션 선언 자체는 조립 객체로 세지 않는다.
3. 한 타입을 업무와 조립으로 동시에 등록하거나 업무 타입에 설정 어노테이션을 붙여 숨기면 오류다. 실제 중첩 타입도 바깥 타입의 역할을 이어받으며 충돌을 확인한다.
4. 업무와 config가 직접 참조한 내부 타입의 정의·역할도 필요하다. config가 사용할 저장 구현을 빠뜨린 경우도 통과하지 않는다.
5. 오류가 있으면 의존 검사를 시작하지 않는다. config만 있고 업무 대상이 0개인 경우도 통과할 수 없다.

**기대값 근거:** 명세 APP-01·07·13·14. 정상 조립과 누락·충돌은 서로 다른 결과여야 한다. 빈 입력을 ‘위반 0’으로 통과시키면 새 코드가 검사에서 빠져도 모르게 된다.

근거: [대상 준비 코드](../architecture-tests/src/test/kotlin/com/exchange/architecture/support/ApplicationBoundaryScope.kt), [역할 기대값 테스트](../architecture-tests/src/test/kotlin/com/exchange/architecture/ApplicationBoundaryScopeTest.kt), [실제 파일 수집 테스트](../architecture-tests/src/test/kotlin/com/exchange/architecture/ApplicationBoundaryImportTest.kt).

## 2. config의 생성과 업무의 사용을 구분한다

준비를 통과한 뒤 **모든 업무 타입**을 검사 출발점으로 잡는다. 유즈케이스뿐 아니라 내부 Service·Coordinator·helper도 포함한다. config는 별도로 개수를 표시하고 업무 의존 판정 출발점에서는 제외한다.

| 직접 참조 | 왜 이 결과인가 |
| --- | --- |
| `SubmissionService → FundingService → FundsPort` | 업무가 협력자·포트를 사용하는 정상 조율이므로 허용 |
| `FundingService → FundsCalculator → Reserved` | 도메인 계산과 결과 사용이므로 허용 |
| 업무 → `PostgresFundsStore`, JPA 구현, NoOp 발행 구현, Repository·영속 모델 | 구체 기술 역할에 직접 의존하므로 금지. 이름을 `FakeUseCase`로 바꿔도 같은 결과 |
| 업무 → 요청/응답 DTO·컨트롤러·응답 매퍼·예외 처리기 | HTTP 표현을 업무 계층에 가져오므로 금지 |
| config → `PostgresFundsStore()` → `FundsPort`로 업무에 주입 | config의 조립 책임이므로 허용 |
| 업무 → config의 필드·Bean 메서드·메서드 참조 | 업무가 조립 객체에 역으로 의존하므로 금지 |
| 업무 → 포트 → 실제 DB 구현 | 업무의 직접 참조는 포트까지다. 구현의 DB 작업을 업무 위반으로 전파하지 않음 |

`@Service`가 있어도 이 허용표를 건너뛰지 않는다. 이것은 검사기의 판정 기준이며, **제품의 config·@Bean 조립을 컴포넌트 스캔으로 바꾼다는 뜻이 아니다.**

**기대값 근거:** APP-01~05·07·12. 테스트는 금지 대상의 정확한 타입 쌍을 명시하고 정상 포트 호출도 함께 확인한다. 단순히 위반 목록이 비어 있지 않다는 것만으로 모든 경계를 확인했다고 보지 않는다.

근거: [방향 판단 코드](../architecture-tests/src/test/kotlin/com/exchange/architecture/rules/ApplicationImplementationIndependence.kt), [정상·위반 판정 테스트](../architecture-tests/src/test/kotlin/com/exchange/architecture/ApplicationBoundaryContractTest.kt), [실제 config 조립 예제](../architecture-tests/src/test/kotlin/com/exchange/architecture/fixtures/applicationboundary/config/Assembly.kt).

## 3. 외부 라이브러리와 컨테이너 우회도 구분한다

프로젝트 역할과 함께 명세의 외부 기술 목록을 대조한다. JDBC/JPA·DB 드라이버 계열과 HTTP 계열 직접 사용은 금지하고, `@Transactional`, DI 메타데이터, URI·컬렉션·Future 같은 일반 지원 타입은 허용한다. 정상 트랜잭션 어노테이션 때문에 Spring 전체를 금지하지 않는다.

`ApplicationContext`나 `BeanFactory`로 구현을 직접 찾는 방향도 금지한다. 이름이 다른 `GenericApplicationContext` 또는 사용자 인터페이스도 상위 타입을 따라 컨테이너 계약을 구현하는지 확인한다. 이름·역할만 바꾼다고 허용되지 않는다.

상속을 읽다 필요한 타입 정의가 없으면 “컨테이너가 아니다”라고 추정하지 않는다. `UNRESOLVED_TYPE_HIERARCHY` 준비 오류를 반환하고, 그 전에 발견한 부분 위반도 최종 결과에서는 비운다. 실제로 존재하지 않는 상위 타입을 가진 바이트코드로 이 경계를 확인한다.

**기대값 근거:** APP-02·06·08. 실제 Spring·JDBC·JPA·HTTP 타입을 읽는다. 프레임워크 패키지를 흉내 낸 가짜 타입으로 통과를 증명하지 않는다. 없는 상위 타입은 미해석 상황을 만들기 위한 예제다.

근거: [외부 기술 정책](../architecture-tests/src/test/kotlin/com/exchange/architecture/rules/ApplicationTechnologyPolicy.kt), [라이브러리 참조 예제](../architecture-tests/src/test/kotlin/com/exchange/architecture/fixtures/applicationboundary/application/PolicyExamples.kt), [상속 누락 테스트](../architecture-tests/src/test/kotlin/com/exchange/architecture/ApplicationBoundaryImportTest.kt).

## 4. 호출이 없어도, helper 안에 있어도 검사한다

직접 참조에는 메서드 호출뿐 아니라 필드·인자·반환·배열·제네릭·상속·어노테이션 값이 포함된다. 사용하지 않는 구현체 필드도 같은 경계를 위반한다.

| 경우 | 검사와 보고 방식 |
| --- | --- |
| 유즈케이스 → helper → 저장 구현 | helper도 업무 출발점이므로 실제 저장 참조가 있는 helper에서 보고 |
| 부모 클래스·최상위 확장 함수 속 저장 호출 | 부모와 파일 파사드(최상위 함수를 담은 컴파일 클래스)를 역할에 등록해 그 본문을 검사 |
| 중첩·익명 클래스·람다 | 실제 포함 관계를 따라 역할을 연결. `$` 이름이나 생성 코드라는 이유로 통째로 제외하지 않음 |
| 포트의 메서드 참조 / 구현체의 메서드·생성자 참조 | 포트는 허용, 구현체는 금지. Kotlin과 Java로 실제 만들어진 호출/참조를 확인 |
| 행 번호 없는 반환 선언 | 파일과 타입은 표시하지만 행 번호는 `null`. 호출자의 줄을 임의로 붙이지 않음 |
| 입력 순서가 달라짐·동일 참조 반복 | 중복을 제거하고 동일한 순서로 보고 |
| 위반과 준비 오류가 함께 있음 | 준비 오류를 우선해 미평가. 부분 위반·검사 참조 수를 성공 근거로 남기지 않음 |

**기대값 근거:** APP-09~11·15. Java 참조 예제의 저장 포트·구현 메서드·생성자 참조는 실제 12·15·18행에 존재한다. 포트 12행은 허용하고 구현 15·18행을 위반 근거로 확인한다. Kotlin은 컴파일러가 참조를 호출로 바꿀 수 있어 실제 바이트코드에 나온 위치와 대조한다.

근거: [참조와 진단 테스트](../architecture-tests/src/test/kotlin/com/exchange/architecture/ApplicationBoundaryReferenceTest.kt), [Kotlin 우회 예제](../architecture-tests/src/test/kotlin/com/exchange/architecture/fixtures/applicationboundary/application/ReferenceExamples.kt), [JVM 참조 예제](../architecture-tests/src/test/java/com/exchange/architecture/fixtures/applicationboundary/application/JavaReferences.java).

## 테스트 작성·검토·실행 기록

- 준비 단위: 미구현 상태의 12개가 기대 어설션에서 실패 → 구현 후 12개 통과.
- 판정 단위: 11개 중 7개가 미구현 판정 때문에 실패 → 구현 후 11개 통과. 처음부터 통과한 정상 사례를 일부러 깨지 않았다.
- 우회·진단·파일 경계: 추가한 16개도 기존 구현으로 통과해 39개를 확인했다. 이 단계는 새 Red 증거라고 부르지 않는다.
- 구현 결과 검토에서 실제 출력의 위반 판정·config-only 사례 2개를 추가했다. 신규 41개 전체가 통과했다.
- 기대값은 명세의 역할 표와 구체 반례에서 정했다. 같은 AI가 테스트 의도를 대조한 단계이며 독립 PR 리뷰를 수행한 것은 아니다.

**전체 실행 결과: 480개 통과, 실패·오류·skip 0.** `./gradlew build --no-daemon --continue --console=plain --rerun-tasks`가 성공했고 30개 작업을 모두 재실행했다. 구조 검사 237개(기존 196 + 신규 41), 제품 테스트 243개다. Docker 29.6.2가 동작하는 환경에서 기존 app-api 테스트도 실행했다.

컴파일러가 예제의 `Qualifier` 적용 대상에 대한 미래 변경 경고를 내어, 현재 동작과 동일하게 생성자 인자(`@param:`)로 명시했다. 그 한 줄 수정 후 ARCH-04 41개를 다시 실행했다. 다른 제품 코드는 바뀌지 않았으며 전체 집계·단계별 명령·검증 당시 소스 해시는 [실행 기록](architecture-04-verification.json)에 보존한다. 로컬 XML/HTML 테스트 보고서는 마지막 선택 실행의 41개를 표시할 수 있다.

## 테스트별 확인 범위와 남은 한계

| 사례 | 주된 검증 위치 | 확인하는 것 |
| --- | --- | --- |
| APP-01·07 | ScopeTest·ContractTest·ImportTest | 정상 역할과 config 조립 허용, 수집부터 판정까지 연결 |
| APP-02·03·04·05·06·08·12 | ContractTest, 상속 누락은 ImportTest | 역할·기술별 허용/금지와 포트 뒤 구현 경계 |
| APP-09·10·11·15 | ReferenceTest, 준비 실패는 ContractTest | 호출/선언/생성 코드의 실제 참조와 안정적인 진단 |
| APP-13·14 | ScopeTest·ImportTest | 미등록·충돌·외부 역할 오등록·손상·누락·중복·빈 입력 |

이번 검사는 **타입에 나타나는 직접 의존**을 보장한다. config에 실제 업무를 숨겼는지, 도메인 판단을 Service에 재구현했는지, 역할 등록의 의미가 맞는지는 리뷰로 판단한다. 리플렉션 문자열·모든 외부 I/O 라이브러리·임의 서비스 검색 구현을 전수 검사하지 않는다. DB 원자성·트랜잭션 프록시·롤백·실행 순서도 구조 검사로 증명하지 않는다.

## 변경 파일의 역할

<details markdown="1"><summary>파일 목록과 읽을 이유</summary>

| 분류 | 파일 | 연결된 흐름 |
| --- | --- | --- |
| 검증 도구 | [ApplicationBoundaryScope.kt](../architecture-tests/src/test/kotlin/com/exchange/architecture/support/ApplicationBoundaryScope.kt) | 1. 준비와 역할 |
| 검증 도구 | [ApplicationImplementationIndependence.kt](../architecture-tests/src/test/kotlin/com/exchange/architecture/rules/ApplicationImplementationIndependence.kt) | 2·4. 의존 판단과 보고 |
| 검증 도구 | [ApplicationTechnologyPolicy.kt](../architecture-tests/src/test/kotlin/com/exchange/architecture/rules/ApplicationTechnologyPolicy.kt) | 3. 외부 기술·상속 |
| 공통 자료 | [ScopeContracts.kt](../architecture-tests/src/test/kotlin/com/exchange/architecture/support/ScopeContracts.kt) | 새 준비 오류 4종 추가 |
| 테스트 | ApplicationBoundaryScopeTest·ContractTest·ReferenceTest·ImportTest | 위 네 흐름의 기대값·실제 입력 검증 |
| 테스트 예제 | Contracts.kt·NormalWorkflow.kt·Assembly.kt | 정상 도메인·포트·업무·구현·조립 역할 |
| 테스트 예제 | OutsideConfiguration.kt·PolicyExamples.kt | 발견 패키지 밖 설정, 외부 기술, 의도한 금지 참조 |
| 테스트 예제 | ReferenceExamples.kt·WorkflowFunctions.kt·JavaReferences.java | 선언·helper·생성 코드·메서드 참조 |
| 테스트 빌드 | [build.gradle.kts](../architecture-tests/build.gradle.kts) | 실제 Transactional 예제의 spring-tx 의존을 테스트 전용으로 명시 |
| 문서·근거 | [상세 명세](architecture-check-spec.md), 이 가이드, 실행 기록 | 합의·구현·검증 연결 |
| 로컬 표시 | reader/build.py·template.html·index.html | 기존 HTML에 자연어 흐름과 색상·줄 번호가 있는 원문 표시. 원격 PR 파일과 별개 |

제품 실행 코드·기존 SQL·설정 조립·API는 변경하지 않았다. 기존 수집기는 수정하지 않고 재사용한다. 새 코드의 전체 목록은 아래에서 펼칠 수 있다.

</details>

<!-- ARCH04_IMPL_SOURCES_START -->
HTML에서는 현재 원문·줄 번호·내용 해시를 함께 제공한다.
<!-- ARCH04_IMPL_SOURCES_END -->
