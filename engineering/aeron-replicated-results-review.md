# #108 · 주문을 다시 보내도 돈은 한 번만 예약된다

**이번에 확인하는 것은 이미 있는 재전송 규칙이 실제 세 노드에서도 유지되는가다.** 같은 주문 요청을 두 번 보내면 입력 로그에는 두 번 나타나지만, 처음 승인한 예약·원장·승인 순번은 한 번만 생겨야 한다.

예를 들어 1,000 USDT에서 300 USDT짜리 주문을 승인한 뒤 같은 R1을 다시 보내면, 사용 가능 금액은 700 USDT, 예약 금액은 300 USDT를 유지한다. 두 번째 응답도 처음의 승인 순번 1이다. **새 로그 위치는 ‘다시 전송했다’는 뜻이고, 승인 순번 1은 ‘새 주문을 추가 승인하지 않았다’는 뜻이다.**

읽을 순서는 세 가지다.

1. 아래에서 로그 위치와 승인 순번을 구분한다.
2. 실행 지도를 열어 테스트·전송 프로그램·세 노드가 어디서 도는지 본다.
3. 승인 재전송, 거절 재전송, 정책 수정 중 궁금한 사례 하나를 펼친다.

로컬 관련 테스트 **558개가 실패·오류·skip 없이 통과**했다. 독립 리뷰에서 코드 결함은 발견되지 않았고, 문서의 오류 분류 설명 한 건은 수정 후 재검토를 마쳤다. 아래는 PR 게시 전 로컬 검증 기록이며, 원격 CI 결과는 PR의 Checks에서 별도로 확인한다.

<details markdown="1">
<summary>먼저 보기 · 로그 위치는 바뀌는데 승인 순번은 왜 그대로일까?</summary>

## 입력을 받은 횟수와 주문을 승인한 횟수는 다르다

아래 위치는 이번 `ACCEPTED_REPLAY` Docker 실행 보고서에서 읽은 실제 값이다. 실행이 달라지면 값도 달라질 수 있다.

| 보낸 입력 | 이번 실행의 클러스터 적용 위치 | 주문 승인 순번 | 처리 후 사용 가능 금액 | 처리 후 예약 금액 |
| --- | --- | --- | --- | --- |
| 개발 자금 P1 준비 | L=352 | 아직 0 | 1,000 USDT | 0 USDT |
| R1 / O1 처음 전송 | L=768 | 1 | 700 USDT | 300 USDT |
| 같은 R1 / O1 재전송 | L=1184 | 처음의 1 | 700 USDT | 300 USDT |

**클러스터 적용 위치 `position`**은 Aeron 입력 로그의 바이트 위치다. 각 노드가 어떤 입력까지 적용했는지 맞춰 볼 때 사용한다. 자금 준비와 거절도 입력이므로 위치는 전진할 수 있다. 연속된 입력이라고 1씩 늘어나는 번호는 아니다. 노드는 [현재 입력의 `cluster.logPosition()`과 처리 후 상태](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingAdapter.kt#L133)를 함께 관찰로 남긴다.

**주문 승인 순번 `sequence`**은 우리 도메인이 새 주문을 승인할 때 부여하는 `Long` 값이다. [새 승인 후보를 만들 때만 `lastSequence + 1`](../domain-trading/src/main/kotlin/com/exchange/core/trading/OrderAdmissionState.kt#L234)을 계산한다. 같은 요청의 재전송은 [저장된 첫 결과를 먼저 반환](../domain-trading/src/main/kotlin/com/exchange/core/trading/OrderAdmissionState.kt#L178)하므로 이 계산에 다시 들어가지 않는다.

전송 프로그램의 `offer`가 반환하는 양수 위치도 있다. 그것은 그 프로그램의 전송 수용 근거이며, 세 노드가 같은 업무 입력을 적용했다는 근거로 대신 쓰지 않는다. 테스트는 별도로 세 노드의 `cluster.logPosition()`을 확인한다.

## R1, O1, A-2는 각각 무엇일까?

| 값 | 뜻 | 이번 예시 |
| --- | --- | --- |
| `requestId` | 사용자가 같은 요청을 다시 보냈는지 구분 | R1 |
| `orderId` | 접수하려는 주문 자체를 구분 | O1 |
| `step.name` | 테스트의 몇 번째 전송인지 구분 | A-1, A-2 |
| `runId` | 어떤 빈 클러스터 실행인지 구분 | 실행마다 새 값 |

R1/O1을 두 번 보내도 테스트 단계는 A-1과 A-2로 다르다. [입력 식별자는 실제 인자에서 읽고](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationScenario.kt#L263), [컨테이너명과 출력 파일은 단계 이름으로 구분](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerFundingReplicationTest.kt#L215)한다. 그래야 첫 R1의 출력으로 두 번째 R1까지 확인한 것으로 착각하거나 파일을 덮어쓰지 않는다.

R1/O1과 R2/O1은 **서로 다른 요청으로 같은 주문을 제출한 것**이다. 요청 이력의 키는 사용자와 요청 ID이고, 이미 승인된 주문의 키는 마켓과 주문 ID다. 그래서 다음 결과가 갈린다.

| 상황 | 결과 |
| --- | --- |
| 이미 처리한 R1, 원래 주문 내용도 같음 | R1의 최초 승인 또는 최초 거절 재사용 |
| 이미 처리한 R1, 가격 등 주문 내용이 달라짐 | 요청 내용 충돌, 기존 이력 보존 |
| 처음 보는 R2, O1은 이미 승인됨 | 주문 중복 거절을 R2의 첫 결과로 저장 |
| 처음 보는 R2, O1은 이전에 자금 부족으로 거절됐을 뿐임 | 현재 잔고로 새 판단 가능 |

</details>

<details markdown="1">
<summary>실행 지도 · 테스트 JVM, 전송 컨테이너, 세 노드는 어디서 돌까?</summary>

## 한 사례를 실행하는 과정

```text
개발 컴퓨터 또는 CI
└─ Gradle이 시작한 테스트 JVM
   └─ DockerOrderReplicationTest
      ├─ 한 번 준비한 이미지를 여섯 사례에 재사용
      ├─ 사례마다 새 runId로 아래 세 노드를 기동
      │
      │  Docker 전용 네트워크
      │  ┌─ node0 컨테이너 · JVM · 자기 상태/Driver/Archive
      │  ├─ node1 컨테이너 · JVM · 자기 상태/Driver/Archive
      │  └─ node2 컨테이너 · JVM · 자기 상태/Driver/Archive
      │       같은 입력 순서로 각자의 상태를 계산
      │
      ├─ A-0 전송 컨테이너 · JVM → P1 자금 준비
      ├─ 응답 + 세 노드의 같은 새 적용 위치/업무 값 확인
      ├─ A-1 전송 컨테이너 · JVM → R1/O1 주문
      ├─ 응답 + 세 노드의 같은 새 적용 위치/업무 값 확인
      ├─ A-2 전송 컨테이너 · JVM → 같은 R1/O1 재전송
      └─ 모든 단계 뒤 종료 확인 · 기록 복사 · 보고서 저장
```

테스트 JVM은 **실행을 지휘하고 결과를 판정**한다. 업무 잔고를 직접 바꾸지 않는다. 별도 전송 컨테이너의 JVM은 **명령을 바이트로 만들어 Aeron으로 보내고 응답을 기다린다.** 세 노드 JVM은 **그 입력을 기존 코어 함수에 전달해 각각 자신의 메모리에 적용**한다. 테스트·전송·업무 처리를 모두 같은 JVM에서 실행하는 것은 아니다.

세 노드는 [기존 Compose 설정](../app-api/src/test/docker/aeron-funding/compose.yml#L13)으로 실행한다. 각 노드에 별도의 기록 볼륨이 있고, 전송 컨테이너도 자기 Driver를 가진다. 여러 EC2에 배치하거나 사용자 HTTP API와 연결하는 검사는 이번 실행에 포함하지 않는다.

## 주문 바이트가 돈 예약으로 이어지는 곳

1. [개발 주문 프로그램](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentOrderClient.kt#L219)이 주문·마켓·정책·원장 ID·업무 시각을 명령에 담는다. 기존 Protobuf 변환기를 사용한다.
2. Aeron이 전달한 바이트를 [기존 `onSessionMessage`](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingAdapter.kt#L92)가 읽어 주문 명령을 구분한다.
3. [Adapter의 `admit`](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingAdapter.kt#L179)은 기존 요청인지 먼저 확인한다. 처음 보는 요청만 현재 노드의 마켓·수수료 설정과 맞는지 검사한다.
4. [상태 객체의 `admit`](../domain-trading/src/main/kotlin/com/exchange/core/trading/OrderAdmissionState.kt#L166)은 같은 사용자·요청 ID의 첫 결과가 있으면 주문 원본을 비교한다. 같으면 첫 결과, 다르면 내용 충돌이다.
5. 새 요청이면 현재 잔고로 계산한다. 새 승인은 잔고·대기 주문·예약 원장·최초 결과를 함께 상태에 반영한다. 정상 거절은 돈을 바꾸지 않고 최초 거절만 기억한다.
6. Adapter는 처리 후 불변 관찰을 남긴다. [기존 출력 코드](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingEvents.kt#L112)가 결과와 상태를 읽을 수 있는 형태로 펼친다. 리더는 작은 접수 응답을 전송하고, 팔로워도 같은 업무 처리를 적용한다.
7. 테스트는 이번 전송의 응답과 세 노드 관찰을 각각 대조한다. 그 단계가 확인돼야 다음 입력을 보낸다.

클라이언트가 응답을 받았다고 해서 테스트가 세 노드의 현재 상태를 모두 읽은 것은 아니다. 그래서 **응답 수신과 세 사본 대조를 따로 기다린다.** 이때 관찰을 기다리는 코드는 테스트 JVM에 있고, 업무 처리 callback에 테스트 대기나 파일 쓰기를 추가하지 않는다.

</details>

<details markdown="1">
<summary>세 가지 새 사례 · 승인 재전송, 거절 재전송, 정책 수정</summary>

## 공통으로 사용하는 값

각 사례는 빈 클러스터에서 시작한다. 사용자 U1, 마켓 BTC-USDT, 지정가·GTC, 수수료 0, 노드의 정책 버전 1이다. 주문 O1은 가격 100 USDT, 수량 3 BTC이므로 300 USDT가 필요하다.

| 사람이 읽는 값 | 실제 입력의 최소 단위 |
| --- | --- |
| 1,000 USDT | `1_000_000_000` |
| 가격 100 USDT | `100_000_000` |
| 수량 3 BTC | `300_000_000` |
| 예약 300 USDT | `300_000_000` |

정답은 [새 고정 사례 파일](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/ReplayedOrderScenarios.kt#L4)에 직접 적었다. 실제 엔진의 반환값이나 첫 노드의 관찰을 정답으로 복사하지 않는다. 원장 ID·정책·업무 시각의 초와 나노초까지 비교한다.

## A · 첫 승인을 다시 쓰고 추가 예약은 하지 않는다

**준비:** 빈 세 노드에 P1로 1,000 USDT를 준비한다. **실행:** 첫 주문, 같은 명령, 실행 정보만 바꾼 재전송, 내용 충돌, 새 요청의 주문 중복을 이어 보낸다. **확인:** 각 단계마다 세 노드의 응답·상태와 이번 전송의 새 위치를 확인한다.

| 단계 | 보내는 내용 | 그 단계에서 확인하는 결과 |
| --- | --- | --- |
| A-0 | P1, 1,000 USDT 준비 | 사용 가능 1,000·예약 0 USDT, OPENING 한 건, 요청 이력 0, 승인 순번 0 |
| A-1 | R1/O1 처음 제출 | 승인 순번 1, 사용 가능 700·예약 300 USDT, 대기 주문·예약·RESERVE 각각 한 건 |
| A-2 | A-1과 완전히 같은 명령 | 처음의 승인 순번 1, A-1의 돈·이력·원장 유지, 적용 위치는 새 값 |
| A-3 | R1/O1 원본 유지, 정책 2·새 원장 ID·다른 시각 | 첫 승인 재사용, 저장된 정책 1·원장 ID·시각·후보 보존 |
| A-4 | R1/O1 가격을 101 USDT로 변경, 정책 2 | `REQUEST_CONTENT_CONFLICT`, 기존 이력·돈·대기 주문 유지 |
| A-5 | 새 R2로 이미 승인된 O1 제출 | `DUPLICATE_ORDER`, R2의 첫 거절만 추가 |
| A-6 | R2/O1 같은 원본, 정책 2·새 원장 ID·다른 시각 | R2의 첫 중복 거절 재사용, 이력 추가 없음 |

A-4는 정책이 달라도 먼저 기존 R1의 원본과 비교한다. 따라서 현재 정책 오류로 바꿔 응답하면 실패다. 최종적으로 요청 이력은 R1 승인과 R2 거절의 두 건이며, 실제 원장은 OPENING 한 건과 RESERVE 한 건이다. **거절 요청에 원장 ID를 제출했다는 사실은 실제 전표 발행이 아니다.**

입력·독립 정답: [`acceptedReplaySteps`](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/ReplayedOrderScenarios.kt#L4).

## B · 나중에 돈이 생겨도 같은 요청의 첫 거절을 돌려준다

**준비:** 잔고 항목도 없는 빈 상태다. **실행:** R1을 자금 부족으로 거절한 뒤 자금을 준비하고, 같은 R1과 새 R2를 차례로 보낸다. **확인:** 과거 요청의 응답과 현재 잔고를 혼동하지 않는다.

| 단계 | 보내는 내용 | 그 단계에서 확인하는 결과 |
| --- | --- | --- |
| B-0 | 돈 없이 R1/O1 제출 | `INSUFFICIENT_BALANCE`, 최초 사용 가능 0·필요 300·부족 300 USDT, 요청 이력 한 건. 잔고 항목·실제 원장·예약 없음 |
| B-1 | P1, 1,000 USDT 준비 | 현재 사용 가능 1,000·예약 0 USDT, R1의 첫 거절 유지 |
| B-2 | 같은 R1/O1, 정책 2·새 원장 ID·다른 시각 | 첫 거절의 사용 가능 0·필요 300·부족 300 USDT 반환. 현재 1,000 USDT는 그대로 |
| B-3 | 새 R2로 같은 O1 제출, 정책 1 | 현재 잔고로 새 판단하여 승인 순번 1, 사용 가능 700·예약 300 USDT |
| B-4 | 예전 R1/O1 다시 전송 | R1의 첫 자금 부족 반환. 현재 O1이 승인됐다는 이유로 주문 중복으로 바꾸지 않음 |

R1의 응답에 나오는 사용 가능 0 USDT는 **처음 R1을 판단할 때의 값**이다. 현재 잔고 조회 결과가 아니다. 새로 판단받으려면 새 요청 R2를 사용한다. B-0 앞에 자금 준비를 자동으로 넣으면 이 상황 자체를 없애므로, B만 첫 입력이 주문이다.

입력·독립 정답: [`rejectedReplaySteps`](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/ReplayedOrderScenarios.kt#L31).

## C · 처음부터 정책이 안 맞은 요청은 고쳐 보낼 수 있다

**준비:** P1로 1,000 USDT를 준비하고 노드는 정책 버전 1을 사용한다. **실행:** 처음 보는 R1에 정책 2를 담아 보낸 뒤, 정책 1로 고친 같은 R1을 보낸다. **확인:** 첫 시도는 엔진 접수 이력에 남지 않고, 고친 시도가 최초 승인이 된다.

| 단계 | 보내는 내용 | 그 단계에서 확인하는 결과 |
| --- | --- | --- |
| C-0 | P1, 1,000 USDT 준비 | 사용 가능 1,000·예약 0 USDT, 요청 이력 0 |
| C-1 | 새 R1/O1, 정책 2 | `EXECUTION_CONTEXT_MISMATCH`, 돈 유지, 요청 이력 0·승인 순번 0·예약 없음 |
| C-2 | 같은 R1/O1, 정책 1로 수정 | 최초 승인, 사용 가능 700·예약 300 USDT, 요청 이력 한 건·승인 순번 1 |

C-1도 받은 입력으로 로그에는 남고 각 노드의 응답 관찰이 있어야 한다. 다만 코어에 전달하기 전에 정책 불일치로 돌려보냈으므로 **최초 접수 결과 이력에는 넣지 않는다.** 나중 C-2의 승인만 보고 C-1도 처리됐다고 추정하지 않는다.

입력·독립 정답: [`contextCorrectionSteps`](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/ReplayedOrderScenarios.kt#L56).

</details>

<details markdown="1">
<summary>코드 한 단위 읽기 · 같은 R1의 예전 출력으로 이번 검사가 통과하지 않게 하기</summary>

## 이전 검사에서 바꾼 부분

기존 비교기는 같은 요청 ID에 해당하는 전체 관찰에서 리더 적용 위치 하나를 찾았다. 같은 R1을 정당하게 재전송하면 서로 다른 두 위치가 생기므로, 이번에는 **각 전송의 새 위치를 선택하고 그 값을 보관**하도록 바꿨다.

[`compareOrderReplication`](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparison.kt#L35)의 핵심 선택식은 다음과 같다.

```kotlin
val position = pinnedPosition ?: leaders.filter { it > previousPosition }.singleOrNull()
```

처음 검사할 때는 `previousPosition`보다 큰 리더 위치가 하나여야 한다. 재검사할 때는 이미 보관한 `pinnedPosition`을 사용한다. `singleOrNull()`은 후보가 없거나 여러 개면 하나를 임의로 고르지 않는다는 뜻이다.

실행 순서는 [기존 실행기의 `runOrderInputs`](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerFundingReplicationTest.kt#L145)에서 이어진다.

1. 전송 직전 각 노드 출력 개수를 보고서용 cursor로 남긴다.
2. 고유 단계 이름으로 전송 컨테이너를 실행하고 그 컨테이너의 새 stdout을 읽는다.
3. 노드 출력을 모아 직전보다 큰 새 리더 위치 L을 찾는다.
4. 세 노드 모두 같은 L에서 이번 입력과 독립 기대값을 보여주는지 확인한다.
5. 단계·직전 위치·확인한 L·이번 응답을 `OrderCheckpoint`에 보관한다.
6. 다음 입력과 마지막 수집 뒤에는 [`recheckOrderInputs`](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerFundingReplicationTest.kt#L193)가 그 고정 L로 다시 검사한다.

cursor는 수집 당시의 출력 개수를 남기는 진단값이다. cursor만 보고 새 업무 처리라고 판정하지 않는다. 실제 선택에는 새 로그 위치가 필요하다.

예를 들어 A-1을 L=768에서 확인한 뒤 A-2가 L=1184에 나타나도 A-1의 위치는 768을 유지한다. 그런데 뒤늦게 **같은 L=768의 예약이 600 USDT라는 모순된 관찰**이 오면 A-1 검사는 실패한다. 나중의 정상 재전송과 과거 입력의 모순을 구분하기 위해서다.

## 정확히 무엇을 비교할까?

각 단계의 새 client 출력에는 `TRANSPORT_ACCEPTED`와 `RESULT_RECEIVED`가 각각 한 건이어야 한다. 응답의 사용자·요청·마켓·주문 식별자와 작은 접수 결과를 비교한다. 각 노드에서는 같은 L의 모든 관련 관찰을 읽어 결과와 다음 상태를 비교한다.

- 현재 잔고의 사용 가능 금액·예약 금액
- 준비 기록, 대기 주문, 최초 요청 이력, 승인 순번
- 최초 주문 원본·마켓·정책·원장 ID·업무 시각
- 승인 후보의 이전/다음 잔고, 주문별 예약, RESERVE 전표의 분개
- 실제 전표 배열의 개수와 순서

[`compareOrderFields`](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparison.kt#L100)는 기대값에 적은 의미 필드를 재귀적으로 비교한다. 배열은 길이와 항목 순서까지 비교한다. 진단용 필드까지 JSON 전체가 똑같아야 한다는 검사는 아니다. 세 노드가 같은 오답을 내도 고정 정답과 다르면 실패한다.

</details>

<details markdown="1">
<summary>변경 파일 지도 · 개발 송신 코드 한 곳과 검사 코드 일곱 곳</summary>

## 파일을 읽는 순서

| 분류 | 파일·심벌 | 이번에 맡은 일 |
| --- | --- | --- |
| 개발·검증 도구 | [`DevelopmentOrderClient.kt` · `runDevelopmentOrderClient`](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentOrderClient.kt#L203) | 명령 끝의 선택 인자로 양수 Long 정책 버전을 받을 수 있게 한다. 생략하면 기존 버전 1을 쓴다. 노드 설정을 바꾸지 않는다. |
| 테스트 | [`DevelopmentOrderClientTest.kt`](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentOrderClientTest.kt#L189) | 선택 버전의 실제 전달, 잘못된 값의 전송 전 거절, 기본값 유지 확인 |
| 테스트 입력·정답 | [`OrderReplicationScenario.kt` · `OrderReplicationStep`](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationScenario.kt#L263) | 기존 세 사례에 새 A/B/C 사례를 연결한다. 전송 단계 이름과 실제 요청·주문 ID를 분리한다. |
| 테스트 입력·정답 | [`ReplayedOrderScenarios.kt`](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/ReplayedOrderScenarios.kt#L4) | A/B/C의 15개 입력과 독립 기대값을 한 파일에 둔다. 기존 계산기나 실제 관찰로 정답을 만들지 않는다. |
| 검사 도구 | [`OrderReplicationComparison.kt`](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparison.kt#L7) | 이번 전송의 새 위치를 찾고, 이후에는 고정 위치로 결과·상태를 다시 대조한다. |
| 검사 도구 테스트 | [`OrderReplicationComparisonTest.kt`](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L12) | 합법적인 재전송은 허용하고, 과거 관찰 재사용·누락·뒤늦은 모순·잘못된 상태를 잡는지 확인한다. |
| 실제 Docker 실행 지원 | [`DockerFundingReplicationTest.kt` · `DockerFundingVerification`](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerFundingReplicationTest.kt#L26) | 단계별 고정 위치를 보관하고, 후속 수집·종료 시에도 그 위치를 재검사한다. 기존 자금 복제 검사도 이 실행기를 사용한다. |
| 실제 Docker 테스트 진입점 | [`DockerOrderReplicationTest.kt`](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerOrderReplicationTest.kt#L8) | 여섯 사례를 각각 빈 클러스터에서 실행하고 실패 목록이 비었는지 확인한다. 첫 이미지 하나를 다음 사례에 재사용한다. |

업무 판단은 기존 `OrderAdmissionState`와 `DevelopmentFundingAdapter`를 재사용한다. 이번에 새로 추가한 업무 서버·Protobuf 메시지·DB 테이블은 없다. 문서는 위 여덟 파일을 설명하는 별도 파일이다.

</details>

<details markdown="1">
<summary>테스트 읽기 1 · 비교기 자체를 시험하는 추가 아홉 사례와 기존 반복 검사</summary>

## JSON 예제로 검사기의 오판을 잡는다

이 절은 실제 Docker를 띄우지 않는 **검사 도구의 단위 테스트**다. `Fixture`가 고정 JSON 예제를 만들고, 일부를 일부러 틀리게 바꾼다. 오류를 비교기가 찾아내면 그 테스트는 통과한다. 실제 세 노드 실행 결과는 다음 절의 Docker 테스트로 따로 확인한다.

공통 assertion인 [`assertIssue`](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L668)는 성공이 아님과 기대한 오류 분류·문구의 존재를 확인한다. 오류 목록이 정확히 한 건이라는 검사는 아니다. [`assertMutations`](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L652)는 변형 대상이 실제로 존재하는지도 확인하고, 한 필드를 바꿀 때마다 비교기를 실행한다.

| 추가 테스트 | Given · 준비 | When · 실행 | Then · 실제 assertion |
| --- | --- | --- | --- |
| [같은 R1의 새 위치 구분](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L14) | 세 노드에 첫 R1 위치와 재전송 위치 928의 관찰을 함께 둠 | 직전 위치 뒤의 재전송을 비교 | `succeeded=true`, 선택 위치가 정확히 928 |
| [고정 위치와 뒤늦은 모순](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L26) | 첫 R1과 나중 R1이 함께 있어도 과거 위치를 고정하면 통과 | 과거 위치의 node2 예약을 300에서 600 USDT로 바꾼 관찰 추가 | 선택 위치는 과거 위치 그대로, 해당 hold 차이를 `MISMATCH`로 검출 |
| [과거 자료만 있거나 한 노드가 누락](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L44) | 재전송 단계의 기대 위치는 새 값 | 세 노드 모두 과거 관찰만 제공한 경우와 node2만 과거인 경우를 각각 비교 | 새 리더 위치 없음 또는 node2의 새 동일 위치 누락을 `UNVERIFIABLE`로 검출 |
| [단계 이름과 입력 ID 분리](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L53) | R1/O1을 보내는 기존 단계 | 단계 이름만 A-2로 변경 | identity가 원래와 같고 요청 R1·주문 O1 유지 |
| [재전송이 첫 결과·돈·이력을 바꾸면 검출](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L533) | A-3의 정상 독립 정답 | 아래 11필드를 각각 변형하고 실제 전표 한 건 추가한 경우도 비교 | 각 변형 위치의 `MISMATCH`, 전표 배열은 기대 2건과 다름을 검출 |
| [거절 종류별 이력 개수](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L556) | A-5·A-4·C-1의 서로 다른 기대 이력 | 아래 표의 잘못된 이력 개수로 각 관찰 변형 | 세 경우 각각 `MISMATCH` 검출 |
| [첫 부족 상세와 현재 잔고 구분](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L569) | B-2와 B-4는 첫 부족을 반환해야 함 | 부족 상세나 사유를 변형. 세 노드의 사유를 모두 같은 오답으로 바꾼 경우도 비교 | 모든 변형을 검출하며, 세 사본이 같아도 `.result.reason`의 `MISMATCH` 확인 |
| [나중 C-2로 C-1 관찰 대체 금지](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L588) | C-1의 정책 불일치 위치를 고정 | C-2 승인 관찰만 제공 | node0의 C-1 새 동일 위치 누락을 `UNVERIFIABLE`로 검출 |
| [고유 전송 단계와 B의 첫 입력](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L598) | 여섯 사례의 단계 목록 | 각 이름을 소문자로 만들고 실제 입력 ID를 읽음 | 이름 중복 없음, A-1~A-4는 모두 실제 R1, B 첫 입력은 FUNDING이 아닌 ORDER |

마지막 테스트는 실제 파일을 생성해 경로를 검사하지 않는다. 파일명 생성에 사용하는 단계 이름의 고유성과 실제 R1 유지 여부를 확인한다. 실제 단계별 파일은 Docker 실행 보고서로 확인한다.

### 필드 변형의 실제 파라미터

A-3의 변형 11개는 각각 node2에서 한 필드만 잘못된 경우다.

| 변형 대상 | 잘못 넣는 값 |
| --- | --- |
| 현재 잔고 hold | 600 USDT |
| 대기 주문 개수 | 2 |
| 최초 요청 이력 개수 | 2 |
| 마지막 승인 순번 | 2 |
| 저장된 첫 주문의 가격 | 101 USDT |
| 저장된 첫 정책 버전 | 2 |
| 저장된 첫 원장 ID | `L-RETRY-3` |
| 저장된 첫 업무 시각의 초 | `1792540803` |
| 저장된 첫 업무 시각의 나노초 | `123456788` |
| 첫 승인 결과 안 후보의 다음 hold | 600 USDT |
| 실제 RESERVE 전표 ID | `L-RETRY-3` |

여기에 전표 배열에 RESERVE를 한 번 더 붙이는 별도 비교 한 번이 있다. 새 원장 ID를 제출했더라도 첫 승인의 전표가 바뀌거나 늘면 안 되기 때문이다.

| 이력 개수 변형의 단계 | 정상 개수 | 일부러 넣는 오답 |
| --- | --- | --- |
| A-5 새 R2의 주문 중복 거절 | 2 | 1 |
| A-4 같은 R1의 내용 충돌 | 1 | 2 |
| C-1 새 요청의 정책 불일치 | 0 | 1 |

B-2와 B-4에는 각각 세 필드 변형을 적용한다. 응답의 최초 available을 1,000 USDT로 바꾸거나, 응답 사유를 `DUPLICATE_ORDER`로 바꾸거나, 저장된 R1 첫 결과의 available을 1,000 USDT로 바꾼다. 각 단계에서 세 노드의 응답 사유를 모두 주문 중복으로 바꾸는 비교도 한 번씩 있다.

### 기존 정상 반복 검사도 여섯 사례로 확장한다

[`모든 독립 사례의 준비와 주문 단계를 새 동일 위치에서 확인한다`](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L63)는 여섯 사례의 **23개 단계**를 반복한다. 각 단계의 정상 JSON을 비교기에 넣고 성공 여부와 선택 위치를 확인한다. 기존 세 사례의 8단계에 A 7단계, B 5단계, C 3단계가 추가됐다. 하나의 테스트 정의에 반복 사례가 들어 있는 구조다.

테스트용 위치는 [인덱스에 따라 충분한 개수로 생성](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L702)한다. 이 숫자는 비교기 예제용이며 실제 Aeron 실행의 측정값이 아니다. 정상 JSON도 기대값으로 만드는 단위 테스트이므로 실제 노드의 업무 정답을 검증했다는 근거로 쓰지 않는다.

실행 상태: PR 게시 전 로컬 검사 통과. 원격 CI 실행 여부와 결과는 PR의 Checks에서 확인한다.

</details>

<details markdown="1">
<summary>테스트 읽기 2 · 정책 버전 인자가 실제로 전달되는지 확인하기</summary>

## 옵션을 생략하면 기존 버전 1을 사용한다

기존 [로컬 main 실행 테스트](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentOrderClientTest.kt#L61)는 버전 인자를 생략한다. 실제 자금 준비 후 주문을 보내고, 전송 수용 안내 뒤 결과 안내가 나오는지와 잔고·예약·원장을 확인한다. 이번에는 후보의 `scheduleVersion == 1L`도 확인한다.

기존 [환경 설정 실행 테스트](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentOrderClientTest.kt#L131)도 버전을 생략한 승인·부족 거절을 보내고, 처음 승인된 후보의 버전 1을 확인한다. 별도 Driver를 닫아도 노드 Driver는 살아 있는지 확인하는 기존 검증은 유지한다.

## 새 옵션 2는 실제 엔진이 기억한 값도 2여야 한다

[`선택 정책 버전은 로컬과 환경 주문에서 실제 접수 원본에 전달된다`](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentOrderClientTest.kt#L190).

- **Given:** 실제 개발 노드를 정책 버전 2로 띄우고 1,000 USDT를 준비한다.
- **When:** 로컬 인자 형식과 `--cluster-env` 형식 각각에서 명령 끝에 `2`를 붙여 같은 조건의 주문을 보낸다. 각 반복은 새 노드를 사용한다.
- **Then:** 실제 승인 응답이 기대 식별자·순번과 같고, 노드의 대기 후보와 최초 요청 이력에 정책 2가 저장됐는지 확인한다. 사용 가능 700 USDT, 예약 300 USDT, 마지막 승인 순번 1, 노드 오류 없음도 확인한다.

테스트는 옵션 문자열을 파싱한 객체만 보는 것이 아니라 실제 Aeron 노드가 남긴 상태를 읽는다. 이 테스트에서 사용하는 노드와 클라이언트는 테스트 JVM 안에서 실행되며, 별도 Docker JVM 검사는 아래 절이 맡는다.

| 반복 파라미터 `structured` | 명령 형식 | 실제 확인하는 버전 |
| --- | --- | --- |
| `false` | 기존 노드루트·기준포트 인자 뒤에 주문 값과 `2` | 후보·최초 요청 모두 2 |
| `true` | 환경 연결 정보와 `--cluster-env`, 주문 값과 `2` | 후보·최초 요청 모두 2 |

## 잘못된 옵션은 Driver를 만들기 전에 거절한다

[`잘못된 선택 정책 버전은 Driver 생성과 전송 전에 거절한다`](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentOrderClientTest.kt#L239).

- **Given:** 아래 네 잘못된 값과 두 명령 형식을 조합한다. 노드·Driver를 기동하지 않는다.
- **When:** 주문 실행 함수에 잘못된 선택 버전을 전달한다.
- **Then:** `IllegalArgumentException`, ‘수수료 정책 버전’ 오류 문구, 실행 루트 디렉터리가 생기지 않았음을 각각 확인한다. Driver 생성보다 앞선 [입력 검사 순서](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentOrderClient.kt#L211)도 코드에서 대조했다.

| 버전 입력 | 거절 이유 | 반복 명령 형식 |
| --- | --- | --- |
| `0` | 양수가 아님 | 로컬·환경 |
| `-1` | 양수가 아님 | 로컬·환경 |
| `two` | Long 숫자로 읽을 수 없음 | 로컬·환경 |
| `9223372036854775808` | Long 최댓값을 넘음 | 로컬·환경 |

두 개의 추가 테스트 정의가 각각 정상 2조합, 오류 8조합을 실행한다. 정상 조합은 실제 노드·클라이언트를 쓰고, 오류 조합은 전송에 들어가기 전의 입력 거절 검사다. 이 추가 테스트에 mock이나 MockMvc를 사용하지 않는다. 기존 파일 전체의 제어 응답·희귀 전송 오류용 대역을 새 실제 노드 증거로 계산하지 않는다.

실행 상태: PR 게시 전 로컬 검사 통과. 원격 CI 실행 여부와 결과는 PR의 Checks에서 확인한다.

</details>

<details markdown="1">
<summary>테스트 읽기 3 · 실제 Docker 여섯 실행과 각 단계의 검증문</summary>

## 테스트 하나가 독립 실행 여섯 번을 감독한다

[`DockerOrderReplicationTest`](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerOrderReplicationTest.kt#L8)의 JUnit 테스트 정의는 하나다. 아래 여섯 사례를 반복하고, 각 실행 결과가 실패면 목록에 넣는다. 마지막의 `assertTrue(failures.isEmpty())`가 모든 사례의 성공을 요구한다. 단순히 컨테이너가 켜졌는지만 확인하지 않는다.

**Given:** 미리 만든 배포 산출물로 이미지 하나를 준비한다. 각 사례는 새 runId·세 컨테이너·빈 상태로 시작한다. **When:** 정해진 단계 순서대로 실제 전송 컨테이너를 실행한다. **Then:** 매 단계의 새 client 응답과 세 노드의 같은 새 L, 고정 기대 결과·상태를 대조하고 종료·자료 수집 오류도 합쳐 판정한다.

| 반복 사례 | 준비와 실행 | 마지막 주요 기대값 | 단계 수 |
| --- | --- | --- | --- |
| 기존 `BUY_SEQUENCE` | 1,000 USDT 준비 → 300 USDT 주문 → 100 USDT 주문 | 사용 가능 600·예약 400 USDT, 승인 순번 1·2, 대기 O1→O2 | 3 |
| 기존 `SELL` | BTC 3개 준비 → BTC 2개 매도 | 사용 가능 BTC 1개·예약 BTC 2개, 승인 한 건 | 2 |
| 기존 `INSUFFICIENT` | 1,000 USDT 준비 → 300 USDT 예약 → 800 USDT 추가 요구 | 사용 가능 700·예약 300 USDT, 두 번째 요청은 필요 800·현재 700·부족 100 USDT 거절 | 3 |
| 새 `ACCEPTED_REPLAY` | A-0~A-6 | 첫 승인 재사용, 요청 이력 2, 대기·예약·RESERVE 한 건, 승인 순번 1 | 7 |
| 새 `REJECTED_REPLAY` | B-0~B-4 | R1의 최초 부족과 R2의 최초 승인 공존, 현재 사용 가능 700·예약 300 USDT | 5 |
| 새 `CONTEXT_CORRECTION` | C-0~C-2 | C-1 이력 없음, C-2 최초 승인 후 이력 한 건·승인 순번 1 | 3 |

총 23단계이며 새 A/B/C는 15단계다. 각 표의 마지막 값뿐 아니라 **중간 단계마다** 검사한다. 특히 C-1은 이력에 안 남으므로 C-2를 보내기 전에 C-1의 실제 세 관찰을 모은다.

기존 [`DockerFundingReplicationTest`](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerFundingReplicationTest.kt#L16)는 별도의 한 테스트로 P1 자금·OPENING 한 건의 세 사본 복제를 확인한다. 주문 여섯 실행을 합쳐 ‘JUnit 테스트 6개’로 세거나, 이 기존 자금 테스트를 새로 추가했다고 세지 않는다.

### 성공을 판정하는 실제 코드

- [세 노드의 같은 새 L·입력 식별자](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerFundingReplicationTest.kt#L201): 세 멤버 모두 이번 위치의 APPLIED가 있어야 한다.
- [응답과 업무 상태](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparison.kt#L63): 같은 L에 정상 관찰과 모순 관찰이 함께 있어도 모두 비교한다.
- [이번 전송의 client 결과](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparison.kt#L81): 전송 수용 한 건, 응답 한 건, 올바른 식별자와 결과를 요구한다.
- [고정 L 재검사](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerFundingReplicationTest.kt#L193): 나중 정상 재전송과 섞지 않고 뒤늦은 과거 모순을 검출한다.
- [전체 실행의 최종 assertion](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerOrderReplicationTest.kt#L17): 어느 실행이든 수집·정리까지 문제가 있으면 실패 목록이 남는다.

이 테스트는 실제 Docker UDP·Aeron Cluster·각 노드의 Kotlin 코어를 사용한다. HTTP API, 주문 체결·취소, 재시작 복구, 동시 주문, 리더 장애 시험은 수행하지 않는다. 여러 마켓과 성능 목표도 이 결과로 보장하지 않는다.

실행 상태: PR 게시 전 로컬 검사 통과. 원격 CI 실행 여부와 결과는 PR의 Checks에서 확인한다.

</details>

<details markdown="1">
<summary>실패하면 어디서 멈추고, 돈과 기록은 어떻게 남을까?</summary>

## 기대한 거절과 검사 실패를 구분한다

자금 부족·요청 내용 충돌·주문 중복·정책 불일치는 시나리오가 기대한 응답과 상태를 보여주면 테스트 성공이다. 예를 들어 B-0의 자금 부족은 돈을 바꾸지 않고 최초 거절 이력만 남기는 정상 동작이다.

반면 다음은 검사를 성공시킬 수 없다.

| 발견한 상황 | 분류와 다음 처리 |
| --- | --- |
| 이미지·Docker·노드 준비 실패 | `SETUP`, 입력을 보내기 전 종료·자료 수집으로 이동 |
| 응답 없음, 새 L 없음, 한 노드 관찰 없음 | `UNVERIFIABLE`, 그 뒤 입력을 진행하지 않음 |
| 응답·잔고·예약·원장·최초 이력의 값이 정답과 다름 | `MISMATCH`, 처음 확인한 차이를 보존 |
| 이벤트 JSON 파싱 실패 | `UNVERIFIABLE`, 읽지 못한 입력을 정상 관찰로 세지 않음 |
| 상태 수집에서 예상 밖 노드 종료 발견, 로그 수집·자료 복사 실패 | `COLLECTION`, 부분 결과를 전체 성공으로 바꾸지 않음 |
| client·노드 종료 또는 자원 정리 실패 | `CLEANUP`, 업무 값이 맞더라도 실행 전체 성공으로 보고하지 않음 |

응답 대기 시간이 끝났다고 해서 주문이 미접수됐거나 돈이 자동 롤백됐다는 뜻은 아니다. 이미 엔진에 반영됐을 수 있다. 이번 테스트 실행기는 결과 미확인 상태에서 다음 단계나 새 요청 ID를 자동 전송하지 않는다. 예상 밖 코어 오류는 기존 노드 중단 경로로 전달하며 정상 거절로 바꾸지 않는다.

이 분류는 [이벤트 파서](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/FundingReplicationComparison.kt#L21)와 [노드 수집의 catch 경로](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerFundingReplicationTest.kt#L270)를 따른다. 같은 실행에 관찰 누락과 수집 오류가 함께 기록될 수도 있다.

## 종료 순서와 보존 자료

[기존 정리 흐름](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerFundingReplicationTest.kt#L330)을 유지한다. 마지막 관찰을 모으고, 계속 실행 중인 client JVM을 먼저 닫고 종료를 확인한다. 그 뒤 세 노드를 정상 종료하고 STOPPED·프로세스 상태를 확인한 후 Archive·cluster 기록을 복사한다. 이번 컨테이너·네트워크를 정리하되 기록 볼륨은 임의로 지우지 않는다.

보고서는 `app-api/build/reports/aeron-funding/<runId>/result.json`에 있다. 사례명, 단계별 실제 인자·직전 위치·고정 위치·기대값·client 출력·오류, 실행 명령과 소요 시간, 노드 출력·컨테이너 식별·기록 자료를 함께 남긴다. 경로의 runId는 실제 결과에서 읽는다.

기존 제한은 기동/연결 최대 60초, 전송·응답 각각 5초, client 프로세스 감독 100초, 관찰 대기 15초다. 이 시간은 로컬 대기를 끝내기 위한 상한이다. 주문 업무 시각이나 노드 사이의 결정론적 계산값으로 사용하지 않는다.

</details>

<details markdown="1">
<summary>검증 기준 · 실행 결과 · 다시 실행하는 방법</summary>

## 무엇을 기준으로 읽었나?

- 기준 통합 커밋: `b53f5f0e13d2f83c104249d2c324a74a70afeeef`.
- 브랜치: `test/aeron-replicated-results/108`.
- 설명 대상: 이 문서와 같은 리비전에 있는 여덟 코드·테스트 파일. 링크는 해당 리비전의 소스를 가리킨다.
- 기존 `OrderAdmissionState`, Adapter, Events, Compose는 연결을 설명하기 위해 읽었다. 업무 로직·wire 스키마·CI job은 이번에 변경하지 않았다.

## PR 게시 전 로컬 실행·리뷰 결과

| 항목 | 상태 |
| --- | --- |
| 실제 코드와 자연어 흐름 대조 | 이 문서에 연결한 범위를 원문으로 확인 |
| 비교기·개발 client 로컬 검사 | 비교기 35개·client 10개 통과 |
| 실제 세 노드 Docker 여섯 사례와 기존 자금 회귀 | 7개 빈 클러스터 실행 모두 성공·정상 종료, 기록 볼륨 보존 |
| 코어·단일 노드·lint·구조 회귀 | app-api 관련 83개·domain-trading 100개·구조 375개, 모두 실패·오류·skip 0. ktlint와 P01~P09 실행 확인 통과 |
| 독립 코드 검토 | 별도 체크아웃에서 코드·문서 검토 완료. 확인된 미해결 코드 결함 없음. 실제 여섯 Docker 실행과 92개 독립 관찰 반례 검사 완료. 문서의 오류 분류 설명은 수정 후 재검토 |
| 원격 CI | 위 결과에 포함하지 않음. PR의 Checks에서 별도 확인 |

아래 Gradle 명령으로 검증했다. 합계 558개는 JUnit 실행 수이며, 한 테스트 안의 23개 전송 단계나 필드 변형을 따로 더한 숫자가 아니다. 전체 Gradle 실행은 7분 33초였고, 이는 주문 응답 지연을 측정한 값이 아니다. 재실행할 때 `/absolute/path/to/coin-exchange`를 이 브랜치를 체크아웃한 실제 절대 경로로 바꾼다. Java 25와 실행 중인 Docker가 필요하다.

```sh
cd /absolute/path/to/coin-exchange
./gradlew :app-api:test --tests '*OrderReplicationComparisonTest' --tests '*DevelopmentOrderClientTest' --tests '*DevelopmentOrderNodeTest' --tests '*DevelopmentFundingAdapterTest' --tests '*DevelopmentFundingEventsTest' --tests '*DockerFundingReplicationTest' --tests '*DockerOrderReplicationTest' :domain-trading:test ktlintCheck :architecture-tests:test :architecture-tests:verifyArchitectureReport --console=plain --no-daemon
```

최초 비교기 테스트는 기존 코드에서 두 assertion 실패를 확인했고, 개발 client의 새 두 테스트도 변경 전 실패를 확인했다. 포맷 오류를 정리한 뒤 위 최종 실행을 통과했다. 기존 업무 규칙을 바꾸거나 기대값을 줄여 통과시키지 않았다.

이 문서는 현재 전송에서 첫 결과를 재사용하는 흐름과 그 검사를 설명한다. 재시작 뒤의 보존·복구, 장애 전환, 동시 요청은 후속 범위다. 다음에 코드를 한 군데 더 본다면 **상태 객체가 기존 R1 결과를 반환해 새 예약 계산을 건너뛰는 곳**부터 읽으면 된다.

</details>
