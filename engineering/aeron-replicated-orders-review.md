# #107 · 응답을 받은 뒤 세 노드의 돈과 기록까지 확인하기

**로컬 검증·독립 리뷰 완료. 원격 CI는 PR에서 별도로 확인한다.** 주문 응답부터 세 노드의 업무 상태 대조까지 코드와 테스트를 따라 읽는 문서다.

이번 변경은 **기존 주문 접수를 세 Docker 노드에 연결하고, 응답과 같은 새 적용 위치의 세 업무 상태를 함께 대조하는 것**이다. 주문 계산·체결·정산을 새로 만들지 않는다. #106에서 자금 준비 P1 한 건을 확인하던 실행기에 단계별 주문 클라이언트와 주문 의미 필드 대조를 연결한다.

| 이번에 읽을 세 흐름 | 입력 → 확인할 결과 |
| --- | --- |
| 매수 두 건 | USDT 1,000 준비 → 300 예약 뒤 사용 가능 700·예약 300 → 100 추가 예약 뒤 사용 가능 600·예약 400. 승인 순번 1·2와 실제 대기 순서 O1 → O2를 확인한다. |
| 매도 | 새 빈 실행에서 BTC 3 준비 → BTC 2 예약. 사용 가능 BTC 1·예약 BTC 2, 수수료 예약 0, USDT 잔고 항목 없음. |
| 부족 거절 | 새 빈 실행에서 USDT 1,000 준비 → O1에 300 예약 → 새 R2/O2가 800 요구. 사용 가능 700·예약 300과 O1을 유지하고 부족 100의 최초 거절 기록만 추가한다. |

먼저 아래의 **응답과 세 사본 확인이 다른 이유**를 읽고, 이어서 입력 사이의 기다림과 거절 기록을 읽으면 된다. 파일·원문·테스트 설명은 접힌 상세에 있다.

## 응답을 받았는데 왜 세 노드를 더 확인할까

매수 R1/O1의 승인 응답에는 사용자·요청·마켓·주문 ID와 승인 순번이 있다. 사용자가 제출한 주문의 답을 받았음을 확인하는 값이다. 그 작은 응답 안에는 node0·node1·node2 각각의 잔고, 예약, 전표, 요청 이력이 담겨 있지 않다. 팔로워의 업무 적용과 main의 관찰 출력도 별도로 진행된다.

따라서 이번 실행기는 승인 응답을 받더라도 세 출력이 모두 도착할 때까지 그 단계를 끝내지 않는다. 이번 runId와 이번 입력을 가리키는 리더의 `APPLIED`에서 로그 위치 L을 얻고, 세 멤버가 **그 L**에서 만든 불변 관찰을 대조한다. L은 로그의 바이트 위치다. 승인 순번 1·2와 다르며, 단계마다 연속 정수일 필요가 없다.

| 지금 가진 근거 | 확인한 사실 | 아직 필요한 것 |
| --- | --- | --- |
| `TRANSPORT_ACCEPTED` | Aeron 전송이 수용됐다. | 접수 결과 응답과 세 상태 |
| `RESULT_RECEIVED` | 이번 주문의 승인 또는 거절 응답을 받았다. | 세 노드가 같은 새 L에서 보관한 업무 상태 |
| 한 노드의 `APPLIED` | 그 노드가 해당 위치의 상태를 관찰했다. | 나머지 두 노드의 같은 L과 독립 기대값 |
| 응답 + 세 노드의 같은 새 L + 기대값 | 이번 입력의 관찰 가능한 잔고·예약·원장·요청 결과가 맞다. | 이번 범위 밖인 장애 전환·재시작 복구·성능 |

세 사본이 같은 값이어도 그 값이 모두 틀릴 수 있다. 그래서 700/300, O1의 예약 300, AVAILABLE DEBIT/HOLD CREDIT, 고정 원장 ID·사건 ID·시각을 명세의 독립 정답과 각각 대조한다. 제품 계산기를 다시 호출해 기대값을 만들지 않는다.

## 다음 주문은 앞 단계의 확인 뒤에 보낸다

매수 사례는 P1 확인 → R1 확인 → R2 확인 순서다. P1을 완료한 L0를 다음 단계의 `previousPosition`으로 넘긴다. R1은 L1 > L0인 같은 위치의 세 관찰과 응답이 있어야 완료한다. R2도 L2 > L1을 요구한다. 응답이나 관찰 하나가 빠지면 그 사례의 다음 입력을 보내지 않는다.

이 기다림에는 관찰을 놓치지 않게 하는 목적도 있다. 기존 Adapter는 가장 최신의 불변 관찰 하나를 공개하고 node main이 주기적으로 출력한다. 다음 입력을 먼저 보내면 아직 출력하지 않은 이전 관찰이 바뀔 수 있다. 이번에는 세 출력을 확보한 뒤 다음 입력을 보내며, 동시 요청 큐나 영구 Snapshot 저장소는 추가하지 않는다.

같은 L의 `PENDING`·`OFFERED`·`NOT_LEADER` 갱신은 응답 전달 진단이다. 이를 주문 두 번 적용으로 세지 않는다. 그러나 같은 L에 잔고나 후보가 다른 행이 하나라도 있으면 정상 행 하나를 골라 숨기지 않고 차이를 남긴다.

## 거절 기록에는 원장 ID가 있어도 돈을 묶은 것은 아니다

R1/O1 뒤에는 사용 가능 700·예약 300이다. 새 R2/O2가 800을 요구하면 100이 부족하다. R2의 원본·당시 마켓과 수수료 정책·제출한 원장 ID와 시각·최초 거절 결과를 요청 이력에 남긴다. O2 예약이나 실제 RESERVE 전표, 승인 순번 2는 만들지 않는다.

| 부족 거절 전후 | 변하지 않는 것 | 추가되는 것 |
| --- | --- | --- |
| R1 승인 → R2 부족 거절 | 잔고 700/300, O1 당시 후보, O1 예약, O1 RESERVE, 대기 O1, 마지막 승인 순번 1 | R2/O2 원본과 부족 100을 보존한 요청 기록 |

반면 두 번째 매수가 승인되는 사례에서는 현재 잔고가 600/400으로 바뀐다. 그래도 O1의 당시 후보와 최초 결과는 700/300을 유지한다. 과거 후보를 현재 잔고로 덮어쓰지 않는지 별도로 확인한다.

## 실패하면 다음 입력을 멈추고 실제 쓰기 주체를 닫는다

설정·이미지·기록 경로 준비 실패는 `SETUP`, 응답·관찰·위치 미확인은 `UNVERIFIABLE`, 의미 필드 차이는 `MISMATCH`로 남긴다. 이미 읽은 업무 차이는 뒤의 자료 수집 실패로 지우지 않는다. 예상한 부족 거절은 정상 업무 결과다.

클라이언트 감독 시간이 끝나 Compose 명령을 종료해도 컨테이너 안의 JVM이 자동으로 종료됐다고 볼 수 없다. 실행기는 시작한 모든 client 이름을 보관하고 실제 컨테이너의 실행·종료를 확인한다. 남아 있는 client를 멈춘 뒤 노드를 닫고, `STOPPED.closed`와 종료 상태를 확인한 다음 Archive·cluster를 수집한다. 이번 컨테이너와 네트워크를 정리하며 기록 볼륨은 삭제하지 않는다. 종료·복사·정리 실패도 전체 실패로 남긴다.

<details markdown="1">
<summary>실제 코드에서 응답·상태·다음 입력을 연결해 읽기</summary>

### 기존 코어는 어디서 호출되나

변경하지 않은 [DevelopmentFundingAdapter.kt](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingAdapter.kt)의 `onSessionMessage`가 확정 입력을 해석하고 기존 `OrderAdmissionState.admit` 또는 `prepareDevelopmentBalance`를 호출한다. 서비스 스레드가 만든 Snapshot·결과·적용 위치를 묶은 후 응답 전달을 시도한다. 아래는 현재 파일 133–144줄의 원문이다.

```kotlin
            val applied =
                FundingObservation(
                    cluster.logPosition(),
                    state.snapshot(),
                    fundingResult,
                    malformedMessages = malformedMessages,
                    replyStatus = "PENDING",
                    admissionResult = admissionResult,
                    appliedRole = cluster.role().name,
                )
            observation = applied
            observation = sendReply(session, reply, applied)
```

응답을 받는 경로와 세 노드의 관찰을 읽는 경로가 여기서 갈라진다. Snapshot은 메모리의 읽기 전용 관찰 값이다. Aeron의 영구 Snapshot 저장·복구를 뜻하지 않는다. 구조화 출력은 node main에서 하고, 출력 함수가 외부 스레드에서 State를 직접 조회하지 않는다.

### 별도 주문 클라이언트가 갖추는 것

[DevelopmentOrderClient.kt](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentOrderClient.kt)의 `runDevelopmentOrderClient`는 `--cluster-env`일 때 공통 환경 설정으로 입력을 만들고 자기 MediaDriver를 소유한다. 모든 멤버의 ingress를 연결 후보로 주며, 응답 주소는 노드 주소가 아닌 client 자신의 광고 주소다. 응답은 U1/R1/BTC-USDT/O1 네 식별자가 현재 입력과 맞아야 수용한다.

`TRANSPORT_ACCEPTED`, `RESULT_RECEIVED`, `UNCONFIRMED`를 구조화해 출력한다. 결과 미확인은 돈이 반영되지 않았다는 판단이 아니다. 기존 로컬 인자 실행은 노드 Driver를 사용하고, 환경 실행에서 만든 Driver만 `finally`에서 닫는다. 업무 wire 종류와 작은 응답 payload는 기존 것을 사용한다.

### 이번 출력은 무엇을 펼치나

[DevelopmentFundingEvents.kt](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingEvents.kt)의 `fundingAppliedFields`는 기존 FUNDING 필드를 유지하고 ORDER 입력 종류와 사용자·요청·주문·마켓을 추가한다. `admissionResultFields`는 작은 승인/거절 응답만 펼친다.

`admissionStateFields`는 실제 대기 순서의 `pendingOrders`, 요청 원본과 최초 결과의 `requests`, 준비 전표와 승인 후보의 실제 RESERVE를 모은 `transactions`를 출력한다. 후보에는 이전/이후 Balance·주문 8필드·마켓 4필드·수수료 정책 전체·전체 예약·RESERVE를 담는다. 요청과 전표 시각은 문자열·epochSecond·nano로 남긴다. 일반 클래스의 객체 주소나 Snapshot 전체 `equals`에 의존하지 않는다.

### 어느 지점이 다음 입력을 막나

[DockerFundingReplicationTest.kt](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerFundingReplicationTest.kt)의 `runOrderInputs`는 단계별 client 출력과 세 관찰을 `compareOrderReplication`에 넘긴다. 아래는 현재 파일 177–179줄의 원문이다.

```kotlin
            // 응답과 세 사본이 확인되지 않았으면 다음 입력을 보내지 않는다.
            if (issues.isNotEmpty()) break
            previousPosition = checkNotNull(comparison.position)
```

성공한 단계만 checkpoint를 전진시킨다. 단계는 대기 상한을 갖고, 실패 뒤 새 요청 ID로 자동 재전송하지 않는다. `recheckOrderInputs`는 뒤에 수집된 출력에도 앞서 보낸 모든 단계를 다시 대조한다. 단계가 처음 통과한 뒤 늦게 도착한 같은 L의 모순도 최종 성공에서 숨기지 않는다. `preserveAndClose`는 client JVM 확인 → 노드 종료 확인 → 기록 복사 → 자원 정리를 맡는다.

### 같은 값과 맞는 값을 함께 확인하는 곳

[OrderReplicationComparison.kt](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparison.kt)의 `compareOrderReplication`은 runId와 입력 식별자로 후보를 고른다. 새 리더 위치, 노드별 같은 위치, client 전송/결과 한 건을 요구하고 관찰한 모든 후보 행의 의미 값을 확인한다. 같은 L에 다른 요청·사용자·주문 식별자의 행이 섞여 있어도 따로 대조해 모순을 찾는다. `compareOrderFields`는 지정한 필드를 재귀적으로 비교하며 배열은 길이와 순서까지 확인한다. 숫자는 정수 여부와 값을 확인한다. 역할·응답 시도 진단은 독립 업무 정답에 넣지 않는다.

[OrderReplicationScenario.kt](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationScenario.kt)는 이번 세 사례의 고정 입력과 독립 기대값이다. 가격 100·수수료 0이라는 이번 입력에서 매수 300/100과 매도 BTC 2를 명시한다. 일반 주문 계산기의 대체 구현으로 확장하지 않는다.

</details>

<details markdown="1">
<summary>변경 파일별 역할 · 실행 코드와 검사 도구를 구분하기</summary>

| 파일 | 역할 | 연결되는 흐름 |
| --- | --- | --- |
| [DevelopmentOrderClient.kt](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentOrderClient.kt) | 개발 실행 도구. 컨테이너에서 실제 사용하는 main·연결·자원 소유 | 세 사례의 주문 송수신과 결과 미확인 |
| [DevelopmentFundingEvents.kt](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingEvents.kt) | 개발 실행 도구의 환경 설정·관찰 출력 | 작은 응답과 전체 업무 관찰의 구분 |
| [DockerFundingReplicationTest.kt](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerFundingReplicationTest.kt) | 실제 Docker 테스트와 재사용 실행기 | 기존 P1 검사, 새 사례·단계·client·수집·종료 |
| [DockerOrderReplicationTest.kt](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerOrderReplicationTest.kt) | 실제 Docker 테스트 | 이미지 하나를 공유하는 세 독립 클러스터 실행 |
| [OrderReplicationComparison.kt](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparison.kt) | 테스트 도구. 출력 대조기 | 새 동일 L·응답·독립 기대값·오류 보존 |
| [OrderReplicationScenario.kt](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationScenario.kt) | 테스트용 입력·독립 정답 예제 | BUY_SEQUENCE·SELL·INSUFFICIENT의 단계별 상태 |
| [DevelopmentOrderClientTest.kt](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentOrderClientTest.kt) | 실제 단일 노드와 제어 연결을 구분한 테스트 | 자기 Driver·환경 실행·주소·실패 출력 |
| [DevelopmentFundingEventsTest.kt](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingEventsTest.kt) | 순수 출력 변환 테스트. 실제 State를 직접 호출해 관찰 입력을 준비 | 자금 호환·과거 후보·거절 기록·SELL 출력 |
| [OrderReplicationComparisonTest.kt](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt) | 순수 출력 예제 반례 테스트 | 누락·과거 L·모순·동일 오답·순서·거절 전표 |
| 이 문서 | 학습 문서 | 흐름·소스·테스트·실행 근거 연결 |

`DevelopmentFundingNode`, Adapter, State·Calculator와 도메인, Dockerfile·Compose·Gradle·기존 PR workflow는 이 문서 작성 시점에 새 변경이 없다. 기존 main 산출물 staging과 같은 이미지의 다른 main 실행을 재사용한다. HTTP·DB·Kafka를 실행에 연결하지 않는다.

</details>

<details markdown="1">
<summary>변경 테스트를 Given · When · Then으로 읽기</summary>

여기서 Then은 테스트 소스의 실제 assertion이 관측하는 값이다. 실행 결과와 재검사 이력은 마지막 절에 따로 기록한다. 비교기 반례는 잘못된 출력 예제를 실패로 판정해야 그 테스트가 통과한다.

### 실제 Docker: 주문 세 사례와 기존 P1 사례

| 테스트 | Given — 준비 | When — 실행 | Then — 실제 확인과 한계 |
| --- | --- | --- | --- |
| `DockerOrderReplicationTest`의 `매수 두 건의 순서와 매도와 부족 거절을 세 독립 클러스터 실행에서 대조한다` | 같은 실제 Java 이미지, 사례마다 다른 runId·빈 노드 볼륨·세 독립 JVM·UDP·Archive. 이미지 준비는 첫 실행에서 한다. | BUY_SEQUENCE는 P1→R1→R2, SELL은 새 P1→R1, INSUFFICIENT는 새 P1→R1→R2를 단계별 확인 뒤 보낸다. | 각 실행 결과의 실패 목록이 모두 비어야 마지막 assertion이 통과한다. 단계별 응답·새 동일 L·의미 필드·종료·수집·정리도 실행기 실패 목록에 포함된다. 한 테스트 메서드가 실제 클러스터 사례 세 개를 순서대로 실행한다. |
| `DockerFundingReplicationTest`의 기존 `한 번 보낸 P1의 자금과 OPENING이 세 독립 컨테이너의 같은 위치에 복제된다` | 같은 재사용 실행기의 기본 모드, 빈 세 노드 | Compose 기본 client로 기존 P1 한 건을 보낸다. | 기존 자금 기대값·OPENING·새 동일 L과 실행기 종료 결과가 성공이어야 한다. 주문 도구로 바꾼 뒤에도 #106 기본 동작을 유지하는 회귀다. |

직접 관찰하는 것은 한 호스트 위의 세 컨테이너다. 장애 전환·네트워크 분할·재시작 복구·처리량을 검증하는 사례는 없다.

### 환경 주문 클라이언트: 새 테스트 세 개

근거는 [DevelopmentOrderClientTest.kt](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentOrderClientTest.kt)의 해당 테스트 이름과 `clusterArguments`·`clientEnvironment`·`captureEvents`다. 이 세 helper는 실제 업무 결과 대신 정답을 만들지 않으며 환경 인자와 stdout만 준비·수집한다.

| 테스트 이름 | Given — 준비 | When — 실행 | Then — 실제 assertion |
| --- | --- | --- | --- |
| `환경 주문 실행은 별도 Driver로 승인과 부족 거절을 받고 자기 Driver만 닫는다` | 실제 단일 개발 노드에 USDT 1,000을 준비한다. R1/O1은 300, R2/O2는 800 요구다. 각 환경 실행은 새 client 폴더·Driver를 쓴다. | 환경 main으로 R1을 보낸 뒤 새 client로 R2를 보낸다. | 두 실행 모두 전송 수용→결과 수신 순서, runId와 네 입력 식별자, 고정 승인/부족 JSON을 확인한다. client Driver는 닫히고 node Driver는 살아 있다. 최종 700/300·대기 1·요청 2·순번 1과 노드 오류 없음을 확인한다. 세 노드 복제 검사는 아니다. |
| `주문 연결은 모든 멤버 ingress와 광고된 응답 주소를 사용한다` | node0·1·2 주소와 `order-client` 광고 주소를 설정한다. `AeronCluster.connect`만 제어해 받은 Context를 보관한다. | `DevelopmentOrderClient`를 생성한다. | Context에 세 ingress, client 자신의 응답 주소, 자기 Driver 경로와 연결 상한이 들어갔는지 확인한다. 실제 세 주소에 연결한 테스트는 아니다. |
| `환경 주문의 연결 실패와 수용 후 실패는 미확인 이벤트와 종료로 남기고 Driver를 닫는다` | 실제 client Driver를 만들고, 연결 함수가 예외를 던지는 경우와 전송 수용 후 poll이 예외를 던지는 경우를 제어한다. | 두 경우에 환경 main을 실행한다. | 첫 경우는 UNCONFIRMED만, 둘째는 TRANSPORT_ACCEPTED→UNCONFIRMED다. 미접수로 단정하지 말라는 예외, 원인·transportAccepted·입력 ID·Driver 종료와 offer 0회/1회를 확인한다. 실제 네트워크 장애 주입은 아니다. |

기존 로컬 main, 다른 응답 식별자·손상 응답 무시, 전송 종단 반환값, conductor 오류·offer 예외 테스트는 이번 변경에서도 회귀 대상으로 남는다. 새 테스트 3개와 그 내부 두 실패 분기를 구분하며 전체 클래스의 테스트 수를 새 사례 수로 세지 않는다.

### 관찰 출력: 새 테스트 네 개

근거는 [DevelopmentFundingEventsTest.kt](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingEventsTest.kt)다. 실제 State를 직접 호출해 출력 변환의 입력을 준비하고 고정 수치·문자열과 비교한다. Docker 송수신을 대신하는 테스트는 아니다.

| 테스트 이름 | Given — 준비 | When — 실행 | Then — 실제 assertion |
| --- | --- | --- | --- |
| `자금 관찰은 기존 필드와 OPENING의 전체 시각을 유지한다` | 빈 State에 P1 USDT 1,000·고정 원장·나노초 시각으로 자금을 준비한다. | 그 불변 관찰을 출력 맵·JSON으로 펼친다. | FUNDING/P1/U1, 최초 준비 결과, 잔고 1,000/0·빈 대기/요청, OPENING 사건과 시각 문자열·초·나노초를 확인한다. |
| `두 BUY 뒤 현재 잔고와 O1 당시 후보를 나누고 실제 순서와 최초 결과를 보존한다` | P1 뒤 300을 예약하는 R1과 100을 예약하는 R2를 준비한다. | R1 출력을 보관한 뒤 R2를 적용하고 다시 펼친다. | O1→O2·순번 1/2, 현재 600/400, O1의 과거 후보 전체 유지·이전 1,000/0→이후 700/300, 원본·문맥·전체 예약·고정 사건 ID·두 분개, 실제 전표 3개, 요청 최초 결과와 대기 record 연결, 요청 시각, 작은 승인 응답을 확인한다. |
| `부족 거절은 최초 원본과 문맥을 남기며 제출한 원장 ID로 실제 전표를 만들지 않는다` | P1→R1 승인 뒤 새 R2/O2가 800을 요구한다. | 거절 상태를 펼친다. | 잔고·대기·실제 전표·마지막 순번이 이전과 같고 요청은 2개다. R2 원본·문맥·제출 원장/시각·700/800/100 부족 결과와 작은 응답을 확인한다. 실제 O2 전표와 응답의 내부 후보가 없음을 확인한다. |
| `SELL 관찰은 BTC 예약과 영 수수료를 펼치며 존재하지 않는 USDT 잔고를 추가하지 않는다` | 새 State에 BTC 3개를 준비하고 2개 SELL을 만든다. | SELL 뒤 관찰을 펼친다. | 잔고 목록이 BTC 1/2 하나이며 전체 BTC 예약 2·최초/남은 수수료 0·feeRemainder 0·releasedAmount null인지 확인한다. |

### 비교기 반례: 새 테스트 26개

근거는 [OrderReplicationComparisonTest.kt](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt)다. 아래 준비는 모두 출력 예제이며 실제 네트워크나 엔진을 실행하지 않는다. 공통 `fixture`는 명세의 고정 기대값으로 세 노드 출력과 client 응답을 만들고, `assertMutations`는 각 지정 필드를 하나씩 바꾼다. Then의 `assertIssue`는 성공 판정이 아님과 오류 종류·경로·기대/실제 값이 들어 있음을 함께 요구한다. 올바른 출력 예제와 실제 컨테이너가 그 출력을 만들었는지는 별도 검사다.

| 테스트 위치·이름 | Given — 준비 | When — 실행 | Then — 실제 assertion |
| --- | --- | --- | --- |
| [14줄 · 세 독립 사례의 준비와 모든 주문 단계를 새 동일 위치에서 확인한다](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L14) | 세 사례의 P1과 각 주문 단계, 단계 위치 128→448→928 | 실제 공급자 `steps()`의 3·2·3단계를 각각 대조한다. | 총 8개의 정상 출력 조합에서 성공과 해당 위치를 확인한다. 실제 Docker 8회 실행을 뜻하지 않는다. |
| [27줄 · 노드 관찰 누락과 이전 실행 관찰](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L27) | 정상 R1 예제 | node2를 제거하거나 runId를 과거 값으로 바꾼다. | UNVERIFIABLE과 node2·기대 위치 448을 확인한다. |
| [40줄 · 이번 응답 누락](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L40) | 전송 수용과 세 정상 관찰 | 응답을 제거하거나 응답만 과거 runId로 바꾼다. | 이번 응답 0건이라는 UNVERIFIABLE을 확인한다. |
| [53줄 · 빈 관찰과 응답](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L53) | 빈 노드 맵·빈 client 출력 | R1 대조기를 호출한다. | 새 리더 위치 없음·세 멤버 누락·전송과 응답 0건을 모두 확인한다. |
| [66줄 · 같거나 오래된 리더 위치](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L66) | 현재 L=448 | checkpoint를 448 또는 512로 둔다. | 새 위치가 아니므로 리더와 세 멤버 확인 모두 UNVERIFIABLE이다. |
| [80줄 · 노드별 과거 또는 더 뒤의 위치](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L80) | 리더 L=448 | node2만 128 또는 928로 바꾼다. | 같은 위치 누락과 실제 위치 차이를 함께 확인한다. |
| [93줄 · 서로 다른 리더 위치](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L93) | 정상 리더 행 | 같은 입력의 리더 L=928 행을 추가한다. | [448,928] 중 하나를 고르지 않고 새 리더 위치 미확인으로 남긴다. |
| [103줄 · 역할·세션·주소·응답 시도 갱신](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L103) | 업무 값이 같은 L=448 행 | 역할·sessionId·PID·응답 주소·시도 수와 PENDING/OFFERED/NOT_LEADER를 바꾼 행을 추가한다. | 성공과 L=448을 확인한다. 이 진단 차이는 돈의 차이가 아니다. |
| [123줄 · 같은 위치의 모순된 업무 값](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L123) | node1의 정상 행과 사용 가능 699,999,999의 잘못된 행 | 두 행의 앞뒤 순서를 바꿔 각각 대조한다. | 두 순서 모두 정확한 잔고 경로·기대 700,000,000·실제 값을 포함한 MISMATCH다. |
| [136줄 · 같은 위치의 다른 요청·주문·사용자](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L136) | 정상 node1 행 | 같은 L에 requestId=R2, orderId=O2, userId=U2인 행을 각각 붙인다. | 식별자가 다른 행을 필터로 숨기지 않고 각 식별자 MISMATCH를 확인한다. |
| [150줄 · 최초 성공 뒤 늦게 수집한 모순](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L150) | 정상 예제가 먼저 성공한다. | node2 hold=1 행을 누적해 다시 대조한다. | 새로 읽은 모순의 MISMATCH를 확인한다. 실행기의 재대조 연결은 Docker 실행기 코드에서 별도로 읽는다. |
| [163줄 · 세 사본의 동일 오답](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L163) | 두 번째 BUY 뒤 정답은 600/400 | 세 사본의 사용 가능액을 모두 700으로 바꾼다. | 각 멤버의 기대 600/실제 700 MISMATCH 세 건을 확인한다. |
| [178줄 · 예약 전체 의미 필드](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L178) | 잔고·개수는 정상 | 예약의 식별자·방향·자산·가격·최초/남은 수량과 금액·최초/남은 수수료·상태·feeRemainder·releasedAmount, 총 15필드를 하나씩 바꾼다. | 각 변조 필드의 기대/실제 MISMATCH를 확인한다. |
| [203줄 · 실제·후보·최초 결과 원장](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L203) | OPENING, 실제 RESERVE, 대기 후보 RESERVE, 최초 요청 후보 RESERVE의 네 위치 | 각 위치에서 ID·사건·종류·전체 시각·두 계정/자산/방향/금액 14필드를 각각 바꾸고, 분개 하나 제거도 실행한다. | 각 필드 차이와 분개 수 2→1을 확인한다. 균형 합계만 보는 검사가 아니다. |
| [240줄 · 주문 원본·마켓·정책](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L240) | 대기 후보·요청 원본·최초 결과 후보 | 세 위치의 주문 8·마켓 4·정책 5필드를 각각 바꾼다. 후보와 최초 결과 예약의 정책 5필드도 각각 바꾼다. | 각 실제 변조 경로와 기대/실제 MISMATCH를 확인한다. |
| [260줄 · 준비 원본·승인 순번·요청 시각](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L260) | 작은 주문 응답은 정상 | 잔고 소유자/자산/hold, 네 개수·순번, 준비 원본, 대기 순번, 요청 ID·원장·전체 시각·최초 결과, 작은 응답 순번의 지정 필드를 각각 바꾼다. | 각 경로의 기대/실제 MISMATCH를 확인한다. |
| [291줄 · 실제 대기 순서 반전](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L291) | 둘째 BUY 뒤 잔고·개수는 정상 | 대기를 O2→O1로 바꾼다. | 첫 순번·주문 ID와 둘째 순번 차이를 확인한다. 대기를 정렬해 숨기지 않는다. |
| [307줄 · 과거 O1 후보 덮어쓰기](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L307) | 둘째 BUY 뒤 O1 당시 값은 1,000/0→700/300 | 대기 후보와 최초 요청 후보의 이전/이후 잔고 네 값을 각각 현재 600/400으로 바꾼다. | 두 위치 모두 당시 값과의 차이를 검출한다. |
| [324줄 · SELL BTC 예약과 USDT 없음](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L324) | SELL 최종 BTC 1/2 | 잔고·예약 자산/수량/금액/수수료·전표 계정 11필드를 각각 바꾸고 USDT 잔고를 추가한다. | 각각의 MISMATCH와 잔고 목록 길이 1→2를 확인한다. |
| [354줄 · 거절 요청 이력 제거](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L354) | 부족 거절 뒤 돈과 대기는 정상 | R2 요청 기록을 제거한다. | 요청 길이 2→1과 두 번째 객체 누락을 함께 확인한다. |
| [364줄 · 거절 원본·문맥·시각·상세](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L364) | R2 거절 기록 | 주문 8·마켓 4·정책 5필드와 요청 ID·원장·전체 시각·결과 종류/사유·부족 자산/가능/필요/부족 수치를 각각 바꾼다. | 모든 변조 경로의 MISMATCH를 확인한다. |
| [384줄 · 작은 거절 응답 대응](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L384) | R2의 최초 거절·노드 결과·client 응답 | 네 식별자·결과 종류·사유·부족 상세 4필드를 노드 결과와 client 결과에서 각각 바꾼다. | 해당 노드 또는 client 경로의 MISMATCH를 확인한다. |
| [412줄 · 금지된 O2 예약·RESERVE](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L412) | 부족 거절 상태 | O2 대기 예약·순번 2를 추가한 경우와 실제 RESERVE를 추가한 경우를 각각 만든다. | 대기 길이·마지막 순번·전표 길이 차이를 각각 확인한다. |
| [434줄 · 거절 뒤 기존 자금 변경](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L434) | 부족 거절 뒤 O1만 유지돼야 한다. | 잔고·순번·O1 예약·최초 후보·O1 분개 금액 7필드를 각각 바꾼다. | 원래 승인 값과의 MISMATCH를 확인한다. |
| [450줄 · 원래 차이와 부분 수집 오류 보존](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L450) | node0의 잘못된 hold를 이미 읽었다. | READY까지만 온 부분 로그를 누적하고 node1·2 누락과 COLLECTION 오류를 추가한다. | 원래 MISMATCH·COLLECTION·두 멤버 UNVERIFIABLE이 모두 남는다. |
| [468줄 · 중복 증거·명시적 미확인](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L468) | 정상 client 예제 | 전송 이벤트 중복, 결과 이벤트 중복, UNCONFIRMED 추가를 각각 만든다. | 전송 2건·응답 2건·명시적 미확인을 성공으로 판정하지 않는다. |

</details>

<details markdown="1">
<summary>대상 버전 · 실행 근거 · 검토 범위</summary>

### 읽은 대상과 원문 스냅샷

구현 브랜치는 `test/aeron-container-orders/107`, 비교 기준은 통합 `d0c3b546c512223f2036c7151f2c9f514ba9b035`다. 신규 파일을 포함한 구현 상태를 고정 스냅샷으로 대조했다. 최초 수집 뒤 실행기·비교기·반례와 전체 원문 해시를 `2026-10-21 12:48:08 KST`에 다시 확인했다. 아래 SHA-256은 그 시점 파일 내용 기준이며 이후 수정·포맷 변경을 자동 반영하지 않는다.

| 원문 파일 | SHA-256 |
| --- | --- |
| DevelopmentFundingAdapter.kt · 발췌 133–144줄 | `04bd524db6d93a84b9167aebeb68c3bfcfe56b3636be85e74d1b9825711f90fe` |
| DevelopmentOrderClient.kt | `5040d7f69e84e78a66038bac3a98c30f66e2fa737f819c3dd8f4f2dc5fcedace` |
| DevelopmentFundingEvents.kt | `04f4faaa9c130637f3d966d509c7ecc8227125a4d5023c333a6f7d16e2ca57e3` |
| DockerFundingReplicationTest.kt · 발췌 177–179줄 | `8dfaa5c35abbcd21552ebd3cf1b905124178b0eb2e98d0977b604e3a56df1d64` |
| DockerOrderReplicationTest.kt | `7f017d0da1eeac34112d29be17bd388e2fcc9809498bf32e39fa347a78401f5d` |
| OrderReplicationComparison.kt | `6b8af17e0315c675a198b9b9109ea03a810602fbc61b40906c392715367bcd10` |
| OrderReplicationComparisonTest.kt | `df56e6f91dfbfe99c3fa4f1f2b516e8562a25b5a6a2bfe1e63159322518b0e64` |
| OrderReplicationScenario.kt | `fa9d00ae944552e34c47236a4afce067a56719f2a391e8d9b6114d722ff684e5` |
| DevelopmentOrderClientTest.kt | `113c4031e9f0daa2ca215b45a741660b0b9c86acf989aa83672eebef595a0271` |
| DevelopmentFundingEventsTest.kt | `5e756fe593a3433af5cff5859d4765248a7845f54484b48f325c5420f476e5c0` |

### 최종 로컬 실행·검토 상태

| 구분 | 이 문서 작성 시점의 상태 |
| --- | --- |
| 구현 | 최신 통합 d0c3b54에서 작성한 미커밋 변경. 주문 계산·상태·wire 스키마는 유지했다. |
| 테스트 설명 | 위에 적은 소스와 assertion을 읽었다. 비교기 테스트 26개와 내부 변조 공급자도 설명에 연결했다. |
| 실행 검증 | 실제 Docker의 세 주문 사례와 기존 자금 준비, 도메인 100개·구조 375개·운영 검사 P01~P09 통과. Aeron/codec 192개 실행 중 190개 통과·비교기 준비 코드 2개 실패 후 해당 클래스 26개를 수정 재실행해 모두 통과했다. 기대값은 변경하지 않았다. 실패·재검사 이력은 아래에 남긴다. |
| 독립 결함 리뷰 | 작성 대화를 받지 않은 별도 에이전트가 고정 v1 전체 변경과 v2 수정분을 검토했다. 준비 오류 1건을 독립 재현한 뒤 해소를 확인했고, v2에서 관련 30개를 다시 빌드·실행해 실패·오류·skip 0, 확인된 미해결 결함 0건이다. |
| 원격 CI | PR 미게시·CI 미실행. 기존 PR CI에서 신규 Docker 사례의 실제 실행·skip 없음·보고서 업로드까지 확인해야 #107 이슈를 완료할 수 있다. |



### 독립 리뷰 대상과 전달 상태

- 기준: `d0c3b546c512223f2036c7151f2c9f514ba9b035`.
- 전체 1차 리뷰: `12b7206dce55b57736fa51e22ef637a1a6d42ebc`. 별도 checkout에서 작성 대화 없이 검토했다.
- 수정 후 리뷰: `3a71f34ff63ecc0d14ec3a9900ee85e380845e39`. 변경은 JSON 행 구성 5곳과 학습 가이드다. 기대값·비교기·클라이언트·Docker 실행기·출력 코드는 바꾸지 않았다.
- 발견/해소: JSON 객체가 Iterable이라는 이유로 테스트에서 한 행 대신 내부 값들이 추가되던 공통 원인 1건. 메시지 한 건을 `listOf(node)`로 감싸도록 수정하고 관련 30개 통과를 독립 확인했다.
- 리뷰어의 추가 확인: 실제 State의 세 사례 총 8단계와 실제 출력 변환·독립 정답 연결, 목록 연산 원인 재현의 임시 검사 2개 통과. 테스트용 소스와 XML은 리뷰 checkout의 build 근거에만 남겼다.
- 그 리뷰 후 제품·테스트·설정 소스는 그대로이며 이 문서의 결과 상태만 갱신했다. 최종 전달의 소스 해시는 위 표와 같다. 종료 경로의 장애 주입, 리더 장애 전환·복구·성능·원격 CI는 검증했다고 주장하지 않는다.

### 실행한 명령과 실제 결과

아래 명령은 `test/aeron-container-orders/107`을 체크아웃한 저장소에서 실행한다. 다른 작업 폴더라면 브랜치 확인에서 중단한다. 첫 컴파일에서는 새 assertion의 Jackson 목록 타입 추론 오류, 포맷에서는 긴 줄 오류를 고친 뒤 진행했다. 이를 제품 동작의 TDD Red로 기록하지 않는다.

```sh
cd "$(git rev-parse --show-toplevel)"
[ "$(git branch --show-current)" = "test/aeron-container-orders/107" ] || exit 1
./gradlew :app-api:test --tests '*DevelopmentFundingEventsTest' --tests '*DevelopmentOrderClientTest' --tests '*DockerOrderReplicationTest' --tests '*DockerFundingReplicationTest' --console=plain --no-daemon
./gradlew :app-api:ktlintFormat :app-api:test --tests '*trading.infrastructure.aeron.*' --tests '*trading.infrastructure.codec.*' :domain-trading:test ktlintCheck :architecture-tests:test :architecture-tests:verifyArchitectureReport --console=plain --no-daemon
./gradlew :app-api:test --tests '*OrderReplicationComparisonTest' :domain-trading:test :architecture-tests:test :architecture-tests:verifyArchitectureReport --console=plain --no-daemon
./gradlew ktlintCheck --console=plain --no-daemon
```

- 첫 실제 송수신·Docker 실행은 14개 통과했다. 이후 같은 L의 잘못된 식별자와 늦은 수집 재대조를 보강했다.
- 확장 실행 192개 중 두 실패는 테스트의 `List<JsonNode> + ObjectNode`가 메시지 한 건 대신 JSON 내부 값들을 더한 공통 준비 오류였다. `+ listOf(node)`로 메시지 단위를 명시했다. 기대하는 금액·실패 분류는 유지했다.
- 해당 26개를 다시 실행해 실패·오류·skip 0. 확장 실행의 나머지 166개는 소스가 변하지 않은 상태의 통과 근거를 재사용한다. 한 번의 192개 전체 Green 실행이라고 합쳐 쓰지 않는다.
- 도메인 100개, 구조 375개와 필수 운영 검사 P01~P09는 통과했다. 전체 ktlintCheck도 최종 소스에서 통과했다.

수정된 비교기로 실행한 Docker 근거:

| 실제 실행 ID | 사례 | 위치 변화 | 결과 |
| --- | --- | --- | --- |
| funding179255407018309e42702 | 기존 P1 자금 준비 | 352 | 성공·정상 종료·기록 보존 |
| funding1792554084051c9e3ad3f | 매수 두 건 | 352 → 768 → 1184 | 성공·정상 종료·기록 보존 |
| funding1792554101781be1665a7 | 매도 | 352 → 768 | 성공·정상 종료·기록 보존 |
| funding1792554113281414f0a44 | 부족 거절 | 352 → 768 → 1184 | 성공·정상 종료·기록 보존 |

주문 세 실행은 같은 이미지를 사용했고 첫 매수 실행에서만 이미지를 준비했다. 각 단계의 client 응답, 세 상태, 기대값과 실제 값, 제한 시간, 종료와 Archive/cluster 복사 결과는 해당 `app-api/build/reports/aeron-funding/<runId>/result.json`에 있다. 로그 바이트 위치의 증가를 승인 순번 증가로 오해하지 않는다. 부족 거절도 입력 로그 위치는 전진하지만 승인 순번은 1로 유지한다.

실행 자료는 기존 `app-api/build/reports/aeron-funding/<runId>/`를 사용한다. 단계별 client 출력, 세 관찰, 기대/실제 값, 위치, 이미지·컨테이너·상한·종료·기록 수집 결과를 읽는다. 앞선 #106 통과 기록을 #107 실행 결과로 옮기지 않는다.

다음으로 볼 판단은 **응답은 맞지만 node2 관찰 하나가 없을 때 왜 R2를 보내면 안 되는가**다. 현재 구현은 그 사례를 미확인으로 멈추고 자료를 수집한다. 동시 요청·재전송/중복 행렬·장애 전환·복구는 후속 범위에 남는다.

</details>
