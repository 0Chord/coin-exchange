# #44 · 먼저 체결된 지정가 주문의 잔량을 후속 체결로 채우기

매수 주문 3개를 보내면 기존 매도 주문과 1개가 체결되고, 남은 2개가 주문장에 대기한다. 다음 매도 주문으로 그 2개까지 체결하고 **처음 받은 매수 주문 ID 그대로** 체결 합계 3개, 잔량 0, 정산 완료를 조회한다. 두 체결의 잔고·예약·수수료를 직접 대조한다. [이슈 #44](https://github.com/0Chord/coin-exchange/issues/44)

- **합의한 범위.** 실제 HTTP 정상 사례 한 건. 지정가 GTC의 기존 동작을 검증한다. 장애 주입·리더 전환·재시작·성능 측정은 제외한다.
- **확인한 기준.** 통합 `d80bd0f`에는 실제 WAS·Aeron·PostgreSQL 부분 체결 후 잔량 **취소** 시험이 있다. 한 주문이 두 체결에서 테이커와 메이커로 정산되는 금액은 도메인 시험에 있다. 이번에는 잔량이 **후속 체결**되는 동일 주문을 현재 HTTP·조회 DB 경로에서 검증한다. [통합 기준](https://github.com/0Chord/coin-exchange/commit/d80bd0fc622a4559f881c1a65f1846225d554243) · [기존 HTTP 시험](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitFundsTradingHttpTest.kt#L196) · [기존 역할 전환 시험](../domain-trading/src/test/kotlin/com/exchange/core/trading/TradingStateFeeTest.kt#L31)
- **검증 경계.** 주문 상태는 PostgreSQL을 읽는 기존 GET으로 확인한다. 사용 가능 잔고·예약·수수료는 실제 자금 엔진의 확정 상태를 기존 테스트 관찰로 대조한다. 영속 분개 직접 대조, 새 API·DB 스키마·제품 코드 변경은 이 사례의 범위 밖이다. 기존 이슈에서 언급한 영속 원장의 확인은 별도 범위로 남긴다. [조회 저장소](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/persistence/PostgresOrderQueryStore.kt#L172) · [테스트용 자금 관찰](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitFundsTradingHttpTest.kt#L1832)

## 실제 요청과 관찰 순서

한 마켓에서 매수자는 1,000 USDT, 매도자는 3 BTC로 시작한다. 다른 주문과 예약은 없다. 모든 주문은 단가 100, `LIMIT + GTC`다. 사례의 수수료는 **메이커 1%, 테이커 2%**로 정한다. 메이커는 이미 주문장에 있던 쪽이고 테이커는 나중에 들어와 그 주문과 체결하는 쪽이다. 서로 다른 수수료율을 쓰면 같은 매수 주문에 두 역할의 요율이 각각 적용됐는지 잔고로도 확인할 수 있다.

| 순서 | 실제 HTTP 입력 | 확인한 뒤 다음 단계로 넘어갈 조건 |
| --- | --- | --- |
| 1. 매도 1개를 먼저 대기시킨다 | 매도자 요청 번호 1로 매도 1개 POST | `202 ACCEPTED`. 응답의 첫 매도 주문 ID를 보관한다. GET `RESTING / APPLIED`에서 체결 0, 잔량 1을 확인한 뒤 매수 주문을 보낸다. |
| 2. 매수 3개 중 1개가 먼저 체결된다 | 매수자 요청 번호 1로 매수 3개 POST | `202 ACCEPTED`. 응답의 매수 주문 ID를 보관한다. 같은 주문의 GET이 `200`, `PARTIALLY_FILLED / APPLIED`, 체결 1·잔량 2·취소 0이 될 때까지 기다린다. 아래 중간 잔고와 예약을 확인한 뒤에만 두 번째 매도를 보낸다. |
| 3. 매수 잔량 2개가 후속 체결된다 | 같은 매도자의 요청 번호 2로 매도 2개 POST | `202 ACCEPTED`. 첫 매도와 다른 주문 ID를 받는다. **2단계의 매수 주문 ID**로 GET해 `200`, `FILLED / APPLIED`, 누적 체결 3·잔량 0·취소 0을 확인한다. |

세 주문 모두 기존 `POST /api/trading/v3/markets/{marketId}/orders`로 보내고, 동일 WAS의 `GET /api/trading/v3/markets/{marketId}/orders/{orderId}`로 확인한다. 기존 HTTP 채널 발급을 이용한다. GET에서 매수 주문의 `initialQuantity=3`과 `notExecutedQuantity=0`을 두 번 확인한다. `APPLIED`는 조회된 주문의 마지막 결과까지 자금이 반영됐다는 뜻이다. 조회 반영이 늦을 수 있으므로 기존 제한 시간 조회를 이용한다. [HTTP 진입점](../app-api/src/main/kotlin/com/exchange/core/api/trading/api/BoundedTradingOrderController.kt#L19) · [GET 응답](../app-api/src/main/kotlin/com/exchange/core/api/trading/api/OrderQueryController.kt#L23) · [정산 완료 판정](../app-api/src/main/kotlin/com/exchange/core/api/trading/application/OrderQueryModel.kt#L62)

## 독립 기대값

매수 접수에는 거래 대금 300과 최대 수수료 6을 합해 306 USDT가 필요하다. 첫 체결에서 매수자는 테이커로 대금 100과 수수료 2를 내고, 매도자는 메이커 수수료 1을 뺀 99를 받는다. 남은 매수 예약은 **거래 대금 200 + 수수료 예약 4 = 204**다. [기존 매수·매도 예약 계산](../domain-order/src/main/kotlin/com/exchange/core/order/OrderReservation.kt#L451)

두 번째 체결에서 같은 매수 주문은 메이커다. 대금 200과 수수료 2를 쓰고 수수료 예약의 나머지 **2**를 가용 잔고로 돌린다. 두 번째 매도 주문은 테이커라서 대금 200에서 수수료 4를 뺀 196을 받는다. 따라서 매수자의 누적 수수료는 **4**, 매도자의 누적 수수료는 **5**, 전체 수수료는 **9 USDT**다.

| 확정 시점 | 매수 USDT 가용 / 예약 | 매수 BTC 가용 / 예약 | 매도 USDT 가용 / 예약 | 매도 BTC 가용 / 예약 | 누적 수수료 USDT |
| --- | ---: | ---: | ---: | ---: | ---: |
| 첫 매도 주문 대기 | 1,000 / 0 | 0 / 0 | 0 / 0 | 2 / 1 | 0 |
| 첫 체결 정산 완료 | 694 / 204 | 1 / 0 | 99 / 0 | 2 / 0 | 3 |
| 후속 체결 정산 완료 | **696 / 0** | **3 / 0** | **295 / 0** | **0 / 0** | **9** |

첫 대기 때 아직 생성되지 않은 수취 자산 잔고는 0 / 0으로 읽는다. 첫 체결 뒤 매수 주문의 활성 예약에서 최초 예약액 306, 남은 수량 2, 남은 예약액 204, 그중 수수료 예약 4를 확인한다. 최종에는 같은 예약이 활성 목록에서 정리되고 양쪽 예약 잔고가 0이어야 한다. 최종 보존식은 **USDT 696 + 295 + 9 = 1,000, BTC 3 + 0 = 3**이다. 수수료 기대값은 제품 계산기를 호출해 만들지 않는다.

## 구현에 넘길 검증 단위

기존 `SplitFundsTradingHttpTest`에 테스트 메서드 하나를 추가했다. WAS 하나, 자금·매칭 각 1노드, 임시 PostgreSQL을 사용하는 기존 구성을 재사용한다. 초기 자산과 매도자 계정도 재사용한다. 새 사례의 수수료율만 서로 다르게 설정하고, 두 번째 매도에 같은 매도자의 새 요청 순번을 넣는다. [시험 설정과 초기 자산](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitFundsTradingHttpTest.kt#L1368) · [현재 수수료 설정](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitFundsTradingHttpTest.kt#L1916)

- 자금 엔진의 기존 확정 상태 사본에서 해당 마켓의 결과 진행 위치와 표의 잔고·예약·수수료를 함께 검증한다. 첫 체결 뒤 결과 순번 2, 최종은 3을 확인한다. 첫 매도 대기 결과가 1번이다.
- 같은 사본의 실제 체결 이벤트 두 건에서 가격 100과 수량 1·2, 첫 체결의 메이커/테이커가 첫 매도 ID/매수 ID, 두 번째가 매수 ID/후속 매도 ID인지 확인한다. GET의 수량과 함께 대조해 두 번째 주문이 기존 매수 잔량을 체결했는지 판별한다. [기록된 결과](../domain-trading/src/main/kotlin/com/exchange/core/trading/FundsResultSettlement.kt#L57) · [체결 이벤트의 주문 ID](../domain-matching/src/main/kotlin/com/exchange/core/matching/MatchingEvent.kt#L46)
- 이 한 사례에서 GET·두 체결·자금 값이 모두 맞으면 이번 검증 단위를 끝낸다. 대기 시간이 지나거나 값이 어긋나면 해당 단계의 최근 GET과 자금 결과 순번·실제 값을 기록해 실패 처리한다. 테스트를 통과시키기 위해 기대값을 맞춰 바꾸거나 범위를 넓히지 않는다.

Java 25와 Docker 환경에서 새 메서드 하나와 테스트 코드의 서식 검사를 실행했다.

```sh
cd /Users/0chord/Desktop/exchange-core
./gradlew --no-daemon --max-workers=2 :app-api:test \
  --tests '*SplitFundsTradingHttpTest.같은 지정가 매수 주문이 첫 체결 뒤 남은 수량까지 실제 HTTP로 체결된다' \
  :app-api:ktlintTestSourceSetCheck
```

## 실행 결과 · 2026-11-18

- 로컬 브랜치 `test/gtc-follow-up-fill/44`에서 **실제 HTTP 시험 1건 통과, 실패·오류·건너뜀 0건**과 테스트 코드 서식 검사 통과를 확인했다. 최종 Gradle 실행은 31초, JUnit의 해당 사례는 7.451초다. 조회 DB와 두 엔진을 실제로 사용했다. [추가한 시험](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitFundsTradingHttpTest.kt#L316)
- 작성 대화를 공유하지 않은 독립 검토에서 실패 시 GET과 자금 상태가 함께 표시되도록 보완하고, 조회의 0 수량은 누락이나 문자열로 대체하면 실패하도록 확인했다. 최종 검토 대상은 통합 `d80bd0f` 기준 테스트 패치 SHA-256 `b86acfa1d9b754b6272ccdefcc2a793f4e26bb6c12de2b81ea92f27028de616f`다. 이 범위에서 남은 리뷰 지적은 없었다.
- 이번 실행은 위 정상 사례 한 건으로 기록한다. 장애·성능 테스트와 영속 분개 대조는 이번 실행 범위에 포함되지 않는다.
