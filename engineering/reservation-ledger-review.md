# #72 예약·원장을 함께 저장하는 흐름

로컬 구현·검증 완료 · 관련 검사 **37개**, 전체 검사 **691개** 통과 · 독립 리뷰에서 확인된 미해결 결함 없음. 사람의 읽기·판단 완료는 확인하지 않았다.

작업 폴더 `/Users/0chord/.codex/worktrees/issue72-reservation-spec/exchange-core` · 브랜치 `feat/reservation-ledger/72` · 기준 `76cb78d3e87edac7c7b99870ed64841b2e8ff05a`.

## 이전 → 지금

이전에는 주문 예약과 잔고 hold만 저장했다. 이제 같은 DB 트랜잭션에 **RESERVE 원장 한 건과 두 분개**도 저장한다. 주문 입력 저장·재시작 복구·취소 RELEASE·HTTP 응답 개편은 이번 변경에 없다.

## 전체 흐름 지도

| 시작 | 판단·저장 순서 | 관측하는 결과 |
| --- | --- | --- |
| 영속화 설정 true | 기존 config가 같은 DB의 예약·잔고·원장 포트를 funding Bean에 연결 | 기존 주문 제출 worker가 이 Bean을 호출 |
| 정상 BUY·SELL | 도메인 계산 → 예약/분개 객체 검증 → 예약 insert → 조건부 잔고 이동 → 원장 헤더/두 분개 → 커밋 | 기존 예약 결과 + 한 번의 잔고 이동 + RESERVE |
| 같은 마켓·주문 재요청 | 예약 PK에서 중복 거절. 후속 잔고/원장 저장에 진행하지 않음 | 중복 오류. 이전 데이터·갱신 시각 그대로 |
| 동시 요청 | 같은 주문은 PK, 다른 주문의 잔고 경쟁은 기존 조건부 UPDATE로 판정 | 같은 주문 성공 1/중복 1. 서로 다른 707 주문은 성공 1/부족 1 |
| 저장 본문 실패 | DB 제약 오류를 전파. 같은 트랜잭션 전체 롤백 | 이번 예약·잔고 이동·원장 없음. 이전 거래 보존 |
| 기존 체결·취소 | 예약 커밋 후 기존 매칭·정산·취소 그대로 | 기존 HTTP·수수료·잔고 유지. SETTLEMENT와 RESERVE를 분리해 검사 |
| 외부 트랜잭션 참여 | REQUIRED로 같은 경계에 참여 | 메서드 반환만으로 커밋을 뜻하지 않음. 외부 롤백이면 세 기록도 롤백 |
| 커밋 응답 유실·3초 초과 | 기존 호출 경계 유지 | 실패 응답만으로 롤백을 단정하지 않음. 자동 재예약·취소·복구 없음 |

## 1. 300을 예약하면 새 돈이 생기는가

아니다. 도메인이 수수료 없는 BUY 100 × 3 = 300을 검증하면, 같은 사용자의 사용 가능 금액 1,000 중 300을 hold로 옮긴다. DB 잔고는 **700/300**, 원장은 **AVAILABLE 차변 300 / HOLD 대변 300**이다. 양수와 같은 자산의 차변·대변 균형은 기존 `LedgerPosting`·`LedgerTransaction`이 검증한다.

BUY 100 × 5에 최대 예약 수수료 1%가 있으면 총 예약액 505, 잔고 495/505, 양쪽 분개도 505다. 별도로 수수료를 다시 계산하지 않고 `reservation.reservedAmount`를 사용한다. 아직 체결 전이므로 수수료 수익 분개는 없다. SELL 3은 base 잔고 10/0 → 7/3만 옮기며 quote는 그대로다.

기대값·검증: `OrderFundingServiceTest`의 기본 BUY·수수료 BUY·SELL·전액 예약. 반환 예약, DB 예약, 계정/자산/방향/금액 두 항목을 함께 확인했고 모두 통과했다.

<details markdown="1"><summary>실제 코드 보기 · OrderFundingService.kt 95~110줄</summary>

원본: `/Users/0chord/.codex/worktrees/issue72-reservation-spec/exchange-core/app-api/src/main/kotlin/com/exchange/core/api/order/application/OrderFundingService.kt` · 파일 SHA-256 `b82bee5321c286f2730583c6295b95e5854be17d0144202456faa0bc7b7308d5`. 생성 당시 실제 소스 발췌이며 자동 갱신되는 화면이 아니다.

```kotlin
        val reserveTransaction = reserveTransaction(reservation)
        reservationStore.create(reservation)

        // 거래 예약액과 수수료 예약액의 합을 hold한다. 이 호출이 실패하면 위 insert도
        // 같은 트랜잭션에서 rollback된다.
        balanceStore.reserve(
            userId = userId,
            assetId = requirement.assetId,
            amount = requirement.totalReserveAmount,
        )

        ledgerTransactionStore.append(reserveTransaction)

        return reservation
    }

```

</details>
<details markdown="1"><summary>실제 코드 보기 · OrderFundingService.kt 111~133줄</summary>

원본: `/Users/0chord/.codex/worktrees/issue72-reservation-spec/exchange-core/app-api/src/main/kotlin/com/exchange/core/api/order/application/OrderFundingService.kt` · 파일 SHA-256 `b82bee5321c286f2730583c6295b95e5854be17d0144202456faa0bc7b7308d5`. 생성 당시 실제 소스 발췌이며 자동 갱신되는 화면이 아니다.

```kotlin
    private fun reserveTransaction(reservation: OrderReservation): LedgerTransaction =
        LedgerTransaction(
            ledgerTransactionId = UUID.randomUUID().toString(),
            sourceEventId = reserveSourceId(reservation),
            transactionType = LedgerTransactionType.RESERVE,
            occurredAt = Instant.now(),
            postings =
                listOf(
                    LedgerPosting(
                        accountId = "USER:${reservation.userId.value}:${reservation.assetId.value}:AVAILABLE",
                        assetId = reservation.assetId,
                        side = LedgerPostingSide.DEBIT,
                        amount = reservation.reservedAmount,
                    ),
                    LedgerPosting(
                        accountId = "USER:${reservation.userId.value}:${reservation.assetId.value}:HOLD",
                        assetId = reservation.assetId,
                        side = LedgerPostingSide.CREDIT,
                        amount = reservation.reservedAmount,
                    ),
                ),
        )

```

</details>

## 2. Bean에서 무엇을 추가했나

`exchange.ledger.persistence.enabled=true`일 때 기존 `LedgerPersistenceConfig`가 만들던 `OrderFundingService` 생성자에 기존 `ledgerTransactionStore`를 추가로 전달한다. 서비스가 원장 구현을 새로 만들거나 다른 DB를 선택하지 않는다. false/미설정이면 기존처럼 영속화 Bean이 없다. `@Service` 자동 등록은 추가하지 않았다.

설정 테스트 3개와 실제 DB 서비스 호출로 연결을 확인했다. 원장 포트 append는 기존 Spring 트랜잭션에 참여한다.

<details markdown="1"><summary>실제 코드 보기 · LedgerPersistenceConfig.kt 68~85줄</summary>

원본: `/Users/0chord/.codex/worktrees/issue72-reservation-spec/exchange-core/app-api/src/main/kotlin/com/exchange/core/api/config/LedgerPersistenceConfig.kt` · 파일 SHA-256 `bf037bfb0650359039a12290666111c3c66380f6bf8bbcef62e51a961b9135d2`. 생성 당시 실제 소스 발췌이며 자동 갱신되는 화면이 아니다.

```kotlin
    fun orderFundingService(
        balanceStore: BalanceStore,
        orderReservationStore: OrderReservationStore,
        ledgerTransactionStore: LedgerTransactionStore,
    ): OrderFundingService =
        OrderFundingService(
            calculator =
                OrderReservationCalculator(
                    buyOrderFundingQuoteCalculator =
                        BuyOrderFundingQuoteCalculator(
                            feeReserveCalculator = TradingFeeReserveCalculator(),
                        ),
                ),
            balanceStore = balanceStore,
            reservationStore = orderReservationStore,
            ledgerTransactionStore = ledgerTransactionStore,
        )

```

</details>

## 3. 같은 주문을 다시 보내면 무엇이 남나

이미 `(marketId, orderId)` 예약이 있으면 기존 예약 insert가 `OrderReservationAlreadyExistsException`을 던진다. 내용이 같아도 성공이나 원본 예약을 재반환하지 않는다. 다른 사용자·방향·가격·수량·수수료 정책으로 보내도 기존 주문을 덮어쓰지 않는다.

테스트는 최초 성공 뒤 예약·모든 잔고·원장·분개 행을 **갱신 시각까지** 저장해두고, 오류 뒤 전체 상태가 동일한지 확인한다. 동시 같은 주문도 성공 한 개·중복 오류 한 개·잔고 이동 한 번·RESERVE 한 건이다. 다른 주문이 1,000에서 각각 707을 요구하면 기존 SQL 조건이 부족한 호출을 거절하고 293/707 및 승자 기록만 남긴다.

<details markdown="1"><summary>실제 코드 보기 · OrderFundingServiceTest.kt 303~323줄</summary>

원본: `/Users/0chord/.codex/worktrees/issue72-reservation-spec/exchange-core/app-api/src/test/kotlin/com/exchange/core/api/order/application/OrderFundingServiceTest.kt` · 파일 SHA-256 `c85b83f22ada54a22feb36aed2c5a56d57c91630e5205ce3e52eeed50b82e50d`. 생성 당시 실제 소스 발췌이며 자동 갱신되는 화면이 아니다.

```kotlin
    fun `같은 주문의 입력이 달라도 중복 오류이며 원본 전체 상태를 보존한다`() {
        insertBalance(available = 10, hold = 0, assetId = MARKET.baseAssetId)
        insertBalance(available = 1_000, hold = 0, userId = UserId("other-user"))
        reserveOrder()
        val before = readDatabaseState()
        val attempts =
            listOf<() -> OrderReservation>(
                { reserveOrder(userId = UserId("other-user")) },
                { reserveOrder(side = Side.SELL) },
                { reserveOrder(price = Price(90)) },
                { reserveOrder(quantity = Quantity(3)) },
                { reserveOrder(policy = feeFreePolicy().copy(scheduleVersion = 2)) },
            )
        for (attempt in attempts) {
            val error = assertFailsWith<OrderReservationAlreadyExistsException>(block = attempt)
            assertEquals(MARKET.marketId, error.marketId)
            assertEquals(ORDER_ID, error.orderId)
            assertEquals(before, readDatabaseState())
        }
    }

```

</details>

## 4. 둘째 분개가 실패하면 앞선 저장은 왜 사라지나

예약 insert → 잔고 UPDATE → 원장 헤더 → 첫 분개까지 성공했어도, **하나의 서비스 트랜잭션이 아직 커밋 전**이다. 둘째 분개에서 PostgreSQL 제약 오류가 나면 오류를 삼키지 않고 전파하여 서비스 경계 전체를 롤백한다. 최종 조회에는 잔고 1,000/0, 새 예약 없음, 새 RESERVE 없음이다. 이전 원장은 변하지 않는다.

네 지점(예약·잔고·헤더·둘째 분개)에 테스트 전용 DB CHECK를 걸어 실제 SQL을 실패시켰다. 임의 예외가 아니라 SQLState 23514와 지정 제약 이름을 확인했다. 둘째 분개에서는 롤백되지 않는 DB 시퀀스가 두 번 증가했는지도 확인하므로, 첫 항목까지 실제 insert한 뒤 실패한 근거가 있다. 테스트 제약은 finally에서 제거하며 운영 migration에는 추가하지 않는다.

예약은 없는데 해당 주문 source 원장만 이미 있으면, 동일 내용도 원장 UNIQUE 오류 23505다. 이번 예약·잔고를 롤백하고 사전 원장 그대로 유지한다. 이것은 기존 예약의 중복 오류와 다른 저장 불일치 경로이며 자동 수리하지 않는다.

<details markdown="1"><summary>실제 코드 보기 · OrderFundingServiceTest.kt 357~390줄</summary>

원본: `/Users/0chord/.codex/worktrees/issue72-reservation-spec/exchange-core/app-api/src/test/kotlin/com/exchange/core/api/order/application/OrderFundingServiceTest.kt` · 파일 SHA-256 `c85b83f22ada54a22feb36aed2c5a56d57c91630e5205ce3e52eeed50b82e50d`. 생성 당시 실제 소스 발췌이며 자동 갱신되는 화면이 아니다.

```kotlin
    fun `실제 DB 저장 실패는 이전 원장을 보존하고 이번 예약 잔고 원장 전체를 롤백한다`(stage: String) {
        ledgerStore.append(existingLedger("previous-event", 7))
        val before = readDatabaseState()
        val sequenceBefore = jdbcTemplate.queryForObject("select last_value from ledger_postings_posting_id_seq", Long::class.java)!!
        val (table, condition) =
            when (stage) {
                "reservation" -> "order_reservations" to "order_id <> 'order-1'"
                "balance" -> "balance_projection" to "available <> 495"
                "header" -> "ledger_transactions" to "source_event_id <> '$DEFAULT_SOURCE_ID'"
                "second-posting" -> "ledger_postings" to "posting_sequence <> 2"
                else -> error("알 수 없는 실패 지점")
            }
        // NOT VALID로 기존 기록은 보존하고 새 insert/update에만 테스트 제약을 적용한다.
        jdbcTemplate.execute("alter table $table add constraint issue72_failure check ($condition) not valid")
        try {
            val error = assertFailsWith<DataIntegrityViolationException> { reserveOrder() }
            val sql = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<SQLException>().first()
            assertEquals("23514", sql.sqlState)
            assertTrue(sql.message.orEmpty().contains("issue72_failure"))
            assertEquals(before, readDatabaseState())
            assertNull(reservationStore.find(MARKET.marketId, ORDER_ID))
            assertPersistedBalance(1_000, 0)
            if (stage == "second-posting") {
                // 시퀀스는 롤백되지 않으므로 앞선 분개 insert가 실제로 실행됐는지 확인한다.
                assertEquals(
                    sequenceBefore + 2,
                    jdbcTemplate.queryForObject("select last_value from ledger_postings_posting_id_seq", Long::class.java),
                )
            }
        } finally {
            jdbcTemplate.execute("alter table $table drop constraint issue72_failure")
        }
    }

```

</details>

### 학습 문답

질문: ‘예약 저장 → 잔고 이동 → 첫 분개 저장 → 둘째 분개 실패’에서 같은 트랜잭션이라 앞선 저장도 사라지는 이유 / 중복과 저장 실패의 차이 / Bean부터 DB까지 중 무엇을 더 보고 싶은지 물었다.

AI 설명·근거: 위 실제 PostgreSQL 제약·사후 전체 행 대조로 롤백을 확인했다. 테스트 자체를 감싸는 트랜잭션은 NOT_SUPPORTED이며 서비스 호출 종료 뒤 조회하므로 테스트의 자동 롤백으로 성공한 것이 아니다.

사용자 답변: **“같은 트랜잭션이라 앞선 저장도 사라지는 이유”**를 더 보고 싶다고 답했다. 아래 설명을 추가했으며 사람의 이해 완료는 추정하지 않는다.

### SQL 성공과 커밋은 다르다

`@Transactional` 서비스에 들어갈 때 Spring이 트랜잭션을 시작한다. 같은 DataSource의 JDBC 포트는 이 경계의 DB 연결을 공유하고, 기존 원장 append의 REQUIRED도 여기에 참여한다. 각 SQL은 성공할 수 있지만 서비스 경계를 끝내기 전에는 개별 커밋하지 않는다.

| 시점 | 이번 트랜잭션이 수행한 작업 | 확정 여부 |
| --- | --- | --- |
| 예약 insert 성공 | 새 예약 행을 썼다 | 아직 커밋 전 |
| 잔고 UPDATE 성공 | available 감소·hold 증가 | 아직 커밋 전 |
| 원장 헤더·첫 분개 성공 | 사건과 첫 항목을 썼다 | 아직 커밋 전 |
| 둘째 분개 실패 | PostgreSQL이 제약 오류를 전달한다 | 정상 커밋으로 진행하지 않는다 |
| 오류가 서비스 경계로 전파 | Spring이 이 트랜잭션을 롤백한다 | 위 행·잔고 변경이 함께 취소된다 |
| 호출 종료 후 새 DB 조회 | 이전 상태 1,000/0, 새 예약·RESERVE 없음 | 실제 테스트의 관측 결과 |

DB가 트랜잭션에 속한 변경을 취소하는 것이며, Kotlin 객체나 다른 시스템의 상태까지 되감는 것은 아니다. 예를 들어 분개 ID 시퀀스는 롤백되지 않아 번호에 빈틈이 생길 수 있다. 그것을 이용해 첫 분개까지 실제 insert가 실행됐음을 확인한 것이다.

대안으로 각 저장에 REQUIRES_NEW를 쓰거나 예외를 삼키면 이 원자성 경계가 깨질 수 있다. 이번 구현은 그런 변경을 하지 않는다. 이미 앞선 주문에서 커밋된 기록은 이번 롤백에 포함되지 않는다.

## 5. 주문 원본 식별자는 왜 해시인가

`market:order`만 연결하면 `(a:b, c)`와 `(a, b:c)`가 같은 문자열이 된다. 그래서 각각의 UTF-8 **바이트 길이**와 원본 바이트를 순서대로 연결한 뒤 SHA-256을 사용한다. `RESERVE:v1:` 접두사를 붙인 75자로 DB의 128자 제한을 지킨다. trim·대소문자·Unicode 정규화는 하지 않는다.

공개 ID 계층이나 별도 모듈을 만들지 않고 서비스의 작은 private 함수다. 테스트의 기대 source 값은 구현 함수를 호출하지 않는 고정값이며, 콜론·64자 한글 키·다른 마켓·주문을 실제 저장값과 대조했다. 해시 충돌이 수학적으로 불가능하다는 보장은 하지 않으며 DB UNIQUE 충돌은 오류다.

<details markdown="1"><summary>실제 코드 보기 · OrderFundingService.kt 135~147줄</summary>

원본: `/Users/0chord/.codex/worktrees/issue72-reservation-spec/exchange-core/app-api/src/main/kotlin/com/exchange/core/api/order/application/OrderFundingService.kt` · 파일 SHA-256 `b82bee5321c286f2730583c6295b95e5854be17d0144202456faa0bc7b7308d5`. 생성 당시 실제 소스 발췌이며 자동 갱신되는 화면이 아니다.

```kotlin
    private fun reserveSourceId(reservation: OrderReservation): String {
        val market = reservation.marketId.value.toByteArray(Charsets.UTF_8)
        val order = reservation.orderId.value.toByteArray(Charsets.UTF_8)
        val input =
            ByteBuffer
                .allocate(Int.SIZE_BYTES * 2 + market.size + order.size)
                .putInt(market.size)
                .put(market)
                .putInt(order.size)
                .put(order)
                .array()
        val digest = MessageDigest.getInstance("SHA-256").digest(input)
        return "RESERVE:v1:${digest.joinToString("") { "%02x".format(it) }}"
```

</details>

## 6. 기존 체결·부분 체결·취소에서 달라지는 것

원장에 RESERVE가 추가된 것만으로 SETTLEMENT 분개 기대값을 느슨하게 바꾸지 않았다. 부분 체결 테스트는 **SETTLEMENT 한 건**을 종류로 골라 확인하고, BUY 303,000·SELL 1의 RESERVE 각각 두 항목을 별도로 확인한다. 취소 전후 전체 원장과 분개도 그대로인지 비교한다. 취소 후 BUY 잔고 909,100/0, 매도자 잔고와 수수료 1,350을 유지한다.

부분 정산 재요청 검사는 SETTLEMENT 4분개를 골라 원래 계정·금액을 그대로 검사하고, 먼저 커밋한 RESERVE 두 건도 확인한다. 정산 중복 거절 뒤 **원장 전체 스냅샷**과 잔고·예약을 비교하므로 예약 원장을 놓치지 않는다.

연결된 테스트: `OrderLifecycleE2ETest` 4개, `TradeSettlementServiceTest` 9개. 정상·부분 체결·남은 취소·정산 재요청·실패 회귀가 모두 통과했다. 주문 입력 영구 기록·재시작은 검증하지 않는다.

## 변경 파일을 어떻게 읽나

| 파일 | 종류 | 연결된 판단 |
| --- | --- | --- |
| OrderFundingService.kt | 제품 실행 코드 | 세 저장의 같은 경계, 두 분개, source 함수 |
| LedgerPersistenceConfig.kt | 제품 Bean 조립 | 같은 DB·기존 포트 연결 |
| OrderFundingServiceTest.kt | 실제 DB 테스트 | 정상·중복·경쟁·네 저장 실패·외부 롤백·ID |
| OrderLifecycleE2ETest.kt | HTTP·DB 테스트 | 체결·부분 체결·취소, RESERVE와 SETTLEMENT 분리 |
| TradeSettlementServiceTest.kt | 실제 DB 테스트 | 기존 정산 분개·중복 후 전체 상태 보존 |
| reservation-ledger-spec.md / 이 문서 | 명세·읽기 안내 | 수용 범위와 실행 증거 |

## 실행 기록과 현재 완료 상태

1. 테스트 먼저: 6개 중 새 RESERVE 검사만 기대 1 / 실제 0으로 실패했다. `/tmp/issue72-red.log`.
2. 최소 구현 후 설정·기존 예약 검사 통과. `/tmp/issue72-basic.log`.
3. 경계 보강에서 overflow의 예외 기대를 기존 도메인의 IllegalArgumentException(cause=ArithmeticException)에 맞췄다. 제품 계약이나 검증 범위를 약화하지 않았다.
4. 관련 37개(예약 21, 설정 3, HTTP 4, 정산 9) 통과·실패/오류/skip 0. `/tmp/issue72-regression.log`.
5. `ktlintCheck build :architecture-tests:verifyArchitectureReport --continue` 성공, 전체 691개(제품 132·구조 371·fee 37·ledger 25·order 52·matching 74), 실패·오류·skip 0. 필수 운영 구조 검사 P01~P09 모두 실행·성공. JMH 소스 린트는 실행됐고, jmhClasses 컴파일·성능 측정은 실행하지 않았다. `/tmp/issue72-full.log`.
6. 작성 대화를 상속하지 않은 별도 에이전트가 `/tmp/issue72-review-uufxfk3_` 고정 snapshot에서 A01~A15·실제 호출·정산 회귀를 검토하고 관련 37개를 독립 실행했다. 확인된 미해결 결함 없음. source 인코딩은 별도 고정값 계산으로 대조했다. 검토된 제품·테스트 5개 파일 집합 SHA-256 `ada7102c9baab29f89f60505c73a136974f04936e8e51db220a7771b59231771`이며 현재 구현과 일치한다. 리뷰 후 변경은 실행 기록·학습 설명 문서뿐이다.
7. 정산 회귀에서 추가 실패는 없었고 기존 기대값을 약화하지 않았다. E2E 원장 조회를 향후 같은 사용자의 복수 주문까지 재사용할 때 source 조건으로 좁히는 것은 선택적 개선이며 현재 fixture의 계약 위반은 아니다.

실행 로그는 로컬 근거다. 원격 PR·CI·병합은 아직 하지 않았다.

```sh
cd /Users/0chord/.codex/worktrees/issue72-reservation-spec/exchange-core
./gradlew :app-api:test --tests '*OrderFundingServiceTest' --tests '*LedgerPersistenceConfigurationTest' --tests '*OrderLifecycleE2ETest' --tests '*TradeSettlementServiceTest'
./gradlew ktlintCheck build :architecture-tests:verifyArchitectureReport --continue
```

## 남은 경계

커밋 응답 유실은 저장 본문 롤백과 다르다. 응답 실패만으로 DB 상태를 판단하지 않는다. 매칭/정산 오류는 앞서 커밋한 RESERVE를 되돌리지 않으며, 3초 초과도 실행 취소가 아니다. #50의 입력·접수 원자 저장, #73의 RELEASE, #74의 대조와 실제 강제 종료·복구는 후속 범위다. PR·CI·병합·사용자 이해 완료를 로컬 검사와 같다고 표시하지 않는다.
