# 마켓 설정 등록·조회 — #118

**전체 마켓 설정을 지정한 버전으로 보관하고, 저장이 확인된 버전과 SHA-256을 받는다.** 사람이 직접 SQL을 넣는 대신 CLI와 관리자 API가 같은 검사·저장 기능을 사용한다. 응답을 놓쳐도 같은 요청으로 저장 결과를 다시 확인할 수 있다.

```text
CLI 배열 / API markets → 설정 내용 검사·비교 → 최초 문서 보관 → 버전 + 저장 문서 SHA-256
별도 노드 시작  → 버전 + SHA-256 지정 → DB 설정 읽기
```

| 요청 | 결과 |
| --- | --- |
| 없는 버전 7에 설정 A 등록 | 새 행 저장 후 `7 + 저장 문서의 SHA-256` 반환 |
| 공백·필드 순서·마켓 나열 순서만 바꿔 7번 재전송 | 같은 설정이므로 최초 결과 재사용, 저장 문서·등록 시각 유지 |
| 검증을 통과한 수수료·자산·수량 정책 등 실제 필드 변경 | 409 충돌, 기존 설정 유지 |
| 변경한 설정을 새 버전 8로 등록 | 새 행 생성, 버전 7 유지 |

**등록과 노드 적용은 별개다.** 등록은 노드 시작·실행 중 설정 변경·잔고 변경을 하지 않는다. 기존 버전의 수정·삭제 API도 없다. 적용하려면 별도로 노드를 시작할 때 등록 결과의 버전과 SHA-256을 지정한다.

**현재 상태:** 실제 필드 DTO와 내용 기준 중복 판정을 구현했고, 관련 테스트 66개·구조 검사 381개를 통과했다. 독립 검토에서 확인된 제품 결함은 없었다. 추가 Aeron 검사는 로컬 저장소 오류로 미완료이며 새 커밋의 원격 CI는 별도로 확인한다. 명시적으로 켠 API는 인증 없이 호출하며 인증 설계·관리 화면·실시간 적용은 후속이다.

<details markdown="1">
<summary>1. 실행 준비와 CLI 사용법</summary>

UTF-8 PostgreSQL에 기존 [V9 마이그레이션](../app-api/src/main/resources/db/migration/V9__create_market_configuration_release.sql)이 적용되어 있어야 한다. 등록 계정에는 대상 테이블의 SELECT·INSERT 권한이 필요하다. CLI는 Spring 서버나 노드를 시작하지 않으며, 마이그레이션도 실행하지 않는다.

아래 환경값을 실행 환경에 미리 주입한다. 자격정보를 명령 인자·JSON·DB URL 안에 넣지 않는다. 예시에는 비밀값을 기재하지 않았다.

| 환경값 | 용도 |
| --- | --- |
| `FUNDING_MARKET_DB_URL` | 자격정보가 없는 PostgreSQL JDBC URL |
| `FUNDING_MARKET_DB_USER` | 등록·조회 DB 계정 |
| `FUNDING_MARKET_DB_PASSWORD` | 해당 계정 비밀번호 |

`markets.json`은 BOM 없는 UTF-8 JSON 배열이다. 기존 파서가 마켓 1~32개와 필드·중복·수량·수수료 정책을 검사한다. 최대 65,536바이트이며, 새 버전을 만들 때 최초 파일의 공백·순서까지 그대로 보존한다. 같은 내용의 기존 버전이 있으면 그 행을 그대로 재사용한다. 입력 예:

```json
[{"marketId":"BTC-USDT","baseAssetId":"BTC","quoteAssetId":"USDT","baseQuantityScale":8,"feePolicy":{"productType":"SPOT","feeTier":"NORMAL","scheduleVersion":1,"makerPartsPerMillion":0,"takerPartsPerMillion":0}}]
```

구현 폴더는 `/Users/0chord/.codex/worktrees/issue118-market-registration/exchange-core`, 브랜치는 `feat/market-configuration-registration/118`이다. 실제 입력 파일의 절대 경로로 바꿔 실행한다. 다른 체크아웃에서는 각 `cd` 경로를 그 저장소의 절대 경로로 바꾼다.

```sh
cd /Users/0chord/.codex/worktrees/issue118-market-registration/exchange-core
./gradlew :app-api:runDevelopmentMarketRegistration \
  --args='--version 7 --document /absolute/config/markets.json'
```

성공 시 제품 main은 표준 출력으로 `CONFIRMED configVersion=7 sha256=<64자리 소문자 지문>`을 내보낸다. 같은 버전·같은 설정 내용의 재실행은 최초 저장 결과와 같은 출력을 반환한다. 반환 지문은 재전송 파일이 아니라 DB에 실제 보관된 배열 문서의 지문이다. 제품 main 종료 코드는 성공 `0`, 입력/충돌/확정 실패 `2`, 결과 미확인 `3`이다. Gradle을 통해 실행하면 자식 main의 비정상 종료가 Gradle 작업 실패로 보고되므로 셸이 받는 Gradle 종료 코드와 구분한다.

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

API 입력 파일 `market-registration.json`은 CLI 파일과 다르게 `markets` 필드를 가진 객체다. 마켓과 수수료의 실제 필드를 DTO로 받아 `@Valid`·Bean Validation으로 검사한다. HTTP 본문 전체의 한도는 65,536바이트다.

```json
{"markets":[{"marketId":"BTC-USDT","baseAssetId":"BTC","quoteAssetId":"USDT","baseQuantityScale":8,"feePolicy":{"productType":"SPOT","feeTier":"NORMAL","scheduleVersion":1,"makerPartsPerMillion":0,"takerPartsPerMillion":0}}]}
```

아래는 로컬 기본 포트 예시다. 서버가 다른 포트를 쓰면 주소를 바꾼다. 활성화된 API에는 인증 헤더 없이 요청한다.
```sh
cd /Users/0chord/.codex/worktrees/issue118-market-registration/exchange-core
curl --fail-with-body --header 'Content-Type: application/json' \
  --request PUT --data-binary @/absolute/config/market-registration.json \
  http://127.0.0.1:8080/api/admin/market-configurations/7

curl --fail-with-body \
  http://127.0.0.1:8080/api/admin/market-configurations/7

curl --fail-with-body \
  'http://127.0.0.1:8080/api/admin/market-configurations?limit=20'
```

| 요청 | 응답 내용 |
| --- | --- |
| `PUT /api/admin/market-configurations/{version}` | `markets` 객체의 설정을 등록. 신규·같은 내용 재전송 모두 200, `configVersion`, `sha256` |
| `GET /api/admin/market-configurations/{version}` | `configVersion`, `sha256`, DB에 보관된 배열 문서 문자열 `document`, `createdAt`. 없는 버전 404 |
| `GET /api/admin/market-configurations?beforeVersion=7&limit=20` | 7 미만 버전을 내림차순으로 조회. `items`에는 버전·지문·시각, 원문은 생략 |

PUT은 `Content-Type: application/json` 또는 `application/json; charset=UTF-8`로 `markets` 객체를 보낸다. Content-Type 누락·`text/plain`·잘못된 형식·`application/*`는 Spring의 `consumes=application/json` 조건에서 **415**로 거절하며 DB에 저장하지 않는다. 415는 본문 형식 오류이며 업무 오류의 `code/stage/configVersion` 형식을 보장하지 않는다. 기존 공통 오류 처리에도 415 처리를 명시해 IllegalArgumentException의 원인 매칭이 400으로 바꾸지 않게 했다.

응답은 JSON이다. `Accept: application/json`처럼 JSON을 받을 수 있는 요청을 사용한다. `Accept: text/plain`이나 JSON에 적용되는 `q=0`은 **DB 처리 전에 406**으로 거절한다. 이 검사는 PUT·상세 GET·목록 GET에 공통으로 적용한다. 406은 HTTP 응답 형식 협상 오류이며, 지원되는 요청에서 반환하는 아래의 정제된 입력·업무 오류와 별개다. 406에 업무 오류의 `code/stage/configVersion` 형식을 보장하지 않는다.

`q`는 해당 응답 형식을 받을 의향을 나타내며 `0`은 거절이다. JSON에 일치하는 범위 중 **`application/json` → `application/*` → `*/*` 순으로 더 구체적인 범위의 q**를 적용한다. 따라서 `application/json;q=0, */*;q=1`은 JSON을 명시적으로 거부했으므로 406이고, `application/json;q=1, */*;q=0`은 JSON을 명시적으로 허용했으므로 처리한다. 양수 q와 허용 wildcard도 지원한다. Spring의 `produces`만으로는 q=0 요청이 통과하는 경계가 있어 [마켓 설정 API 패키지에만 적용하는 공통 Advice](../app-api/src/main/kotlin/com/exchange/core/api/common/MarketConfigurationHttpExceptionHandler.kt#L55)의 `@ModelAttribute`·`@RequestHeader` 검사로 이 조건을 유지한다. Controller의 각 메서드는 `HttpServletRequest`를 받지 않는다.

`limit`은 기본 20, 허용 범위 1~100이다. 조회 조건은 기존 `@RequestParam`을 유지하며 limit의 `defaultValue="20"`, nullable beforeVersion을 사용한다. 따라서 `?limit=`은 20, `?beforeVersion=`은 커서 없음으로 처리하며 모두 정상 요청이다. 숫자가 아닌 값·Long 범위 초과·0 이하 버전/커서·한도 밖 limit은 400 `INVALID_INPUT`, `stage=request`로 거절한다. 알 수 없는 쿼리 키는 이전 `@RequestParam` 방식처럼 무시한다. `limit[0]` 같은 인덱스 키·중첩 키·`pageLimit`·폼 별칭(`!limit`, `_limit` 등)은 조회 조건을 바꾸지 않는다. `limit[]=1`은 Spring의 기존 `@RequestParam` 별칭 동작에 따라 limit 1로 처리한다. `limit=1&limit[0]=1`에서도 유효한 `limit=1`을 유지한다. 응답의 `nextBeforeVersion`을 다음 요청의 `beforeVersion`으로 전달한다. 페이지가 꽉 차면 마지막 버전을 반환하므로 **다음 페이지가 반드시 있다는 뜻은 아니다**. 다음 조회가 빈 목록일 수 있다. 전체 개수·활성 버전도 반환하지 않는다.

근거: [Controller](../app-api/src/main/kotlin/com/exchange/core/api/market/api/MarketConfigurationController.kt#L21), [활성화·조립](../app-api/src/main/kotlin/com/exchange/core/api/config/MarketConfigurationAdminConfig.kt#L14), [기본 설정](../app-api/src/main/resources/application.yaml).

</details>

<details markdown="1">
<summary>3. 요청이 저장 결과가 되기까지 — 실제 코드 흐름</summary>

```text
CLI: 배열 파일 한 번 읽기 ───────────────────────────┐
API: markets 객체 → 64KiB·UTF-8·필드 읽기 → DTO 검증
                   → document(request): markets를 배열 JSON으로 변환 ─┤
                                                                     ↓
MarketConfigurationUseCase → MarketConfigurationStore → PostgreSQL
  입력 배열 검사 → INSERT ON CONFLICT DO NOTHING → 별도 SELECT
  → 입력·저장 문서의 도메인 설정을 marketId 순서로 비교
  → 같은 내용이면 COMMIT / 다르면 충돌·롤백
  → 최초 저장 문서의 버전 + SHA-256
```

| 파일·진입점 | 하는 일과 이유 |
| --- | --- |
| [DevelopmentMarketRegistration.main](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentMarketRegistration.kt#L15) | 파일·환경·종료 코드·출력을 처리한다. 서버 없이 등록하는 진입점이다. |
| [MarketConfigurationController](../app-api/src/main/kotlin/com/exchange/core/api/market/api/MarketConfigurationController.kt#L21) | 양수 Long 버전·검증된 등록 Request DTO·기존 조회 파라미터로 UseCase를 호출하고, 결과를 `Response.from`으로 변환한다. |
| [MarketConfigurationRegistrationRequest](../app-api/src/main/kotlin/com/exchange/core/api/market/api/MarketConfigurationRegistrationRequest.kt#L12) | `markets` 목록과 마켓·수수료의 실제 입력 필드, 중첩 검증을 담는다. |
| [MarketConfigurationResponses](../app-api/src/main/kotlin/com/exchange/core/api/market/api/MarketConfigurationResponses.kt#L9) | 등록·상세·요약·페이지·오류 DTO를 관리한다. application 결과를 기존 응답 키로 명시적으로 변환한다. |
| [MarketConfigurationRequestConverter](../app-api/src/main/kotlin/com/exchange/core/api/market/api/MarketConfigurationRequestConverter.kt#L26) | 전체 HTTP 본문을 최대 65,537바이트만 읽어 한도를 검사하고, 엄격한 UTF-8과 Jackson 필드 읽기로 등록 DTO를 만든다. |
| [MarketConfigurationHttpExceptionHandler](../app-api/src/main/kotlin/com/exchange/core/api/common/MarketConfigurationHttpExceptionHandler.kt#L26) | 마켓 설정 API 패키지에만 적용되는 Advice다. JSON 응답 수용 조건과 입력·업무 오류의 HTTP 응답 변환을 모은다. |
| [기존 ApiExceptionHandler](../app-api/src/main/kotlin/com/exchange/core/api/common/ApiExceptionHandler.kt#L27) | `HttpMediaTypeNotSupportedException`을 명시적으로 415로 반환한다. 기존 IllegalArgumentException 원인 매칭이 본문 형식 오류를 400으로 바꾸는 일을 막는다. |
| [MarketConfigurationUseCase](../app-api/src/main/kotlin/com/exchange/core/api/market/application/MarketConfigurationUseCase.kt#L7) | 공통 버전·크기·페이지 한도를 검사하고, 미존재를 명시적 오류로 바꾼다. |
| [MarketConfigurationStore](../app-api/src/main/kotlin/com/exchange/core/api/market/application/port/MarketConfigurationStore.kt#L7) | 등록·상세·목록 저장 계약이다. HTTP나 JDBC 타입을 요구하지 않는다. |
| [PostgresMarketConfigurationStore.register](../app-api/src/main/kotlin/com/exchange/core/api/market/infrastructure/persistence/PostgresMarketConfigurationStore.kt#L22) | 실제 DB 등록·경합·도메인 설정 내용 비교·최초 저장 지문 반환·커밋과 실패 분류를 맡는다. |
| [validateDocument·release](../app-api/src/main/kotlin/com/exchange/core/api/market/infrastructure/persistence/PostgresMarketConfigurationStore.kt#L154) | 손상 UTF-8·BOM·NUL을 거절하고 기존 문서 파서를 재사용한다. DB에서 읽은 문서도 재검사해 손상 내용을 정상 설정으로 반환하지 않는다. |
| [MarketConfigurationDatabaseSettings](../app-api/src/main/kotlin/com/exchange/core/api/market/infrastructure/persistence/MarketConfigurationDatabaseSettings.kt#L9) | 필수 환경과 기존 URL 규칙을 검사하고 유한한 연결·SQL 대기를 적용한다. |
| [MarketConfigurationAdminConfig](../app-api/src/main/kotlin/com/exchange/core/api/config/MarketConfigurationAdminConfig.kt#L14) | 활성화 때 UseCase·PostgreSQL 구현과 RequestConverter Bean을 조립한다. Spring Boot가 이 변환기를 HTTP 변환기에 등록한다. |

**API 요청은 `{"markets":[...]}`이고 DB·CLI 문서는 기존 JSON 배열이다.** 등록 DTO에는 바이트 덩어리 대신 `marketId`, 자산 ID, 수량 소수 자릿수와 `feePolicy`의 실제 필드를 둔다. Controller의 `@Valid @RequestBody`와 중첩 Bean Validation으로 입력을 검사한다. [`document(request)`](../app-api/src/main/kotlin/com/exchange/core/api/market/api/MarketConfigurationRequestConverter.kt#L81)는 검증한 markets를 기존 배열 JSON으로 써서 Store에 전달한다. 따라서 새 API 등록은 직렬화된 배열 문서를 저장하며 요청의 공백·필드 순서를 그대로 보관하지 않는다.

[전용 Converter](../app-api/src/main/kotlin/com/exchange/core/api/market/api/MarketConfigurationRequestConverter.kt#L52)는 전체 HTTP 본문의 64KiB 한도, 엄격한 UTF-8 읽기, Jackson을 통한 DTO 필드 읽기만 담당한다. 최대 65,537바이트까지만 읽어 초과를 거절한다. 입력 필드의 제약은 DTO 검증이, 마켓의 업무 규칙은 기존 파서·도메인이 확인한다. Converter는 등록 Request만 처리하고 응답 변환에는 참여하지 않는다. Content-Type은 Controller의 `consumes=application/json` 조건으로 검사한다.

| 입력 필드 | DTO 검증 |
| --- | --- |
| `markets` | 1~32개, 각 마켓 중첩 검증 |
| marketId·baseAssetId·quoteAssetId | 빈 문자열·공백만 있는 값 거절 |
| `baseQuantityScale` | 0~18 |
| `feePolicy.productType`·`feeTier` | SPOT / NORMAL·VIP·VVIP·VVVIP |
| `scheduleVersion` | 양수 Long |
| maker/takerPartsPerMillion | 각각 0~1,000,000 |


필수 본문·필드 형식·DTO 검증·숫자 변환 오류는 공통 Advice에서 400 `INVALID_INPUT`, `stage=request`로 정제한다. Store에 전달된 배열 문서의 규칙 오류도 저장 전에 거절한다. JSON 중복 필드·알 수 없는 필드·뒤따르는 추가 JSON·누락/null·문자/숫자 자동 변환도 Jackson 설정으로 거절한다.

[목록 조회](../app-api/src/main/kotlin/com/exchange/core/api/market/api/MarketConfigurationController.kt#L40)는 기존 `@RequestParam` 두 개를 유지한다. `beforeVersion: Long?`은 `@Positive`, 기본값 20인 `limit: Int`는 `@Min(1)`·`@Max(100)`으로 검증한다. 누락·빈 limit은 20, 누락·빈 beforeVersion은 커서 없음으로 처리한다. 입력 본문과 응답만 별도 DTO로 관리하므로 조회 파라미터를 객체 속성으로 확장하거나 헤더에서 가져오지 않는다. 알 수 없는 중첩·폼 별칭 키는 무시하고 Spring의 기존 `limit[]` 별칭은 유지한다. 경로의 버전도 `@Positive Long`이며 기존 [starter-validation](../app-api/build.gradle.kts#L56)의 Hibernate Validator를 사용한다.

Advice는 `@ControllerAdvice(basePackages=["com.exchange.core.api.market.api"])`로 범위를 정하며 이 패키지의 향후 Controller도 포함한다. Advice는 Accept 검사와 HTTP 오류 응답을 담당한다. [최고 우선순위 선언](../app-api/src/main/kotlin/com/exchange/core/api/common/MarketConfigurationHttpExceptionHandler.kt#L24)으로 이 범위의 입력·업무 오류를 정제된 응답으로 먼저 처리한다. `@Order(Ordered.HIGHEST_PRECEDENCE)`는 마켓 범위에서 이 처리 순서를 명시한다. 정상 결과는 [등록·상세·요약·페이지 Response](../app-api/src/main/kotlin/com/exchange/core/api/market/api/MarketConfigurationResponses.kt#L9)의 `from`에서 application 결과를 명시적으로 옮기고, 오류도 [FailureResponse.from](../app-api/src/main/kotlin/com/exchange/core/api/market/api/MarketConfigurationResponses.kt#L65)으로 변환한다. HTTP 응답 키는 기존 계약을 유지한다.

**중복은 JSON 모양보다 설정 내용을 기준으로 판정한다.** 입력 배열과 기존 저장 배열을 기존 파서로 읽고 [marketId 순으로 정렬한 도메인 마켓 목록](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentMarketRegistry.kt#L59)을 [비교한다](../app-api/src/main/kotlin/com/exchange/core/api/market/infrastructure/persistence/PostgresMarketConfigurationStore.kt#L65). 공백·JSON 필드 순서·마켓 배열 순서는 비교에 영향을 주지 않는다. 수수료나 자산, 수량 정책 등 실제 필드가 바뀌면 409다. 예를 들어 BTC·SUI 두 설정을 SUI·BTC 순으로 다시 보내도 정책이 같으면 같은 버전의 최초 결과를 받는다. BTC 수수료를 바꾸면 새 버전이 필요하다.

내용 비교와 저장 지문은 역할이 다르다. 내용이 같으면 **최초 저장 문서·SHA-256·createdAt을 그대로 재사용**한다. 신규 CLI 등록은 최초 파일 배열의 원문을, 신규 API 등록은 DTO에서 직렬화한 배열을 보관한다. SHA-256은 이 실제 저장 문서에서 계산하므로 기존 노드의 버전·지문 지정 방식을 유지한다. 기존 행을 재직렬화하거나 수정하지 않으며 새 스키마도 필요 없다.

같은 버전의 경합은 PK가 최종적으로 정한다. `INSERT ... ON CONFLICT DO NOTHING` 뒤 별도 SELECT로 최초 문서를 읽어 내용을 비교한다. COMMIT 확인 후 그 저장 문서의 지문을 반환하며, 다른 내용이면 롤백하고 기존 행을 보존한다. V9의 UPDATE·DELETE·TRUNCATE 금지도 유지한다.

상세는 원문까지, 목록은 버전·지문·시각만 제공한다. 조회 시 저장 원문의 검증·지문 계산을 다시 수행한다. 목록은 `config_version < beforeVersion`, 내림차순, 제한 건수로 조회한다.

연결·소켓·로그인은 5초, SQL·취소 신호는 3초로 제한한다. 사용자가 URL에 무제한 옵션을 넣어도 뒤에 고정한 제한이 적용된다. 이 값들은 단계별 제한이며 전체 요청이 항상 그 시간 안에 끝난다는 보장은 아니다.

</details>

<details markdown="1">
<summary>4. 실패했을 때 무엇을 다시 해야 하는가</summary>

| 코드 | HTTP / 제품 main 종료 | 의미와 대응 |
| --- | --- | --- |
| `INVALID_INPUT` | 400 / 2 | 버전·문서·환경·URL 등 입력 오류. HTTP 숫자 변환·Bean Validation·본문 읽기 오류는 `stage=request`이며, Content-Type 오류 415와 구분한다. 입력을 고친다. |
| `CONTENT_CONFLICT` | 409 / 2 | 같은 버전에 다른 설정 내용이 있다. 기존 설정으로 재확인하거나 변경 내용을 새 명시 버전에 등록한다. |
| `NOT_FOUND` | 404 / 해당 없음 | 유효한 상세 조회에서 버전이 없다. |
| `REGISTRATION_FAILED` | 503 / 2 | 연결 전 실패 또는 쓰기 후 롤백이 확인된 실패. 이번 호출의 새 저장이 없다고 판단한다. 기존 행이 없다는 뜻은 아니다. |
| `RESULT_UNCONFIRMED` | 503 / 3 | 저장됐는지 아직 모르는 상태. **동일 버전·동일 설정 내용으로 재전송**한다. |
| `LOOKUP_FAILED` | 503 / 해당 없음 | DB 접근이나 저장 문서 검증에 실패했다. 버전 없음으로 해석하지 않는다. |

JSON 응답을 지원하는 요청의 입력·업무 오류에는 `code`, `stage`, 알 수 있을 때 `configVersion`만 전달한다. 지원하지 않는 Content-Type의 415와 JSON 응답을 받지 않는 Accept의 406은 쓰기 전 HTTP 형식 오류이며 이 업무 오류 형식과 구분한다. 문서 원문·DB 주소·계정·비밀번호·하위 JDBC 예외는 외부 오류에 담지 않는다. `stage`는 `connection`, `insert`, `compare`, `commit` 등 실패 위치를 나타낸다.

쓰기 시도 뒤 커밋을 시도했거나, DB 연결이 끊겼거나, 롤백 성공을 확인하지 못하면 결과 미확인으로 처리한다. 롤백을 한 번 시도했다는 사실만으로 이미 반영된 커밋이 취소됐다고 단정하지 않는다. 커밋 성공 뒤 연결 정리 오류는 성공을 뒤집지 않는다. CLI에서 성공 출력 전달 실패가 감지되어도 저장이 없었다고 바꾸지 않고 `RESULT_UNCONFIRMED stage=response`로 끝낸다.

자동 버전 증가·기존 행 삭제·다른 기본 설정으로 대체하는 복구는 없다. 실제 COMMIT 응답 유실이나 출력 전달 실패를 이번 자동 테스트에서 재현한 것은 아니다.

근거: [저장 예외 분류·정리](../app-api/src/main/kotlin/com/exchange/core/api/market/infrastructure/persistence/PostgresMarketConfigurationStore.kt#L22), [오류 자료형](../app-api/src/main/kotlin/com/exchange/core/api/market/application/MarketConfigurationUseCase.kt#L63), [HTTP 오류 변환](../app-api/src/main/kotlin/com/exchange/core/api/common/MarketConfigurationHttpExceptionHandler.kt#L37).

</details>

<details markdown="1">
<summary>5. 제품 테스트 — 준비·실행·실제 확인</summary>

등록 기능의 현재 테스트는 **Store 15개·CLI 5개·HTTP 15개**다. 실제 PostgreSQL과 별도 JVM의 CLI·HTTP 서버를 사용하며 MockMvc나 DB mock으로 대신하지 않는다. [HTTP 서버](../app-api/src/test/kotlin/com/exchange/core/api/market/api/MarketConfigurationHttpTestServer.kt#L16)는 임의 포트로 시작하고, [전역 Handler를 먼저 Import](../app-api/src/test/kotlin/com/exchange/core/api/market/api/MarketConfigurationHttpTestServer.kt#L35)해도 정제된 오류 응답을 유지하는지 확인한다.

#### Store 15개

| 사례·코드 | 준비 → 실행 | 실제 확인 |
| --- | --- | --- |
| [최초 원문 재실행][db-43] | 유효 배열을 등록·상세·재등록·기존 로더로 읽기 | 고정 SHA·같은 결과·한 행·원문/시각 불변·로더 V/H 일치 |
| [동등한 표현][db-70] | BTC·SUI 배열의 공백·필드 순서·마켓 순서를 바꿔 재등록 | 최초 결과·상세 불변·한 행, 최초 문서의 독립 SHA, 로더 BTC/SUI와 지문 일치 |
| [실제 수수료 변경][db-97] | 같은 버전과 새 버전에 변경한 정책 등록 | 같은 버전 CONTENT_CONFLICT·상세 불변, 새 버전의 변경 문서·고정 SHA |
| [동등 내용 동시 등록][db-115] | 표현이 다른 두 배열을 latch 뒤 실제 두 연결로 등록 | 두 성공·한 행, 승자 문서의 SHA를 두 응답이 공유, 후속 재등록에도 최초 상세 유지 |
| [상이 내용 동시 등록][db-134] | 수수료가 다른 두 배열을 동시에 등록 | 한 성공·한 충돌·한 행, 승자 원문에 맞는 고정 SHA |
| [앞 커밋·같은 입력][db-151] | 앞 INSERT 미커밋·뒤 연결 PK 대기 → 앞 COMMIT | 최초 원문·지문·한 행 |
| [앞 커밋·동등 표현][db-156] | 다른 표현으로 실제 PK 대기 → 앞 COMMIT | 입력 표현 대신 앞서 저장한 원문·지문·한 행 |
| [앞 커밋·상이 내용][db-161] | 다른 정책으로 실제 PK 대기 → 앞 COMMIT | CONTENT_CONFLICT·최초 원문·한 행 |
| [앞 롤백][db-166] | 앞 INSERT 미커밋·뒤 등록 대기 → 앞 ROLLBACK | 뒤 요청 문서·지문·한 행 |
| [잠금 제한·회복][db-171] | 실제 테이블 잠금, URL 무제한 옵션 → 등록·해제·재등록 | 12초 검사 한도 내 REGISTRATION_FAILED/insert·cause 없음, 0행 뒤 정상 지문 |
| [INSERT 후 SELECT 거절][db-199] | INSERT·버전 SELECT만 가능한 역할 → 원문 조회 실패 | REGISTRATION_FAILED/compare·0행·비밀/cause 비노출, 정상 계정 재등록 성공 |
| [페이지 조회][db-233] | 격리 DB에 네 버전 → 두 페이지·빈 페이지·없는 상세 | 정확한 내림차순 버전·고정 SHA·빈 목록·null 상세 |
| [손상 저장 문서][db-248] | fixture로 빈 배열 저장 → 상세·목록 | 둘 다 LOOKUP_FAILED/stored-document·cause 없음 |
| [UTF-8·크기][db-264] | 최대 크기 배열과 초과/손상/BOM/NUL/빈 입력 | 정상 원문 전체 보존, 오류 INVALID_INPUT/document·0행·cause 없음 |
| [접속 전 실패][db-290] | 닫힌 포트·별도 자격정보 → 등록 | REGISTRATION_FAILED/connection·cause/주소/계정/비밀번호/문서 비노출 |

동시 등록의 승자는 고정하지 않는다. 저장된 후보 문서에 맞는 SHA를 테스트 쪽에서 계산하고 두 응답을 대조한다. [PK 대기 helper](../app-api/src/test/kotlin/com/exchange/core/api/market/infrastructure/persistence/PostgresMarketConfigurationStoreTest.kt#L341)는 `pg_stat_activity`의 Lock 대기와 아직 끝나지 않은 작업을 확인하므로 단순 sleep을 경합 근거로 삼지 않는다.

#### CLI 별도 JVM 5개

| 사례·코드 | 준비 → 실행 | 실제 확인 |
| --- | --- | --- |
| [첫 출력 미소비 후 재실행][cli-35] | 첫 출력을 다음 입력으로 쓰지 않고 같은 명령 실행 | 두 종료 0·고정 stdout·빈 stderr·최초 문서/시각·로더 BTC/SUI와 SHA |
| [표현 재사용·실제 변경][cli-58] | 공백·순서를 바꿔 재실행, 수수료를 바꿔 같은/새 버전 | 동등 입력은 종료 0·같은 stdout·최초 행 불변, 실제 변경은 종료 2/충돌·새 버전 성공 |
| [입력·옵션 오류][cli-82] | 손상 UTF-8/BOM/빈 파일/잘못된 JSON/초과, 중복 옵션·버전 0 | 종료 2·성공 출력 없음, 문서 오류 INVALID_INPUT·원문 비노출·행 없음 |
| [최대 크기 원문][cli-110] | 정확히 65,536바이트 → main 실행 | 종료 0·DB 파일 원문 전체 일치 |
| [환경·접속 오류][cli-118] | 필수 환경 누락·자격정보 포함 URL·닫힌 포트 | 종료 2·빈 stdout·행 없음, 주소/자격정보/원문/stack trace 비노출 |

첫 출력 미소비 테스트는 실제 TCP COMMIT ACK 유실 실험이 아니다. 테스트 도구는 첫 stdout도 수집·비교한다.

#### 실제 HTTP 15개

| 사례·코드 | 준비 → 실행 | 실제 확인 |
| --- | --- | --- |
| [DTO 등록·동등 표현·충돌][http-44] | markets 요청 → 동일/들여쓰기/필드 재배열, 수량 scale 변경 | 최초 배열·고정 SHA·상세 키/시각 유지, 실제 변경 409·한 행, 새 버전200·없는 상세404·DELETE405 |
| [페이지·쿼리 한도][http-81] | 10·20번 → limit1 페이지·빈/잘못된 쿼리 | 정확한 페이지/요약 키·버전 순서·원문 생략·마지막 다음 빈 페이지, 정상200·오류400 |
| [지원하지 않는 조회 키][http-127] | 두 건 이상 확보 → 알 수 없는 키10개·혼합·limit[] | 모두200, 키10개는 기본 응답 전체와 동일, 혼합/limit[]는 limit1 응답과 동일 |
| [Content-Type][http-160] | 누락/text/plain/잘못된 형식/application/* → 정상 JSON 두 표기 | 오류415·매번0행, 정상200·최종1행·저장 배열 일치 |
| [Accept][http-174] | 거절6개(text/plain/q=0)·허용5개로 PUT·GET | 거절 PUT406·0행, 허용PUT200, 거절 상세/목록406, 최종1행·저장 배열 일치 |
| [전체 본문 바이트·버전][http-201] | 최대 크기의 markets 요청·초과/손상/빈/빈목록·잘못된 버전 | 최대200·저장은 패딩 없는 배열, 오류400/INVALID_INPUT·0행, 초과/빈본문 stage=request |
| [숫자 변환·Bean Validation][http-223] | 잘못된 path·beforeVersion·limit → PUT/GET | 400·INVALID_INPUT/request·오류 키 code/stage/configVersion 정확히 일치 |
| [중첩 필드 제약][http-243] | 빈 ID·scale 범위·상품/등급·정책버전·수수료·33마켓 오류 | 각 입력400·INVALID_INPUT/request·0행·정제 응답 |
| [엄격한 JSON 읽기][http-264] | 배열 직접전송/null/누락/중복/추가필드/자동형변환/추가JSON | 각 입력400·INVALID_INPUT/request·0행·정제 응답 |
| [허용 필드 경계][http-297] | 32마켓, scale0/18, 수수료1,000,000 | 200·저장 항목32개·첫/마지막 scale0/18·1행 |
| [한국어·잘못된 Unicode][http-319] | 한국어 ID 등록, 짝 없는 surrogate escape 두 개 | 한국어 ID/자산 보존, 오류400/INVALID_INPUT·0행·정제 응답 |
| [미완성 큰 본문][http-339] | Content-Length10000000 또는 종료하지 않은 chunked에65,537바이트만 전송 | 본문 완료 전400·각 요청 뒤0행 |
| [SELECT 권한 실패][http-347] | 제한 계정 서버 → PUT·GET | PUT503 REGISTRATION_FAILED/compare·0행, GET503 LOOKUP_FAILED·비밀 비노출 |
| [쓰기 연결 단절][http-373] | INSERT 뒤 연결을 끊는 trigger → PUT·복구·재전송 | 503 RESULT_UNCONFIRMED/insert·정제 응답·실제0행, 복구 후200 |
| [기본 비활성화][http-412] | 활성화/DB 환경 없는 서버 → 목록·PUT·상세 | 시작 성공·모두404·새 행 없음 |

[입력 오류 helper](../app-api/src/test/kotlin/com/exchange/core/api/market/api/MarketConfigurationHttpTest.kt#L437)는 상태·code·stage·DB 0행·비밀 비노출을 직접 비교한다. 숫자 path/query 사례는 오류 키 집합을 확인하지만 configVersion의 값과 DB 행 수를 별도로 비교하지 않는다. 허용 필드 경계 사례는 수수료 상한을 입력해 등록 성공을 확인하며 저장 수수료값 자체를 따로 assert하지 않는다.

[소켓 helper](../app-api/src/test/kotlin/com/exchange/core/api/market/api/MarketConfigurationHttpTest.kt#L520)는 5초 소켓 제한 안에 상태 줄의 400을 확인한다. 응답 JSON code/stage까지 검사하지는 않는다. API의 최대 본문 테스트는 요청 패딩 보존을 요구하지 않으며, 실제 저장 배열은 고정 DOCUMENT와 비교한다. 실제 COMMIT ACK 유실·commit 예외·CLI 출력 실패는 자동 재현하지 않았다.

</details>

<details markdown="1">
<summary>6. 구조 검사를 바꾼 이유와 검증 범위</summary>

새 market 코드를 기존 검사 대상에 포함시켜, HTTP가 저장소를 바로 호출하거나 UseCase가 JDBC에 묶이는 우회를 계속 감지하려는 변경이다. 검사 규칙을 완화하지 않았다.

- [ProjectLayoutPolicy](../architecture-tests/src/test/kotlin/com/exchange/architecture/policy/ProjectLayoutPolicy.kt#L34)는 `market/api`, `market/application`, `market/application/port`, `market/infrastructure/persistence`의 정확한 위치·역할을 등록한다. [NamingRules](../architecture-tests/src/test/kotlin/com/exchange/architecture/rules/NamingRules.kt#L220)는 이 영역의 Controller·UseCase·Store·HTTP 데이터 이름과 위치를 검사한다.
- [ProductionBoundaryInputs](../architecture-tests/src/test/kotlin/com/exchange/architecture/support/ProductionBoundaryInputs.kt#L24)는 Controller→UseCase와 공개 결과·오류 5개 타입을 연결한다. 공개 데이터도 application의 기술 의존 검사에 남겨, 결과 객체 안에 JDBC를 숨길 수 없게 한다. 일반 내부 업무 타입까지 HTTP 공개 데이터로 허용하지 않는다.
- [ProductionScope](../architecture-tests/src/test/kotlin/com/exchange/architecture/support/ProductionScope.kt#L36)는 `MarketConfigurationStore`를 외부 포트로 등록한다. 핵심 타입이 사라지면 빈 검사 통과로 처리하지 않는다.
- [NamingPlacementScopeTest](../architecture-tests/src/test/kotlin/com/exchange/architecture/NamingPlacementScopeTest.kt#L131)는 폴더 21개가 25개가 된 이유와 새 4개 경로·포트 구현 매핑을 검증한다. [NamingPlacementIntegrationTest](../architecture-tests/src/test/kotlin/com/exchange/architecture/NamingPlacementIntegrationTest.kt#L28)와 [NamingPlacementGradleScenario](../architecture-tests/src/test/kotlin/com/exchange/architecture/NamingPlacementGradleScenario.kt#L32)는 기존 검사기 예제의 준비 정책에도 새 UseCase 영역을 넣는다. 제품 동작 테스트를 추가한 변경은 아니다.

본문 읽기는 전용 Converter로 옮겼고 Advice는 상위 `RequestBodyAdviceAdapter`를 사용하지 않는다. 따라서 그 상위 타입을 읽기 위해 추가했던 검사용 `spring-webmvc` 의존성도 제거했다. 제품의 validation 스타터는 기존 것을 사용하며 새 제품 의존성은 없다. Advice의 패키지 범위와 기존 경계 규칙은 유지한다.

구조 검사 준비에서는 축소된 fixture의 market 폴더 정책과 허용 package 목록 기대값을 보완했다. [NamingPlacementGradleAutomaticTest의 AUTO-26](../architecture-tests/src/test/kotlin/com/exchange/architecture/NamingPlacementGradleAutomaticTest.kt#L97)는 허용 package 기대값에 market 영역을 포함한다. 검사 제외나 위반 허용으로 실패를 숨긴 변경이 아니다. snapshot `11bb1f7`의 구조 검사 381개와 필수 운영 검사 P01~P09를 통과했다.

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

snapshot `11bb1f7`의 집중 검사는 `typed-focused-verification.log`에서 **BUILD SUCCESSFUL in 2m 45s**로 끝났다.

| 실제 실행 범위 | 결과 |
| --- | --- |
| HTTP15 + Store15 + CLI5 + Settings8 + PostgresSettings8 + OrderController9 + OrderLifecycle6 | 66개 통과, 실패·오류·skip 각각0 |
| 구조 테스트 | 381개 통과, 실패·오류·skip 각각0 |
| ktlintCheck·app-api assemble·필수 운영 검사 P01~P09 | 통과 |

```sh
cd /Users/0chord/.codex/worktrees/issue118-market-registration/exchange-core
./gradlew ktlintCheck :app-api:assemble :app-api:test \
  --tests '*MarketConfigurationHttpTest' \
  --tests '*PostgresMarketConfigurationStoreTest' \
  --tests '*DevelopmentMarketRegistrationMainTest' \
  --tests '*DevelopmentMarketSettingsTest' \
  --tests '*OrderControllerTest' \
  --tests '*OrderLifecycleE2ETest' \
  :architecture-tests:test :architecture-tests:verifyArchitectureReport
```

`*DevelopmentMarketSettingsTest`는 일반 Settings와 PostgresSettings 두 클래스를 포함한다. 원본 XML은 `typed-focused-test-results`, 실행 범위는 `verification-summary.json`에 보관한다. 이 결과는 전체 앱·domain·Aeron suite를 모두 새로 실행했다는 뜻이 아니다. 이전 바이트 동일성 계약의 완료 수치를 이번 결과에 합산하지 않는다.

작성 대화를 상속하지 않은 독립 검토는 동일 snapshot `11bb1f7`에서 확인된 제품 결함 0건을 보고했다. 실제 PostgreSQL·별도 JVM HTTP 서버·CLI로 **HTTP 97요청·CLI 3실행·399확인문**을 통과했다. CLI→API/API→CLI 재전송과 동시 등록, 표현 차이, 유효한 실제 필드 변경, 저장 지문·노드 로더, 엄격 입력과 손상 기존 행을 직접 대조했다. 원본은 `typed-independent-review.md`다. 이 독립 확인문 수는 위 JUnit 66개에 합산하지 않는다.

이전 HEAD `618c73a`의 원격 CI는 `DockerOrderReplicationTest`의 ACCEPTED_REPLAY A-2 egress 연결 60초 시간 초과로 실패했다. 해당 시나리오는 등록 API·DB 경로를 쓰지 않는다. 이번 로컬 재검사에서는 그 사례를 포함한 앞선 6개 시나리오의 `success=true`와 빈 issues를 확인했으나, 뒤의 보고서 기록 중 `No space left on device`로 전체 JUnit 실행이 실패했다. 테스트 Archive 사본을 내용 검증 후 압축해 공간을 확보했지만, 재시도는 Docker 저장소의 `meta.db: input/output error`로 이미지 준비부터 실패했다. `typed-aeron-recheck.log`, `typed-aeron-recheck-2.log`와 실패 XML을 보관했으며 **추가 복제 검사 전체는 미완료**다. 두 환경 실패를 위의 집중 검사 66개·구조 381개 통과에 합산하지 않는다. 새 HEAD 원격 CI는 별도로 확인한다.

실제 COMMIT ACK 유실·commit 예외·CLI 출력 실패는 자동 재현하지 않았다. 자동 검사·독립 AI 검토는 사용자의 이해 확인이나 사람의 승인을 대신하지 않는다.

</details>

[db-43]: ../app-api/src/test/kotlin/com/exchange/core/api/market/infrastructure/persistence/PostgresMarketConfigurationStoreTest.kt#L43

[db-70]: ../app-api/src/test/kotlin/com/exchange/core/api/market/infrastructure/persistence/PostgresMarketConfigurationStoreTest.kt#L70

[db-97]: ../app-api/src/test/kotlin/com/exchange/core/api/market/infrastructure/persistence/PostgresMarketConfigurationStoreTest.kt#L97

[db-115]: ../app-api/src/test/kotlin/com/exchange/core/api/market/infrastructure/persistence/PostgresMarketConfigurationStoreTest.kt#L115

[db-134]: ../app-api/src/test/kotlin/com/exchange/core/api/market/infrastructure/persistence/PostgresMarketConfigurationStoreTest.kt#L134

[db-151]: ../app-api/src/test/kotlin/com/exchange/core/api/market/infrastructure/persistence/PostgresMarketConfigurationStoreTest.kt#L151

[db-156]: ../app-api/src/test/kotlin/com/exchange/core/api/market/infrastructure/persistence/PostgresMarketConfigurationStoreTest.kt#L156

[db-161]: ../app-api/src/test/kotlin/com/exchange/core/api/market/infrastructure/persistence/PostgresMarketConfigurationStoreTest.kt#L161

[db-166]: ../app-api/src/test/kotlin/com/exchange/core/api/market/infrastructure/persistence/PostgresMarketConfigurationStoreTest.kt#L166

[db-171]: ../app-api/src/test/kotlin/com/exchange/core/api/market/infrastructure/persistence/PostgresMarketConfigurationStoreTest.kt#L171

[db-199]: ../app-api/src/test/kotlin/com/exchange/core/api/market/infrastructure/persistence/PostgresMarketConfigurationStoreTest.kt#L199

[db-233]: ../app-api/src/test/kotlin/com/exchange/core/api/market/infrastructure/persistence/PostgresMarketConfigurationStoreTest.kt#L233

[db-248]: ../app-api/src/test/kotlin/com/exchange/core/api/market/infrastructure/persistence/PostgresMarketConfigurationStoreTest.kt#L248

[db-264]: ../app-api/src/test/kotlin/com/exchange/core/api/market/infrastructure/persistence/PostgresMarketConfigurationStoreTest.kt#L264

[db-290]: ../app-api/src/test/kotlin/com/exchange/core/api/market/infrastructure/persistence/PostgresMarketConfigurationStoreTest.kt#L290

[cli-35]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentMarketRegistrationMainTest.kt#L35

[cli-58]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentMarketRegistrationMainTest.kt#L58

[cli-82]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentMarketRegistrationMainTest.kt#L82

[cli-110]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentMarketRegistrationMainTest.kt#L110

[cli-118]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentMarketRegistrationMainTest.kt#L118

[http-44]: ../app-api/src/test/kotlin/com/exchange/core/api/market/api/MarketConfigurationHttpTest.kt#L44

[http-81]: ../app-api/src/test/kotlin/com/exchange/core/api/market/api/MarketConfigurationHttpTest.kt#L81

[http-127]: ../app-api/src/test/kotlin/com/exchange/core/api/market/api/MarketConfigurationHttpTest.kt#L127

[http-160]: ../app-api/src/test/kotlin/com/exchange/core/api/market/api/MarketConfigurationHttpTest.kt#L160

[http-174]: ../app-api/src/test/kotlin/com/exchange/core/api/market/api/MarketConfigurationHttpTest.kt#L174

[http-201]: ../app-api/src/test/kotlin/com/exchange/core/api/market/api/MarketConfigurationHttpTest.kt#L201

[http-223]: ../app-api/src/test/kotlin/com/exchange/core/api/market/api/MarketConfigurationHttpTest.kt#L223

[http-243]: ../app-api/src/test/kotlin/com/exchange/core/api/market/api/MarketConfigurationHttpTest.kt#L243

[http-264]: ../app-api/src/test/kotlin/com/exchange/core/api/market/api/MarketConfigurationHttpTest.kt#L264

[http-297]: ../app-api/src/test/kotlin/com/exchange/core/api/market/api/MarketConfigurationHttpTest.kt#L297

[http-319]: ../app-api/src/test/kotlin/com/exchange/core/api/market/api/MarketConfigurationHttpTest.kt#L319

[http-339]: ../app-api/src/test/kotlin/com/exchange/core/api/market/api/MarketConfigurationHttpTest.kt#L339

[http-347]: ../app-api/src/test/kotlin/com/exchange/core/api/market/api/MarketConfigurationHttpTest.kt#L347

[http-373]: ../app-api/src/test/kotlin/com/exchange/core/api/market/api/MarketConfigurationHttpTest.kt#L373

[http-412]: ../app-api/src/test/kotlin/com/exchange/core/api/market/api/MarketConfigurationHttpTest.kt#L412
