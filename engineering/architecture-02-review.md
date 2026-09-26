# ARCH-02 · 모듈 연결 검사

**로컬 구현·검증 완료: 구조 검사 81개, 전체 테스트 324개 통과.** 비교 기준은 `0f709c3`, 작업 브랜치는 `test/module-deps/19`입니다. 실행 당시 소스 SHA256은 [검증 기록](architecture-02-verification.json)에 있습니다. 원격 CI·리뷰의 최신 상태는 이 문서를 포함한 PR에서 확인합니다.

## 이것만 먼저 보면 됩니다

**목적:** 모듈 사이에 허용되지 않은 연결을 막습니다. 주문·정산 기능을 바꾸는 작업은 아닙니다.

> 코드와 Gradle에서 연결을 찾는다 → 허용표와 비교한다 → 금지된 연결과 이유를 알려준다.

- 매칭 → 주문: **통과**
- 매칭 → 수수료 직접 연결: **위반**
- 매칭 → 주문 → 수수료 간접 연결: **통과**
- 검사할 입력이 빠짐: **준비 실패. 통과로 처리하지 않음**

이전에는 수집과 ARCH-01만 실행했습니다. 이제 동일한 명령에서 ARCH-02도 실행합니다. 아래 네 흐름의 코드와 테스트는 필요한 경우에만 펼쳐보면 됩니다.

## 1. 판단할 입력이 충분한지 확인

**입력 → 판단 → 결과:** 기존 수집기가 읽은 모듈별 클래스, 실제 프로젝트 목록, Gradle 구성 기록을 받습니다. 수집 오류·빈 모듈·중복 소속·허용표 누락·빠진 Gradle 구성이 있으면 오류를 모아 **ARCH-02 미평가**로 보고합니다.

예를 들어 common의 “직접 의존 0개”는 정상입니다. common의 런타임 기록 자체가 없다면 아직 조사하지 못한 것이므로 준비 실패입니다. 한쪽 입력만 정상이어도 전체 통과로 보고하지 않습니다.

**내가 판단할 것:** “못 읽었다”와 “읽었는데 위반이 없다”를 구분하는가?

[입력 해석 코드](#source-input) · [준비 조건과 두 결과 결합](#source-rule) · [누락·손상 테스트](#source-gradle-tests)

## 2. 코드가 다른 모듈을 직접 사용하는지 확인

**입력 → 판단 → 결과:** 실제 출력에서 클래스의 소속을 찾습니다. 각 클래스의 직접 참조를 읽어 같은 모듈이면 허용하고, 다른 운영 모듈이면 허용표와 비교합니다. 일반 외부 라이브러리는 ARCH-02의 모듈 방향 대상에서 제외합니다. 내부 타입인데 소속을 못 찾으면 준비 실패입니다.

매칭 코드가 주문을 통해 수수료 모듈과 연결되어도, 매칭이 수수료 타입을 직접 참조하지 않으면 통과합니다. 반대로 같은 패키지에 있어도 실제 소속이 매칭과 수수료라면 직접 참조를 금지합니다. 포트·실행기·관측되는 생성 클래스도 검사합니다.

**내가 판단할 것:** 컴파일이 된다는 이유만으로 금지 방향을 허용하지 않는가?

[허용표와 코드 검사](#source-rule) · [정상·위반·모듈 소속 테스트](#source-code-tests) · [검사기에 넣는 작은 예제의 정체](#source-fixtures)

## 3. Gradle에 선언만 해 둔 연결도 확인

**입력 → 판단 → 결과:** 각 main의 컴파일·런타임 구성과 상위 구성에서 직접 선언한 프로젝트 의존을 가져옵니다. 허용표와 비교해 코드에서 아직 쓰지 않는 금지 선언도 보고합니다. 목적지 프로젝트가 다시 선언한 전이 의존까지 출발점의 직접 선언으로 펼치지 않습니다.

`compileOnly`, `runtimeOnly`, 사용자 정의 상위 구성도 포함합니다. 테스트 전용 의존은 제외합니다. 같은 선언이 컴파일·런타임 양쪽에서 보이면 한 위반으로 묶고 두 구성 근거를 남깁니다. 등록된 비운영 목적지는 보고하되 ARCH-08의 판단으로 남깁니다.

**내가 판단할 것:** 입력을 손으로 꾸며 넣은 테스트만 통과한 것은 아닌가? 실제 Gradle 테스트는 최소 프로젝트를 실행해 같은 수집 스크립트가 구성 상속·전이·테스트 경계를 구분하고, 선언 변경 시 입력 변경으로 감지하는지 확인했습니다.

[실제 Gradle 수집 스크립트](#source-producer) · [기존 빌드 연결](#source-build) · [실제 Gradle 프로젝트 검증](#source-wiring-tests)

## 4. 두 결과를 모아 실제 운영 코드를 판정

**입력 → 판단 → 결과:** P02가 기존 수집 결과와 실제 Gradle 입력을 같은 검사기에 넣습니다. 준비 오류가 없으면 코드와 선언의 위반을 함께 정렬해 보고합니다. 위반이 없을 때만 ARCH-02 통과입니다. 기존 P01의 ARCH-01 검사와 독립되어 테스트 실행 순서에 의존하지 않습니다.

**이번 실행:** 운영 6개 모듈·113개 클래스·내부 직접 타입 참조 1,473개·main 구성 12개·직접 프로젝트 선언 10개. 준비 오류와 ARCH-02 위반은 모두 0개입니다. 1,473은 바이트코드 참조 관측 수이며 모듈 간 서로 다른 연결의 개수가 아닙니다.

검사는 파일을 읽고 보고서를 만듭니다. 예제의 업무 메서드나 거래소 서버를 실행하지 않습니다. 독립 구조 검사에는 DB·Docker가 필요하지 않습니다. 전체 저장소 빌드에는 기존 Testcontainers 테스트가 있으므로 Docker를 사용합니다.

**내가 판단할 것:** 구조 검사가 통과했다는 것을 금액 계산·동시성·DB 정확성까지 증명한 것으로 오해하지 않는가?

[운영 코드 검사 진입점](#source-production) · [검사 결과와 한계](#verification)

<h2 id="review-followup">PR 리뷰 보완: 늦게 등록되는 의존도 확인</h2>

[독립 리뷰](https://github.com/0Chord/coin-exchange/pull/27#pullrequestreview-5324683769)의 선택적 보완에 따라 **실제 Gradle 테스트 두 개**를 추가했습니다. 검사 로직과 허용표는 그대로입니다. 여기서 ‘해석’은 Gradle이 의존 관계를 실제 사용할 대상으로 확정하는 단계입니다.

**1. 금지 연결이 나중에 생기는 경우**

매칭의 runtimeOnly가 비어 있으면 수수료를 기본으로 추가하도록 설정합니다. 처음 목록에는 주문만 보입니다. Gradle의 런타임 의존 해석을 실행하면 수수료가 추가됩니다. 같은 수집 스크립트를 다시 읽고 **runtimeOnly의 매칭 → 수수료 위반 정확히 1건**을 기대합니다. 코드 사용 여부와 관계없이 금지된 직접 선언이기 때문입니다. compile 목록과 다른 모듈의 목록은 그대로인지도 비교합니다.

입력이 바뀌었으므로 snapshot 작업은 다시 실행되어야 합니다. 같은 조건으로 한 번 더 실행하면 입력이 같으므로 이전 결과를 재사용할 수 있습니다. 해석 전의 불완전한 목록을 ‘전체 검사 통과’의 정답으로 삼지는 않습니다.

**2. 기본 연결이 생기지 않아야 하는 경우**

runtimeOnly에 허용된 common을 이미 명시합니다. Gradle의 기본 의존은 이때 추가되지 않아야 합니다. 해석 후에도 common만 남고 fee는 없어야 하므로 **위반 0건**을 기대합니다. snapshot이 재사용되더라도 해석 직후 입력을 별도로 다시 기록해, 오래된 파일만 비교해서 통과하지 않게 했습니다.

**내가 판단할 것:** 기본값이 실제로 추가된 경우와, 명시한 값 때문에 추가되지 않은 경우를 구분하는가? 첫 번째만 검사하면 존재하지 않는 금지 연결을 보고하는 오탐을 놓칠 수 있습니다.

이 두 테스트는 Gradle TestKit의 임시 프로젝트에서 공유 수집 스크립트와 실제 의존 해석을 사용합니다. 제품 코드를 바꾸거나 새 도구를 도입하지 않았습니다. 모든 지연 등록 API·플러그인·작업 순서를 자동으로 지원한다는 보장은 아닙니다. [실제 테스트 코드](#source-wiring-tests) · [G06 계약](architecture-check-spec.md).

<h2 id="verification">검사 결과와 한계</h2>

| 확인 | 실제 근거 |
| --- | --- |
| 코드 검사 Red | 3개 실행, 허용 1개 통과·금지 2개 실패. 예제 준비에는 오류가 없었고 빈 검사기가 위반을 놓쳤음. |
| Gradle 판정 Red | 8개 실행, 허용 2개 통과·위반/누락/해석 6개 실패. 실패 원인을 각각 입력 해석과 기대 결과로 확인함. |
| 기대값 검토 | 기존 허용표에서 정상·위반 기대값을 정함. 36개 방향 조합, 같은 패키지, 전이 연결, 0개/누락 구분, 정확한 진단 위치를 검토함. 같은 AI의 자체 검토이며 독립 PR 리뷰는 아님. |
| 현재 구조 검사 | 전체 빌드에서 기존 57개와 추가 24개, 총 81개 통과. 실패·오류·skip 0. 실제 Gradle 연결 4개와 운영 P02 포함. |
| 전체 회귀 | `./gradlew build --no-daemon --continue --stacktrace --rerun-tasks` 성공. 전체 324개 통과, 실패·오류·skip 0. 기존 PostgreSQL Testcontainers 테스트 포함. |
| 독립 명령의 작업 경계 | `:architecture-tests:test --dry-run`에서 다른 모듈의 test 작업 없음. dry-run의 SKIPPED 표시는 실행 계획 표시이며 위 실제 테스트의 skip 수와 다름. |
| 리뷰 보완 | 새 2개를 포함한 Gradle 연결 4개 통과. 최초 실패 1건은 macOS 임시 경로 별칭 비교 문제로 기대 경로를 실제 경로로 정규화함. 검사 동작은 이미 계약을 충족해 TDD Red로 기록하지 않음. |
| 독립 리뷰 | `6854274`는 별도 컨텍스트 리뷰 완료. 이후 추가한 두 테스트는 자체 검토·실행 완료이며 독립 재리뷰는 미실행. |
| 원격 상태 | 위 수치는 로컬 실행 기록입니다. 원격 CI 결과와 독립 리뷰는 PR에서 별도로 확인합니다. |
| 사람의 검토 | 미확인. 이 문서를 열거나 코드가 통과했다고 승인으로 기록하지 않음. |

추가된 예제 클래스는 거래소의 새 기능이 아닙니다. 예를 들어 `FeeReadingPort`는 “포트라 해도 금지된 모듈 타입을 직접 쓰면 잡는가”를 확인하는 테스트 입력입니다. 위반 예제에서는 **위반을 제대로 찾아야 테스트가 성공**합니다.

<details markdown="1">
<summary>테스트가 확인한 것과 이번 검사에서 다루지 않는 것</summary>

아래 제외 범위는 미완성 작업 목록이 아닙니다. 합의한 ARCH-02 구현은 로컬 검증까지 완료했고, 다른 규칙과 런타임 동작은 별도 범위입니다.

- 코드·소속·통합 결과 11개: 모든 방향 36조합, 전이/직접 구분, 출력 소속, 포트·실행기·생성 객체, 배열/제네릭/상속/어노테이션, 외부 타입, 누락·정책 오류, 양쪽 위반 결합.
- Gradle 입력·판정 8개: 미사용 선언, 빈 값/누락, 중복·미등록·모순 입력, 비운영 목적지, 새 프로젝트 등록, 형식 해석과 손상.
- 실제 Gradle 연결 4개: 실제 main 구성, 상속된 선언, compileOnly/runtimeOnly, 테스트·전이 제외, 입력 변경 재평가, 중첩 프로젝트의 전체 경로 보존, 지연 기본 의존의 해석 전후·미추가 경계.
- 운영 적용 1개: 모든 운영 출력과 Gradle 기록을 실제 규칙에 연결.
- 리플렉션·문자열 로딩·바이트코드에서 사라진 참조, composite build·dependency substitution·파일 의존 우회는 보장하지 않습니다. ARCH-06/08과 내부 계층·이름·폴더·거래 동작 변경도 이번 범위가 아닙니다.
- 실제 Gradle 검증에는 [Gradle TestKit](https://docs.gradle.org/current/userguide/test_kit.html)을 사용하며 프로젝트와 같은 Gradle 설치본으로 실행합니다.

</details>

## 변경 파일과 근거 코드

아래 링크는 이 문서와 같은 버전의 실제 소스입니다. 제품 실행 코드는 변경하지 않았습니다. 검증 도구도 `src/test` 아래에 있으므로, 경로만 보고 모두 테스트 예제라고 해석하지 않습니다. 로컬 HTML에서는 같은 소스를 색상·행 번호와 함께 펼쳐볼 수 있습니다.

<!-- ARCH02_SOURCES_START -->
| 근거 | 파일·핵심 진입점 | 연결되는 흐름·역할 |
| --- | --- | --- |
| <a name="source-input"></a>입력 해석 | [ProjectDependencies.kt](../architecture-tests/src/test/kotlin/com/exchange/architecture/support/ProjectDependencies.kt) · `read` | 흐름 1 · 검증 도구. 누락·손상은 빈 결과로 바꾸지 않음 |
| <a name="source-rule"></a>방향 판정 | [ModuleDependencyDirection.kt](../architecture-tests/src/test/kotlin/com/exchange/architecture/rules/ModuleDependencyDirection.kt) · `inspectBytecode`, `inspectGradle`, `inspect` | 흐름 1–4 · 검증 도구. 허용표, 준비 오류, 양쪽 위반 합산 |
| <a name="source-producer"></a>선언 수집 | [project-dependencies.gradle.kts](../architecture-tests/gradle/project-dependencies.gradle.kts) | 흐름 3 · 빌드 도구. 실제 main 구성과 직접 선언을 전달 |
| <a name="source-build"></a>기존 빌드 연결 | [build.gradle.kts](../architecture-tests/build.gradle.kts) | 흐름 3–4 · 빌드 설정. 공유 스크립트·TestKit·입력 추적 연결 |
| <a name="source-production"></a>운영 준수 검사 | [ProductionArchitectureTest.kt](../architecture-tests/src/test/kotlin/com/exchange/architecture/ProductionArchitectureTest.kt) · `P02` | 흐름 4 · 테스트. 기존 P01을 유지하고 실제 입력을 같은 규칙에 전달 |
| <a name="source-code-tests"></a>코드 검사 계약 | [ModuleDependencyContractTest.kt](../architecture-tests/src/test/kotlin/com/exchange/architecture/ModuleDependencyContractTest.kt) | 흐름 1·2·4 · 테스트 11개. 정상·위반·누락·결과 결합 |
| <a name="source-gradle-tests"></a>선언 검사 계약 | [ProjectDependencyContractTest.kt](../architecture-tests/src/test/kotlin/com/exchange/architecture/ProjectDependencyContractTest.kt) | 흐름 1·3 · 테스트 8개. 미사용 선언·빈 값·누락·해석 오류 |
| <a name="source-wiring-tests"></a>실제 Gradle 연결 | [GradleDependencyWiringTest.kt](../architecture-tests/src/test/kotlin/com/exchange/architecture/GradleDependencyWiringTest.kt) | 흐름 3·리뷰 보완 · 테스트 4개. 임시 빌드와 공유 스크립트로 입력 전달·지연 등록 경계 확인 |
| <a name="source-fixtures"></a>검사용 예제 | [ModuleDependencyFixtures.kt](../architecture-tests/src/test/kotlin/com/exchange/architecture/fixtures/moduledeps/ModuleDependencyFixtures.kt) | 흐름 2 · 테스트 입력. 의도적 위반을 포함하며 제품 기능이 아님 |
| <a name="source-evidence"></a>실행 근거 | [architecture-02-verification.json](architecture-02-verification.json) | 전체 · 문서. 명령·실제 결과·소스 해시를 기록 |
| 합의한 경계 | [architecture-check-spec.md](architecture-check-spec.md) | 전체 · 명세. 허용표·수용 사례·제외 범위 |
| 읽기 안내 | [architecture-02-review.md](architecture-02-review.md) | 전체 · 현재 문서. 네 흐름과 검토할 판단 연결 |
<!-- ARCH02_SOURCES_END -->

명세는 [architecture-check-spec.md](architecture-check-spec.md)에 있습니다. 이 리뷰 문서는 설명·실행 기록이며 검사 구현 자체가 아닙니다.
