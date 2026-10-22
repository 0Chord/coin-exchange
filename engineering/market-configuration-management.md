# 마켓 설정 등록·조회 — #118

**전체 마켓 설정을 지정한 버전으로 보관하고, 저장이 확인된 버전과 SHA-256을 받는다.** 사람이 직접 SQL을 넣는 대신 CLI와 관리자 API가 같은 검사·저장 기능을 사용한다. 응답을 놓쳐도 같은 요청으로 저장 결과를 다시 확인할 수 있다.

```text
CLI / 관리자 PUT → 원문 검사 → 지정 버전에 저장·대조 → 커밋 확인 → 버전 + SHA-256
별도 노드 시작  → 버전 + SHA-256 지정 → DB 설정 읽기
```

| 요청 | 결과 |
| --- | --- |
| 없는 버전 7에 원문 A 등록 | 새 행 저장 후 `7 + SHA-256` 반환 |
| 버전 7에 원문 A 재전송 | 같은 결과 반환, 원문·등록 시각 유지 |
| 버전 7에 다른 원문 등록 | 충돌. 공백·개행만 바뀌어도 다른 원문 |
| 변경한 원문을 새 버전 8로 등록 | 새 행 생성, 버전 7 유지 |

**등록과 노드 적용은 별개다.** 등록은 노드 시작·실행 중 설정 변경·잔고 변경을 하지 않는다. 기존 버전의 수정·삭제 API도 없다. 적용하려면 별도로 노드를 시작할 때 등록 결과의 버전과 SHA-256을 지정한다.

**현재 범위:** 명시적으로 켠 API는 인증 없이 등록·상세·목록을 제공하며 인증 설계는 후속이다. 요청·응답 DTO를 별도 파일로 분리했지만 클라이언트는 기존 JSON 배열을 그대로 보내고 같은 응답 키를 받는다. 조회 조건은 기존 `@RequestParam`을 유지하고 본문 `@RequestBody` DTO와 응답 DTO를 분리했다. 최신 코드의 관련 테스트 26개와 구조 검사 381개, 린트·조립 검사를 통과했다. 작성 대화를 상속하지 않은 독립 검토에서도 확인된 미해결 제품 결함은 없었다.

<details markdown="1">
<summary>1. 실행 준비와 CLI 사용법</summary>

UTF-8 PostgreSQL에 기존 [V9 마이그레이션](../app-api/src/main/resources/db/migration/V9__create_market_configuration_release.sql)이 적용되어 있어야 한다. 등록 계정에는 대상 테이블의 SELECT·INSERT 권한이 필요하다. CLI는 Spring 서버나 노드를 시작하지 않으며, 마이그레이션도 실행하지 않는다.

아래 환경값을 실행 환경에 미리 주입한다. 자격정보를 명령 인자·JSON·DB URL 안에 넣지 않는다. 예시에는 비밀값을 기재하지 않았다.

| 환경값 | 용도 |
| --- | --- |
| `FUNDING_MARKET_DB_URL` | 자격정보가 없는 PostgreSQL JDBC URL |
| `FUNDING_MARKET_DB_USER` | 등록·조회 DB 계정 |
| `FUNDING_MARKET_DB_PASSWORD` | 해당 계정 비밀번호 |

`markets.json`은 BOM 없는 UTF-8 JSON 배열이다. 기존 파서가 마켓 1~32개와 필드·중복·수량·수수료 정책을 검사한다. 최대 65,536바이트이며, 원문의 공백·순서까지 보존한다. 입력 예:

```json
[{"marketId":"BTC-USDT","baseAssetId":"BTC","quoteAssetId":"USDT","baseQuantityScale":8,"feePolicy":{"productType":"SPOT","feeTier":"NORMAL","scheduleVersion":1,"makerPartsPerMillion":0,"takerPartsPerMillion":0}}]
```

실제 입력 파일의 절대 경로로 바꿔 실행한다. 아래 명령은 구현 당시 작업 폴더 기준이며, 다른 체크아웃에서는 각 `cd` 경로를 그 저장소의 절대 경로로 바꾼다.

```sh
cd /Users/0chord/.codex/worktrees/issue118-market-registration/exchange-core
./gradlew :app-api:runDevelopmentMarketRegistration \
  --args='--version 7 --document /absolute/config/markets.json'
```

성공 시 제품 main은 표준 출력으로 `CONFIRMED configVersion=7 sha256=<64자리 소문자 지문>`을 내보낸다. 신규와 동일 원문 재실행의 출력이 같다. 제품 main 종료 코드는 성공 `0`, 입력/충돌/확정 실패 `2`, 결과 미확인 `3`이다. Gradle을 통해 실행하면 자식 main의 비정상 종료가 Gradle 작업 실패로 보고되므로 셸이 받는 Gradle 종료 코드와 구분한다.

`--version`은 양수 Long이며 자동 증가·latest 선택은 없다. `--version`, `--document`를 각각 한 번만 받고 미지정·중복·알 수 없는 옵션을 거절한다. 파일은 한 번, 최대 65,537바이트만 읽고 상한 초과를 검사한다. 파싱 뒤 재읽기·trim·JSON 재직렬화를 하지 않는다. 등록은 노드용 `FUNDING_MARKET_CONFIG_VERSION`·`FUNDING_MARKET_CONFIG_SHA256`을 읽지 않는다.

등록 후 노드 시작 시에는 기존 [시작 로더](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentMarketSettings.kt#L48)에 같은 DB 연결값과 `FUNDING_MARKET_CONFIG_VERSION=7`, 반환받은 `FUNDING_MARKET_CONFIG_SHA256`을 전달한다. 이 별도 시작 과정에서 DB 원문과 지문을 대조한다.

근거: [CLI main·인자·파일 읽기](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentMarketRegistration.kt#L15), [실행 작업](../app-api/build.gradle.kts#L145).

</details>

<details markdown="1">
<summary>2. 관리자 HTTP API 사용법과 명시적 활성화</summary>

관리 API는 기본 비활성화되어 404를 반환한다. 기존 서버 실행 설정과 DB 환경값을 준비한 뒤 `EXCHANGE_MARKET_ADMIN_ENABLED=true`로 명시적으로 켠다. **활성화되면 인증 없이 등록·상세·목록을 호출할 수 있다.** 이 토글은 기능 활성화 조건이며 사용자 인증이나 권한 검사가 아니다. 인증 설계·구현은 후속이며, 이번에는 인증 필터나 인터셉터를 두지 않는다.

```sh
cd /Users/0chord/.codex/worktrees/issue118-market-registration/exchange-core
# 기존 서버 설정과 위의 DB 환경값을 준비한 환경에서 실행
EXCHANGE_MARKET_ADMIN_ENABLED=true ./gradlew :app-api:bootRun
```

아래는 로컬 기본 포트 예시다. 서버가 다른 포트를 쓰면 주소를 바꾼다. 활성화된 API에는 인증 헤더 없이 요청한다.

```sh
cd /Users/0chord/.codex/worktrees/issue118-market-registration/exchange-core
curl --fail-with-body --header 'Content-Type: application/json' \
  --request PUT --data-binary @/absolute/config/markets.json \
  http://127.0.0.1:8080/api/admin/market-configurations/7

curl --fail-with-body \
  http://127.0.0.1:8080/api/admin/market-configurations/7

curl --fail-with-body \
  'http://127.0.0.1:8080/api/admin/market-configurations?limit=20'
```

| 요청 | 응답 내용 |
| --- | --- |
| `PUT /api/admin/market-configurations/{version}` | 원문 JSON을 등록. 신규·동일 재전송 모두 200, `configVersion`, `sha256` |
| `GET /api/admin/market-configurations/{version}` | `configVersion`, `sha256`, 원문 문자열 `document`, `createdAt`. 없는 버전 404 |
| `GET /api/admin/market-configurations?beforeVersion=7&limit=20` | 7 미만 버전을 내림차순으로 조회. `items`에는 버전·지문·시각, 원문은 생략 |

PUT은 `Content-Type: application/json` 또는 `application/json; charset=UTF-8`로 원문을 보낸다. Content-Type 누락·`text/plain`·잘못된 형식·`application/*`는 Spring의 `consumes=application/json` 조건에서 **415**로 거절하며 DB에 저장하지 않는다. 사용자 요청으로 HTTP 입력 처리를 단순화하면서, 이전의 수동 400 분류를 Spring의 표준 415로 변경했다. 415는 본문 형식 오류이며 업무 오류의 `code/stage/configVersion` 형식을 보장하지 않는다. 기존 공통 오류 처리에도 415 처리를 명시해 IllegalArgumentException의 원인 매칭이 400으로 바꾸지 않게 했다.

응답은 JSON이다. `Accept: application/json`처럼 JSON을 받을 수 있는 요청을 사용한다. `Accept: text/plain`이나 JSON에 적용되는 `q=0`은 **DB 처리 전에 406**으로 거절한다. 이 검사는 PUT·상세 GET·목록 GET에 공통으로 적용한다. 406은 HTTP 응답 형식 협상 오류이며, 지원되는 요청에서 반환하는 아래의 정제된 입력·업무 오류와 별개다. 406에 업무 오류의 `code/stage/configVersion` 형식을 보장하지 않는다.

`q`는 해당 응답 형식을 받을 의향을 나타내며 `0`은 거절이다. JSON에 일치하는 범위 중 **`application/json` → `application/*` → `*/*` 순으로 더 구체적인 범위의 q**를 적용한다. 따라서 `application/json;q=0, */*;q=1`은 JSON을 명시적으로 거부했으므로 406이고, `application/json;q=1, */*;q=0`은 JSON을 명시적으로 허용했으므로 처리한다. 양수 q와 허용 wildcard도 지원한다. Spring의 `produces`만으로는 q=0 요청이 통과하는 경계가 있어 [마켓 설정 API 패키지에만 적용하는 공통 Advice](../app-api/src/main/kotlin/com/exchange/core/api/common/MarketConfigurationHttpExceptionHandler.kt#L55)의 `@ModelAttribute`·`@RequestHeader` 검사로 이 조건을 유지한다. Controller의 각 메서드는 `HttpServletRequest`를 받지 않는다.

`limit`은 기본 20, 허용 범위 1~100이다. 조회 조건은 기존 `@RequestParam`을 유지하며 limit의 `defaultValue="20"`, nullable beforeVersion을 사용한다. 따라서 `?limit=`은 20, `?beforeVersion=`은 커서 없음으로 처리하며 모두 정상 요청이다. 숫자가 아닌 값·Long 범위 초과·0 이하 버전/커서·한도 밖 limit은 400 `INVALID_INPUT`, `stage=request`로 거절한다. 알 수 없는 쿼리 키는 이전 `@RequestParam` 방식처럼 무시한다. `limit[0]` 같은 인덱스 키·중첩 키·`pageLimit`·폼 별칭(`!limit`, `_limit` 등)은 조회 조건을 바꾸지 않는다. `limit[]=1`은 Spring의 기존 `@RequestParam` 별칭 동작에 따라 limit 1로 처리한다. `limit=1&limit[0]=1`에서도 유효한 `limit=1`을 유지한다. 응답의 `nextBeforeVersion`을 다음 요청의 `beforeVersion`으로 전달한다. 페이지가 꽉 차면 마지막 버전을 반환하므로 **다음 페이지가 반드시 있다는 뜻은 아니다**. 다음 조회가 빈 목록일 수 있다. 전체 개수·활성 버전도 반환하지 않는다.

근거: [Controller](../app-api/src/main/kotlin/com/exchange/core/api/market/api/MarketConfigurationController.kt#L21), [활성화·조립](../app-api/src/main/kotlin/com/exchange/core/api/config/MarketConfigurationAdminConfig.kt#L14), [기본 설정](../app-api/src/main/resources/application.yaml).

</details>

<details markdown="1">
<summary>3. 요청이 저장 결과가 되기까지 — 실제 코드 흐름</summary>

```text
CLI: 옵션·DB 환경 검사 → 파일 바이트 읽기 ──────────┐
HTTP: 활성화 → 형식 확인 → DTO 변환·검증 → Controller ─┤
                                                 ↓
MarketConfigurationUseCase → MarketConfigurationStore 계약
                                                 ↓
PostgresMarketConfigurationStore
  원문 검사·SHA-256 → READ COMMITTED 트랜잭션
  → INSERT ON CONFLICT DO NOTHING
  → 별도 SELECT로 저장 원문과 입력 바이트 비교
  → COMMIT 확인 → 결과 반환 → HTTP Response DTO로 변환
```

| 파일·진입점 | 하는 일과 이유 |
| --- | --- |
| [DevelopmentMarketRegistration.main](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentMarketRegistration.kt#L15) | 파일·환경·종료 코드·출력을 처리한다. 서버 없이 등록하는 진입점이다. |
| [MarketConfigurationController](../app-api/src/main/kotlin/com/exchange/core/api/market/api/MarketConfigurationController.kt#L21) | 양수 Long 버전·검증된 등록 Request DTO·기존 조회 파라미터로 UseCase를 호출하고, 결과를 `Response.from`으로 변환한다. |
| [MarketConfigurationRegistrationRequest](../app-api/src/main/kotlin/com/exchange/core/api/market/api/MarketConfigurationRegistrationRequest.kt#L7) | 등록 원문 ByteArray와 크기 검증을 담는 Request DTO를 관리한다. |
| [MarketConfigurationResponses](../app-api/src/main/kotlin/com/exchange/core/api/market/api/MarketConfigurationResponses.kt#L9) | 등록·상세·요약·페이지·오류 DTO를 관리한다. application 결과를 기존 응답 키로 명시적으로 변환한다. |
| [MarketConfigurationRequestConverter](../app-api/src/main/kotlin/com/exchange/core/api/market/api/MarketConfigurationRequestConverter.kt#L10) | 정확한 등록 Request 타입만 읽는다. 최대 65,537바이트의 원문을 DTO에 담고 응답 변환에는 참여하지 않는다. |
| [MarketConfigurationHttpExceptionHandler](../app-api/src/main/kotlin/com/exchange/core/api/common/MarketConfigurationHttpExceptionHandler.kt#L26) | 마켓 설정 API 패키지에만 적용되는 Advice다. JSON 응답 수용 조건과 입력·업무 오류의 HTTP 응답 변환을 모은다. |
| [기존 ApiExceptionHandler](../app-api/src/main/kotlin/com/exchange/core/api/common/ApiExceptionHandler.kt#L27) | `HttpMediaTypeNotSupportedException`을 명시적으로 415로 반환한다. 기존 IllegalArgumentException 원인 매칭이 본문 형식 오류를 400으로 바꾸는 일을 막는다. |
| [MarketConfigurationUseCase](../app-api/src/main/kotlin/com/exchange/core/api/market/application/MarketConfigurationUseCase.kt#L7) | 공통 버전·크기·페이지 한도를 검사하고, 미존재를 명시적 오류로 바꾼다. |
| [MarketConfigurationStore](../app-api/src/main/kotlin/com/exchange/core/api/market/application/port/MarketConfigurationStore.kt#L7) | 등록·상세·목록 저장 계약이다. HTTP나 JDBC 타입을 요구하지 않는다. |
| [PostgresMarketConfigurationStore.register](../app-api/src/main/kotlin/com/exchange/core/api/market/infrastructure/persistence/PostgresMarketConfigurationStore.kt#L22) | 실제 DB 등록·경합·원문 비교·커밋과 실패 분류를 맡는다. |
| [validateDocument·release](../app-api/src/main/kotlin/com/exchange/core/api/market/infrastructure/persistence/PostgresMarketConfigurationStore.kt#L141) | 손상 UTF-8·BOM·NUL을 거절하고 기존 문서 파서를 재사용한다. DB에서 읽은 문서도 재검사해 손상 내용을 정상 설정으로 반환하지 않는다. |
| [MarketConfigurationDatabaseSettings](../app-api/src/main/kotlin/com/exchange/core/api/market/infrastructure/persistence/MarketConfigurationDatabaseSettings.kt#L9) | 필수 환경과 기존 URL 규칙을 검사하고 유한한 연결·SQL 대기를 적용한다. |
| [MarketConfigurationAdminConfig](../app-api/src/main/kotlin/com/exchange/core/api/config/MarketConfigurationAdminConfig.kt#L14) | 활성화 때 UseCase·PostgreSQL 구현과 RequestConverter Bean을 조립한다. Spring Boot가 이 변환기를 HTTP 변환기에 등록한다. |

**DTO 분리는 서버 내부 구조 변경이다. PUT 본문은 기존 마켓 JSON 배열 그대로다.** `{"document": ...}` 객체로 감싸지 않는다. [전용 변환기](../app-api/src/main/kotlin/com/exchange/core/api/market/api/MarketConfigurationRequestConverter.kt#L18)가 원문을 최대 65,537바이트만 읽어 `MarketConfigurationRegistrationRequest.document`에 담는다. JSON 파싱·재직렬화를 하지 않아 공백·순서·UTF-8 바이트와 SHA-256 계약을 유지한다. 읽기 상한은 이 변환기 한 곳에서 처리한다.

변환기의 `supports`는 정확히 등록 Request DTO만 선택한다. `MediaType.ALL`은 이 DTO가 기본 Jackson 변환으로 우회되지 않게 하는 설정이며, 실제 허용 Content-Type은 Controller의 `consumes=application/json`이 결정한다. `canWrite=false`이므로 응답 직렬화에 참여하지 않는다. [Config의 Bean](../app-api/src/main/kotlin/com/exchange/core/api/config/MarketConfigurationAdminConfig.kt#L20)으로 등록하여 Spring Boot의 변환기 자동 등록을 사용한다.

Controller의 `@Valid @RequestBody`가 DTO의 `@field:Size(min=1, max=65536)`를 검증한다. 상한 초과는 DTO 검증에서 거절하며, 비어 있는 본문은 Spring의 필수 본문 예외로 거절한다. 두 경우 모두 [공통 invalidRequest](../app-api/src/main/kotlin/com/exchange/core/api/common/MarketConfigurationHttpExceptionHandler.kt#L33)에서 400 `INVALID_INPUT`, `stage=request`, `configVersion=null`로 정제한다. 여기에 DTO 검증의 `MethodArgumentNotValidException`, 메서드 검증·숫자 변환·본문 읽기 오류도 포함한다. 손상 UTF-8이나 문서 내용 규칙은 이어지는 Store 검사에서 거절한다.

[목록 조회](../app-api/src/main/kotlin/com/exchange/core/api/market/api/MarketConfigurationController.kt#L37)는 기존 `@RequestParam` 두 개를 유지한다. `beforeVersion: Long?`은 `@Positive`, 기본값 20인 `limit: Int`는 `@Min(1)`·`@Max(100)`으로 검증한다. 누락·빈 limit은 20, 누락·빈 beforeVersion은 커서 없음으로 처리한다. 입력 본문과 응답만 별도 DTO로 관리하므로 조회 파라미터를 객체 속성으로 확장하거나 헤더에서 가져오지 않는다. 알 수 없는 중첩·폼 별칭 키는 무시하고 Spring의 기존 `limit[]` 별칭은 유지한다. 경로의 버전도 `@Positive Long`이며 기존 [starter-validation](../app-api/build.gradle.kts#L56)의 Hibernate Validator를 사용한다.

Advice는 `@ControllerAdvice(basePackages=["com.exchange.core.api.market.api"])`로 범위를 정하며 이 패키지의 향후 Controller도 포함한다. Advice는 Accept 검사와 HTTP 오류 응답을 담당한다. [최고 우선순위 선언](../app-api/src/main/kotlin/com/exchange/core/api/common/MarketConfigurationHttpExceptionHandler.kt#L24)으로 이 범위의 입력·업무 오류를 정제된 응답으로 먼저 처리한다. 오류 처리를 Controller에서 Advice로 옮긴 뒤, 전역 IllegalArgumentException 처리기가 먼저 등록되면 잘못된 경로 입력이 message에 노출되는 400 응답을 독립 실행에서 재현했다. `@Order(Ordered.HIGHEST_PRECEDENCE)`는 마켓 범위에서 이 처리 순서를 명시한다. 정상 결과는 [등록·상세·요약·페이지 Response](../app-api/src/main/kotlin/com/exchange/core/api/market/api/MarketConfigurationResponses.kt#L9)의 `from`에서 application 결과를 명시적으로 옮기고, 오류도 [FailureResponse.from](../app-api/src/main/kotlin/com/exchange/core/api/market/api/MarketConfigurationResponses.kt#L65)으로 변환한다. HTTP 응답 키는 기존 계약을 유지한다.

저장 전 입력 바이트를 복사해 같은 내용으로 검사·지문 생성·저장을 수행한다. 중복 판정은 해시만 비교하지 않고 **원문 바이트 전체를 비교**한다. 같은 버전은 PK가 최종적으로 경합을 정한다. INSERT와 SELECT를 별도 문장으로 실행하므로 앞선 등록의 커밋 뒤 새 조회에서 그 행을 읽을 수 있다. 다른 원문이면 롤백하고 기존 행을 보존한다. V9는 UPDATE·DELETE·TRUNCATE도 금지한다.

상세는 원문까지, 목록은 버전·지문·시각만 제공한다. 조회 시 저장 원문의 검증·지문 계산을 다시 수행한다. 목록은 `config_version < beforeVersion`, 내림차순, 제한 건수로 조회한다.

연결·소켓·로그인은 5초, SQL·취소 신호는 3초로 제한한다. 사용자가 URL에 무제한 옵션을 넣어도 뒤에 고정한 제한이 적용된다. 이 값들은 단계별 제한이며 전체 요청이 항상 그 시간 안에 끝난다는 보장은 아니다.

</details>

<details markdown="1">
<summary>4. 실패했을 때 무엇을 다시 해야 하는가</summary>

| 코드 | HTTP / 제품 main 종료 | 의미와 대응 |
| --- | --- | --- |
| `INVALID_INPUT` | 400 / 2 | 버전·문서·환경·URL 등 입력 오류. HTTP 숫자 변환·Bean Validation·본문 읽기 오류는 `stage=request`이며, Content-Type 오류 415와 구분한다. 입력을 고친다. |
| `CONTENT_CONFLICT` | 409 / 2 | 같은 버전에 다른 원문이 있다. 기존 원문으로 재확인하거나 변경 내용을 새 명시 버전에 등록한다. |
| `NOT_FOUND` | 404 / 해당 없음 | 유효한 상세 조회에서 버전이 없다. |
| `REGISTRATION_FAILED` | 503 / 2 | 연결 전 실패 또는 쓰기 후 롤백이 확인된 실패. 이번 호출의 새 저장이 없다고 판단한다. 기존 행이 없다는 뜻은 아니다. |
| `RESULT_UNCONFIRMED` | 503 / 3 | 저장됐는지 아직 모르는 상태. **동일 버전·동일 원문으로 재전송**한다. |
| `LOOKUP_FAILED` | 503 / 해당 없음 | DB 접근이나 저장 문서 검증에 실패했다. 버전 없음으로 해석하지 않는다. |

JSON 응답을 지원하는 요청의 입력·업무 오류에는 `code`, `stage`, 알 수 있을 때 `configVersion`만 전달한다. 지원하지 않는 Content-Type의 415와 JSON 응답을 받지 않는 Accept의 406은 쓰기 전 HTTP 형식 오류이며 이 업무 오류 형식과 구분한다. 문서 원문·DB 주소·계정·비밀번호·하위 JDBC 예외는 외부 오류에 담지 않는다. `stage`는 `connection`, `insert`, `compare`, `commit` 등 실패 위치를 나타낸다.

쓰기 시도 뒤 커밋을 시도했거나, DB 연결이 끊겼거나, 롤백 성공을 확인하지 못하면 결과 미확인으로 처리한다. 롤백을 한 번 시도했다는 사실만으로 이미 반영된 커밋이 취소됐다고 단정하지 않는다. 커밋 성공 뒤 연결 정리 오류는 성공을 뒤집지 않는다. CLI에서 성공 출력 전달 실패가 감지되어도 저장이 없었다고 바꾸지 않고 `RESULT_UNCONFIRMED stage=response`로 끝낸다.

자동 버전 증가·기존 행 삭제·다른 기본 설정으로 대체하는 복구는 없다. 실제 COMMIT 응답 유실이나 출력 전달 실패를 이번 자동 테스트에서 재현한 것은 아니다.

근거: [저장 예외 분류·정리](../app-api/src/main/kotlin/com/exchange/core/api/market/infrastructure/persistence/PostgresMarketConfigurationStore.kt#L22), [오류 자료형](../app-api/src/main/kotlin/com/exchange/core/api/market/application/MarketConfigurationUseCase.kt#L63), [HTTP 오류 변환](../app-api/src/main/kotlin/com/exchange/core/api/common/MarketConfigurationHttpExceptionHandler.kt#L37).

</details>

<details markdown="1">
<summary>5. 제품 테스트 — 준비·실행·실제 검증문</summary>

아래는 현재 소스의 DB 13개·CLI 5개·HTTP 11개 테스트를 설명한다. 최신 코드의 HTTP 11개와 주문 관련 15개를 재실행해 모두 통과했다. 변경하지 않은 DB Store·CLI의 기존 근거와 이번 재검증 범위는 6절에서 구분한다. HTTP 요청에는 인증 헤더를 보내지 않는다.

DTO 분리 후에도 원문 입력·응답 JSON 키·빈 쿼리값의 기본 처리를 유지한다. 정제된 오류 Advice의 우선순위를 명시하고 조회에는 기존 `@RequestParam`을 유지했다. 기존 공통 Handler를 먼저 등록해도 정제된 오류를 반환하고, 알 수 없는 중첩 쿼리 키는 기존처럼 무시하는지 실제 HTTP로 확인한다.

| 묶음 | 종류·공통 준비 | 수 |
| --- | --- | --- |
| [PostgresMarketConfigurationStoreTest][db-tests] | Testcontainers의 실제 PostgreSQL 17 + Flyway. 제품 Store를 실제 연결로 실행 | 13 |
| [DevelopmentMarketRegistrationMainTest][cli-tests] | 실제 DB + 임시 파일 + 별도 JVM의 제품 main. 종료 코드·stdout·stderr·DB를 관측 | 5 |
| [MarketConfigurationHttpTest][http-tests] | 실제 DB + 별도 JVM의 Spring HTTP 서버 + Java HTTP 클라이언트·실제 소켓 | 11 |

HTTP 검증은 MockMvc가 아니다. [테스트 서버](../app-api/src/test/kotlin/com/exchange/core/api/market/api/MarketConfigurationHttpTestServer.kt#L16)가 제품 Controller·변환기 Bean을 포함한 Config·마켓 공통 Advice·기존 ApiExceptionHandler를 함께 가져오고 임의 포트로 시작한다. [테스트 서버의 Import 순서](../app-api/src/test/kotlin/com/exchange/core/api/market/api/MarketConfigurationHttpTestServer.kt#L35)는 전역 ApiExceptionHandler를 마켓 Advice보다 먼저 선언한다. 이 조건에서도 입력 오류가 code/stage/configVersion의 정제된 응답을 유지하는지 기존 HTTP 오류 검증으로 확인한다. DB 준비는 [MarketConfigurationDatabase](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MarketConfigurationDatabase.kt#L13)가 담당한다. 동시성은 서로 다른 실제 연결과 latch를 사용한다. PK 대기는 `pg_stat_activity.wait_event_type='Lock'`를 확인하므로 단순 sleep을 경합 증거로 삼지 않는다.

#### 실제 PostgreSQL 13개

각 행의 마지막 열이 assertion이 관측하는 결과다. 파일 링크의 행 번호는 해당 테스트 시작 위치다.

| 사례·코드 위치 | Given — 준비 | When — 실행 | Then — 실제 확인 |
| --- | --- | --- | --- |
| [같은 원문 재등록][db-42] | 유효 원문, 버전 7 | 등록→상세→재등록→기존 로더 | 고정 SHA-256·같은 결과, 원문·등록 시각 불변, 정확히 한 행, 로더 버전·지문 일치 |
| [다른 원문 충돌][db-69] | 원문을 저장 | 정책 변경·공백 변경 재등록, 새 버전 등록 | 두 충돌 코드와 기존 상세 불변, 새 버전의 변경 원문·고정 지문 |
| [같은 원문 동시 등록][db-89] | 같은 버전·원문 두 요청 | latch 해제 후 두 연결 등록 | 두 성공·동일 지문·한 행·정확한 원문 |
| [다른 원문 동시 등록][db-101] | 같은 버전·상이한 두 원문 | 동시에 등록 | 한 성공·한 충돌·한 행, 승자 원문과 독립 고정 지문 일치 |
| [앞 커밋·같은 원문][db-118] | 앞 연결 INSERT 미커밋, 뒤 연결 실제 PK 대기 | 앞 연결 COMMIT | 뒤 호출 성공·고정 지문·요청 원문·한 행 |
| [앞 커밋·다른 원문][db-123] | 앞 원문 A, 뒤 원문 B가 PK 대기 | 앞 연결 COMMIT | 뒤 호출 충돌, A 보존·한 행 |
| [앞 롤백][db-128] | A 미커밋, B가 PK 대기 | 앞 연결 ROLLBACK | B 등록 성공·B 지문·B 원문·한 행 |
| [제한시간·회복][db-133] | 실제 테이블 잠금, URL 무제한 옵션 | 등록 시도 후 잠금 해제·재등록 | 12초 상한 내 `REGISTRATION_FAILED/insert`, cause 없음, 0행 후 정상 지문 |
| [INSERT 후 조회 거절][db-161] | INSERT와 버전 SELECT만 허용한 역할 | 원문 SELECT 실패 후 정상 계정 재등록 | `REGISTRATION_FAILED/compare`, 0행, cause·역할·비밀번호 비노출, 이후 성공 |
| [페이지 조회][db-195] | 격리 DB에 네 버전 등록 | 두 페이지·빈 페이지·없는 상세 | 정확한 내림차순 목록·빈 목록·null 상세·고정 지문 |
| [손상된 저장 문서][db-210] | fixture로 `[]`를 직접 저장 | 상세·목록 조회 | 둘 다 `LOOKUP_FAILED/stored-document`, cause 없음 |
| [UTF-8·크기][db-226] | 65,536바이트 유효 문서와 6개 오류 입력 | 정상 등록·초과/손상 UTF-8/BOM/NUL/빈 문서/빈 배열 등록 | 경계 원문 동일, 오류마다 `INVALID_INPUT/document`·0행·cause 없음 |
| [접속 전 실패][db-252] | 닫힌 로컬 포트·별도 자격정보 | 등록 | `REGISTRATION_FAILED/connection`, cause 및 주소·계정·비밀번호·원문 비노출 |

#### CLI 별도 JVM 5개

| 사례·코드 위치 | Given — 준비 | When — 실행 | Then — 실제 확인 |
| --- | --- | --- | --- |
| [응답을 다음 입력에 쓰지 않고 재실행][cli-35] | BTC·SUI 원문·같은 명시 버전 | main 두 번, 기존 로더 조회 | 두 종료 0·같은 고정 출력·빈 stderr·한 행·원문/시각 유지·로더 BTC/SUI 및 지문 |
| [공백 충돌·새 버전][cli-58] | 최초 등록 성공 | 공백 추가해 같은 버전, 이어 새 버전 | 종료 2·빈 stdout·충돌 오류·기존 행 불변, 새 버전 종료 0·원문 보존 |
| [잘못된 입력][cli-71] | 손상 UTF-8/BOM/빈 파일/잘못된 JSON/상한 초과, 중복 옵션·버전 0 | 각 입력으로 실제 main | 종료 2·성공 출력 없음, 문서 오류에 INVALID_INPUT·원문 비노출·행 없음; 중복 옵션도 행 없음 |
| [최대 크기][cli-99] | 정확히 65,536바이트 | main 실행 | 종료 0, DB 원문 전체 일치 |
| [환경·접속 오류][cli-107] | 비밀번호 누락/자격정보 포함 URL/닫힌 포트 | 실제 main | 종료 2·빈 stdout·행 없음, 자격정보·URL·원문·stack trace 비노출 |

첫 사례는 첫 호출 결과를 다음 등록 입력에 사용하지 않는 재실행을 검증한다. 테스트 도구는 첫 stdout도 수집·비교한다. 실제 네트워크에서 COMMIT ACK를 잃게 만든 실험은 아니다.

#### 실제 HTTP 11개

| 사례·코드 위치 | Given — 준비 | When — 실행 | Then — 실제 확인 |
| --- | --- | --- | --- |
| [PUT·재전송·충돌·상세][http-44] | 명시적으로 활성화한 서버, 인증 헤더 없는 요청 | PUT→GET→동일 PUT→변경 PUT→새 버전, 없는 GET·DELETE | 등록 응답 전체 일치·상세의 정확한 키 집합·원문/지문/시각 유지·409/한 행·새 버전 200·404·405 |
| [페이지·쿼리 한도][http-70] | 10·20번 등록 | limit 1로 다음 cursor 조회, 경계/잘못된 쿼리 | 페이지·요약의 정확한 키 집합, 버전 순서·원문 생략·마지막 cursor 뒤 빈 목록, 빈 limit/커서와 정상 입력 200·오류 400 |
| [알 수 없는 조회 키 무시][http-116] | 700·701번 등록, 기본 조회에 최소 2건·limit 1 조회에 정확히 1건 보장 | 알 수 없는 키 10개, 명시적 limit과 인덱스 키의 혼합, 기존 limit[] 별칭으로 GET | 모두 200, 앞의 10개는 기본 페이지 전체와 일치, 혼합 요청·limit[]는 limit 1 페이지 전체와 일치 |
| [Content-Type 형식 거절][http-149] | 누락/text/plain/잘못된 형식/application/* 헤더와 유효 원문 | 각 PUT 후 JSON·JSON+UTF-8 형식 PUT | 각 오류 415·0행, 정상 200·최종 1행·원문 일치 |
| [Accept 협상은 DB 처리 전][http-163] | 유효 원문, 거절 형식 6개(text/plain 1개·q=0 조합 5개), 허용 형식 5개 | 거절 형식별 PUT→허용 형식별 PUT→거절 형식별 상세·목록 GET | 거절 PUT마다 406·0행, 허용 PUT마다 200, 두 GET 각각 406, 최종 1행·상세 원문 일치 |
| [바이트·버전 오류][http-190] | 최대 크기와 초과/손상/빈/잘못된 문서 | PUT·상세, 0/음수/문자/Long 초과 버전 | 최대 원문 일치, 오류 400·INVALID_INPUT·0행·정제 응답, 상한 초과·빈 본문은 `stage=request` |
| [숫자 변환·Bean Validation][http-211] | 버전 0/음수/문자/Long 초과, 커서 0/음수/문자, limit 0/101/문자 | 잘못된 버전으로 PUT·상세 GET, 잘못된 쿼리로 목록 GET | 모든 응답 400·`INVALID_INPUT`·`stage=request`, 오류 키가 code/stage/configVersion과 정확히 일치 |
| [본문 끝을 기다리지 않는 크기 거절][http-231] | 실제 소켓, 큰 Content-Length 또는 종료하지 않은 chunked 본문 | 두 전송 방식에서 65,537바이트만 보내고 나머지·종료 표식은 보내지 않음 | 본문 전송 완료 전 400 응답, 각 요청 뒤 버전 350의 DB 0행 |
| [원문 조회 권한 실패][http-239] | 제한 DB 계정의 별도 서버 | PUT 후 GET | PUT 503 `REGISTRATION_FAILED/compare`·0행, GET 503 `LOOKUP_FAILED`, 응답·로그 비밀 비노출 |
| [쓰기 연결 단절][http-265] | INSERT 뒤 해당 DB 연결을 끊는 테스트 trigger | PUT→trigger 제거→같은 PUT | 503 `RESULT_UNCONFIRMED/insert`, 정제 응답, 실제 0행, 복구 후 200 |
| [기본 비활성화][http-304] | 관리자 활성화·DB 환경값 없는 별도 서버 | 목록·PUT·상세 | 시작 성공, 모두 404·새 행 없음 |

[본문 크기 사례의 stage 검증](../app-api/src/test/kotlin/com/exchange/core/api/market/api/MarketConfigurationHttpTest.kt#L200)은 65,537바이트와 빈 본문에서 `request`를 확인한다. 상한 초과의 DTO `@Size` 검증과 빈 본문의 Spring 필수 본문 오류가 공통 요청 오류로 처리되는지 확인하며, Store 문서 검사의 `document` 오류와 구분한다.

숫자 변환·Bean Validation 사례는 상태·code·stage와 정확한 오류 키 집합을 비교한다. configVersion의 값과 DB 행 수를 직접 비교하는 것은 아니다. 목록의 빈 쿼리값 사례는 200을 확인하며, 기본 limit 20·커서 null의 적용은 Controller의 파라미터 선언으로 확인한다. 상세·페이지·요약 응답도 각각 공개하기로 한 키 집합을 대조한다.

[알 수 없는 조회 키 사례][http-116]는 `limit[0]=1`, `beforeVersion[0]=1`, `limit.value=1`, `beforeVersion.value=1`, `pageLimit[0]=1`, `pageLimit=1`, `!limit=1`, `!beforeVersion=1`, `_limit=1`, `_beforeVersion=1`의 응답 전체를 기본 조회 응답과 비교한다. `limit=1&limit[0]=1`과 Spring의 기존 별칭인 `limit[]=1`은 각각 명시적인 limit 1 조회 응답과 비교한다. URL의 대괄호는 `%5B`·`%5D`, 느낌표는 `%21`로 보내며, DB에 700·701번을 먼저 등록해 기본 조회가 2건 이상이고 limit 1 조회는 1건임을 확인한다. 이로써 빈 결과끼리 우연히 같아지는 비교를 피한다. 기본값 20 자체는 `@RequestParam` 선언으로 확인하며 이 사례가 20건짜리 페이지를 구성하는 것은 아니다.

큰 본문 사례의 [실제 소켓 helper](../app-api/src/test/kotlin/com/exchange/core/api/market/api/MarketConfigurationHttpTest.kt#L399)는 한 번은 `Content-Length: 10000000`, 한 번은 `Transfer-Encoding: chunked`로 요청한다. 둘 다 공백 65,537바이트만 보내고 나머지 본문 또는 chunked 종료 표식을 보내지 않은 채 응답을 기다린다. 5초 소켓 제한 안에 상태 줄의 400을 확인한 다음, 테스트 본문에서 DB 0행을 확인한다. 응답 JSON의 code/stage까지 이 소켓 helper가 검사하는 것은 아니다.

Accept 테스트의 거절 입력은 `text/plain`, `application/json;q=0`, `*/*;q=0`, `application/json;q=0, */*;q=1`, `application/json;q=0, text/plain;q=1`, `application/*;q=0, */*;q=1`이다. 각 입력으로 PUT을 보내 406과 DB 0행을 직접 확인한다. 허용 대조는 `application/json`, `application/json;q=0.5`, `application/*`, `*/*`, `application/json;q=1, */*;q=0`이며, 같은 버전·원문을 등록할 때마다 200을 확인한다. 이어 거절 입력 6개를 상세·목록 GET에도 보내 각각 406을 확인하고, 최종 한 행과 저장 원문을 대조한다. GET 검사는 HTTP 상태를 관측하며 DB 조회 호출 횟수까지 계측하지 않는다.

쓰기 연결 단절 사례의 실제 최종 DB는 0행이다. 서버가 응답을 만들던 순간에는 이를 확정하지 못하므로 미확인으로 분류하는 것을 검사한다. COMMIT 시점의 ACK 유실과는 다른 장애다.

</details>

<details markdown="1">
<summary>6. 구조 검사를 바꾼 이유와 검증 범위</summary>

새 market 코드를 기존 검사 대상에 포함시켜, HTTP가 저장소를 바로 호출하거나 UseCase가 JDBC에 묶이는 우회를 계속 감지하려는 변경이다. 검사 규칙을 완화하지 않았다.

- [ProjectLayoutPolicy](../architecture-tests/src/test/kotlin/com/exchange/architecture/policy/ProjectLayoutPolicy.kt#L34)는 `market/api`, `market/application`, `market/application/port`, `market/infrastructure/persistence`의 정확한 위치·역할을 등록한다. [NamingRules](../architecture-tests/src/test/kotlin/com/exchange/architecture/rules/NamingRules.kt#L220)는 이 영역의 Controller·UseCase·Store·HTTP 데이터 이름과 위치를 검사한다.
- [ProductionBoundaryInputs](../architecture-tests/src/test/kotlin/com/exchange/architecture/support/ProductionBoundaryInputs.kt#L24)는 Controller→UseCase와 공개 결과·오류 5개 타입을 연결한다. 공개 데이터도 application의 기술 의존 검사에 남겨, 결과 객체 안에 JDBC를 숨길 수 없게 한다. 일반 내부 업무 타입까지 HTTP 공개 데이터로 허용하지 않는다.
- [ProductionScope](../architecture-tests/src/test/kotlin/com/exchange/architecture/support/ProductionScope.kt#L36)는 `MarketConfigurationStore`를 외부 포트로 등록한다. 핵심 타입이 사라지면 빈 검사 통과로 처리하지 않는다.
- [NamingPlacementScopeTest](../architecture-tests/src/test/kotlin/com/exchange/architecture/NamingPlacementScopeTest.kt#L131)는 폴더 21개가 25개가 된 이유와 새 4개 경로·포트 구현 매핑을 검증한다. [NamingPlacementIntegrationTest](../architecture-tests/src/test/kotlin/com/exchange/architecture/NamingPlacementIntegrationTest.kt#L28)와 [NamingPlacementGradleScenario](../architecture-tests/src/test/kotlin/com/exchange/architecture/NamingPlacementGradleScenario.kt#L32)는 기존 검사기 예제의 준비 정책에도 새 UseCase 영역을 넣는다. 제품 동작 테스트를 추가한 변경은 아니다.

본문 읽기는 전용 Converter로 옮겼고 Advice는 상위 `RequestBodyAdviceAdapter`를 사용하지 않는다. 따라서 그 상위 타입을 읽기 위해 추가했던 검사용 `spring-webmvc` 의존성도 제거했다. 제품의 validation 스타터는 기존 것을 사용하며 새 제품 의존성은 없다. Advice의 패키지 범위와 기존 경계 규칙은 유지한다.

구조 검사 준비에서는 축소된 fixture의 market 폴더 정책과 허용 package 목록 기대값을 보완했다. [NamingPlacementGradleAutomaticTest의 AUTO-26](../architecture-tests/src/test/kotlin/com/exchange/architecture/NamingPlacementGradleAutomaticTest.kt#L97)는 허용 package 기대값에 market 영역을 포함한다. 검사 제외나 위반 허용으로 실패를 숨긴 변경이 아니다. 최신 코드의 구조 검사 381개와 필수 운영 검사 P01~P09도 통과했다.

[ProductionBoundaryInputsTest](../architecture-tests/src/test/kotlin/com/exchange/architecture/ProductionBoundaryInputsTest.kt#L107)의 새 메서드 6개는 다음을 검사한다.

| 메서드별 목적·위치 | 실제 확인 |
| --- | --- |
| [정상 HTTP→UseCase→포트](../architecture-tests/src/test/kotlin/com/exchange/architecture/ProductionBoundaryInputsTest.kt#L107) | 실제 제품과 새 예제 타입을 검사 대상에 포함하며 HTTP/application/이름 규칙 위반 없음 |
| [HTTP의 저장·내부 업무 우회](../architecture-tests/src/test/kotlin/com/exchange/architecture/ProductionBoundaryInputsTest.kt#L141) | Store·Postgres 구현·일반 내부 업무 타입을 직접 참조한 위반 대상 집합이 정확히 일치 |
| [UseCase의 JDBC·구현 참조](../architecture-tests/src/test/kotlin/com/exchange/architecture/ProductionBoundaryInputsTest.kt#L169) | Connection·Postgres 구현 직접 참조를 정확히 검출 |
| [공개 결과·오류도 검사 포함](../architecture-tests/src/test/kotlin/com/exchange/architecture/ProductionBoundaryInputsTest.kt#L192) | 공개 5개 타입이 HTTP 데이터와 application 기술 의존 검사 양쪽에 포함 |
| [공개 결과 안의 숨은 JDBC](../architecture-tests/src/test/kotlin/com/exchange/architecture/ProductionBoundaryInputsTest.kt#L203) | 결과 타입을 JDBC 참조가 있는 실제 바이트코드로 바꿔 Connection 위반 검출 |
| [Store 공개 계약](../architecture-tests/src/test/kotlin/com/exchange/architecture/ProductionBoundaryInputsTest.kt#L231) | 새 Store가 ARCH06 검사 대상이며 계약 위반 없음 |

기존 [포트 누락 사례](../architecture-tests/src/test/kotlin/com/exchange/architecture/ProductionBoundaryInputsTest.kt#L633)에도 새 Store를 추가했다. 위반 예제가 검출되면 검사기 테스트가 통과하는 것이며, 이것만으로 DB·HTTP 동작까지 증명하지 않는다.

집중 테스트를 재실행하려면 Docker가 동작하는 환경에서 다음을 사용한다.

```sh
cd /Users/0chord/.codex/worktrees/issue118-market-registration/exchange-core
./gradlew :app-api:test \
  --tests '*PostgresMarketConfigurationStoreTest' \
  --tests '*DevelopmentMarketRegistrationMainTest' \
  --tests '*MarketConfigurationHttpTest'
```

이번 DTO 변경의 실제 재검증 명령은 다음과 같다. snapshot `311f1fd`의 제품 소스 그대로 실행한 `dto-final-verification-8.log`에서 **BUILD SUCCESSFUL in 2m 11s**를 확인했다.

```sh
cd /Users/0chord/.codex/worktrees/issue118-market-registration/exchange-core
./gradlew ktlintCheck :app-api:assemble :app-api:test \
  --tests '*MarketConfigurationHttpTest' \
  --tests '*OrderControllerTest' \
  --tests '*OrderLifecycleE2ETest' \
  :architecture-tests:test :architecture-tests:verifyArchitectureReport
```

| 최신 실행 범위 | 결과 |
| --- | --- |
| 관리자 HTTP 11개 + OrderController 9개 + OrderLifecycle 6개 | 26개 통과, 실패·오류·skip 각각 0 |
| 구조 테스트 | 381개 통과, 실패·오류·skip 각각 0 |
| `ktlintCheck`·`:app-api:assemble` | 통과 |
| 필수 운영 검사 P01~P09 | 실행·성공 확인 |

세부 결과는 `dto-final-test-results-8`에 보관했으며 `verification-summary.json`은 검증 범위를 기록한다. 변경하지 않은 Store·CLI·domain·Aeron의 전체 회귀는 `40ca0a0` 기준 기존 근거를 재사용한다. 당시 app-api 553개·domain 303개를 이번에 다시 실행한 것으로 합산하지 않는다. 저장소 전체 검사 명령인 `ktlintCheck build test :architecture-tests:verifyArchitectureReport`와 이번 영향 범위 재검증을 구분한다.

작성 대화를 상속하지 않은 별도 checkout의 독립 검토는 동일 snapshot `311f1fd`에서 기존 지적의 해소를 확인했으며, 확인된 미해결 제품 결함은 없었다. 실제 common 패키지 component scan·PostgreSQL·동적 포트 서버에서 조회·오류 50요청/85확인문과 정상·거절 흐름 41요청/80확인문, 합계 165확인문을 통과했다. 원본은 `dto-independent-review.md`에 기록했다. 자동 검사·독립 AI 검토를 사용자의 이해 확인이나 사람의 승인으로 해석하지 않는다. 게시 커밋의 원격 CI는 별도로 확인한다.

실제 COMMIT ACK 유실·commit 예외·CLI 출력 실패는 자동 재현하지 않았다. 해당 분기는 코드 검토와 후속 장애 검증의 대상으로 남아 있다. 테스트·AI 리뷰를 사용자의 이해 확인이나 사람의 승인으로 해석하지 않는다.

</details>

[db-tests]: ../app-api/src/test/kotlin/com/exchange/core/api/market/infrastructure/persistence/PostgresMarketConfigurationStoreTest.kt#L27
[cli-tests]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentMarketRegistrationMainTest.kt#L18
[http-tests]: ../app-api/src/test/kotlin/com/exchange/core/api/market/api/MarketConfigurationHttpTest.kt#L25
[db-42]: ../app-api/src/test/kotlin/com/exchange/core/api/market/infrastructure/persistence/PostgresMarketConfigurationStoreTest.kt#L42
[db-69]: ../app-api/src/test/kotlin/com/exchange/core/api/market/infrastructure/persistence/PostgresMarketConfigurationStoreTest.kt#L69
[db-89]: ../app-api/src/test/kotlin/com/exchange/core/api/market/infrastructure/persistence/PostgresMarketConfigurationStoreTest.kt#L89
[db-101]: ../app-api/src/test/kotlin/com/exchange/core/api/market/infrastructure/persistence/PostgresMarketConfigurationStoreTest.kt#L101
[db-118]: ../app-api/src/test/kotlin/com/exchange/core/api/market/infrastructure/persistence/PostgresMarketConfigurationStoreTest.kt#L118
[db-123]: ../app-api/src/test/kotlin/com/exchange/core/api/market/infrastructure/persistence/PostgresMarketConfigurationStoreTest.kt#L123
[db-128]: ../app-api/src/test/kotlin/com/exchange/core/api/market/infrastructure/persistence/PostgresMarketConfigurationStoreTest.kt#L128
[db-133]: ../app-api/src/test/kotlin/com/exchange/core/api/market/infrastructure/persistence/PostgresMarketConfigurationStoreTest.kt#L133
[db-161]: ../app-api/src/test/kotlin/com/exchange/core/api/market/infrastructure/persistence/PostgresMarketConfigurationStoreTest.kt#L161
[db-195]: ../app-api/src/test/kotlin/com/exchange/core/api/market/infrastructure/persistence/PostgresMarketConfigurationStoreTest.kt#L195
[db-210]: ../app-api/src/test/kotlin/com/exchange/core/api/market/infrastructure/persistence/PostgresMarketConfigurationStoreTest.kt#L210
[db-226]: ../app-api/src/test/kotlin/com/exchange/core/api/market/infrastructure/persistence/PostgresMarketConfigurationStoreTest.kt#L226
[db-252]: ../app-api/src/test/kotlin/com/exchange/core/api/market/infrastructure/persistence/PostgresMarketConfigurationStoreTest.kt#L252
[cli-35]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentMarketRegistrationMainTest.kt#L35
[cli-58]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentMarketRegistrationMainTest.kt#L58
[cli-71]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentMarketRegistrationMainTest.kt#L71
[cli-99]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentMarketRegistrationMainTest.kt#L99
[cli-107]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentMarketRegistrationMainTest.kt#L107
[http-44]: ../app-api/src/test/kotlin/com/exchange/core/api/market/api/MarketConfigurationHttpTest.kt#L44
[http-70]: ../app-api/src/test/kotlin/com/exchange/core/api/market/api/MarketConfigurationHttpTest.kt#L70
[http-190]: ../app-api/src/test/kotlin/com/exchange/core/api/market/api/MarketConfigurationHttpTest.kt#L190
[http-239]: ../app-api/src/test/kotlin/com/exchange/core/api/market/api/MarketConfigurationHttpTest.kt#L239
[http-265]: ../app-api/src/test/kotlin/com/exchange/core/api/market/api/MarketConfigurationHttpTest.kt#L265
[http-304]: ../app-api/src/test/kotlin/com/exchange/core/api/market/api/MarketConfigurationHttpTest.kt#L304

[http-149]: ../app-api/src/test/kotlin/com/exchange/core/api/market/api/MarketConfigurationHttpTest.kt#L149
[http-163]: ../app-api/src/test/kotlin/com/exchange/core/api/market/api/MarketConfigurationHttpTest.kt#L163

[http-211]: ../app-api/src/test/kotlin/com/exchange/core/api/market/api/MarketConfigurationHttpTest.kt#L211
[http-231]: ../app-api/src/test/kotlin/com/exchange/core/api/market/api/MarketConfigurationHttpTest.kt#L231

[http-116]: ../app-api/src/test/kotlin/com/exchange/core/api/market/api/MarketConfigurationHttpTest.kt#L116
