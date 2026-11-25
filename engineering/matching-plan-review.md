# #126 · 먼저 계산하고, 나중에 주문장에 반영하기

<div class="plan126-purpose">
<strong>새 주문의 체결·대기 결과를 먼저 계산합니다.</strong>
<p>이전에는 계산하며 주문장을 바꿨습니다. 이제 후보를 만들고 검사한 뒤 <code>apply</code>할 때 바꿉니다.</p>
</div>

<div class="plan126-example" aria-label="준비와 적용의 차이">
<p><b>SELL 90×1 · 95×1</b> 대기 중, <b>BUY 100×3</b></p>
<div><span>준비·검증</span><strong>체결 2건 + BUY 1개 대기라는 후보</strong><small>실제 SELL 2건·마지막 순번 2는 그대로</small></div>
<div><span>적용</span><strong>SELL 소진 · BUY 100×1 대기</strong><small>이때 사용 ID와 마지막 순번 5가 반영됨</small></div>
</div>

<div class="plan126-flow" aria-label="전체 흐름">
<a href="#flow-prepare"><small>1</small>가격·FIFO로 읽기</a>
<a href="#flow-validate"><small>2</small>현재 기준 검사</a>
<a href="#flow-apply"><small>3</small>한 번 반영</a>
<a href="#flow-boundaries"><small>4</small>실패·취소 구분</a>
</div>

<p class="plan126-caption">후보는 버려도 됩니다. 오래됨·다른 엔진·재적용·순번 부족이면 첫 쓰기 전에 거절합니다. <b>이번 범위: 매칭 후보 계산·검증·적용. 자금 정산과 Aeron 자동 연결은 후속 작업입니다.</b></p>

## 어디부터 읽을까

첫 흐름은 **후보를 만들어도 실제 상태가 바뀌지 않는 이유**입니다. 아래 단위를 하나씩 펼치면 실제 코드와 테스트로 이어집니다. 생성·검증, 현재 기준 거절, 적용, 취소와 overflow가 이번 변경의 전체 지도입니다.

<details markdown="1" id="flow-prepare" open><summary>1. 후보의 체결 2건은 아직 실제 체결이 아니다</summary>

S1 SELL 90×1, S2 SELL 95×1은 이미 처리돼 순번 1·2와 사용 ID를 갖고 있습니다. 새 B1 BUY 100×3의 <code>prepareSubmit</code>은 최저 ask부터 S1→S2를 읽고, 후보에 순번 3·4의 체결과 5의 B1 잔량 대기를 담습니다.

이때 엔진은 실제 maker에 <code>fill</code>하거나 제거하지 않습니다. 없는 마켓도 조회만 하므로 빈 book map을 만들지 않습니다. 후보에 적힌 순번은 예상 값이며 아직 발급한 상태가 아닙니다. 같은 상태에서 두 번 준비하면 같은 결과를 계산합니다. 후보를 버릴 때 해제할 예약도 없습니다.

| 시점 | 실제 대기 주문 | 실제 마지막 순번 | B1 사용 ID |
| --- | --- | ---: | --- |
| 준비 전 | S1 90×1 → S2 95×1 | 2 | 없음 |
| 준비·검증 뒤 / 후보 폐기 | 그대로 | 2 | 없음 |
| 유효 후보 apply 뒤 | B1 BUY 100×1 | 5 | 있음 |

읽는 순서는 가격에 따라 정렬된 반대편 book, 같은 가격의 삽입 순서입니다. BUY는 낮은 ask, SELL은 높은 bid를 먼저 보고 체결 가격은 maker 가격을 씁니다. BUY 한도보다 높은 ask, SELL 한도보다 낮은 bid에 도달하면 계산을 멈춥니다. maker는 먼저 기다리던 주문, taker는 방금 들어온 주문입니다.

<details markdown="1"><summary>판단을 만드는 짧은 실제 코드</summary>

[MatchingEngine.kt:41](../domain-matching/src/main/kotlin/com/exchange/core/matching/MatchingEngine.kt#L41) · 원문 SHA-256 `8f75c01a09d53132a754b9854caa9cb6e9f2a591951b8365699606eb759824aa`

```kotlin
        val makers = orderBooks[command.marketId]?.oppositeOrders(command.side) ?: emptySequence()
        for (maker in makers) {
            if (remaining.isZero() || !priceCrosses(command.side, command.price, maker.price)) break
            val quantity = minOf(remaining, maker.remainingQuantity)
            fills +=
```

전체 읽기 순회: [OrderBook.kt:149](../domain-matching/src/main/kotlin/com/exchange/core/matching/OrderBook.kt#L149)

같은 가격의 FIFO 읽기: [PriceLevel.kt:67](../domain-matching/src/main/kotlin/com/exchange/core/matching/PriceLevel.kt#L67)

<code>Sequence</code>는 순서대로 읽는 경로입니다. 실제 내부 주문 참조는 이 순회에만 쓰고, 후보에는 주문의 ID·방향·가격·원수량·기준 잔량·체결량을 값으로 옮깁니다. 제품은 주문장 전체를 깊게 복사하지 않습니다.

</details>

대표 테스트는 [E1 후보 폐기 뒤 기존 process](#test-E1)와 [C1 빈 새 마켓의 내부 값](#test-C1)입니다. E1은 뒤의 정상 처리·취소 결과로, C1은 map까지 포함한 깊은 snapshot으로 무변경을 각각 관측합니다.

</details>

<details markdown="1" id="flow-validate"><summary>2. 만든 엔진의 현재 주문장에 아직 맞는지 검사한다</summary>

<code>validate(plan)</code>은 후보의 발급 엔진, 해당 마켓의 마지막 순번, 사용하지 않은 주문 ID를 봅니다. 현재 가격·FIFO 위치의 maker와 후보의 값·수량·이벤트를 맞추고, 체결량 합+대기량=원수량인지 검사합니다. 별도 검증 성공은 예약이 아닙니다. <code>apply</code>가 같은 검증을 다시 하므로 검증 사이의 실제 처리를 놓치지 않아야 합니다.

같은 엔진의 같은 마켓에서 정상 SUBMIT·CANCEL 또는 CANCEL 거절 이벤트가 처리되면 마지막 순번이 전진해 이전 후보는 오래됩니다. 다른 마켓의 진행과 상태를 바꾸지 않은 SUBMIT 예외는 구분합니다.

| 사이에 일어난 일 | 이전 후보 | 이유와 거절 뒤 상태 |
| --- | --- | --- |
| 같은 마켓 정상 submit | 거절 | 실제 순번이 전진함. 새 주문 처리 직후 상태 유지 |
| 정상 취소 | 거절 | maker 제거와 취소 이벤트 순번 반영. 취소 직후 상태 유지 |
| 없는 주문·다른 소유자의 취소 | 거절 | book은 그대로여도 거절 이벤트가 순번을 사용함 |
| 중복 ID·MARKET·IOC submit 예외 | 계속 유효 | 실제 상태와 순번을 바꾸기 전에 예외로 끝남 |
| 다른 마켓의 submit·취소·취소 거절 | 계속 유효 | 순번과 주문장은 마켓별로 관리됨 |
| 다른 엔진이 발급한 후보 | 거절 | IllegalArgumentException. 양쪽 상태와 원래 엔진의 후보는 보존 |
| 이미 apply한 같은 후보 | 거절 | IllegalStateException. 첫 적용 뒤 상태 유지 |

같은 상태에서 만든 P/Q는 둘 다 사전 검증할 수 있습니다. Q를 먼저 적용하면 Q가 실제 처리이며 P는 오래됩니다. 생성 순서로 체결 순서를 선점하지 않습니다. 발급 토큰은 로컬 객체 비교에만 쓰고 이벤트·거래 ID·복제 결과에 넣지 않습니다. 후보는 저장·전송·복구 포맷이 아닙니다.

<details markdown="1"><summary>발급자·순번·수량의 실제 검사 보기</summary>

발급자·마켓 순번·미사용 ID 검사: [MatchingEngine.kt:85](../domain-matching/src/main/kotlin/com/exchange/core/matching/MatchingEngine.kt#L85)

수량 보존 검사: [MatchingEngine.kt:124](../domain-matching/src/main/kotlin/com/exchange/core/matching/MatchingEngine.kt#L124)

현재 가격·FIFO 순서의 cursor를 한 칸씩 전진시키며 후보가 기억한 maker와 대조합니다. 같은 maker ID가 존재하는지만 확인하지 않습니다. 실제 취소 인덱스도 그 주문 객체를 가리키는지 확인합니다. 잔량 대기가 있으면 다음 주문이 체결 가능한 상대인지 한 번 더 봅니다. BUY 100의 후보가 대기를 계산했는데 뒤에 SELL 95가 새로 생겼다면 후보는 현재 판단과 맞지 않습니다.

[MatchingEngine.kt:95](../domain-matching/src/main/kotlin/com/exchange/core/matching/MatchingEngine.kt#L95) · 원문 SHA-256 `8f75c01a09d53132a754b9854caa9cb6e9f2a591951b8365699606eb759824aa`

```kotlin
        val makerCursor = (book?.oppositeOrders(command.side) ?: emptySequence()).iterator()
        var filled = 0L
        val makers = hashSetOf<OrderId>()
        plan.fills.forEachIndexed { index, fill ->
            check(makerCursor.hasNext()) { "matching plan maker is missing" }
            val maker = makerCursor.next()
            check(fill.matches(maker)) { "matching plan maker or price/FIFO position changed" }
            check(book?.find(fill.orderId) === maker) { "matching plan maker index changed" }
            check(makers.add(fill.orderId) && fill.orderId != command.orderId) { "invalid matching plan maker" }
            check(
```

잔량 대기 앞의 추가 상대 검사: [MatchingEngine.kt:126](../domain-matching/src/main/kotlin/com/exchange/core/matching/MatchingEngine.kt#L126)

</details>

연결 테스트: [C3 사전 검사 뒤 변경](#test-C3), [C4 두 후보](#test-C4), [C5~C7 세 취소](#test-C5), [C8 무변경 submit 예외](#test-C8), [C9 다른 마켓](#test-C9), [C10 다른 엔진](#test-C10), [C11 재적용](#test-C11), [C14 maker 기준 잔량](#test-C14), [C20 같은 가격 FIFO 위치](#test-C20), [C21 앞에 생긴 유리한 maker](#test-C21), [C22 대기 판단 뒤 새 상대](#test-C22).

</details>

<details markdown="1" id="flow-apply"><summary>3. apply가 실제 잔량·대기 순서·ID·순번을 바꾼다</summary>

검증이 모두 끝나면 B1 사용 ID를 기록하고, 계획한 maker 잔량을 줄입니다. 전량 체결 maker만 book과 취소 인덱스에서 제거합니다. 부분 체결 maker는 같은 객체의 잔량을 줄여 기존 FIFO 위치를 보존합니다. B1 잔량이 있으면 새 대기 주문을 넣고 마지막 순번을 후보의 끝 값으로 바꿉니다.

반환값은 후보가 이미 보관한 불변 이벤트 목록입니다. 계획 생성 시 입력 목록을 복사해 수정할 수 없게 보관합니다. apply는 그 동일한 events 목록을 반환하며, 이후 실제 주문의 잔량이 바뀌어도 과거 이벤트 값은 바뀌지 않습니다. 후보가 가변 <code>BookOrder</code>를 보관하지 않기 때문입니다.

<details markdown="1"><summary>첫 쓰기와 불변 값이 있는 실제 코드</summary>

apply 첫 동작은 validate(plan)입니다: [MatchingEngine.kt:147](../domain-matching/src/main/kotlin/com/exchange/core/matching/MatchingEngine.kt#L147)

[MatchingEngine.kt:157](../domain-matching/src/main/kotlin/com/exchange/core/matching/MatchingEngine.kt#L157) · 원문 SHA-256 `8f75c01a09d53132a754b9854caa9cb6e9f2a591951b8365699606eb759824aa`

```kotlin
        seenOrderIds.getOrPut(command.marketId) { HashSet() }.add(command.orderId)
        for (fill in plan.fills) {
            val maker = checkNotNull(book.find(fill.orderId))
            maker.fill(fill.filledQuantity)
            if (maker.isFilled()) book.removeFilledOrder(maker)
        }
        if (restingOrder != null) book.addRestingOrder(restingOrder)
        lastEngineSequences[command.marketId] = plan.lastEngineSequence
        return plan.events
    }
```

후보 값 복사·불변 이벤트: [SubmitOrderPlan.kt:24](../domain-matching/src/main/kotlin/com/exchange/core/matching/SubmitOrderPlan.kt#L24)

</details>

기존 <code>process(SubmitOrderCommand)</code>도 <code>apply(prepareSubmit(command))</code>을 호출합니다. 가격·FIFO 계산 알고리즘을 두 벌 유지하지 않으면서 기존 호출자는 즉시 처리 결과를 받습니다. [E2 실제 주문 제거·잔량](#test-E2), [C12 부분 maker FIFO](#test-C12), [C13 SELL 전량 체결](#test-C13), [C15 과거 이벤트](#test-C15)가 이를 연결합니다.

예상된 후보 오류는 첫 쓰기 전에 거절합니다. 실제 쓰기 도중 JVM Error 같은 예상 밖 오류를 SQL 트랜잭션처럼 되돌리는 보장은 없습니다. 이번 기능은 매칭에만 적용되며 돈·예약·수수료·정산과의 공동 반영은 #127~129의 후속 범위입니다.

</details>

<details markdown="1" id="flow-boundaries"><summary>4. 취소는 즉시 처리하고, 전체 순번이 부족하면 쓰기 전에 멈춘다</summary>

취소 후보 API는 없습니다. <code>process(CancelOrderCommand)</code>가 바로 기존 성공/거절 이벤트를 만듭니다. 성공이면 남은 수량만 취소하고, 없는 주문·다른 소유자이면 book을 유지합니다. 세 경우 모두 이벤트 순번 하나를 사용하므로 같은 마켓의 예전 새 주문 후보를 오래되게 만듭니다.

순번은 마지막 발급값+1부터 계산합니다. <code>Math.addExact</code>가 Long 범위를 넘으면 ArithmeticException을 내고 음수로 감지 않습니다. 새 주문은 필요한 모든 이벤트의 순번을 후보 계산 중 먼저 확인합니다. 취소도 순번 하나를 확보한 뒤 book을 찾거나 삭제합니다.

| 마지막 값 / 필요한 이벤트 | 결과 | 실제 상태 |
| --- | --- | --- |
| MAX−3 / 3개 | MAX−2, MAX−1, MAX 허용 | apply 후에만 반영 |
| MAX−2 / 3개 | 후보 계산부터 ArithmeticException | maker·taker·ID·순번 그대로 |
| MAX / 다음 submit 또는 취소 | ArithmeticException | 정상 취소 대상도 지우지 않고 빈 book도 만들지 않음 |

<details markdown="1"><summary>취소와 순번 계산 코드</summary>

취소는 Math.addExact로 순번을 확보한 다음 book을 찾습니다: [MatchingEngine.kt:168](../domain-matching/src/main/kotlin/com/exchange/core/matching/MatchingEngine.kt#L168)

[MatchingEngine.kt:215](../domain-matching/src/main/kotlin/com/exchange/core/matching/MatchingEngine.kt#L215) · 원문 SHA-256 `8f75c01a09d53132a754b9854caa9cb6e9f2a591951b8365699606eb759824aa`

```kotlin
    private fun sequenceAt(
        last: Long,
        eventIndex: Int,
    ): Long = Math.addExact(last, eventIndex.toLong() + 1)
```

</details>

[C17 MAX까지 적용](#test-C17), [C18 세 이벤트 전체 부족](#test-C18), [C19 빈 마켓 취소](#test-C19)가 상태 전후 값을 비교합니다. [C16 최대 수량](#test-C16)은 순번 경계와 별개로 체결 수량의 정확한 합을 확인합니다.

</details>

<details markdown="1" id="files-api"><summary>변경 6파일의 역할과 공개 API</summary>

| API | 호출자가 받는 것 | 실제 상태 변화 |
| --- | --- | --- |
| prepareSubmit(command): SubmitOrderPlan | 원본 command, 읽기 전용 events, first/lastEngineSequence | 없음. 버릴 수 있음 |
| validate(plan): Unit | 유효하면 정상 반환, 위반이면 예외 | 없음 |
| apply(plan): List&lt;MatchingEvent&gt; | 사전 계산한 이벤트 | 재검증 후 book·사용 ID·마지막 순번 변경 |
| process(command): List&lt;MatchingEvent&gt; | 기존 즉시 처리 결과 | SUBMIT은 준비+적용, CANCEL은 즉시 처리 |

계획 생성자는 모듈 내부입니다. 공개 <code>copy</code>는 없고, 외부 호출자는 원본 명령·이벤트·순번만 읽습니다. 가변 book·가격 레벨·maker 참조와 내부 fills는 공개하지 않습니다.

| 실제 변경 파일 | 분류와 연결된 역할 | 전체 내용 SHA-256 |
| --- | --- | --- |
| [MatchingEngine.kt](../domain-matching/src/main/kotlin/com/exchange/core/matching/MatchingEngine.kt#L1) | 제품 실행 코드 · 후보 계산→현재 기준 검사→한 번 반영, 기존 process와 취소 | `8f75c01a09d53132a754b9854caa9cb6e9f2a591951b8365699606eb759824aa` |
| [OrderBook.kt](../domain-matching/src/main/kotlin/com/exchange/core/matching/OrderBook.kt#L1) | 제품 실행 코드 · 가격순 반대편 주문 읽기, 기존 취소 인덱스 | `13b5ae8d975ea42f719f4835dc17b20b4dec129178f32d002c2548e3b991e516` |
| [PriceLevel.kt](../domain-matching/src/main/kotlin/com/exchange/core/matching/PriceLevel.kt#L1) | 제품 실행 코드 · 같은 가격의 FIFO 읽기 | `be7f055dc3fd5c855a01fc191d1dc953beddcd794d3c3ac3e754f5708e727e60` |
| [SubmitOrderPlan.kt](../domain-matching/src/main/kotlin/com/exchange/core/matching/SubmitOrderPlan.kt#L1) | 제품 실행 코드 · 엔진 발급 후보, maker 값 복사, 불변 이벤트 | `c2ad447e99c015f5c5dea72b2379824ccb89f58d026c704f1fc2e989d5c6cbb4` |
| [MatchingPlanContractTest.kt](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L1) | 순수 도메인 테스트 · 무변경/거절/순번/불변성과 내부 값 snapshot | `9081b0d50ddf8246954d3ffd3000f5fa9fd92d0338918285fef8c95ea07ddc23` |
| [MatchingPlanExampleTest.kt](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanExampleTest.kt#L1) | 순수 도메인 테스트 · SELL 90·95 + BUY 100×3의 기존 API 관측 | `d8bbd1c7c384e5d46990d7dc54df2edf159d9ec7dee8352a6ef03c24ef801b59` |

새 모듈·모듈 의존·운영 테스트 gate·HTTP endpoint는 추가하지 않았습니다. BookOrder, MatchingCommand, MatchingEvent, 기존 MatchingResultState는 필요한 협력자이며 이번 변경 파일은 아닙니다. #51 결과 등록을 자동 호출하지 않습니다. 기존 worker는 process 계약을 사용하므로 새 준비 API를 직접 호출하지 않습니다.

</details>

<details markdown="1" id="tests"><summary>테스트를 자연어로 읽기 · 새 테스트 24개</summary>

새 파일은 ExampleTest 2개와 ContractTest 22개입니다. 둘 다 실제 MatchingEngine·OrderBook·PriceLevel·BookOrder를 메모리에서 사용하는 **순수 도메인 테스트**입니다. @Test 메서드 수이며 파라미터 공급자는 없습니다. C8의 세 거절 입력과 C17의 세 취소 입력은 한 테스트 안의 반복입니다. DB·HTTP 서버·실제 Aeron은 사용하지 않습니다.

**읽는 순서:** E1의 상황→실행→결과부터 읽습니다. 깊은 내부 값 관측이 궁금할 때 C1과 아래 공통 준비를 이어 봅니다. 각 설명의 Then은 연결한 실제 assertion이 관측하는 범위입니다.

<details markdown="1" id="test-helper"><summary>공통 준비: 왜 reflection으로 깊은 snapshot을 만들까</summary>

공개 이벤트만으로는 “없는 마켓을 준비했는데 빈 map이 생겼는가”, “취소 인덱스와 FIFO가 그대로인가”를 모두 볼 수 없습니다. 테스트의 <code>readField</code>는 reflection으로 private 필드를 읽고, <code>snapshot</code>이 다음 값을 새 불변 데이터 값으로 옮깁니다.

- 마켓별 book 존재 여부, 양쪽 가격 레벨 순서, 가격 안의 주문 순서.
- 주문 ID·사용자·방향·가격·원수량·현재 잔량.
- 취소 조회용 orderIndex의 방향·가격, 사용했던 주문 ID 집합, 마지막 순번 map.

실제 BookOrder 참조를 before에 보관하면 뒤에서 같은 객체가 바뀔 때 before도 바뀌어 잘못 통과할 수 있습니다. 이 helper는 문자열·숫자·값 객체와 새 목록/map으로 옮겨 **전후 실제 값**을 비교합니다. 이 깊은 복사는 테스트 관측에만 쓰며 제품이 매 주문 전체 주문장을 복사한다는 뜻이 아닙니다.

<code>assertRejectedWithoutChanges</code>는 apply 예외→snapshot 동일→validate 예외→snapshot 동일의 네 단계를 확인합니다. apply를 먼저 호출하므로 사전 validate가 오류를 내서 우연히 멈춘 것을 apply의 보장으로 오해하지 않습니다. 예외만 관측하는 검사가 아닙니다. <code>assertListCannotBeChanged</code>는 mutable 인터페이스로 변환 가능하면 첫 항목 교체·추가·clear를 각각 시도해 UnsupportedOperationException을 확인합니다. 변환 불가능한 목록이면 helper는 바로 반환합니다.

[MatchingPlanContractTest.kt:650](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L650) · [MatchingPlanContractTest.kt:671](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L671) · [MatchingPlanContractTest.kt:702](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L702) · [MatchingPlanContractTest.kt:523](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L523) · [MatchingPlanContractTest.kt:534](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L534)

<code>setLastSequence</code>와 maker 직접 접근은 테스트가 Long 경계·내부 값 변경을 만드는 fixture입니다. 외부 운영 호출 API가 아닙니다. 기대 이벤트는 entered/trade/cancelled helper가 고정 입력으로 만들며 운영 prepare 결과를 정답으로 다시 호출하지 않습니다. 최대 수량의 합은 BigInteger로 독립 계산합니다.

</details>

<details markdown="1" id="test-E1"><summary>E1. 후보를 반복하고 버림</summary>

- **Given — 준비:** SELL S1 90×1·S2 95×1을 실제 process로 넣어 마지막 순번 2인 엔진을 만든다. BUY B1은 100×3이다.
- **When — 실행:** B1 후보 두 개를 만든다. 각 후보를 검증한 뒤 적용하지 않고 기존 process(B1)를 호출하고 B1을 취소한다.
- **Then — 실제 확인:** 후보마다 원본 명령과 90×1·95×1 체결/100×1 대기 이벤트가 정확히 같고 첫/끝 순번은 3·5다. 실제 process도 같은 이벤트를 반환한다. 이후 B1 취소는 순번 6·잔량 1이다.

폐기한 후보가 ID나 순번·수량을 소비했다면 뒤의 실제 결과가 달라지므로 관측 가능한 호출로 대조한다. 메서드: [매칭 후보 생성과 검증을 반복하고 버려도 실제 주문은 같은 순번과 수량으로 처리된다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanExampleTest.kt#L26). [검증문 32](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanExampleTest.kt#L32) · [검증문 33](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanExampleTest.kt#L33) · [검증문 34](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanExampleTest.kt#L34) · [검증문 35](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanExampleTest.kt#L35) · [검증문 39](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanExampleTest.kt#L39) · [검증문 40](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanExampleTest.kt#L40)
</details>

<details markdown="1" id="test-E2"><summary>E2. 명시적 적용 뒤 실제 주문</summary>

- **Given — 준비:** 같은 SELL 두 건과 BUY B1 100×3 후보를 준비한다.
- **When — 실행:** apply 뒤 B1을 중복 제출한다. S1·S2 취소, B1 취소, B1 재제출을 차례로 시도한다.
- **Then — 실제 확인:** apply는 정확한 세 이벤트를 반환한다. 중복 B1은 처음과 취소 후 모두 IllegalArgumentException이다. S1/S2 취소는 순번 6·7의 order not found, B1 취소는 순번 8·잔량 1이다. 마지막에도 plan.events는 최초 기대값과 같다.

실제 maker 제거·taker 잔량·과거 이벤트 보존을 외부에서 부를 수 있는 process 결과로 확인한다. 메서드: [명시적 적용은 두 체결과 잔량을 함께 반영하고 과거 후보 값은 보존한다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanExampleTest.kt#L47). [검증문 51](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanExampleTest.kt#L51) · [검증문 52](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanExampleTest.kt#L52) · [검증문 53](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanExampleTest.kt#L53) · [검증문 57](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanExampleTest.kt#L57) · [검증문 61](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanExampleTest.kt#L61) · [검증문 65](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanExampleTest.kt#L65) · [검증문 66](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanExampleTest.kt#L66)
</details>

<details markdown="1" id="test-C1"><summary>C1. 빈 새 마켓</summary>

- **Given — 준비:** 아직 BTC-USDT 주문장·사용 ID·순번 항목이 없는 엔진과 BUY B1 100×3.
- **When — 실행:** 같은 주문의 후보를 생성·검증하고 버린 뒤 새 후보를 다시 생성·검증·적용한다.
- **Then — 실제 확인:** 후보에는 순번 1의 B1 3개 대기가 있다. 준비·검증 뒤 깊은 상태 값은 빈 map 세 개와 같고, 적용 후에만 B1 주문·사용 ID·마지막 순번 1이 생긴다.

없는 마켓을 읽었다는 이유만으로 내부 상태를 만드는 일을 막는다. 메서드: [빈 새 마켓의 준비와 검증은 map과 주문 ID와 순번을 만들지 않는다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L21). [검증문 28](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L28) · [검증문 29](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L29) · [검증문 30](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L30) · [검증문 31](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L31) · [검증문 32](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L32) · [검증문 36](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L36) · [검증문 37](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L37) · [검증문 38](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L38)
</details>

<details markdown="1" id="test-C2"><summary>C2. 가격 미교차</summary>

- **Given — 준비:** SELL S1 101×2가 적용돼 있다. BUY B1의 최고 매수가격은 100, 수량은 1이다.
- **When — 실행:** 후보를 생성·검증한 뒤 적용한다.
- **Then — 실제 확인:** 후보·반환값은 순번 2의 B1 100×1 대기 한 건이다. 준비 중 상태는 그대로다. 적용 뒤 S1 101×2가 보존되고 B1 100×1이 대기한다.

비싼 매도 주문을 허용 가격보다 높게 체결하지 않도록 한다. 메서드: [가격이 교차하지 않는 계획은 상대 주문을 보존하고 자기 잔량만 대기시킨다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L49). [검증문 57](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L57) · [검증문 58](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L58) · [검증문 59](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L59) · [검증문 60](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L60)
</details>

<details markdown="1" id="test-C3"><summary>C3. 사전 검사 뒤 새 주문</summary>

- **Given — 준비:** S1 100×2에서 B1 100×1 후보를 만들고 사전 검증한다.
- **When — 실행:** 같은 마켓에 S2 SELL 110×1을 실제 처리한 뒤 과거 후보의 validate와 apply를 시도한다.
- **Then — 실제 확인:** S2 입장 순번 2는 정상 반환된다. 과거 후보의 검증·적용은 IllegalStateException이며 S1/S2·사용 ID·마지막 순번 2를 보존한다.

사전 검사에 성공했다는 이유로 이후 상태의 변화를 놓치지 않게 한다. 메서드: [사전 검증 뒤 같은 마켓에 정상 submit이 적용되면 다시 검사해 계획을 거절한다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L72). [검증문 78](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L78) · [검증문 84](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L84) · [검증문 85](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L85)
</details>

<details markdown="1" id="test-C4"><summary>C4. 두 후보 중 먼저 적용</summary>

- **Given — 준비:** S1 100×2에서 B1·B2의 BUY 100×1 후보를 이 순서로 만든다.
- **When — 실행:** 두 후보를 검증하고, 두 번째로 만든 B2 후보를 먼저 적용한 뒤 B1 후보를 시도한다.
- **Then — 실제 확인:** 후보 생성·검증은 상태를 예약하지 않는다. B2가 순번 2로 먼저 1개 체결된다. B1 후보는 거절되며 S1 잔량 1·사용 ID S1/B2만 남는다.

후보 생성 순서를 실제 주문 우선순위 예약으로 오해하지 않게 한다. 메서드: [같은 상태에서 만든 두 계획은 생성 순서로 예약하지 않고 먼저 적용한 뒤 나머지를 거절한다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L96). [검증문 105](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L105) · [검증문 107](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L107) · [검증문 108](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L108) · [검증문 109](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L109)
</details>

<details markdown="1" id="test-C5"><summary>C5. 정상 취소 뒤 오래됨</summary>

- **Given — 준비:** S1 100×2에서 B1 후보를 만든다.
- **When — 실행:** S1을 정상 취소하고 과거 후보를 검증·적용한다.
- **Then — 실제 확인:** 취소는 순번 2·취소량 2를 반환한다. 과거 후보는 두 호출 모두 거절되고 빈 book·사용 ID S1·순번 2를 보존한다.

삭제된 maker를 과거 후보로 다시 체결하지 않게 한다. 메서드: [같은 마켓의 정상 취소 뒤에는 계획을 거절하고 취소 뒤 상태를 보존한다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L120). [검증문 125](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L125) · [검증문 127](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L127) · [검증문 128](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L128)
</details>

<details markdown="1" id="test-C6"><summary>C6. 없는 주문 취소 뒤 오래됨</summary>

- **Given — 준비:** S1 100×2에서 B1 후보를 만든다.
- **When — 실행:** 같은 마켓의 missing 주문을 취소하고 과거 후보를 검증·적용한다.
- **Then — 실제 확인:** 취소 거절 이벤트의 순번은 2, 이유는 order not found다. S1 잔량은 그대로여도 순번을 썼으므로 후보가 거절된다. 거절 뒤 상태는 취소 직후와 같다.

주문장이 안 바뀐 취소 거절도 이벤트 순번을 바꾼다는 차이를 확인한다. 메서드: [없는 주문 취소도 거절 순번을 소비하므로 같은 마켓의 계획을 무효화한다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L132). [검증문 137](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L137) · [검증문 139](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L139) · [검증문 140](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L140)
</details>

<details markdown="1" id="test-C7"><summary>C7. 다른 소유자 취소 뒤 오래됨</summary>

- **Given — 준비:** S1 100×2에서 B1 후보를 만든다.
- **When — 실행:** intruder 사용자가 S1 취소를 요청하고 과거 후보를 검증·적용한다.
- **Then — 실제 확인:** 순번 2의 order owner mismatch 거절과 요청자 intruder를 정확히 확인한다. S1은 보존되지만 과거 후보는 거절되며 이후 상태가 바뀌지 않는다.

소유자 불일치 거절의 순번도 오래됨 기준에 포함한다. 메서드: [소유자 불일치 취소도 거절 순번을 소비하므로 같은 마켓의 계획을 무효화한다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L147). [검증문 152](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L152) · [검증문 157](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L157) · [검증문 158](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L158)
</details>

<details markdown="1" id="test-C8"><summary>C8. 거절된 새 주문은 무변경</summary>

- **Given — 준비:** S1 100×2와 유효한 B1 후보가 있다. 중복 S1, MARKET 주문, IOC 주문의 세 입력을 고정한다.
- **When — 실행:** 각 입력을 process와 prepareSubmit에 각각 넣고 매번 기존 B1 후보를 검증한다. 마지막에 B1을 적용한다.
- **Then — 실제 확인:** 세 입력 모두 두 진입점에서 IllegalArgumentException이다. 매 반복 상태는 before와 같고 기존 후보 검증도 상태를 바꾸지 않는다. B1은 순번 2로 S1 1개를 체결한다.

예외로 끝나 순번을 쓰지 않은 새 주문을 취소 거절과 구분한다. 메서드: [중복과 미지원 submit 예외는 상태를 바꾸거나 기존 정상 계획을 무효화하지 않는다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L165). [검증문 179](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L179) · [검증문 180](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L180) · [검증문 181](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L181) · [검증문 183](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L183) · [검증문 186](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L186) · [검증문 187](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L187)
</details>

<details markdown="1" id="test-C9"><summary>C9. 다른 마켓의 처리</summary>

- **Given — 준비:** BTC-USDT S1 100×2에서 B1 후보를 만든다.
- **When — 실행:** ETH-USDT에 E1 제출→정상 취소→없는 주문 취소를 순번 1·2·3으로 처리한다. BTC 후보를 검증·적용한다.
- **Then — 실제 확인:** ETH 이벤트 각각의 값과 BTC 순번 2 체결을 확인한다. BTC 검증 전후 전체 snapshot이 같고, 적용 뒤 ETH의 book·사용 ID E1·마지막 순번 3을 보존한다. BTC book은 S1 잔량 1과 같다.

다른 마켓의 진행으로 현재 마켓 후보를 불필요하게 무효화하지 않는다. 메서드: [다른 마켓의 submit과 정상 취소와 취소 거절은 현재 계획을 무효화하지 않는다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L194). [검증문 199](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L199) · [검증문 203](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L203) · [검증문 207](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L207) · [검증문 213](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L213) · [검증문 215](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L215) · [검증문 217](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L217) · [검증문 218](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L218) · [검증문 219](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L219) · [검증문 220](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L220)
</details>

<details markdown="1" id="test-C10"><summary>C10. 다른 엔진에서 적용 시도</summary>

- **Given — 준비:** 원래 엔진은 S1 100×2, 다른 엔진은 S2 99×3을 가진다. B1 후보는 원래 엔진이 발급했다.
- **When — 실행:** 다른 엔진에서 validate와 apply를 시도한다. 이어 원래 엔진에서 validate와 apply를 한다.
- **Then — 실제 확인:** 다른 엔진의 두 호출은 IllegalArgumentException이다. 두 엔진의 상태는 거절 전과 같다. 원래 엔진에서는 순번 2의 S1/B1 체결이 여전히 성공하고 다른 엔진은 그대로다.

잘못된 사용 시도 때문에 원래 후보까지 소모되지 않게 한다. 메서드: [다른 엔진의 거절은 두 엔진 상태와 원래 엔진의 적용 권한을 보존한다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L227). [검증문 236](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L236) · [검증문 237](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L237) · [검증문 238](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L238) · [검증문 239](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L239) · [검증문 240](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L240) · [검증문 243](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L243) · [검증문 244](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L244)
</details>

<details markdown="1" id="test-C11"><summary>C11. 한 번 적용한 후보</summary>

- **Given — 준비:** S1 100×2에서 B1 100×1 후보를 만든다.
- **When — 실행:** 한 번 적용해 순번 2 체결을 받은 뒤 같은 후보의 validate와 apply를 다시 시도한다.
- **Then — 실제 확인:** 재검증·재적용은 IllegalStateException이다. 첫 적용 직후 snapshot과 같고 S1 잔량 1·사용 ID S1/B1·순번 2가 유지된다.

같은 후보를 두 번 체결하지 않게 한다. 외부 요청 재전송에 최초 결과를 돌려주는 기능과는 다른 동작이다. 메서드: [이미 적용한 계획의 검증과 재적용은 거절하고 첫 결과 상태를 보존한다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L248). [검증문 253](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L253) · [검증문 256](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L256) · [검증문 257](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L257)
</details>

<details markdown="1" id="test-C12"><summary>C12. 부분 maker의 FIFO</summary>

- **Given — 준비:** 같은 가격 100에 S1 수량 2, S2 수량 1이 이 순서로 대기한다.
- **When — 실행:** B1 100×1 후보를 준비·적용한다. 이어 B2 100×2 후보를 준비·적용한다.
- **Then — 실제 확인:** 첫 후보는 S1 1개 체결이며 준비 중 snapshot은 그대로다. 첫 적용 후 S1 잔량 1이 S2 앞에 있다. 다음 이벤트는 순번 4의 S1→순번 5의 S2 순서이고 최종 대기 주문은 없다.

부분 체결 주문을 줄 뒤로 보내는 실수를 막는다. 메서드: [부분 체결된 maker는 같은 가격에서 FIFO의 앞자리를 유지한다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L264). [검증문 271](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L271) · [검증문 272](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L272) · [검증문 273](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L273) · [검증문 274](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L274) · [검증문 289](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L289) · [검증문 290](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L290) · [검증문 291](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L291)
</details>

<details markdown="1" id="test-C13"><summary>C13. SELL의 가격·FIFO</summary>

- **Given — 준비:** BUY B1 100×2, B2 110×1, B3 110×1이 이 순서로 적용돼 있다.
- **When — 실행:** SELL S1 95×3을 준비·검증·적용한다.
- **Then — 실제 확인:** 후보·반환값은 B2 110×1→B3 110×1→B1 100×1의 순번 4·5·6이다. 준비·검증은 상태를 바꾸지 않고 적용 후 B1 100×1만 남는다.

SELL에서도 높은 bid, 같은 가격의 먼저 온 주문, maker 가격이라는 세 판단을 함께 확인한다. 메서드: [sell 계획은 높은 bid와 같은 가격 FIFO를 따라 maker 가격으로 전량 체결한다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L295). [검증문 309](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L309) · [검증문 311](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L311) · [검증문 312](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L312) · [검증문 313](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L313)
</details>

<details markdown="1" id="test-C14"><summary>C14. 순번 그대로 maker 잔량 변경</summary>

- **Given — 준비:** S1 100×2에서 B1 후보를 만든다.
- **When — 실행:** 테스트 내부 접근으로 S1을 1개 체결해 잔량만 바꾸고 순번은 1로 둔다. 과거 후보를 검증·적용한다.
- **Then — 실제 확인:** 두 호출은 IllegalStateException이다. 이미 인위적으로 바꾼 S1 잔량 1·사용 ID S1·순번 1을 더 바꾸지 않는다.

순번 비교 외에 후보가 기억한 maker의 기준 값도 확인함을 관측한다. 운영 API 입력 사례가 아닌 방어 검사다. 메서드: [순번이 같아도 maker의 기준 잔량이 바뀌면 첫 쓰기 전에 거절한다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L320). [검증문 328](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L328) · [검증문 329](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L329)
</details>

<details markdown="1" id="test-C15"><summary>C15. 과거 이벤트의 불변 값</summary>

- **Given — 준비:** S1 100×1에서 B1 100×2 후보를 만든다. 기대 이벤트는 순번 2의 체결과 순번 3의 B1 1개 대기다.
- **When — 실행:** 후보 목록 변경을 시도하고 검증·적용한다. 반환 목록 변경도 시도한다. 다음 SELL S2 90×1로 B1 잔량을 체결한다.
- **Then — 실제 확인:** 목록의 교체·추가·clear는 UnsupportedOperationException이다. 각 시도 뒤 엔진 값과 기대 이벤트를 확인한다. 순번 4의 후속 체결 뒤에도 후보·반환 목록의 과거 이벤트는 기대값과 같다.

현재 주문의 가변 잔량과 과거 결과 값이 연결되어 뒤늦게 바뀌지 않게 한다. 메서드: [후보와 적용 반환 목록은 변경할 수 없고 후속 체결도 과거 이벤트 값을 바꾸지 않는다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L381). [검증문 392](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L392) · [검증문 393](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L393) · [검증문 395](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L395) · [검증문 399](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L399) · [검증문 400](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L400) · [검증문 401](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L401) · [검증문 402](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L402) · [검증문 404](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L404) · [검증문 408](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L408) · [검증문 409](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L409)
</details>

<details markdown="1" id="test-C16"><summary>C16. 최대 수량 보존</summary>

- **Given — 준비:** S1 수량 MAX−1과 S2 수량 MAX가 가격 100에 대기한다. B1 수량은 MAX다.
- **When — 실행:** B1을 준비·검증·적용한다.
- **Then — 실제 확인:** 후보는 S1의 MAX−1개와 S2의 1개를 체결한다. 별도 BigInteger 합계가 정확히 Long.MAX_VALUE인지 확인한다. 준비·검증 뒤 snapshot은 그대로이고 적용 뒤 S2 잔량 MAX−1만 남는다.

여러 체결의 수량 합계를 큰 정수로 대조해 Long 누적의 감김을 놓치지 않는다. 메서드: [최대 수량의 계획은 여러 maker를 체결해도 수량과 부분 maker 잔량을 정확히 보존한다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L413). [검증문 425](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L425) · [검증문 426](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L426) · [검증문 433](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L433) · [검증문 434](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L434) · [검증문 435](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L435)
</details>

<details markdown="1" id="test-C17"><summary>C17. 마지막 MAX 순번 허용</summary>

- **Given — 준비:** S1 90×1·S2 95×1을 넣고 테스트 내부에서 마지막 순번을 MAX−3으로 설정한다.
- **When — 실행:** B1 100×3 후보를 준비·검증·적용한다. 이후 새 주문의 준비·process와 정상·없는 주문·다른 소유자 취소를 시도한다.
- **Then — 실제 확인:** 첫/끝 순번과 이벤트 전체는 MAX−2·MAX−1·MAX다. 준비·검증은 무변경이다. 적용 뒤 B1 잔량 1·사용 ID 세 개·마지막 MAX를 확인한다. 모든 후속 시도는 ArithmeticException이며 매번 exhausted snapshot을 보존한다.

MAX 자체는 허용하면서 그 다음 이벤트가 음수 순번으로 감기는 것을 막는다. 메서드: [마지막 이벤트에 MAX 순번을 허용하고 이후 submit과 모든 cancel은 변경 전에 실패한다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L446). [검증문 460](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L460) · [검증문 461](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L461) · [검증문 462](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L462) · [검증문 464](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L464) · [검증문 465](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L465) · [검증문 467](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L467) · [검증문 477](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L477) · [검증문 478](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L478) · [검증문 479](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L479) · [검증문 480](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L480) · [검증문 482](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L482) · [검증문 483](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L483)
</details>

<details markdown="1" id="test-C18"><summary>C18. 전체 순번이 부족함</summary>

- **Given — 준비:** S1 90×1·S2 95×1, 마지막 순번 MAX−2, BUY B1 100×3이다.
- **When — 실행:** 세 이벤트가 필요한 prepareSubmit과 기존 process를 각각 시도한다. 테스트에서 마지막 순번을 2로 돌린 뒤 같은 주문을 처리한다.
- **Then — 실제 확인:** 앞선 두 시도는 ArithmeticException이고 매번 before snapshot과 같다. 마지막 호출은 순번 3·4 체결과 5의 대기로 정확히 성공한다.

뒤쪽 이벤트에서야 범위 부족을 알아 이미 maker를 바꿔버리는 일을 막는다. 순번 복구는 테스트 준비이며 운영 복구 API가 아니다. 메서드: [세 이벤트 전체 순번이 부족하면 prepare와 기존 process 모두 어떤 상태도 바꾸지 않는다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L488). [검증문 496](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L496) · [검증문 497](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L497) · [검증문 498](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L498) · [검증문 499](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L499) · [검증문 502](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L502)
</details>

<details markdown="1" id="test-C19"><summary>C19. 소진된 빈 마켓 취소</summary>

- **Given — 준비:** book·사용 ID map은 비어 있고 BTC 순번 항목만 MAX인 인위적 경계 상태다.
- **When — 실행:** 없는 주문을 취소한다.
- **Then — 실제 확인:** 처음 snapshot이 빈 map 두 개·마지막 MAX와 같고, 취소는 ArithmeticException이다. 이후 snapshot도 같아 빈 book map이 생기지 않았음을 직접 비교한다.

취소 순번 검사를 book 생성보다 먼저 수행함을 확인한다. 메서드: [순번이 소진된 빈 마켓의 cancel은 새 주문장 map을 만들지 않는다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L513). [검증문 517](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L517) · [검증문 519](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L519) · [검증문 520](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L520)
</details>

<details markdown="1" id="test-C20"><summary>C20. 순번 그대로 FIFO 위치 변경</summary>

- **Given — 준비:** 같은 가격 100에 S1·S2가 각 수량 2로 대기한다. B1 100×1 후보를 만들고 먼저 validate에 성공한다.
- **When — 실행:** 테스트 내부 book에서 S1을 취소한 뒤 같은 값으로 맨 뒤에 넣어 S2→S1로 바꾼다. 순번은 2다. 먼저 apply, 이어 validate를 시도한다.
- **Then — 실제 확인:** apply가 IllegalStateException이며 reordered snapshot을 그대로 보존한다. 이후 validate도 같은 예외이며 상태가 같다. 마지막에는 S2→S1 순서·각 잔량 2·사용 ID S1/S2·순번 2를 독립 기대값과 비교한다.

maker 값만 비교하면 놓치는 같은 가격의 위치 변화를 확인한다. 사전 검사 후 apply가 직접 재검사하는지도 첫 호출로 관측한다. 메서드: [순번과 maker 값이 같아도 동일 가격 FIFO 위치가 바뀌면 적용 전에 거절한다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L336). [검증문 347](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L347) · [검증문 348](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L348)
</details>

<details markdown="1" id="test-C21"><summary>C21. 더 유리한 maker가 앞에 추가됨</summary>

- **Given — 준비:** S1 SELL 100×2에서 B1 BUY 100×1 후보를 만든다.
- **When — 실행:** 테스트 내부 book에 S2 SELL 90×1을 직접 추가한다. 순번은 1인 채 apply, 이어 validate를 시도한다.
- **Then — 실제 확인:** 두 호출은 IllegalStateException이며 각 호출 뒤 snapshot은 내부 추가 직후 changed와 같다. 더 유리한 S2를 건너뛰고 과거 S1부터 체결하지 않는다.

기억한 maker가 남아 있어도 현재 가격 우선순위의 앞에 더 좋은 주문이 생겼다면 과거 후보를 거절한다. 이 테스트는 snapshot 보존을 확인하며 추가한 S2의 seen ID 등록을 주장하지 않는다. 메서드: [같은 순번에서 더 유리한 maker가 앞에 추가되면 기존 후보를 적용하지 않는다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L355). [검증문 364](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L364)
</details>

<details markdown="1" id="test-C22"><summary>C22. 대기 잔량 뒤 새 체결 상대</summary>

- **Given — 준비:** S1 SELL 90×1에서 B1 BUY 100×3 후보를 만든다. 후보는 S1 체결 뒤 B1 2개 대기를 계산했다.
- **When — 실행:** 테스트 내부 book에 S2 SELL 95×1을 직접 추가한다. 순번은 1인 채 apply, 이어 validate를 시도한다.
- **Then — 실제 확인:** 두 호출은 IllegalStateException이며 각 호출 뒤 snapshot이 새 상대 추가 직후 changed와 같다. S1의 값·순서가 맞더라도 체결할 수 있는 다음 maker가 남아 있으면 과거 대기량을 적용하지 않는다.

접촉한 maker 값만 보지 않고 잔량 대기 판단이 현재에도 맞는지 확인한다. 메서드: [잔량을 대기시키는 후보 뒤에 새 체결 상대가 생기면 순번이 같아도 적용하지 않는다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L368). [검증문 377](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingPlanContractTest.kt#L377)
</details>

</details>

<details markdown="1" id="execution"><summary>실행 근거와 보장 범위</summary>

2026-10-23 로컬 검증은 위 파일 표의 기술 6파일을 대상으로 실행했습니다. 현재 가격·FIFO·추가 체결 상대를 대조하는 세 반례는 보완 전 구현에서 기대 예외가 나오지 않아 실패했고, 검증을 보완한 뒤 통과했습니다. 세 반례는 실제 assertion에 도달한 실패이며, 아직 없는 API 때문에 컴파일이 실패했던 최초 단계와 구분합니다.

최종 관련 검사는 matching 134·trading 100·app-api 8·architecture 381, 총 623개 실패·오류·skip 0으로 통과했습니다. P01~P09와 공통 ktlint도 통과했습니다. 마지막 묶음 실행에서 도메인 테스트는 UP-TO-DATE였으며, 같은 입력의 앞선 실행 XML을 재사용한 결과입니다. 전체 app-api나 원격 CI를 실행한 결과는 아닙니다.

초기 구조 검사는 381개 중 NAME-23 1개가 실패했습니다. 변경 전 기준 코드에서 해당 검사를 단독 재실행한 결과와 현재 코드의 최종 구조 검사 전체는 통과했습니다. 초기 실패 원인은 아직 확정하지 않았습니다.

| 구분 | 읽은 검사와 관측 | 이 근거로 주장하지 않는 것 |
| --- | --- | --- |
| 새 순수 도메인 테스트 | 준비·적용·거절·내부 값·산술 경계 | HTTP·DB·실제 Aeron 동작 |
| 기존 매칭·거래·worker 회귀 | matching 134·trading 100·worker 사용 coordinator 7 성공 | 새 후보 API의 외부 자동 연결 |
| 구조 검사 | architecture 381·P01~P09 성공. 가변 상태·모듈·역할 규칙 확인 | 실제 DB 저장·정산·복제 성공 |
| 기존 DB 통합 회귀 | MatchingPersistenceIntegrationTest 1개: 실제 PostgreSQL 저장 조회 통과 | 새 후보 경로의 HTTP·Aeron 자동 연결. HTTP 테스트는 이 실행에 포함하지 않음 |

기술 6파일의 독립 AI 재리뷰에서 확인된 미해결 결함은 없었습니다. 사람의 리뷰와는 별개이며, 성능·실제 Aeron 연결·프로세스 장애 복구는 검증하지 않았습니다. 게시 준비에서는 기술 파일이 기존 실행 대상과 동일함을 대조했으며, 설명 문서만 정리한 뒤 제품 테스트를 다시 실행하지 않았습니다.

아래는 최종 로컬 검증과 같은 범위를 재현하는 명령입니다. 첫 경로는 이 PR을 체크아웃한 저장소의 절대 경로로 바꿉니다. Java 25와 실제 PostgreSQL 통합 테스트를 실행할 Docker가 필요합니다.

```bash
cd /absolute/path/to/coin-exchange
./gradlew :domain-matching:test :domain-trading:test ktlintCheck \
  :architecture-tests:test :architecture-tests:verifyArchitectureReport \
  :app-api:test \
  --tests 'com.exchange.core.api.matching.application.MatchingCoordinatorContractTest' \
  --tests 'com.exchange.core.api.matching.application.MatchingCoordinatorPersistenceFailureTest' \
  --tests 'com.exchange.core.api.matching.infrastructure.persistence.MatchingPersistenceIntegrationTest' \
  --console=plain
```

결과는 각 모듈의 `build/reports/tests/test/index.html`과 `build/test-results/test/*.xml`에서 확인합니다. 원격 CI는 PR의 **Architecture checks**와 **Build and test**가 별도로 실행하며, 로컬 623개 통과를 원격 전체 검사 성공으로 간주하지 않습니다.

</details>

<details markdown="1" id="snapshot126"><summary>설명 대상과 후속 연결</summary>

- 비교 기준: <code>359ffe17f6f9b79cc911c97ee4f944a20b2055c2</code>.
- 브랜치: <code>feat/matching-plan/126</code>. 제품 4파일과 테스트 2파일의 변경을 설명합니다. 코드·테스트 링크는 이 문서와 같은 체크아웃의 원문을 가리킵니다.
- 코드 발췌는 작성 시점 사본입니다. 위 파일 표의 SHA-256과 원문을 함께 확인하며 이후 소스 변경 시 설명도 갱신해야 합니다.
- 다음 단계는 [#127 정산 후보](https://github.com/0Chord/coin-exchange/issues/127), [#128 공동 반영](https://github.com/0Chord/coin-exchange/issues/128), [#129 Aeron 자동 연결](https://github.com/0Chord/coin-exchange/issues/129)입니다. 이번 변경만으로 자금과 주문장의 공동 원자성이 완성되지는 않습니다.

다음으로 볼 판단은 **후보에 적힌 이벤트와 실제 주문장 상태가 언제 달라지는가**입니다. [합의한 #126 이슈](https://github.com/0Chord/coin-exchange/issues/126) · [상위 #105 이슈](https://github.com/0Chord/coin-exchange/issues/105).

</details>
