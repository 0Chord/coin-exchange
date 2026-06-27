# Phase 1: Pure Kotlin Matching Engine

## 목표

Phase 1의 목표는 Spring, DB, Kafka 없이 동작하는 순수 Kotlin 매칭 엔진을 만드는 것이다.

이 단계에서 증명해야 하는 것은 "주문 API가 있다"가 아니라 다음 속성이다.

- 같은 command stream을 넣으면 항상 같은 trade stream이 나온다.
- 가격-시간 우선순위가 깨지지 않는다.
- 부분 체결과 취소가 오더북 상태를 일관되게 변경한다.
- 정상 처리 후 crossed book이 남지 않는다.
- 매칭 엔진은 Spring/JPA/Kafka/Repository/Clock에 의존하지 않는다.

Phase 1의 완료 기준은 `:domain-matching:test`가 Spring context 없이 빠르게 통과하는 것이다.

```bash
./gradlew :domain-matching:test
```

## 도메인 용어

### Market

거래가 일어나는 상품 쌍이다.

예:

```text
BTC-KRW
ETH-KRW
```

`BTC-KRW`는 BTC를 KRW로 사고파는 마켓이라는 뜻이다.

```text
base asset  = BTC
quote asset = KRW
```

가격이 `100,000,000 KRW`라면 `1 BTC = 100,000,000 KRW`라는 뜻이다.

### Base Asset과 Quote Asset

`BTC-KRW` 기준:

```text
base asset  = BTC
quote asset = KRW
```

매수자는 KRW를 내고 BTC를 받는다.

매도자는 BTC를 내고 KRW를 받는다.

### Bid와 Ask

`bid`는 매수 호가다.

```text
100,000,000원에 BTC를 사고 싶다
```

`ask`는 매도 호가다.

```text
101,000,000원에 BTC를 팔고 싶다
```

오더북에서는 보통 bid는 높은 가격이 우선이고, ask는 낮은 가격이 우선이다.

```text
bids: 높은 가격 먼저
asks: 낮은 가격 먼저
```

### Order Book

아직 체결되지 않고 남아 있는 주문 목록이다.

예:

```text
asks
101,000,000 KRW | 0.5 BTC
100,500,000 KRW | 0.2 BTC

bids
100,000,000 KRW | 0.3 BTC
 99,500,000 KRW | 1.0 BTC
```

매수 주문이 들어오면 가장 싼 ask부터 체결한다.

매도 주문이 들어오면 가장 비싼 bid부터 체결한다.

### Best Bid와 Best Ask

`best bid`는 현재 가장 높은 매수 가격이다.

`best ask`는 현재 가장 낮은 매도 가격이다.

예:

```text
best bid = 100,000,000
best ask = 100,500,000
```

정상적인 오더북에서는 보통 `best bid < best ask` 상태다.

만약 `best bid >= best ask`이면 서로 체결 가능한 주문이 남아 있다는 뜻이고, 이를 crossed book이라고 부른다.

### Crossed Book

체결 가능한 매수/매도 주문이 오더북에 그대로 남아 있는 잘못된 상태다.

예:

```text
best bid = 101
best ask = 100
```

101에 사겠다는 사람과 100에 팔겠다는 사람이 동시에 남아 있으므로 즉시 체결되어야 한다.

매칭 엔진 처리 후 이런 상태가 남으면 버그다.

### Side

주문의 방향이다.

```text
BUY  = 매수
SELL = 매도
```

`BTC-KRW`에서 `BUY`는 KRW를 내고 BTC를 사는 주문이다.

`SELL`은 BTC를 팔고 KRW를 받는 주문이다.

### Limit Order

지정가 주문이다.

사용자가 원하는 가격을 직접 지정한다.

예:

```text
BTC를 100,000,000원 이하에서 사고 싶다
BTC를 101,000,000원 이상에서 팔고 싶다
```

매수 지정가 주문은 현재 매도 호가가 내 지정가 이하일 때 체결된다.

```text
buy limit price = 100
best ask = 99
체결 가능
```

매도 지정가 주문은 현재 매수 호가가 내 지정가 이상일 때 체결된다.

```text
sell limit price = 100
best bid = 101
체결 가능
```

바로 체결되지 않은 잔량은 GTC 주문이면 오더북에 남는다.

### Market Order

시장가 주문이다.

가격을 지정하지 않고 현재 오더북의 반대편 호가를 가능한 만큼 즉시 체결한다.

예:

```text
지금 살 수 있는 가격에 BTC를 바로 산다
지금 팔 수 있는 가격에 BTC를 바로 판다
```

Phase 1 초반에는 limit order부터 구현하고, market order는 나중에 추가한다.

### Time In Force

주문이 얼마나 오래 유효한지를 나타내는 정책이다.

Phase 1에서 볼 값은 `GTC`, `IOC`다.

### GTC

`Good Till Cancelled`의 약자다.

한국어로는 "취소될 때까지 유효한 주문" 정도로 보면 된다.

즉시 체결되지 않은 잔량이 오더북에 남는다.

예:

```text
buy limit 100, quantity 10
현재 best ask = 101
체결 안 됨
주문은 bid book에 남음
```

이후 누군가 `sell 100`을 넣으면 남아 있던 GTC 매수 주문과 체결될 수 있다.

### IOC

`Immediate Or Cancel`의 약자다.

한국어로는 "즉시 체결 가능한 만큼만 체결하고, 남은 수량은 취소"다.

예:

```text
buy limit 100, quantity 10, IOC
현재 ask 100에 quantity 3만 있음
3은 즉시 체결
남은 7은 오더북에 남지 않고 취소
```

Phase 1 첫 구현은 GTC부터 하고, IOC는 그 다음에 붙인다.

### Maker와 Taker

`maker`는 이미 오더북에 있던 주문이다.

`taker`는 새로 들어와서 기존 주문을 체결시키는 주문이다.

예:

```text
1. ask 100 주문이 먼저 오더북에 들어감
2. buy 110 주문이 새로 들어옴
3. buy 110이 ask 100을 체결시킴
```

이 경우:

```text
maker = ask 100
taker = buy 110
```

체결 가격은 maker 주문 가격을 따른다.

```text
trade price = 100
```

### Maker Price

체결 가격이 새로 들어온 주문 가격이 아니라, 기존에 오더북에 있던 maker 주문 가격으로 결정되는 규칙이다.

예:

```text
ask 100이 먼저 있음
buy 110이 들어옴
```

buy 주문은 110까지 살 의사가 있지만, 이미 100에 파는 주문이 있었으므로 100에 체결된다.

```text
trade price = 100
```

반대도 같다.

```text
bid 110이 먼저 있음
sell 100이 들어옴
trade price = 110
```

### Partial Fill

부분 체결이다.

주문 수량 전체가 아니라 일부만 체결되는 상황이다.

예:

```text
ask 100, quantity 3
buy 100, quantity 10
```

결과:

```text
3 체결
buy 주문은 7 남음
```

GTC라면 남은 7은 bid book에 들어간다.

### Filled

주문 수량이 전부 체결된 상태다.

예:

```text
order quantity = 10
filled quantity = 10
remaining quantity = 0
```

완전히 체결된 주문은 오더북에 남으면 안 된다.

### Remaining Quantity

아직 체결되지 않고 남아 있는 수량이다.

```text
remaining = original quantity - filled quantity - cancelled quantity
```

Phase 1에서는 최소한 아래 불변식을 지켜야 한다.

```text
remaining >= 0
filled <= original quantity
```

### Cancel

오더북에 남아 있는 미체결 주문을 취소하는 명령이다.

취소되면:

- 오더북에서 제거된다.
- 이후 체결되면 안 된다.
- `OrderCancelled` 이벤트가 생성된다.

이미 체결되어 오더북에 없는 주문을 취소하려는 경우는 `OrderCancelRejected` 또는 no-op으로 처리할 수 있다.

Phase 1에서는 테스트 명확성을 위해 rejected event를 두는 편이 좋다.

### Price-Time Priority

가격-시간 우선순위다.

거래소 매칭의 핵심 규칙이다.

1. 더 좋은 가격이 먼저 체결된다.
2. 가격이 같으면 먼저 들어온 주문이 먼저 체결된다.

매수 입장에서 더 좋은 가격:

```text
더 낮은 ask
```

매도 입장에서 더 좋은 가격:

```text
더 높은 bid
```

같은 가격에서는 FIFO다.

```text
first in, first out
```

### FIFO

`First In, First Out`의 약자다.

먼저 들어온 주문이 먼저 나간다는 뜻이다.

같은 가격 레벨에서는 FIFO가 반드시 지켜져야 한다.

예:

```text
ask 100, order A, quantity 1
ask 100, order B, quantity 1
buy 100, quantity 1
```

결과:

```text
order A가 먼저 체결
order B는 남음
```

### Engine Sequence

매칭 엔진이 command 또는 event 처리 순서에 부여하는 단조 증가 번호다.

예:

```text
1, 2, 3, 4, ...
```

서버 수신 시간이나 현재 시각이 아니라, 엔진 내부 순서를 기준으로 한다.

이 값이 중요한 이유:

- replay 시 같은 순서를 재현할 수 있다.
- 테스트에서 결과 순서를 비교할 수 있다.
- 나중에 market data sequence와 구분할 수 있다.

### Deterministic

결정적이라는 뜻이다.

같은 입력을 같은 순서로 넣으면 항상 같은 결과가 나와야 한다.

예:

```text
command A
command B
command C
```

위 command stream을 새 엔진에 다시 넣었을 때 trade 결과와 오더북 상태가 같아야 한다.

Phase 1의 가장 중요한 목표 중 하나다.

## 모듈 구조

Phase 1에서 사용하는 모듈은 세 개다.

```text
domain-common
domain-order
domain-matching
```

Spring Boot 애플리케이션은 `app-api`에 있지만, Phase 1에서는 직접 사용하지 않는다.

의존성 방향은 아래처럼 유지한다.

```text
domain-common
  <- domain-order
  <- domain-matching

app-api
  -> domain-common
  -> domain-order
  -> domain-matching
```

중요한 규칙:

- `domain-common`은 어떤 모듈에도 의존하지 않는다.
- `domain-order`는 `domain-common`만 의존한다.
- `domain-matching`은 `domain-common`, `domain-order`만 의존한다.
- `domain-matching`은 `app-api`, Spring, JPA, Kafka를 몰라야 한다.

이 구조는 매칭 엔진을 독립적으로 테스트하고, 나중에 JMH 벤치마크 대상으로 분리하기 위한 것이다.

## 패키지 구조

Phase 1 구현 대상 패키지는 다음과 같다.

```text
domain-common
  src/main/kotlin/com/exchange/core/common
    Identifiers.kt
    ScaledNumbers.kt

domain-order
  src/main/kotlin/com/exchange/core/order
    OrderTypes.kt

domain-matching
  src/main/kotlin/com/exchange/core/matching
    MatchingCommand.kt
    MatchingEvent.kt
    BookOrder.kt
    PriceLevel.kt
    OrderBook.kt
    MatchingEngine.kt

  src/test/kotlin/com/exchange/core/matching
    MatchingEngineTest.kt
```

파일은 처음부터 꼭 이 이름 그대로일 필요는 없지만, 책임은 이 단위로 나누는 것이 좋다.

## domain-common

`domain-common`은 여러 도메인이 공유하는 기본 타입을 둔다.

예상 타입:

```text
MarketId
OrderId
UserId
Price
Quantity
Amount
```

### 식별자

식별자는 단순 문자열을 직접 넘기지 않고 value class로 감싼다.

예시:

```kotlin
@JvmInline
value class OrderId(val value: String) {
    init { require(value.isNotBlank()) }
}
```

이렇게 하면 `OrderId`, `UserId`, `MarketId`를 실수로 바꿔 넣는 문제를 컴파일 단계에서 줄일 수 있다.

### 숫자 타입

Phase 1에서는 `Double`을 사용하지 않는다.

권장 방향:

```text
Price    -> scaled Long
Quantity -> scaled Long
Amount   -> scaled Long
```

처음 테스트에서는 `Price(100)`, `Quantity(10)`처럼 단순 raw 값을 써도 된다. 중요한 것은 API와 내부 로직에서 부동소수점 오차가 들어오지 않게 막는 것이다.

기본 규칙:

- `Price`는 양수만 허용한다.
- `Quantity`는 0 이상만 허용한다.
- 주문 생성 시 `quantity`는 0보다 커야 한다.
- 체결 계산 중 잔량은 0이 될 수 있다.
- `Double`, `Float`는 금지한다.

## domain-order

`domain-order`는 주문 도메인의 공통 언어를 둔다.

Phase 1 최소 타입:

```kotlin
enum class Side {
    BUY,
    SELL,
}

enum class OrderType {
    LIMIT,
    MARKET,
}

enum class TimeInForce {
    GTC,
    IOC,
}

enum class OrderStatus {
    ACCEPTED,
    PARTIALLY_FILLED,
    FILLED,
    CANCELLED,
}
```

Phase 1 구현은 `LIMIT + GTC + CANCEL`부터 시작한다.

`MARKET`, `IOC`는 enum만 먼저 정의해도 되고, 구현은 limit 매칭이 안정된 뒤 추가한다.

## domain-matching

`domain-matching`은 Phase 1의 핵심 모듈이다.

책임:

- 마켓별 오더북 관리
- 가격-시간 우선순위 매칭
- maker/taker 결정
- 부분 체결 처리
- 주문 취소 처리
- 매칭 결과 event 생성
- deterministic engine sequence 부여

책임이 아닌 것:

- 잔고 동결
- 수수료 계산
- 원장 posting
- DB 저장
- Kafka publish
- WebSocket broadcast
- REST request validation

이 책임들은 Phase 2 이후에 붙인다.

## Command와 Event

외부에서 매칭 엔진에 넣는 입력은 command로 표현한다.

```text
SubmitOrderCommand
CancelOrderCommand
```

매칭 엔진이 만든 결과는 event로 표현한다.

```text
TradeExecuted
OrderEnteredBook
OrderPartiallyFilled
OrderFilled
OrderCancelled
OrderRejected
```

처음에는 모든 event를 다 만들 필요는 없다.

Phase 1 최소 event:

```text
TradeExecuted
OrderEnteredBook
OrderCancelled
```

추가로 테스트와 상태 설명이 필요해지면 `OrderPartiallyFilled`, `OrderFilled`를 분리한다.

중요한 규칙:

- event에는 엔진이 부여한 `engineSequence`가 있어야 한다.
- `engineSequence`는 market별 단조 증가 값이다.
- wall clock 시간은 매칭 순서 기준으로 사용하지 않는다.
- replay mode에서도 같은 command 순서면 같은 event 순서가 나와야 한다.

## OrderBook 자료구조

초기 구현은 단순하고 검증하기 쉬운 구조로 간다.

```text
bids: TreeMap<Price, PriceLevel>       // 높은 가격 우선
asks: TreeMap<Price, PriceLevel>       // 낮은 가격 우선
orderIndex: HashMap<OrderId, OrderRef> // 취소용 인덱스
```

가격 레벨은 같은 가격의 주문들을 FIFO로 들고 있다.

```text
PriceLevel
  price
  orders: LinkedHashMap<OrderId, BookOrder>
```

`LinkedHashMap`을 쓰는 이유:

- 삽입 순서가 유지된다.
- 같은 가격에서 FIFO를 구현하기 쉽다.
- `orderId`로 취소할 때 제거하기 쉽다.

나중에 JMH에서 `ArrayDeque`, linked list, intrusive list와 비교할 수 있다. Phase 1에서는 correctness가 우선이다.

## 매칭 규칙

### Buy limit

매수 지정가 주문은 반대편 best ask가 존재하고, `bestAsk <= buyPrice`이면 체결된다.

```text
while taker.remaining > 0:
  bestAsk = asks.firstEntry()
  if bestAsk.price > taker.limitPrice:
    break
  match with first maker in bestAsk price level
```

### Sell limit

매도 지정가 주문은 반대편 best bid가 존재하고, `bestBid >= sellPrice`이면 체결된다.

```text
while taker.remaining > 0:
  bestBid = bids.firstEntry()
  if bestBid.price < taker.limitPrice:
    break
  match with first maker in bestBid price level
```

### 체결 가격

체결 가격은 항상 maker 주문 가격이다.

```text
trade.price = maker.price
```

예:

```text
ask 100이 먼저 book에 있음
buy 110이 들어옴
체결 가격은 110이 아니라 100
```

### 체결 수량

체결 수량은 taker와 maker의 잔량 중 작은 값이다.

```text
trade.quantity = min(taker.remaining, maker.remaining)
```

체결 후:

```text
taker.remaining -= trade.quantity
maker.remaining -= trade.quantity
```

### maker 완전 체결

maker 잔량이 0이 되면:

- price level에서 maker를 제거한다.
- `orderIndex`에서도 제거한다.
- price level이 비면 `bids` 또는 `asks`에서 price level을 제거한다.

### taker 잔량

매칭 후 taker 잔량이 남으면:

- `GTC` 주문은 자기 side book에 등록한다.
- `IOC` 주문은 잔량을 book에 넣지 않고 취소 처리한다.

Phase 1 첫 구현은 `GTC`부터 한다.

## 취소 규칙

취소는 `orderIndex`를 통해 처리한다.

```text
CancelOrderCommand(orderId)
  -> orderIndex에서 order 위치 조회
  -> price level에서 order 제거
  -> orderIndex에서 제거
  -> price level이 비면 book에서 제거
  -> OrderCancelled event 생성
```

이미 체결되어 book에 없는 주문의 cancel은 Phase 1에서 둘 중 하나로 정한다.

권장:

```text
idempotent no-op event 또는 OrderCancelRejected
```

처음에는 테스트를 단순하게 하기 위해 `OrderCancelRejected`를 두는 편이 명확하다.

## Crossed Book 방지

정상 매칭이 끝났는데 아래 상태가 남으면 안 된다.

```text
bestBid >= bestAsk
```

예:

```text
bid 100
ask 90
```

이 상태는 매수/매도 조건이 아직 체결 가능하다는 뜻이다. 따라서 매칭 엔진 처리 후에는 crossed book이 남지 않아야 한다.

테스트 helper로 아래 검증을 두는 것이 좋다.

```text
bestBid == null || bestAsk == null || bestBid < bestAsk
```

## 구현 순서

추천 구현 순서:

```text
1. domain-common: identifier value class
2. domain-common: Price, Quantity, Amount
3. domain-order: Side, OrderType, TimeInForce, OrderStatus
4. domain-matching: MatchingCommand
5. domain-matching: MatchingEvent
6. domain-matching: BookOrder
7. domain-matching: PriceLevel
8. domain-matching: OrderBook.addRestingOrder()
9. domain-matching: OrderBook.cancel()
10. domain-matching: MatchingEngine.process(SubmitOrderCommand)
11. domain-matching: MatchingEngine.process(CancelOrderCommand)
12. domain-matching: snapshot/debug 조회 메서드
13. domain-matching 테스트 작성
```

`MatchingEngine`이 public entry point가 되고, `OrderBook`은 가능하면 package 내부 구현으로 둔다.

## 테스트 목록

Phase 1에서 최소한 아래 테스트를 작성한다.

### 주문 등록

- 빈 book에 buy limit 주문을 넣으면 bid book에 남는다.
- 빈 book에 sell limit 주문을 넣으면 ask book에 남는다.
- book에 들어간 주문은 `OrderEnteredBook` event를 만든다.

### 가격 우선순위

- `ask 100`, `ask 90`이 있으면 buy 주문은 `90`부터 체결된다.
- `bid 100`, `bid 110`이 있으면 sell 주문은 `110`부터 체결된다.

### 시간 우선순위

- 같은 가격의 ask 두 개가 있으면 먼저 들어온 ask가 먼저 체결된다.
- 같은 가격의 bid 두 개가 있으면 먼저 들어온 bid가 먼저 체결된다.

### 부분 체결

- maker 수량보다 taker 수량이 작으면 maker 잔량이 book에 남는다.
- taker 수량보다 maker 수량이 작으면 maker는 제거되고 taker가 다음 maker와 계속 체결된다.
- 일부만 체결된 GTC taker는 남은 수량이 자기 book에 들어간다.

### maker price

- `ask 100`이 먼저 있고 `buy 110`이 들어오면 체결 가격은 `100`이다.
- `bid 110`이 먼저 있고 `sell 100`이 들어오면 체결 가격은 `110`이다.

### 취소

- book에 있는 주문을 cancel하면 book에서 제거된다.
- cancel된 주문은 이후 체결되지 않는다.
- 없는 주문 cancel은 명확한 rejected/no-op 결과를 만든다.

### Crossed book

- 매칭 처리 후 `bestBid >= bestAsk` 상태가 남지 않는다.

### 결정성

- 같은 command list를 새 engine 두 개에 각각 적용하면 같은 event list가 나온다.
- 같은 command list를 적용한 뒤 snapshot checksum 또는 top of book 상태가 같다.

## Phase 1에서 하지 않을 것

아래는 일부러 하지 않는다.

- Spring Controller
- JPA Entity
- Repository
- PostgreSQL 저장
- Kafka publish
- WebSocket broadcast
- balance reserve/release
- ledger posting
- fee calculation
- event store
- outbox/inbox
- market owner fencing
- JMH 최적화

이것들을 먼저 넣으면 매칭 규칙 검증이 흐려진다.

## 완료 기준

Phase 1 완료 기준:

- `domain-matching`이 Spring/JPA/Kafka 없이 컴파일된다.
- `:domain-matching:test`가 통과한다.
- `Double`, `Float`를 사용하지 않는다.
- limit buy/sell이 가격-시간 우선순위로 체결된다.
- partial fill과 cancel이 동작한다.
- 체결 가격은 maker price다.
- 처리 후 crossed book이 남지 않는다.
- 같은 command stream replay 결과가 같다.

이 기준을 만족하면 Phase 2에서 ledger reserve/release를 붙일 수 있다.
