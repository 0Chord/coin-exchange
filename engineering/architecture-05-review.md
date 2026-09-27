# 이름과 폴더를 검사하는 흐름 · ARCH-05

> **개정 명세 안내:** 이 문서는 `1a5e377`의 **클래스별 등록 기반 구현**을 설명한다. [최신 상세 명세](architecture-check-spec.md)는 클래스별 등록을 없애고 공통 규칙으로 자동 분류하는 변경을 정의한다. 개정 구현·테스트는 아직 시작하지 않았다. 아래 525개/12개 실행 결과는 이전 구현의 기록이다.

상태: **로컬 구현·검증 완료 · 신규 45개 · 전체 빌드 525개 통과**. 기준 `f511105002c6581e8e4b44b94e549bc9d736685d`, 브랜치 `feat/naming-placement-check/19`에서 검증했다. 아래 결과는 로컬 실행 기록이며, 최신 CI·리뷰·병합 상태는 이 변경의 PR에서 확인한다. 테스트 통과를 사람의 검토 완료로 표시하지 않는다.

## 먼저 읽을 세 가지

1. **이름:** 주문 제출 역할로 등록한 타입이 `OrderSubmissionService`이면 기대한 `SubmitOrderUseCase`와 다르다고 보고한다.
2. **위치:** package가 맞아도 파일만 엉뚱한 폴더에 있거나, 서로 일치하지만 허용하지 않은 폴더라면 실패한다.
3. **누락:** 새 타입의 역할 등록이 없거나 파일을 읽지 못하면 일부만 검사한 뒤 통과시키지 않는다. `검사 준비 오류`로 중단한다.

이제 위 세 가지를 검사하는 도구가 있다. **아직 실제 거래 코드의 이름·폴더를 옮기거나 운영 ARCH-05를 켠 것은 아니다.** #19에서는 고의로 틀린 예제를 잡는지 확인했고, #20~21에서 실제 이동과 함께 같은 검사기에 운영 대상의 역할·위치를 연결한다.

## 전체 흐름

| 읽기 단위 | 어디서 시작하고 무엇으로 끝나는가 | 정상과 실패의 차이 |
| --- | --- | --- |
| 등록에서 빠진 코드 찾기 | 기존 클래스 수집 결과 → 명시한 역할과 소유 관계 → 검사할 타입 확정 | 미등록 타입·중복 역할·빈 업무 대상은 준비 오류 |
| 역할별 이름과 위치 판단 | 타입·역할·정책 → 이름·모듈·package 비교 → 항목별 위반 | `Service`와 `UseCase` 역할을 이름에서 추측하지 않음 |
| 허용 폴더 목록 적용 | 원본 경로·수정 가능한 정책 → 정확한 루트·부모 폴더 비교 | 목록에 없는 하위 폴더도 실패 |
| 원본 package 읽기 | Kotlin PSI 또는 JDK Java 파서 → 실제 선언 → 폴더와 비교 | 주석 속 가짜 선언은 무시, 구문 오류는 준비 오류 |
| 두 입력 연결 | 읽은 클래스의 SourceFile·모듈·package → 원본 후보 대조 | 후보 0개/복수이면 잘못된 입력으로 중단 |
| 변경 후 다시 검사 | Gradle의 main 파일·경로·정책 입력 → 파일 이동·정책 수정 → 재실행 | 이전 통과 결과를 그대로 재사용하지 않음 |

검사 과정은 소스·바이트코드를 읽는다. 제품의 주문 처리·DB 저장·외부 API를 실행하거나 변경하지 않는다. Gradle 연결 테스트는 임시 프로젝트를 만들고 Java 예제를 컴파일한다.

## 1. 새 코드가 목록에서 빠졌는가

**입력 → 판단 → 결과:** 운영 수집기에서 `SubmitOrderUseCase`와 `NewHelper`를 읽는다 → 역할 목록에는 제출 타입만 있다 → `NewHelper`의 역할을 모르므로 준비 오류를 반환한다 → 이름 검사의 위반 목록·평가 대상은 비운다.

- `NewHelper`가 옳은 폴더에 있어도 등록 누락이다. 이름이나 어노테이션으로 역할을 자동 승인하지 않는다.
- 이름 있는 중첩 `Result`는 데이터 역할로 따로 등록한다. 바깥 타입이 UseCase라고 Result에도 UseCase 접미사를 강제하지 않는다.
- companion은 실제 Kotlin 메타데이터, 익명 클래스는 enclosing 관계를 확인한 경우에만 등록된 소유자에 귀속한다. 이름에 `$`나 `Kt`만 들어간 타입은 자동 제외하지 않는다.
- 최상위 함수의 운반 타입과 `@file:JvmName` 타입도 구체 이름·이유·위치를 등록한다.

근거: [대상 준비 코드](../architecture-tests/src/test/kotlin/com/exchange/architecture/support/NamingPlacementScope.kt), [준비·생성 타입 테스트](../architecture-tests/src/test/kotlin/com/exchange/architecture/NamingPlacementScopeTest.kt). 특히 `NamingPlacementScope.prepare`의 미등록 대상 처리와 준비 오류 반환을 보면 판단을 확인할 수 있다.

## 2. 역할에 맞는 이름과 위치인가

예를 들어 제출 유즈케이스의 등록값은 `역할=USE_CASE`, `기대 이름=SubmitOrderUseCase`, `기대 위치=order-application`이다. 이 위치의 정책에서 `app-api`와 `com.exchange.core.api.order.application`을 얻는다.

| 실제 입력 | 판단 결과 |
| --- | --- |
| 맞는 이름·모듈·package | 타입 검사 통과 |
| `OrderSubmissionService` | 이름 위반. 실제 값과 기대한 `SubmitOrderUseCase`를 보고 |
| 올바른 이름을 infrastructure에 둠 | package 위반 |
| 다른 도메인 모듈로 옮김 | module 위반 |
| 세 항목 모두 틀림 | 항목별 3건. 파일 행 번호를 임의로 만들지 않음 |

Store 포트·기술 Store 구현·Spring Data Repository도 등록한 역할을 구분한다. 저장 구현의 `Postgres`·`Jpa`는 검토한 기술 목록이며 임의 접두사를 자동 허용하지 않는다. config는 조립 역할이어도 이름·위치 검사를 받는다.

근거: [이름·위치 판단](../architecture-tests/src/test/kotlin/com/exchange/architecture/rules/RoleNamingPlacement.kt), [역할별 기대값 테스트](../architecture-tests/src/test/kotlin/com/exchange/architecture/NamingPlacementContractTest.kt). 역할 자체가 실제 업무 책임에 맞는지는 코드 리뷰에서 판단해야 한다.

## 3. 허용 폴더를 추가하거나 제거하면 어떻게 되는가

`ProjectLayoutPolicy.target`에 모듈·소스 루트·정확한 폴더·허용 역할·이유를 관리한다. 앱 시작점과 공통 HTTP 오류 위치에는 구체 타입 제한도 있다. **현재 운영 코드가 이미 이 목표 위치라는 뜻은 아니다.**

원본 파일을 전부 받은 뒤 목록과 대조한다. 허용 폴더만 검색해서 금지 폴더를 놓치는 방식이 아니다.

- `order/application` 허용: 그 위치는 통과하지만 `order/application/internal`은 자동 허용하지 않는다.
- 나중에 `order/application/cancel`이 필요함: 목록에 그 경로와 역할을 추가하고 해당 타입의 위치 연결을 변경한다. 검사 코드는 그대로다.
- 목록에서 경로 제거: 그곳에 남은 파일은 다시 위반이다.
- 중복 경로, 없는 모듈·위치, 빈 역할, 모순된 타입 제한: 정책 준비 오류다. 모르는 역할 enum 이름은 정책 코드 컴파일 단계에서도 거절된다.

package와 실제 폴더가 일치해도 미등록 경로면 `allowedFolder` 위반이다. Gradle에 새 root를 등록한 것만으로 허용되지는 않으며 `sourceRoot`로 보고한다.

근거: [수정할 정책 파일](../architecture-tests/src/test/kotlin/com/exchange/architecture/policy/ProjectLayoutPolicy.kt), [소스 폴더 비교](../architecture-tests/src/test/kotlin/com/exchange/architecture/support/SourcePlacement.kt), [추가·삭제 반례](../architecture-tests/src/test/kotlin/com/exchange/architecture/SourcePlacementContractTest.kt).

## 4. 파일 위치와 package는 같은가

파일 `src/main/kotlin/com/example/order/Submit.kt` 안에 `package com.example.order.application`이 있다고 가정한다.

1. 소스 루트를 뺀 부모 폴더는 `com/example/order`다.
2. 파서가 읽은 package의 단어는 `com`, `example`, `order`, `application`이다.
3. 기대 폴더 `com/example/order/application`과 다르므로 `sourceFolder` 위반을 보고한다.

여기서는 `.class`의 폴더를 쓰지 않는다. 컴파일러는 원본이 잘못 놓여도 package에 맞춰 `.class`를 만들 수 있기 때문이다.

| 경계 사례 | 처리 |
| --- | --- |
| 주석·문자열 속 `package wrong.path` | 실제 선언으로 쓰지 않음 |
| 파일 어노테이션·이스케이프 식별자 | Kotlin 구문대로 읽음 |
| Java 파일 | JDK 파서로 package와 구문 오류를 읽음 |
| package 없는 파일 | 루트 바로 아래와 비교. 허용 폴더 정책은 별도 적용 |
| typealias·최상위 함수·package만 있는 파일 | 바이트코드 타입 수와 관계없이 원본 폴더 검사 |
| 여러 타입을 한 파일에 선언 | 파일당 폴더 항목을 한 번 보고 |
| 파일 부재·구문 오류·루트 중복·심볼릭 링크 탈출 | 준비 오류. 잘못 읽은 입력으로 준수 판단을 하지 않음 |

근거: [실제 구문 파서](../architecture-tests/src/test/kotlin/com/exchange/architecture/support/SourcePackageParser.kt), [소스 준비·비교](../architecture-tests/src/test/kotlin/com/exchange/architecture/support/SourcePlacement.kt), [구문·파일 경계 테스트](../architecture-tests/src/test/kotlin/com/exchange/architecture/SourcePlacementContractTest.kt).

**기술 선택:** 프로젝트와 같은 Kotlin 2.3.21의 compiler-embeddable PSI를 검사 모듈의 테스트 의존성으로만 사용했다. 환경 생성 API는 K1Deprecation opt-in이 필요하므로 그 호출에 한정해 표시했다. 타입 해석이나 실행은 하지 않는다. 컴파일러 버전을 바꿀 때 파서 계약 테스트도 함께 확인해야 한다. JDK 25에서 파서 라이브러리의 Unsafe 사용 경고가 있지만 테스트 실패는 아니다.

## 5. 소스와 클래스가 같은 대상인가

타입 검사와 파일 검사 각각이 정상이어도, 예전 클래스 출력과 다른 원본이 섞일 수 있다. 최종 `NamingPlacement.inspect`는 클래스의 모듈·package·SourceFile로 원본 후보를 찾는다.

- 후보 1개: 그 타입에 지정한 소스 루트까지 대조한 뒤 두 검사 결과를 합친다. 같은 package를 가진 다른 허용 루트라도 그 타입의 지정 위치가 아니면 `sourceRoot` 위반이다.
- 후보 0개: 원본 누락 또는 package 불일치이므로 준비 오류다.
- 후보 2개: 같은 이름의 소스가 두 루트에 있어 모호하므로 준비 오류다.
- 어느 준비 단계든 오류 있음: `evaluated=false`, 위반·평가 타입·평가 파일 목록을 비운다.

근거: [최종 결과 결합](../architecture-tests/src/test/kotlin/com/exchange/architecture/rules/NamingPlacement.kt), [실제 컴파일과 원본 연결 테스트](../architecture-tests/src/test/kotlin/com/exchange/architecture/NamingPlacementIntegrationTest.kt). 소스가 모두 `.class` 하나씩을 만든다는 가정은 하지 않는다.

## 6. 파일이나 정책을 고치면 다시 실행되는가

`main-sources.gradle.kts`가 기존 운영 모듈 목록을 사용해 각 모듈의 Java·Kotlin **main** 소스 설정을 읽는다. 허용 목록과 독립적으로 모듈·루트·실제 파일을 전달한다. 소스 경로·내용·정책 파일을 Test 작업 입력으로 등록한다.

- 변화가 없으면 Gradle이 이전 결과를 재사용할 수 있다.
- 파일 이동·추가·삭제, 정책 변경은 검사 입력을 바꾸므로 다시 실행한다.
- test·JMH·resources는 운영 원본 목록에 넣지 않는다.
- 생성 소스는 실제 생산 작업을 확인하고, 정책의 루트·생산 작업과 대조한다.
- 비어 있는 기본 Java 루트는 비활성이다. Java 파일을 넣으면 명시적 루트 허용이 필요하다.

실제 Gradle TestKit 임시 프로젝트에서 변화 전 `UP_TO_DATE`, 변화 후 `SUCCESS`와 바뀐 입력·위반을 확인했다. 이 저장소 6개 모듈의 Kotlin main 원본 전달도 확인했다. **이 연결 확인 테스트가 운영 ARCH-05 준수 검사인 것은 아니다.**

근거: [Gradle 수집·입력](../architecture-tests/gradle/main-sources.gradle.kts), [전달 형식 읽기](../architecture-tests/src/test/kotlin/com/exchange/architecture/support/MainSourceSnapshot.kt), [실제 Gradle 재실행 테스트](../architecture-tests/src/test/kotlin/com/exchange/architecture/NamingPlacementGradleWiringTest.kt).

## 명세와 테스트 기대값

| 명세 사례 | 테스트 파일 · 확인하는 결과 |
| --- | --- |
| NAME-01~07·10 | NamingPlacementContractTest: 역할별 정상, 이름·package·module 위반과 대상·기대 값 |
| NAME-08~09 | NamingPlacementScopeTest와 ContractTest: 도메인/앱 포트·조립·HTTP·발행의 목표 위치와 이름 구분 |
| NAME-11~16 | NamingPlacementScopeTest와 ContractTest: 중첩/companion/익명/JvmName, 위장·누락·역할 충돌·손상·지원 이유 |
| NAME-17~22 | SourcePlacementContractTest, NamingPlacementIntegrationTest: Kotlin/Java 원본, classless·복수 선언, 실제 폴더·구문·원본 연결 |
| NAME-23 | NamingPlacementGradleWiringTest: 실제 main 전달, 파일/정책 변경, 생성 작업 |
| NAME-24~28 | SourcePlacementContractTest와 Integration/ScopeTest: 정확한 목록, 루트 우회, 잘못된 역할, 정책 추가·삭제·오류 |

기대값은 명세의 구체 이름·경로·오류 항목에서 작성했다. 구현 함수로 기대값을 만들지 않았다. 위반 예제를 정확하게 찾으면 **테스트는 성공**한다. 리뷰는 동일 AI의 별도 검토 단계이며 독립 컨텍스트의 PR 리뷰나 사람의 리뷰를 대신하지 않는다.

첫 이름 검사 9개는 빈 결과를 반환하는 초기 구현에서 모두 실패했다. 폴더 검사 14개도 미구현 결과에서 실패했다. 최종 검토에서 같은 package의 다른 허용 소스 루트로 이동하는 반례를 추가했으며, 6개 연결 테스트 중 그 1개만 실패하는 것을 확인한 뒤 루트 비교를 보완했다. 이후 연결·생성 타입 반례는 기존 동작이 충족하면 인위적으로 실패시키지 않고 회귀 근거로 추가했다. 정상 예제로 DATA만 넣었던 테스트는 업무 대상이 필요하다는 명세와 맞지 않아 수정했다. macOS 경로 표기 차이도 실제 경로로 비교하도록 테스트를 보완했다.

## 실행 근거

- 전용 명령: `./gradlew :architecture-tests:test --tests '*NamingPlacement*Test' --tests '*SourcePlacement*Test' --no-daemon --console=plain`
- 첫 전용 실행은 **44개, 실패·오류·skip 0**. 이후 소스 루트 반례 1개를 추가한 **ARCH-05 45개**가 전체 빌드에서 통과했다.
- 전체 회귀: `./gradlew build --no-daemon --continue --stacktrace --rerun-tasks` **성공(5분, 30개 작업 재실행)**. 구조 검사 **282개**, 제품 테스트 **243개**, 합계 **525개**, 실패·오류·skip 모두 0. 첫 실행은 기존 PostgreSQL 통합 테스트의 IPv4 인증 응답 대기로 중단했다. 같은 포트에 IPv4 접속은 타임아웃, IPv6는 즉시 응답함을 확인해 이번 실행에만 `JAVA_TOOL_OPTIONS=-Djava.net.preferIPv6Addresses=true TESTCONTAINERS_HOST_OVERRIDE=localhost`를 적용했다. 저장소 DB 설정과 테스트 기대값은 바꾸지 않았다.
- 전체 빌드 후 목표 정책의 도메인별 역할 목록을 명세 표에 맞춰 좁혔다. `NamingPlacementScopeTest` **12개**를 다시 통과시켰다. 검사 로직·제품 코드 변경은 없으며, 전체 빌드 당시 해시와 최종 해시를 실행 근거에 각각 보존했다.
- [실행 근거와 소스 해시](architecture-05-verification.json)에서 명령·집계를 확인할 수 있다.
- HTML 원문 발췌는 생성 시점의 스냅샷이다. 실행 근거의 해시와 함께 확인하며 자동 최신화된다고 가정하지 않는다.

## 변경 파일이 맡은 일

| 구분 | 파일 · 흐름 |
| --- | --- |
| 검증 도구 | NamingPlacementScope.kt · 타입·역할·소유자 준비(1) |
| 검증 도구 | RoleNamingPlacement.kt · 이름·모듈·package 비교(2) |
| 검증 정책 | ProjectLayoutPolicy.kt · 목표 폴더·역할 목록과 정책 오류 검사(3) |
| 검증 도구 | SourcePackageParser.kt, SourcePlacement.kt · 원본 읽기와 위치 판정(3~4) |
| 검증 도구 | NamingPlacement.kt · 원본 연결과 최종 결과(5) |
| 빌드·입력 | main-sources.gradle.kts, MainSourceSnapshot.kt, build.gradle.kts · Gradle 입력 전달·테스트 전용 파서 의존(6) |
| 테스트 | NamingPlacementContractTest, ScopeTest, IntegrationTest, GradleWiringTest, SourcePlacementContractTest · 위 사례 표 |
| 테스트용 예제 | NamingFixtures.kt, NamedFunctions.kt · 올바른/틀린 이름과 생성 타입·운반 타입의 실제 컴파일 형태 |
| 명세·설명 | architecture-check-spec.md, 이 문서, architecture-05-verification.json · 계약·흐름·실행 근거 |
| 별도 로컬 읽기 화면(PR 제외) | 기존 reader/build.py·index.html · 이 문서와 색상·줄 번호가 있는 실제 코드 표시 |

제품 실행 코드는 변경하지 않았다. 아직 적용하지 않은 운영 이름·폴더 정리는 #20~21이다. 역할의 의미적 타당성, 함수·변수명, 거래 계산·DB 원자성·동시성은 이 검사로 판정하지 않는다. 모든 외부 소스 생성기 플러그인을 시험한 것도 아니다.

## 근거 코드 펼치기

GitHub에서는 각 절의 근거 링크로 실제 파일을 확인할 수 있다. 로컬 HTML에서는 같은 파일을 줄 번호·내용 해시와 함께 아래에 펼쳐 보여준다.

<!-- ARCH05_IMPL_SOURCES_START -->
원문·줄 번호·내용 해시는 HTML 생성 시 파일에서 직접 읽는다.
<!-- ARCH05_IMPL_SOURCES_END -->
