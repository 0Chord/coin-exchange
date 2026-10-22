# 마켓 선택이 실제 주문 정보로 바뀌는 과정

**`FUNDING_MARKETS_JSON`으로 BTC·SUI를 등록한 환경에서, 주문 끝에 `--market SUI-USDT`를 붙이면 SUI의 자산·수량 단위·수수료 정책을 담아 실제 노드에 보내도록 연결한다.** 코드를 고쳐 설정을 주입하던 단계를 실행 명령으로 옮긴다. 마켓마다 별도 잔고를 만드는 기능은 아니다.

U1에게 1,000 USDT를 준비한 다음 두 마켓에서 주문하면 다음과 같다. 여기서 **사용 가능 금액**은 새 주문에 쓸 돈이고, **예약 금액**은 이미 접수한 주문에 묶인 돈이다.

| 차례로 보낸 입력 | 접수 결과 | 사용 가능 금액 | 예약 금액 |
| --- | --- | ---: | ---: |
| BTC 6개 × 100 USDT | 승인 순번 1 | 400 USDT | 600 USDT |
| SUI 25개 × 20 USDT | 100 USDT 부족으로 거절 | 400 USDT | 600 USDT |
| 새 요청으로 SUI 20개 × 20 USDT | 승인 순번 2 | 0 USDT | 1,000 USDT |

전체 흐름은 **설정 읽기 → 마켓 선택 → 주문 원본 만들기 → 전송 → 노드의 설정 확인과 자금 판단 → 응답 확인**이다.

- 세 실행 프로그램은 시작할 때 같은 `FUNDING_MARKETS_JSON`을 읽는다. 잘못된 설정은 폴더·통신 자원을 만들기 전에 실패한다.
- 주문 프로그램은 선택한 항목에서 마켓 ID, 자산, 수량 단위, 정책을 함께 가져온다. 선택을 생략하면 등록된 BTC를 찾는다.
- 노드는 새 요청의 정의·정책을 자기 설정과 비교하고 기존 자금 상태에 넘긴다. BTC와 SUI 매수는 같은 `(U1, USDT)` 잔고를 사용한다.
- 전송 수용은 응답 완료가 아니다. 실제 승인·거절 응답을 확인하며, 응답이 없으면 기존의 ‘결과 미확인’ 처리로 남는다.

**첫 번째 상세에서 `--market SUI-USDT`가 주문 정보로 바뀌는 코드를 볼 수 있다.** 나머지는 필요한 부분만 펼쳐 읽으면 된다.

<details markdown="1">
<summary>선택한 SUI 한 항목이 주문 원본 전체를 결정한다</summary>

`DevelopmentOrderClient.kt`의 `parseDevelopmentOrderInvocation`, 274행에서 명령을 해석한다. 가격 `20000000`, 수량 `25000000000`, 끝 옵션 `--market SUI-USDT`를 받으면 다음처럼 옮긴다.

| 입력·설정 | 만들어지는 주문 정보 | 이 값을 함께 고르는 이유 |
| --- | --- | --- |
| `--market SUI-USDT` | 주문의 마켓 ID는 `SUI-USDT` | 요청할 시장을 식별한다 |
| SUI 등록의 base/quote | 기준 자산 `SUI`, 결제 자산 `USDT` | 매수 시 어떤 자산 잔고를 예약할지 결정한다 |
| SUI 등록의 `baseQuantityScale=9` | 수량 최소 단위는 SUI 한 개의 10억분의 1 | 입력 수량 `25000000000`이 SUI 25개가 된다 |
| SUI 등록의 정책 버전 2·수수료 0 | 전송 정책도 버전 2·수수료 0 | BTC의 정책 버전 1을 섞어 보내지 않는다 |
| 입력 가격 `20000000` | 가격 최소 단위 값 그대로 보존 | 이 예시의 20 USDT다. 사람이 적은 숫자를 자동 환산하지 않는다 |

핵심 원문은 같은 `context`를 계속 사용하는 부분이다. `context`는 등록 목록에서 선택한 마켓 한 항목을 뜻한다.

```kotlin
val context = requireNotNull(config.markets[MarketId(selectedMarket)]) {
    "등록되지 않은 마켓입니다: $selectedMarket. --market으로 등록된 마켓을 선택하세요"
}
```

위 코드는 `DevelopmentOrderClient.kt`, 298행이다. 같은 함수의 312행은 주문에 `context.market.marketId`를 넣고, 321행은 함께 보낼 마켓 정의에 `context.market`을 넣는다. 300행의 정책 선택도 `context.feePolicySnapshot`에서 시작한다. 따라서 선택한 마켓 ID만 SUI로 바꾸고 정의나 정책은 BTC로 남기는 경로가 없다.

선택 수수료 버전이 있으면 304행의 `context.feePolicySnapshot.copy(scheduleVersion = version)`이 **전송용 복사본**만 바꾼다. `... 업무Instant 1 --market SUI-USDT`는 SUI 원본을 보내면서 정책 버전만 1로 바꾸는 개발 검증 명령이다. Registry에 저장한 SUI 버전 2를 바꾸지 않는다.

옵션은 맨 끝에 한 번만 허용한다. 값을 빠뜨리거나 옵션 뒤에 정책 버전을 붙이거나 알 수 없는 옵션을 넣으면 사용법 오류다. 마켓 선택을 생략한 기본값은 284행의 `BTC-USDT`이며, 목록 첫 항목이 아니다. SUI만 등록했다면 SUI를 명시해야 한다.

`runDevelopmentOrderClient`, 209행은 이 해석을 끝낸 뒤 Driver를 만들거나 연결한다. 그 다음 기존 `DevelopmentOrderClient.request`, 157행 → `send`, 93행 → `awaitResult`, 136행으로 이어진다. BUY와 SELL은 같은 선택 경로를 쓰고 LIMIT·GTC 조건은 유지한다.

</details>

<details markdown="1">
<summary>세 실행 프로그램이 같은 설정을 읽고 전체 목록을 보여준다</summary>

세 프로그램은 모두 `src/main`의 **개발 실행 진입점**이다. 이번 연결은 기본 HTTP 주문 API를 바꾸지 않는다.

| 실제 파일·함수·위치 | 시작할 때 하는 일 | 자원을 사용하는 다음 단계 |
| --- | --- | --- |
| `DevelopmentFundingNode.kt` — `main`, 310행 | 환경을 한 번 읽고 로컬 인자 또는 환경 기반 Settings 생성 | `DevelopmentFundingNode.start`로 Driver·Archive·합의·서비스 시작, 전체 READY 출력 |
| `DevelopmentFundingClient.kt` — `main`, 202행 | 같은 마켓 목록을 검증하고 기존 준비 ID·사용자·자산·금액 입력 구성 | 실제 자금 준비 요청 전송. 이 프로그램에 마켓 선택 옵션은 추가하지 않음 |
| `DevelopmentOrderClient.kt` — `main`, 205행 / `runDevelopmentOrderClient`, 209행 | 목록 검증, 마켓 선택, 주문 원본 구성 | 기존 주문 연결·전송·응답 대기 |
| `DevelopmentFundingEvents.kt` — `developmentClusterSettings`, 20행 | `--cluster-env`의 공통 설정을 만들며 `readDevelopmentMarkets(environment)` 호출 | 호출한 프로그램이 자기 실행 책임에 따라 자원 생성 |

로컬 클라이언트는 노드의 기존 Driver에 연결한다. 환경 기반 클라이언트는 자기 Driver를 만든다. Settings 생성자의 기본 인자에서는 환경을 읽지 않으므로, Settings를 직접 만드는 기존 테스트가 주변 환경에 종속되지 않는다.

새 `DevelopmentMarketSettings.kt`의 `readDevelopmentMarkets`, 30행은 입력을 검사해 Registry를 만든다.

| 입력 | 실제 판단 |
| --- | --- |
| 환경 변수가 없음 | 기존 BTC-USDT 한 항목 반환 |
| 빈 문자열·공백·`null`·빈 배열 | 기본값으로 바꾸지 않고 오류 |
| 중복 JSON 키·마켓 ID, 누락·추가 필드, 뒤따르는 다른 JSON | 오류 |
| 숫자 문자열·소수·지수·Long 초과 | 정수로 바꾸거나 반올림하지 않고 오류 |
| 수량 자릿수·정책 버전·요율·자산·상품·등급 | 명시한 범위와 기존 값 객체의 규칙으로 검사 |
| UTF-8 65,536바이트 초과 또는 마켓 32개 초과 | 개발 설정 상한 오류 |

`integer`, 100행의 `isIntegralNumber && canConvertToLong()`은 정수 토큰과 Long 범위를 함께 요구한다. 이 검사 덕분에 `8.0`을 8로 조용히 받아들이지 않는다. 오류에는 `FUNDING_MARKETS_JSON`과 가능한 항목·필드 위치가 남는다.

`DevelopmentMarketRegistry.kt`의 `all`, 55행은 마켓 ID 문자열 순서로 정렬한 변경 불가능 목록을 돌려준다. `DevelopmentFundingEvents.kt`의 `fundingMarketFields`, 68행은 각 마켓 정의와 정책을 한 항목으로 펼친다. `fundingReadyFields`, 44행은 이를 `settings.markets` 배열에 넣는다. 로컬 Node main도 같은 함수의 전체 목록을 표시한다. 기존의 분리된 `settings.market`·`settings.feePolicy` 필드는 제거된다.

개발 Docker 설정인 `compose.yml`의 `x-common-environment`, 8행은 node0·node1·node2·client·client-a·client-b가 같은 환경 변수를 전달받게 한다. 값 없는 `FUNDING_MARKETS_JSON:` 항목은 미설정과 명시한 빈 문자열을 구분하기 위한 것이다. 여러 노드 사이에서 설정을 합의하거나 자동 동기화하는 기능은 아니다.

</details>

<details markdown="1">
<summary>전송 뒤에는 기존 자금 판단을 그대로 사용한다</summary>

`DevelopmentFundingAdapter.kt`의 `onSessionMessage`, 82행이 기존 코덱으로 주문을 복원하고 `admit`, 169행으로 넘긴다. 이 파일과 아래 자금 상태 코드는 이번에 변경하지 않았다.

`admit`은 기존 사용자·요청 ID인지 먼저 본다. 새 요청이면 173행에서 노드의 Registry를 조회하고, 174행에서 전송된 마켓 정의와 정책까지 비교한다. 맞으면 186행에서 기존 `state.admit(...)`을 호출한다.

`OrderAdmissionState.kt`의 `prepare`, 227행은 매수의 결제 자산을 고른 뒤 사용자·자산으로 잔고를 찾는다. BTC-USDT와 SUI-USDT의 매수는 결제 자산이 둘 다 USDT여서 U1의 같은 잔고로 판단된다. `applyAccepted`, 252행은 예약·대기 주문·전표·최초 결과를 반영한다. `rememberRejected`, 273행은 부족 거절의 최초 결과를 남긴다.

| 상황 | 응답 또는 종료 | 남는 돈과 기록 |
| --- | --- | --- |
| 잘못된 JSON·옵션·미등록 선택 | 연결 전 입력 오류 | 이번 프로그램의 업무 전송 없음 |
| 새 요청의 정의·정책이 노드 설정과 다름 | `EXECUTION_CONTEXT_MISMATCH` | 돈과 최초 요청 기록 유지. 같은 요청을 바르게 고쳐 판단 가능 |
| 정상 새 요청의 돈이 부족함 | `INSUFFICIENT_BALANCE` | 돈과 예약은 유지, 최초 거절 기록 추가 |
| 정상 새 요청의 돈이 충분함 | 승인과 전역 순번 | 사용 가능 금액 감소, 주문별 예약·대기·전표·최초 승인 추가 |
| 기존 요청 재전송·원본 변경 | 최초 결과 또는 내용 충돌 | 기존 계약 유지. 새 전달 정책으로 과거 결과를 덮지 않음 |
| 전송·응답 대기 만료 | `UNCONFIRMED`, 결과 미확인 | 미접수·자동 취소·예약 반환으로 판단하지 않음 |
| 예상 밖 코어 오류 | 기존 개발 노드 중단 | 자동 롤백·재시작을 추가하지 않음 |

이미 있던 것은 여러 마켓의 자금 공유, 신규 문맥 검사, 재전송과 충돌, 원장 생성, 결과 미확인 처리다. 이번에 추가한 것은 **외부 JSON을 그 등록 목록에 넣는 입구**, **CLI에서 한 항목을 고르는 입구**, **전체 시작 설정을 출력·검사하는 연결**이다. 접수 승인은 체결·정산 완료를 뜻하지 않는다.

</details>

<details markdown="1">
<summary>실제 실행 테스트가 각 단계에서 무엇을 확인하는가</summary>

새 `DevelopmentMarketMainTest.kt`는 별도 JVM의 **실제 세 main**, 실제 UDP 노드, 실제 도메인을 사용한다. 자금 판단이나 네트워크를 mock으로 바꾸지 않는다. 아래의 assertion은 기대와 다르면 테스트를 실패시키는 실제 검증문이다. 실행 결과의 최종 판정은 뒤의 검증 현황에 별도로 기록한다.

| 새 테스트·위치 | Given — 준비 | When — 실행 | Then — 실제 assertion | 역할·보장 범위 |
| --- | --- | --- | --- | --- |
| 환경 기반 세 main의 단계별 상태, 19행 | SUI·BTC 역순 JSON, 한 노드 loopback | P1 준비 → BTC 600 → SUI 500 → 새 SUI 요청의 정책 버전 1 → 같은 요청 버전 2 | `verify`가 응답 전체와 `expectedState(stage)` 전체 JSON을 `assertEquals`; 새 적용 위치·요청 ID·`OFFERED`를 기다림 | 외부 설정·CLI·실제 전송·기존 자금 판단 연결. 각 단계의 잔고·예약·최초 결과·전표까지 확인 |
| 로컬 세 main의 순차 응답, 64행 | 같은 두 마켓 JSON, 실제 로컬 노드 | P1 → BTC 600 → SUI 500 → SUI 400 | 전체 `markets` 문자열 포함; `assertLocalAccepted`로 마켓·요청·주문·순번; 부족 이유·400/500/100 값; 전송 수용보다 결과 출력이 뒤 | 로컬 진입점과 마켓 선택·응답 연결. 로컬 객체 텍스트를 상태 전체로 복원하지 않음 |
| 설정 없는 기존 BTC 실행, 85행 | 부모의 `FUNDING_MARKETS_JSON` 제거 | 로컬·환경 두 모드 각각 노드·준비·BTC 주문 실행 | READY에 BTC 한 항목, 준비·승인 응답; 환경 모드는 승인 뒤 전체 상태 비교 | 세 main 모두 미설정 기본값 회귀. 명시한 빈 문자열과 다름 |
| 잘못된 설정의 시작 실패, 107행 | `[]`, 새 실행 경로 | 세 main × 두 모드의 6개 시작 | `assertInputFailure`: 제한 시간 내 종료 코드 1, 오류에 환경명, READY·전송·결과·미확인 출력 없음; 자원 루트가 존재하지 않음 | 실제 시작 경로에서 대표 설정 오류가 자원 사용보다 먼저 발견됨. 모든 JSON 변형은 파서 테스트에서 검사 |
| 잘못된 선택의 시작 실패, 120행 | 두 마켓에 없는 DOGE 선택 또는 SUI만 있는데 선택 생략 | 두 경우 × 두 모드의 4개 주문 시작 | 같은 입력 오류 검증, 진단에 DOGE 또는 BTC, 자원 루트 없음 | 선택 오류를 실제 연결 전 확인 |

가장 먼저 읽을 실제 검증문은 환경 기반 테스트의 다음 두 줄이다.

```kotlin
assertEquals(json(expectedResult), observed.path("result"))
assertEquals(json(expectedState(stage)), observed.path("state"))
```

`DevelopmentMarketMainTest.kt`의 `verify`, 25행부터다. 첫 줄은 **그 요청에 대한 답**, 둘째 줄은 **그 요청 뒤에 남은 전체 상태**를 확인한다. `expectedState`, 292행은 명세에 정한 금액으로 기대값을 만들며, 서버 응답에서 정답을 가져오지 않는다.

| 구조화 테스트의 단계 | 그 단계에서 확인하는 상태 |
| --- | --- |
| P1 준비 | 사용 가능 1,000·예약 0, 준비·OPENING 각 한 건, 요청·대기 없음 |
| BTC 승인 | 사용 가능 400·예약 600, 순번 1, BTC 정의·정책 버전 1, BTC 예약·RESERVE |
| SUI 부족 | 사용 가능 400·예약 600 유지, 현재 400·필요 500·부족 100, 최초 요청 두 건, SUI 예약·전표 없음 |
| 새 SUI 요청의 정책 버전 1 | 문맥 불일치, 직전 전체 상태와 같음. 거절 요청 기록도 추가하지 않음 |
| 같은 SUI 요청의 정책 버전 2 | 사용 가능 0·예약 1,000, 순번 2, BTC→SUI 대기 순서, SUI 정의·자릿수 9·정책 버전 2, 400의 새 RESERVE |

`MainProcesses.applied`, 208행은 준비/요청 ID가 맞고 적용 위치가 직전보다 증가한 관찰을 기다린다. 일반 Node main은 최신 관찰 하나를 주기적으로 출력하므로, 응답만 받고 다음 요청을 즉시 보내면 중간 관찰을 놓칠 수 있다. 테스트는 해당 관찰을 확인한 뒤 다음 요청을 보낸다. 제품에 관찰 큐나 테스트용 gate를 추가하지 않았다.

공통 실행은 기존 `VerificationCommands`를 재사용한다. `RunningVerificationCommand.start`, 46행은 상속 환경에서 마켓 변수를 지운 뒤 이 테스트의 명시 설정을 적용한다. `MainProcesses.close`, 286행은 시작한 프로세스가 제한 시간 안에 종료되는지 확인한다. 테스트는 한 노드의 순차 실행을 검증하며, 여러 마켓의 세 노드 대조와 동시 요청은 후속 범위다.

</details>

<details markdown="1">
<summary>설정·선택·READY 테스트의 준비와 실제 검증문</summary>

여기서는 네트워크를 시작하지 않는 입력 검사와 출력 비교를 묶어 읽는다. 반복문 안의 여러 입력은 한 테스트 정의가 실행하는 사례들이며, 표의 조합 수를 JUnit 테스트 개수와 혼동하지 않는다.

| 새 테스트·파일·위치 | Given — 준비 | When — 실행 | Then — 실제 assertion | 역할·보장 범위 |
| --- | --- | --- | --- | --- |
| 미설정·원래 값 보존 — `DevelopmentMarketSettingsTest.kt`, 12행 | 빈 환경, SUI→BTC 순서의 JSON | `readDevelopmentMarkets` | 기본 BTC 동등성, 정렬된 ID 목록, SUI 자산·자릿수·정책·요율을 각각 `assertEquals` | 외부 설정을 Registry로 정확히 옮김 |
| 빈 값·루트·중복 — 같은 파일 29행 | 빈 문자열, 공백, null, 빈/잘못된 배열·객체, 뒤 JSON, 중복 ID·키 | 각 원시 JSON 읽기 | `assertInvalid`: `IllegalArgumentException`, 메시지에 환경명 | 입력 오류를 기본 BTC로 숨기지 않음 |
| 필수 필드 — 같은 파일 49행 | 마켓·정책의 각 필드 누락/null, 추가 필드, 정책 객체 누락/null | 변형마다 읽기 | 변형이 원본과 다름을 확인하고 `assertInvalid` | 객체 필드 집합 검사 |
| 숫자 토큰 — 같은 파일 76행 | 네 숫자 필드 × 문자열·소수·지수·boolean·Long 상하한 초과 | 24개 변형 읽기 | 매번 `assertInvalid` | 숫자 강제 변환 거절 |
| 기존 값 제약 — 같은 파일 85행 | 잘못된 스케일·버전·요율·식별자·상품·등급; 허용 경계 | 각 변형 읽기 | 오류 입력 거절; 스케일 0/18·Long 최대 버전·모든 기존 등급·백만 요율을 실제 반환값과 비교 | 기존 값 객체 검사를 외부 설정에서도 적용 |
| 입력 상한 — 같은 파일 122행 | 65,536/65,537바이트, 다국어 UTF-8 초과, 32/33개 마켓 | 목록 읽기 | 경계의 BTC/32개 결과 비교, 초과 `assertInvalid` | 글자 수만으로 바이트 상한을 판단하지 않음 |
| 선택한 SUI 원본 — `DevelopmentMarketSelectionTest.kt`, 12행 | 두 실행 모드 × BUY/SELL | `parseDevelopmentOrderInvocation` | 마켓 ID·정의·자산·스케일 9·버전 2·가격·수량·방향을 각각 비교 | 전송 전 주문 구성. 실제 네트워크 전송은 main 테스트 담당 |
| 잘못된 JSON 전달 — 같은 파일 31행 | 두 모드에 `[]` | 주문 인자 해석 | 예외와 환경명 확인 | 두 인자 경로가 공통 파서를 사용 |
| 생략·SUI 단독 — 같은 파일 42행 | 다중 등록, 미설정, SUI 단독 × 두 모드 | 생략 또는 SUI 명시 | 생략은 BTC, SUI 단독 생략은 예외, 명시는 SUI | 첫 마켓을 임의 선택하지 않음 |
| 정책 버전 복사 — 같은 파일 68행 | SUI 등록 버전 2, 입력 버전 3 × 두 모드 | 선택한 주문 구성 | 전송 정책 3, Registry 정책 2, SUI 스케일 9 | 선택한 항목의 전송용 정책만 변경 |
| 잘못된 옵션 — 같은 파일 83행 | 미등록·값 누락·빈 값·옵션형 값·오위치·중복·알 수 없는 옵션·앞쪽 옵션 × 두 모드 | 16개 명령 해석 | 모두 `assertFailsWith<IllegalArgumentException>` | 인자 검사이며 이 테스트만으로 자원 미생성을 관측하지는 않음 |
| 기본 READY — `DevelopmentFundingEventsTest.kt`, 36행 | 기본 BTC 설정 | `fundingMarketFields` | BTC 정의와 정책 한 항목의 전체 JSON 동등성 | 출력 구조 검사 |
| 전체 정렬·불변 — 같은 파일 41행 | SUI→BTC 변경 가능 원본 목록 | Registry 생성 뒤 원본 제거·반환 목록 수정 시도 | BTC→SUI 목록 동등성, 수정 시 예외, 전체 필드 동등성, 입력 순서를 바꿔도 같은 출력 | 출력 순서와 목록 고정 |
| 정상 READY 비교 — `FundingReplicationComparisonTest.kt`, 12행 | 독립 BTC 기대 또는 독립 BTC·SUI 기대 | 세 READY 예제를 비교기에 전달 | 문제 목록 `isEmpty()` | 테스트 검증 도구의 정상 예제. 실제 세 노드를 띄우는 테스트는 아님 |
| 목록 누락·추가·중복·순서 — 같은 파일 19행 | 빈 목록·한 항목 누락·추가·중복·역순, 필드 자체 누락, 기본 BTC에 SUI 추가 | 변형된 READY 비교 | 목록 불일치 문제 3건 또는 해당 문제 존재 | 세 출력이 같기만 해서 통과하는 오류를 방지 |
| SUI 필드 변조 — 같은 파일 44행 | SUI의 정의·정책 아홉 필드를 각각 세 READY 모두 동일하게 변경 | 독립 기대 배열과 비교 | 각 변형의 목록 불일치 문제 3건 | 마켓 ID만 또는 첫 BTC만 검사하지 않음 |
| 옛 READY 거절 — 같은 파일 75행 | `markets`를 지우고 옛 `market`·`feePolicy`만 둠 | READY 비교 | 배열 문제와 옛 단일 필드 문제 존재 | 누락을 옛 기본값으로 보완하지 않음 |

아래는 기존 테스트에서 바뀐 부분이다. 기존 자금 동작을 새로 구현한 것으로 세지 않는다.

| 변경된 테스트·위치 | Given — 준비 | When — 실행 | Then — 실제 assertion | 바뀐 부분·범위 |
| --- | --- | --- | --- | --- |
| `DevelopmentMultiMarketOrderNodeTest.kt`의 BTC·SUI 자금 공유, 46행 | Settings로 직접 만든 두 마켓 실제 노드 | 시작 READY를 만들고 기존 준비·주문·재전송 시나리오 실행 | 새로 전체 BTC·SUI 배열 동등성, 옛 `market`·`feePolicy` 부재 확인. 기존 잔고·예약·최초 부족 유지 검증은 유지 | ‘다중 READY 실패’ 기대를 ‘전체 출력 성공’으로 교체. 외부 JSON/main 연결 증거는 별도 새 테스트 |
| `DevelopmentFundingNodeTest.kt`의 노드 종료 뒤 결과 미확인, 307행 | 기존 실제 노드·별도 자금 클라이언트. 자식 환경의 마켓 변수 제거 | 기존 내부 실패를 일으키는 준비 요청 | 15초 내 종료 코드 1, 전송 수용·결과 미확인·같은 준비 ID 안내, 결과 수신 없음, 노드 종료와 오류 | 부모 설정에 흔들리지 않는 기존 회귀 유지. 금액 미반영을 일반화하지 않음 |

`FundingReplicationComparison.kt`의 `compareFundingReadiness`, 107행도 함께 바뀌었다. 각 노드의 전체 배열을 공통 설정과 독립 기대 배열 양쪽에 대조한다. `VerificationCommands.kt`의 환경 제거는 실제 main 검사와 기존 Docker 검사에 공통으로 사용하는 테스트 지원 변경이다. 변경하지 않은 `DevelopmentFundingSettingsTest`, `DevelopmentOrderClientTest`, `DevelopmentFundingClientTest` 등은 기존 등록·연결·결과 미확인 동작의 회귀 대상으로 남는다.

</details>

<details markdown="1">
<summary>실제 구현 폴더에서 재현하는 방법</summary>

구현 폴더는 `/Users/0chord/.codex/worktrees/issue115-market-selection/exchange-core`, 브랜치는 `feat/development-market-selection/115`다. 아래 명령은 이 구현 폴더에서 실행한다. 다른 환경에서는 `cd` 경로와 `JAVA_HOME`을 자신의 checkout과 JDK 25 경로로 바꾼다.

실제 main 연결 검사만 재현하는 명령이다. JDK 25를 사용한다.

```bash
cd /Users/0chord/.codex/worktrees/issue115-market-selection/exchange-core
JAVA_HOME=/Users/0chord/Library/Java/JavaVirtualMachines/corretto-25.0.2/Contents/Home ./gradlew :app-api:test --tests '*DevelopmentMarketMainTest'
```

직접 실행하려면 먼저 실행 파일을 한 번 준비한다. 이 작업이 끝나면 노드와 클라이언트는 Gradle 대신 각각 Java로 실행한다.

```bash
cd /Users/0chord/.codex/worktrees/issue115-market-selection/exchange-core
JAVA_HOME=/Users/0chord/Library/Java/JavaVirtualMachines/corretto-25.0.2/Contents/Home ./gradlew :app-api:stageDevelopmentFundingRuntime
```

두 터미널 모두에서 같은 설정을 넣는다.

```bash
cd /Users/0chord/.codex/worktrees/issue115-market-selection/exchange-core
export JAVA_HOME=/Users/0chord/Library/Java/JavaVirtualMachines/corretto-25.0.2/Contents/Home
export FUNDING_MARKETS_JSON='[{"marketId":"BTC-USDT","baseAssetId":"BTC","quoteAssetId":"USDT","baseQuantityScale":8,"feePolicy":{"productType":"SPOT","feeTier":"NORMAL","scheduleVersion":1,"makerPartsPerMillion":0,"takerPartsPerMillion":0}},{"marketId":"SUI-USDT","baseAssetId":"SUI","quoteAssetId":"USDT","baseQuantityScale":9,"feePolicy":{"productType":"SPOT","feeTier":"NORMAL","scheduleVersion":2,"makerPartsPerMillion":0,"takerPartsPerMillion":0}}]'
```

첫 터미널에서 노드를 실행하고 READY를 기다린다. 아래 경로가 이미 존재하면 두 터미널 모두 같은 새 경로로 바꾼다. 기존 기록을 삭제하지 않는다.

```bash
cd /Users/0chord/.codex/worktrees/issue115-market-selection/exchange-core
"$JAVA_HOME/bin/java" --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/java.util.zip=ALL-UNNAMED \
  -cp 'app-api/build/aeron-development/classes:app-api/build/aeron-development/lib/*' \
  com.exchange.core.api.trading.infrastructure.aeron.DevelopmentFundingNodeKt /tmp/exchange-market115-example 19700
```

두 번째 터미널에서 다음 요청을 하나씩 실행하고 응답을 확인한다. 실제로 돈을 바꾸는 것은 노드이며, 아래 프로그램은 요청을 보내고 결과를 출력한다.

```bash
cd /Users/0chord/.codex/worktrees/issue115-market-selection/exchange-core
"$JAVA_HOME/bin/java" --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/java.util.zip=ALL-UNNAMED \
  -cp 'app-api/build/aeron-development/classes:app-api/build/aeron-development/lib/*' \
  com.exchange.core.api.trading.infrastructure.aeron.DevelopmentFundingClientKt \
  /tmp/exchange-market115-example 19700 P1 U1 USDT 1000000000 OPENING-1 2026-10-22T00:00:00Z

"$JAVA_HOME/bin/java" --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/java.util.zip=ALL-UNNAMED \
  -cp 'app-api/build/aeron-development/classes:app-api/build/aeron-development/lib/*' \
  com.exchange.core.api.trading.infrastructure.aeron.DevelopmentOrderClientKt \
  /tmp/exchange-market115-example 19700 R-BTC U1 O-BTC BUY 100000000 600000000 ledger-R-BTC 2026-10-22T00:01:00Z

"$JAVA_HOME/bin/java" --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/java.util.zip=ALL-UNNAMED \
  -cp 'app-api/build/aeron-development/classes:app-api/build/aeron-development/lib/*' \
  com.exchange.core.api.trading.infrastructure.aeron.DevelopmentOrderClientKt \
  /tmp/exchange-market115-example 19700 R-SUI U1 O-SUI BUY 20000000 25000000000 ledger-R-SUI 2026-10-22T00:01:00Z --market SUI-USDT

"$JAVA_HOME/bin/java" --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/java.util.zip=ALL-UNNAMED \
  -cp 'app-api/build/aeron-development/classes:app-api/build/aeron-development/lib/*' \
  com.exchange.core.api.trading.infrastructure.aeron.DevelopmentOrderClientKt \
  /tmp/exchange-market115-example 19700 R-SUI-2 U1 O-SUI-2 BUY 20000000 20000000000 ledger-R-SUI-2 2026-10-22T00:01:00Z --market SUI-USDT

```

환경 기반 모드의 별도 Driver·한 노드 loopback 실행은 `DevelopmentMarketMainTest`가 구성한다. 기본 BTC를 확인할 때는 `FUNDING_MARKETS_JSON`을 제거한다. 빈 문자열로 지정하는 것은 입력 오류다.

</details>

<details markdown="1">
<summary>설명의 기준과 검증 현황</summary>

설명 기준은 #115의 `feat/development-market-selection/115` 변경이다. 통합 기준은 `abeb75416ba61bf73b3d89bd8b0d6855ff441dce`다. 줄 번호는 이 문서와 함께 게시한 코드에서 확인한 위치이며, 이후 코드를 고치면 해당 설명과 줄 번호도 다시 맞춘다.

내용 기준: 설명 시점에 변경·추가된 Kotlin·Compose 16개 파일을 경로 순서로 정렬하고 각 `경로 + NUL + 내용 + NUL`을 이어 계산한 SHA-256은 `ab5c69a812ca90f8865a36803e4caa1aee4940e2494de206f4dac9e772f7a81e`다. 이 문서 자체는 해시 대상에서 제외했다. 이후 코드가 바뀌면 이 값과 관련 설명을 함께 갱신한다.

읽은 범위는 새 마켓 파서, 세 main의 설정·선택 경로, Registry 전체 조회, READY 출력, Compose 전달, READY 비교 도구와 새·변경 테스트다. 기존 Adapter와 자금 상태는 새 입력이 연결되는 부분을 확인했다. 문서의 파일명은 저장소 검색용 텍스트이며 웹 상대 링크가 아니다.

| 항목 | 현재 기록 |
| --- | --- |
| 코드 설명 | 위 작업 파일 기준으로 작성 |
| 실행 검증 | app-api의 Aeron·코덱 288개, domain-trading 100개, 구조 375개: 총 763개 성공. 실패·오류·skip 0. 공통 ktlintCheck와 구조 P01~P09 확인 통과 |
| 독립 검토 | 작성 대화 없는 별도 checkout에서 동일 구현·테스트·Compose 내용 검토. 확인된 미해결 결함 0건. 별도로 API 86개·구조 375개와 외부 입력·SUI SELL 반례 실행 |
| 남은 범위 | 여러 마켓의 세 노드 대조는 #116, 동시 요청은 #117, RDB 설정 공급은 #118 |

실제 검증 명령은 다음과 같다. 4분 56초에 성공했으며, 저장소의 모든 DB 테스트를 실행한 결과는 아니다. Docker에서는 기존 자금 준비·매수 순서·매도·부족·승인/거절 재전송·문맥 수정·동시 요청의 8개 시나리오를 확인했다. #116의 새 다중 마켓 세 노드 검증은 포함하지 않는다.

```bash
cd /Users/0chord/.codex/worktrees/issue115-market-selection/exchange-core
JAVA_HOME=/Users/0chord/Library/Java/JavaVirtualMachines/corretto-25.0.2/Contents/Home ./gradlew ktlintCheck :architecture-tests:test :architecture-tests:verifyArchitectureReport :domain-trading:test :app-api:test --tests 'com.exchange.core.api.trading.infrastructure.aeron.*' --tests 'com.exchange.core.api.trading.infrastructure.codec.*' --console=plain
```

첫 두 CLI 테스트는 옵션·외부 설정 연결이 없는 상태에서 실제 실패를 확인했다. 구현 후 새 경로 중심 45개 검사를 통과시켰고, 위 회귀 범위로 넓혀 확인했다. Compose의 미설정·빈 값·명시 값 전달도 컨테이너 환경에서 확인했다.

위 표는 2026-10-22의 로컬 검증 결과다. 게시 전 구현·테스트·Compose 16개 파일이 검증한 내용과 같음을 대조했으며, 설명 문서의 게시 시점 표현만 정리했다. 원격 CI 결과는 PR의 Checks에서 별도로 확인한다. 재실행한 JUnit 결과는 각 모듈의 `build/test-results/test`, Docker 시나리오 결과는 `app-api/build/reports/aeron-funding` 아래에 남는다.

다음으로 함께 볼 판단은 **SUI 정책 버전만 1로 보내면 왜 기록 없이 거절되고, 같은 요청을 버전 2로 고치면 판단할 수 있는가**다. 앞의 CLI 복사본 생성과 기존 Adapter의 신규 문맥 검사를 연결해서 읽으면 된다.

</details>
