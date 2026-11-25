# #22 구현 읽기 — 원본 보존과 실패한 DB 작업

**현재: 단위 1~3과 추가 HTTP 대표 사례의 로컬 구현·검증 완료. 최종 build 성공.** 사용자가 HTTP 대표 사례 1개 포함을 확정했다. [상세 명세](immutable-db-contracts-spec.md)에서 이어간다. 기준은 PR #37 병합 커밋 `ea3a3f446a5809db80eae18467db337947678574`, 작업 폴더는 `/Users/0chord/.codex/worktrees/issue22-contract-spec/exchange-core`, 브랜치는 `test/immutable-db-contracts/22`다. 테스트 작성, 실행 검증, 사람의 이해, PR 게시는 서로 다른 상태다.

## 처음에 읽을 지도

이번 변경은 **기존 처리에서 돈과 상태가 잘못 남는 경우를 테스트로 구별**한다. 기존 사례를 재사용하고 빠진 관측과 경계만 보강한다. 테스트 9개 파일에 신규 17개(기존 단위 16개 + 이번 HTTP 1개)를 추가하고 기존 어설션을 강화했다. 제품 실행 코드·SQL·Bean 조립은 그대로다.

| 읽기 단위 | 입력 → 판단 → 결과 | 이번 작업 |
| --- | --- | --- |
| 원본을 유지하고 새 상태를 반환한다 | 잔고·예약 → 각 금액/상태 규칙 → 새 객체 또는 거절 | 기존 정상·거절 사례의 원본/식별자 보존 보강, 별도 거래·수수료 한도 |
| 도메인과 SQL의 같은 금액 계약 | 잔고7/2 → 7 예약 → 0/9 반환·DB 저장 | 정확한 전액 예약, 큰 금액 덧셈 실패 시 행 보존 |
| 실패한 작업은 그 트랜잭션에서 되돌린다 | INSERT/UPDATE/정산 → 금액 부족·경쟁·중복 → 실패한 변경 롤백 | 실제 Spring Bean·PostgreSQL 결과 확인 |
| HTTP 대표 흐름 | SELL 1개 → BUY 3개 중1개 체결 → 남은2개 취소 | 포함 확정·테스트1개 추가. 기존 HTTP suite4개 실행·성공 |

## 1. 원본 보존: 새 결과와 원본은 따로 확인한다

잔고 available600/hold400에150 해제를 요청한다 → 도메인이 hold가 충분한지 검사한다 → 새 잔고750/250을 반환한다. 처음 잔고는600/400이고 사용자·자산도 그대로여야 한다. 결과 금액만 맞고 원본이나 식별자가 바뀌면 테스트는 실패해야 한다.

주문 예약에는 거래500과 수수료5가 따로 있다. 합계505 안에 들어간다는 이유로 거래501을 소비하거나 수수료6을 소비하도록 허용하지 않는다. 각각의 한도를 검사하고 거절 뒤 원본 전체를 유지한다. 부분 체결 결과를 다음 계산에 넣더라도 앞서 반환받은 중간 결과를 바꾸지 않아야 한다.

원장은 생성 시 자산별 차대 균형을 확인한다. 원장에 넘긴 mutable 목록을 나중에 바꿔도 이미 검증한 분개가 달라지면 안 된다. 입력 목록 복사와 반환 목록 변경 금지를 실제 목록 변경 요청으로 확인한다.

근거: [잔고](../domain-ledger/src/main/kotlin/com/exchange/core/ledger/Balance.kt), [주문 예약](../domain-order/src/main/kotlin/com/exchange/core/order/OrderReservation.kt), [원장](../domain-ledger/src/main/kotlin/com/exchange/core/ledger/LedgerTransaction.kt). 도메인은 DB를 호출하지 않는다.


<details markdown="1">
<summary>근거 코드 보기 · 거래와 수수료의 별도 한도</summary>

원문: `domain-order/src/main/kotlin/com/exchange/core/order/OrderReservation.kt` · 294~300행 · 파일 SHA256 `e69fc66d00d1`. 생성 당시 실제 소스 발췌다. 전체 원문은 해당 파일 링크로 연결한다.

```kotlin
        require(tradeReserveAmountToReduce <= remainingTradeReserveAmount) {
            "trade reserve amount to reduce must not exceed remaining trade reserve"
        }

        require(feeReserveAmountToReduce <= remainingFeeReserveAmount) {
            "fee reserve amount to reduce must not exceed remaining fee reserve"
        }
```

</details>

## 2. SQL: 전액 예약과 산술 초과는 다른 결과다

전액 예약은 정상이다. available7/hold2에서7 예약은 새 잔고0/9여야 한다. DB도 같은 값인지 재조회한다. 테스트는 도메인 함수의 반환값을 SQL의 정답으로 복사하지 않고0/9라는 독립 기대값을 쓴다.

hold가 이미 Long 최대값인데1을 더하는 것은 금액 부족과 다르다. 도메인은 산술 예외를, PostgreSQL은 숫자 범위 오류를 낸다. 예외 이름을 통일하지 않고 **실패 뒤 원래 상태가 남는다는 계약**을 각각 확인한다.

SQL 오류가 난 트랜잭션 안에서 바로 조회하면 조회 자체도 실패할 수 있다. 해당 테스트는 기존 동시성 사례처럼 테스트 전체 트랜잭션을 끄고, 저장소 호출의 실패·롤백이 끝난 뒤 원래 행을 조회한다. 제품 SQL과 트랜잭션 조립을 바꾸지 않는다.

근거: [조건부 잔고 갱신](../app-api/src/main/kotlin/com/exchange/core/api/ledger/infrastructure/persistence/PostgresBalanceStore.kt), [실제 DB 테스트](../app-api/src/test/kotlin/com/exchange/core/api/ledger/infrastructure/persistence/PostgresBalanceStoreTest.kt).


<details markdown="1">
<summary>근거 코드 보기 · 조건과 변경을 한 SQL로 처리</summary>

원문: `app-api/src/main/kotlin/com/exchange/core/api/ledger/infrastructure/persistence/PostgresBalanceStore.kt` · 52~59행 · 파일 SHA256 `0823bfb1ab24`. 생성 당시 실제 소스 발췌다. 전체 원문은 해당 파일 링크로 연결한다.

```sql
                    update balance_projection
                    set available = available - :amount,
                        hold = hold + :amount,
                        updated_at = current_timestamp
                    where user_id = :userId
                      and asset_id = :assetId
                      and available >= :amount
                    returning user_id, asset_id, available, hold
```

</details>

## 3. 경쟁·중복·롤백: 실패한 호출과 앞선 성공을 구분한다

| 시작과 입력 | 정상/실패 판단 | 관측할 상태 |
| --- | --- | --- |
| 현금1,000에 서로 다른 주문이707씩 예약 | 성공1·잔고부족1 | 현금293/hold707, 승자 예약만 저장. 패자 INSERT는 롤백 |
| hold100에 서로 다른 예약70씩 해제 | 성공1·hold부족1 | available900→970/hold30. 실패한 예약 UPDATE는 롤백 |
| 같은 주문 예약을 두 번 해제 | 중복 해제는 정상 반환 가능 | 실제 현금 반환은1번. 기존 사례 재사용 |
| 수수료 없는 정산에서 BUY hold179로 대금180 소비 | maker 반영 뒤 taker 부족 | 이번 원장·양쪽 변경 모두 롤백. 호출 전179가 유지되고200으로 복구하지 않음 |
| 수량5 중1 체결 성공 후 같은 체결 재호출 | 잔량4로 계산을 통과한 뒤 원장 source event 중복 거절 | 첫 성공 직후 예약·잔고·원장/분개 유지 |
| 첫 체결 성공 뒤 별도 두 번째 체결 실패 | 각 체결은 별도 트랜잭션 | 첫 성공 기록은 유지, 두 번째 변경만 롤백. 기존 사례 재사용 |

서로 다른70 예약의 합140이 실제 hold100보다 큰 것은 **안전 경계 검증용으로 일부러 만든 DB 상태**다. 정상 운영에 그런 불일치가 발생했다고 주장하거나 복구 기능을 추가하지 않는다.

Service의 rollback은 해당 DB 호출 안의 변경에 적용된다. 앞선 예약·매칭 이벤트·메모리 주문장을 전부 되돌리는 보장이 아니다. 실패한 마켓의 후속 요청·시간 초과·자동 재시도는 #23의 기존 경계를 유지한다.

근거: [자금 예약 테스트](../app-api/src/test/kotlin/com/exchange/core/api/order/application/OrderFundingServiceTest.kt), [해제 테스트](../app-api/src/test/kotlin/com/exchange/core/api/order/application/OrderReservationReleaseServiceTest.kt), [정산 테스트](../app-api/src/test/kotlin/com/exchange/core/api/order/application/TradeSettlementServiceTest.kt).


<details markdown="1">
<summary>근거 테스트 보기 · 오류 원인과 양쪽 상태 보존</summary>

원문: `app-api/src/test/kotlin/com/exchange/core/api/order/application/TradeSettlementServiceTest.kt` · 722~733행 · 파일 SHA256 `17daf38ee9ae`. 실제 테스트 발췌다. `assertFailsWith`는 아무 예외가 아니라 지정한 부족 오류만 인정한다.

```kotlin
        val error =
            assertFailsWith<InsufficientHoldException> {
                service.settle(MARKET, trade)
            }

        assertEquals(BUYER_USER_ID, error.userId)
        assertEquals(KRW_ASSET_ID, error.assetId)
        assertEquals(Amount(179), error.hold)
        assertEquals(Amount(180), error.requested)
        assertEquals(buyerReservation(), readReservation(BUYER_ORDER_ID))
        assertEquals(sellerReservation(), readReservation(SELLER_ORDER_ID))
        assertEquals(balancesBeforeFailure, readBalances())
```

</details>

## 4. 부분 체결 후 취소: 체결한 1개를 되돌리는 취소가 아니다

실제 JSON 주문 요청을 기존 MockMvc로 전달한다 → Spring의 주문 컨트롤러·유즈케이스·실제 Bean이 호출된다 → 매칭 엔진의 체결·잔량 이벤트가 저장되고 정산된다 → 응답과 PostgreSQL 잔고·예약·원장을 확인한다. 서비스 대역을 넣지 않는다. MockMvc는 외부 TCP 연결 없이 Spring HTTP 처리 경로를 실행하는 도구다.

처음 구매자는 현금1,000,000원·BTC0, 판매자는 현금0·BTC10을 가진다. 기존 테스트 설정의 maker0.5%·taker1%를 사용한다.

1. 판매자가90,000원 SELL 1개를 제출한다. 주문장 진입 응답을 확인한다.
2. 구매자가 지정가100,000원 BUY 3개를 제출한다. 거래300,000+최대 수수료3,000=303,000을 예약한다. 기존 판매 주문의90,000원에1개가 체결된다. 응답에는 체결1개와 남은2개의 주문장 진입이 있어야 한다.
3. 체결 대금90,000+구매 수수료900=90,900을 소비한다. 이1개에 잡아둔101,000 중10,100은 이미 반환된다. 나머지2개에 필요한202,000만 동결한다. 실제 DB의 최초·남은 예약과 양쪽 잔고를 조회한다.
4. 구매자가 같은 주문의 취소 HTTP 요청을 보낸다. 남은2개의 예약202,000만 반환한다. 받은 BTC1개, 판매자 정산, 이미 기록한 체결 원장은 유지한다.

| 직접 확인한 시점 | 구매자 현금 available / hold | 구매자 BTC | 구매 예약 |
| --- | --- | --- | --- |
| 1개 체결 후 | 707,100 / 202,000 | 1 | ACTIVE·남은수량2·남은금액202,000·남은수수료2,000 |
| 남은2개 취소 후 | 909,100 / 0 | 1 | RELEASED·남은수량2·남은금액/수수료0 |

최종 현금은 `1,000,000 - 90,000 - 900 = 909,100`이라는 독립 기대값이다. 판매자는 두 시점 모두 BTC9·현금89,550이다. 거래소 수수료는 판매자450+구매자900=1,350원이다.

취소 전에는 정산 원장1건·자산별 차대 균형을 확인한다. 원장 거래와 분개를 그대로 읽어 두고 취소 뒤 전체 목록이 같은지 비교한다. 개수만 유지하면서 기존 원장을 바꾸거나 새로 기록하는 결과도 실패해야 한다. 저장 이벤트는 SELL 진입 → 체결 → BUY 잔량 진입 → BUY 취소의4개다. 이벤트의 가격·주문 ID·잔량은 HTTP 응답에서, 저장 순서·타입은 실제 DB에서 확인한다. DB 이벤트 payload의 모든 필드까지 비교하는 테스트는 아니다.

근거: [HTTP·DB 테스트](../app-api/src/test/kotlin/com/exchange/core/api/order/application/OrderLifecycleE2ETest.kt), [주문 제출](../app-api/src/main/kotlin/com/exchange/core/api/order/application/SubmitOrderUseCase.kt), [주문 취소](../app-api/src/main/kotlin/com/exchange/core/api/order/application/CancelOrderUseCase.kt), [테스트의 고정 수수료 설정](../app-api/src/test/kotlin/com/exchange/core/support/ExchangeIntegrationTestConfiguration.kt). 실제 코드 발췌는 아래 접힌 근거에서 읽을 수 있다.

<details markdown="1">
<summary>근거 테스트 보기 · 취소 전후 원장을 그대로 비교</summary>

원문: `app-api/src/test/kotlin/com/exchange/core/api/order/application/OrderLifecycleE2ETest.kt` · 350~359행 · 파일 SHA256 `cd81a9193d20`. 실제 테스트 발췌다. `settledTransactions`와 `settledPostings`는 부분 체결이 끝난 뒤 읽은 원장이다. 아래 조회는 취소가 끝난 뒤 다시 실행한다.

```kotlin
        assertEquals(
            settledTransactions,
            jdbcTemplate.queryForList("select * from ledger_transactions order by ledger_transaction_id"),
        )
        assertEquals(
            settledPostings,
            jdbcTemplate.queryForList(
                "select * from ledger_postings order by ledger_transaction_id, posting_sequence",
            ),
        )
```

</details>

## 확정한 HTTP 범위

2026-10-03 BUY 부분 체결 후 취소의 HTTP 대표 사례 1개를 포함하기로 확정했다. 기존 MockMvc·실제 PostgreSQL을 재사용해 서비스 수준 검증과 HTTP 연결의 차이를 확인한다. 해당 사례를 추가한 HTTP suite4개가 성공했으며, 모든 오류를 HTTP로 복제하지 않는다. 실행 성공과 사람의 코드 검토는 구분한다.

## 실행 결과

기존 단위1~3은 순수 도메인110개(ledger21·order52·fee37), 실제 DB·Service 선택 검증53개와 전체646개(제품·DB278 + 구조368)를 재실행해 실패·오류·skip0을 확인했다. 그 뒤 이번 HTTP1개를 추가하고 기존 HTTP suite4개를 다시 실행해 모두 통과했다. 최종 build도 성공했다. **현재 합계647개(제품·DB279 + 구조368), 실패·오류·skip0**이다. 이번 최종 build에서는 앱95개와 구조368개를 실제 재실행했고, 변경 없는 도메인184개는 직전 성공 결과를 재사용했다. P01~P09의 실행·성공 확인도 다시 수행했다. 신규17개가 실제 성공 결과에 모두 포함됐다. 새 사례는 기존 제품 구현에서 처음부터 통과했다. 운영 동작을 고의로 깨뜨려 Red를 만들지 않았으며 실제 제품 결함을 재현했다고 주장하지 않는다.

| 단계 | 지금 결과 | 근거 |
| --- | --- | --- |
| 기대값과 테스트 검토 | 독립 상수·오류 원인·금지할 변화 대조 | 원본/식별자 보존, 숫자 범위 오류, source event 중복 분기. 작성하지 않은 도메인·Service 변경의 별도 소스 검토도 수행 |
| 순수 도메인 | 110개 통과, 실패/오류/skip0 | ledger·order·fee 모듈의 HTML/XML |
| 실제 DB·Service | 53개 통과, 실패/오류/skip0 | 저장소3개·Service3개 suite의 HTML/XML |
| HTTP 추가 전 전체 build·P01~P09 실행 확인 | 646개 통과, 실패/오류/skip0. P01~P09 실행·성공 | 모든 test 작업 재실행. 6분21초 |
| HTTP 대표1개 + 기존 lifecycle 사례 | 4개 실행·성공, 실패/오류/skip0 | MockMvc·실제 PostgreSQL. 46초 |
| HTTP 추가 후 최종 build·P01~P09 확인 | 앱95 + 구조368 실제 재실행·성공. 도메인184 직전 결과 재사용. 합계647·실패/오류/skip0 | 이번에는 `--rerun-tasks` 없이 변경 영향을 Gradle이 확인. 6분21초 |

[실행 기록](immutable-db-contracts-verification.json)에 명령·선택 suite/실제 테스트명·소스 해시를 남긴다. 같은 AI의 단계 이름만 다르다고 독립 검증을 보장하지 않는다. 별도 검토는 작성하지 않은 영역의 명세 상수와 실제 어설션을 대조했고 실행 결과에 의존하지 않았다.

<details markdown="1">
<summary>최종 실행 명령과 보고서 위치</summary>

```bash
cd /Users/0chord/.codex/worktrees/issue22-contract-spec/exchange-core
JAVA_HOME=/Users/0chord/Library/Java/JavaVirtualMachines/corretto-25.0.2/Contents/Home ./gradlew build :architecture-tests:verifyArchitectureReport --no-daemon --continue --console=plain
```

`BUILD SUCCESSFUL`과 XML 결과를 대조했다. 제품·DB 보고서는 `app-api/build/reports/tests/test/index.html`, 구조 보고서는 `architecture-tests/build/reports/tests/test/index.html`, 순수 도메인은 각 모듈의 같은 보고서 위치다. XML은 각 모듈 `build/test-results/test/TEST-*.xml`에 있다. 테스트가 없는 domain-common·benchmark-jmh는 대상0을 성공 테스트로 집계하지 않는다.

이번 명령은 HTTP 추가 후 최종 실행이다. `UP-TO-DATE` 도메인 결과를 이번 재실행이라고 세지 않는다. 실행 기록의 소스 해시가 현재 파일과 일치하며 모든 신규17개를 성공 XML에 대조했다. 최종 실행 뒤에는 설명·실행 기록만 갱신했다. 이 기록은 로컬 검증 시점의 근거이며, 원격 CI·사람의 PR 검토·병합·이슈 완료 상태는 GitHub에서 별도로 확인한다.

</details>

## 실제 변경과 코드 읽기

테스트는 실제 도메인 함수·DB·Spring Bean을 호출한다. ArchUnit의 구조 위반 예제와 다르다. 변경한 테스트를 아래 실제 원문에서 필요할 때 펼쳐 읽을 수 있게 연결한다. 해시와 실행 상태는 [실행 기록](immutable-db-contracts-verification.json)에 남긴다.

| 변경 파일 | 성격·읽을 목적 | 신규/보강 |
| --- | --- | --- |
| [BalanceTest](../domain-ledger/src/test/kotlin/com/exchange/core/ledger/BalanceTest.kt) | 제품 동작 테스트 · 새 잔고와 원본/식별자를 따로 확인 | 신규5개, 기존 정상·부족·credit overflow 보강 |
| [LedgerTransactionTest](../domain-ledger/src/test/kotlin/com/exchange/core/ledger/LedgerTransactionTest.kt) | 제품 동작 테스트 · 입력/노출 목록을 실제 변경하려고 시도 | 기존 생성 사례 보강 |
| [OrderReservationTest](../domain-order/src/test/kotlin/com/exchange/core/order/OrderReservationTest.kt) | 제품 동작 테스트 · 별도 거래/수수료 한도와 고정 정보/원본 보존 | 신규2개, 기존 상태 전이/거절 보강 |
| [OrderFillSettlementCalculatorTest](../domain-order/src/test/kotlin/com/exchange/core/order/OrderFillSettlementCalculatorTest.kt) | 제품 동작 테스트 · 계산에 넣은 최초/중간 예약 보존 | 기존 분할 수수료3개 사례 보강 |
| [PostgresBalanceStoreTest](../app-api/src/test/kotlin/com/exchange/core/api/ledger/infrastructure/persistence/PostgresBalanceStoreTest.kt) | 실제 DB 테스트 · 반환 객체와 실제 행, 산술 오류의 정확한 원인 | 신규3개, 기존 행 확인의 식별자 보강 |
| [OrderFundingServiceTest](../app-api/src/test/kotlin/com/exchange/core/api/order/application/OrderFundingServiceTest.kt) | 실제 DB·Bean 테스트 · 예약 INSERT와 잔고 이동이 함께 끝나는가 | 신규3개. 기존 latch/executor 방식과 예약 개수 확인 재사용 |
| [OrderReservationReleaseServiceTest](../app-api/src/test/kotlin/com/exchange/core/api/order/application/OrderReservationReleaseServiceTest.kt) | 실제 DB·Bean 테스트 · 서로 다른 예약 경쟁의 패자 상태 | 신규1개, 기존 부족 롤백의 예약 전체 보존 보강 |
| [TradeSettlementServiceTest](../app-api/src/test/kotlin/com/exchange/core/api/order/application/TradeSettlementServiceTest.kt) | 실제 DB·Bean 테스트 · 후반 소비 실패와 정확한 이벤트 중복 거절 | 신규2개. 기존 전체 DB snapshot/분개 도우미 재사용 |
| [OrderLifecycleE2ETest](../app-api/src/test/kotlin/com/exchange/core/api/order/application/OrderLifecycleE2ETest.kt) | HTTP 처리·실제 DB 테스트 · 부분 체결 뒤 잔여 예약만 반환하고 원장 보존 | 신규1개. 기존 요청·잔고·원장 도우미 재사용 |
| 명세·이 문서·실행 기록·README·공통 책임표/흐름 문서 | 문서·실행 근거 · 범위와 실제 검증 연결 | 제품 동작 영향 없음 |

표의 테스트 파일은 모두 이번 변경에 속한다. HTML 화면 하단에 연결한 제품 실행 코드 원문은 호출 경계를 읽기 위한 기존 코드이며 이번에 수정한 파일이 아니다. 별도 fixture 파일·테스트 플랫폼·일반 결과 해석 도구를 추가하지 않았다.

## 보장 경계와 다음으로 읽을 판단

이번 검증은 지정한 정상·거절·경쟁·중복·롤백 사례를 실제 상태로 확인한다. 모든 스레드 스케줄·모든 실패 조합·다중 마켓 deadlock/복구를 증명하지 않는다. HTTP 부분 체결 후 취소의 연결과 기존 전량 체결·미체결 취소4개는 실제 실행했다. HTTP에서의 모든 실패 조합이나 외부 네트워크 장애로 범위를 확대하지 않는다.

다음으로 읽을 판단은 **‘한 정산의 롤백’과 ‘전체 주문 처리의 롤백’의 차이**다. 위3번에서 호출 전 hold179가 남는 이유와 앞선 별도 체결 성공이 유지되는 이유를 먼저 보면 된다. 설명 작성·실행 성공을 사용자가 이 경계를 이해하거나 승인한 것으로 기록하지 않는다.
