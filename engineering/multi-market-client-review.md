# DB의 지정 마켓 설정을 읽어 실제 주문에 연결하기

**노드·자금 준비·주문 프로그램은 시작할 때 PostgreSQL에서 지정한 설정 버전을 한 번 읽는다.** 읽은 목록과 예상한 내용이 같은지 확인한 뒤, 주문의 `--market SUI-USDT`가 SUI의 자산·수량 단위·수수료 정책을 함께 고르게 한다.

설정 원문을 환경 변수나 파일로 옮기는 단계를 없애고, DB에 등록한 버전을 직접 사용하기 위한 변경이다. 실제 잔고 판단과 주문 전송은 기존 자금 엔진과 Aeron 경로를 사용한다.

흐름은 다음 네 단계다.

1. **시작 준비:** DB 주소·계정·설정 버전·기대 지문을 읽는다.
2. **설정 확정:** DB에서 그 버전의 전체 마켓 목록을 조회하고 내용·형식을 검증한다.
3. **실행:** 검증한 목록을 메모리에 고정한 다음 노드나 클라이언트를 시작한다.
4. **주문:** 선택한 마켓의 정보를 보내고, 노드가 자기 설정과 대조한 뒤 자금 판단 결과를 돌려준다.

BTC와 SUI 매수는 U1의 같은 USDT 잔고를 사용한다. 수수료가 0이고 1,000 USDT를 준비했을 때의 기대 결과는 다음과 같다.

| 차례로 보낸 입력 | 결과 | 사용 가능 금액 | 예약 금액 |
| --- | --- | ---: | ---: |
| BTC 6개 × 100 USDT | 승인 순번 1 | 400 USDT | 600 USDT |
| SUI 25개 × 20 USDT | 100 USDT 부족으로 거절 | 400 USDT | 600 USDT |
| 새 요청으로 SUI 20개 × 20 USDT | 승인 순번 2 | 0 USDT | 1,000 USDT |

<details markdown="1">
<summary>DB에는 무엇을 저장하고, 어떻게 버전을 고르는가</summary>

`V9__create_market_configuration_release.sql`은 `market_configuration_release` 테이블을 만든다. **한 행이 한 버전의 전체 마켓 목록**이다.

| 열 | 내용 |
| --- | --- |
| `config_version` | 양수 버전 번호. 기본 키이므로 같은 번호를 다시 등록할 수 없다 |
| `markets_document` | 마켓 목록 전체를 담은 TEXT 형식의 JSON 원문. UTF-8 1~65,536바이트 |
| `created_at` | 등록 시각 |

문서의 각 항목에는 마켓 ID, 기준·결제 자산, 수량 자릿수, 수수료 정책이 함께 들어간다. BTC와 SUI를 한 버전으로 공개하려면 두 항목을 담은 문서 하나를 INSERT한다. 행 하나가 등록 단위이므로 일부 마켓만 먼저 공개되는 중간 상태를 만들지 않는다.

등록한 버전의 UPDATE·DELETE·TRUNCATE는 DB trigger가 거절한다. 바꿀 내용은 새 버전으로 INSERT한다. 데이터베이스 소유자가 trigger를 해제하는 등 관리 권한으로 우회하는 것까지 막는 장치는 아니다.

이번 범위에는 등록 API·화면·전용 CLI가 없다. 테이블과 데이터는 SQL/Flyway로 명시적으로 준비해야 하며, 개발 main이 시작하면서 운영 DB의 migration이나 데이터 등록을 대신하지 않는다.

아래는 V9 적용 후 DB 관리 도구에서 등록하는 예다. 한 번 등록한 7번은 고치지 않고, 변경하려면 새 번호를 쓴다. 실제 배포에서는 등록 계정과 조회 계정을 구분한다.

```sql
insert into market_configuration_release(config_version, markets_document)
values (7, '[{"marketId":"BTC-USDT","baseAssetId":"BTC","quoteAssetId":"USDT","baseQuantityScale":8,"feePolicy":{"productType":"SPOT","feeTier":"NORMAL","scheduleVersion":1,"makerPartsPerMillion":0,"takerPartsPerMillion":0}},
{"marketId":"SUI-USDT","baseAssetId":"SUI","quoteAssetId":"USDT","baseQuantityScale":9,"feePolicy":{"productType":"SPOT","feeTier":"NORMAL","scheduleVersion":2,"makerPartsPerMillion":0,"takerPartsPerMillion":0}}]');

select config_version, encode(sha256(convert_to(markets_document, 'UTF8')), 'hex') as expected_sha256
from market_configuration_release
where config_version = 7;
```

등록한 내용을 확인한 뒤 이 버전·지문을 노드와 클라이언트의 공통 배포 입력으로 고정한다. 다음 실행 때마다 DB에서 기대 지문까지 다시 골라 쓰면 변조 대조의 의미가 없어지므로, 시작 프로그램은 문서만 읽고 배포 입력의 지문과 비교한다.

`DevelopmentMarketSettings.kt`의 `readMarketsDocument`는 읽기 전용 JDBC 연결에서 다음 조회를 실행한다.

```sql
select markets_document
from market_configuration_release
where config_version = ?
```

버전은 준비된 문장의 매개변수로 전달한다. 새 버전이 더 있어도 요청한 버전만 읽으며, 없는 버전을 최신 버전이나 BTC 기본값으로 바꾸지 않는다. 연결·로그인·소켓·쿼리에 유한 제한시간을 적용하고 연결과 조회 자원을 닫는다.

</details>

<details markdown="1">
<summary>다섯 환경 변수와 내용 지문은 왜 필요한가</summary>

로컬 인자 모드와 `--cluster-env` 모드 모두 같은 다섯 입력을 사용한다.

| 환경 변수 | 역할 |
| --- | --- |
| `FUNDING_MARKET_DB_URL` | PostgreSQL JDBC 연결 주소 |
| `FUNDING_MARKET_DB_USER` | 조회 계정 |
| `FUNDING_MARKET_DB_PASSWORD` | 조회 비밀번호 |
| `FUNDING_MARKET_CONFIG_VERSION` | 읽을 버전. 양수 Long |
| `FUNDING_MARKET_CONFIG_SHA256` | 배포 전에 고정한 예상 원문의 SHA-256. 소문자 64자리 |

DB 주소는 `jdbc:postgresql://host[:port]/database` 형태의 한 서버 주소를 받는다. DNS·IPv4·대괄호 IPv6와 SSL·제한시간 쿼리 옵션을 지원한다. 사용자·비밀번호는 별도 환경으로만 받고, URL의 userinfo·user/password/service·host/port/dbname 우회 옵션은 거절한다. 잘못된 주소를 PostgreSQL 드라이버가 로그에 출력하기 전에 검사하며, 오류에 원문 주소·자격정보·파싱 예외를 붙이지 않는다.

JSON 원문을 받는 파일 경로나 환경 변수는 없다. 이전 입력인 `FUNDING_MARKETS_JSON`이 남아 있으면 시작 오류로 알린다. DB 입력은 하나라도 있으면 다섯 개 모두 필요하고, 빈 값도 오류다. 다섯 입력을 모두 생략했을 때만 기존 BTC-USDT 기본 설정을 사용한다.

지문은 **DB에서 읽은 문서 원문 전체를 UTF-8로 바꾼 바이트**에서 계산한다. 공백·줄바꿈·항목 순서도 원문의 일부다. 의미가 같은 JSON이어도 원문이 달라지면 지문이 달라질 수 있다. 읽어 온 문서에서 계산한 값을 곧바로 기대값으로 채택하지 않고, 외부에서 고정한 기대값과 비교한다.

`readDevelopmentMarkets`는 지문이 일치한 문서의 구조와 도메인 규칙도 검사한다. 1~32개 마켓, 중복 없는 ID와 JSON 키, 정확한 필드, Long 정수, 수량 자릿수·자산·정책 제약을 확인한다. 지문 일치만으로 잘못된 마켓 설정을 허용하지 않는다.

검증한 목록·버전·실제 지문은 변경할 수 없는 `DevelopmentMarketRegistry`에 담긴다. READY의 `settings.markets`, `marketConfigVersion`, `marketConfigSha256`에서 실행 설정을 확인할 수 있다. 기본 BTC 실행의 버전·지문은 `null`이며, DB 주소·계정·비밀번호는 READY에 넣지 않는다.

</details>

<details markdown="1">
<summary>실패는 어디서 멈추고, DB가 나중에 중단되면 어떻게 되는가</summary>

세 main은 동일한 시작 환경에서 설정을 만든다. `DevelopmentFundingNode.kt`, `DevelopmentFundingClient.kt`, `DevelopmentOrderClient.kt`의 로컬 경로와 `DevelopmentFundingEvents.kt`의 `developmentClusterSettings`가 DB 조회를 연결한다.

DB 설정을 확정한 뒤에 Driver·Archive·서비스나 클라이언트를 시작한다. 그래서 아래 실패는 READY와 업무 전송 전에 발견된다.

| 상황 | 시작 결과 |
| --- | --- |
| DB 입력 일부 누락·빈 값, 잘못된 버전·지문 형식 | 입력 오류 |
| DB 연결·조회 오류 또는 제한시간 초과 | 조회 실패. BTC 기본값으로 대체하지 않음 |
| 지정 버전 없음, 기대 지문과 불일치 | 설정 실패 |
| 빈 목록·손상 JSON·잘못된 자산이나 정책 | 내용 검증 실패 |
| 미등록 마켓 선택·잘못된 옵션 | 주문 입력 오류 |

잘못된 DB 주소는 드라이버에 전달하기 전에 거절해 원문이 경고 로그로 새는 것을 막는다. JDBC 실패도 연결 주소·계정·비밀번호가 포함될 수 있는 원래 예외를 그대로 출력하지 않는다. 어떤 설정 버전 조회가 실패했는지 알리는 오류로 종료한다.

**이미 초기화한 노드와 연결된 클라이언트는 DB가 중단되어도 메모리의 설정을 계속 사용한다.** 주문 callback은 DB를 조회하지 않는다. 반면 새 노드나 새 자금 준비·주문 CLI를 시작하려면 DB 모드에서 다시 조회해야 하므로 DB가 필요하다. 한 번 실행해 본 CLI의 다음 실행도 새 시작이다.

Compose는 세 노드와 클라이언트에 같은 다섯 입력을 전달한다. 격리된 Compose 네트워크에서 DB를 쓰려면 그 네트워크에서 접근할 수 있는 주소를 제공해야 한다. 같은 버전·기대 지문을 모든 프로세스에 배포하는 것은 실행 전제이며, READY 출력이 노드 간 설정 합의를 대신하지 않는다.

</details>

<details markdown="1">
<summary>마켓 선택 뒤의 주문·잔고 계약은 무엇인가</summary>

`parseDevelopmentOrderInvocation`은 Registry에서 선택한 `context` 하나를 찾는다. 그 항목의 마켓 ID, 자산, 수량 단위와 정책을 한 요청에 담으므로 SUI를 고르면서 BTC 정책을 섞지 않는다. BUY·SELL 모두 같은 선택 경로를 사용한다.

선택을 생략하면 `BTC-USDT`를 찾는다. 목록의 첫 항목을 고르는 방식이 아니므로 SUI만 등록했다면 `--market SUI-USDT`를 명시해야 한다. 옵션은 맨 끝에 한 번만 허용한다. 별도 정책 버전 인자는 전송용 정책 복사본만 바꾸며 Registry의 원본은 유지한다.

`DevelopmentFundingAdapter.admit`은 새 요청의 정의·정책을 노드 Registry와 비교한다. 정책 버전이 틀린 새 요청은 `EXECUTION_CONTEXT_MISMATCH`로 거절하며 돈이나 최초 요청 기록을 바꾸지 않는다. 같은 요청을 올바른 정책으로 고치면 다시 판단할 수 있다.

정상 설정으로 보낸 주문의 잔고가 부족하면 `INSUFFICIENT_BALANCE`와 최초 거절을 기록한다. 이미 기록된 요청은 기존 최초 결과·내용 충돌 규칙을 따른다. 전송 수용만으로 접수 결과가 확정되지는 않으며, 응답을 확인하지 못하면 기존 `UNCONFIRMED`로 남는다. 접수 승인은 체결·정산 완료를 뜻하지 않는다.

</details>

<details markdown="1">
<summary>테스트가 확인하려는 내용과 후속 범위</summary>

아래는 테스트의 의도다. 실제 PostgreSQL을 포함한 전체 app-api 482개가 통과했고, 이후 JDBC 주소 로그 노출을 보강한 최종 코드에서 관련 35개를 다시 확인했다. 최종 구조 검사 375개·필수 P01~P09 실행 확인·공통 `ktlintCheck`도 통과했다. 테스트 실패·오류·skip은 없었다. 이 수치는 이전 JSON 환경 입력 구현의 결과를 복사한 것이 아니다.

| 테스트 | 준비·실행 → 확인할 결과 |
| --- | --- |
| `DevelopmentMarketSettingsTest` | 엄격 문서 파싱과 환경 입력 → 정상 목록·기본 BTC 또는 해당 입력 오류 |
| `PostgresDevelopmentMarketSettingsTest` | 실제 PostgreSQL에 버전별 문서 등록·조회 → 지정 버전, 원문 지문, 제약·변경 금지, 손상 내용·연결·잠금 실패 |
| `DevelopmentMarketSelectionTest` | DB에서 BTC·SUI 등록 후 두 모드의 BUY·SELL·옵션 해석 → 선택한 정의·정책·수량, 기본 선택과 원본 보존 |
| `DevelopmentMarketMainTest` | 실제 세 main과 한 노드, 두 실행 모드 → READY 버전·지문, 1,000→600 예약→500 부족→400 승인, 환경 모드의 단계별 전체 상태 |
| 같은 Main 테스트의 실패 사례 | 없는 버전·다른 지문·누락·연결 거절·빈 목록·이전 JSON 입력 → 3개 main × 2모드의 시작 실패, 자원 미생성·전송/결과 출력 없음. 잘못된 JDBC 주소도 두 출력에서 원문·자격정보 비노출 |
| `DevelopmentMultiMarketOrderNodeTest` | 실제 DB에서 초기화한 뒤 DB 종료 → 이미 연결된 노드·클라이언트의 SUI 승인과 잔고·정책 유지 |

CI에서는 `DevelopmentOrderClientTest`의 노드 생존 확인 한 건이 실패했다. 생존 신호 갱신 주기와 검사 기준을 모두 1초로 두어, 정상 Driver도 갱신 직전에 종료된 것으로 판단할 수 있었다. 해당 검사는 **개발 클라이언트가 종료된 뒤 노드 Driver로 새 클라이언트를 연결하고 같은 주문의 최초 결과를 받는지** 확인하도록 바꿨다. 승인·부족 거절의 원래 결과와 잔고·예약·요청 수는 그대로 검증하며, 클라이언트 전용 Driver의 종료 검사도 유지한다. 수정 후 관련 클라이언트 테스트 13개와 공통 린트가 통과했다.

실제 구현 폴더는 `/Users/0chord/.codex/worktrees/issue115-market-selection/exchange-core`, 브랜치는 `feat/development-market-selection/115`다. 아래는 Docker와 JDK 25가 준비된 환경에서 실제 main 검사를 실행하는 명령이다.

```bash
cd /Users/0chord/.codex/worktrees/issue115-market-selection/exchange-core
JAVA_HOME=/Users/0chord/Library/Java/JavaVirtualMachines/corretto-25.0.2/Contents/Home ./gradlew :app-api:test --tests '*DevelopmentMarketMainTest'
```

#116은 새 다중 마켓의 세 노드 결과·상태 대조, #117은 동시 요청 검증을 맡는다. #118은 이번에 구현한 직접 DB 조회·버전 저장과 중복되는 부분을 빼고 관리·배포 후속 범위를 다시 정리해야 한다.

기존 로그 복구 때 원래 설정 버전을 자동 복원하는 기능, 실행 중 설정 변경, DB 고가용성, 등록 관리 기능은 이번 완료 범위에 포함하지 않는다. 전체 회귀 후 주소 검증과 클라이언트 생존 확인을 보강했고, 해당 시작 경로·DB·문서·마켓 선택·클라이언트 검사를 재실행했다. 작성 대화를 받지 않은 별도 리뷰어가 독립 checkout에서 전체 변경과 보강분을 검토했고, 실제 main의 로그 노출 반례는 해결됐으며 추가 결함은 발견하지 못했다. AI 검토를 사람의 승인이나 운영 고가용성 검증으로 간주하지 않는다.

</details>
