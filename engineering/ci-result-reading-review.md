# Kotlin/Gradle로 실행하기 — #25 초기 구현 흐름

> **#20 이후 현재 기준:** 필수 운영 검사는 P01~P08이다. 아래 P01~P05·330개 실행·옛 소스 발췌는 #25 초기 구현 시점의 기록으로 보존한다. 이번에 추가한 P06~P08과 새 실행 근거는 [#20 구현 기록](order-usecases-review.md), 현재 실행 방법은 [README](../README.md)를 따른다.

2026-10-02 · `ci/structure-checks/25` · 비교 기준 `18bed4f32ec05e12ec1a799d2f1aa06286016e5a` · 아래 발췌 파일의 내용 해시로 고정한 소스 스냅샷.

**로컬 구조 테스트 330개 통과, 실제 보고서 확인 성공.** 이 문서는 게시 전 로컬 실행 근거다. 새 GitHub CI·artifact의 결과는 같은 커밋의 PR checks와 Actions에서 별도로 확인한다. 발췌는 생성 당시 코드이며 자동 갱신되지 않는다. 독립 리뷰·사용자 이해/승인 완료를 기록하지 않았다.

## 로컬에서는 이 폴더에서 실행한다

다른 환경에서 재현할 때는 이 PR 변경을 포함한 자신의 저장소 루트로 이동한다. 아래 절대 경로는 이 문서를 작성한 컴퓨터의 작업 폴더이며, 다른 컴퓨터에서 그대로 사용할 경로가 아니다.

현재 #25 구현은 `/Users/0chord/.codex/worktrees/issue19-module-deps/exchange-core`의 `ci/structure-checks/25` 브랜치에 있다. Desktop의 `/Users/0chord/Desktop/exchange-core`는 `chore/verification-drafts/18` 브랜치의 이전 초안이며, `verifyArchitectureReport` 작업이 없다. 같은 저장소여도 두 폴더의 파일은 따로 관리된다. 브랜치 이름을 바꿔도 다른 폴더의 구현이 복사되지는 않는다.

**아래 두 줄을 순서대로 실행한다.** 첫 줄이 #25 작업 폴더로 이동하고, 두 번째 줄이 테스트 재실행 → XML 확인을 수행한다.

```bash
cd /Users/0chord/.codex/worktrees/issue19-module-deps/exchange-core
./gradlew :architecture-tests:verifyArchitectureReport --rerun-tasks
```

Desktop에서 나온 `task 'verifyArchitectureReport' not found`는 테스트 시작 전에 현재 폴더에서 실행할 작업을 찾지 못했다는 뜻이다. 구조 규칙 위반 결과가 아니다. Desktop의 기존 초안은 보존했다.

폴더를 지정한 실제 실행으로 보고서 확인 작업의 성공을 다시 확인했다. 이 실행에서는 기존 테스트 결과를 `UP-TO-DATE`로 재사용했으며, 앞서 재실행한 330개 테스트와 별도로 기록한다.

## 먼저 볼 변화

이번 요청에 따라 Python 보고서 확인 코드와 별도 테스트를 없앴다. 같은 역할을 기존 `architecture-tests` 모듈의 Kotlin 코드·JUnit 테스트·Gradle 작업으로 옮겼다. **검사 기준은 유지하고, 실행·관리 언어를 프로젝트에 맞췄다.** 새 모듈이나 라이브러리는 추가하지 않았다.

전체 흐름은 네 가지다.

1. 같은 변경에서 구조 작업과 기존 전체 작업이 따로 시작한다.
2. Gradle이 구조 테스트를 실행한 뒤, Kotlin 코드로 필수 운영 검사 P01~P05의 XML을 읽는다.
3. 누락·중복·skip 등이 있으면 실패한다. 테스트 자체가 먼저 실패하면 보고서 확인으로 진행하지 않는다.
4. 생성된 보고서는 보존하려 시도하고, 첫 실패 단계에서 실제 이유를 읽는다.

[합의한 명세·문답·실행 결과](ci-result-reading-spec.md) · [README 읽기 절차](../README.md#ci-실패-이유와-보고서-읽기)

## 1. Docker가 실패해도 구조 검사는 따로 실행한다

**입력:** 동일 변경의 CI. **판단:** 두 작업 사이에 `needs`, 즉 상대 작업의 성공을 기다리는 연결이 없다. **결과:** 구조 작업은 Java·Gradle로 검사하고 전체 작업은 기존 Docker 준비 → 전체 build를 실행한다.

구조가 성공하고 Docker가 실패하면 **구조 통과·제품 미실행·전체 CI 실패**다. 구조 작업의 Java/Gradle 준비가 실패하면 구조도 미실행이다. 구조 테스트는 운영 main 코드를 컴파일해 읽지만 제품 테스트·서버·DB를 시작하지 않는다.

기존 전체 작업의 명령·보고서 이름 `test-reports`·7일 보관은 유지했다. 구조 보고서는 `architecture-test-reports`로 따로 보존한다. 실제 GitHub에서 두 작업과 업로드가 실행되는 것은 PR 게시 후 확인해야 한다.

원문: `.github/workflows/build-and-test.yml` · 15~24행 · 파일 SHA-256 `8a788bb95402785d`

<details>
<summary>판단에 쓰는 실제 코드 펼치기</summary>

```yaml
jobs:
  architecture-checks:
    name: Architecture checks
    runs-on: ubuntu-24.04
    timeout-minutes: 20

    steps:
      - name: Check out source
        id: source
        uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
```

</details>

## 2. Gradle이 테스트와 XML 확인을 연결한다

**입력:** `verifyArchitectureReport` 실행 요청과 Gradle의 실제 XML 출력 경로. **판단:** 먼저 `test`가 성공해야 한다. **결과:** 테스트가 성공하면 컴파일된 Kotlin 확인 코드의 `main`을 실행한다. 실패하면 확인으로 넘어가지 않는다.

`JavaExec`는 Kotlin/JVM으로 컴파일한 코드를 실행하는 Gradle의 기본 작업이다. `mainClass`는 실행할 코드, `args`는 그 코드에 전달할 XML 경로, `classpath`는 기존 테스트 코드와 라이브러리의 위치다. 새 서버·새 검증 플랫폼을 띄우는 것이 아니다.

CI는 두 단계다. 첫 단계에서 테스트를 강제로 재실행한다. 두 번째 단계에서는 그 결과를 읽는다. `verifyArchitectureReport`도 `test`에 의존하지만, 변경이 없는 직전 테스트 결과는 `UP-TO-DATE`로 재사용한다. 이를 새 테스트 실행이라고 기록하지 않는다. 입력이 달라지면 Gradle이 테스트를 다시 실행할 수 있다.

로컬 실행은 문서 첫 부분의 **작업 폴더 이동 → Gradle 실행** 두 줄을 사용한다. `--rerun-tasks`가 없으면 과거 결과를 재사용할 수 있으므로 이번 실행 증거가 필요한 경우 붙인다.

Gradle의 작업 의존과 실행 실패 처리는 [작업 의존 문서](https://docs.gradle.org/current/userguide/controlling_task_execution.html#sec:adding_dependencies_to_tasks), [JavaExec 공식 문서](https://docs.gradle.org/current/dsl/org.gradle.api.tasks.JavaExec.html#org.gradle.api.tasks.JavaExec:ignoreExitValue)를 대조했다. 실제 프로젝트의 Gradle 9.5.1 실행 결과는 아래에 별도로 남긴다.

원문: `architecture-tests/build.gradle.kts` · 108~118행 · 파일 SHA-256 `befedad48b9a6bd3`

<details>
<summary>판단에 쓰는 실제 코드 펼치기</summary>

```kotlin
tasks.register<JavaExec>("verifyArchitectureReport") {
	group = "verification"
	description = "필수 운영 검사 P01~P05가 실행되고 성공했는지 XML로 확인한다."
	// 테스트 실패 시 실행하지 않는다. 재실행하려면 이 작업에도 --rerun-tasks를 붙인다.
	dependsOn(tasks.named("test"))
	javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
	classpath = sourceSets["test"].runtimeClasspath
	mainClass.set("com.exchange.architecture.support.ArchitectureReportVerifier")
	args(tasks.named<Test>("test").get().reports.junitXml.outputLocation.get().asFile
		.resolve("TEST-com.exchange.architecture.ProductionArchitectureTest.xml").absolutePath)
}
```

</details>

원문: `.github/workflows/build-and-test.yml` · 42~62행 · 파일 SHA-256 `8a788bb95402785d`

<details>
<summary>판단에 쓰는 실제 코드 펼치기</summary>

```yaml
      - name: Run architecture tests without Docker
        id: tests
        run: ./gradlew :architecture-tests:test --no-daemon --console=plain --rerun-tasks

      # Gradle이 실패했으면 이 단계는 실행하지 않아 원래 실패를 유지한다.
      - name: Verify required operating checks ran
        id: report-check
        # 직전 단계에서 테스트를 재실행했다. 여기서는 그 결과를 읽으며 테스트는 UP-TO-DATE로 재사용한다.
        run: ./gradlew :architecture-tests:verifyArchitectureReport --no-daemon --console=plain

      - name: Upload architecture test reports
        id: reports
        if: ${{ always() }}
        uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7.0.1
        with:
          name: architecture-test-reports
          path: |
            architecture-tests/build/reports/tests/test/**
            architecture-tests/build/test-results/test/*.xml
          if-no-files-found: warn
          retention-days: 7
```

</details>

## 3. ‘다섯 개’라는 합계만 믿지 않는다

**입력:** 이번 테스트가 만든 `ProductionArchitectureTest` XML. **판단:** 운영 클래스의 P01~P05가 각각 정확히 한 번 있는지, skip·실패·오류가 없는지 확인한다. **결과:** 모두 맞으면 성공 메시지. 읽기 실패나 불충족이 있으면 구체 이유를 붙여 예외를 발생시킨다. Gradle은 실행 실패를 작업 실패로 남긴다.

예를 들어 합계가 5여도 P03이 빠지면 실패한다. P01을 두 번 적어 다섯 개를 채워도 실패한다. 다른 테스트 클래스의 P03이나 이름이 비슷한 P030은 실제 P03을 대신하지 못한다. 순서는 달라도 된다.

XML이 없거나 손상됐으면 ‘읽을 수 없다’고 실패한다. XML을 읽으면서 외부 파일·네트워크를 참조하지 않는다. 보고서가 과거 실행인지 XML 내용만으로 증명할 수는 없으므로 Gradle의 이번 재실행과 연결한다. 필수 운영 검사 P06을 실제 추가하면 Kotlin의 `required` 목록도 맞춘다.

`problems`는 문제 이유 목록을 반환한다. `main`은 Gradle이 호출하는 입구다. 목록이 비어 있지 않으면 `check`가 실패한다. 이 두 함수는 제품 비즈니스 로직이 아니라 CI에서 실행하는 검증 도구다.

원문: `architecture-tests/src/test/kotlin/com/exchange/architecture/support/ArchitectureReportVerifier.kt` · 12~70행 · 파일 SHA-256 `856b67faaae6c432`

<details>
<summary>판단에 쓰는 실제 코드 펼치기</summary>

```kotlin
/** 기존 운영 검사 XML의 실행·성공 여부만 확인한다. 보고서의 신선도는 Gradle 재실행으로 확보한다. */
object ArchitectureReportVerifier {
    private const val suite = "com.exchange.architecture.ProductionArchitectureTest"
    private val required = listOf("P01", "P02", "P03", "P04", "P05")

    fun problems(report: Path): List<String> {
        val parser = DocumentBuilderFactory.newInstance().apply {
            // 보고서를 읽으며 외부 파일·네트워크를 참조하지 않는다.
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }.newDocumentBuilder().apply {
            setErrorHandler(object : DefaultHandler() {
                override fun error(error: SAXParseException) = throw error
                override fun fatalError(error: SAXParseException) = throw error
            })
        }
        val root = try {
            parser.parse(report.toFile()).documentElement
        } catch (error: IOException) {
            return listOf("보고서를 읽을 수 없습니다: $report · ${error.message}")
        } catch (error: SAXException) {
            return listOf("보고서를 읽을 수 없습니다: $report · ${error.message}")
        }
        if (root.tagName != "testsuite" || root.getAttribute("name") != suite) {
            return listOf("필수 운영 보고서가 아닙니다: ${root.getAttribute("name")}")
        }
        val cases = root.children("testcase")
        return buildList {
            // 합계만 믿으면 빠진 검사나 중복된 검사도 통과할 수 있다.
            required.forEach { identifier ->
                val count = cases.count {
                    it.getAttribute("classname") == suite && it.getAttribute("name").startsWith("$identifier ")
                }
                if (count != 1) add("$identifier: 정확히 한 번 실행되어야 합니다. 보고서 항목 ${count}개")
            }
            cases.forEach { case ->
                listOf("skipped", "failure", "error").forEach { status ->
                    if (case.children(status).isNotEmpty()) add("${case.getAttribute("name")}: $status")
                }
            }
            listOf("skipped", "failures", "errors").forEach { status ->
                val value = root.getAttribute(status)
                if (value.isNotEmpty() && value != "0") add("운영 보고서 $status=$value")
            }
        }
    }

    private fun Element.children(tag: String): List<Element> = (0 until childNodes.length)
        .map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) { "운영 검사 XML 경로가 필요합니다." }
        val errors = problems(Path.of(args.single()))
        check(errors.isEmpty()) { "구조 결과 확인 실패:\n" + errors.joinToString("\n") }
        println("필수 운영 검사 P01~P05 실행·성공 확인. 제품·DB 검사 결과는 별도로 확인하세요.")
    }
}
```

</details>

### 옮긴 13개 테스트가 확인하는 것

테스트는 독립 XML을 임시 파일에 쓰고 실제 `main`을 호출한다. 거절 사례는 예외가 났다는 사실과 기대한 이유를 함께 확인한다. 잘못된 XML을 제대로 거절하면 **테스트는 통과**한다. 실제 운영 구조 실패와 구분한다.

| 입력 | 기대값과 근거 |
| --- | --- |
| 정상 5개, 순서 다름 | 성공. 순서는 계약이 아님 |
| XML 없음 / 손상 | 읽기 실패. 실행 근거 없음 |
| 빈 XML / P03 누락 / P01 중복 | 실패. 필수 검사 각각 한 번 조건 불충족 |
| skipped / failure / error | 실패. 실제 실행·성공 아님 |
| 실패 합계 1 | 실패. 성공 보고서 아님 |
| 다른 suite / classname / P030 | 실패. 운영 검사를 대체할 수 없음 |

**Red:** 판정이 비어 있을 때 정상 1개는 통과, 거절 사례 12개는 예외가 없어 실패했다. **Green:** Kotlin 판정 구현 후 13개 모두 통과했다. 기대값은 기존 명세에서 옮겼으며 구현 결과를 정답 생성에 쓰지 않았다. 같은 AI가 검토한 단계로, 독립 리뷰라고 표시하지 않는다.

원문: `architecture-tests/src/test/kotlin/com/exchange/architecture/ArchitectureReportVerifierTest.kt` · 28~74행 · 파일 SHA-256 `792a6742de8619ef`

<details>
<summary>판단에 쓰는 실제 코드 펼치기</summary>

```kotlin
    private fun report(xml: String?): Path = directory.resolve("report.xml").also {
        if (xml != null) Files.writeString(it, xml)
    }

    private fun reject(xml: String?, reason: String) {
        val error = assertFailsWith<IllegalStateException> {
            ArchitectureReportVerifier.main(arrayOf(report(xml).toString()))
        }
        assertTrue(error.message.orEmpty().contains(reason), error.message)
    }

    @Test
    fun `운영 검사 다섯 개가 성공하면 순서와 무관하게 통과한다`() {
        val path = report(normal)
        assertEquals(emptyList(), ArchitectureReportVerifier.problems(path))
        ArchitectureReportVerifier.main(arrayOf(path.toString()))
    }

    @Test
    fun `XML이 없으면 읽기 실패로 거절한다`() {
        reject(null, "보고서를 읽을 수 없습니다")
    }

    @Test
    fun `손상된 XML은 읽기 실패로 거절한다`() {
        reject("<testsuite>", "보고서를 읽을 수 없습니다")
    }

    @Test
    fun `빈 보고서를 검사 통과로 인정하지 않는다`() {
        reject("""<testsuite name="$suite" tests="0"/>""", "P01")
    }

    @Test
    fun `합계가 다섯 개여도 P03이 빠지면 거절한다`() {
        reject(normal.replace(portCase, ""), "P03")
    }

    @Test
    fun `P01을 중복해 다섯 개를 채워도 거절한다`() {
        val duplicate = """<testcase name="P01 다른 도메인 검사()" classname="$suite"/>"""
        reject(normal.replace(portCase, duplicate), "P01: 정확히 한 번")
    }

    @Test
    fun `필수 검사가 skip이면 거절한다`() {
        reject(normal.replace(portCase, portCase.replace("/>", "><skipped/></testcase>")), "skipped")
```

</details>

## 4. 원래 실패를 유지하고 이유부터 읽는다

**입력:** 단계별 success/failure/skipped 상태와 생성된 HTML·XML. **판단:** 구조 테스트가 실패했으면 보고서 확인 단계는 건너뛴다. 업로드·안내는 실패 뒤에도 시도한다. **결과:** 원래 오류는 실패로 남고, 생성된 자료로 이유를 읽을 수 있다.

| 관측 | 첫 확인 위치와 의미 |
| --- | --- |
| 구조 테스트 실패 | 실패 테스트·XML 원문. 규칙 위반/입력 누락/검사기 기대값을 구분 |
| 필수 실행 확인 실패 | P03 누락·skip 등 해당 Kotlin 작업의 이유와 이번 XML |
| Docker 준비 실패 | 환경 원문. 제품 미실행, 구조 결과와 구분 |
| 업로드 실패 | 결과 전달 오류. 검사 결과와 별개 |
| 준비 실패·취소·보고서 없음 | 실행/보존 근거가 없는 범위를 통과로 인정하지 않음 |

안내는 실행 순서에서 처음 실패한 단계와 원문을 읽을 위치를 보여준다. 그 이름만으로 근본 원인을 확정하거나 오류의 업무 의미를 자동 분류하지 않는다. 준비 단계에서 실패하면 보고서가 없을 수 있다. 업로드 장애·취소·runner 중단 때 보존을 보장하지 않는다.

**Ask 질문:** 구조 통과 + Docker 실패일 때 무엇을 먼저 확인하고 싶은가?

**AI 설명:** 구조 통과·제품 미실행·전체 CI 실패는 각각 남는다. 단계 → 실제 오류 → 입력/코드로 읽는다.

**사용자 실제 답변:** “왜 실패했는지를 먼저 알고 싶지 실패한 이유들”. 별도 이유는 제시하지 않았다.

**반영:** 두 작업 안내와 README를 실패 이유 중심으로 유지했다.

**이번 사용자 결정:** “kotlin/gradle로 진행해 굳이 이종언어를 넣을 필요가 있나 싶어”. Kotlin/JUnit/Gradle로 옮겼으며 유지보수 언어를 늘리지 않는 선택을 반영했다. 검사 기준을 줄이거나 새 플랫폼을 추가하지 않았다.

원문: `.github/workflows/build-and-test.yml` · 74~93행 · 파일 SHA-256 `8a788bb95402785d`

<details>
<summary>판단에 쓰는 실제 코드 펼치기</summary>

```bash
          first_failure="없음. skip·취소가 있으면 실행 여부를 따로 확인하세요."
          for stage in "소스 준비:$CI_SOURCE_OUTCOME" "Java 준비:$CI_JAVA_OUTCOME" "Gradle 준비:$CI_GRADLE_OUTCOME" "구조 검사:$CI_TESTS_OUTCOME" "필수 운영 검사 실행 확인:$CI_REPORT_CHECK_OUTCOME" "보고서 업로드:$CI_REPORTS_OUTCOME"; do
            if [[ "$stage" == *:failure ]]; then
              first_failure="${stage%:*}"
              break
            fi
          done
          {
            echo "### 구조 검사: 실패 이유부터 확인"
            echo "- 첫 실패 단계: **$first_failure**"
            echo "- 실제 이유: 해당 단계의 오류 메시지를 먼저 읽으세요. 구조 검사 실패라면 HTML의 실패 테스트와 XML 메시지를 대조하세요."
            echo "- 실행 상태: 구조 검사=$CI_TESTS_OUTCOME / 필수 운영 검사 실행 확인=$CI_REPORT_CHECK_OUTCOME / 보고서 업로드=$CI_REPORTS_OUTCOME"
            echo "- 기준 커밋: $GITHUB_SHA"
            echo '- 명령: `./gradlew :architecture-tests:test --no-daemon --console=plain --rerun-tasks`'
            echo '- 실행 확인: `./gradlew :architecture-tests:verifyArchitectureReport --no-daemon --console=plain`'
            echo '- 보고서: `architecture-test-reports` · HTML과 XML · 7일 보관. 생성 전 실패하면 보고서가 없을 수 있습니다.'
            echo '- 준비 오류·미평가라면 입력/출력을, 규칙 위반이라면 오류에 나온 운영 타입/의존을 확인하세요. 예제 기대값 실패는 검사기 테스트를 확인하세요.'
            echo '- 범위: 구조만 검사합니다. 제품·DB 결과는 **Build and test**에서 별도로 확인하세요.'
            echo '- 읽는 절차: 저장소 README의 **CI 실패 이유와 보고서 읽기** 항목'
          } >> "$GITHUB_STEP_SUMMARY"
```

</details>

## 변경 파일과 실행 근거

| 파일 | 성격과 연결된 흐름 |
| --- | --- |
| `.github/workflows/build-and-test.yml` | CI 설정. 두 작업·Gradle 호출·보존·실패 이유 안내: 1~4 |
| `AGENTS.md` · `engineering/conventions-design.md` | 브랜치 명명·작업 폴더 안내 기준. 실행 코드 영향 없음 |
| `architecture-tests/build.gradle.kts` | 빌드. 기존 모듈에 실행 확인 작업 연결: 2 |
| `architecture-tests/src/test/kotlin/com/exchange/architecture/support/ArchitectureReportVerifier.kt` | 검증 도구. XML 읽기·거절·실패 전달: 3 |
| `architecture-tests/src/test/kotlin/com/exchange/architecture/ArchitectureReportVerifierTest.kt` | 테스트/임시 XML 예제. 정상·거절 이유: 3 |
| `README.md` | 실행·보고서 받기·이유 읽기: 1~4 |
| `engineering/ci-result-reading-spec.md` | 합의·기대값·문답·검증 범위 |
| `engineering/ci-result-reading-review.md` | 현재 안내와 실제 코드 스냅샷 |

이전 미게시 Python 코드·테스트·캐시 제외 설정은 제거했다. 제품 운영 코드·구조 규칙·수집기는 바꾸지 않았다. 로컬 reader는 기존 도구로 이 안내를 표시하며 제품/CI 코드나 PR의 일부가 아니다.

**현재 실행 근거:** 새 Kotlin 테스트 13개를 포함해 구조 테스트 **330개 통과**(32개 suite, 실패·오류·skip 0). P01~P05 5개가 실제 실행됐다. 이후 Gradle 확인 작업은 직전 XML을 읽어 정상 종료 0이었다.

보고서 없음·P03 누락·P03 skip은 임시 XML로 같은 Gradle 작업에 입력했다. 세 경우 모두 해당 이유를 남기고 실제 Gradle 종료 1이었다. 운영 XML·소스는 바꾸지 않았다. 임시 입력은 PR에 포함하지 않는다.

변경된 CI 설정의 actionlint·기존 설정 보존·두 작업 독립·Python 호출 제거·업로드 조건 확인도 통과했다. 실제 안내 셸을 구조/실행 확인/업로드/Docker 실패 상태로 실행해 확인했으며, 원격 CI 장애 실험으로 기록하지 않는다.

전환 전 C02·C03 격리 실험은 의도한 위반/누락 assertion에서 Gradle 종료 1과 HTML·XML을 남겼다. 그 규칙·수집기는 변경하지 않았다. 새 보고서 확인 작업 자체의 실패 전달은 위 Kotlin/Gradle 실행으로 별도 확인했다.

**남은 경계:** PR 게시 후 같은 변경의 두 GitHub CI 작업과 실제 artifact를 확인한다. 전체 제품 build·원격 업로드/취소·강제 Docker 장애는 로컬 구조 결과로 증명하지 않았다. #25 초기 단계이며 #25 전체 종료는 아니다.
