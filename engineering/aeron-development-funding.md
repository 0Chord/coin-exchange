# #97 · 실제로 보내고 자금 준비 결과 받기

**기존 자금 준비 함수를 실제 Aeron 통신에 연결했다.** 같은 저장소에서 개발 노드와 개발 클라이언트를 각각 실행한다. 클라이언트는 요청을 보내는 작은 프로그램이고, 노드는 그 요청으로 같은 메모리 엔진의 잔고를 바꾸는 프로그램이다.

예: **P1로 A에게 1,000 USDT 준비 → 노드에 1,000 USDT와 OPENING 기록 → 클라이언트가 결과 수신.** 같은 P1을 다시 보내도 추가 지급하지 않는다. 같은 돈으로 주문을 보내는 연결은 [#98 구현 설명](aeron-order-admission.md)에서 이어진다.

이 문서는 [구현 명세 #97](https://github.com/0Chord/coin-exchange/issues/97)의 단일 개발 노드 범위를 다룬다.

## 응답이 없어도 돈은 이미 반영됐을 수 있다

| 순서 | 실제로 일어난 일 | 클라이언트가 아는 것 |
| --- | --- | --- |
| 전송 수용 | Aeron이 요청 바이트를 받음 | 아직 준비 결과는 모름 |
| 업무 반영 | 노드가 잔고와 OPENING을 함께 기억함 | 응답을 못 읽었다면 여전히 모름 |
| 결과 수신 | 해당 준비 원본의 결과를 읽고 대조함 | 최초 준비·이미 준비·두 충돌 중 결과를 알게 됨 |

응답을 놓쳤다고 새 P2를 만들어 다시 지급하면 안 된다. **같은 P1·사용자·자산·금액**으로 확인하면 #95의 기존 이력이 최초 결과를 돌려준다. 돈은 그대로이고 원장도 한 건이다. 이 응답은 최초 지급의 결과이며 현재 잔고 조회가 아니다.

전체 흐름은 네 가지다. **처음 준비**, **같은 요청 재확인**, **내용 충돌·이미 사용한 계정**, **손상 메시지·내부 오류·응답 미확인**. 아래에서 필요한 흐름만 펼쳐 읽는다.

<details markdown="1">
<summary>1. 어떤 코드가 실행되고, 어디서 돈이 바뀌는가</summary>

1. [DevelopmentFundingClient.kt](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingClient.kt)의 개발용 `main`이 명시한 값으로 준비 요청을 만든다. `send`는 기존 #96 코덱으로 바이트를 만들고 `AeronCluster.offer`에 전달한다. 양수면 전송 수용이다.
2. [DevelopmentFundingNode.kt](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingNode.kt)의 개발용 `main`은 실제 Driver·Archive·Consensus·서비스와 **빈 OrderAdmissionState 하나**를 만든다. 기본 Spring HTTP 실행은 이 코드를 호출하지 않는다.
3. [DevelopmentFundingAdapter.kt](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingAdapter.kt)의 `onSessionMessage`는 확정 callback의 정확한 바이트 구간을 복사하고 기존 코덱으로 읽는다. 손상되거나 미지원인 메시지는 여기서 제외한다. #98부터 정상 주문 종류는 같은 상태의 주문 접수로 연결한다.
4. `prepare`가 **같은** [OrderAdmissionState.prepareDevelopmentBalance](../domain-trading/src/main/kotlin/com/exchange/core/trading/OrderAdmissionState.kt)를 호출한다. 돈이 바뀌는 곳은 여기다. 첫 요청이면 잔고·준비 기록·OPENING을 반영하고, 같은 요청이면 이미 저장한 첫 결과만 반환한다.
5. 어댑터는 결과를 바이트로 만들고, 같은 서비스 스레드에서 완성한 불변 관찰값과 적용 위치를 공개한다. 외부 관찰자가 가변 엔진을 직접 읽지 않는다. 이 관찰값은 디스크 Snapshot이 아니다.
6. 리더·유효 세션일 때만 이미 만든 결과 바이트를 보낸다. 전달이 밀려도 돈을 다시 준비하지 않는다. 역할·세션이 없는 경우에도 확정된 업무 적용을 건너뛰지 않는다.
7. 클라이언트의 `awaitResult`가 응답을 읽어 준비 원본과 대조한다. 기한이 지나거나 연결이 끊기면 미지급 대신 **결과 미확인**을 표시한다.

| 입력/실패 | 결과와 이후 상태 |
| --- | --- |
| 처음 P1·A·USDT·1,000 | 사용 가능 1,000 USDT·예약 0 USDT, OPENING 한 건·두 분개 |
| 같은 P1·같은 원본 | 최초 원장 ID·업무 시각 재사용, 돈·원장 추가 없음 |
| 같은 P1의 금액 변경 | 내용 충돌, 기존 상태 보존·다음 정상 요청 허용 |
| 새 P2로 이미 사용한 A·USDT 준비 | 계정 사용 충돌, 기존 상태 보존·다음 정상 요청 허용 |
| 잘린/누락/미지원 바이트 | 코어 호출 없음, 다음 정상 메시지 계속 처리 |
| 두 정상 충돌 이외의 코어 오류 | 반영 전이어도 서비스의 후속 로그 처리 중단 → 소유자에게 실패 통보 → 실제 개발 런타임 종료 |
| 반영 중 오류 | 코어 blocked 보존, 정상 snapshot 공개 없음, 실제 개발 런타임 종료 |
| 적용은 끝났지만 응답만 미전달 | 돈·원장 보존, 클라이언트 결과 미확인. 같은 ID로 재확인 |

**파일의 성격:** 새 main/노드/어댑터/클라이언트는 개발 실행 코드다. 현재 HTTP 거래 API에 연결된 제품 경로가 아니다. 테스트 3파일은 실물 통신, 통제된 callback, 클라이언트 표시·전송 실패 진단을 검증한다. 빌드 2파일은 실행/상위 타입 읽기, 폴더 정책과 기존 정책 테스트는 정확한 새 위치 하나를 추가한 구조 검사 변경이다. 이 문서는 흐름·실행·테스트를 연결한다. 코덱·도메인·SQL·Protobuf 메시지는 변경하지 않았다.

</details>

<details markdown="1">
<summary>2. 개발 노드와 클라이언트를 직접 실행하기</summary>

Aeron 1.53.3·Java 25를 사용한다. JavaExec 두 작업과 테스트 JVM에 `jdk.internal.misc`·`java.util.zip`의 add-opens를 전달한다. Spring Boot의 기본 main은 기존 `ExchangeCoreApplicationKt`로 고정한다. DB/Kafka·Spring 컨텍스트를 시작하지 않는다.

이 저장소의 Git checkout 안에서 두 터미널로 실행한다. 기존 실행 폴더는 재사용하지 않는다. 금액은 최소 단위 `Long`이고 이 예제의 USDT는 1 USDT = 1,000,000 최소 단위다.

```sh
cd "$(git rev-parse --show-toplevel)"
./gradlew :app-api:runDevelopmentFundingNode --args='/tmp/exchange-funding-97-new-run 19700'
```

다른 터미널에서:

```sh
cd "$(git rev-parse --show-toplevel)"
./gradlew :app-api:runDevelopmentFundingClient --args='/tmp/exchange-funding-97-new-run 19700 P1 A USDT 1000000000 L1 2026-10-17T00:00:00.123456789Z'
```

두 번째 명령을 같은 값으로 다시 보내면 `alreadyPrepared=true`인 최초 기록을 받는다. 아직 입금 서비스·재시작 복구는 아니다. 노드를 끄면 기록 파일은 남지만, 이번 실행기는 그 디렉터리를 다시 읽어 복구하지 않는다. **다음 실험은 다른 새 경로를 명시한다. 기존 경로를 지우지 않는다.**

| 설정 | 실제 기본값과 뜻 |
| --- | --- |
| cluster / member / service | 97 / 0 / 0, 단일 개발 노드 |
| 포트 묶음 | 기준 19700부터 8개. ingress 19700, consensus 19701, log control 19702, catchup 19703, Archive UDP control 19704, Archive replication 19705, Consensus replication 19706, 클라이언트 egress 19707 |
| 주소 | loopback 127.0.0.1, 로컬 Archive 제어 요청·응답은 IPC |
| 실행 루트 | 명시한 절대 경로 밑 driver / archive / cluster. 기존 루트·사용 중 포트는 기동 실패 |
| 준비 기한 / 결과 대기 | 각각 10초 / 5초. 성공 응답 시간 보장이 아닌 개발 실행 상한 |
| offer 재시도 | 최대 100회. 서비스 응답은 추가로 100ms 상한, SleepingMillisIdleStrategy(1) 사용 |
| 세션 / keep-alive | 10초 / 500ms. 클라이언트가 egress와 연결 상태를 polling하며 갱신 |
| 동기화 | 실제 Archive data 2 / catalog 2 / Consensus 2의 Context getter를 확인 |

서비스 종료 hook은 소유자에게 신호만 보내고, 다른 스레드인 소유자가 자원을 닫는다. 자신의 service thread를 join하지 않는다. `onStart`·리더 준비를 확인한 뒤 READY를 표시한다. `onTakeSnapshot` 또는 non-null snapshotImage는 미지원 오류이며 빈 성공·자동 초기화로 처리하지 않는다.

노드의 관찰 결과에는 실제 적용 위치와 불변 업무 상태가 있다. `recordings()`는 해당 로그의 archiveId·recordingId·logSessionId를 식별한다. 원문 전체를 재생·대조하는 기능은 #99에 남겨 두었다. 단일 노드로 세 사본 HA·자동 장애 전환·전원 장애 무유실·성능 수치를 보장하지 않는다.

</details>

<details markdown="1">
<summary>3. 테스트 범위와 기대 결과</summary>

새 테스트는 **29개 정의·3파일**이다. [DevelopmentFundingNodeTest.kt](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingNodeTest.kt)의 11개는 실제 노드와 loopback UDP 클라이언트를 사용한다. [DevelopmentFundingAdapterTest.kt](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingAdapterTest.kt)의 16개는 실제 코어에 callback을 직접 연결하되 전송 상태·역할·일부 내부 오류는 Mockito/좁은 쓰기 지점으로 통제한다. [DevelopmentFundingClientTest.kt](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingClientTest.kt)의 2개는 연결·전송·수신을 Mockito로 통제해 CLI의 출력 순서와 전송 실패 원인 분류를 확인한다. 내부 반복을 각각 별도의 테스트 정의로 세지 않으며, 통제한 검사를 전체 실물 장애 실험으로 확대하지 않는다.

**응답만 버려 대기가 끝나는 테스트**에서는 빈 노드에 요청을 실제로 보내고 코어도 실제로 실행한다. 클라이언트가 받은 결과만 통제 지점에서 버린다. 결과 미확인·전송 수용과 노드의 준비 기록 한 건을 확인한 뒤, 결과 수신을 허용하고 같은 P1을 다시 보낸다. 재확인에서도 최초 기록·잔고 1,000 USDT·원장 한 건을 유지한다.

<details markdown="1">
<summary>실제 노드·통신 · 11개 테스트</summary>

### 실제 통신으로 준비한 1000 USDT는 같은 요청을 다시 보내도 한 번만 지급된다

[실제 검증문과 준비 코드](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingNodeTest.kt#L33)

- **Given:** 빈 개발 노드와 실제 클라이언트를 연결한다. P1·U1·USDT·1,000 USDT(최소 단위 1,000,000,000)·L1·고정 업무 시각을 준비한다.
- **When:** P1의 첫 결과를 받은 뒤, 같은 원본을 새 원장 ID unused·Instant.MAX 시각으로 다시 보낸다.
- **Then:** 첫 결과는 최초 성공이고 반복 결과는 alreadyPrepared=true이며 L1과 최초 시각을 유지한다. 사용 가능 1,000,000,000·예약 0, 준비 이력 1건·분개 2개다. 적용 위치는 양수이고 실제 저장 설정 getter 세 개는 2다. 기록 목록이 있으며 archiveId=97·recordingId≥0이다.

검증 범위: 실제 MediaDriver·Archive·Consensus·어댑터·UDP 클라이언트를 사용한다. 단일 노드이며 기록 원문 재생, 재시작 복구, 다중 서버 내구성은 확인하지 않는다. 포트 탐색과 임시 디렉터리는 테스트 격리용이다.

### 전송 후 실제 반영을 관찰해도 클라이언트가 읽기 전에는 완료가 아니다

[실제 검증문과 준비 코드](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingNodeTest.kt#L78)

- **Given:** 실제 노드와 클라이언트를 연결하되, 요청을 보낸 후 결과 읽기를 아직 시작하지 않는다.
- **When:** send가 전송을 수용하면 노드의 준비 이력 1건을 최대 5초 동안 기다린다. 노드 잔고를 확인한 다음에야 awaitResult로 응답을 읽는다.
- **Then:** 응답을 읽기 전에도 노드에는 사용 가능 금액 1,000,000,000이 있다. 이후 결과 읽기는 Confirmed를 반환하고 그 준비 원본은 보낸 원본과 같다. 전송 수용과 자금 반영은 클라이언트의 결과 확인보다 먼저 일어날 수 있다.

검증 범위: 실제 통신이며 중간 순서는 테스트가 awaitResult 호출을 늦춰 만든다. 응답 도착 자체를 늦추는 실험이나 화면 문구 검사는 아니다. 수신 전 Confirmed 객체를 직접 조회하는 assertion은 없다.

### 실제 응답만 버려 대기가 끝나도 같은 준비 ID로 최초 결과를 확인한다

[실제 검증문과 준비 코드](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingNodeTest.kt#L105)

- **Given:** 실제 노드·클라이언트를 연결하고 결과 대기를 100ms로 정한다. 클라이언트의 acceptResponse만 false로 두어 수신 결과를 받아들이지 않게 한다.
- **When:** ① P1을 보내 결과 대기를 끝낸다. ② 응답 수용을 켜고 같은 P1·원본으로 다시 요청한다.
- **Then:** ① 전송 수용은 true이지만 결과는 Unconfirmed이며 '결과 미확인' 사유를 담는다. 이때 노드 준비 이력은 이미 1건이다. ② 반복 결과는 Confirmed·alreadyPrepared=true·L1이고, 사용 가능 금액 1,000,000,000과 준비 이력 1건을 유지한다.

검증 범위: 요청·클러스터 처리·코어·응답 전송은 실제다. 통제한 곳은 클라이언트가 읽은 결과를 버리는 지점 하나다. 네트워크 단절·프로세스 강제 종료·복구를 재현한 것은 아니다. 대기 종료가 미지급을 뜻하지 않는 사례다.

### 두 정상 충돌과 손상 미지원 메시지 뒤에도 다른 사용자의 준비를 처리한다

[실제 검증문과 준비 코드](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingNodeTest.kt#L144)

- **Given:** 실제 노드에서 P1·U1의 첫 준비를 완료한다.
- **When:** 같은 P1의 금액을 2,000,000,000으로 바꾸고, P2로 같은 U1 계정을 다시 준비한다. 이어 7바이트 메시지·빈 명령 본문·명령이 아닌 접수 응답 종류 2의 세 입력을 직접 전송한 뒤 P3·U2·L3을 보낸다.
- **Then:** 금액 변경은 REQUEST_CONTENT_CONFLICT, P2는 ACCOUNT_ALREADY_USED다. 세 잘못된 입력은 malformedMessages=3으로 관측된다. 뒤의 U2 준비는 성공하고 준비 이력은 총 2건, 대기 주문은 없으며 노드 오류도 없다.

검증 범위: 모든 메시지가 실제 UDP·클러스터 경로를 지난다. 코어 호출 0회는 이 테스트에서 직접 계수하지 않으며, 개별 잘못된 입력의 진단 사유도 비교하지 않는다.

### 반영 전 코어 오류도 실제 노드를 닫고 성공을 응답하지 않는다

[실제 검증문과 준비 코드](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingNodeTest.kt#L179)

- **Given:** 실제 노드·클라이언트를 연결하고 결과 대기를 100ms로 정한다. 신규 P1의 원장 ID를 빈 문자열로 만든다.
- **When:** 요청한 뒤 실제 노드의 종료 신호를 최대 5초 기다린다.
- **Then:** 클라이언트는 Confirmed가 아닌 Unconfirmed를 받는다. 노드는 실제로 닫히고 오류가 기록되며 진단에 '원장'이 들어간다. 코어 blocked는 false이고 정상 상태 관찰값은 없다. 반영 전 입력 오류여도 실행 중단 정책은 적용된다.

검증 범위: 실제 코어의 원장 ID 검증과 실제 런타임 종료를 사용한다. 반영 중 부분 실패나 이후 재시작은 이 사례의 대상이 아니다.

### 기본 대기의 별도 클라이언트도 노드 종료 뒤 미확인과 재확인 안내를 남긴다

[실제 검증문과 준비 코드](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingNodeTest.kt#L196)

- **Given:** 실제 UDP 개발 노드를 시작한다. 별도 JVM에서 실제 클라이언트 main을 실행하며 기본 결과 대기 5초를 사용한다. P1·A·USDT·최소 단위 1,000,000,000·고정 시각을 보내되 신규 원장 ID는 빈 문자열로 둔다.
- **When:** 별도 클라이언트가 요청을 전송하고 실제 코어의 원장 ID 오류로 노드가 종료되게 한다. 자식 프로세스가 최대 15초 안에 끝나는지 기다린 뒤 모아 둔 출력을 읽는다.
- **Then:** 자식 프로세스는 종료 코드 1이다. 출력에는 TRANSPORT_ACCEPTED, UNCONFIRMED transportAccepted=true, '같은 준비 ID' 안내가 있고 RESULT_RECEIVED는 없다. 실제 노드도 최대 5초 안에 닫히며 오류가 남는다. 노드 종료 뒤에도 전송 수용 사실을 잃지 않고 결과 미확인 안내를 남긴다.

검증 범위: 별도 JVM의 실제 main·기본 대기·UDP·노드·코어 오류를 함께 사용하며 Mockito 전송 대역은 없다. 출력에 같은 ID 재확인 안내가 있는지 확인할 뿐 재요청을 실행하지 않는다. 지급 금액 보존이나 복구도 이 사례에서 검사하지 않는다.

### 반영 도중 쓰기가 실패해도 실제 노드가 차단된 상태를 공개하지 않고 닫힌다

[실제 검증문과 준비 코드](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingNodeTest.kt#L238)

- **Given:** 실제 노드 내부 코어의 준비 이력 Map 쓰기 한 곳만 '준비 쓰기 중단' 오류를 던지도록 바꾼다. 나머지 런타임·클라이언트·코어 경로는 실제다.
- **When:** P1을 실제 통신으로 요청하고 노드 종료를 최대 5초 기다린다. 이어 내부 코어의 정상 조회와 같은 P1의 직접 준비 호출도 시도한다.
- **Then:** 클라이언트 결과는 Unconfirmed이고 노드는 닫힌다. 관찰값은 blocked=true이며 정상 snapshot·성공 결과 대신 쓰기 실패 진단만 남는다. 내부 코어의 정상 조회와 같은 요청 처리도 IllegalStateException으로 막힌다.

검증 범위: 실제 소유자의 자원 종료와 통신을 연결하되 실패 원인은 메모리 쓰기 한 곳에서 통제한다. 이미 일부 반영됐을 수 있으므로 미지급이나 전체 롤백으로 설명하지 않는다. 모든 쓰기 실패 위치와 재시작 복구는 검사하지 않는다.

### 기동 준비 기한을 넘기면 새 노드도 READY로 남지 않는다

[실제 검증문과 준비 코드](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingNodeTest.kt#L275)

- **Given:** 새 실행 루트와 격리 포트를 마련하고 실제 노드의 시작 준비 기한을 1ms로 줄인다.
- **When:** 노드 시작을 시도한 뒤, 남은 실행 루트로 다시 시작을 시도한다.
- **Then:** 첫 시작은 실패하고 실행 루트는 남는다. 두 번째 시작은 FileAlreadyExistsException으로 거절된다. 너무 늦은 시작을 성공으로 넘기거나 남은 루트를 조용히 재사용하지 않는 결과를 확인한다.

검증 범위: 실제 시작 작업과 실제 시간 경과를 사용한다. 첫 실패의 구체적인 예외 종류·READY 값·개별 자원 종료를 assertion으로 직접 검사하지는 않는다. 정확한 시작 시간 보장이나 성능 측정은 아니다.

### 실제 Snapshot 요청은 빈 성공 대신 노드 종료로 끝난다

[실제 검증문과 준비 코드](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingNodeTest.kt#L283)

- **Given:** 실제 노드를 시작하고 그 Driver에 별도 Aeron 연결을 연다.
- **When:** 클러스터 제어 카운터를 찾아 실제 SNAPSHOT 요청을 넣고 노드 종료를 최대 5초 기다린다.
- **Then:** 노드가 닫히며 오류와 Snapshot 실패 진단이 남는다. 미지원 저장 요청이 빈 성공 callback으로 끝나지 않고 실행 종료로 이어진다.

검증 범위: 실제 런타임의 Snapshot 제어 경로다. 이 테스트는 자금 준비 후의 저장·복구나 성공 Snapshot 파일 부재를 검사하지 않는다. 실패 과정에서 생긴 기록 파일의 개수도 요구하지 않는다.

### 종료 뒤에도 기록을 보존하고 기존 디렉터리의 재실행을 거절한다

[실제 검증문과 준비 코드](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingNodeTest.kt#L297)

- **Given:** 실제 노드에서 P1 요청의 확인 응답을 받은 뒤 노드와 클라이언트를 정상 종료한다.
- **When:** 실행 폴더의 경로 목록을 보관하고, 같은 설정·같은 폴더로 노드를 다시 시작한다.
- **Then:** 정상 실행 중 오류는 없고 종료 후 archive 아래 경로가 남는다. 재실행은 FileAlreadyExistsException으로 거절되며 전후 경로 목록은 같다.

검증 범위: 실제 파일과 런타임을 사용한다. 비교 대상은 파일·디렉터리 이름 목록이며 파일 내용의 바이트 동일성이나 재시작 복구를 확인하는 테스트는 아니다.

### 사용 중 포트와 잘못된 저장 설정은 조용히 대체하지 않는다

[실제 검증문과 준비 코드](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingNodeTest.kt#L310)

- **Given:** 노드의 기준 포트를 실제 UDP 소켓으로 점유한다. 별도로 data sync=2·catalog sync=1인 Archive Context와 잘못된 실행 설정을 준비한다.
- **When:** 점유한 포트로 노드를 시작한다. 이어 잘못된 Archive Context를 conclude하고, 상대 실행 경로와 시작 기한 0인 설정을 각각 만든다.
- **Then:** 점유 포트의 노드 시작은 실패하고 실행 루트는 남는다. catalog가 data보다 낮은 Context는 ConfigurationException, 상대 경로와 기한 0은 IllegalArgumentException으로 거절된다.

검증 범위: 포트 점유는 실제 소켓이며 설정 검증도 실제 라이브러리를 호출한다. 잘못된 sync 조합은 별도 Context 검사다. 포트 실패 예외의 정확한 종류·자원 정리 전체·실제 시작 기한 소진은 이 assertion으로 확인하지 않는다.

</details>

<details markdown="1">
<summary>실제 코어 + 통제한 callback · 16개 테스트</summary>

### 비영점 구간만 복원하고 업무 시각과 불변 관찰을 보존한다

[실제 검증문과 준비 코드](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingAdapterTest.kt#L58)

- **Given:** 실제 빈 코어와 코덱을 사용하고 Cluster·세션은 Mockito로 대신한다. P1 바이트를 공유 배열의 11번째 위치에 넣으며 callback 시각은 Long.MAX_VALUE, 적용 위치의 대역 값은 256이다.
- **When:** 그 구간만 callback에 전달한 뒤 입력 배열을 0으로 덮는다. 첫 관찰값을 보관한 채 P2·U2·L2도 처리한다.
- **Then:** 첫 응답은 P1·L1·원래 업무 시각·최초 성공이고 위치 256·OFFERED다. 사용 가능 1,000,000,000·예약 0, 준비 결과 1건·분개 2개·대기 주문 없음이 확인된다. P2 이후에도 이전 관찰값은 1건이고 최신 관찰만 2건이다.

검증 범위: callback을 직접 호출하므로 실제 네트워크는 없다. 금액 계산·코어·코덱은 실제지만 적용 위치 256과 전송 성공은 대역 값이다. callback 시각이 업무 시각을 덮지 않고 이전 관찰이 바뀌지 않는 범위를 설명한다.

### 같은 원본의 재전송은 새 문맥을 사용하지 않고 최초 결과를 보낸다

[실제 검증문과 준비 코드](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingAdapterTest.kt#L97)

- **Given:** 실제 빈 코어에서 P1·L1·고정 시각의 첫 준비를 처리한다. 전송은 Mockito 세션이 수용한다.
- **When:** 같은 준비 원본을 빈 원장 ID와 Instant.MAX 시각으로 다시 callback에 전달한다.
- **Then:** 반복 응답은 최초 L1·고정 시각과 alreadyPrepared=true다. 사용 가능 금액 1,000,000,000·준비 이력 1건을 유지하며 fatal 알림은 없다.

검증 범위: 실제 코어의 재전송 판단과 실제 코덱을 사용한다. 빈 새 문맥을 소비하지 않는 계약을 확인하지만 실제 통신이나 현재 예약 금액은 이 사례에서 검사하지 않는다.

### 두 typed 충돌만 정상 응답이고 다른 계정은 계속 준비한다

[실제 검증문과 준비 코드](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingAdapterTest.kt#L112)

- **Given:** 실제 코어에 P1·U1의 첫 준비를 처리하고, Mockito 세션으로 결과 바이트를 수집한다.
- **When:** P1의 금액을 2,000,000,000으로 변경하고, P2로 U1 계정을 다시 준비한 뒤 P3·U2·L3을 처리한다.
- **Then:** 앞의 두 응답 사유는 각각 REQUEST_CONTENT_CONFLICT와 ACCOUNT_ALREADY_USED다. 그때 준비 이력은 1건이고 U2 요청 뒤에는 2건이다. 어댑터는 계속 시작 상태이며 fatal 알림은 없다.

검증 범위: 두 예외는 실제 코어가 만든다. 전송은 대역이며, 이 테스트의 응답 assertion은 사유만 비교한다. 거절 원본 전체나 각 잔고 금액 전체를 비교했다고 설명하지 않는다.

### 잘림 누락 미지원과 구간 오류는 코어에 들어가지 않고 다음 요청을 허용한다

[실제 검증문과 준비 코드](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingAdapterTest.kt#L145)

- **Given:** 실제 코어를 호출 여부 관찰용 spy로 감싼다. 헤더 잘림·본문 잘림·빈 명령 본문·주문 명령·4097바이트의 다섯 입력과 음수·Int 최대 offset 두 입력을 준비한다.
- **When:** 일곱 입력을 차례로 callback에 전달한 다음 정상 P1을 보낸다.
- **Then:** 잘못된 입력까지 malformedMessages=7이고 코어와 세션에는 호출이 전혀 없다. 정상 P1 뒤에도 오류 수는 7이며 준비 이력은 1건, fatal 알림은 없다.

검증 범위: 구간 복사·코덱·후속 코어는 실제고 callback은 직접 호출한다. spy는 코어 호출 0회를 직접 확인한다. 이미 확정 로그나 Archive에 손상 바이트가 기록됐는지는 이 테스트의 대상이 아니다.

### null 세션과 follower도 같은 업무 상태를 반영하고 응답은 보내지 않는다

[실제 검증문과 준비 코드](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingAdapterTest.kt#L173)

- **Given:** 두 조합을 각각 새 실제 코어로 준비한다. 첫 조합은 리더·세션 없음, 둘째는 follower·세션 있음이며 역할은 Mockito 값이다.
- **When:** 각 조합에 정상 P1 callback을 전달한다.
- **Then:** 두 경우 모두 사용 가능 금액 1,000,000,000과 Prepared 결과를 관측한다. 전송 시도는 0회이고 상태는 각각 NO_SESSION·NOT_LEADER이며 세션 호출도 없다.

검증 범위: 실제 업무 반영과 응답 가능 조건을 분리하는 callback 검사다. Mockito로 역할을 지정하므로 실제 follower 복제·리더 선출을 검증한 것은 아니다. null 세션도 follower와 별개의 조건으로 다룬다.

### 역압력과 admin action은 같은 바이트만 재시도하고 업무를 다시 호출하지 않는다

[실제 검증문과 준비 코드](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingAdapterTest.kt#L195)

- **Given:** 실제 코어·코덱을 두고 세션 offer가 BACK_PRESSURED, ADMIN_ACTION, 양수 99를 순서대로 반환하도록 Mockito로 통제한다.
- **When:** 정상 P1을 한 번 callback에 전달한다.
- **Then:** 전송 시도는 3회지만 바이트 내용과 버퍼 참조는 모두 같다. 사이의 idle은 2회이고 코어 준비 호출은 정확히 1회다. 최종 상태는 OFFERED·시도 3회이며 결과는 최초 성공이다.

검증 범위: 일시 전송 실패만 통제하며 자금 반영은 실제다. 세 번 보내려 한 것은 세 번 지급한 것이 아니다. 실제 네트워크에서 역압력을 발생시키거나 클라이언트 수신까지 확인한 실험은 아니다.

### 역압력 대기 중 follower가 되면 추가 offer 없이 이미 적용한 자금과 원장을 보존한다

[실제 검증문과 준비 코드](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingAdapterTest.kt#L211)

- **Given:** 첫 offer는 BACK_PRESSURED이고 다음에는 성공할 수 있도록 둔다. Mockito idle이 한 번 실행될 때 역할을 리더에서 follower로 바꾼다.
- **When:** P1을 처리하고 첫 전송 실패 뒤 idle을 거친다.
- **Then:** 두 번째 offer는 하지 않아 시도는 1회, 상태는 NOT_LEADER다. idle은 1회이고 실제 코어 호출도 1회다. 사용 가능 1,000,000,000·예약 0·준비 이력 1건·L1·최초 시각·분개 2개를 보존하며 실행은 계속된다.

검증 범위: 역할 변경은 Mockito로 만든다. 실제 리더 변경 실험은 아니지만, 재시도 사이 역할이 바뀌어도 이미 반영한 돈을 취소하거나 다시 적용하지 않는 경계를 확인한다.

### 역압력 대기 중 세션이 닫히면 추가 offer 없이 이미 적용한 자금과 원장을 보존한다

[실제 검증문과 준비 코드](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingAdapterTest.kt#L228)

- **Given:** 첫 offer는 BACK_PRESSURED이고 다음에는 성공할 수 있도록 둔다. Mockito idle이 실행될 때 세션을 종료 중 상태로 바꾼다.
- **When:** P1을 처리하고 첫 전송 실패 뒤 idle을 거친다.
- **Then:** 추가 offer 없이 시도 1회·SESSION_CLOSING으로 끝난다. idle과 실제 코어 호출은 각각 1회다. 사용 가능 1,000,000,000·예약 0·준비 이력 1건·L1·최초 시각·분개 2개가 남고 fatal 알림도 없다.

검증 범위: 세션 종료 여부는 Mockito 값이다. 실제 연결 종료를 재현하지 않으며, 응답을 더 못 보내도 이미 완료한 자금 반영은 보존되는지를 확인한다.

### 재시도 기한이 지나면 남은 횟수와 전송 가능 여부에 관계없이 추가 offer를 멈춘다

[실제 검증문과 준비 코드](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingAdapterTest.kt#L245)

- **Given:** 시도 상한은 100회지만 기한은 10ms다. 첫 offer는 BACK_PRESSURED, 다음 offer는 성공하도록 두고 Mockito idle에서 실제 단조 시각으로 10ms 이상 기다린다.
- **When:** P1을 처리한 뒤 첫 전송 실패의 대기 중 기한을 소모한다.
- **Then:** 추가 offer 없이 시도 1회·DEADLINE_EXCEEDED로 끝난다. 코어 호출은 1회이고 사용 가능 1,000,000,000·예약 0·준비 이력 1건·L1·최초 시각·분개 2개를 보존한다. 실행은 계속된다.

검증 범위: 전송 반환값과 idle 행동은 통제하고 시간 경과는 실제로 만든다. 횟수 여유와 전송 가능성이 있어도 기한을 지키는 검사이며 응답 시간 SLA나 실제 네트워크 지연 측정은 아니다.

### 전송 상한과 종단 반환값은 적용을 되돌리거나 무한 재시도하지 않는다

[실제 검증문과 준비 코드](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingAdapterTest.kt#L266)

- **Given:** 네 경우를 각각 새 실제 코어로 실행한다. offer의 반복 반환값을 BACK_PRESSURED·NOT_CONNECTED·CLOSED·MAX_POSITION_EXCEEDED로 통제하고 시도 상한은 3회로 둔다.
- **When:** 각 경우 정상 P1을 한 번 처리한다.
- **Then:** BACK_PRESSURED는 3회 후 ATTEMPTS_EXHAUSTED다. 나머지 셋은 1회 후 각 반환값에 대응하는 진단으로 끝난다. 모든 경우 준비 이력은 1건이고 코어 호출은 1회이며 어댑터는 계속 시작 상태이고 fatal 알림도 없다.

검증 범위: 실제 네트워크 오류가 아니라 Mockito 반환값별 경계다. 전송 실패 뒤 준비 이력이 남는 것을 확인하지만 이 사례는 잔고·원장 전체 값이나 나중의 클라이언트 재확인을 직접 비교하지 않는다.

### 닫는 세션은 전송하지 않고 background 작업도 전송하지 않는다

[실제 검증문과 준비 코드](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingAdapterTest.kt#L294)

- **Given:** Mockito 세션을 callback 시작 전부터 종료 중으로 둔다. 업무 상태는 실제 빈 코어다.
- **When:** 정상 P1을 처리한 뒤 background callback을 시각 인자 0·1·2로 세 번 호출한다.
- **Then:** 세 background 호출의 반환은 모두 0이다. 전송 시도 목록은 비어 있고 상태는 SESSION_CLOSING이지만 준비 이력은 1건이다.

검증 범위: callback을 직접 호출해 background에서 전송을 재개하지 않는 범위를 확인한다. 실제 세션 닫기나 별도 스레드의 장시간 동작을 관찰하는 테스트는 아니다.

### 반영 전 코어 오류도 소유자에게 알리고 후속 callback을 중단한다

[실제 검증문과 준비 코드](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingAdapterTest.kt#L312)

- **Given:** 실제 빈 코어에 신규 P1을 보내되 원장 ID만 빈 문자열로 만든다. 소유자에게 보낼 fatal 알림은 목록에 수집한다.
- **When:** 잘못된 신규 요청을 처리하고, 이어 정상 P1 callback도 다시 호출한다.
- **Then:** 첫 호출은 IllegalArgumentException을 원인으로 가진 AgentTerminationException이며 같은 원인이 소유자 알림에 1회 전달된다. 실행은 멈추고 코어 blocked=false·빈 잔고를 유지한다. 정상 관찰값 없이 실패 진단이 남고 후속 호출도 종료 예외이며 응답은 없다.

검증 범위: 원장 ID 오류는 실제 코어가 만든다. 확인하는 소유자 행동은 알림 수집까지이며 실제 MediaDriver 등의 종료는 NodeTest에서 따로 다룬다.

### 반영 중 Exception과 Error는 blocked 상태의 정상 snapshot을 읽지 않고 중단한다

[실제 검증문과 준비 코드](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingAdapterTest.kt#L335)

- **Given:** 두 경우를 각각 준비한다. 실제 코어의 준비 이력 Map 쓰기 한 지점이 IllegalStateException 또는 AssertionError를 던지도록 바꾼다.
- **When:** P1 처리 중 해당 쓰기를 실패시키고, 이어 P2·U2·L2의 callback과 정상 상태 조회를 시도한다.
- **Then:** 주입한 오류 객체가 종료 예외의 원인과 소유자 알림에 그대로 남는다. 코어와 관찰값은 blocked=true이고 정상 snapshot·결과는 없다. 직접 정상 조회는 실패하며 후속 요청도 종료 예외로 막히고 응답은 없다.

검증 범위: 쓰기 실패 한 곳만 통제하며 그 앞의 실제 코어 반영은 수행한다. 모든 실패 위치·전체 롤백·실제 노드 자원 종료까지 증명하지 않는다. blocked 상태는 돈이 미반영됐다는 뜻이 아니다.

### 통제된 코어 반환의 결합 오류와 인코딩 오류도 정상 거절로 숨기지 않는다

[실제 검증문과 준비 코드](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingAdapterTest.kt#L355)

- **Given:** Mockito가 코어 반환만 두 값으로 바꾼다. 하나는 다른 준비 ID OTHER의 결과이고, 다른 하나는 원본은 같지만 원장 ID가 257자인 반복 결과다.
- **When:** 각 반환값에 대해 정상 P1 callback을 호출해 응답 결합과 인코딩 경로로 보낸다.
- **Then:** 두 경우 모두 AgentTerminationException이 발생하고 그 원인과 소유자에게 전달된 오류는 같은 객체다. 실행은 멈추고 정상 결과 관찰도 없으며 응답 전송 목록도 비어 있다.

검증 범위: 비정상 코어 반환은 의도적으로 만든 대역 값이므로 실제 지급 성공을 뜻하지 않는다. assertion은 두 오류의 구체적인 원인 클래스까지 비교하지 않으며 실제 노드 종료도 이 테스트 밖이다.

### 복구 image와 snapshot 요청은 성공 callback으로 반환하지 않는다

[실제 검증문과 준비 코드](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingAdapterTest.kt#L373)

- **Given:** ① 아직 시작하지 않은 어댑터와 대역 복구 Image를 준비한다. ② 별도의 어댑터에서는 실제 코어로 P1을 준비하고 응답 수를 보관한다.
- **When:** ① non-null Image로 onStart를 호출한다. ② 대역 publication으로 Snapshot 저장 callback을 호출하고 뒤에 P2 요청도 보낸다.
- **Then:** ① 미지원 복구가 원인인 AgentTerminationException, 시작 상태 false, fatal 알림 1회를 확인한다. ② 미지원 저장이 원인인 종료 예외와 시작 상태 false를 확인하며 후속 P2도 막히고 기존 응답 수는 늘지 않는다.

검증 범위: Image·publication은 Mockito 대역이고 lifecycle callback을 직접 호출한다. 실제 Snapshot 제어 요청의 노드 종료는 NodeTest가 맡으며, 이 사례는 영구 저장·복구 파일을 만들거나 읽지 않는다.

### 종료 알림 자체의 오류는 원래 fatal 원인을 가리지 않는다

[실제 검증문과 준비 코드](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingAdapterTest.kt#L394)

- **Given:** 실제 빈 코어의 어댑터를 시작하되, 소유자 알림 함수가 별도의 IllegalStateException을 던지도록 준비한다. Cluster와 Snapshot publication은 대역이다.
- **When:** 미지원 Snapshot 저장 callback을 호출해 원래 오류와 알림 오류가 연달아 발생하게 한다.
- **Then:** 종료 예외의 주원인은 여전히 미지원 저장의 UnsupportedOperationException이다. 알림 오류는 그 원인의 suppressed 목록에 포함되며 실행은 멈춘다.

검증 범위: 알림 함수 실패만 통제한다. 최초 실패 원인을 보존하는 예외 연결 검사이며 실제 종료 소유자의 자원 정리나 네트워크 전송은 확인하지 않는다.

</details>

<details markdown="1">
<summary>클라이언트 표시·전송 실패 진단 · 2개 테스트</summary>

전송 수용 시점에는 **결과 대기**를 먼저 표시하고, 연결 문제와 전송 채널의 종단 오류는 원인을 나누어 표시한다. 두 테스트 모두 전송 결과를 통제하며 실제 자금 반영 여부는 앞의 노드 테스트에서 확인한다.

### 연결 문제와 publication 종단은 원인을 구분하고 반복 전송하지 않는다

[실제 검증문과 준비 코드](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingClientTest.kt#L46)

- **Given:** 세 경우마다 Mockito 연결을 새로 만든다. 실제 연결 생성은 대역을 반환하게 하고, offer는 `NOT_CONNECTED`·`CLOSED`·`MAX_POSITION_EXCEEDED` 중 하나를 반환하게 한다.
- **When:** 각 경우 P1·A·USDT·최소 단위 1,000,000,000·L1·고정 시각으로 클라이언트 `request`를 호출한다.
- **Then:** 모두 `Unconfirmed`이고 `transportAccepted=false`다. 사유에는 반환값의 기호명이 들어간다. `NOT_CONNECTED`와 `CLOSED`는 ‘연결’, `MAX_POSITION_EXCEEDED`는 ‘종단’으로 구분한다. 각 경우 전송 시도는 정확히 1회, 결과 읽기 `pollEgress`는 0회다.

검증 범위: 클라이언트의 실제 판단 코드를 사용하지만 연결 생성과 전송 반환값은 Mockito로 통제한다. 실제 소켓 연결 실패나 채널 한도 소진을 재현하지 않는다. 이 결과로 자금이 미지급됐다고 판단하지도 않는다.

### 실제 main은 전송 수용과 결과 대기를 먼저 표시한 뒤 수신 결과를 표시한다

[실제 검증문과 준비 코드](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingClientTest.kt#L70)

- **Given:** 실제 CLI `main`과 클라이언트·코덱을 사용한다. 연결 생성은 Mockito 대역으로 바꾸고 전송은 양수 123을 반환하게 한다. 표준 출력을 모으며, 첫 결과 읽기에서 P1의 성공 응답 바이트를 전달하도록 준비한다.
- **When:** P1·A·USDT·금액·L1·고정 시각을 인자로 실제 `main`을 호출한다. 첫 `pollEgress`에 들어가면 응답 전달 전 출력을 검사한 뒤, 실제 코덱으로 만든 바이트를 등록된 수신 listener에 넣는다.
- **Then:** 응답 전달 전에는 `TRANSPORT_ACCEPTED`와 ‘결과 대기’가 있고 `RESULT_RECEIVED`는 없다. 응답 전달 후 전체 출력에서는 `TRANSPORT_ACCEPTED`가 `RESULT_RECEIVED`보다 먼저 나온다. 결과 읽기는 1회이고 `main`은 연결을 닫는다.

검증 범위: CLI 진입점과 출력 시점은 실제로 실행하지만 `connect`·`offer`·`poll`은 Mockito로 통제한다. 성공 응답도 테스트가 만든 바이트이므로 실제 소켓 왕복이나 노드의 지급 성공을 증명하지 않는다. 전송 수용을 지급 완료로 표시하지 않는 순서를 확인한다.

</details>

</details>

<details markdown="1">
<summary>4. 검증 결과와 검토사항</summary>

통합 기준은 `42b7bb767b5f0f09a0f3eef00db85e0637759c9c`다. 검증한 범위와 결과는 다음과 같다.

| 검증 대상 | 실제 결과 |
| --- | --- |
| app-api 개발 자금·코덱 | 92개 통과: 새 테스트 29개·기존 코덱 63개 |
| 순수 domain-trading 코어 | 99개 통과 |
| 전체 구조 검사 | 375개 통과 |
| 운영 구조·폴더 정책 선택 검사 | 19개 통과 |
| 구조 보고서 검증·공통 lint | 통과 |
| bootJar | 생성 성공, Start-Class는 `com.exchange.core.ExchangeCoreApplicationKt` |

테스트의 실패·오류·건너뜀은 0개다. 저장소 checkout 안에서 다음 명령으로 관련 검사를 실행할 수 있다.

```sh
cd "$(git rev-parse --show-toplevel)"
./gradlew :app-api:test --tests '*DevelopmentFunding*' --tests '*codec.*' ktlintCheck
./gradlew :architecture-tests:test --tests '*ProductionArchitectureTest' --tests '*NamingPlacementScopeTest' :architecture-tests:verifyArchitectureReport :app-api:bootJar
./gradlew :domain-trading:test :architecture-tests:test
```

개발 실행 작업도 별도 터미널에서 확인했다. 최초 P1은 `TRANSPORT_ACCEPTED` → `RESULT_RECEIVED`·alreadyPrepared=false 순서였고, 새 원장 ID·시각으로 같은 원본 P1을 다시 보내면 alreadyPrepared=true와 최초 L1·시각을 반환했다. 노드 종료 뒤 기록 파일도 보존됐다.

별도 JVM의 실제 클라이언트는 기본 5초 대기 중 노드가 종료돼도 전송 수용 여부를 보존해 `UNCONFIRMED`와 같은 준비 ID 재확인 안내를 남긴다. 비동기 Aeron 오류는 기록하고 요청 스레드에서 관찰하며, 성공 응답이나 추가 지급으로 바꾸지 않는다.

기존 폴더 정책 검사는 허용 폴더 21개와 새 Aeron 폴더의 정확한 패키지 연결을 확인한다. 임의 하위 폴더 전체를 허용하거나 검사 대상을 제외하는 변경은 없다.

검증 범위는 단일 개발 노드의 자금 준비와 전송·종료 경계다. 전체 Spring HTTP·DB 제품 검사와 원격 CI는 위 실행 결과에 포함하지 않는다. 실제 통신과 통제한 반환값·쓰기 실패의 범위는 각 테스트 설명에 구분했다. 주문 연결은 #98, Archive 원문 전체 재생·대조는 #99의 범위이며 재시작 복구·다중 노드 HA·전원 장애 내구성·성능 보장은 포함하지 않는다.

**Draft 검토사항 — 제한시간 계산:** 클라이언트의 전송·응답 대기와 노드 기동 기한은 `System.nanoTime() + timeout`으로 만든 절대 마감값과 현재 값을 비교한다. `Long` 순환 경계의 덧셈 overflow가 기한 판정을 바꿀 수 있어 `System.nanoTime() - started >= timeout` 같은 경과시간 비교로 정리하는 개선을 검토한다. 이 제안은 현재 제품에 반영하지 않았고 해당 경계도 검증하지 않았다. 단조시각을 이용한 대기 제한 계산에 관한 사항이며, 노드 간 시각 차이로 명령의 업무 `Instant`나 금액·중복 규칙이 바뀌는 문제와 구분한다.

</details>
