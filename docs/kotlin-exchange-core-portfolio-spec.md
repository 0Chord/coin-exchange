# Kotlin Exchange Core Portfolio Spec

## 1. 결론

이 포트폴리오는 단순한 거래소 클론이 아니라, Kotlin + Spring Boot로 만드는 **Event-Sourced Order Matching and Double-Entry Ledger System**이다.

핵심 목표는 다음 다섯 가지다.

- 가격-시간 우선순위 기반 주문 매칭 엔진을 직접 구현한다.
- 모든 잔고 변경을 복식부기 원장으로 설명 가능하게 만든다.
- 장애가 발생해도 idempotency, outbox/inbox, replay로 상태를 수렴시킨다.
- Binance, Coinbase, Kraken, OKX 스타일의 `snapshot + delta + sequence` 시장데이터 피드를 제공한다.
- JMH, k6, Micrometer, Grafana로 성능과 병목을 수치로 증명한다.

포트폴리오 관점에서 가장 강한 메시지는 "빠르게 주문을 받는다"가 아니라 **돈이 틀어지지 않고, 이벤트가 유실되지 않고, 같은 입력을 재생하면 같은 상태로 복구된다**이다.

## 2. 에이전트 토론 합의

2026-06-25 기준 웹 리서치와 에이전트 토론 결과, 이 프로젝트는 "거래소 전체 클론"이 아니라 **외국계 거래소 공개 피드 스타일을 참고한 deterministic exchange core**로 잡는다.

| 역할 | 핵심 결론 |
|---|---|
| 포트폴리오 평가자 | 거래소 전체보다 작고 어려운 핵심을 정확히 구현해야 한다. README에는 정합성, 장애 복구, 성능 지표가 보여야 한다. |
| 매칭 엔진 아키텍트 | 단일 마켓부터 시작하고, 마켓별 single writer와 엔진 시퀀스로 결정성을 보장해야 한다. |
| 장부/정합성 아키텍트 | 단순 잔고 테이블이 아니라 double-entry ledger, idempotency, outbox/inbox, replay가 포트폴리오의 차별점이다. |
| 시장데이터 아키텍트 | 거래소다운 실시간성은 WebSocket `trade tick`, `order book snapshot/delta`, `candle update`, `sequence gap recovery`로 보여야 한다. |
| 확장성 아키텍트 | 같은 마켓은 active-active로 동시에 매칭하지 않는다. `marketId` partition, active owner, fencing token, snapshot+replay failover를 설계한다. |
| 성능/인프라 담당 | "빠르다"가 아니라 목표를 세우고 JMH/k6/관측성으로 측정하고 병목을 설명해야 한다. |

합의된 최종 방향:

- 프로젝트명: `kotlin-exchange-core`
- 형태: 모듈러 모놀리스 우선, Kafka 기반 market partition과 비동기 projection까지 확장
- 1차 범위: 단일 마켓 `BTC-KRW`
- 2차 범위: 다중 마켓 in-process partitioning, Kafka `marketId` key partitioning, owner failover simulation
- 핵심 기능: 지정가/시장가/IOC 주문, 주문 취소, 부분 체결, 잔고 동결, 체결 원장 반영, event replay, WebSocket market data
- 핵심 증명: 매칭 결정성, 장부 불변식, 중복 처리 방지, 장애 후 복구, WebSocket gap recovery, 성능 리포트

## 3. 프로젝트 포지셔닝

### 한 줄 소개

Kotlin + Spring Boot로 구현한 이벤트 소싱 기반 현물 거래소 코어 시스템. 가격-시간 우선순위 매칭 엔진, 복식부기 원장, 실시간 WebSocket 시장데이터, 장애 복구, 성능 벤치마크를 포함한다.

### 면접에서 전달할 메시지

- 오더북은 마켓별 single writer로 결정성을 지킨다.
- 원장은 append-only posting으로만 변경하며, 잔고 projection은 캐시로 취급한다.
- Kafka나 broker의 exactly-once에 기대지 않고, 애플리케이션 레벨 idempotency로 중복을 막는다.
- event store가 source of truth이고, read model은 replay로 재생성 가능하다.
- WebSocket market data는 snapshot, delta, sequence, gap recovery를 갖춘 상태 동기화 프로토콜로 설계한다.
- 다중 서버 확장은 같은 마켓 active-active가 아니라 `marketId` partition과 active owner failover로 처리한다.
- 성능은 감으로 말하지 않고 p95/p99, throughput, replay speed, consumer lag로 증명한다.

### 만들지 않을 것

이 프로젝트는 포트폴리오 완성도를 위해 아래 범위를 의도적으로 제외한다.

- 외부 거래소 주문 연동과 private stream
- 블록체인 입출금
- KYC/AML
- 차트/호가창 프론트엔드 풀 구현
- 모든 주문 타입 전체 지원
- 초저지연 HFT 수준 최적화
- 처음부터 Kubernetes, microservice full split
- Redis를 원장 저장소로 사용하는 구조
- 같은 마켓 오더북의 multi-primary active-active 동기화
- Raft/분산 합의 직접 구현

## 3-A. 외국계 거래소 리서치 기반 지향점

공개 문서 기준으로 Binance, Coinbase Exchange, Kraken, BitMEX, OKX는 시장데이터 피드에서 공통적으로 **초기 snapshot + 이후 delta + 누락 감지 + 재동기화**를 제공한다. 이 포트폴리오는 외부 거래소 adapter를 만드는 프로젝트가 아니라, 그 설계 원칙을 자체 거래소 market data API에 반영한다.

| 거래소 | 참고할 패턴 | 포트폴리오 반영 |
|---|---|---|
| Binance Spot | REST order book snapshot 이후 depth diff stream을 update id로 이어 붙임 | 최초 REST snapshot + WebSocket delta buffer + sequence gap recovery |
| Coinbase Exchange | `level2` snapshot/update, product별 sequence, ticker/matches feed | `orderbook.snapshot`, `orderbook.delta`, `trade.tick`, `ticker` 채널 분리 |
| Kraken Spot | L2 book update 후 top-of-book checksum 검증 | 선택 기능으로 top N checksum 제공, mismatch 시 resync |
| BitMEX | `partial` 초기 이미지와 `insert/update/delete` table delta | delta action을 명시하고, unknown update는 stale 처리 |
| OKX | `prevSeqId`/`seqId` continuity check, candle `confirm` | `previousSequence`/`sequence` 필드와 candle final flag 설계 |

포트폴리오에서 "거래소답다"는 것은 화려한 UI가 아니라 다음을 갖췄다는 뜻이다.

- 주문 체결은 deterministic matching engine이 만든다.
- 시장데이터는 체결과 오더북 변경 이벤트에서 파생된다.
- 최초 접속자는 snapshot을 받고, 이후 sequence가 붙은 delta를 받는다.
- sequence gap, checksum mismatch, slow client는 정상 운영 시나리오로 다룬다.
- candle은 trade tick을 집계한 read model이며, 오더북과 source가 다르다는 점을 명확히 설명한다.

## 3-B. 완성도 기준

이 포트폴리오의 완성도는 기능 개수가 아니라 검증 가능한 속성으로 판단한다.

| 레벨 | 목표 | 완료 증거 |
|---|---|---|
| Core Correctness | 가격-시간 우선순위, 부분 체결, 취소, crossed book 방지 | matching unit/property tests, deterministic replay checksum |
| Money Correctness | hold/release, trade/fee posting, double-entry invariant | ledger invariant test, reconciliation report |
| Failure Safety | idempotency, outbox/inbox, projection rebuild, replay | failure scenario integration tests |
| Market Data | trade tick, orderbook snapshot/delta, candle update, gap recovery | WebSocket fixture tests, sequence gap resync test |
| Scaling Design | market partition, active owner, fencing token, failover | ownership/fencing simulation test, ADR |
| Operation Proof | JMH, k6, metrics, dashboard, bottleneck report | reproducible benchmark scripts and README results |

## 4. 기술 스택

### 필수 스택

| 영역 | 선택 |
|---|---|
| Language | Kotlin |
| Framework | Spring Boot |
| DB | PostgreSQL |
| Messaging | Kafka, 단 MVP 초반에는 DB outbox만으로 시작 가능 |
| Realtime | Spring WebSocket 또는 Netty 기반 WebSocket, STOMP는 사용하지 않는 raw JSON protocol 우선 |
| Cache | Redis, rate limit/idempotency/order book snapshot 캐시 용도 |
| Test | JUnit 5, Kotest, Testcontainers |
| Load Test | k6 |
| Microbenchmark | JMH |
| Observability | Spring Actuator, Micrometer, Prometheus, Grafana, OpenTelemetry |
| Profiling | JFR, async-profiler |
| API 문서 | OpenAPI/Swagger |

### Kotlin/Spring 선택 이유

- Kotlin data class, sealed class, value class로 command/event/domain model 표현력이 좋다.
- Spring Boot는 Actuator, metrics, transaction, Kafka integration, Testcontainers 연동이 좋다.
- 매칭 엔진 코어는 Spring 의존성을 제거한 순수 Kotlin 모듈로 두면 벤치마크와 테스트가 쉽다.

### 권장 모듈 구조

```text
kotlin-exchange-core
├── app-api
│   ├── 주문/취소/조회 REST API
│   └── 인증은 demo user 수준으로 최소화
├── domain-order
│   ├── Order aggregate
│   ├── Order command/event
│   └── 주문 상태 머신
├── domain-matching
│   ├── OrderBook
│   ├── MatchingEngine
│   ├── PriceLevel
│   └── 순수 Kotlin 벤치마크 대상
├── domain-ledger
│   ├── LedgerTransaction
│   ├── LedgerPosting
│   ├── Account
│   └── double-entry invariant
├── domain-settlement
│   ├── SettlementBatch
│   ├── Reconciliation
│   └── 정산 상태 머신
├── market-data
│   ├── TradeTickPublisher
│   ├── OrderBookSnapshotService
│   ├── OrderBookDeltaSequencer
│   ├── CandleAggregator
│   └── WebSocket fanout/backpressure
├── infrastructure-persistence
│   ├── PostgreSQL event store
│   ├── outbox/inbox
│   └── projection tables
├── infrastructure-messaging
│   ├── Kafka producer/consumer
│   └── DLQ/retry
├── benchmark-jmh
│   └── OrderBook, MatchingEngine, Ledger apply benchmark
└── loadtest-k6
    └── smoke, baseline, spike, soak scripts
```

### 관련 기술 후보 확장

기술은 포트폴리오 점수를 올리기 위해 넣는 것이 아니라, 문제를 설명하기 위해 넣는다. 아래 표는 채택, 보류, 실험 대상을 분리한다.

| 기술/패턴 | 상태 | 쓰는 이유 | 주의점 |
|---|---|---|---|
| Spring MVC + JDBC/JPA | 채택 후보 | 구현 단순성, 트랜잭션 이해를 보여주기 좋음 | 높은 동시성에서는 thread pool/connection pool 병목을 반드시 측정 |
| Spring MVC + Virtual Threads | 실험 후보 | blocking DB I/O가 많은 API에서 thread blocking 비용을 낮출 수 있음 | pinned virtual thread, DB connection pool 한계 확인 필요 |
| Spring WebFlux | 실험 후보 | non-blocking, backpressure, Netty 기반 구조를 비교 실험 가능 | R2DBC까지 같이 가지 않으면 전체 non-blocking 이점이 줄어듦 |
| Kotlin Coroutines | 채택 후보 | actor-like market loop, structured concurrency, async projection worker 구현에 적합 | 공유 상태를 늘리면 오히려 결정성이 깨짐 |
| Spring Modulith | 보류/선택 | 모듈러 모놀리스 경계와 application event 테스트에 도움 | 필수는 아님. 포트폴리오 핵심은 도메인/정합성 |
| PostgreSQL append-only event store | 채택 | event store, outbox, ledger를 트랜잭션으로 묶기 쉬움 | 이벤트 폭증 시 partition, index, vacuum, WAL 비용 측정 |
| Kafka | 확장 채택 | projection, ledger consumer, market data feed 분리 | 순서 보장 key 선택이 핵심. exactly-once에 과신 금지 |
| Debezium Outbox Event Router | 확장 실험 | DB outbox row를 CDC로 Kafka에 안정적으로 전달 가능 | 운영 복잡도가 증가하므로 MVP 이후 |
| Redis | 제한 채택 | idempotency TTL, rate limit, order book snapshot cache | 원장/진실 데이터 저장 금지 |
| LMAX Disruptor | 심화 실험 | ring buffer 기반 low-latency command pipeline 비교 | 포트폴리오 MVP에는 과함. JMH 비교 실험으로만 적합 |
| JCTools MPSC/SPSC Queue | 심화 실험 | market command queue 자료구조 성능 비교 | 직접 도입 전 JMH로 ArrayBlockingQueue/Channel과 비교 |
| HdrHistogram | 실험 후보 | latency percentile 기록과 장시간 테스트 분석에 적합 | Micrometer histogram과 역할 중복 가능 |
| async-profiler/JFR | 채택 | CPU, allocation, lock, wall-clock 병목 분석 | 성능 개선 전/후 flame graph를 README에 남기면 강함 |
| Resilience4j | 선택 | 외부 정산 mock, Kafka/Redis 장애 대응 실험 | 코어 매칭 경로에는 불필요한 복잡도 가능 |

### 아키텍처 결정 기록

포트폴리오에는 ADR을 짧게 남기는 것이 좋다. 아래 결정들은 README와 `docs/adr/`에 독립 문서로 분리할 수 있다.

| ADR | 결정 | 이유 | 대안 |
|---|---|---|---|
| ADR-001 | 모듈러 모놀리스로 시작 | 트랜잭션, 테스트, 디버깅이 쉽고 포트폴리오 완성도가 높음 | 처음부터 microservices |
| ADR-002 | 마켓별 single writer matching engine | 오더북 결정성, FIFO 보장, 취소/체결 경합 단순화 | DB lock, distributed lock, synchronized order book |
| ADR-003 | event store를 source of truth로 둠 | replay, projection 재생성, 감사 추적 가능 | 현재 상태 테이블만 저장 |
| ADR-004 | balance update 대신 double-entry ledger | 돈/자산 이동을 회계적으로 검증 가능 | user_balances 직접 update |
| ADR-005 | outbox/inbox로 at-least-once를 수렴 | DB commit과 message publish 불일치 방지 | Kafka transaction만 의존 |
| ADR-006 | projection은 캐시로 취급 | 장애 시 삭제 후 replay 가능 | projection을 진실 데이터로 취급 |
| ADR-007 | fixed decimal/value object 사용 | `Double` 부동소수 오차 방지 | `Double`, `BigDecimal` 무분별 사용 |
| ADR-008 | Kafka partition key는 market/order/account별로 분리 | 순서 보장 단위가 이벤트 종류마다 다름 | 모든 이벤트에 같은 key 사용 |
| ADR-009 | Redis는 보조 저장소로만 사용 | 캐시 장애가 거래 정합성을 깨지 않게 함 | Redis에 잔고/오더북 진실 저장 |
| ADR-010 | 시장데이터는 snapshot + sequence delta로 제공 | WebSocket 누락/재연결 시 복구 가능 | 전체 오더북을 매 tick broadcast |
| ADR-011 | 같은 마켓 active-active matching 금지 | price-time priority는 CRDT 병합 대상이 아님 | 여러 서버가 같은 book 동시 변경 |
| ADR-012 | market owner epoch/fencing token 사용 | stale owner의 늦은 write를 거부 | Redis lock만 신뢰 |
| ADR-013 | 성능 주장은 JMH/k6/JFR 결과로만 한다 | 포트폴리오 신뢰도 확보 | "빠르다"는 설명만 작성 |

### 아키텍처 뷰

#### MVP 아키텍처

```text
Client
  -> Spring API
  -> Command Validation
  -> Idempotency Store
  -> Ledger Reserve
  -> Event Store + Outbox
  -> Market Command Queue
  -> Single Writer Matching Engine
  -> Trade Events
  -> Ledger Posting
  -> Read Model Projection
```

MVP에서는 Kafka 없이도 DB outbox relay worker로 시작할 수 있다. 이렇게 하면 event store, ledger, outbox를 하나의 PostgreSQL transaction으로 다룰 수 있어서 정합성 설명이 쉽다.

#### 확장 아키텍처

```text
Client
  -> API Nodes
  -> Rate Limit / Idempotency
  -> Command Store
  -> Market Partition Router
  -> Matching Engine Workers by Market
  -> Event Store / Outbox
  -> Kafka Topics
       -> Ledger Consumer
       -> Projection Consumer
       -> Market Data Consumer
       -> Settlement Consumer
  -> Read Models / Cache
```

확장 단계에서는 쓰기 모델과 조회 모델을 명확히 분리한다. 매칭 순서가 중요한 이벤트는 `marketId`를 key로 잡고, 사용자 잔고/알림처럼 계정 순서가 중요한 이벤트는 `accountId` key를 검토한다.

#### Market data 아키텍처

```text
Matching Engine
  -> TradeExecuted / OrderBookChanged events
  -> MarketData Sequencer
  -> Trade Tick Stream
  -> OrderBook Snapshot Store
  -> OrderBook Delta Stream
  -> Candle Aggregator
  -> WebSocket Fanout
```

원칙:

- Matching engine은 WebSocket client를 직접 알지 않는다.
- market data는 체결/오더북 변경 이벤트에서 파생되는 read model이다.
- order book 구독자는 먼저 snapshot을 받고, 이후 `sequence`가 붙은 delta를 적용한다.
- delta sequence가 끊기면 client는 local book을 폐기하고 snapshot부터 다시 동기화한다.
- candle은 trade tick을 집계한 결과이며, order book snapshot으로 만들지 않는다.

## 5. 도메인 경계

### Order

책임:

- 주문 요청 검증
- idempotency key 검증
- 주문 상태 전이
- 잔고 동결 요청
- 취소 요청 처리

주요 상태:

- `REQUESTED`
- `REJECTED`
- `RESERVED`
- `ACCEPTED`
- `PARTIALLY_FILLED`
- `FILLED`
- `CANCEL_REQUESTED`
- `CANCELLED`

### Matching

책임:

- 마켓별 오더북 관리
- 가격-시간 우선순위 매칭
- maker/taker 결정
- 체결 이벤트 생성
- 오더북 snapshot 생성

중요 원칙:

- 같은 마켓 오더북은 한 스레드만 변경한다.
- 서버 수신 시간이 아니라 매칭 엔진이 부여한 `engineSequence`를 시간 우선순위 기준으로 쓴다.
- 동일한 command/event 순서를 replay하면 동일한 체결 결과가 나와야 한다.

### Ledger

책임:

- 잔고 동결
- 잔고 해제
- 체결 반영
- 수수료 반영
- reversal transaction
- 원장 불변식 검증

중요 원칙:

- 잔고 변경은 직접 update하지 않는다.
- 모든 변경은 `LedgerTransaction`과 `LedgerPosting`의 append로 발생한다.
- projection balance는 조회 성능을 위한 캐시다.
- 원장이 source of truth다.

### Settlement

책임:

- 체결 후 정산 상태 관리
- 수수료 정산
- 일별 마감
- 대사 reconciliation
- 실패 재시도와 수동 검토 상태 관리

포트폴리오 MVP에서는 외부 은행/지갑 연동 없이 내부 정산과 대사까지만 구현한다.

## 6. 핵심 도메인 모델

### Market

필드:

- `marketId`: `BTC-KRW`
- `baseAsset`: `BTC`
- `quoteAsset`: `KRW`
- `priceTickSize`
- `quantityStepSize`
- `minOrderAmount`
- `status`: `ACTIVE`, `HALTED`, `CLOSED`

검증:

- 가격은 tick size의 배수여야 한다.
- 수량은 step size의 배수여야 한다.
- 주문 금액은 최소 주문 금액 이상이어야 한다.

### Order

필드:

- `orderId`
- `userId`
- `marketId`
- `side`: `BUY`, `SELL`
- `type`: `LIMIT`, `MARKET`
- `timeInForce`: `GTC`, `IOC`
- `limitPrice`
- `quantity`
- `remainingQuantity`
- `filledQuantity`
- `cancelledQuantity`
- `status`
- `idempotencyKey`
- `engineSequence`
- `createdAt`
- `version`

불변식:

- `quantity = remainingQuantity + filledQuantity + cancelledQuantity`
- `remainingQuantity >= 0`
- `filledQuantity >= 0`
- `FILLED` 주문의 `remainingQuantity`는 0이다.
- `CANCELLED` 주문은 추가 체결될 수 없다.

### OrderBook

구조:

- bids: 가격 내림차순
- asks: 가격 오름차순
- price level: 같은 가격 주문 FIFO queue
- order index: `orderId -> order location`

권장 자료구조:

- 가격 레벨: `TreeMap<Price, PriceLevel>`
- 주문 큐: doubly linked list 또는 `ArrayDeque` 기반 queue
- 취소 인덱스: `HashMap<OrderId, OrderRef>`

복잡도 목표:

- best bid/ask 조회: `O(log P)` 또는 캐시 사용 시 `O(1)`
- 가격 레벨 삽입/삭제: `O(log P)`
- 일반 매칭: 체결되는 maker 주문 수에 비례
- 취소: order index를 두어 `O(1)`에 가깝게 처리

### Trade

필드:

- `tradeId`
- `marketId`
- `makerOrderId`
- `takerOrderId`
- `buyerUserId`
- `sellerUserId`
- `price`
- `quantity`
- `quoteAmount`
- `makerFee`
- `takerFee`
- `engineSequence`
- `occurredAt`

정책:

- 체결 가격은 maker 주문의 가격을 따른다.
- 수수료는 명확한 정책을 둔다. 예: taker 0.1%, maker 0.05%.
- 모든 금액은 `Double` 금지. 정수 스케일 또는 fixed decimal value object를 사용한다.

### TradeTick

WebSocket으로 내려가는 체결 1건 단위 market data event다. `TradeExecuted` domain event에서 파생된다.

필드:

- `type`: `trade`
- `marketId`
- `tradeId`
- `price`
- `quantity`
- `side`: taker 기준 `BUY` 또는 `SELL`
- `sequence`: market data sequence
- `engineSequence`: matching engine sequence
- `occurredAt`

정책:

- trade tick은 체결마다 발행한다.
- REST trade history와 WebSocket trade stream은 같은 `tradeId`를 공유한다.
- client가 누락을 감지할 수 있도록 단조 증가 `sequence`를 포함한다.

### OrderBookSnapshot

특정 시점의 top N 또는 full order book read model이다.

필드:

- `marketId`
- `sequence`: snapshot이 반영한 마지막 market data sequence
- `engineSequence`: snapshot이 반영한 마지막 matching engine sequence
- `depth`
- `bids`: 가격 내림차순 `[price, quantity]`
- `asks`: 가격 오름차순 `[price, quantity]`
- `checksum`: 선택. top N 기준 CRC32 또는 SHA-256 hash

정책:

- 최초 WebSocket 구독자는 snapshot을 먼저 받아야 한다.
- snapshot 이후 delta의 `previousSequence`가 snapshot sequence와 이어져야 한다.
- sequence gap이 있으면 local book은 stale 처리하고 snapshot을 다시 요청한다.

### OrderBookDelta

오더북 가격 레벨 변경 이벤트다. 전체 주문 목록이 아니라 가격별 집계 수량을 내려준다.

필드:

- `type`: `orderbook_delta`
- `marketId`
- `previousSequence`
- `sequence`
- `bids`: 변경된 bid price level 목록
- `asks`: 변경된 ask price level 목록
- `checksum`: 선택

정책:

- `quantity = 0`은 해당 price level 삭제를 의미한다.
- delta는 50~100ms 단위 coalescing을 허용한다.
- delta 누락이 감지되면 client는 더 이상 해당 local book을 신뢰하면 안 된다.

### Candle

차트용 OHLCV read model이다. 오더북이 아니라 `TradeTick` 또는 `TradeExecuted`를 시간 구간으로 집계한다.

필드:

- `marketId`
- `interval`: `1m`, `5m`, `15m`, `1h`
- `openTime`
- `open`
- `high`
- `low`
- `close`
- `volume`
- `quoteVolume`
- `tradeCount`
- `final`: 해당 interval 마감 여부

정책:

- REST candle API는 과거 확정 candle과 진행 중 candle을 반환할 수 있다.
- WebSocket candle update는 진행 중 candle을 trade tick마다 또는 1초 단위로 갱신한다.
- `final = true` 이벤트가 오면 해당 interval candle은 더 이상 변경하지 않는다.

### LedgerAccount

계정 예시:

- `USER:{userId}:KRW:AVAILABLE`
- `USER:{userId}:KRW:HOLD`
- `USER:{userId}:BTC:AVAILABLE`
- `USER:{userId}:BTC:HOLD`
- `SYSTEM:KRW:FEE_REVENUE`
- `SYSTEM:KRW:SETTLEMENT_CLEARING`
- `SYSTEM:BTC:SETTLEMENT_CLEARING`

### LedgerTransaction

필드:

- `ledgerTransactionId`
- `sourceEventId`
- `causationId`
- `correlationId`
- `type`: `RESERVE`, `RELEASE`, `TRADE`, `FEE`, `SETTLEMENT`, `REVERSAL`
- `occurredAt`

제약:

- `sourceEventId` unique
- 하나의 transaction 안에서 asset별 debit 합계와 credit 합계가 같아야 한다.

### LedgerPosting

필드:

- `postingId`
- `ledgerTransactionId`
- `accountId`
- `asset`
- `side`: `DEBIT`, `CREDIT`
- `amount`
- `sequence`

제약:

- amount는 양수만 허용한다.
- posting은 수정/삭제하지 않는다.
- 오류는 reversal transaction으로 보정한다.

## 7. 주문 타입 범위

### MVP 필수

| 타입 | 설명 |
|---|---|
| `LIMIT` | 지정 가격 이하/이상에서만 체결된다. 잔량은 오더북에 남는다. |
| `MARKET` | 현재 반대편 호가를 즉시 소진한다. 시장가 매수는 최대 quote amount 기반으로 처리한다. |
| `CANCEL` | 미체결 잔량을 취소하고 hold를 available로 되돌린다. |
| `IOC` | 즉시 체결 가능한 수량만 체결하고 잔량은 취소한다. |

### 확장

| 타입 | 설명 |
|---|---|
| `FOK` | 전량 즉시 체결 가능할 때만 체결한다. |
| `POST_ONLY` | 즉시 체결될 주문이면 거부하고 maker 주문만 허용한다. |
| `GTD` | 특정 시각까지 유효하다. |
| Stop order | MVP 제외. 상태와 트리거가 늘어나므로 후순위. |

## 8. 매칭 알고리즘 명세

### 매수 주문 체결 조건

- 반대편 best ask가 존재해야 한다.
- 시장가 매수는 잔여 예산 안에서 가능한 만큼 체결한다.
- 지정가 매수는 `bestAsk <= limitPrice`일 때 체결한다.

### 매도 주문 체결 조건

- 반대편 best bid가 존재해야 한다.
- 시장가 매도는 가능한 만큼 체결한다.
- 지정가 매도는 `bestBid >= limitPrice`일 때 체결한다.

### 처리 순서

1. API command idempotency를 확인한다.
2. market status, tick size, quantity step, min notional을 검증한다.
3. 매수는 quote asset, 매도는 base asset을 hold 계정으로 동결한다.
4. 동결 성공 이벤트가 있어야 order book 진입이 가능하다.
5. matching engine queue에 command를 넣는다.
6. 마켓별 single writer가 command를 순서대로 처리한다.
7. 반대편 best price를 확인한다.
8. 가격 조건을 만족하면 같은 가격 레벨의 FIFO maker 주문을 선택한다.
9. 체결 수량은 `min(taker.remaining, maker.remaining)`이다.
10. 체결 가격은 maker 가격이다.
11. maker/taker 잔량과 상태를 갱신한다.
12. maker가 완전 체결되면 queue에서 제거한다.
13. 가격 레벨이 비면 price level을 제거한다.
14. taker 잔량이 남고 장부 등록 가능한 주문이면 자기 장부에 등록한다.
15. `TradeExecuted`, `OrderPartiallyFilled`, `OrderFilled`, `OrderEnteredBook` 이벤트를 append한다.
16. outbox에 publish 대상 이벤트를 같은 DB transaction으로 저장한다.
17. ledger projection/consumer가 체결 이벤트를 받아 posting한다.

### 결정성

결정성 요구:

- 같은 event stream을 replay하면 같은 order book 상태가 나와야 한다.
- 같은 event stream을 replay하면 같은 trade sequence가 나와야 한다.
- thread scheduling 차이가 체결 순서를 바꾸면 안 된다.

구현 정책:

- market별 command queue
- market별 단조 증가 `engineSequence`
- command 처리 중 wall clock 의존 최소화
- replay mode에서는 외부 side effect 금지

## 9. 장부 설계

### 복식부기 원칙

모든 회계 사건은 `LedgerTransaction` 하나와 여러 `LedgerPosting`으로 표현한다.

원칙:

- asset별 debit 합계와 credit 합계가 같아야 한다.
- 잔고는 posting 합계로 계산 가능해야 한다.
- projection balance는 성능용이며 원장이 진실이다.
- 원장 record는 append-only다.

### 예시 1: 매수 주문 KRW 동결

사용자 A가 100,000 KRW어치 매수 주문을 낸다.

| Side | Account | Asset | Amount |
|---|---|---|---|
| DEBIT | `USER:A:KRW:HOLD` | KRW | 100000 |
| CREDIT | `USER:A:KRW:AVAILABLE` | KRW | 100000 |

### 예시 2: 매도 주문 BTC 동결

사용자 B가 1 BTC 매도 주문을 낸다.

| Side | Account | Asset | Amount |
|---|---|---|---|
| DEBIT | `USER:B:BTC:HOLD` | BTC | 1 |
| CREDIT | `USER:B:BTC:AVAILABLE` | BTC | 1 |

### 예시 3: 1 BTC, 100,000 KRW 체결, 수수료 100 KRW

KRW:

| Side | Account | Asset | Amount |
|---|---|---|---|
| CREDIT | `USER:A:KRW:HOLD` | KRW | 100000 |
| DEBIT | `USER:B:KRW:AVAILABLE` | KRW | 99900 |
| DEBIT | `SYSTEM:KRW:FEE_REVENUE` | KRW | 100 |

BTC:

| Side | Account | Asset | Amount |
|---|---|---|---|
| CREDIT | `USER:B:BTC:HOLD` | BTC | 1 |
| DEBIT | `USER:A:BTC:AVAILABLE` | BTC | 1 |

### 부분 체결 후 초과 동결 해제

지정가 매수에서 실제 체결 가격이 주문 가격보다 낮으면 초과 동결액이 생긴다.

정책:

- 체결마다 사용된 quote amount를 계산한다.
- 남은 주문에 필요한 hold amount를 재계산한다.
- 이미 hold된 금액 중 불필요한 초과분은 `BalanceReleased`로 available에 돌린다.
- 해제 금액은 projection이 아니라 원장 posting 누적으로 계산한다.

## 10. 이벤트 소싱 명세

### Event Store

필드:

- `eventId`
- `aggregateType`
- `aggregateId`
- `aggregateVersion`
- `globalSequence`
- `eventType`
- `payload`
- `metadata`
- `schemaVersion`
- `occurredAt`

제약:

- `(aggregateId, aggregateVersion)` unique
- `eventId` unique
- `globalSequence` unique

### 주요 이벤트

Order:

- `OrderRequested`
- `OrderRejected`
- `BalanceReserveRequested`
- `BalanceReserved`
- `OrderAccepted`
- `OrderEnteredBook`
- `OrderPartiallyFilled`
- `OrderFilled`
- `OrderCancelRequested`
- `OrderCancelled`

Matching:

- `TradeExecuted`
- `BookUpdated`
- `OrderRemovedFromBook`

Ledger:

- `BalanceReserved`
- `BalanceReleased`
- `TradePosted`
- `FeePosted`
- `LedgerReversed`

Settlement:

- `SettlementBatchOpened`
- `SettlementItemAdded`
- `SettlementBatchClosed`
- `SettlementPosted`
- `SettlementFailed`
- `SettlementRetried`

### Replay

Replay 종류:

- full replay: 전체 read model 재생성
- aggregate replay: 특정 주문/계정만 복원
- point-in-time replay: 특정 sequence까지 복원
- snapshot replay: snapshot 이후 이벤트만 적용

Replay 규칙:

- 외부 API 호출 금지
- Kafka publish 금지
- email/webhook/notification 금지
- projection handler는 deterministic해야 한다.
- replay 대상 projection은 별도 테이블에 만들고 검증 후 swap하는 방식을 권장한다.

### Snapshot

대상:

- order book
- order aggregate
- account balance projection
- settlement batch

필드:

- `snapshotId`
- `aggregateType`
- `aggregateId`
- `lastEventVersion`
- `lastGlobalSequence`
- `statePayload`
- `schemaVersion`
- `createdAt`

정책:

- MVP에서는 snapshot 없이 full replay부터 구현한다.
- 확장 단계에서 이벤트 N개마다 또는 replay 시간이 임계치를 넘을 때 snapshot을 만든다.
- snapshot이 깨져도 event store만 있으면 복구 가능해야 한다.

### Event Hash Chain과 Merkle Tree 판단

무결성 증명은 MVP 핵심이 아니지만, 감사 추적을 보여주고 싶다면 Merkle tree보다 event hash chain을 먼저 검토한다.

Event hash chain 필드:

- `eventId`
- `globalSequence`
- `payloadHash`
- `previousEventHash`
- `eventHash`

정책:

- `eventHash = hash(globalSequence + eventType + payloadHash + previousEventHash)`로 계산한다.
- 중간 이벤트가 조작되면 이후 hash chain 검증이 깨진다.
- replay checksum과 함께 사용하면 "같은 이벤트 로그에서 같은 상태가 나온다"를 더 잘 보여줄 수 있다.
- Merkle tree는 chunk 단위 proof나 외부 감사자에게 부분 증명을 제공해야 할 때만 선택 기능으로 둔다.
- orderbook checksum은 WebSocket client 동기화 검증용이고, event hash chain은 event store 변조 감지용이다. 둘을 혼동하지 않는다.

## 11. Idempotency, Outbox, Inbox

### API Command Idempotency

헤더:

- `Idempotency-Key`

정책:

- `(userId, idempotencyKey)` unique
- 같은 key와 같은 command hash면 기존 결과 반환
- 같은 key와 다른 payload면 `409 Conflict`

### Domain Event Idempotency

정책:

- 모든 event는 `eventId`를 가진다.
- ledger는 `sourceEventId` unique constraint로 같은 체결을 한 번만 posting한다.
- settlement도 `sourceEventId` 또는 `settlementId` unique constraint로 중복 반영을 막는다.

### Outbox

목적:

- DB commit 성공 후 Kafka publish 실패 문제를 막는다.

흐름:

1. command 처리
2. event store append
3. outbox row insert
4. 같은 DB transaction commit
5. relay worker가 outbox를 읽어 Kafka publish
6. 성공 시 outbox status를 `PUBLISHED`로 변경

필드:

- `outboxId`
- `eventId`
- `aggregateType`
- `aggregateId`
- `eventType`
- `payload`
- `headers`
- `status`
- `retryCount`
- `nextRetryAt`
- `createdAt`
- `publishedAt`

### Inbox

목적:

- 같은 Kafka message를 여러 번 받아도 한 번만 처리한다.

정책:

- `(messageId, consumerName)` unique
- 처리 성공 전 offset commit 금지
- 처리 실패 시 retry 가능한 상태로 남긴다.
- poison message는 DLQ로 보낸다.

## 12. API 명세 초안

### 주문 생성

`POST /api/v1/orders`

Headers:

- `Idempotency-Key: <uuid>`

Request:

```json
{
  "marketId": "BTC-KRW",
  "side": "BUY",
  "type": "LIMIT",
  "timeInForce": "GTC",
  "price": "100000000",
  "quantity": "0.01"
}
```

Response:

```json
{
  "orderId": "ord_123",
  "status": "ACCEPTED",
  "marketId": "BTC-KRW",
  "side": "BUY",
  "remainingQuantity": "0.01"
}
```

### 주문 취소

`POST /api/v1/orders/{orderId}/cancel`

Headers:

- `Idempotency-Key: <uuid>`

Response:

```json
{
  "orderId": "ord_123",
  "status": "CANCELLED",
  "releasedAmount": "1000000"
}
```

### 오더북 조회

`GET /api/v1/markets/{marketId}/order-book?depth=20`

Response:

```json
{
  "marketId": "BTC-KRW",
  "sequence": 99123,
  "bids": [
    { "price": "99000000", "quantity": "0.5" }
  ],
  "asks": [
    { "price": "100000000", "quantity": "0.3" }
  ]
}
```

### 체결 내역 조회

`GET /api/v1/markets/{marketId}/trades?limit=50`

### 잔고 조회

`GET /api/v1/accounts/{userId}/balances`

Response:

```json
{
  "userId": "user_1",
  "balances": [
    {
      "asset": "KRW",
      "available": "900000",
      "hold": "100000",
      "total": "1000000"
    }
  ]
}
```

### Reconciliation 실행

`POST /api/v1/admin/reconciliation/run`

관리자 API로 두고, 포트폴리오에서는 demo admin token 정도로 제한한다.

### 캔들 조회

`GET /api/v1/markets/{marketId}/candles?interval=1m&limit=200`

Response:

```json
[
  {
    "marketId": "BTC-KRW",
    "interval": "1m",
    "openTime": "2026-06-25T10:00:00Z",
    "open": "100000000",
    "high": "100500000",
    "low": "99800000",
    "close": "100100000",
    "volume": "1.25",
    "quoteVolume": "125125000",
    "tradeCount": 37,
    "final": true
  }
]
```

### WebSocket 구독

`WS /ws/market-data`

Subscribe request:

```json
{
  "op": "subscribe",
  "channels": [
    "trades:BTC-KRW",
    "orderbook:BTC-KRW:20",
    "ticker:BTC-KRW",
    "candles:BTC-KRW:1m"
  ]
}
```

#### `trades:{marketId}`

체결 1건마다 trade tick을 발행한다.

```json
{
  "type": "trade",
  "marketId": "BTC-KRW",
  "tradeId": "trd_10001",
  "price": "100000000",
  "quantity": "0.25",
  "side": "BUY",
  "sequence": 3812,
  "engineSequence": 120034,
  "occurredAt": "2026-06-25T10:00:01.123Z"
}
```

#### `orderbook:{marketId}:{depth}`

구독 직후 snapshot을 내려주고, 이후 delta를 내려준다.

Snapshot:

```json
{
  "type": "orderbook_snapshot",
  "marketId": "BTC-KRW",
  "sequence": 3812,
  "depth": 20,
  "bids": [
    { "price": "99500000", "quantity": "1.2" }
  ],
  "asks": [
    { "price": "100000000", "quantity": "0.6" }
  ],
  "checksum": "optional"
}
```

Delta:

```json
{
  "type": "orderbook_delta",
  "marketId": "BTC-KRW",
  "previousSequence": 3812,
  "sequence": 3813,
  "bids": [
    { "price": "99500000", "quantity": "1.0" }
  ],
  "asks": [
    { "price": "100000000", "quantity": "0" }
  ],
  "checksum": "optional"
}
```

Client rule:

- 최초 snapshot의 `sequence`를 local book 기준점으로 삼는다.
- 다음 delta의 `previousSequence`가 local sequence와 다르면 gap이다.
- gap이 발생하면 local book을 폐기하고 snapshot을 다시 요청한다.
- `quantity = 0`은 price level 삭제다.
- checksum이 제공되고 mismatch가 발생하면 gap과 동일하게 stale 처리한다.

#### `candles:{marketId}:{interval}`

현재 진행 중 candle과 마감 candle을 발행한다.

```json
{
  "type": "candle",
  "marketId": "BTC-KRW",
  "interval": "1m",
  "openTime": "2026-06-25T10:00:00Z",
  "open": "100000000",
  "high": "100500000",
  "low": "99800000",
  "close": "100100000",
  "volume": "1.25",
  "tradeCount": 37,
  "final": false
}
```

Backpressure policy:

- client별 bounded outbound queue를 둔다.
- queue가 임계치를 넘으면 orderbook delta를 계속 쌓지 않고 snapshot resync를 요구한다.
- 계속 느린 client는 disconnect한다.
- 지표로 `ws_queue_depth`, `ws_dropped_messages`, `ws_resync_requested_count`를 노출한다.

## 13. DB 설계 초안

### `events`

핵심 인덱스:

- `unique(event_id)`
- `unique(aggregate_id, aggregate_version)`
- `unique(global_sequence)`
- `index(aggregate_type, aggregate_id, aggregate_version)`
- `index(global_sequence)`

### `outbox_messages`

핵심 인덱스:

- `unique(event_id)`
- `index(status, next_retry_at)`
- `index(created_at)`

### `inbox_messages`

핵심 인덱스:

- `unique(message_id, consumer_name)`
- `index(status, updated_at)`

### `ledger_transactions`

핵심 인덱스:

- `unique(source_event_id)`
- `index(correlation_id)`
- `index(occurred_at)`

### `ledger_postings`

핵심 인덱스:

- `index(ledger_transaction_id)`
- `index(account_id, asset)`
- `index(asset, created_at)`

### `balance_projection`

핵심 인덱스:

- `unique(account_id, asset, balance_type)`

주의:

- projection은 삭제 후 replay로 재생성 가능해야 한다.
- balance projection이 원장과 다르면 원장이 우선이다.

### `orders_projection`

핵심 인덱스:

- `unique(order_id)`
- `index(user_id, created_at)`
- `index(market_id, status)`

### `trades_projection`

핵심 인덱스:

- `unique(trade_id)`
- `index(market_id, engine_sequence)`
- `index(maker_order_id)`
- `index(taker_order_id)`

### `candles`

핵심 인덱스:

- `unique(market_id, interval, open_time)`
- `index(market_id, interval, open_time desc)`

주의:

- trade event에서 upsert한다.
- 확정 candle은 `final = true` 이후 변경하지 않는다.
- replay 후 생성한 candle checksum이 기존 projection과 같아야 한다.

### `orderbook_snapshots`

핵심 인덱스:

- `unique(market_id, depth, sequence)`
- `index(market_id, created_at desc)`
- `index(market_id, engine_sequence desc)`

필드:

- `market_id`
- `depth`
- `sequence`
- `engine_sequence`
- `bids_json`
- `asks_json`
- `checksum`
- `created_at`

주의:

- WebSocket snapshot 응답과 engine recovery snapshot을 혼동하지 않는다.
- market data snapshot은 client 동기화용 read model이다.
- engine recovery snapshot은 matching engine 내부 상태 복구용 binary/json snapshot이다.

### `market_owner_leases`

다중 서버 확장 설계를 검증하기 위한 테이블이다. MVP에서 실제 클러스터를 띄우지 않더라도 failover simulation test에 사용한다.

핵심 인덱스:

- `unique(market_id)`
- `index(owner_node_id)`
- `index(lease_expires_at)`

필드:

- `market_id`
- `owner_node_id`
- `owner_epoch`
- `lease_expires_at`
- `updated_at`

정책:

- owner를 획득할 때 `owner_epoch`를 단조 증가시킨다.
- matching output, outbox message, snapshot에는 `owner_epoch`를 포함한다.
- downstream은 현재 epoch보다 낮은 stale output을 거부한다.
- Redis lock만으로 correctness를 보장한다고 주장하지 않는다.

## 14. 동시성 모델

### 권장 구조

```text
HTTP API
  -> Command validation
  -> Idempotency check
  -> Balance reserve
  -> Event store append + outbox
  -> Market command queue
  -> Single-writer matching engine per market
  -> Trade events
  -> Ledger consumer
  -> Projection update
```

### 왜 single writer인가

- 오더북은 가격 레벨, FIFO queue, order index가 같이 변한다.
- 락 기반으로 다중 writer를 허용하면 체결 순서와 취소 경합이 복잡해진다.
- 마켓 단위로 single writer를 두면 결정성과 복구가 쉬워진다.
- 병렬성은 마켓 파티셔닝으로 확보한다.

### 취소와 체결 경합

정책:

- 같은 마켓 command queue에 주문/취소를 모두 넣는다.
- 먼저 engineSequence를 받은 command가 먼저 확정된다.
- 체결 후 취소가 오면 미체결 잔량만 취소한다.
- 이미 filled된 주문의 cancel은 idempotent no-op 또는 rejected result로 처리한다.

### 다중 마켓 파티셔닝

확장 단위는 주문 한 건이 아니라 `marketId`다.

```text
BTC-KRW commands -> partition 0 -> Engine Worker A
ETH-KRW commands -> partition 1 -> Engine Worker B
SOL-KRW commands -> partition 2 -> Engine Worker C
```

원칙:

- 같은 `marketId`의 command는 항상 같은 순서화된 stream으로 들어간다.
- 서로 다른 마켓은 병렬 처리할 수 있다.
- 한 마켓 내부에서는 멀티스레드 매칭보다 single writer가 우선이다.
- Kafka를 쓸 경우 command topic key는 `marketId`다.
- Kafka partition 내부 ordering만 신뢰하고, 전체 topic ordering은 주장하지 않는다.
- partition 수 변경은 key-to-partition mapping에 영향을 줄 수 있으므로 routing table 또는 초기 over-partitioning을 검토한다.

### Market ownership과 fencing

다중 서버에서 가장 위험한 상황은 이전 owner가 GC pause, network partition, 긴 stop-the-world 이후 뒤늦게 살아나 stale result를 쓰는 것이다.

정책:

- 각 market은 한 시점에 하나의 active owner만 가진다.
- owner는 lease 갱신과 함께 단조 증가 `ownerEpoch`를 가진다.
- engine output, outbox, orderbook snapshot, market data delta에는 `ownerEpoch`를 붙인다.
- 현재 epoch보다 낮은 output은 DB unique/condition 또는 consumer 검증에서 거부한다.
- failover는 `latest engine snapshot + event/command replay`로 복구한다.

하지 않는 것:

- 같은 마켓을 여러 서버가 동시에 matching하고 나중에 병합하지 않는다.
- Redis distributed lock만으로 매칭 정합성을 보장한다고 말하지 않는다.
- CRDT/active-active eventual consistency로 price-time priority를 해결한다고 주장하지 않는다.

## 14-A. 성능 개선 아키텍처

### 병목별 개선 전략

| 병목 | 증상 | 1차 대응 | 2차 대응 | 포트폴리오에서 보여줄 증거 |
|---|---|---|---|---|
| API thread pool | p99 상승, queued request 증가 | connection pool/worker 수 조정 | virtual thread 또는 WebFlux 비교 | k6 p95/p99 전후 |
| DB connection pool | Hikari wait 증가, timeout | transaction 짧게 유지, pool size 조정 | write batching, outbox relay 분리 | Hikari metrics, DB TPS |
| Event store insert | insert latency 증가, WAL I/O 증가 | batch insert, index 최소화 | table partition, WAL/checkpoint 튜닝 | EXPLAIN, pg_stat_statements |
| Matching queue | command queue depth 증가 | market별 single writer 유지, queue 측정 | hot market shard 또는 backpressure | queue depth, matching latency |
| Ledger apply | consumer lag, posting 지연 | batch consume/apply, idempotent bulk insert | ledger partition/account shard | consumer lag 회복 시간 |
| Projection update | read model lag 증가 | projection worker 분리 | snapshot/rebuild table swap | projection lag |
| Kafka publish | outbox backlog 증가 | relay batch publish | Debezium outbox CDC | outbox pending count |
| Redis hot key | command latency 증가 | key 분산, TTL 정리 | local cache + Redis fallback | Redis latency, hot key report |
| GC/allocation | GC pause, allocation rate 증가 | value object allocation 줄이기 | primitive/array 기반 hot path | JFR/async-profiler flame graph |

### API 계층 선택지

#### 기본안: Spring MVC + JDBC/JPA

장점:

- 트랜잭션 경계가 직관적이다.
- Testcontainers 기반 통합 테스트가 쉽다.
- 포트폴리오에서 비즈니스 정합성에 집중하기 좋다.

주의:

- 요청 수가 늘면 servlet worker thread와 DB connection pool이 먼저 병목이 된다.
- API latency가 matching engine latency를 가리지 않도록 내부 metric을 분리해야 한다.

#### 실험안: Spring MVC + Virtual Threads

도입 목적:

- blocking I/O가 많은 주문 API에서 platform thread 점유 비용을 줄일 수 있는지 비교한다.

실험 방법:

- 동일 k6 시나리오를 platform thread와 virtual thread에서 각각 실행한다.
- p95/p99, CPU, memory, DB pool wait, pinned virtual thread 이벤트를 비교한다.
- Spring Boot 설정은 `spring.threads.virtual.enabled=true`로 실험한다.

주의:

- virtual thread는 DB connection 수를 늘려주지 않는다.
- synchronized block, native call, 일부 blocking 구간에서 pinned virtual thread가 생길 수 있으므로 JFR로 확인한다.

#### 실험안: WebFlux + R2DBC

도입 목적:

- 전체 request pipeline을 non-blocking으로 만들었을 때 API layer backpressure와 resource usage가 좋아지는지 비교한다.

주의:

- DB access가 blocking이면 WebFlux 이점이 줄어든다.
- 매칭 엔진 hot path는 reactive chain보다 명시적 command queue가 더 단순하고 결정적이다.
- MVP 기본안으로 쓰기보다 성능 비교 챕터로 두는 것이 낫다.

### Matching Engine Hot Path

성능 목표:

- command 처리 중 allocation 최소화
- lock 최소화
- clock 호출 최소화
- JSON serialization을 hot path 밖으로 이동
- DB write를 hot path에서 직접 수행하지 않음

구조:

```text
Command Ingress
  -> bounded market queue
  -> single writer matching loop
  -> in-memory order book mutation
  -> append trade/order events
  -> async persistence/publish boundary
```

개선 후보:

- `ArrayDeque` 기반 FIFO price level
- `HashMap<OrderId, OrderRef>`로 cancel 위치 찾기
- `TreeMap<Price, PriceLevel>` 기본 구현
- hot path에서 `BigDecimal` 대신 scaled long value object 사용
- market queue를 `Channel`, `ArrayBlockingQueue`, JCTools MPSC queue, Disruptor ring buffer로 JMH 비교

도입 기준:

- 기본 구조로 p99 목표를 못 맞출 때만 복잡한 queue를 도입한다.
- Disruptor/JCTools는 "포트폴리오 필수 기술"이 아니라 "성능 실험 챕터"로 두는 것이 좋다.

### Market Data Hot Path

성능 목표:

- trade tick은 체결 이벤트마다 지연 없이 발행한다.
- orderbook delta는 50~100ms 단위 coalescing으로 fanout 비용을 줄일 수 있다.
- ticker/candle은 trade tick에서 파생하되 matching loop를 막지 않는다.
- slow client가 전체 market data pipeline을 막지 않게 한다.

구조:

```text
TradeExecuted / OrderBookChanged
  -> MarketData Sequencer
  -> per-market ring/bounded buffer
  -> channel fanout
  -> per-client bounded queue
  -> WebSocket write
```

정책:

- WebSocket client별 outbound queue는 bounded로 둔다.
- orderbook delta가 많이 쌓이면 stale 처리 후 snapshot resync를 요구한다.
- trade tick은 유실 없이 보내는 것을 우선하되, client가 계속 느리면 disconnect한다.
- candle update는 trade마다 또는 1초 단위로 coalesce한다.
- 모든 market data message에는 `sequence`와 `createdAt`을 넣어 delivery lag를 측정한다.

측정:

- trade tick publish latency
- orderbook delta coalescing latency
- WebSocket delivery lag p95/p99
- active WebSocket clients
- per-client queue depth
- dropped/coalesced/resync count
- stale book count

### Backpressure와 Load Shedding

도입 이유:

- market queue가 무한정 쌓이면 API는 성공을 반환하지만 실제 체결 지연이 계속 커진다.
- 거래소 코어에서는 일정 수준 이상 부하가 오면 거부/지연/제한 정책이 필요하다.

정책:

- market command queue는 bounded queue로 둔다.
- queue depth가 임계치를 넘으면 신규 주문은 `429` 또는 `MARKET_BUSY`로 거부한다.
- cancel command는 주문 생성보다 높은 우선순위를 줄 수 있는지 별도 실험한다.
- account/market별 rate limit을 둔다.
- Redis 장애 시 rate limit은 degraded mode로 전환하되 주문 정합성은 유지한다.

측정:

- queue depth
- rejected command count
- time in queue
- matching execution time
- API accepted-to-matched latency

### Partitioning 전략

마켓 파티셔닝:

- matching engine의 기본 확장 단위다.
- `BTC-KRW`, `ETH-KRW`처럼 서로 다른 마켓은 독립 single writer로 처리한다.
- Kafka topic key는 market ordering이 필요한 이벤트에서 `marketId`를 사용한다.
- Kafka ordering은 partition 내부에만 있으므로 전체 topic ordering을 주장하지 않는다.
- partition 수를 바꾸면 key mapping이 바뀔 수 있으므로 초기 over-partitioning 또는 explicit routing table을 검토한다.
- `marketId -> partition -> owner node -> ownerEpoch` 매핑을 운영 지표로 노출한다.
- failover 후 old owner가 늦게 output을 쓰는 상황은 `ownerEpoch` fencing으로 거부한다.

계정 파티셔닝:

- ledger consumer에서는 account ordering이 중요할 수 있다.
- account별 순서가 필요한 이벤트는 `accountId` key를 고려한다.
- 단, 하나의 trade는 buyer/seller 두 계정을 동시에 건드리므로 원장 posting은 DB transaction과 unique constraint로 보호한다.

시간 파티셔닝:

- event store, ledger posting, trades projection은 시간이 지날수록 커진다.
- PostgreSQL declarative partitioning으로 월별/일별 partition을 검토한다.
- 자주 조회하는 최신 partition의 index가 memory에 더 잘 올라오게 하는 것이 목적이다.

### Read Model 성능 전략

조회 모델:

- `orders_projection`
- `trades_projection`
- `balance_projection`
- `order_book_snapshot`
- `daily_settlement_projection`

전략:

- write model과 read model은 분리한다.
- read model은 삭제 후 replay 가능해야 한다.
- order book depth 조회는 in-memory snapshot 또는 Redis cache로 제공할 수 있다.
- 정확한 잔고는 ledger projection을 조회하되, 이상 감지 시 ledger posting 합계로 재계산한다.

### 데이터 직렬화 전략

MVP:

- JSON으로 시작한다.
- event schema version을 반드시 둔다.

확장:

- Kafka message에는 Protobuf 또는 Avro를 검토한다.
- schema evolution 테스트를 추가한다.
- event store payload는 JSONB로 시작하고, 성능 병목 시 binary payload를 비교한다.

실험 기준:

- serialization latency
- payload size
- consumer CPU
- schema migration 난도

## 15. 장애 시나리오

### API timeout 후 클라이언트 재시도

처리:

- 같은 `Idempotency-Key`로 재시도한다.
- 서버는 기존 command result를 반환한다.
- 중복 주문은 생성되지 않는다.

검증:

- 같은 key로 100번 재시도해도 order는 1개만 생성된다.

### 동결 성공 후 order book 진입 실패

처리:

- 주문 상태를 `RESERVED_BUT_NOT_ENTERED` 또는 equivalent recoverable 상태로 둔다.
- recovery worker가 order book 진입을 재시도한다.
- 재시도 불가능하면 보상 이벤트로 hold를 해제한다.

### 체결 이벤트 저장 후 Kafka publish 실패

처리:

- outbox relay가 미발행 메시지를 재시도한다.
- event store와 outbox는 같은 DB transaction에 저장되어야 한다.

### Kafka message 중복 소비

처리:

- inbox unique constraint로 중복 메시지를 감지한다.
- ledger는 `sourceEventId` unique constraint로 중복 posting을 막는다.

### Projection 업데이트 실패

처리:

- event store와 ledger가 source of truth다.
- projection은 삭제 후 replay로 재생성한다.

### Ledger posting 중 프로세스 다운

처리:

- `ledger_transactions`와 `ledger_postings`는 같은 DB transaction으로 저장한다.
- 일부 posting만 저장된 상태는 허용하지 않는다.

### 정산 실패

처리:

- settlement state를 `FAILED_RETRYABLE`로 둔다.
- 재시도 시 settlement idempotency key로 중복 posting을 막는다.
- 명확한 오류는 reversal transaction으로 보정한다.

### Replay 중 side effect 발생 위험

처리:

- replay context flag를 둔다.
- replay handler와 side-effect handler를 분리한다.
- replay에서는 outbox write, Kafka publish, 외부 호출을 금지한다.

### WebSocket orderbook delta 누락

처리:

- client는 `previousSequence`와 local sequence를 비교한다.
- gap이 있으면 local book을 stale 처리한다.
- client는 snapshot을 다시 요청하고 이후 delta부터 재적용한다.
- server는 `sequence_gap_count`, `snapshot_resync_count`를 기록한다.

### WebSocket slow client

처리:

- client별 outbound queue를 bounded로 둔다.
- queue 초과 시 orderbook delta를 계속 쌓지 않고 resync를 요구한다.
- trade tick도 지연이 계속되면 client를 disconnect한다.
- 느린 client 하나가 전체 fanout loop를 막으면 안 된다.

### Market owner failover 중 stale output

처리:

- 새 owner는 더 큰 `ownerEpoch`를 발급받는다.
- old owner가 뒤늦게 output을 저장하거나 publish하면 epoch 검증에서 거부한다.
- failover 후 engine은 snapshot sequence 이후 event/command만 replay한다.
- stale reject count를 metric과 테스트로 남긴다.

## 16. 반드시 테스트할 불변식

### 오더북

- best bid는 모든 bid 중 최고가다.
- best ask는 모든 ask 중 최저가다.
- 정상 상태에서 `bestBid >= bestAsk`인 crossed book이 남아 있으면 안 된다.
- 같은 가격 레벨에서는 FIFO 순서가 유지된다.
- 완전 체결된 주문은 오더북에 남지 않는다.
- 취소된 주문은 이후 체결되지 않는다.

### 주문

- `quantity = remaining + filled + cancelled`
- remaining quantity는 음수가 될 수 없다.
- filled quantity는 original quantity를 초과할 수 없다.
- 같은 idempotency key는 주문 하나만 만든다.
- filled order는 cancel될 수 없다.

### 체결

- 체결 가격은 maker 가격이다.
- 체결 순서는 가격-시간 우선순위를 위반하지 않는다.
- buyer가 받은 base 수량은 seller가 잃은 base 수량과 같다.
- seller가 받은 quote와 fee 합계는 buyer가 지불한 quote와 같다.
- 같은 trade id는 ledger에 한 번만 반영된다.

### 장부

- 모든 ledger transaction은 asset별 debit 합계와 credit 합계가 같다.
- posting amount는 양수다.
- ledger record는 수정/삭제되지 않는다.
- reversal은 기존 record 삭제가 아니라 반대 posting으로 표현한다.
- available balance는 음수가 될 수 없다.
- hold balance는 음수가 될 수 없다.
- projection balance는 ledger posting 합계와 일치해야 한다.

### 이벤트 소싱

- aggregate version은 1씩 증가한다.
- 같은 aggregate/version 이벤트는 둘 이상 존재할 수 없다.
- full replay 결과와 snapshot replay 결과가 같아야 한다.
- replay는 외부 side effect를 만들지 않는다.

### 시장데이터

- orderbook snapshot 이후 delta sequence는 끊기지 않아야 한다.
- `previousSequence != localSequence`이면 local book은 stale이다.
- `quantity = 0` delta는 해당 price level을 삭제한다.
- trade tick sequence는 중복되거나 역행하지 않는다.
- candle OHLCV는 같은 interval의 trade tick 집계와 일치해야 한다.
- checksum을 제공하는 경우 mismatch는 반드시 resync로 이어져야 한다.

### 다중 서버/소유권

- 같은 `marketId`에 active owner는 하나뿐이다.
- owner epoch는 단조 증가한다.
- 낮은 epoch의 output은 저장/발행/소비되지 않아야 한다.
- failover replay 후 order book checksum과 trade stream checksum이 일치해야 한다.

### 장애 복구

- outbox publish 실패 후 재시도하면 모든 이벤트가 결국 발행된다.
- inbox 중복 수신 후에도 상태 변화는 한 번만 발생한다.
- projection을 삭제하고 replay해도 주문/잔고/체결 상태가 복구된다.
- 프로세스가 어느 지점에서 죽어도 retry 또는 보상 이벤트로 수렴한다.
- WebSocket gap 발생 후 snapshot resync로 local book이 복구된다.
- slow client는 전체 fanout latency를 악화시키지 않는다.
- stale owner output은 fencing token으로 거부된다.

## 17. 성능 목표

실거래소급 초저지연을 주장하지 말고, 포트폴리오 기준으로 측정 가능한 목표를 둔다.

| 영역 | 목표 |
|---|---|
| 순수 MatchingEngine 처리량 | 50,000 commands/s 이상, 목표 100,000 commands/s |
| 순수 MatchingEngine p99 | 단일 마켓 p99 < 2ms, 강한 목표 < 500us |
| 주문 접수 API + DB | 1,000 RPS 이상, p95 < 80ms, p99 < 200ms |
| 강한 API 목표 | 2,000 RPS 이상, p95 < 50ms, p99 < 120ms |
| 체결 이벤트 publish | p95 < 20ms, p99 < 80ms |
| 장부 반영 지연 | p95 < 200ms, p99 < 1s |
| trade tick publish | 10,000 ticks/s 내부 처리 목표 |
| WebSocket 동시 접속 | 기본 1,000 clients, 강한 목표 5,000 clients |
| WebSocket delivery lag | p95 < 200ms, 강한 목표 p95 < 100ms |
| orderbook delta batch | 50~100ms coalescing |
| replay 속도 | 20,000 events/s 이상, 강한 목표 100,000 events/s |
| 정합성 | 중복 체결 0건, 장부 불일치 0건, 이벤트 유실 0건, stale owner write 0건 |

## 18. JMH 벤치마크

목적:

- HTTP나 DB가 아니라 순수 핵심 로직의 비용을 측정한다.

대상:

| 벤치마크 | 측정 목적 |
|---|---|
| `OrderBookInsertBenchmark` | 가격 레벨 삽입 비용 |
| `MatchingLoopBenchmark` | 지정가/시장가 체결 비용 |
| `CancelOrderBenchmark` | order index 기반 취소 비용 |
| `PriceLevelTraversalBenchmark` | best bid/ask 탐색 비용 |
| `EventSerializationBenchmark` | 체결 이벤트 직렬화 비용 |
| `LedgerApplyBenchmark` | posting 생성과 balance projection 반영 비용 |
| `OrderBookDeltaBenchmark` | price level 변경을 delta로 변환하는 비용 |
| `CandleAggregationBenchmark` | trade tick에서 OHLCV를 갱신하는 비용 |

측정 기준:

- throughput mode
- average time
- sample time
- warmup 충분히 설정
- GC profiler 포함
- allocation rate 확인
- `TreeMap`, skip list, custom price level map 비교

README에 남길 표:

| 대상 | 구현 | ops/sec | avg | p95 유사값 | allocation/op | 결론 |
|---|---|---:|---:|---:|---:|---|
| OrderBook insert | TreeMap | TBD | TBD | TBD | TBD | MVP 기본 |
| Matching loop | TreeMap + FIFO | TBD | TBD | TBD | TBD | 병목 여부 확인 |

### JMH 비교 실험 백로그

| 실험 | A안 | B안 | C안 | 볼 지표 |
|---|---|---|---|---|
| 가격 레벨 저장소 | `TreeMap` | skip list | custom sorted array | insert/match latency, allocation |
| 동일 가격 FIFO | `ArrayDeque` | linked list | custom intrusive list | cancel 비용, allocation |
| command queue | Kotlin `Channel` | `ArrayBlockingQueue` | JCTools MPSC/Disruptor | p99, throughput, CPU |
| 금액 표현 | `BigDecimal` | scaled `Long` value class | custom decimal | allocation, correctness |
| event payload | Jackson JSON | Kotlinx Serialization | Protobuf/Avro | serialization latency, size |
| ledger apply | row-by-row insert | JDBC batch | COPY-like bulk path | TPS, lock wait |

JMH 주의:

- 벤치마크는 반드시 warmup을 둔다.
- dead code elimination을 피한다.
- benchmark input을 너무 단순하게 만들지 않는다.
- 실제 workload와 benchmark workload의 차이를 README에 적는다.
- JMH 결과만으로 API 성능을 주장하지 않는다. JMH는 hot path 비용 확인용이다.

## 19. k6 부하 테스트

### 시나리오

| 시나리오 | 목적 |
|---|---|
| Smoke | 기본 API 정상 동작 확인 |
| Baseline | 낮은 트래픽 기준 지연 측정 |
| Ramp-up | 100 -> 1,000 -> 5,000 RPS 증가 |
| Spike | 갑작스러운 주문 폭주 |
| Soak | 1~3시간 지속 부하 |
| Hot Symbol | 단일 마켓 hot partition 한계 확인 |
| Mixed Workload | 주문 생성, 취소, 조회 혼합 |
| Failure Load | Kafka/DB 지연 상황에서 회복 확인 |
| WebSocket Fanout | 다수 client가 trade/orderbook/candle 구독 |
| Slow Client | 일부 client의 수신 지연과 disconnect 정책 확인 |

### 트래픽 비율

- 신규 주문 70%
- 주문 취소 15%
- 주문 조회 10%
- 체결/장부 조회 5%

Market data workload:

- WebSocket client 1,000개 기본, 5,000개 강한 목표
- 70%는 trades + ticker 구독
- 20%는 orderbook depth 20 구독
- 10%는 candles 1m 구독
- 일부 client는 의도적으로 read를 늦춰 backpressure를 만든다.

### threshold

- error rate < 0.1%
- 주문 API p95 < 80ms, 강한 목표 < 50ms
- 주문 API p99 < 200ms, 강한 목표 < 120ms
- WebSocket delivery lag p95 < 200ms
- WebSocket slow client가 정상 client p95를 악화시키지 않음
- orderbook sequence gap 후 snapshot resync 성공률 100%
- ledger imbalance count = 0
- duplicate trade count = 0
- stale owner write count = 0
- consumer lag가 부하 종료 후 N분 내 회복

### k6 고급 시나리오

| 시나리오 | 구성 | 확인할 실패 모드 |
|---|---|---|
| Hot Market 100% | 모든 주문을 `BTC-KRW`에 집중 | single writer 한계, queue depth 증가 |
| Multi Market | 10개 마켓에 균등 분산 | market partition scaling |
| Cancel Storm | 주문 생성 50%, 취소 45%, 조회 5% | order index/cancel 경합 |
| Market Order Sweep | 시장가 주문으로 호가를 대량 소진 | price level traversal 비용 |
| Idempotency Retry | 동일 key 재시도 10~100회 | 중복 주문/중복 posting 방지 |
| Ledger Lag | ledger consumer를 의도적으로 느리게 함 | projection lag와 회복 시간 |
| DB Slowdown | DB latency 주입 | outbox backlog, API timeout |
| Kafka Outage | broker 정지/재기동 | outbox relay 재시도, 이벤트 유실 여부 |
| WS Fanout 1k/5k | trade/orderbook/candle 구독자 증가 | delivery lag, queue depth, CPU |
| WS Gap Recovery | delta 일부 drop fixture | stale 감지와 snapshot resync |
| Owner Failover | old worker pause 후 new worker takeover | stale output fencing |

성능 개선 리포트 형식:

| 개선 | 변경 전 | 변경 후 | 판단 |
|---|---:|---:|---|
| command queue 자료구조 변경 | TBD | TBD | p99가 줄었는지 확인 |
| ledger batch insert 도입 | TBD | TBD | consumer lag 회복 개선 |
| event table partition 도입 | TBD | TBD | replay/query 성능 비교 |
| virtual thread 실험 | TBD | TBD | API p99와 pool wait 비교 |

## 20. Observability

### Metrics

API:

- request count
- p50/p95/p99 latency
- status code
- timeout rate

Matching:

- orders accepted/sec
- orders rejected/sec
- trades executed/sec
- matching latency
- order book depth
- command queue depth
- engine sequence gap count
- stale owner output reject count

Ledger:

- events applied/sec
- ledger apply latency
- duplicate source event count
- imbalance count
- projection lag
- reconciliation mismatch count
- trial balance check latency

Market Data:

- trade ticks/sec
- orderbook deltas/sec
- orderbook snapshot requests/sec
- orderbook stale count
- sequence gap count
- checksum mismatch count
- candle updates/sec
- WebSocket active clients
- WebSocket delivery lag p95/p99
- WebSocket per-client queue depth
- WebSocket dropped/coalesced/resync count

Kafka:

- producer latency
- consumer lag
- rebalance count
- failed publish count
- DLQ count

DB:

- TPS
- slow query
- lock wait
- deadlock count
- connection pool wait

JVM:

- heap
- GC pause
- allocation rate
- thread count
- CPU
- safepoint pause

### Dashboards

- API Overview
- Matching Engine
- Ledger Integrity
- Market Data/WebSocket
- Kafka Producer/Consumer
- PostgreSQL
- Redis
- JVM/GC
- Business SLO

### Alerts

- API p99 latency 초과
- error rate 급증
- Kafka consumer lag 지속 증가
- ledger imbalance 발생
- market data sequence gap 급증
- orderbook checksum mismatch 발생
- WebSocket delivery lag 초과
- stale owner output 감지
- DB connection pool exhaustion
- deadlock 발생
- GC pause 장기화
- Redis eviction 발생

## 20-A. 기술별 튜닝 체크리스트

### PostgreSQL

도입 목적:

- event store, outbox, inbox, ledger를 강한 transaction boundary 안에 둔다.
- unique constraint로 idempotency를 데이터베이스 레벨에서 보장한다.

설계:

- event store는 append-only table로 설계한다.
- `event_id`, `(aggregate_id, aggregate_version)`, `global_sequence`는 unique constraint를 둔다.
- ledger transaction과 postings는 같은 DB transaction으로 저장한다.
- outbox row는 domain event append와 같은 DB transaction에 저장한다.
- projection table은 재생성 가능하게 설계한다.

튜닝:

- event table이 커지면 시간 기준 partition을 검토한다.
- 최신 이벤트 조회가 많으면 `global_sequence`, `created_at` index를 관리한다.
- 오래된 event/ledger table은 partition detach/archive 전략을 둔다.
- `pg_stat_statements`로 slow query를 찾는다.
- `EXPLAIN ANALYZE`로 projection query와 reconciliation query를 검증한다.
- long transaction을 피한다.
- HikariCP pool size는 CPU 수가 아니라 DB max connection, query latency, throughput으로 결정한다.
- outbox relay는 batch size, polling interval, retry backoff를 측정하며 조정한다.

위험:

- index를 많이 만들면 insert 성능과 WAL 비용이 증가한다.
- partition은 데이터가 충분히 커지기 전에는 복잡도만 늘릴 수 있다.
- DB connection pool을 키운다고 처리량이 무조건 늘지 않는다.

### Kafka

도입 목적:

- ledger/projection/settlement/market data를 비동기 consumer로 분리한다.
- matching engine과 read model update의 결합도를 낮춘다.

topic 예시:

- `exchange.order-events`
- `exchange.trade-events`
- `exchange.ledger-events`
- `exchange.settlement-events`
- `exchange.market-data`
- `exchange.dlq`

key 전략:

| 이벤트 | key 후보 | 이유 |
|---|---|---|
| order event | `marketId` 또는 `orderId` | 마켓 순서와 주문 순서 중 무엇이 중요한지 결정 |
| trade event | `marketId` | 체결 순서 보장 |
| ledger event | `accountId` 또는 `sourceEventId` | 계정별 반영 순서와 중복 방지 |
| settlement event | `settlementBatchId` | batch 단위 순서 |

튜닝:

- producer는 durability가 필요하므로 `acks=all`과 idempotent producer를 검토한다.
- consumer는 처리 완료 후 offset commit한다.
- consumer lag, rebalance count, DLQ count를 대시보드에 둔다.
- poison message는 무한 retry하지 않고 DLQ로 보낸다.
- schema evolution이 필요하면 Avro/Protobuf와 schema registry를 검토한다.
- replay용 topic retention과 event store replay의 역할을 분리한다. source of truth는 PostgreSQL event store로 둔다.

위험:

- Kafka의 ordering은 partition 안에서만 보장된다.
- partition key를 잘못 잡으면 순서 보장이나 확장성 중 하나가 깨진다.
- exactly-once에 기대기보다 idempotent consumer와 unique constraint를 같이 둔다.

### Debezium Outbox

도입 목적:

- 애플리케이션 polling relay 대신 CDC로 outbox를 Kafka에 전달한다.
- DB transaction과 event publish 사이의 불일치를 줄인다.

MVP 판단:

- MVP에서는 직접 relay worker로 충분하다.
- 확장 단계에서 Debezium Outbox Event Router를 붙여 "polling outbox vs CDC outbox" 비교 실험을 하면 좋다.

비교 지표:

- outbox publish latency
- DB polling load
- Kafka publish 안정성
- 운영 복잡도
- 장애 복구 절차

### Redis

도입 목적:

- idempotency key TTL cache
- account/market별 rate limit
- order book snapshot cache
- short-lived request guard

금지:

- 원장 source of truth 저장 금지
- 체결 source of truth 저장 금지
- Redis lock으로 오더북 정합성을 해결하려는 설계 금지

튜닝:

- TTL을 명확히 둔다.
- hot key를 피하기 위해 key를 market/account 단위로 분산한다.
- sorted set은 rate limit, 랭킹, 시간 윈도우에 쓸 수 있지만 메모리 비용을 측정한다.
- Redis latency monitor, slowlog, eviction metrics를 본다.
- Redis 장애 시 order API가 어떻게 degrade되는지 문서화한다.

### JVM/Kotlin

도입 목적:

- 매칭 엔진 hot path에서 allocation과 GC를 줄인다.
- p99 latency 튐을 JFR/async-profiler로 설명한다.

튜닝:

- hot path에서 불필요한 data class copy를 피한다.
- `Price`, `Quantity`, `Amount`는 value class + scaled long을 검토한다.
- `BigDecimal`은 API boundary/검증 계층에서만 쓰고 내부는 scaled long으로 변환한다.
- 매칭 loop에서 logging, JSON serialization, DB access를 제거한다.
- JFR로 allocation, monitor blocking, pinned virtual thread, GC pause를 확인한다.
- async-profiler로 CPU flame graph와 allocation flame graph를 만든다.

### Spring Boot

선택:

- 기본 구현은 Spring MVC + transaction management로 시작한다.
- virtual thread는 실험 챕터로 둔다.
- WebFlux는 R2DBC까지 포함하는 비교 실험으로 둔다.

설정/관측:

- Actuator endpoint로 health, metrics, prometheus를 노출한다.
- Micrometer timer에 percentile histogram을 설정한다.
- `traceId`, `orderId`, `eventId`, `correlationId`를 structured logging에 포함한다.
- OpenTelemetry로 API -> outbox -> consumer -> ledger trace를 연결한다.

## 20-B. 성능 개선 실험 백로그

실험은 "도입했다"가 아니라 "도입 전/후를 측정했다"가 중요하다.

| 우선순위 | 실험 | 가설 | 성공 기준 |
|---|---|---|---|
| P0 | scaled long vs BigDecimal | hot path allocation과 latency가 줄어든다 | JMH allocation/op 감소 |
| P0 | order index 기반 cancel | 취소가 order book scan보다 빨라진다 | cancel benchmark p95 개선 |
| P0 | ledger batch insert | consumer lag 회복 속도가 빨라진다 | lag recovery time 감소 |
| P1 | DB outbox polling batch 조정 | publish latency와 DB load 균형점이 있다 | outbox backlog 안정화 |
| P1 | event table partition | event/replay query가 안정된다 | 최신 range query latency 개선 |
| P1 | virtual thread API | blocking API 처리량이 증가한다 | API p99 또는 thread usage 개선 |
| P1 | orderbook delta coalescing | fanout CPU와 delivery lag가 줄어든다 | WS p95 delivery lag 개선 |
| P1 | per-client bounded queue | slow client가 전체 fanout을 막지 않는다 | 정상 client p95 영향 없음 |
| P1 | ownerEpoch fencing | stale owner output이 거부된다 | failover simulation 통과 |
| P2 | WebFlux/R2DBC | high concurrency에서 resource usage가 줄어든다 | CPU/thread/memory 비교 우위 |
| P2 | JCTools/Disruptor queue | hot market queue overhead가 줄어든다 | matching queue p99 개선 |
| P2 | Protobuf/Avro event payload | payload size와 CPU가 줄어든다 | serialization latency/size 개선 |
| P2 | Redis snapshot cache | order book 조회 부하가 줄어든다 | DB/read model query 감소 |

README에는 성공한 실험뿐 아니라 실패한 실험도 남긴다. 예를 들어 virtual thread가 DB pool wait 때문에 처리량 개선이 없었다면, 그 결론 자체가 좋은 포트폴리오 내용이다.

## 20-C. 최종 구현 체크리스트

Core matching:

- [ ] `Price`, `Quantity`, `Amount`는 `Double` 없이 fixed scale로 표현한다.
- [ ] `TreeMap<Price, PriceLevel>` 기반 bid/ask book을 구현한다.
- [ ] 같은 가격 레벨은 FIFO를 보장한다.
- [ ] `HashMap<OrderId, OrderRef>`로 cancel scan을 피한다.
- [ ] limit/market/IOC/cancel을 처리한다.
- [ ] partial fill과 maker price 체결을 구현한다.
- [ ] crossed book이 남지 않도록 검증한다.
- [ ] 같은 command stream replay 결과가 같은 trade stream을 만든다.

Ledger and recovery:

- [ ] reserve/release/trade/fee posting을 double-entry로 기록한다.
- [ ] ledger transaction은 asset별 debit 합계와 credit 합계가 같다.
- [ ] posting은 append-only이며 오류는 reversal로 보정한다.
- [ ] idempotency key 재시도 시 주문과 posting이 중복되지 않는다.
- [ ] event store만으로 projection을 rebuild할 수 있다.
- [ ] outbox publish 실패와 inbox 중복 수신을 테스트한다.
- [ ] reconciliation report가 ledger와 projection 차이를 탐지한다.

Market data:

- [ ] trade tick WebSocket을 제공한다.
- [ ] orderbook snapshot API를 제공한다.
- [ ] orderbook delta WebSocket을 제공한다.
- [ ] delta에는 `previousSequence`와 `sequence`를 포함한다.
- [ ] `quantity = 0` price level delete를 처리한다.
- [ ] sequence gap 발생 시 stale 처리와 snapshot resync를 구현한다.
- [ ] candle은 trade tick 기반으로 집계한다.
- [ ] slow client별 bounded queue와 disconnect/resync 정책을 둔다.

Scaling and operation:

- [ ] 다중 마켓 engine registry를 구현한다.
- [ ] Kafka command topic key를 `marketId`로 둔다.
- [ ] `market_owner_leases`와 `ownerEpoch` fencing을 구현한다.
- [ ] old owner stale output reject simulation을 통과한다.
- [ ] JMH로 matching/ledger/market-data hot path를 측정한다.
- [ ] k6로 주문 API와 WebSocket fanout을 측정한다.
- [ ] Grafana dashboard에 API, matching, ledger, market data, Kafka, DB, JVM을 분리한다.
- [ ] README에 목표, 결과, 병목, trade-off를 남긴다.

## 21. 단계별 구현 로드맵

### Phase 0: 설계와 프로젝트 골격

목표:

- 모듈 구조 생성
- 도메인 용어집 작성
- ADR 작성
- OpenAPI 초안 작성

완료 기준:

- README에 목표, 비범위, 아키텍처 다이어그램이 있다.
- `Price`, `Quantity`, `Amount` value object에서 `Double`을 금지한다.

### Phase 1: 순수 Kotlin 매칭 엔진

기능:

- 단일 마켓 order book
- limit buy/sell
- partial fill
- cancel
- price-time priority
- deterministic engine sequence

테스트:

- FIFO 테스트
- 부분 체결 테스트
- 취소 테스트
- crossed book 방지 테스트
- property-based random order test

완료 기준:

- DB/Spring 없이 매칭 엔진 단위 테스트가 통과한다.
- JMH로 insert/match/cancel 기초 수치를 기록한다.

### Phase 2: 주문 API와 잔고 동결

기능:

- 주문 생성 API
- 주문 취소 API
- balance reserve/release
- idempotency key
- order projection

테스트:

- 같은 idempotency key 재시도
- 잔고 부족 rejection
- 동결 성공 전 order book 진입 금지

완료 기준:

- 같은 요청 100회 재시도 시 주문은 1개만 생성된다.
- available/hold projection이 ledger와 일치한다.

### Phase 3: Event Store와 Replay

기능:

- event store append
- aggregate version
- global sequence
- projection rebuild
- full replay

테스트:

- event replay 후 order book 상태 동일
- projection 삭제 후 재생성
- aggregate version conflict

완료 기준:

- event store만으로 주문/체결/잔고 projection을 복구할 수 있다.

### Phase 4: Double-Entry Ledger

기능:

- ledger transaction
- ledger posting
- reserve/release/trade/fee posting
- reversal transaction
- reconciliation query

테스트:

- asset별 debit/credit 합계 0
- 중복 source event 방지
- projection과 posting 합계 일치
- negative balance 방지

완료 기준:

- 랜덤 체결 시나리오 후 ledger imbalance가 0이다.

### Phase 5: Outbox/Inbox와 Kafka

기능:

- outbox table
- relay worker
- Kafka publish
- inbox table
- consumer idempotency
- DLQ
- market command topic key = `marketId`
- trade/ledger/projection event topic 분리

테스트:

- publish 실패 후 재시도
- message 중복 수신
- consumer crash 후 재처리
- partition 내부 ordering 확인
- Kafka 전체 topic ordering을 가정하지 않는 테스트

완료 기준:

- DB commit 이후 publish 실패가 발생해도 이벤트가 유실되지 않는다.

### Phase 6: Market Data와 WebSocket

기능:

- trade tick stream
- orderbook snapshot API
- orderbook delta WebSocket
- ticker stream
- candle REST/API + WebSocket update
- sequence gap detection
- optional checksum
- per-client bounded queue
- slow client disconnect/resync

테스트:

- snapshot 이후 delta 적용 결과가 server book과 일치
- missing delta fixture에서 stale 감지
- `quantity = 0` price level delete
- checksum mismatch resync
- slow client가 정상 client latency를 악화시키지 않음
- trade tick 집계 candle이 DB candle과 일치

완료 기준:

- 1,000 WebSocket client 기준 delivery lag p95 < 200ms를 달성한다.
- gap 발생 시 client가 snapshot resync로 local book을 복구한다.

### Phase 7: 다중 마켓 파티셔닝과 Failover Simulation

기능:

- 다중 마켓 in-process engine registry
- `marketId -> owner -> ownerEpoch` 관리
- market owner lease table
- engine snapshot 저장
- snapshot 이후 replay
- stale owner output fencing

테스트:

- 서로 다른 마켓은 병렬 처리
- 같은 마켓은 active owner 하나만 처리
- old owner pause 후 new owner takeover
- old owner가 늦게 output을 쓰면 epoch 검증으로 거부
- failover 후 orderbook/trade checksum 일치

완료 기준:

- active-active matching을 구현하지 않는 이유와 대안이 ADR로 설명되어 있다.
- failover simulation에서 stale write 0건, replay mismatch 0건이다.

### Phase 8: 정산과 대사

기능:

- settlement batch
- settlement item
- daily closing
- fee revenue report
- reconciliation report

테스트:

- batch item 합계 검증
- posted settlement 중복 처리 방지
- clearing account 수렴 검증

완료 기준:

- 특정 일자의 체결/수수료/잔고 변화를 대사 리포트로 설명할 수 있다.

### Phase 9: 성능과 운영 문서

기능:

- k6 scripts
- JMH benchmark suite
- Grafana dashboard
- alert rule
- bottleneck report

완료 기준:

- README에 성능 목표와 실제 측정 결과가 있다.
- p95/p99, throughput, replay speed, consumer lag 회복 시간이 기록되어 있다.
- 병목 1개 이상을 찾아 개선 전/후를 비교했다.

## 22. README에 반드시 넣을 내용

README 구조:

1. 프로젝트 한 줄 소개
2. 핵심 아키텍처 다이어그램
3. 왜 이 주제를 만들었는지
4. 비범위
5. 도메인 모델
6. 매칭 엔진 규칙
7. 복식부기 원장 설계
8. 이벤트 소싱/replay 설계
9. WebSocket market data 설계
10. 다중 마켓/다중 서버 확장 설계
11. 장애 시나리오와 복구 전략
12. 성능 테스트 결과
13. 테스트 전략
14. 실행 방법
15. 배운 점과 trade-off

README에 보여줄 표:

| 지표 | 목표 | 결과 |
|---|---:|---:|
| MatchingEngine throughput | 50k commands/s 이상 | TBD |
| MatchingEngine p99 | < 2ms | TBD |
| 주문 API p95 | < 80ms, 강한 목표 < 50ms | TBD |
| 주문 API p99 | < 200ms, 강한 목표 < 120ms | TBD |
| Ledger apply latency p95 | < 200ms | TBD |
| WebSocket clients | 1,000 기본, 5,000 강한 목표 | TBD |
| WebSocket delivery lag p95 | < 200ms | TBD |
| Replay speed | 20k events/s 이상 | TBD |
| Ledger imbalance | 0 | TBD |
| Duplicate trade | 0 | TBD |
| Market data sequence gap recovery | 100% | TBD |
| Stale owner write | 0 | TBD |

## 23. 면접 질문 대비

반드시 답할 수 있어야 하는 질문:

- 왜 single writer matching engine을 선택했나?
- 마켓이 많아지면 어떻게 병렬화하나?
- 왜 같은 마켓 active-active matching을 하지 않았나?
- stale owner가 늦게 살아나면 어떻게 막나?
- Kafka partition ordering과 전체 topic ordering은 어떻게 다른가?
- 주문 취소와 체결이 동시에 들어오면 누가 이기나?
- 왜 잔고 테이블 update가 아니라 double-entry ledger를 썼나?
- ledger에서 debit/credit 방향을 어떻게 정의했나?
- Kafka exactly-once를 믿지 않고 왜 idempotency를 설계했나?
- event store와 outbox를 같은 DB transaction에 넣은 이유는?
- projection이 깨지면 어떻게 복구하나?
- replay 중 외부 side effect를 어떻게 막나?
- 오더북 WebSocket snapshot/delta에서 sequence gap은 어떻게 복구하나?
- `quantity = 0` delta는 어떤 의미인가?
- trade tick과 candle, orderbook은 각각 어떤 source에서 만들어지나?
- slow WebSocket client가 있으면 fanout을 어떻게 보호하나?
- p99 latency가 튄 원인을 어떻게 찾았나?
- `Double`을 쓰지 않은 이유는?
- DB isolation level과 unique constraint는 어떤 역할을 하나?
- consumer lag가 쌓이면 어떤 순서로 대응하나?
- engine recovery snapshot과 market data snapshot은 어떻게 다른가?

## 24. 학습 로드맵

### 1단계: 도메인

- 현물 거래소 기본 구조
- order book
- bid/ask
- maker/taker
- market order와 limit order
- partial fill
- cancel
- time in force
- fee model
- trade tick
- order book snapshot/delta
- sequence gap recovery
- OHLCV candle
- ticker

### 2단계: 알고리즘과 자료구조

- 정렬 맵
- 레드블랙트리
- Skip List
- B-Tree
- Priority Queue
- FIFO queue
- doubly linked list
- HashMap indexing
- 고정소수점 수치 처리
- 상태 머신
- deterministic simulation
- checksum/gap validator

### 3단계: Kotlin/JVM

- Kotlin value class
- sealed class
- coroutine 기본 원리
- coroutine channel/actor pattern
- JVM allocation
- GC
- JFR
- async-profiler
- JMH 사용법
- thread model과 virtual thread의 차이
- virtual thread pinning
- false sharing/cache line 기초
- lock-free queue 기초
- flame graph 읽는 법

### 4단계: Spring Boot

- transaction management
- validation
- Actuator
- Micrometer
- Spring for Apache Kafka
- Spring WebFlux와 backpressure
- R2DBC와 JDBC 차이
- Spring MVC + virtual threads 실험
- Testcontainers
- OpenAPI
- HikariCP
- structured logging
- OpenTelemetry trace propagation

### 5단계: PostgreSQL

- MVCC
- transaction isolation
- unique constraint를 이용한 idempotency
- index 설계
- `EXPLAIN ANALYZE`
- partitioning
- WAL
- checkpoint
- autovacuum
- `pg_stat_statements`
- lock wait/deadlock 분석
- append-only event table 설계
- batch insert
- cursor pagination
- partition detach/archive 전략

### 6단계: Kafka

- topic
- partition
- ordering
- consumer group
- offset commit
- idempotent producer
- retry
- DLQ
- outbox pattern
- inbox pattern
- consumer lag 운영
- idempotent producer
- producer `acks`
- partition key 설계
- schema evolution
- Avro/Protobuf
- Debezium Outbox Event Router
- Kafka Connect 운영 기초

### 7단계: Event Sourcing/CQRS

- event store
- aggregate stream
- global sequence
- projection
- replay
- snapshot
- schema evolution
- side effect 분리
- projection rebuild
- blue/green projection swap
- point-in-time replay
- compensating event
- event upcaster

### 8단계: 회계/원장

- double-entry accounting
- debit/credit
- posting
- clearing account
- fee revenue account
- reversal transaction
- reconciliation
- settlement batch
- reserve/release accounting
- maker/taker fee accounting
- clearing account
- trial balance
- manual adjustment와 audit trail

### 9단계: 성능/운영

- k6 시나리오 설계
- SLO
- p95/p99 latency
- Prometheus/Grafana
- OpenTelemetry tracing
- structured logging
- capacity planning
- failure mode analysis
- HdrHistogram 또는 percentile histogram
- backpressure
- load shedding
- rate limiting
- queue depth 운영
- consumer lag 대응
- WebSocket fanout
- slow client handling
- market data resync
- incident postmortem 작성

### 9-A단계: 분산 확장/소유권

- Kafka partition ordering
- consumer group rebalancing
- marketId routing
- partition 수 변경 위험
- leader/owner lease
- fencing token
- stale writer
- snapshot + replay failover
- active-active가 맞지 않는 도메인 구분
- CRDT와 total order의 차이

### 10단계: 심화 성능 자료구조

- LMAX Disruptor ring buffer
- JCTools SPSC/MPSC/MPMC queue
- bounded queue와 unbounded queue trade-off
- wait strategy
- batching
- object pooling의 장단점
- off-heap/direct buffer 기초
- cache locality
- mechanical sympathy 기초

### 11단계: 기술 비교 과제

구현 후 README에 아래 비교 중 3개 이상을 남기면 포트폴리오 설득력이 좋아진다.

| 비교 | 질문 |
|---|---|
| Spring MVC vs MVC + virtual threads | blocking API에서 p99와 thread 사용량이 개선되는가? |
| JDBC vs R2DBC | non-blocking stack이 실제 병목을 줄였는가? |
| TreeMap vs skip list/custom map | order book hot path에서 차이가 있는가? |
| BigDecimal vs scaled long | 정확성과 성능을 동시에 잡을 수 있는가? |
| DB polling outbox vs Debezium outbox | publish latency와 운영 복잡도는 어떻게 달라지는가? |
| JSON vs Protobuf/Avro | payload size, CPU, schema evolution이 어떻게 달라지는가? |
| row-by-row ledger insert vs batch insert | consumer lag 회복에 얼마나 차이가 나는가? |
| Redis rate limit vs local in-memory limit | 정확도와 장애 내성이 어떻게 달라지는가? |

## 25. 공식 문서 참고 링크

거래소/시장데이터:

- Binance Spot WebSocket Streams: https://developers.binance.com/docs/binance-spot-api-docs/web-socket-streams
- Coinbase Exchange Matching Engine: https://docs.cdp.coinbase.com/exchange/concepts/matching-engine
- Coinbase Exchange WebSocket Feed: https://docs.cdp.coinbase.com/exchange/websocket-feed/overview
- Coinbase Exchange WebSocket Channels: https://docs.cdp.coinbase.com/exchange/websocket-feed/channels
- Coinbase Exchange REST Candles: https://docs.cdp.coinbase.com/api-reference/exchange-api/rest-api/products/get-product-candles
- Kraken Spot WebSocket Book: https://docs.kraken.com/exchange/api-reference/spot-websocket-v2/book
- Kraken Spot Book Checksum v2: https://docs.kraken.com/exchange/guides/websockets/book-checksum-v2
- Kraken Spot Trade WebSocket: https://docs.kraken.com/exchange/api-reference/spot-websocket-v2/trade
- Kraken Spot OHLC WebSocket: https://docs.kraken.com/exchange/api-reference/spot-websocket-v2/ohlc
- BitMEX WebSocket API: https://www.bitmex.com/app/wsAPI
- BitMEX WebSocket schema help: https://www.bitmex.com/api/v1/schema/websocketHelp
- OKX API v5 Market Data: https://app.okx.com/docs-v5/en/
- CME Globex supported matching algorithms: https://www.cmegroup.com/confluence/display/EPICSANDBOX/Supported+Matching+Algorithms

아키텍처/분산 설계:

- LMAX Architecture: https://martinfowler.com/articles/lmax.html
- Event Sourcing: https://martinfowler.com/eaaDev/EventSourcing.html
- Microsoft Event Sourcing pattern: https://learn.microsoft.com/en-us/azure/architecture/patterns/event-sourcing
- Kafka documentation: https://kafka.apache.org/documentation/
- Confluent Kafka introduction: https://docs.confluent.io/kafka/introduction.html
- Confluent partition determination: https://docs.confluent.io/kafka/operations-tools/partition-determination.html
- Confluent Kafka replication: https://docs.confluent.io/kafka/design/replication.html
- Kafka transactions and fencing: https://www.confluent.io/blog/transactions-apache-kafka/
- Martin Kleppmann fencing tokens: https://martin.kleppmann.com/2016/02/08/how-to-do-distributed-locking.html
- AWS Transactional Outbox: https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html
- Microservices.io Transactional Outbox: https://microservices.io/patterns/data/transactional-outbox.html
- Microservices.io Idempotent Consumer: https://microservices.io/patterns/communication-style/idempotent-consumer.html

원장/정합성:

- TigerBeetle debit/credit: https://docs.tigerbeetle.com/concepts/debit-credit/
- TigerBeetle safety: https://docs.tigerbeetle.com/concepts/safety/
- TigerBeetle reliable transaction submission: https://docs.tigerbeetle.com/coding/reliable-transaction-submission/
- Uber Money Scale Strong Data: https://www.uber.com/blog/money-scale-strong-data/
- Stripe Ledger: https://stripe.dev/blog/ledger-stripe-system-for-tracking-and-validating-money-movement
- Airbnb financial reporting: https://medium.com/airbnb-engineering/tracking-the-money-scaling-financial-reporting-at-airbnb-6d742b80f040

기술 스택:

- Spring Boot Kotlin: https://docs.spring.io/spring-boot/reference/features/kotlin.html
- Spring Boot virtual threads: https://docs.spring.io/spring-boot/reference/features/spring-application.html#features.spring-application.virtual-threads
- Kotlin coroutines: https://kotlinlang.org/docs/coroutines-overview.html
- Spring WebFlux: https://docs.spring.io/spring-framework/reference/web/webflux.html
- Spring for Apache Kafka transactions: https://docs.spring.io/spring-kafka/reference/kafka/transactions.html
- Apache Kafka documentation: https://kafka.apache.org/documentation/
- PostgreSQL transaction isolation: https://www.postgresql.org/docs/current/transaction-iso.html
- PostgreSQL indexes: https://www.postgresql.org/docs/current/indexes.html
- PostgreSQL table partitioning: https://www.postgresql.org/docs/current/ddl-partitioning.html
- PostgreSQL WAL: https://www.postgresql.org/docs/current/wal-intro.html
- Debezium Outbox Event Router: https://debezium.io/documentation/reference/stable/transformations/outbox-event-router.html
- Redis latency diagnosis: https://redis.io/docs/latest/operate/oss_and_stack/management/optimization/latency/
- Redis sorted sets: https://redis.io/docs/latest/develop/data-types/sorted-sets/
- Micrometer Observation: https://docs.micrometer.io/micrometer/reference/observation.html
- OpenTelemetry Java agent: https://opentelemetry.io/docs/zero-code/java/agent/
- JMH: https://openjdk.org/projects/code-tools/jmh/
- k6 documentation: https://grafana.com/docs/k6/latest/
- Testcontainers Java: https://java.testcontainers.org/
- LMAX Disruptor: https://lmax-exchange.github.io/disruptor/
- JCTools: https://github.com/JCTools/JCTools
- HdrHistogram: https://github.com/HdrHistogram/HdrHistogram
- async-profiler: https://github.com/async-profiler/async-profiler

## 26. 최종 권장 MVP

완성본은 "최소 기능"이 아니라 "질문을 받아도 무너지지 않는 core proof"를 기준으로 한다.

반드시 구현:

- 단일 마켓 `BTC-KRW`
- limit/market/cancel/IOC
- in-memory order book
- price-time priority
- partial fill
- crossed book 방지
- deterministic engine sequence
- trade/order events
- balance reserve/release
- double-entry ledger
- fee posting
- event store
- idempotency key
- outbox/inbox
- replay
- reconciliation
- trade tick WebSocket
- orderbook snapshot API
- orderbook delta WebSocket
- candle REST + WebSocket update
- sequence gap recovery
- WebSocket slow client policy
- JMH benchmark
- k6 HTTP + WebSocket load test
- Grafana dashboard
- README 성능 리포트

강한 완성도를 위해 구현:

- 다중 마켓 in-process partitioning
- Kafka command topic key = `marketId`
- market owner lease
- `ownerEpoch` fencing
- engine snapshot + replay recovery
- failover simulation
- stale owner output reject test
- optional top N orderbook checksum
- 병목 개선 전/후 리포트 1개 이상
- 기술 비교 실험 3개 이상
- ADR 13개 이상

문서로 명확히 제외:

- 같은 마켓 active-active matching
- Redis lock만으로 correctness 보장
- global Kafka ordering 주장
- HFT/microsecond latency 주장
- 실거래소 private order 연동
- 블록체인 입출금/KYC/AML
- full Level3 public feed
- multi-region consensus 구현

최종 메시지:

```text
이 프로젝트는 거래소 전체 클론이 아니라,
single-writer deterministic matching engine, double-entry ledger,
event replay, WebSocket market data, market partition failover를
테스트와 성능 지표로 검증한 exchange core portfolio다.
```
