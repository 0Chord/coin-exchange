# #109 · 두 주문이 동시에 와도 같은 돈을 두 번 쓰지 못하는지 확인하기

**1,000 USDT를 가진 사용자가 600 USDT 주문과 500 USDT 주문을 함께 보낸다. 먼저 처리된 하나만 승인되는지 실제 세 노드에서 검사한다.**

기존 검사는 앞 주문의 응답과 세 노드 반영을 확인한 뒤 다음 주문을 보냈다. 이번에는 주문 프로그램 두 개를 각각 연결하고, 같은 출발 신호를 준다. 돈 계산과 주문 접수 규칙은 기존 엔진을 사용한다.

| 실제로 먼저 처리된 주문 | 첫 주문을 처리한 뒤 | 두 번째 주문을 처리한 뒤 |
| --- | --- | --- |
| A: 600 USDT | A 승인. 사용 가능 400 USDT·예약 600 USDT | B는 100 USDT 부족으로 거절. 돈은 그대로 |
| B: 500 USDT | B 승인. 사용 가능 500 USDT·예약 500 USDT | A는 100 USDT 부족으로 거절. 돈은 그대로 |

어느 주문이 먼저일지는 고정하지 않는다. **합쳐서 1,100 USDT를 예약하거나, 둘 다 거절하거나, 뒤 주문이 처음 잔고 1,000을 다시 보고 승인하면 실패다.**

- 브랜치: `test/aeron-concurrent-reservations/109`
- 기준 통합 커밋: `33f4feca2d7cf02a6c9bfe595851076ae4b3100b`

현재 상태: **실행 코드와 검증 코드 분리 후 로컬 검사 통과**. 현재 실행 근거는 아래 검증 결과에, 이전 실행의 실패와 수정 이력은 문서 끝의 접힌 기록에 구분했다.

## 일반 실행과 이번 검증이 맡는 일

| 위치 | 유지하는 책임 | 이번 동시 검사에서 쓰는 방식 |
| --- | --- | --- |
| `src/main` · `DevelopmentOrderClient` | 실제 Aeron 연결·주문 송수신, 일반 개발 CLI | 일반 CLI에는 GO 파일·`ORDER_START_GATE`·`ORDER_CLIENT_ID` 처리나 READY 대기가 없다. 기존 순차 주문 실행과 결과 이벤트를 유지한다. |
| `src/main` · `DevelopmentFundingAdapter`·`DevelopmentFundingNode` | 기존 엔진 적용·응답, 최신 관찰값 한 칸, 노드 자원과 실제 업무 오류 처리 | 최신 관찰값을 유지한다. 수집 큐·용량·overflow·종료 배출 정책은 두지 않는다. |
| `src/test` · `ConcurrentOrderClient`·`DevelopmentOrderStartGate` | 독립 클라이언트의 READY·GO·진행 정보 | 일반 클라이언트와 주문 파싱을 공유하고 실제 `DevelopmentOrderClient.request()`를 호출한다. GO 대기도 연결 소유 스레드에서 일반 `pollConnection()`으로 연결을 유지한다. |
| `src/test` · `FundingVerificationNode`·`FundingVerificationCollector` | 적용별 관찰 큐·실패 보존·최종 배출 | 실제 어댑터를 감싼 래퍼가 callback 종료 뒤 관찰을 수집한다. overflow는 검증 실패로 보존하고 업무 예외로 전파하지 않는다. 검증 실행의 소유자가 실패를 읽고 노드를 정리한다. |

Docker 검사 이미지는 main 실행 클래스와 test 전용 진입점을 함께 넣는다. 일반 개발 실행 배포에는 main 클래스만 들어간다. 세 검증 노드는 `FundingVerificationNodeKt`, 동시 주문 A/B는 `ConcurrentOrderClientKt`를 실행한다. 기존 자금 준비 클라이언트와 순차 주문 클라이언트는 일반 개발 진입점을 그대로 사용한다. 검증 래퍼는 업무 계산이나 전송을 흉내 내지 않는다.

## 먼저 이 흐름만 읽으면 된다

```text
테스트 JVM: 세 노드 준비 → U1에게 1,000 USDT 준비 → 세 노드에서 확인
      │
      ├─ 주문 A 컨테이너/JVM 연결 → READY → GO 대기
      ├─ 주문 B 컨테이너/JVM 연결 → READY → GO 대기
      │
      └─ 두 READY와 독립 연결 확인 → 공통 GO 파일 한 번 공개
                      │
              ┌───────┴───────┐
              A: 600 전송     B: 500 전송
              └───────┬───────┘
                      ↓
            Aeron Cluster가 입력의 순서를 정해 복제
                      ↓
       노드 0 / 노드 1 / 노드 2가 같은 순서로 기존 엔진 호출
                      ↓
              첫 주문 승인 → 뒤 주문 부족 거절
                      ↓
테스트 JVM: 응답 두 개 + 각 노드의 첫·둘째 상태 확인 → 종료·기록 수집
```

동시에 출발해도 각 노드 안의 업무 처리는 한 건씩 진행한다. A가 먼저라면 B는 A가 600을 예약한 **뒤의 잔고 400**으로 판단한다. 세 노드는 주문을 하나씩 나눠 맡는 서버가 아니라 같은 자금·주문 상태를 각각 보관하는 사본이다.

처음 읽을 코드는 [runConcurrentInputs()](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerFundingReplicationTest.kt), [runConcurrentOrderClient()](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/ConcurrentOrderClient.kt), [compareConcurrentOrderReplication()](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/ConcurrentOrderReplicationComparison.kt) 순서다. 아래 상세는 필요한 것만 펼쳐 읽으면 된다.

<details>
<summary>어떤 JVM이 무엇을 하는가 · 준비부터 출발까지</summary>

### 실행하는 프로그램은 서로 다르다

| 프로그램 | 어디서 실행하나 | 이번 역할 |
| --- | --- | --- |
| 테스트 JVM | Gradle이 시작한 테스트 프로세스 | Docker 실행, READY 수집, GO 공개, 응답·상태 비교, 정리 |
| 자금 준비 클라이언트 | 별도 개발 클라이언트 컨테이너 | P1으로 U1에게 USDT 1,000을 준비하고 종료 |
| 주문 클라이언트 A | `client-a` 컨테이너의 테스트 전용 `ConcurrentOrderClientKt` JVM | 실제 개발 클라이언트로 RA/OA: 600 USDT가 필요한 주문 한 건 전송 |
| 주문 클라이언트 B | `client-b` 컨테이너의 테스트 전용 `ConcurrentOrderClientKt` JVM | 실제 개발 클라이언트로 RB/OB: 500 USDT가 필요한 주문 한 건 전송 |
| 거래 노드 0·1·2 | 각각 별도 컨테이너의 테스트 전용 `FundingVerificationNodeKt` JVM | 실제 개발 노드의 MediaDriver·Archive·엔진으로 같은 입력 순서 처리, 테스트 수집기가 적용별 관찰 보존 |

테스트 JVM은 주문을 승인하지 않는다. A/B는 주문을 전송하는 프로그램이고, 노드의 `DevelopmentFundingAdapter`가 받은 명령을 기존 `OrderAdmissionState.admit()`에 전달한다. 이번 실행에는 HTTP 요청을 받는 app-api 서버나 사용자 화면이 없다.

### 두 클라이언트가 준비됐다는 것을 먼저 확인한다

1. `DockerFundingVerification.run()`이 같은 이미지로 세 노드를 준비한다. 멤버 설정·역할·기록 볼륨을 확인한다.
2. P1의 응답과 세 노드의 자금 준비 관찰을 같은 로그 위치 L0에서 확인한다. 아직 주문은 없다.
3. `runConcurrentInputs()`가 A/B 프로세스를 모두 시작한다. 프로세스가 끝날 때까지 기다리는 작업은 뒤로 미룬다.
4. A/B는 각자의 MediaDriver와 `AeronCluster` 연결을 만든 후 READY를 출력한다. READY에는 실행·요청·세션 ID, JVM PID, 응답 주소, Driver 경로가 담긴다.
5. 테스트는 두 READY와 실제 컨테이너 정보를 대조한다. 같은 세션이나 같은 응답 주소를 공유하면 출발시키지 않는다.
6. `ConcurrentClientBarrier.release()`가 이번 실행 ID를 쓴 임시 파일을 `go`로 원자적으로 옮긴다. 두 클라이언트는 같은 읽기 전용 제어 폴더에서 이 파일을 본다.

컨테이너 안의 JVM PID는 둘 다 1일 수 있다. 서로 다른 컨테이너인지와 서로 다른 세션·주소를 확인하며, PID 숫자가 다르다는 조건을 두지 않는다.

### GO는 ‘동시에 계산하라’는 신호가 아니다

A/B가 같은 출발 조건을 기다린다는 뜻이다. 실제 CPU 스케줄링 때문에 A의 응답이 B의 전송 시작보다 먼저 올 수도 있다. 중요한 것은 **B를 출발시키는 코드가 A의 응답을 기다리지 않는다는 점**이다. 서로 다른 JVM의 `nanoTime()`을 비교해 먼저 처리된 주문을 정하지 않는다.

테스트 전용 A/B 진입점은 GO를 기다리는 동안 연결을 소유한 자기 스레드에서 `DevelopmentOrderClient.pollConnection()`을 호출해 `pollEgress()`와 keepalive를 계속 처리한다. 연결을 만든 뒤 아무것도 하지 않고 기다려 세션이 끊기는 상황을 막기 위한 것이다. 이전 실행의 GO, 준비 전에 있던 GO, 잘못된 실행 ID는 출발 근거가 될 수 없다.

코드: [Compose의 client-a/client-b](../app-api/src/test/docker/aeron-funding/compose.yml), [ConcurrentClientBarrier](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/ConcurrentClientBarrier.kt), [DevelopmentOrderStartGate.await()](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentOrderStartGate.kt).

</details>

<details>
<summary>두 주문이 엔진에서는 한 순서가 되는 과정 · 응답과 세 사본 확인</summary>

### 기존 접수 계산을 그대로 호출한다

A는 가격 100 USDT·수량 6 BTC, B는 가격 100 USDT·수량 5 BTC의 LIMIT/GTC 매수다. 수수료는 0으로 고정해 필요한 돈이 각각 600·500 USDT다. 요청 ID는 RA/RB, 주문 ID는 OA/OB로 서로 다르다.

A/B의 `send()`는 기존 Protobuf 변환 후 Aeron 연결에 바이트를 건넨다. 양수 `offer()` 결과는 전송 경로가 받아들였다는 뜻이다. 자금 예약이 끝났다는 응답은 아니다. 한 번 수용된 업무 요청을 결과가 늦다는 이유로 자동 재전송하지 않는다.

클러스터가 복제 로그의 순서를 정하면 각 노드의 서비스 스레드가 `onSessionMessage()`를 차례대로 호출한다. `DevelopmentFundingAdapter`는 명령을 복원하고 기존 엔진에 넘긴다. 이번 변경에서 합의 알고리즘이나 접수 계산을 새로 만들지 않았다.

예를 들어 각 노드가 A를 먼저 처리한다면 다음과 같다.

| 단계 | 엔진이 판단할 때 보이는 상태 | 처리 결과 |
| --- | --- | --- |
| P1, L0 | 사용 가능 1,000·예약 0 | 시작 자금과 OPENING 원장 준비 |
| RA, L1 | A에 600이 필요하고 1,000 사용 가능 | 600 예약, OA 대기, RESERVE 원장, 최초 승인 결과 저장 |
| RB, L2 | B에 500이 필요한데 이제 400 사용 가능 | 100 부족으로 거절. 돈·OA·원장은 그대로, RB 최초 거절 기록 추가 |

L0/L1/L2는 로그의 적용 위치다. 연속된 정수일 필요가 없다. 주문 승인 순번은 A만 승인됐으므로 1이다. B 거절도 로그에는 적용됐지만 새 승인 순번을 만들지 않는다.

B가 먼저면 같은 규칙으로 B가 500을 예약하고 A가 거절된다. B의 입력에 담긴 업무 시각이 A보다 늦더라도 처리 순서는 실제 로그 위치로 판단한다.

### 응답 두 개를 받는 검사와 세 노드가 같은 상태인지 보는 검사는 다르다

각 클라이언트는 자기 요청의 사용자·요청·마켓·주문 ID와 일치하는 결과를 기다린다. 테스트는 A 응답과 B 응답을 각각 확인한다. 승인 한 건·거절 한 건이라는 개수만 확인하지 않는다.

그다음 세 노드의 관찰에서 RA와 RB의 고유 적용 위치를 찾는다. 낮은 위치의 요청을 먼저 처리된 것으로 정하고, 미리 작성한 A 선행 또는 B 선행 정답과 비교한다. 승인 응답을 보고 승자를 정한 뒤 그 응답에 맞춰 정답을 만들지 않는다.

각 위치에서 확인하는 것은 다음과 같다.

- 첫 위치: 올바른 잔고·예약액, 대기 주문 하나, 승인 순번 1, RESERVE 하나, 최초 승인 기록.
- 둘째 위치: 앞의 돈·주문·승인 후보 유지, 뒤 요청의 부족 수치와 최초 거절 기록 추가.
- 양쪽 모두: 주문 원본, 마켓·수수료 정책, 원장 ID·시각·분개 내용이 고정 기대값과 일치.

세 노드가 모두 똑같이 틀린 1,100 예약을 보여도 실패한다. 첫 상태가 누락되고 마지막 상태만 맞아도 실패한다. 종료 후 수집한 자료를 고정한 두 위치로 다시 비교해 뒤늦게 발견한 모순을 버리지 않는다.

코드: [ConcurrentOrderReplicationScenario.orderedSteps()](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/ConcurrentOrderReplicationScenario.kt), [compareConcurrentOrderReplication()](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/ConcurrentOrderReplicationComparison.kt), [기존 compareOrderReplication()](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparison.kt).

</details>

<details>
<summary>관찰이 덮이지 않게 한 이유 · 실패하면 돈과 프로세스는 어떻게 되나</summary>

### 마지막 상태만 남기면 첫 주문 검사를 놓친다

일반 개발 노드는 기존처럼 `observation` 한 칸에 최신 값을 저장한다. 출력 전에 A 다음 B가 처리되면 A의 상태를 놓칠 수 있으므로, 이번 Docker 검사만 별도 테스트 수집기를 사용한다. 요청 사이에 기다림을 넣어 문제를 피하지 않는다.

`FundingVerificationCollector.decorate()`는 실제 `DevelopmentFundingAdapter`에 모든 서비스 동작을 위임한다. 서비스 스레드에서 한 `onSessionMessage()`가 끝나면 새로 완성된 불변 관찰을 용량 16의 큐에 한 번 넣는다. 응답 재시도가 있으면 그 callback의 최종 응답 진단을 수집하며, 같은 적용의 PENDING·OFFERED를 별도 주문으로 늘리지 않는다. `FundingVerificationNode`의 테스트 main 한 소비자가 큐를 꺼내 JSON으로 출력한다. 업무 callback은 파일을 쓰거나 큐가 빌 때까지 기다리지 않는다.

여기서 관찰에 담은 snapshot은 **검사용 상태 복사본**이다. 디스크에 복구용 스냅샷을 저장하는 기능을 추가한 것은 아니다. 일반 개발 어댑터의 최신 관찰값도 그대로 유지한다.

큐가 가득 차면 `verificationFailure`에 검증 실패를 남긴다. **overflow를 업무 서비스로 던지거나 `onFatal`을 호출하지 않는다.** 이미 적용한 자금과 현재 응답은 유지되고, 테스트 소유자가 정리하기 전에는 다음 정상 업무도 처리할 수 있다. 배출 뒤 후속 정상 관찰이 들어와도 실패 표시는 지우지 않는다. 검증 main이 이 실패를 읽으면 검사를 실패시키고, 자신이 소유한 노드를 닫아 생산자 종료를 확인한 뒤 남은 관찰을 배출한다.

실제 코어 오류는 기존 서비스의 치명 오류 경로와 원래 예외를 유지한다. 수집 래퍼는 `finally`에서 그 오류 관찰을 별도 슬롯에 보존하므로 큐가 가득 찼어도 원인을 잃지 않는다. 잘못된 메시지 관찰도 다음 정상 관찰과 함께 보존한다.

코드: [FundingVerificationCollector.decorate()/drainObservations()와 테스트 노드 main](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/FundingVerificationNode.kt), [일반 어댑터의 최신 observation](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingAdapter.kt), [일반 노드 실행](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingNode.kt).

### 실패를 처리한 척하고 통과시키지 않는다

| 상황 | 테스트가 남기는 결과 |
| --- | --- |
| A/B 연결 준비 실패, 이전 GO, 주소·세션 공유 | SETUP. 출발하지 않고 시작한 자원을 정리 |
| GO 뒤 응답·관찰 누락 또는 기한 초과 | UNVERIFIABLE. 실제 돈이 반영됐을 수 있으므로 미접수나 취소로 단정하지 않음 |
| 테스트 관찰 큐 overflow | 검증 실패를 보존하고 테스트 실행 소유자가 정리. 업무 거절·업무 치명 오류로 바꾸지 않음 |
| 두 승인, 틀린 잔고·예약·원장·응답 대응 | MISMATCH. 어느 요청·노드·위치·필드가 다른지 기록 |
| 추가 로그 수집 실패 | COLLECTION 추가. 앞서 확인한 MISMATCH는 유지 |
| 클라이언트·노드 종료 또는 기록 보존 실패 | CLEANUP/COLLECTION 추가. 전체 실행 실패 |

MediaDriver가 종료한 클라이언트의 주소를 다시 찾다가 내는 `Category.WARN`은 Aeron 오류 로그에 남기되 노드를 중단하지 않는다. Driver의 ERROR/FATAL/일반 예외, Archive·Consensus·업무 오류는 계속 중단 대상으로 취급한다. 경고 문구나 특정 주소를 골라 무시하는 방식은 아니다.

잔고 부족은 정상 업무 거절이다. 개발 노드 장애나 검증 자료 누락과 같은 종류로 취급하지 않는다.

### 시작한 것들을 모두 회수한다

`VerificationCommands.start()`는 프로세스를 시작하기 전에 고유 로그 경로와 핸들을 등록한다. 두 프로세스를 먼저 시작해도 같은 출력 파일을 덮지 않으며, B 기동이 실패해도 먼저 띄운 A를 정리할 수 있다.

Docker 명령 프로세스가 끝났다고 컨테이너 JVM도 끝났다고 가정하지 않는다. `preserveAndClose()`는 실제 클라이언트 컨테이너를 조회해 종료를 확인하고, 검증 노드 종료·Archive와 cluster 디렉터리 복사를 진행한다. 검증 노드 안의 테스트 main이 생산자를 닫은 뒤 남은 관찰을 배출하고, 종료 hook은 그 완료를 확인한다. 일반 개발 main에는 이 큐 배출 계약이 없다. 마지막에는 컨테이너와 네트워크를 정리하되 기록 볼륨은 보존한다.

연결 대기·GO 대기·응답·관찰·종료에는 각각 상한이 있다. 두 READY 수집은 90초, GO 이후 두 응답 회수는 함께 20초, 두 위치의 관찰은 15초다. 각 클라이언트의 실제 GO 대기 상한도 READY에서 읽어 보고서에 남긴다(현재 60초). 단계별 실제 소요 시간은 실패해도 남긴다. A의 최종 로그 파일을 저장하지 못해도 COLLECTION 오류를 기록하고 B 로그·노드 종료·정리·보고서 작성을 계속한다. 출발 뒤 문제가 생겼다고 새 요청 ID를 만들어 자동으로 다시 보내지는 않는다.

코드: [VerificationCommands](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/VerificationCommands.kt), [DockerFundingVerification.preserveAndClose()](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerFundingReplicationTest.kt).

</details>

<details>
<summary>테스트를 자연어로 읽기 · 실제 Docker와 작은 검사를 구분해서</summary>

아래는 테스트 원문과 검증문을 읽어 정리한 내용이다. 실제 실행 결과는 아래 검증 기록에 별도로 정리했다. ‘준비 → 실행 → 확인’은 각각 Given·When·Then에 해당한다.

### 실제 Docker 실행

[DockerOrderReplicationTest](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerOrderReplicationTest.kt)의 `기존 접수와 재전송 충돌 사례를 각각 빈 세 노드 클러스터에서 대조한다`에 동시 사례를 추가했다.

- **준비:** 같은 이미지의 빈 세 노드와 P1을 준비하고 L0를 확인한다.
- **실행:** A/B를 독립 컨테이너에서 연결하고 두 READY 뒤 GO를 공개한다. 둘 다 시작한 다음 응답을 회수한다.
- **확인:** 두 응답과 세 노드의 L1/L2를 독립 정답에 대조한다. 클라이언트·노드 종료와 기록 수집까지 실패가 없어야 한다.

기존 순차 매수·매도·부족·승인 재전송·거절 재전송·정책 교정 사례도 계속 실행한다. [DockerFundingReplicationTest](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerFundingReplicationTest.kt)의 `한 번 보낸 P1의 자금과 OPENING이 세 독립 컨테이너의 같은 위치에 복제된다`는 기존 준비 경로 회귀 검사다.

### 앞 적용의 관찰을 잃지 않는가

[FundingVerificationCollectorTest](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/FundingVerificationCollectorTest.kt)는 테스트 수집기로 감싼 실제 어댑터·도메인 상태와 통제한 Cluster·응답 API를 사용한다. 네트워크 복제 자체는 위 Docker 실행이 확인한다. 기존 [DevelopmentFundingAdapterTest](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingAdapterTest.kt)는 일반 어댑터의 업무 처리와 최신 관찰값을 계속 검사한다.

| 테스트 이름 | 준비 → 실행 → 실제 확인 |
| --- | --- |
| 소비가 늦어도 두 적용을 보존하고 앞 상태는 바뀌지 않는다 | U1/U2의 자금 준비를 소비 없이 연속 적용 → 한 번 배출 → 두 위치가 순서대로 남고, 첫 상태에는 U1만·둘째에는 U1/U2가 있음 |
| 응답 재시도가 끝난 한 callback의 최종 관찰만 한 번 수집한다 | 응답 전송을 BACK_PRESSURED→ADMIN_ACTION→성공으로 통제 → 한 명령 적용 → 관찰 한 건의 상태는 OFFERED, 시도는 3회이며 실제 어댑터의 최신 관찰과 같은 객체 |
| 잘못된 메시지도 다음 정상 적용에 덮이지 않는다 | 잘린 바이트 다음 정상 자금 준비 → 배출 → 잘못된 메시지 관찰과 정상 상태가 각각 남음 |
| 버퍼 초과는 검증만 실패시키고 현재 응답과 다음 업무 처리를 유지한다 | 용량 1에 두 자금 준비 → overflow 확인·배출 뒤 세 번째 준비 → 세 응답과 세 지급 모두 정상, 서비스 시작 상태·코어 비차단·onFatal 미호출, 최초 검증 실패는 계속 남음 |
| 버퍼가 차 있어도 실제 코어 종료 오류와 원래 예외를 보존한다 | 첫 관찰을 채운 뒤 잘못된 원장 ID 명령 → 실제 서비스 오류 → 첫 상태와 오류 관찰을 모두 배출, 원래 예외 원인·업무 종료 유지, 둘째 지급과 응답 없음 |
| 관찰 용량은 메모리 할당 전에 제한한다 | 음수·0·2의 거듭제곱이 아닌 값·상한 초과 용량 → 테스트 수집기 생성 → 잘못된 설정 거절 |

[DevelopmentFundingNodeTest](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingNodeTest.kt)는 같은 수집 래퍼를 실제 단일 노드에 연결해 다음을 확인한다.

| 테스트 이름 | 준비 → 실행 → 실제 확인 |
| --- | --- |
| 실제 노드를 닫아도 아직 출력하지 않은 두 적용이 남는다 | 실제 노드에 테스트 수집기를 연결하고 자금 준비 두 건 전송 → 노드 종료 뒤 배출 → 두 적용의 순서·각 상태 보존, 다음 배출은 빈 목록. 일반 READY에는 수집 용량이 없고 테스트 READY에만 16이 있음 |
| 수집 버퍼 초과 뒤에도 실제 노드는 다음 자금 준비와 정상 응답을 유지한다 | 용량 1의 테스트 수집기로 실제 노드의 두 적용 후 overflow 확인 → 한 번 배출하고 세 번째 준비 전송 → 세 준비와 정상 응답, 최초 검증 실패 유지, 노드 오류 없음·노드가 계속 열려 있음 |

같은 Node 테스트에 다음 오류 분류 사례도 추가했다.

| 테스트 이름 | 준비 → 실행 → 실제 확인 |
| --- | --- |
| Driver 경고는 실제 오류 기록에 남기고 다음 자금 준비를 처리한다 | 실제 노드의 Driver에 WARN 전달 → 실제 자금 준비 전송 → 경고 로그가 남고 1,000 USDT 준비 성공·노드 유지 |
| Driver의 ERROR FATAL 일반 예외는 실제 노드를 중단한다 | 실제 노드를 각각 시작 → 세 오류 종류를 전달 → 노드가 닫히고 원래 오류 보존 |

### 연결 후에만 출발하고, 기다리는 동안 연결을 유지하는가

[DevelopmentOrderStartGateTest](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentOrderStartGateTest.kt)의 파일 검사는 실제 임시 파일을 사용한다. 마지막 API 호출 검사는 전송 API 대역을 사용한다.

| 테스트 이름 | 준비 → 실행 → 실제 확인 |
| --- | --- |
| 연결 준비 후 같은 실행의 GO를 확인해야 출발하고 대기 중에도 연결을 처리한다 | GO 없음 → READY 뒤 poll 세 번 중 현재 실행 GO 생성 → READY·poll·GO·전송 시작 순서 |
| 준비 전에 이미 있던 GO는 이번 실행의 값이어도 출발 근거로 쓰지 않는다 | 이전/현재 실행 값의 파일을 미리 둠 → 대기 시작 → READY와 전송 없이 오류 |
| 준비 후에도 다른 실행의 GO나 손상된 신호로 주문을 보내지 않는다 | READY 뒤 이전 ID·빈 파일·추가 내용·과도한 길이 입력 → GO 확인 → READY만 남고 전송하지 않음 |
| GO가 없으면 유한 대기 후 끝나고 출발하지 않는다 | GO 없이 제어 시각 사용 → 대기 기한 진행 → 기한 오류와 poll만 남고 출발 없음 |
| 대기 중 연결 처리가 실패하면 GO 확인과 전송을 하지 않는다 | poll 오류를 주입 → 대기 → 원래 오류를 유지하고 GO·전송 없음 |
| 실제 클라이언트 대기 루프는 같은 소유 스레드에서 egress와 keepalive를 진행하고 offer는 하지 않는다 | 전송 API 대역과 잠시 늦은 GO → 테스트 gate가 실제 클라이언트의 일반 pollConnection 호출 → 같은 스레드의 poll·keepalive 호출, offer 호출 없음 |

[DevelopmentOrderClientTest](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentOrderClientTest.kt)에 있는 일반 실행과 검증 실행의 경계 사례:

| 테스트 이름 | 준비 → 실행 → 실제 확인 |
| --- | --- |
| 일반 주문 CLI는 테스트 출발 환경변수를 무시하고 로컬과 환경 실행에서 바로 접수한다 | 실제 노드에 자금 준비, 로컬 실행에는 낡은 GO와 두 환경변수·환경 실행에는 잘못된 GO 경로만 또는 빈 client ID만 제공 → 일반 CLI 실행 → 주문 세 건 정상 접수, 테스트 READY·식별 필드 없음, 낡은 GO 파일 그대로 |
| 테스트 전용 공통 출발 주문은 실제 연결 READY 뒤 GO를 받고 한 번 전송한 결과와 연결 정보를 남긴다 | 실제 단일 노드에 자금 준비, READY 때 아직 주문이 없는지 확인하고 GO 작성 → 테스트 전용 진입 함수 실행 → 다섯 단계 이벤트·연결 정보·승인 한 건·Driver 종료 |
| 남아 있던 GO 때문에 준비가 실패하면 미확인 전송으로 표시하지 않고 실제 offer 없이 Driver를 닫는다 | 연결 API 대역과 미리 있는 GO → 테스트 전용 진입 함수 실행 → SETUP_FAILED만 출력, offer 없음, 자기 Driver 종료 |

### 테스트 제어자가 출발 조건과 프로세스를 제대로 관리하는가

[ConcurrentClientBarrierTest](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/ConcurrentClientBarrierTest.kt)는 수집 JSON 예제와 실제 임시 파일로 제어 로직을 검사한다.

| 테스트 이름 | 준비 → 실행 → 실제 확인 |
| --- | --- |
| 두 연결 준비 뒤 하나의 출발 파일을 공개한다 | 올바른 A/B READY → release → 현재 runId의 GO만 공개되고 임시 파일 없음 |
| 한 연결 누락이나 이전 실행과 조기 전송은 GO를 만들지 않는다 | 한 READY 없음·이전 실행·GO 전 SEND·빈 자료 → release → 문제를 보고하고 GO 없음 |
| 이전 GO를 현재 runId로 덮어쓰지 않는다 | 이전 GO 존재 → release → 문제를 보고하고 이전 파일 그대로 |
| 같은 세션이나 egress와 driver를 공유하면 준비 실패다 | 세션·응답 주소·Driver·광고 주소·실행 루트를 각각 공유하도록 변경 → 준비 비교 → 공유 사실 검출 |
| 각 클라이언트의 인과 순서만 검사하고 뒤바뀐 단계와 결과 미확인은 실패한다 | 정상 단계, SEND/GO 역전, UNCONFIRMED 추가 예제 → 진행 비교 → 정상만 허용 |

[VerificationCommandsTest](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/VerificationCommandsTest.kt)는 실제 작은 셸 프로세스를 사용한다. Docker 컨테이너 종료까지 증명하는 테스트는 아니다.

| 테스트 이름 | 준비 → 실행 → 실제 확인 |
| --- | --- |
| 두 프로세스를 먼저 시작해도 각 출력이 다른 파일에 보존된다 | A/B 출력 프로그램 → 둘 다 시작한 뒤 기다림 → 각 표준 출력·오류가 다른 파일에 정확히 남음 |
| 두 번째 기동 실패 뒤 이미 실행한 첫 프로세스도 종료한다 | 첫 프로세스 실행, 둘째 실행 파일 없음 → 기동·정리 → 둘째 실패도 등록되고 첫 프로세스가 더 이상 실행되지 않음 |
| 기다림 상한 초과는 실패이며 CLI 프로세스를 남기지 않는다 | 오래 기다리는 프로세스 → 짧은 await 상한 → timeout 실패와 실제 프로세스 종료 |

[DockerVerificationCleanupTest](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerVerificationCleanupTest.kt)는 실제 파일 쓰기 실패를 주입한다. 임의의 미존재 이미지로 준비를 종료하여 새 컨테이너는 만들지 않고, 전체 종료 경로를 검사한다.

| 테스트 이름 | 준비 → 실행 → 실제 확인 |
| --- | --- |
| A 최종 출력 파일을 쓸 수 없어도 B 수집과 노드 종료 정리 보고서 작성을 이어간다 | 기존 불일치와 A 출력 경로 디렉터리 준비 → runner 실행·종료 경로 → B 로그 수집, 노드 stop, compose down, 보고서 생성 시도 및 기존 불일치·수집 실패 함께 보존 |
| 동시 단계의 상한과 확인한 GO 대기를 기록하고 실패한 단계의 소요 시간도 남긴다 | A READY의 GO 60초만 관찰 → 시간 측정 중 오류·보고서 작성 → 90/20/15초 상한, A GO 60초·B 미확인, 실패 단계 소요 시간 보존 |

### 비교기가 잘못된 결과를 통과시키지 않는가

[ConcurrentOrderReplicationComparisonTest](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/ConcurrentOrderReplicationComparisonTest.kt)는 미리 작성한 A/B 정답과 일부러 손상한 JSON 관찰을 사용한다. **손상 예제를 실패로 판정하면 이 테스트는 통과한다.** 실제 네트워크나 엔진을 다시 실행하는 검사는 아니다.

| 테스트 이름 | 준비 → 실행 → 실제 확인 |
| --- | --- |
| A와 B 어느 쪽이 먼저여도 실제 로그 순서에 맞는 한 승인과 한 거절을 확인한다 | A 선행/B 선행 고정 정답 → 비교 → 각각 맞는 순서와 두 위치 선택 |
| 응답 수집 순서와 업무 시각은 실제 적용 순서를 바꾸지 않는다 | B 선행 자료를 역순 수집 → 비교 → 비연속 로그 위치에 따라 여전히 B→A |
| 노드 둘의 일치나 최종 상태만으로 누락된 첫 적용을 대신할 수 없다 | 첫 적용의 한 사본 누락·최종 상태만 존재·빈 자료 → 비교 → 검증 불가 |
| 과거 실행과 준비 위치와 서로 다른 사본 위치는 이번 두 적용을 증명하지 못한다 | 한 사본의 실행 ID·위치를 잘못 바꿈 → 비교 → 이번 동일 위치 관찰 누락 검출 |
| 두 요청이 같은 위치이거나 한 요청에 새 위치가 더 있으면 고정 뒤에도 실패한다 | 같은 L 또는 추가 새 L → 위치 고정 전후 비교 → 두 요청의 고유 위치 조건 위반 검출 |
| 잘못된 고정 위치와 숫자가 아닌 위치는 검증 불가다 | 다른 고정 위치·한 위치만 제공·문자열 위치 → 비교 → 검증 불가 |
| 같은 위치의 진단 갱신은 허용하고 뒤늦은 업무 모순은 버리지 않는다 | 같은 L의 응답 진단만 변경, 이후 틀린 hold와 수집 오류 추가 → 비교 → 진단 차이는 허용하고 돈 불일치와 수집 오류 모두 유지 |
| 정상 행 뒤에 같은 위치의 다른 요청을 붙여도 숨길 수 없다 | 정상 관찰 뒤 요청 ID만 바꾼 행 추가 → 고정 위치 비교 → 요청 불일치 검출 |
| 각 클라이언트의 응답과 전송 수용이 하나씩 필요하며 서로 바꾸면 실패한다 | A/B 응답 교환·응답 누락·수용/응답 중복·UNCONFIRMED 추가 → 비교 → 대응 불일치 또는 검증 불가 |
| 세 노드가 모두 같은 잘못된 돈을 보여도 독립 정답에 어긋나면 실패한다 | 세 사본 모두 예약 1,100으로 변조 → 비교 → 모든 사본의 금액 불일치 |
| 두 승인이나 두 거절을 실제 적용 순서로부터 기대한 결과와 대조한다 | 앞 결과를 거절 또는 뒤 결과를 승인으로 변경 → 비교 → 결과 불일치 |
| 최종 잔고가 맞아도 거절 이력 누락과 추가 주문 원장을 허용하지 않는다 | 돈은 유지하고 이력 삭제·대기 주문/원장 추가·승인 순번 2로 변경 → 비교 → 목록 개수·순번 불일치 |
| 후보와 원본 정책 원장 및 부족 수치의 변조를 모두 확인한다 | 최초 후보·예약 상태/금액·주문/마켓/정책·원장 ID/시각/분개·부족 수치를 한 항목씩 변경 → 비교 → 해당 필드 불일치 |
| 버퍼 초과와 실패 행을 정상 적용 행으로 가리지 않는다 | 정상 자료 뒤 observation overflow 오류 추가 → 비교 → 검증 불가와 원인 보존 |
| 식별자가 없는 잘못된 메시지 관찰도 뒤의 정상 주문에 가려지지 않는다 | 식별자 없는 malformed 관찰 뒤 정상 주문 또는 malformed만 존재 → 비교 → 잘못된 메시지 원인 보존 |
| 적용 위치를 정하지 못해도 코어 오류의 원인을 함께 남긴다 | 코어 오류 행만 있음 → 비교 → 위치 미확인과 코어 오류를 함께 보고 |
| 이전 실행의 잘못된 메시지와 오류 행은 이번 실행에 섞지 않는다 | 정상 이번 실행 자료 앞에 이전 실행 오류 추가 → 비교 → 이전 오류를 이번 실패로 오인하지 않음 |

</details>

## 실제로 확인한 결과

2026-10-22(KST)에 분리한 코드로 다시 실행했다. 이번에는 **B가 먼저 승인되고 A가 거절됐다.** 세 노드 모두 P1 위치 `352` → B 위치 `960` → A 위치 `1152`를 확인했다. 최종 사용 가능 금액은 **500 USDT**, 예약은 **500 USDT**다. 대기 주문·예약·RESERVE는 각각 한 건, 최초 요청 기록은 두 건이며 A의 부족액은 100 USDT다.

| 확인 범위 | 분리 후 결과 |
| --- | --- |
| app-api 관련 검사 | 중복 실행을 제외한 210건 통과, 실패·오류·생략 0. Docker JUnit 2건 포함 |
| 실제 Docker | 자금 준비·기존 주문/재전송·동시 주문 총 8개 사례 통과. 모두 종료·기록 수집 성공 |
| domain-trading | 변경 없는 코드의 유효한 100건 성공 결과 재사용(`UP-TO-DATE`) |
| 구조 검사 | 375건 통과, 필수 운영 검사 실행 확인 통과 |
| 공통 Kotlin 린트 | `ktlintCheck` 통과 |
| 배포 경계 | 일반 JAR·Boot JAR·main 클래스·일반 개발 배포에 gate와 검증 수집기 없음. 테스트 배포에는 존재 |

첫 검사 164건과 둘째 검사 63건 중 비교기 17건이 겹쳐, 고유 검사는 210건이다. Docker의 8개 사례는 JUnit 메서드 2개에서 실행한다. 이 숫자를 8개의 별도 JUnit 테스트로 더하지 않는다. 모든 사례가 현재 코드로 통과한 것이며, 전체 app-api 테스트를 실행했다는 뜻은 아니다.

작성 대화를 받지 않은 독립 리뷰에서 추가 확정 결함은 없었다. 별도 checkout의 수집기·gate·비교기 등 39건도 실패·오류·생략 없이 통과했다. 독립 리뷰에서는 실제 Docker·Boot JAR를 다시 실행하지 않았으며, 위 결과는 구현 checkout에서 확인한 근거다. 실제 검사한 `app-api` 파일은 격리 리뷰 snapshot `8e080bfb81805204454580f87ef8fb282e66e7c8`과 바이트 단위로 같다. 통합 기준과 달라진 app-api 파일 24개의 경로·내용 SHA-256은 `22f8ade129bfd0e4122118b77e07ebb247aac569d07263ac446165f27af41e08`이다.

아래는 실제 실행한 검사다. 첫 줄은 이 브랜치를 받은 저장소의 절대 경로로 바꾼다.

```sh
cd /absolute/path/to/exchange-core
./gradlew :app-api:ktlintFormat :app-api:test --tests '*Development*' --tests '*FundingVerification*' --tests '*FundingReplication*' --tests '*ConcurrentOrder*' --tests '*DockerFundingReplicationTest' --console=plain
./gradlew :app-api:test --tests '*ConcurrentClientBarrierTest' --tests '*VerificationCommandsTest' --tests '*DockerVerificationCleanupTest' --tests '*OrderReplicationComparisonTest' --tests '*DockerOrderReplicationTest' :domain-trading:test ktlintCheck :architecture-tests:test :architecture-tests:verifyArchitectureReport :app-api:jar :app-api:bootJar --console=plain
```

Docker 보고서는 `app-api/build/reports/aeron-funding/<runId>/result.json`에 있다. 이번 동시 사례의 runId는 `funding1792596369584e6d5ea18`이다. 일반 배포는 `stageDevelopmentFundingRuntime`, 검증 배포는 `stageFundingVerificationRuntime`으로 분리했다.

이번 범위는 한 사용자·한 BTC-USDT 마켓의 동시 두 주문 접수다. 체결·정산, 여러 마켓, 장애 전환, 복구용 스냅샷, Kafka·DB 연결, 성능 목표를 입증하지 않는다. 전체 저장소 테스트와 원격 PR CI의 통과를 뜻하지 않으며, 기존 CI의 일반 테스트 경로에 포함되도록 연결했다. 이슈의 최종 완료 판단에는 이번 변경에 대한 원격 CI 실행과 보고서 업로드 확인이 남아 있다.

<details>
<summary>분리 전 2026-10-21 검증 기록 · 현재 변경의 결과가 아님</summary>

분리 전 2026-10-21의 당시 최종 실행에서는 **A가 먼저 승인되고 B가 거절됐다.** 세 노드에서 자금 준비 위치 `352`, A의 위치 `960`, B의 위치 `1152`가 같았다. 최종 돈은 사용 가능 **400 USDT**, 예약 **600 USDT**였고, 대기 주문·예약·RESERVE 원장은 각각 한 건이었다. A/B 최초 요청 기록은 두 건이고 승인 순번은 1이다. B의 부족액은 100 USDT다.

| 확인 범위 | 실제 결과 |
| --- | --- |
| 관련 단위·실제 단일 노드 검사 | 147건 통과, 실패·오류·생략 0 |
| 실제 Docker 실행 | 기존 7개 + 새 동시 주문 1개, 총 8개 모두 통과. JUnit 메서드로는 2건 |
| domain-trading | 100건 통과, 실패·오류·생략 0 |
| 구조 검사 | 375건 통과, 필수 운영 검사 실행 확인 통과 |
| 공통 Kotlin 린트 | `ktlintCheck` 통과 |
| 독립 재리뷰 | 앞선 P2·P3 해소 확인. 검토한 범위에서 추가 확정 결함 없음 |

<details>
<summary>실행 근거·수정한 실패·독립 리뷰 범위</summary>

아래 내용은 분리 전 검증 기록이다. 당시 기준 통합 커밋은 `33f4feca2d7cf02a6c9bfe595851076ae4b3100b`이다. 변경된 `app-api` 파일 20개는 독립 리뷰에 제공한 격리 복사본 및 실제 검사한 코드와 바이트 단위로 동일함을 게시 전에 확인했다. 이 문서 끝의 소스 해시는 해당 파일 경로와 내용을 식별한다. 당시 리뷰 뒤 코드가 같았다는 확인이며, 이후 이루어진 이번 main/test 분리는 이 기록의 검증 대상에 포함되지 않는다.

Docker 보고서는 실행한 저장소의 `app-api/build/reports/aeron-funding/<runId>/result.json`에 생성된다. PR CI에서는 `test-reports` 산출물에 포함된다. 각 client의 READY·GO·전송·응답과 세 노드의 각 적용 위치, 최종 종료 상태, 기록 파일을 함께 보존했다. 단계 소요 시간은 실행 감독 근거이며, 주문 지연이나 처리량의 성능 측정값이 아니다.

독립 리뷰는 작성 대화를 전달하지 않은 에이전트가 별도 checkout에서 진행했다. 최초 전체 변경 검토 후 다음 두 지적을 수정하고 새 snapshot으로 재검토했다.

- **P2:** A의 최종 출력 파일 저장 실패가 B 수집·노드 종료·정리까지 끊었다. 각 client 수집 실패를 따로 기록하도록 고쳤다. 동일한 외부 파일 오류 재현과 회귀 테스트에서 뒤의 정리·최종 보고서가 계속되고, 앞서 확인한 MISMATCH도 유지됨을 확인했다.
- **P3:** 동시 단계의 실제 상한이 보고서에 빠졌다. 실행과 같은 상수를 연결하고, 관찰한 client별 GO 상한 및 실패 단계의 소요 시간도 기록했다.

실제 Docker 회귀 검사 중 종료한 client의 DNS 재조회 WARN을 치명 오류로 처리하는 기존 문제도 발견했다. MediaDriver의 공식 WARN 분류만 계속 처리하도록 고쳤고, 경고 자체는 Aeron 오류 로그에 남는다. 실제 WARN 주입에서 실패하는 Red를 확인한 뒤 Node 테스트 14건이 통과했다. ERROR/FATAL/일반 예외는 계속 노드를 중단하며 Archive·Consensus·업무 오류 경로도 유지했다.

독립 재리뷰의 관련 50건 중 기존 Driver 활동 확인 1건이 1초 heartbeat 판정에서 한 번 실패했다. 그때의 모든 Driver·Consensus·Service·Archive 오류 로그는 비어 있었으나 순간 노드 상태가 없어 정확한 원인은 확정하지 않았다. Docker 실행 종료 후 같은 한 검사는 통과했고, 추가 진단에서 `nodeClosed=false`, `nodeError=null`, heartbeat 680ms·9ms를 확인했다. 실패 원문과 재실행 근거를 둘 다 보존했다. 이를 항상 재현 가능한 코드 결함으로 단정하거나 최초 실패를 삭제하지 않았다.

독립 리뷰어는 최종 동시 실행의 원본 client/node 로그도 읽어 위치·잔고·예약·원장·최초 결과·관찰 배출·정상 종료를 대조했다. 실제 컨테이너 기동 실패·감독 시간 초과를 직접 주입하는 검사는 이번 독립 재리뷰에서 실행하지 않았다. 작은 프로세스·파일 오류 회귀 검사와 정상 Docker 8개 실행은 각각의 범위를 증명한다.

초기 전체 구현의 Red 시도는 컴파일 오류로 멈췄으므로 업무 동작의 Red 증거로 계산하지 않는다. 이후 경고 처리의 Red와 파일 오류 반례는 따로 확인했다.


</details>

분리 전 검증일: 2026-10-21 (KST). 변경된 `app-api` 파일 20개의 경로와 내용을 정렬해 묶은 SHA-256은 `893ecc82b610f7a089ae3b8e3dd992f8d43b8a0c49c218130ea2c12a8ead906e`이다. 실행 로그·XML·8개 보고서 색인·독립 재현은 로컬 검증 자료로 보존했다. 이 자료는 저장소에 포함하지 않으며, 원격 검토에서는 PR CI의 별도 실행 결과와 산출물을 확인해야 한다.

</details>
