# Aeron으로 받은 주문을 기존 접수 함수에 연결하기

**같은 노드에 준비한 1,000 USDT로 300 USDT 주문을 접수한다. 사용 가능 금액은 700 USDT, 예약 금액은 300 USDT가 된다.** 같은 요청을 다시 보내도 예약을 더 만들지 않는다.

앞선 #97은 자금을 준비하는 메시지를 연결했다. #98은 주문 메시지를 그 노드의 같은 잔고·접수 함수에 연결한다. 새 자금 계산기·매칭 엔진·별도 주문 서버를 만드는 작업이 아니다. [상세 명세](aeron-order-admission-spec.md)는 정상·실패 결과와 제외 범위의 기준이다.

## 먼저 볼 흐름 네 가지

| 흐름 | 어디까지 확인할까 |
| --- | --- |
| 정상 접수 | 실제 통신 → 같은 메모리 잔고 → 예약·대기·RESERVE → 작은 승인 응답 |
| 재전송과 충돌 | 같은 원본은 최초 결과, 다른 원본은 내용 충돌, 새 요청의 기존 주문은 주문 중복 |
| 두 종류의 거절 | 잔고 부족은 최초 판단을 기억, 설정 불일치는 판단 전 제외라 수정 후 재시도 가능 |
| 응답 누락과 내부 오류 | 응답만 놓치면 결과 미확인, 내부 상태를 신뢰할 수 없으면 노드 중단 |

**접수 승인은 체결·정산 완료가 아니다.** 지금 주문은 대기 상태에 있다. 실제 주문장 매칭·취소·정산·HTTP 전환은 연결하지 않는다.

<details markdown="1">
<summary>1. 어느 코드가 무엇을 하는가</summary>

1. [DevelopmentOrderClient.main](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentOrderClient.kt)은 입력한 주문을 기존 Protobuf 바이트로 만들고 Aeron에 보낸다. 양수 offer는 전송 수용이며 아직 접수 승인이 아니다.
2. 기존 [DevelopmentFundingNode](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingNode.kt)가 만든 **한 개**의 OrderAdmissionState를 그대로 사용한다. 설정에는 고정 마켓과 수수료 정책을 추가했다.
3. [DevelopmentFundingAdapter.onSessionMessage](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingAdapter.kt)가 정확한 바이트 구간을 읽고 자금 준비와 주문 명령을 구분한다. 정상 주문을 미지원 메시지로 버리던 곳이 바뀌었다.
4. 주문 분기는 [OrderAdmissionState.hasRequest](../domain-trading/src/main/kotlin/com/exchange/core/trading/OrderAdmissionState.kt)로 기존 요청 여부를 확인한다. 차단된 객체이면 이 조회도 실패한다. 기존 키는 기존 admit이 최초 결과·내용 충돌을 판단하고, 신규 키만 고정 설정을 검사한다.
5. 설정이 맞으면 기존 admit이 최신 잔고에서 계산하고, 예약·대기·RESERVE·최초 결과를 기억한다. 응답 사유 변환과 전달은 app-api가 맡는다.
6. 서비스는 완성된 상태 사본과 결과를 관찰값으로 공개한 뒤 응답한다. 외부 스레드는 그 사본을 읽고 가변 엔진을 직접 만지지 않는다.
7. 클라이언트는 사용자·요청·마켓·주문 네 식별자를 대조한 뒤 접수 결과를 표시한다. 결과를 못 받으면 같은 사용자·요청 ID·원본으로 재확인하도록 안내한다.

새 파일의 성격은 개발용 주문 클라이언트와 통신 테스트다. 제품 HTTP/SQL 경로는 바꾸지 않았다. 별도 Spring 컨텍스트·DB·Kafka 연결도 없다. 어댑터에 이미 기록된 요청을 복제하는 캐시를 만들지 않았다.

</details>

<details markdown="1">
<summary>2. 왜 최초 결과를 현재 설정보다 먼저 쓰는가</summary>

R1을 0ppm 정책으로 승인해 이미 300 USDT를 예약했다면, 재전송이 새 수수료 정보를 가지고 왔다고 다시 계산하면 안 된다. R1은 새 주문이 아니라 **첫 요청의 결과를 확인하는 요청**이기 때문이다. 기존 기록에는 첫 원본·정책·원장 ID·업무 시각·결과가 남아 있다.

순서는 **바이트 형식 검증 → 상태 사용 가능 여부·기존 키 확인 → 기존 키는 코어의 최초 결과 판단 / 신규 키는 설정 검사 → 접수**다. 손상된 바이트까지 이력으로 구제하지 않으며, 코어의 식별자 검사 순서도 유지한다.

| 상황 | 돈과 최초 이력 |
| --- | --- |
| 같은 사용자·R1·같은 원본, 새 수수료/시각 | 첫 결과만 반환, 돈·대기·원장·승인 순번 추가 없음 |
| 같은 사용자·R1, 다른 유효한 가격/수량 | 내용 충돌, 첫 기록 보존 |
| 새 R2로 이미 승인한 주문 ID | 설정이 맞으면 주문 중복 거절을 기억 |
| 처음 받은 R3의 설정 오류 | 사유 9, 접수·최초 업무 이력 없음. 설정을 바로잡아 같은 R3 재제출 가능 |
| 잔고 0에서 R4 부족 거절 뒤 자금 준비 | 같은 R4는 당시 부족 거절 유지. 새 요청은 현재 돈으로 판단 |

설정 오류에도 Aeron 입력 로그는 남을 수 있다. “이력을 만들지 않는다”는 말은 코어의 최초 요청 기록·금전 효과를 만들지 않는다는 뜻이다.

읽을 때는 **최초 결과를 현재 설정보다 먼저 쓰는 이유**와 승인·거절 재전송 사례를 먼저 확인하면 된다. 위 예시의 예약 금액, 최초 결과와 새 요청의 차이를 실제 코드·검증문으로 연결했다.

</details>

<details markdown="1">
<summary>3. 응답을 못 받거나 코드가 실패하면</summary>

승인 반영이 끝난 뒤 응답만 놓칠 수 있다. 이때 300 USDT 예약을 되돌리거나 새 요청으로 다시 예약하지 않는다. **결과 미확인**으로 표시하고 같은 요청 원본으로 재확인한다. 현재 노드가 종료됐다면 아직 재시작 복구를 지원하지 않으므로 그 실행에서 재확인할 수 없다.

역압력·ADMIN_ACTION에는 이미 만든 응답 바이트만 횟수·시간 상한 안에서 다시 보낸다. 리더가 아니거나 세션이 닫혔거나 전송이 종단 상태이면 예약을 유지하고 전달을 중단한다. 확정 입력의 업무 적용은 역할·세션 유무로 생략하지 않는다.

잔고 부족·내용 충돌·주문 중복·설정 불일치는 정해진 거절로 끝나고 다음 요청을 받는다. 예상 밖 코어 오류는 반영 전이어도 개발 노드를 중단한다. 반영 중 쓰기 실패는 일부가 바뀌었을 수 있어 blocked를 유지하며 정상 조회·추가 접수를 막는다. SQL 트랜잭션처럼 전체 롤백한 것으로 설명하지 않는다.

관찰값은 최신 결과 하나와 그때 완성한 상태 사본이다. 전체 거래 원장 저장소·영구 Snapshot·복구 입력 검증을 대신하지 않는다.

</details>

<details markdown="1">
<summary>4. 직접 실행할 때</summary>

실제 구현 폴더는 **/Users/0chord/Desktop/exchange-core-aeron-98**, 브랜치는 **feat/aeron-order-admission/98**다. 기존 #97 폴더와 기본 작업 폴더를 바꾸지 않았다. Java 25와 Aeron 1.53.3·기존 Protobuf를 사용한다.

첫 터미널에서 **아직 존재하지 않는 새 실행 루트**로 노드를 시작한다. 아래 경로가 이미 있으면 새 경로를 골라 두 클라이언트 인자에도 같은 경로를 넣는다. 기존 기록은 삭제하지 않는다.

~~~~sh
cd /Users/0chord/Desktop/exchange-core-aeron-98
./gradlew -p /Users/0chord/Desktop/exchange-core-aeron-98 :app-api:runDevelopmentFundingNode --args='/tmp/exchange-order-98-demo-1 19700'
~~~~

다른 터미널에서 자금 준비 → 주문 → 같은 주문 순서로 하나씩 실행한다.

~~~~sh
cd /Users/0chord/Desktop/exchange-core-aeron-98
./gradlew -p /Users/0chord/Desktop/exchange-core-aeron-98 :app-api:runDevelopmentFundingClient --args='/tmp/exchange-order-98-demo-1 19700 P1 U1 USDT 1000000000 OPENING-1 2026-10-20T00:00:00Z'
./gradlew -p /Users/0chord/Desktop/exchange-core-aeron-98 :app-api:runDevelopmentOrderClient --args='/tmp/exchange-order-98-demo-1 19700 R1 U1 O1 BUY 100000000 300000000 RESERVE-1 2026-10-20T00:00:01Z'
./gradlew -p /Users/0chord/Desktop/exchange-core-aeron-98 :app-api:runDevelopmentOrderClient --args='/tmp/exchange-order-98-demo-1 19700 R1 U1 O1 BUY 100000000 300000000 RESERVE-1 2026-10-20T00:00:01Z'
~~~~

기본 시연은 BTC-USDT·BTC scale 8·SPOT/NORMAL/version 1·maker/taker 0ppm이다. 가격과 USDT 금액은 백만 단위, BTC 수량은 1억 단위라 위 주문은 100 USDT × BTC 3개다. 응답의 sequence=1은 접수 순번이며 Aeron 로그 위치·체결 순번이 아니다. 700 USDT·예약 300 USDT와 RESERVE 두 분개는 노드 관찰값에서 별도로 확인한다.

한 클라이언트는 한 요청씩 기다린다. 같은 고정 egress 포트를 쓰는 개발 클라이언트를 동시에 띄우지 않는다. HTTP 거래 서버를 이 개발 잔고와 같은 자금의 다른 쓰기 경로로 연결하지 않는다.

</details>

<details markdown="1">
<summary>5. 테스트는 무엇을 확인했는가</summary>

실제 통신 사례는 새 단일 노드·실제 UDP·기존 코어를 쓴다. 희귀 전송 반환값·역할·세션·내부 쓰기 실패는 통제한 callback이나 대역으로 검사한다. 아래는 새 검사와 이번에 보강한 기존 검사의 설명이며, 31개를 모두 새 테스트로 세지는 않는다.

실행 결과는 다음 절에 모았다. **준비 → 실행 → 확인**은 각 테스트의 실제 assertion을 설명하며, 설명 문서를 읽었다는 사실을 사람의 코드 이해 완료로 취급하지 않는다.

<details markdown="1">
<summary>실제 UDP 주문 흐름 · 13개</summary>

<details markdown="1">
<summary>실제로 준비한 1000 USDT에서 BUY 300을 접수하면 700은 사용 가능하고 300은 예약된다</summary>

**준비:** 새 실행 폴더와 서로 겹치지 않는 UDP 포트를 사용하는 실제 개발 노드를 띄우고, 자금 준비 클라이언트로 U1에게 1,000 USDT를 준비한다. 주문은 BTC-USDT의 LIMIT·GTC 매수이며, 가격 100 USDT와 수량 3 BTC를 기존 최소 단위로 표현한다. 수수료는 0이다.

**실행:** 개발 주문 클라이언트로 R1·O1 주문을 실제 UDP 경로에 보내 승인 응답을 받고, 같은 노드가 공개한 상태 사본을 읽는다.

**확인:** 응답의 승인 순번은 1이고 요청 ID가 제출한 값과 같다. 사용 가능 금액 700 USDT·예약 금액 300 USDT, 상태의 마지막 승인 순번 1, 대기 주문 한 건을 확인한다. 대기 주문의 원본과 남은 예약액이 맞고, 예약 원장 거래의 ID·업무 시각이 요청과 같다. 두 분개의 계정은 U1의 USDT AVAILABLE과 HOLD, 금액은 각각 300 USDT, 방향은 DEBIT와 CREDIT이다. 자금 준비 이력과 최초 주문 요청 이력은 각각 한 건이며 노드 오류는 없다.

**검사 경계:** 단일 개발 노드의 실제 통신과 메모리 상태를 읽는다. 실제 체결·정산, DB 반영, Archive 원문이나 재시작 후 복구는 확인하지 않는다.

[실제 검증문 보기 · DevelopmentOrderNodeTest.kt:42](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentOrderNodeTest.kt#L42)

</details>

<details markdown="1">
<summary>실제 최초 승인은 마켓 수수료 원장 시각을 바꾼 같은 원본의 재전송에도 그대로 남는다</summary>

**준비:** U1에게 1,000 USDT를 준비한 실제 노드에서 R1·O1을 먼저 승인받는다. 그 직후 관찰한 잔고·주문·원장·자금 준비·최초 요청의 의미 필드를 보관한다.

**실행:** 주문 원본은 유지한 채 같은 요청을 그대로 한 번 보내고, 마켓 정의 ID·기준 자산·표시 자산·수량 scale·상품·수수료 등급·수수료 버전·maker 요율·taker 요율을 각각 바꿔 보낸다. 원장 ID만 바꾼 경우와 업무 시각만 2040년으로 바꾼 경우도 보낸다. 최초 접수 뒤 총 12회 재전송한다.

**확인:** 매 재전송의 응답은 최초 승인 응답과 같고, 노드가 공개한 주문 결과도 그 최초 응답과 같다. 매번 상태 사본의 의미 필드가 최초 승인 직후와 같으며 노드 오류가 없다. 마지막 상태는 승인 순번 1·요청 한 건·대기 주문 한 건·사용 가능 700 USDT·예약 300 USDT다. 최초 요청에 저장된 마켓·수수료 정책·원장 ID·업무 시각도 처음 제출한 값으로 남는다.

**검사 경계:** 읽을 수 있는 문맥을 한 필드씩 바꾼 순차 재전송이다. 주문 원본 자체의 변경이나 여러 문맥을 동시에 바꾸는 모든 조합, 손상된 바이트, 병렬 요청은 이 사례의 대상이 아니다.

[실제 검증문 보기 · DevelopmentOrderNodeTest.kt:82](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentOrderNodeTest.kt#L82)

</details>

<details markdown="1">
<summary>빈 잔고의 최초 부족 거절은 준비와 문맥 변경 뒤에도 유지되고 새 요청만 승인된다</summary>

**준비:** 잔고가 비어 있는 실제 노드에서 U1의 R2·O2를 먼저 보낸다. 이 주문에는 300 USDT가 필요하다.

**실행:** 첫 거절을 받은 뒤 자금 준비 클라이언트로 1,000 USDT를 넣는다. R2 원본을 유지한 채 그대로 한 번, 마켓 정의·수수료의 아홉 필드를 각각 바꿔 한 번씩, 원장 ID만 바꿔 한 번, 업무 시각만 바꿔 한 번 재전송한다. 마지막에는 요청 ID만 R3로 바꿔 같은 주문을 새로 판단받는다.

**확인:** 처음에는 INSUFFICIENT_BALANCE이고 당시 사용 가능 0·필요 300·부족 300 USDT다. 이때 잔고 항목과 대기 주문은 없고 승인 순번은 0이다. 돈을 준비한 뒤의 R2 재전송 12회도 처음 받은 거절과 같으며, 준비 직후의 상태 의미 필드를 바꾸지 않는다. 새 R3만 순번 1로 승인되어 사용 가능 700·예약 300 USDT가 된다. 최초 요청 목록은 R2와 R3이고 대기 주문은 한 건이다. R2의 부족 상세·마켓·정책·원장 ID·시각은 최초 값으로 보존된다.

**검사 경계:** 같은 실행에서 잔고가 생긴 뒤에도 최초 거절이 유지되는지 확인한다. 재시작 후 이력을 복구하거나, 같은 요청을 여러 클라이언트가 동시에 보내는 상황은 다루지 않는다.

[실제 검증문 보기 · DevelopmentOrderNodeTest.kt:114](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentOrderNodeTest.kt#L114)

</details>

<details markdown="1">
<summary>신규 주문의 설정 열 필드는 각각 사유 9이고 고친 같은 요청은 처음 접수된다</summary>

**준비:** U1에게 1,000 USDT를 준비한다. 정상 기준 주문은 100 USDT 가격의 BTC 1개 매수다. 비교 대상은 주문의 marketId와 마켓 정의의 ID·기준 자산·표시 자산·수량 scale, 수수료 정책의 상품·등급·버전·maker 요율·taker 요율로 총 10종이다.

**실행:** 각 필드를 하나씩 다르게 만든 주문에 서로 다른 요청 ID·주문 ID·원장 ID를 붙여 보낸다. 거절을 확인한 뒤에는 같은 사용자·요청 ID·주문 ID로 정상 설정의 주문을 다시 보내 접수한다. 이 과정을 10종 모두에 순차 적용한다.

**확인:** 10종이라는 개수를 직접 확인한다. 잘못된 설정을 보낼 때마다 EXECUTION_CONTEXT_MISMATCH(사유 9)이고 부족 상세는 없다. 응답의 마켓 ID가 제출한 주문을 가리키며, 거절 전후의 상태 의미 필드는 같다. 그 요청의 최초 이력은 없고 손상 메시지 개수도 0이며, 관찰된 주문 결과는 받은 거절과 같다. 설정을 고친 요청은 차례로 승인 순번 1~10을 받고, 매번 100 USDT씩 예약된다. 최종 사용 가능 금액은 0·예약은 1,000 USDT이며 요청 이력과 대기 주문도 각 10건이다.

**검사 경계:** 각 필드를 하나씩 바꾸는 10종을 검사하며 모든 다중 필드 조합을 검사하지 않는다. 주문 marketId가 잘못된 경우에는 수정 과정에서 원본의 그 값도 바로잡는다. 자동 생성된 파라미터 테스트 10개가 아니라 한 테스트 안의 10회 반복이다.

[실제 검증문 보기 · DevelopmentOrderNodeTest.kt:160](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentOrderNodeTest.kt#L160)

</details>

<details markdown="1">
<summary>기존 요청의 내용 충돌이 설정 거절보다 먼저이고 새 요청의 주문 중복은 첫 거절로 남는다</summary>

**준비:** U1의 1,000 USDT에서 R1·O1을 먼저 승인받아 700 USDT가 남고 300 USDT가 예약된 상태를 만든다.

**실행:** ① 기존 R1의 가격을 200 USDT로 바꾸면서 정책 버전도 2로 바꿔 보낸다. ② 기존 O1을 가리키는 새 요청 R4를 잘못된 정책 버전으로 보낸다. ③ R4의 설정만 맞춰 다시 보낸다. ④ 최초 주문 중복 거절을 받은 R4를 다시 잘못된 정책 버전으로 보낸다.

**확인:** ①은 설정 불일치보다 REQUEST_CONTENT_CONFLICT가 먼저이고 부족 상세가 없으며 기존 상태를 바꾸지 않는다. ②는 사유 9이고 R4 이력을 만들지 않는다. ③은 DUPLICATE_ORDER이며 사용 가능 700·예약 300 USDT, 승인 순번 1·대기 주문 한 건을 유지한다. 요청 목록에는 R1과 R4가 남고 R4의 최초 코어 결과는 주문 중복 거절이다. ④도 그 최초 중복 거절과 같은 응답을 주며 상태 의미 필드는 ③ 직후와 같다.

**검사 경계:** 기존 요청의 원본 변경은 가격 한 필드로 확인한다. 원본의 다른 필드별 변경은 이 사례에서 모두 반복하지 않으며, 동시 접수나 마켓이 다른 주문 중복은 다루지 않는다.

[실제 검증문 보기 · DevelopmentOrderNodeTest.kt:206](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentOrderNodeTest.kt#L206)

</details>

<details markdown="1">
<summary>1100이 필요한 첫 거절 뒤에도 300 주문은 순번 1로 승인된다</summary>

**준비:** U1에게 1,000 USDT를 준비하고 자금 준비 원장 거래의 의미 필드를 보관한다. 처음 보낼 주문은 100 USDT 가격의 BTC 11개 매수라서 1,100 USDT가 필요하다.

**실행:** 1,100 USDT가 필요한 주문의 거절을 확인한 뒤, 다른 요청 ID와 주문 ID로 300 USDT가 필요한 정상 주문을 보낸다.

**확인:** 첫 응답은 잔고 부족이며 사용 가능 1,000·필요 1,100·부족 100 USDT다. 첫 거절 직후에도 사용 가능 1,000·예약 0이고, 승인 순번은 0·대기 주문은 없으며 보관한 자금 준비 원장 거래의 값도 같다. 뒤의 정상 주문은 승인 순번 1을 받고 사용 가능 700·예약 300 USDT가 된다. 최초 요청 이력은 총 두 건이고 노드 오류는 없다.

**검사 경계:** 첫 거절 뒤 다음 정상 주문을 계속 처리하는 흐름이다. 이 거절의 재전송이나 다른 종류의 정상 거절, 병렬 주문 사이의 잔고 경합은 여기서 확인하지 않는다.

[실제 검증문 보기 · DevelopmentOrderNodeTest.kt:250](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentOrderNodeTest.kt#L250)

</details>

<details markdown="1">
<summary>다른 사용자의 같은 요청 ID는 각자의 잔고와 최초 결과로 판단한다</summary>

**준비:** 같은 실제 노드에서 U1과 U2에게 각각 1,000 USDT를 준비하되 자금 준비 ID와 OPENING 원장 ID는 서로 다르게 한다.

**실행:** U1은 요청 R1·주문 O1로 300 USDT를 예약하고, U2는 같은 요청 ID R1과 다른 주문 ID O2로 200 USDT를 예약한다.

**확인:** U1의 응답은 승인 순번 1, U2의 응답은 순번 2다. 두 번째 응답에는 U2·R1·O2가 들어 있다. U1 잔고는 사용 가능 700·예약 300, U2는 사용 가능 800·예약 200 USDT로 따로 남는다. 최초 요청 이력의 사용자는 U1과 U2이고 요청 ID는 둘 다 R1이다. 자금 준비 이력과 대기 주문은 각각 두 건이며 노드 오류는 없다.

**검사 경계:** 요청 키가 사용자별로 나뉘는 사례이며 주문 ID는 O1과 O2로 다르게 쓴다. 서로 다른 사용자가 같은 마켓·주문 ID를 동시에 사용할 수 있다는 뜻은 아니다.

[실제 검증문 보기 · DevelopmentOrderNodeTest.kt:276](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentOrderNodeTest.kt#L276)

</details>

<details markdown="1">
<summary>신규 식별자 오류보다 설정 거절이 먼저이고 기존 키는 코어 식별자 검사를 먼저 따른다</summary>

**준비:** U1에게 1,000 USDT를 준비하고 정상 R1·O1 주문을 기준으로 둔다. 식별자 오류 입력은 신규 요청의 빈 요청 ID와, 이미 처리한 요청에 붙여 보내는 65자 주문 ID다.

**실행:** ① 빈 요청 ID와 잘못된 정책 버전 2를 함께 보낸다. ② 같은 빈 요청 ID의 정책만 정상 값으로 고친다. ③ 유효한 R1·O1을 접수한다. ④ 이미 처리된 R1에 65자 주문 ID와 정책 버전 2를 붙여 보낸다.

**확인:** ①은 신규 요청이므로 사유 9가 먼저이고 상태는 변하지 않는다. ②는 INVALID_REQUEST_IDENTIFIER이며 역시 상태를 바꾸지 않는다. ③은 승인 응답이다. ④는 설정 불일치나 내용 충돌 대신 코어의 INVALID_ORDER_IDENTIFIER를 돌려주고 부족 상세는 없으며, ③ 직후의 상태 의미 필드를 그대로 보존한다. 노드 오류도 없다.

**검사 경계:** 형식상 전송할 수 있지만 업무 규칙에는 맞지 않는 빈 요청 ID와 65자 주문 ID를 사용한다. 잘못된 UTF-8·잘린 프레임처럼 코덱에서 먼저 거절하는 입력이나 모든 식별자 오류 조합을 다루지는 않는다.

[실제 검증문 보기 · DevelopmentOrderNodeTest.kt:302](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentOrderNodeTest.kt#L302)

</details>

<details markdown="1">
<summary>비영점 개발 요율의 BUY는 300 USDT와 최대 수수료 0점6을 함께 예약한다</summary>

**준비:** U1에게 1,000 USDT를 준비한다. 이 노드의 고정 정책과 주문 모두 maker 1,000ppm(0.1%)·taker 2,000ppm(0.2%)으로 맞춘다. 매수 주문은 100 USDT 가격의 BTC 3개다.

**실행:** 실제 주문 클라이언트로 주문을 접수하고 응답과 상태 사본의 주문별 예약·원장을 읽는다.

**확인:** 응답의 승인 순번은 1이다. 대금 300 USDT에 두 요율 중 큰 0.2%의 수수료 0.6 USDT를 더한 독립 기대값으로, 사용 가능 699.4·예약 300.6 USDT를 확인한다. 최초·남은 예약액은 각각 300.6 USDT, 최초 수수료 예약액은 0.6 USDT다. 후보와 예약에 고정 정책이 보존되고, 원장 분개 두 항목의 금액은 각각 300.6 USDT다. 원장 ID·업무 시각은 요청 값이며 준비 원장의 종류는 OPENING, 최초 요청 이력은 한 건이고 노드 오류는 없다.

**검사 경계:** 한 가지 비영점 요율 조합의 전달과 고정 금액을 확인한다. 실제 체결의 maker/taker 판정이나 체결 수수료 차감, 모든 반올림 경계를 검사하지 않는다.

[실제 검증문 보기 · DevelopmentOrderNodeTest.kt:340](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentOrderNodeTest.kt#L340)

</details>

<details markdown="1">
<summary>비영점 개발 요율의 SELL도 USDT 없이 준비한 BTC 세 개에서 두 개만 예약한다</summary>

**준비:** 노드와 주문의 고정 요율을 maker 1,000ppm·taker 2,000ppm으로 맞추고, U1에게 USDT가 아닌 BTC 3개만 준비한다.

**실행:** BTC 2개를 파는 LIMIT·GTC 주문을 실제 주문 클라이언트로 접수한다.

**확인:** 응답의 승인 순번은 1이다. 잔고 목록에는 BTC만 있고 사용 가능 1개·예약 2개다. 주문 후보의 원본이 제출한 매도 주문과 같고 예약에 비영점 정책이 보존되지만, 예약액은 BTC 2개·최초 수수료 예약액은 0이다. RESERVE 거래의 ID·시각은 요청과 같고, 두 분개는 BTC AVAILABLE의 DEBIT와 HOLD의 CREDIT이며 금액은 각각 BTC 2개다. 준비 이력과 요청 이력은 각 한 건이고 노드 오류는 없다.

**검사 경계:** 매도 접수 시 BTC만 예약하는 범위를 확인한다. 매칭 이후 USDT 수취·매도 체결 수수료 공제·정산은 실행하지 않는다.

[실제 검증문 보기 · DevelopmentOrderNodeTest.kt:371](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentOrderNodeTest.kt#L371)

</details>

<details markdown="1">
<summary>실제 승인 응답을 수용하지 않아 대기가 끝나도 같은 요청은 첫 예약과 순번을 돌려준다</summary>

**준비:** U1에게 1,000 USDT를 준비하고 결과 대기 시간을 250ms로 둔다. 요청·업무 적용·응답 전송은 실제 경로를 사용하되, 주문 클라이언트의 acceptResponse 함수가 처음에는 수신 결과를 보관하지 않도록 한다.

**실행:** ① 300 USDT 주문을 보내 결과 대기를 끝낸다. ② 노드에 승인 결과가 관찰될 때까지 기다리고 그때의 상태 의미 필드를 보관한다. ③ 같은 클라이언트에서 응답 수용을 허용한 뒤 같은 사용자·요청 ID·원본을 재전송한다.

**확인:** ①의 클라이언트 결과는 Unconfirmed이고 전송 수용 여부는 true이며 결과 미확인·같은 사용자와 요청 ID로 재확인하라는 문구가 있다. ②에서는 노드의 승인을 별도로 관찰하고 사용 가능 700·예약 300 USDT를 확인한다. ③의 응답은 그때 관찰한 최초 승인과 같고 순번은 1이다. 전후의 상태 의미 필드가 같으며 대기 주문과 최초 요청 이력은 각 한 건이다. 노드 오류는 없다.

**검사 경계:** 통제하는 지점은 클라이언트가 수신 결과를 받아들이는 함수 하나다. 실제 UDP 패킷 손실·네트워크 단절·리더 종료를 주입한 검사는 아니며, 250ms를 운영 지연 보장으로 측정하지 않는다.

[실제 검증문 보기 · DevelopmentOrderNodeTest.kt:408](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentOrderNodeTest.kt#L408)

</details>

<details markdown="1">
<summary>잘린 주문과 미지원 명령 뒤의 같은 정상 주문은 최초 순번으로 접수된다</summary>

**준비:** U1에게 1,000 USDT를 준비하고 정상 R1·O1 주문을 실제 코덱으로 바이트화한다. 하나는 끝 한 바이트를 잘라내고, 다른 하나는 헤더의 명령 종류를 지원하지 않는 99로 바꾼다.

**실행:** 두 바이트 배열을 실제 Aeron 연결로 보내고 손상·미지원 메시지 개수가 2가 될 때까지 기다린다. 그 뒤 제품 주문 클라이언트로 원래의 정상 R1·O1을 보낸다.

**확인:** 두 잘못된 입력 직후에는 주문 결과와 fatal 진단이 없고 노드 오류도 없다. 정상 주문은 승인 순번 1을 받으며 사용 가능 700·예약 300 USDT가 된다. 준비 이력·대기 주문·최초 요청 이력은 각 한 건이고 손상 메시지 개수는 계속 2다. 잘못된 입력 뒤에도 노드 오류가 없다.

**검사 경계:** 잘림 한 종류와 미지원 명령 종류 한 가지를 실제 ingress에 넣는다. 모든 Protobuf 손상 조합이나 손상된 응답은 확인하지 않으며, 잘못된 바이트 전송에는 제품 클라이언트 대신 테스트의 raw 전송 함수를 쓴다.

[실제 검증문 보기 · DevelopmentOrderNodeTest.kt:438](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentOrderNodeTest.kt#L438)

</details>

<details markdown="1">
<summary>독립 실제 노드 둘에 같은 입력을 순차 적용하면 업무 의미 필드가 같다</summary>

**준비:** 서로 다른 새 실행 폴더와 포트를 가진 실제 개발 노드 두 개를 준비한다. 두 노드는 빈 상태에서 같은 BTC-USDT·0ppm 정책과 동일한 사용자·요청·주문·원장 ID·업무 시각을 사용한다. 두 노드를 동시에 복제 구성으로 묶지 않고 하나씩 실행한다.

**실행:** 각 노드에 같은 순서를 적용한다. ① 돈 없이 R0·O0을 보내 거절받는다. ② P1로 1,000 USDT를 준비한다. ③ R1·O1을 승인받고 그대로 반복한다. ④ 800 USDT가 필요한 R2·O2를 보낸다. ⑤ 처음의 R0를 다시 보낸다. 마지막 상태를 객체 참조 대신 업무 값으로 풀어 두 실행끼리 비교한다.

**확인:** 각 실행에서 R1 반복 응답은 최초 승인과 같고 R0 반복 응답은 최초 거절과 같다. R2는 잔고 부족 사유와 부족액 100 USDT를 반환한다. 최종 사용 가능 700·예약 300 USDT, 승인 순번 1·대기 주문 한 건·준비 이력 한 건, 최초 요청 순서 R0→R1→R2를 확인한다. 두 최종 사본의 잔고·승인 순번·대기 주문 원본/마켓/정책/전후 잔고/예약/원장·준비 결과/OPENING 거래·최초 요청의 원본/문맥/결과를 의미 필드로 비교해 같음을 확인한다. 두 노드 모두 오류가 없다.

**검사 경계:** 독립된 두 단일 노드를 순차 실행해 같은 입력의 결과를 비교한다. 하나의 클러스터에 속한 사본 복제·합의·리더 선출·장애 전환·Archive 재생을 검사하지 않으며, 로컬 전송 진단이나 Aeron 로그 위치를 서로 비교하지 않는다.

[실제 검증문 보기 · DevelopmentOrderNodeTest.kt:468](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentOrderNodeTest.kt#L468)

</details>

</details>

<details markdown="1">
<summary>주문 클라이언트 · 5개</summary>

<details markdown="1">
<summary>실제 주문 main은 전송 수용 다음에 접수 결과를 표시하고 한 주문만 반영한다</summary>

**준비:** 실제 단일 Aeron 노드를 띄우고 실제 자금 클라이언트로 사용자 U1에게 1,000 USDT를 준비한다. 기본 주문은 가격 100 USDT·수량 3 BTC의 BUY이며 업무 시각과 원장 ID는 명시한다.

**실행:** 개발 주문 main에 실제 실행 폴더·포트·요청/사용자/주문 ID·방향·가격·수량·원장 ID·시각을 넘겨 호출하고 표준 출력을 보관한다. main이 끝난 뒤 실제 노드의 완성된 관찰값을 읽는다.

**확인:** TRANSPORT_ACCEPTED 결과 대기 문구가 있고 RESULT_RECEIVED가 그 뒤에 나오며, 체결·정산 완료가 아니라는 안내가 있고 UNCONFIRMED는 없다. 관찰된 Accepted의 네 식별자·순번 1을 비교한다. 사용 가능 700 USDT·예약 300 USDT, 마지막 순번 1, 준비 1건·최초 요청 1건·대기 한 건을 확인하고 주문 원본·예약액 300 USDT·원장 ID·업무 시각·분개 금액 목록 [300 USDT, 300 USDT]·노드 오류 없음도 대조한다.

**검사 경계:** main과 정상 결과의 경로는 실제 노드·통신·코어를 사용하도록 작성된 테스트다. 이 메서드는 원장 두 분개의 계정·방향·거래 종류까지 직접 비교하지는 않는다.

[실제 검증문 보기 · DevelopmentOrderClientTest.kt:56](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentOrderClientTest.kt#L56)

</details>

<details markdown="1">
<summary>다른 네 식별자와 자금 응답 손상 응답은 무시하고 실제 주문 결과를 기다린다</summary>

**준비:** 실제 노드에 U1의 5,000 USDT를 준비하고 실제 주문 클라이언트를 연결한다. 잘못된 응답은 사용자·요청·마켓·주문 식별자를 각각 바꾼 4종, 자금 준비 결과, 7바이트 응답, 잘린 본문, 미지원 거절 사유 255의 총 8종이다.

**실행:** 각 반복에서 서로 다른 요청·주문 ID로 300 USDT 주문을 실제 전송한다. 아직 결과를 읽기 전에 그 연결의 egressListener에 잘못된 응답만 직접 주입한다. 주입은 앞뒤 여유 바이트가 있는 버퍼의 offset 5 구간을 사용한다. 이후 awaitResult로 실제 노드가 보낸 올바른 응답을 읽는다.

**확인:** 주입 직후 네 식별자 불일치는 다른 응답이라는 진단, 나머지 네 종류는 응답 복원 실패 진단을 남긴다. 각 실제 결과는 Confirmed이고 현재 주문의 네 식별자와 순번 1~8을 가진 Accepted와 같다. 마지막 관찰값은 사용 가능 2,600 USDT·예약 2,400 USDT, 마지막 순번 8·대기 8건·최초 요청 8건·준비 1건이며 노드 오류가 없다.

**검사 경계:** 정상 8개 주문과 올바른 응답은 실제 통신·코어 경로다. 잘못된 바이트는 수신 파서 경계에 직접 주입했으며 실제 서버가 손상 응답을 보냈거나 네트워크가 변조했다는 시험은 아니다. 반복 8개는 한 @Test 안의 사례이며 독립 JUnit 테스트 8개가 아니다. 실행 결과는 이번 설명에서 판단하지 않았다.

[실제 검증문 보기 · DevelopmentOrderClientTest.kt:125](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentOrderClientTest.kt#L125)

</details>

<details markdown="1">
<summary>전송 종단 세 반환값은 한 번만 전송하고 수용 전 결과 미확인으로 구분한다</summary>

**준비:** AeronCluster 연결만 전송 대역으로 준비하고 offer 반환값을 NOT_CONNECTED·CLOSED·MAX_POSITION_EXCEEDED 세 종류로 각각 고정한다. 업무 코어는 이 사례에서 호출하지 않는다.

**실행:** 각 반환값에서 실제 DevelopmentOrderClient.request를 호출하고 전송 수용 알림 함수가 실행되는지도 기록한다.

**확인:** 반환은 Unconfirmed이며 알림 함수는 호출되지 않고 transportAccepted=false다. 이유에는 각 상수 이름, 연결 또는 종단이라는 구분, 같은 사용자·요청 ID·원본으로 다시 확인하라는 안내가 있다. offer는 한 번만 호출하고 pollEgress는 호출하지 않는다.

**검사 경계:** 희귀 전송 반환값 처리만 통제한 클라이언트 검사다. 실제 네트워크 장애·노드 접수·금전 상태를 검증하지 않는다. 세 반복을 테스트 정의 3개로 세지 않으며 실행 통과 여부도 여기서 추정하지 않았다.

[실제 검증문 보기 · DevelopmentOrderClientTest.kt:201](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentOrderClientTest.kt#L201)

</details>

<details markdown="1">
<summary>conductor 오류는 수용 전후를 구분하고 원인을 담은 결과 미확인으로 끝난다</summary>

**준비:** 전송 대역의 offer는 양수 123을 반환하도록 준비한다. conductor 오류 통지를 전송 수용 전과 후의 두 시점에 줄 수 있도록 연결 Context의 errorHandler를 보관한다.

**실행:** 수용 후 사례는 send 성공을 먼저 확인한다. 각 시점에서 errorHandler에 AeronException(controlled-conductor)을 전달한다. 수용 후에는 awaitResult, 수용 전에는 request로 결과를 확인한다.

**확인:** 두 사례 모두 Unconfirmed다. transportAccepted는 수용 전 false·수용 후 true이고, 이유에는 결과 미확인·AeronException·controlled-conductor가 들어 있다. offer 호출은 각각 0회·1회이며 pollEgress는 두 경우 모두 호출하지 않는다.

**검사 경계:** conductor 오류 통지의 소비와 표시만 통제한다. transportAccepted=true도 업무 승인을 뜻하지 않으며, 실제 네트워크 단절이나 노드 상태는 확인하지 않는다. 두 반복은 한 @Test의 입력이고 이번 설명에서 실행 판정을 하지 않았다.

[실제 검증문 보기 · DevelopmentOrderClientTest.kt:229](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentOrderClientTest.kt#L229)

</details>

<details markdown="1">
<summary>offer가 던진 연결 예외도 승인으로 표시하지 않고 미확인 이유를 보존한다</summary>

**준비:** 전송 대역의 offer가 AeronException(controlled-offer)을 던지게 준비한다.

**실행:** 실제 클라이언트 request를 호출한 뒤 awaitResult를 한 번 더 호출한다.

**확인:** request는 transportAccepted=false인 Unconfirmed를 반환하고 이유에 결과 미확인과 controlled-offer를 보존한다. 추가 awaitResult는 IllegalStateException이다. offer는 한 번, pollEgress는 0번 호출한다.

**검사 경계:** 클라이언트의 전송 예외 변환과 대기 상태 정리 경계다. 실제 업무 반영·서버 취소·롤백을 확인하지 않으며, 추가 awaitResult의 예외 메시지까지 비교하지는 않는다. 실행 결과는 이번 설명에서 확인하지 않았다.

[실제 검증문 보기 · DevelopmentOrderClientTest.kt:262](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentOrderClientTest.kt#L262)

</details>

</details>

<details markdown="1">
<summary>서비스 callback의 통제된 실패 · 3개</summary>

<details markdown="1">
<summary>주문 응답이 밀려도 같은 바이트만 다시 보내고 예약은 한 번만 만든다</summary>

**준비:** 실제 코어에 1,000 USDT를 준비하고, 응답 전송 반환값을 역압력→관리 처리→성공으로 통제한다.

**실행:** 앞뒤에 다른 바이트가 있는 버퍼의 정확한 주문 구간만 callback에 주고 원본 버퍼를 지운다.

**확인:** 세 번 보낸 응답 바이트가 같고 코어 접수는 한 번이다. 순번 1·사용 가능 700 USDT·예약 300 USDT·첫 업무 시각·최초 이력 1건을 확인한다.

**검사 경계:** 코어는 실제이며 callback과 전송 반환값만 통제한다. 실제 네트워크 역압력 시험은 아니다.

[실제 검증문 보기 · DevelopmentFundingAdapterTest.kt:177](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingAdapterTest.kt#L177)

</details>

<details markdown="1">
<summary>주문 응답의 종단 상한 팔로워와 닫는 세션은 예약을 취소하지 않는다</summary>

**준비:** 실제 준비 잔고와 주문을 두고 전송 종단 3종·역압력 횟수 상한·팔로워·세션 없음·닫는 세션을 각각 마련한다.

**실행:** 같은 callback 경로에 정상 주문을 적용한다.

**확인:** 전송 진단과 시도 횟수가 기대대로이고, 어떤 경우도 예약 300 USDT와 대기 주문 1건을 취소하지 않는다. 팔로워/없거나 닫힌 세션에서도 접수는 되고 응답은 보내지 않는다.

**검사 경계:** 노드 선출·실제 소켓 단절·자동 재개를 증명하지 않는다.

[실제 검증문 보기 · DevelopmentFundingAdapterTest.kt:231](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingAdapterTest.kt#L231)

</details>

<details markdown="1">
<summary>주문 반영 전 오류와 반영 중 오류는 정상 거절 대신 개발 서비스를 중단한다</summary>

**준비:** 실제 코어 자금 준비 후, 빈 원장 ID의 반영 전 오류 또는 주문 저장 한 곳의 쓰기 실패를 준비한다.

**실행:** 주문 callback을 실행하고 이후 같은 정상 주문도 다시 실행한다.

**확인:** 정상 거절 대신 종료 오류·실패 통보가 남고 이후 callback도 막힌다. 반영 중 실패는 blocked·정상 snapshot 없음이며, 반영 전 오류의 blocked=false와 구분한다.

**검사 경계:** 메모리 쓰기 한 지점만 통제한다. 전체 롤백·실제 런타임 자원 종료·복구 검사는 아니다.

[실제 검증문 보기 · DevelopmentFundingAdapterTest.kt:290](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingAdapterTest.kt#L290)

</details>

</details>

<details markdown="1">
<summary>응답 변환 · 새 검사 5개와 관련 기존 검사 2개</summary>

<details markdown="1">
<summary>실제 접수 승인은 자금 반영 뒤 작은 확인 응답으로 왕복한다</summary>

**준비:** 실제 접수 상태에 사용 가능 1,000 USDT와 300 USDT BUY 주문을 준비한다.

**실행:** 명령을 바이트로 왕복하고 실제 코어 접수 후 작은 응답도 바이트로 왕복한다.

**확인:** 응답의 사용자·R1·마켓·O1·순번 1, 잔고 700 USDT·예약 300 USDT·대기/최초 이력 각 1건·RESERVE 두 항목을 확인한다.

**검사 경계:** 통신 없이 같은 JVM의 실제 코덱·코어를 검사한다.

[실제 검증문 보기 · AdmissionResultCodecTest.kt:22](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionResultCodecTest.kt#L22)

</details>

<details markdown="1">
<summary>실제 자금 부족 거절은 당시 부족 상세를 그대로 왕복한다</summary>

**준비:** 잔고 200 USDT에서 300 USDT가 필요한 주문을 준비한다.

**실행:** 실제 코어의 거절을 작은 응답으로 변환하고 되읽는다.

**확인:** 필요 300·현재 200·부족 100 USDT를 보존하고 돈·대기·승인 순번은 그대로이며 최초 거절만 기록됐는지 확인한다.

**검사 경계:** DB·실제 UDP 통신은 없다.

[실제 검증문 보기 · AdmissionResultCodecTest.kt:39](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionResultCodecTest.kt#L39)

</details>

<details markdown="1">
<summary>설정 불일치 사유 9는 독립 고정 바이트로 읽고 같은 바이트로 보낸다</summary>

**준비:** 구현 인코더를 호출하지 않고 사유 9와 네 식별자를 담은 고정 16진수 바이트를 준비한다.

**실행:** 바이트를 읽어 응답 객체로 만들고 다시 인코딩한다.

**확인:** 사유 9·각 식별자·부족 상세 없음이 맞고 원래 고정 바이트와 같아야 한다.

**검사 경계:** 설정 비교 자체나 네트워크 동작은 검사하지 않는다.

[실제 검증문 보기 · AdmissionResultCodecTest.kt:62](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionResultCodecTest.kt#L62)

</details>

<details markdown="1">
<summary>기존 도메인의 여덟 사유는 공개 응답 사유로 명시적으로 변환한다</summary>

**준비:** 기존 도메인 사유 8개와 각각의 공개 응답 사유를 명시적으로 짝지어 둔다.

**실행:** 각 코어 거절 결과를 공개 응답으로 변환한다.

**확인:** 빠진 도메인 사유가 없고 각 매핑·부족 상세를 정확히 유지한다.

**검사 경계:** 도메인 계산이 각 거절을 내는 조건을 여기서 다시 검사하지 않는다.

[실제 검증문 보기 · AdmissionResultCodecTest.kt:78](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionResultCodecTest.kt#L78)

</details>

<details markdown="1">
<summary>설정 불일치 사유 9에 부족 상세를 붙이면 송신과 수신 모두 거절한다</summary>

**준비:** 정상 사유 9 응답과 숫자 관계가 유효한 부족 상세를 준비한다.

**실행:** 객체에 상세를 붙여 인코딩하고, 별도 Protobuf 본문에 상세를 붙여 디코딩한다.

**확인:** 송신과 수신 모두 AdmissionMessageException으로 거절한다.

**검사 경계:** 잘못된 금액 때문에 실패하는 사례가 아니라 사유와 상세 조합을 검사한다.

[실제 검증문 보기 · AdmissionResultCodecTest.kt:107](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionResultCodecTest.kt#L107)

</details>

<details markdown="1">
<summary>새 사유 다음의 미지원 숫자 10은 정상 식별자와 부족 상세 없이도 거절한다</summary>

**준비:** 네 식별자는 정상이고 부족 상세는 없는 사유 10 고정 바이트를 준비한다.

**실행:** 새 결과 코덱으로 읽는다.

**확인:** 지원하지 않는 사유를 거절한다.

**검사 경계:** 새 사유가 추가돼도 임의 숫자를 허용하지 않는 변환 검사다.

[실제 검증문 보기 · AdmissionResultCodecTest.kt:131](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionResultCodecTest.kt#L131)

</details>

<details markdown="1">
<summary>기존 도메인 이름 매핑 검증 경계는 사유 8을 읽고 새 사유 9는 거절한다</summary>

**준비:** 같은 정상 식별자의 사유 8과 9 고정 바이트를 준비한다.

**실행:** #97의 기존 도메인 이름 매핑 판정만 고정한 작은 함수에 각각 넣는다.

**확인:** 8은 내용 충돌로 읽고 9는 변환 오류로 거절한다.

**검사 경계:** 구버전 런타임 전체·혼합 배포를 시험한 것은 아니다.

[실제 검증문 보기 · AdmissionResultCodecTest.kt:138](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionResultCodecTest.kt#L138)

</details>

</details>

<details markdown="1">
<summary>기존 요청 조회 · 새 검사와 차단 회귀 3개</summary>

<details markdown="1">
<summary>R13 요청 존재 조회는 승인과 거절을 찾고 사용자별 키와 상태를 보존한다</summary>

**준비:** 실제 계산기를 사용하는 메모리 상태에 user-1의 사용 가능 1,000 USDT·예약 0 USDT를 넣는다. 다른 사용자는 other-user이며 초기 요청 이력은 비어 있다.

**실행:** 먼저 accepted·rejected 키를 조회한다. 이어 user-1의 accepted 요청은 승인하고 rejected 요청은 수량 0으로 정상 거절시킨다. 기존 두 키·없는 키·다른 사용자 키를 조회한다. 마지막으로 other-user의 accepted라는 요청 ID에 수량 0 거절을 저장한 후 두 사용자의 존재 여부를 다시 조회한다.

**확인:** 초기 두 조회는 false, 저장한 user-1의 승인·거절 키는 모두 true다. missing과 아직 저장하지 않은 다른 사용자 키는 false다. other-user의 accepted 키를 별도로 기록한 뒤에는 두 사용자의 accepted 키가 각각 true이고 other-user의 rejected 키는 여전히 false다. 조회 전후 잔고·마지막 순번·대기·요청 원본/문맥/결과를 비교하는 assertSnapshotValue가 같고 isBlocked=false다.

**검사 경계:** hasRequest는 키 존재만 알려 주며 accepted라는 문자열이 승인 결과를 뜻하지 않는다. 다른 사용자의 그 키에 저장한 결과는 수량 0 거절이다. 새 조회가 원본 비교나 결과 재판정을 한다고 설명하지 않는다. 순수 메모리·단일 실행자 검사이며 이번 설명에서는 재실행·독립 리뷰 판정을 하지 않았다.

[실제 검증문 보기 · OrderAdmissionRequestTest.kt:601](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L601)

</details>

<details markdown="1">
<summary>R11 승인 반영의 각 쓰기 전후 Exception과 Error는 재전송과 정상 조회를 차단한다</summary>

**준비:** 각 반복에서 이전 승인 1건과 정상 거절 1건을 실제 코어에 저장하고, 사용 가능 700 USDT·예약 300 USDT인 관찰값을 보관한다. 승인 반영의 balances·records·transactionIds·sourceEventIds·requestRecords 다섯 쓰기에 대해 쓰기 전/후와 Exception/Error를 조합한다(총 20조합).

**실행:** 내부 쓰기를 테스트 장치로 감싸 지정한 시점에 준비한 실패 객체를 던지도록 한 뒤 새 승인 주문을 접수한다. 이후 기존 접수·재전송·정상 조회와 새로 보강한 네 hasRequest 호출을 시도한다.

**확인:** 최초 던짐은 지정한 실패 객체와 같은 참조이고 isBlocked=true다. 기존 승인 키·기존 거절 키·없는 키·다른 사용자 키의 hasRequest 네 호출이 모두 IllegalStateException이다(공유 helper 793~796행). 기존 접수·snapshot·마켓별 대기도 차단하며 실패 전에 돌려준 잔고·순번 1·대기 1건·요청 2건은 보존된다.

**검사 경계:** 이번 보강은 기존 반영 실패 검사에 좁은 요청 조회의 차단 assertion을 추가한 것이다. 통제된 private 쓰기 실패를 사용하며 실제 JVM 종료·분산 장애·롤백을 검증하지 않는다. 20조합은 한 @Test 안의 반복이다. 현재 테스트 소스를 설명했고 실행 통과나 독립 리뷰 판정을 새로 주장하지 않았다.

[실제 검증문 보기 · OrderAdmissionRequestTest.kt:535](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L535)

</details>

<details markdown="1">
<summary>R11 정상 거절의 최초 기록 쓰기 전후 Exception과 Error도 객체를 차단한다</summary>

**준비:** 각 반복에서 이전 승인·정상 거절과 그 관찰값을 준비한다. 신규 IOC 주문은 정상 거절될 입력이지만, 그 최초 거절을 requestRecords에 쓰기 전/후 Exception/Error를 던지도록 준비한다(총 4조합).

**실행:** 거절 요청을 접수해 최초 결과 기록 쓰기에서 실패시킨 뒤, 신규/기존 요청과 정상 상태 조회·hasRequest 네 경우를 시도한다.

**확인:** 지정한 실패 객체가 그대로 전달되고 isBlocked=true다. hasRequest는 기존 승인·거절 키, 없는 키, 다른 사용자 키 모두 IllegalStateException이며 기존 정상 조회·재전송도 차단된다. 실패 전 관찰값의 사용 가능 700 USDT·예약 300 USDT·순번 1·대기 1건·이전 요청 2건은 그대로다.

**검사 경계:** 정상 거절도 최초 결과 저장 중 실패하면 객체를 계속 사용할 수 없다는 경계다. 실패 뒤 실제 내부 요청 map을 롤백했다고 확인하지 않으며, 새 hasRequest assertion은 공유 helper 793~796행에 있다. 4조합을 독립 JUnit 테스트 수에 더하지 않는다. 실행·독립 리뷰 상태는 이 설명에서 판단하지 않았다.

[실제 검증문 보기 · OrderAdmissionRequestTest.kt:546](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L546)

</details>

</details>

**기존 메시지 회귀의 조정:** AdmissionMessageBoundaryTest·AdmissionProtocolFlowTest·DevelopmentBalanceCodecTest·DevelopmentBalanceProtocolFlowTest의 기존 기대 거절 사유를 전송 전용 Reason으로 비교하도록 맞췄다. 기존 숫자 1~8, 고정 바이트, 자금 준비 종류 4~6과 손상 거절 계약은 유지한다. 해당 회귀도 아래 118개 안에서 통과했다.

**기존 노드 입력 사례의 조정:** DevelopmentFundingNodeTest의 미지원 메시지는 이제 지원하는 정상 주문 대신 종류 2의 결과 프레임을 입력으로 사용한다. 정상 충돌·손상·미지원 입력 뒤에도 다음 자금 준비가 가능한 기존 검증 의도는 유지한다.

</details>


<details markdown="1">
<summary>6. 실제 실행 결과와 독립 리뷰</summary>

통합 기준은 d6d1db401eb7913cb944bd33512275e95d7787a4다. 아래는 이 작업 폴더의 로컬 실행 결과이며 PR·CI·병합 결과와 구분한다. 원격 진행은 #98에 연결한 PR에서 확인한다. 모든 표의 실패·오류·skip은 0이다.

| 실행 범위 | 결과 |
| --- | --- |
| app-api의 trading Aeron·codec 전체 선택 | 118개 통과 |
| domain-trading | 100개 통과 |
| architecture-tests | 375개 통과 |
| verifyArchitectureReport | 필수 P01~P09 통과 |
| 전체 ktlintCheck | 통과 |
| app-api bootJar | 통과. 기존 Spring HTTP 실행 진입점 유지 |

HTML 보고서는 실제 구현 폴더의 `app-api/build/reports/tests/test/index.html`·`domain-trading/build/reports/tests/test/index.html`·`architecture-tests/build/reports/tests/test/index.html`에 있다. 수치 원본은 각 모듈의 `build/test-results/test/TEST-*.xml`이다. 실행 로그·고정 소스·리뷰 결과는 로컬에 보존했다. 이 문서의 코드·테스트 링크는 저장소 상대 경로이며, PR 브랜치에서도 해당 소스를 읽을 수 있다.

~~~~sh
cd /Users/0chord/Desktop/exchange-core-aeron-98
./gradlew -p /Users/0chord/Desktop/exchange-core-aeron-98 :app-api:test --tests 'com.exchange.core.api.trading.infrastructure.aeron.*' --tests 'com.exchange.core.api.trading.infrastructure.codec.*' ktlintCheck
./gradlew -p /Users/0chord/Desktop/exchange-core-aeron-98 :domain-trading:test :architecture-tests:test :architecture-tests:verifyArchitectureReport :app-api:bootJar
~~~~

**실패에서 통과로:** 처음에는 정상 주문을 기존 노드가 받지 못해 결과 대기가 끝났고, 고정 사유 9 바이트도 기존 코덱이 거절했다. 연결·사유 매핑 후 두 사례가 통과했다. 이후 통합 검사에서는 빈 OrderId를 생성하는 준비 코드가 메시지 전송 전에 실패했다. 전송은 가능하지만 코어 제한을 넘는 65글자 ID로 고쳐, 의도한 기존 키의 식별자 검사 순서를 실제로 확인했다. 제품의 기대 결과나 검사 범위를 낮추지 않았다.

**실제 실행 task 시연:** 새 실행 루트에서 노드 실행 → P1 자금 준비 → R1 주문 → 같은 R1 순서로 실행했다. 두 주문 실행 모두 TRANSPORT_ACCEPTED 뒤 RESULT_RECEIVED·승인 순번 1을 받았다. 같은 요청이 새 접수가 되지 않음을 확인했다. 700 USDT·예약 300 USDT와 원장은 실제 노드 테스트가 상태 사본으로 대조한다. 시연 후 그 노드 프로세스만 정상 종료했으며 기록 폴더는 보존했다.

**독립 리뷰:** 작성 대화를 상속하지 않은 별도 에이전트가 기준 커밋과 고정 review-snapshot-2의 소스·명세·호출 경로를 읽고 직접 실행했다. 선택 app-api 140개, domain-trading 100개, 구조 375개, domain-fee 37개, domain-order 53개, P01~P09와 전체 lint가 통과했다. 선택 범위가 다른 118개와 140개를 합쳐 고유 테스트 수로 세지 않는다. 실행 뒤 고정 소스 해시가 일치했고, 검토한 범위에서 미해결 결함은 발견되지 않았다. 앞으로 발생할 모든 버그가 없다는 뜻은 아니다.

리뷰에서 snapshot1의 빈 OrderId 준비 실패를 확인했고, snapshot2에서 65글자 입력과 관련 회귀의 통과를 재확인했다. 그 뒤 제품·테스트 코드는 바꾸지 않고 이 실행 설명·명세의 진행 상태·연결 계획만 정리했다. 검토 대상과 실행 로그·해시는 로컬 issue98-implementation 결과 폴더에 보존한다.

**확인하지 않은 것:** 전체 HTTP·DB 제품 테스트를 이번에 다시 실행하지 않았다. 실제 주문 통신은 검사했지만 네트워크 분할·물리 호스트 장애·재시작 복구·세 노드 HA·Archive 원문 대조·성능 목표는 검증하지 않았다. 주문의 내부 오류는 통제 callback으로 확인하고, 런타임 중단은 공통 소유자 경로의 기존 자금 준비 오류 테스트로 확인했다.

</details>


<details markdown="1">
<summary>변경 파일이 여러 개인 이유</summary>

| 변경 묶음 | 역할 |
| --- | --- |
| 기존 Node·Adapter + 새 OrderClient·실행 task | 같은 노드에 주문 메시지를 연결하는 개발 실행 코드 |
| 기존 OrderAdmissionState·요청 테스트 | 기존 키 여부를 차단 검사 후 조회하는 작은 API와 회귀 |
| 기존 proto·결과 payload/codec | 전송 전용 거절 사유 9 추가. 도메인 거절 종류는 그대로 유지 |
| 기존 메시지 테스트 5파일 | 같은 전송 숫자·기존 고정 메시지 보존, wire 사유 타입으로 비교 조정 |
| 통신·클라이언트·callback 테스트 | 실제 성공/거절과 통제한 희귀 실패를 구분해 검증 |
| 기존 자금 설명·연결 계획 + 현재 명세/설명 | #97에서 #98로 이어지는 흐름·실행·테스트 근거 |

새 서버나 프레임워크 계층을 파일 수만큼 만든 것이 아니다. 이번에 바꾸는 제품 목적은 주문 접수 연결 한 가지다. 기존 계산·재전송 판정을 복제하지 않는다.

</details>

**후속:** #99는 실제 Archive 원문과 입력 순서를 대조한다. 세 노드 정상 복제 대조는 #89이며, 재시작 복구·장애 전환·Kafka/DB 적재·성능 보장은 아직 없다. 로컬 구현·검증, 독립 AI 리뷰, 사람의 이해, PR·CI·병합을 각각 구분한다.
