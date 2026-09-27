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
