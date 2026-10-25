# #84 매도 주문의 자금 변경안 계산

> 2026-10-15 · 로컬 구현·검증·독립 구현 리뷰 완료. 아래 5개 검사 모듈 총 547개·전체 lint·필수 운영 구조 검사 P01~P09 통과. PR·CI·병합 완료를 뜻하지 않는다.

**BTC 3개 중 2개를 팔려면, 사용 가능 1개·예약 2개가 되는 새 후보를 만든다. 원래 잔고 3/0은 바꾸지 않는다.**

이전에는 BUY만 계산하고 SELL은 미지원으로 거절했다. 이번에는 기존 SELL 예약 규칙을 연결한다. 팔 BTC만 묶고, USDT는 미리 요구하지 않는다. 수수료는 이후 실제 판매대금에서 차감하므로 후보의 수수료 예약은 0이다. 전체 수수료 정책은 보관한다.

이 기록은 구현·실행 근거다. 업무 계약의 원본은 [GitHub #84 명세](https://github.com/0Chord/coin-exchange/issues/84)이며 새로운 합의를 추가하지 않는다.

## 전체 흐름에서 달라진 곳

1. **주문 방향에 맞는 잔고를 받는다.** BUY면 quote(살 대금), SELL이면 base(팔 자산)다. BTC를 파는 주문에 USDT 잔고를 연결한 것은 사용자의 자금 부족이 아니라 서버의 연결 오류다.
2. **기존 도메인으로 계산한다.** SELL 필요액은 BTC 수량 자체다. 기존 잔고 객체가 부족 여부를 판단하고 새 잔고를 반환한다. 부족이면 필요한 BTC·현재 BTC·부족분을 담은 거절이다.
3. **후보가 같은 주문을 설명하는지 대조한다.** 이전/새 잔고, 신규 ACTIVE 예약, 수수료 정책과 RESERVE 원장을 확인한다. SELL 예약액은 주문 수량과 같아야 하고 수수료 선예약은 0이어야 한다. 원장의 사용자·자산·계정·방향·금액·두 항목도 정확히 맞아야 한다.
4. **후보 또는 거절을 반환한다.** 잘못된 서버 문맥·후보 조합·hold 합산 초과는 예외로 드러낸다. 원본 불변 객체는 어느 경로에서도 유지된다. 이 함수에는 외부 저장·스레드·거래 실행이 없다.

| 상황 | 결과 | 남는 원본/외부 효과 |
| --- | --- | --- |
| BTC 3/0에서 SELL 2 | 새 후보 BTC 1/2, 예약 2, BTC RESERVE 각 2 | 원본 3/0, 실제 상태 반영 없음 |
| BTC 1/0에서 SELL 2 | BTC 현재 1·필요 2·부족 1의 거절 | 원본 1/0, 후보 없음 |
| USDT 잔고를 SELL에 연결 | 내부 연결 오류 | 성공/부족으로 위장하지 않음 |
| 예약·잔고·원장을 1.5 BTC로 맞추되 주문은 2 BTC | 후보 생성 거절 | 숫자끼리 맞아도 주문의 의미가 다르면 허용하지 않음 |
| hold=MAX에서 1 추가 | ArithmeticException | 원본 유지, 잔고 부족으로 바꾸지 않음 |

**‘계산 후보’와 ‘접수 성공’은 다르다.** 이 문서는 #84의 당시 구현 기록이다. 최신 코드의 접수·재전송·매칭·정산 연결은 후속 이슈에서 추가됐다. #128 리뷰 수정에서는 원래 #46에 남겼던 가격·수량 단위와 전체 대금 범위 검증도 BUY·SELL 예약 전에 포함했다. 최신 조건과 사례는 [거래 상태 읽기의 정밀도 수정](trading-state-review.md#precision)을 따른다.

## 읽을 파일과 역할

| 파일 | 종류 | 이번 책임 |
| --- | --- | --- |
| [계산기](../domain-trading/src/main/kotlin/com/exchange/core/trading/OrderAdmissionCalculator.kt#L43) | 제품 실행 코드 | SELL 허용, 방향에 맞는 자산 확인. 기존 예약·잔고·원장 조합은 재사용 |
| [후보](../domain-trading/src/main/kotlin/com/exchange/core/trading/OrderAdmissionCandidate.kt#L29) | 제품 실행 코드 | 방향별 자산·계정, SELL 수량과 수수료 예약 불변식 |
| [계산기 테스트](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionCalculatorTest.kt#L80) | 순수 도메인 테스트 | 신규 10개 테스트 정의, 기존 0 수량·MARKET/IOC 정의 2개 확장. BUY 검증 유지 |
| [후보 테스트](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionCandidateTest.kt#L82) | 순수 도메인 테스트 | 신규 7개 정의. 도메인 생성 규칙은 통과하지만 후보 조합은 잘못된 반례 |
| 이 문서 | 구현·학습 기록 | 전체 흐름, 실제 테스트 의도, 실행·리뷰 근거 |

모듈·의존·Bean·SQL·마이그레이션·HTTP 변경은 없다. 기존 모듈을 재사용하므로 테스트를 위해 외부 도구나 저장소 대역을 추가하지 않았다.

## 새로 추가하거나 바꾼 테스트를 상황으로 읽기

모든 테스트는 실제 순수 Kotlin 도메인 객체를 연결한다. DB·HTTP·Aeron·Kafka는 사용하지 않는다. 같은 흐름의 여러 입력은 한 테스트 정의 안에서 반복하며, 아래의 반복 횟수를 JUnit 테스트 개수로 세지 않는다.

공통 입력: BTC-USDT, base scale=8, 사용자 user-1, 명시 원장 ID ledger-1·시각 2026-10-15T00:00:00Z. BTC 1개는 내부 100_000_000 단위다. 정책 0 또는 표에 지정한 SPOT 정책을 사용한다. 기대 잔고·원장 값은 명세의 독립 숫자이며 생산 계산기로 만들지 않는다.

### 계산기 — 준비 → 실행 → 실제 확인

| 코드 근거 | 준비(Given) | 실행(When) | 실제 확인(Then) |
| --- | --- | --- | --- |
| [테스트](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionCalculatorTest.kt#L80) | BTC 3/0, SELL 2 | 후보 계산 | 새 1/2·예약 2·BTC 원장 정확한 두 항목, 주문·정책·기록 ID/시각. 원본 3/0 |
| [테스트](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionCalculatorTest.kt#L97) | BTC 1/0, SELL 2 | 후보 계산 | 부족 자산 BTC, 현재/필요/부족 1/2/1의 정확한 거절과 원본 보존 |
| [테스트](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionCalculatorTest.kt#L112) | BTC 3/0, SELL 2, VIP·버전 7, maker/taker 1000/2000 또는 2000/1000 ppm | 각 정책으로 계산 | 새 1/2, 예약 2, 수수료 예약·나머지 0, 정책 전체 동일, 원본 유지 |
| [테스트](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionCalculatorTest.kt#L141) | BTC 2/0 또는 3/0.5, SELL 2 | 각 초기 잔고로 계산 | 각각 새 0/2, 1/2.5. 주문별 예약은 이번 2개만, 원본 유지 |
| [테스트](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionCalculatorTest.kt#L156) | 유효한 SELL에 USDT 또는 타 사용자 BTC | 각 잔고로 계산 | IllegalArgumentException, 해당 원본 잔고 유지 |
| [테스트](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionCalculatorTest.kt#L167) | SELL, 다른 마켓/비현물 정책/잘못된 기록·자산 ID를 각각 준비 | 각 잘못된 문맥으로 계산 | IllegalArgumentException, 원본 BTC 3/0 유지. 길이 65·NUL·잘못된 high/low surrogate 및 기록 ID 빈값/공백, 자산 ID 콜론 포함 |
| [테스트](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionCalculatorTest.kt#L192) | SELL의 market/order/user ID를 길이 65·NUL·high/low surrogate로 각각 설정, 사용자 콜론도 별도 | 각 입력으로 계산 | INVALID_ORDER_IDENTIFIER, 전달 잔고 유지 |
| [테스트](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionCalculatorTest.kt#L220) | 가격 1·수량 1 / 가격 MAX·수량 2 BTC / 가격 MAX·수량 MAX, 각 충분한 BTC | 각 입력으로 계산 | 최신 코드는 단위 위반 또는 quote 대금 초과를 정상 거절하고 원본을 유지. 호환되는 최소 수량 설정에서 대금 범위 안의 MAX 수량 정상 대조도 확인 |
| [테스트](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionCalculatorTest.kt#L254) | BTC 내부 1/MAX, 다음은 MAX/1, 수량 1 | 첫 계산에서 오류 확인 후 별개 잔고로 정상 계산 | 첫 번째 hold 합산만 ArithmeticException·원본 유지. 두 번째 MAX−1/2 후보와 원본 유지 |
| [테스트](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionCalculatorTest.kt#L275) | 동일 SELL·원본 잔고·명시 ID/시각 | 두 번 계산 후 다른 기록 ID·60초 뒤 시각으로 세 번째 계산 | 첫 두 후보 전체 값, 세 번째 ID/시각 변경과 같은 사건 ID·예약·분개·새 잔고. 이전 후보와 입력 유지 |
| [테스트](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionCalculatorTest.kt#L391) | BUY와 SELL 각각 수량 0 | 각 입력으로 계산 | INVALID_QUANTITY, 입력 수량 0 유지. 값 타입이 막는 음수/0 가격 검증으로 확대하지 않음 |
| [테스트](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionCalculatorTest.kt#L401) | BUY/SELL 각각 MARKET 또는 IOC, 총 네 조합 | 각 입력으로 계산 | 모두 UNSUPPORTED_ORDER_CONDITION. SELL LIMIT+GTC는 정상 테스트에서 허용 |

SELL 예약액은 여전히 base 수량이고 수수료 선예약은 0이다. 다만 #128 리뷰 수정부터는 가격·수량 단위 및 전체 quote 대금 범위도 예약 전에 검사한다. 예전의 ‘quote 표현과 무관하게 예약’ 기대는 승인된 부분 체결 안전성 규칙에 맞춰 거절로 바뀌었다. hold 합산 overflow 검사는 최소 수량을 허용하는 호환 설정을 명시해 기존 경계를 계속 실행한다.

### 후보 — 잘못된 조합도 그럴듯할 수 있다

공통 준비는 계산기 결과를 복사하지 않고 BTC 이전 3/0·이후 1/2, SELL 수량/예약 2, 수수료 0, ACTIVE 예약과 정확한 BTC 원장을 직접 만든다. 잘못된 예약·원장도 **예외 assertion 밖에서** 생성한다. 따라서 예약 객체나 원장 자체의 오류를 후보가 잡은 것처럼 보고하지 않는다.

| 코드 근거 | 준비(Given) | 실행(When) | 실제 확인(Then) |
| --- | --- | --- | --- |
| [테스트](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionCandidateTest.kt#L82) | 같은 SELL을 설명하는 정상 주문·마켓·정책·잔고·예약·기록 | 후보 직접 생성 | 모든 입력 필드 보존. 항상 예외를 내는 후보 구현은 통과하지 못함 |
| [테스트](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionCandidateTest.kt#L96) | 이전/새 잔고·예약·원장 자산을 모두 USDT로 일관되게 변경 | SELL 후보 생성 | IllegalArgumentException. 자산끼리 같아도 팔 자산 BTC와 다르면 거절 |
| [테스트](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionCandidateTest.kt#L113) | 예약·새 잔고 이동·균형 원장을 모두 1.5 BTC로 변경하되 주문은 2 | 후보 생성 | IllegalArgumentException. SELL 예약 수량 대응을 검증 |
| [테스트](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionCandidateTest.kt#L124) | 예약 총액 2 BTC는 유지하고 그중 1단위를 수수료 예약으로 표기 | 후보 생성 | IllegalArgumentException. 총액·균형만 맞는 수수료 선예약을 막음 |
| [테스트](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionCandidateTest.kt#L131) | 예약의 마켓/주문/사용자/방향/가격/최초 수량/남은 수량·금액/정책/수수료/나머지, RELEASED·SETTLED를 각각 변경; MARKET·IOC 주문도 별도 | 각 조합으로 후보 생성 | 각 IllegalArgumentException. 13개 예약 반례와 두 미지원 주문을 대조 |
| [테스트](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionCandidateTest.kt#L158) | 이전 잔고의 사용자·자산, 새 잔고의 사용자·자산·available·hold 또는 마켓을 각각 변경 | 각 조합으로 후보 생성 | 각 IllegalArgumentException. 합계만 보지 않고 각 칸·소유·마켓 대조 |
| [테스트](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionCandidateTest.kt#L177) | 균형 원장의 사용자/quote 자산+계정/계정만/반대 분개/다른 금액/순서/중복 항목 또는 SETTLEMENT 타입 | 각 원장으로 후보 생성 | 각 IllegalArgumentException. 원장 균형만으로 허용하지 않음 |

이 19개 행은 새 테스트 정의 17개와 기존 정의 수정 2개를 전부 설명한다. 나머지 기존 BUY 정의 24개는 변경하지 않았고 trading 전체 실행에 포함한다. 검증 helper는 주문·정책·이전/새 잔고·예약 전체, ID·시각·사건 ID, 원장 두 항목과 BigInteger 총액 보존을 확인한다. 기대 자산은 테스트에서 명시하며 실제 후보의 자산에서 정답을 얻지 않는다.

## 핵심 코드 두 곳

아래는 실제 소스 발췌이며 구현을 설명하려고 다시 작성한 의사 코드가 아니다. 파일 전체 해시와 줄 범위를 함께 고정한다. 이후 소스가 바뀌면 갱신해야 한다.

### 어느 잔고를 받을까

[원본](../domain-trading/src/main/kotlin/com/exchange/core/trading/OrderAdmissionCalculator.kt#L50) · 50–61행 · SHA-256 `78ccedbbae68f164addc0694e48dc1cd37a5a3fc7579129117bb512e64cd4da3`

```kotlin
        require(order.marketId == market.marketId) { "주문과 마켓 정의가 다릅니다" }
        require(order.userId == balance.userId) { "주문과 잔고 소유자가 다릅니다" }
        val expectedAssetId =
            when (order.side) {
                Side.BUY -> market.quoteAssetId
                Side.SELL -> market.baseAssetId
            }
        require(expectedAssetId == balance.assetId) { "주문 방향에 맞는 예약 자산의 잔고가 아닙니다" }
        require(validIdentifier(market.baseAssetId.value, accountPart = true)) { "base 자산 식별자가 잘못됐습니다" }
        require(validIdentifier(market.quoteAssetId.value, accountPart = true)) { "quote 자산 식별자가 잘못됐습니다" }
        require(feePolicySnapshot.productType == FeeProductType.SPOT) { "현물 수수료 정책이 필요합니다" }
        require(validIdentifier(ledgerTransactionId)) { "원장 거래 식별자가 잘못됐습니다" }
```

### SELL 후보가 보장할 것

[원본](../domain-trading/src/main/kotlin/com/exchange/core/trading/OrderAdmissionCandidate.kt#L35) · 35–64행 · SHA-256 `6721225a28922c7f9ec3693e0e46af1f972728382c268ac3838cac3bd845b515`

```kotlin
        require(order.marketId == market.marketId && reservation.marketId == order.marketId)
        require(reservation.orderId == order.orderId && reservation.userId == order.userId && reservation.side == order.side)
        require(previousBalance.userId == order.userId && nextBalance.userId == order.userId)
        require(previousBalance.assetId == expectedAssetId && nextBalance.assetId == expectedAssetId)
        require(reservation.assetId == expectedAssetId && reservation.limitPrice == order.price)
        require(reservation.initialQuantity == order.quantity && reservation.remainingQuantity == order.quantity)
        require(reservation.feePolicySnapshot == feePolicySnapshot)
        require(reservation.status == OrderReservationStatus.ACTIVE && reservation.releasedAmount == null)
        require(reservation.reservedAmount == reservation.remainingAmount)
        require(reservation.initialFeeReserveAmount == reservation.remainingFeeReserveAmount)
        require(reservation.feeRemainder == FeeRemainder.ZERO)
        if (order.side == Side.SELL) {
            // SELL 수수료는 판매대금에서 차감하므로 팔 수량에 더해 미리 묶지 않는다.
            require(reservation.reservedAmount.value == order.quantity.value)
            require(reservation.initialFeeReserveAmount == Amount.ZERO)
        }
        val amount = reservation.reservedAmount
        require(nextBalance.available.value == Math.subtractExact(previousBalance.available.value, amount.value))
        require(nextBalance.hold.value == Math.addExact(previousBalance.hold.value, amount.value))

        // 원장의 자산별 균형만으로는 잘못된 사용자·계정·방향을 찾을 수 없다.
        val account = "USER:${order.userId.value}:${expectedAssetId.value}"
        require(reserveTransaction.transactionType == LedgerTransactionType.RESERVE)
        require(
            reserveTransaction.postings ==
                listOf(
                    LedgerPosting("$account:AVAILABLE", expectedAssetId, LedgerPostingSide.DEBIT, amount),
                    LedgerPosting("$account:HOLD", expectedAssetId, LedgerPostingSide.CREDIT, amount),
                ),
        )
```

`when`은 주문 방향에 따라 자산을 고른다. `require`는 조합이 틀리면 예외로 중단한다. 계산 공식 자체를 새로 만든 것이 아니다. BUY/SELL의 자산 선택은 계산 진입과 후보 검증 각각에서 필요하며 별도 범용 정책 클래스로 추출하지 않았다.

## 실행 근거

- Red: 새 정상 SELL·부족 SELL 테스트 두 개가 현재 SELL 미지원 때문에 assertion에서 실패했다. 3개 수집/2개 실패(나머지 한 개는 당시 기존 SELL·MARKET·IOC 미지원 테스트). 환경·컴파일 실패가 아니다.
- 테스트 의도 검토: 작성 대화를 상속하지 않은 별도 에이전트가 S01~S10·숫자·후보 반례와 실제 assertion을 읽었다. 확인한 기대값 결함 없음. 읽기 검토이며 직접 실행은 하지 않았다.
- 최소 Green: SELL 허용·방향별 자산·후보 불변식 확장 후 trading 기존/초기 테스트 통과. 나머지 사례를 추가한 trading 43개도 통과했다.
- 관련 검증: `domain-trading` 43, `domain-order` 53, `domain-ledger` 39, `domain-fee` 37, `architecture-tests` 375로 총 547개. 각 모듈 XML을 대조하여 실패·오류·skip 0을 확인했다.
- `:architecture-tests:verifyArchitectureReport`는 필수 운영 P01~P09 실행·성공을 확인했다. 전체 `ktlintCheck`도 통과했다. 최종 관련 검증은 2분 23초였다.
- app-api HTTP/DB 제품 테스트·전체 build·원격 CI는 이번에 실행하지 않았다. 이번 변경은 순수 계산 경로이며 기존 SQL/HTTP 경로는 변경하지 않았다.

실행 폴더: `/Users/0chord/.codex/worktrees/issue84-sell-candidate/exchange-core` · 작업 브랜치 `feat/sell-admission-candidate/84`.

```sh
cd /Users/0chord/.codex/worktrees/issue84-sell-candidate/exchange-core
./gradlew :domain-trading:test :domain-order:test :domain-ledger:test :domain-fee:test :architecture-tests:verifyArchitectureReport ktlintCheck --console=plain
```

이전 Red 원본은 `/tmp/issue84-red.xml`, `/tmp/issue84-red.log`; 최신 검증 로그는 `/tmp/issue84-validation.log`다. 임시 로그는 영구 저장소 자료가 아니므로 핵심 원인·숫자는 이 기록에도 남긴다. 결과 XML은 각 모듈 `build/test-results/test`, HTML은 `build/reports/tests/test`에 생성된다.

## 독립 구현 리뷰와 대상

기준 `855eebe05775689b834509096e24bb749ead1f13` → 고정 리뷰 snapshot `7cf3b9cf65023f9742e688a901c2e8009f741181`.

이 snapshot은 로컬 검토용이며 작업 브랜치에 커밋하거나 원격 게시한 것이 아니다. 작성에 참여하지 않은 `fork_turns=none` 에이전트가 격리 checkout에서 코드·테스트·합의 명세를 읽고 검토한다. 작성 대화·완료 주장·다른 리뷰 결과는 최초 입력에 넣지 않았다. 검토 범위에서 확인된 미해결 결함은 없었다. 다음 내용을 독립적으로 확인했다.

- 격리 checkout에서 trading·order·ledger·fee·구조 검증·trading lint 실행: 547건 실패·오류·skip 0, P01~P09 성공.
- 별도 임시 입력 576개 조합을 독립 기대값과 대조: 정상 168개, 부족 거절 288개, 예상 산술 예외 120개. scale 0/8/18·수수료 0%/100%·Long 경계를 포함한다.
- 추가로 직접 만든 잘못된 후보 조합 3개 거절. 이 실험을 저장소 JUnit 547개에 더해 테스트 개수로 표시하지 않는다.
- BUY·SELL 정상/거절/예외·후보 불변식·원본·반복 계산 흐름과 실제 범위 제외를 확인했다. 원격 CI·실제 상태 반영·DB·체결·정산은 확인 범위 밖이다.

원인이 확인된 지적이 없어 수정/재리뷰 회차는 필요하지 않았다. 이는 검토 범위에서 확인된 결함이 없다는 뜻이며 모든 미래 버그가 없다는 보장이 아니다. 전달할 네 코드/테스트 파일은 snapshot의 SHA-256과 대조했으며 같은 내용이다. 추가된 이 문서는 실행·학습 기록으로서 snapshot 뒤에 작성됐고 코드 계약을 바꾸지 않는다.

## 수수료 예약에서 먼저 판단할 것

SELL은 팔 BTC를 먼저 묶고 실제 판매대금에서 수수료를 빼므로 USDT를 미리 예약하지 않는다. 판매대금 200 USDT·수수료 0.2%라면 199.6을 받는다는 숫자는 기존 정산 규칙의 설명이며 이번 코드가 정산까지 실행한 결과는 아니다.

후보 계산과 실제 접수의 구분, SELL 수수료 선예약 없음, [최신 가격·수량 검증](trading-state-review.md#precision)을 먼저 확인한다. 테스트·독립 AI 리뷰 통과를 사람의 이해·승인으로 표시하지 않는다.
