# #62 주문별 실제 청구 수수료 조회

**한 주문이 두 번 체결되면, 사용자는 체결마다 자기가 실제로 낸 수수료와 자산을 보고 주문 합계를 확인한다.** 이미 보이는 체결이 정산을 기다리는 동안에는 금액이 미확정이다. 정산이 끝나 청구액 0이 기록된 체결은 해당 자산의 `0`으로 응답한다. 최신 통합 기준은 #196 병합 커밋 `1ac3e3d`. 선행 #43·#55·#61 완료를 확인했다. [#62](https://github.com/0Chord/coin-exchange/issues/62) · [통합 기준](https://github.com/0Chord/coin-exchange/commit/1ac3e3d63824938a0a701a4523d918e765462e75)

## 조회 흐름

1. 사용자가 기존 주문별 체결 GET을 호출한다. 매수 3개 중 첫 1개가 체결되면 첫 체결이, 잔량 2개가 후속 체결되면 두 번째 체결이 기존 순서에 이어 보인다.
2. WAS가 체결의 역할과 정산 상태를 기존 기준으로 판정한다. 전송받은 정산 분개와 마켓의 저장 순번이 함께 커밋되고 체결 내용이 일치하면, 조회한 주문에 해당하는 실청구액 한쪽과 자산을 붙인다.
3. 같은 시점의 PostgreSQL에서 이 주문의 현재까지 확정된 수수료를 집계한다. 첫 페이지에 체결 한 건만 반환해도 합계는 주문 전체의 같은 조회 범위를 사용한다.

## 기존 저장 근거

- #55가 `order_query.settlement_transaction`에 양쪽 주문 ID, `maker_fee`, `taker_fee`, `quote_asset_id`, 정산 ID, 결과·체결 순번을 저장한다. 양수와 0 모두 같은 행에 영속화된다. 이 SPOT 경로에서 매수·매도 양쪽 수수료 자산은 그 시장의 결제 자산(`quote`)이다. 양수 수수료에는 수익 분개가 생긴다. 0의 근거는 커밋된 정산 행의 `0`이다. [기존 PostgreSQL 정산 행](../app-api/src/main/resources/db/migration/V12__create_order_query_settlement_ledger.sql#L5) · [SPOT 수수료 자산 규칙](../domain-trading/src/main/kotlin/com/exchange/core/trading/BoundedFundingState.kt#L1312) · [분개 생성](../domain-trading/src/main/kotlin/com/exchange/core/trading/SettlementLedgerTransactions.kt#L96)
- 기존 원본에는 주문이 접수될 때의 요율·정책 버전이 남는다. 감사 근거가 필요하면 V11의 접수 원문과 정산 ID로 연관되는 기록을 사용한다. 이 조회에서 청구액은 V12의 확정 값에서 읽는다. 최대 예약액·미사용 예약 반환은 별도 자금 기록이다. [접수 원본 저장](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/persistence/PostgresOrderQueryFeedStore.kt#L167) · [원본 정책 필드](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/codec/SplitOrderDeliveryCodec.kt#L489) · [확정 금액 저장](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/persistence/PostgresOrderQueryFeedStore.kt#L279)

## 응답 계약

`GET /api/trading/v3/markets/{marketId}/orders/{orderId}/fills`에 체결별 `chargedFee`와 주문의 `settledFeeTotal`을 추가한다. 각 값의 형태는 `{ "amount": "2", "assetId": "USDT" }`다. 금액 문자열은 해당 자산의 최소 단위 정수다. 개별 금액과 합계가 큰 경우에도 자릿수를 보존한다.

| 상태 | 체결의 `chargedFee` | 주문의 합계 |
| --- | --- | --- |
| 주문 조회 성공, 체결 0건 | `fills: []` | `null` |
| 첫 체결이 `PENDING`이고 커밋된 분개가 없음 | `null` | `null` |
| 체결이 `COMPLETED`이고 청구액 0 | `{ "amount": "0", "assetId": "USDT" }` | 이 행을 확정분에 포함한다. 확정된 모든 체결의 청구액이 0이면 합계에도 자산과 `0`을 담는다. |
| 체결이 `COMPLETED`이고 청구액이 양수 | 같은 역할의 저장 금액과 저장 자산 | 같은 주문의 해당 역할 청구액을 합산한다. |
| 이미 완료된 체결과 정산 대기 체결이 공존 | 완료 건은 금액, 대기 건은 `null` | 같은 조회 범위의 완료 건만 합산해 즉시 반환한다. |

첫 1개의 수수료가 확정되면 주문 합계에 바로 포함한다. 후속 체결의 대기 상태는 그 체결에 표시한다. B가 대기 중이면 매수 합계는 `2`, B가 완료되면 `4`다.

조회 준비 여부와 입력 검증은 기존 #61의 HTTP 계약을 따른다. `202`는 최소 접수 순번을 따라가는 중임을, `404`는 확인된 범위에 주문이 없음을 뜻한다. 정산 저장을 지원하지 않는 계보는 `503 QUERY_NOT_READY`, 읽기 실패는 `503 QUERY_UNAVAILABLE`이다. 정산 순번이 체결을 포함하는데 행이 없거나, 행만 앞서 있거나, ID·주문·가격·수량·자산이 원본과 어긋나면 `503 QUERY_INCONSISTENT`로 응답한다. [기존 #61 계약](order-fill-query-spec-61.md#get의-응답과-완료-기준) · [HTTP 코드 판정](../app-api/src/main/kotlin/com/exchange/core/api/trading/api/OrderQueryController.kt#L88) · [체결과 정산 일치 검사](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/persistence/PostgresOrderFillReader.kt#L293)

## 한 PR의 구현 범위

- 기존 체결 조회 모델·응답·PostgreSQL 읽기 경로를 사용한다. 페이지에 나온 체결은 기존 식별·순번·본문 일치 검사를 통과한 뒤 정산 행의 역할별 수수료와 결제 자산을 더한다. 저장 자산은 해당 체결의 보존된 마켓 정의와 대조한다. [기존 체결 모델](../app-api/src/main/kotlin/com/exchange/core/api/trading/application/OrderFillQueryModel.kt#L16) · [GET 응답](../app-api/src/main/kotlin/com/exchange/core/api/trading/api/OrderQueryResponse.kt#L49)
- 주문 전체 합계는 같은 `REPEATABLE_READ` 읽기 트랜잭션에서 PostgreSQL로 한 번 집계한다. 현재 자금 계보의 해당 마켓·주문에 실제 귀속되고 조회 주문에 반영됐으며 정산 저장 순번이 덮는 체결을 포함한다. 집계 상한은 주문의 마지막 반영 결과와 정산 저장 순번 중 작은 값이다. 커서는 체결 목록에만 적용한다. 다른 주문의 역할별 금액, 이후 결과, 취소·예약 반환, 미저장 체결은 합계에서 제외한다. [트랜잭션 시작점](../app-api/src/main/kotlin/com/exchange/core/api/trading/application/OrderQueryUseCase.kt#L52) · [체결 페이지 범위](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/persistence/PostgresOrderFillReader.kt#L37)
- V13의 주문 후보 검색을 재사용한다. 페이지가 주문 전체를 담으면 그 페이지에서 검증한 체결을 재사용한다. 여러 페이지에 걸치면 이 주문의 확정 범위만 유한한 묶음으로 읽고 페이지 밖 정산 행도 V11 매칭 원문과 대조한다. 금액 합계는 V12의 정산 행에서 직접 읽으며, 이때 합산된 수량과 원문에서 확인한 수량이 일치해야 한다. V14는 기존 행에 메이커·테이커 주문별 인덱스 두 개를 추가한다. PostgreSQL의 `SUM(bigint)`는 정확한 정수로 읽는다. 수수료 정책 변경, 원장 전체 대조, 다른 조회 API의 확대는 별도 범위다. [주문 후보 인덱스](../app-api/src/main/resources/db/migration/V13__index_order_query_fill_candidates.sql#L2) · [정산 주문 인덱스](../app-api/src/main/resources/db/migration/V14__index_order_query_settled_fees.sql#L2) · [#58](https://github.com/0Chord/coin-exchange/issues/58)

## 수용 사례와 독립 기대값

### #44의 가격·수량과 실제 기대값

단가 100에서 매도 1개를 대기시킨 뒤 매수 3개 주문으로 첫 1개를 체결한다. 이후 매도 2개로 매수 잔량을 체결한다. #44의 메이커 1%, 테이커 2% 값을 사용한다. 기존 #61 시험처럼 정산 저장을 켜고 각각 분개 트랜잭션 시작 직전과 실제 커밋 뒤를 읽는다. [#44 독립 기대값](gtc-follow-up-fill-spec.md#독립-기대값) · [#61 기존 HTTP 시험](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitFundsTradingHttpTest.kt#L570)

| 체결 | 매수 주문 금액 | 해당 매도 주문 금액 | 양쪽 합계 |
| --- | ---: | ---: | ---: |
| 첫 1개 A | TAKER 2 | 첫 매도 MAKER 1 | 3 |
| 후속 2개 B | MAKER 2 | 후속 매도 TAKER 4 | 6 |
| 두 번 커밋 후 | **주문 합계 4** | 첫 매도 합계 1, 후속 매도 합계 4 | **9** |

금액은 전부 USDT 최소 단위다. #44 최초 예약 수수료 `6`과 미사용분 반환 `2`는 실제 청구액 `4`와 각각 대조한다. 수수료 수익 분개는 A에서 `3`, B에서 `6`이어야 한다.

| WAS의 조회 시점 | 매수 주문 A | 매수 주문 B | 확정분 합계 |
| --- | --- | --- | ---: |
| A 매칭 반영, 분개 커밋 전 | `PENDING`, 금액 `null` | 미발생 | `null` |
| A 분개 커밋 후 | `COMPLETED`, `2` | 미발생 | `2` |
| B 매칭 반영, 분개 커밋 전 | `COMPLETED`, `2` | `PENDING`, 금액 `null` | `2` |
| B 분개 커밋 후 | `COMPLETED`, `2` | `COMPLETED`, `2` | `4` |

`limit=1`에서 첫·다음 페이지가 각각 A·B를 반환한다. 두 요청 사이에 저장 내용이 바뀌지 않으면 매수 합계는 둘 다 `4`다. 조회 시점이 달라지면 저장된 확정분도 달라질 수 있다.

### 같은 흐름으로 확정 0과 이월 확인

보조 사례에서는 메이커 `4,000 ppm(0.4%)`, 테이커 `6,000 ppm(0.6%)`로 바꾼다. 첫 1개에서 매수 수수료는 `floor(100 × 0.006) = 0`이고 0.6을 같은 주문에 이월한다. 후속 2개의 수수료는 `floor(200 × 0.004 + 0.6) = 1`이다. 첫 매도와 후속 매도의 실청구액은 각각 `0`, `1`이다. HTTP에서 첫 체결의 저장 전 `null`과 저장 후 `{ "amount": "0", "assetId": "USDT" }`를 대조한다. 후속 저장 뒤 매수 주문의 합계는 `1`, 같은 마켓의 두 체결 수수료 수익 합은 `2`다. 저장된 수익 분개와 두 주문의 실제 금액을 비교하고, 수기 기대값을 검증문에 사용한다. [기존 나머지 계산 규칙](../domain-fee/src/main/kotlin/com/exchange/core/fee/TradingFeeCalculator.kt#L35)

### 기존 데이터와 실패 경계

- 기존 V12 자료를 인덱스 적용 뒤 복사 없이 다시 열고, 같은 체결 저장 요청을 반복해도 개별 금액·자산·주문 합계가 유지되는지 확인한다. 부분 체결 뒤 나머지를 취소한 주문은 취소 전 청구액을 그대로 보여준다. 실제 PostgreSQL 시험에 있는 취소·반복 저장·재조회 자료를 확장한다. [기존 자료 재열기와 취소 검증](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/persistence/PostgresOrderQueryFeedStoreTest.kt#L120)
- 정산 저장이 매칭 조회 반영보다 앞선 결과는 그 결과가 조회 범위에 들어온 뒤 합계에 포함된다. 같은 요청의 페이지와 합계는 한 시점으로 검증한다. 다른 주문·마켓의 수수료가 섞이면 실패한다. 실제 DB 행 불일치와 읽기 실패는 기존 #61 오류 시험을 확장한다. [기존 동시 커밋 회귀](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/persistence/PostgresOrderQueryFeedStoreTest.kt#L318) · [HTTP 통합 시험](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitFundsTradingHttpTest.kt#L570)

## 구현과 검증 순서

1. 기존 HTTP 체결 시험에 #44의 양쪽 역할별 실청구액과 커밋 전후 기대값을 붙인다.
2. 같은 PR에서 현재 PostgreSQL 읽기와 HTTP 응답을 연결한다. 기존 V12 기록, 페이지와 독립적인 합계, 확정 0과 이월, 취소·재조회·503 회귀를 확인한다.
3. 기존 테스트가 WAS·Aeron·PostgreSQL을 준비한다. 구현 시 실행할 대상은 아래와 같다. 실제 서버 재시작은 #66, 운영 규모의 쿼리 성능은 별도 검증 범위다. [#66](https://github.com/0Chord/coin-exchange/issues/66)

```sh
cd /Users/0chord/Desktop/exchange-core
./gradlew --no-daemon --max-workers=2 :app-api:test \
  --tests '*SplitFundsTradingHttpTest.주문별 체결 조회는 양쪽 주문과 정산 저장 상태를 연결한다' \
  --tests '*SplitFundsTradingHttpTest.같은 지정가 매수 주문이 첫 체결 뒤 남은 수량까지 실제 HTTP로 체결된다' \
  --tests '*SplitFundsTradingHttpTest.주문별 체결 수수료는 확정 0과 수수료 이월을 표시한다' \
  --tests '*PostgresOrderQueryFeedStoreTest' \
  --tests '*PostgresOrderQueryStoreTest' \
  --tests '*OrderQueryUseCaseTest' \
  --tests '*OrderQueryConfigurationTest' \
  --tests '*OrderQueryHttpTest' \
  :app-api:ktlintCheck :architecture-tests:ktlintCheck :architecture-tests:test \
  :architecture-tests:verifyArchitectureReport --continue --console=plain
```

## 구현과 검증 기록

- 기존 체결 GET이 V12의 `maker_fee` 또는 `taker_fee`와 저장된 `quote_asset_id`를 읽는다. 체결과 주문 합계는 `amount`를 최소 단위 정수 문자열로 내려준다. `PENDING`의 금액은 `null`이고, `COMPLETED`에는 자산과 금액이 있으며 확정 0도 값으로 내려준다. [응답 모델](../app-api/src/main/kotlin/com/exchange/core/api/trading/application/OrderFillQueryModel.kt#L16)
- 구현 전 실제 HTTP에서 첫 체결의 분개가 커밋된 뒤 `chargedFee`가 빠져 `{"amount":"2","assetId":"USDT"}` 기대값에 실패했다. 기존 PostgreSQL·HTTP 시험에 양쪽 실제 수수료, 저장된 분개와 확정 합계 전이, `limit=1` 양쪽 페이지를 연결했다. 요율 `4,000/6,000 ppm`에서는 첫 확정 0과 후속 청구 1을 대조한다. [HTTP 수용 사례](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitFundsTradingHttpTest.kt#L570)
- 독립 리뷰에서 페이지 밖 정산 행의 주문 ID 교환, 최근 체결이 정산 대기 중일 때 이전 정산 행 누락, 정산 행은 남아 있는데 매칭 원문이나 적용 기록만 누락된 사례를 확인했다. 이 주문 전체의 확정 금액을 내려주기 전에 체결별 귀속을 대조하고, 매칭 원문과 정산 행에서 수량이 각각 같은지 검사한다. [페이지 밖 원문 대조](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/persistence/PostgresOrderFillReader.kt#L175) · [누락 반례](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/persistence/PostgresOrderQueryFeedStoreTest.kt#L183) · [귀속 변경 반례](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/persistence/PostgresOrderQueryFeedStoreTest.kt#L265)
- 통합 기준 `1ac3e3d`에 이번 변경을 적용해 파일별 체크섬을 고정했다. 독립 리뷰의 최종 체크섬 `37999e437d669f054c2456666d4a1cd69e1c4adbeea281f8c0f1f80ca3817ca6`에서 PostgreSQL 16으로 누락 반례와 기존 V12 기록의 V14 업그레이드를 다시 확인했다. 확인 범위에서 미해결 결함은 없었다. 고정 이후 이 문서만 갱신했다.
- 위 Gradle 명령을 최종 코드에서 실행했다. HTTP·PostgreSQL 등을 포함한 앱 테스트 66건, 아키텍처 테스트 386건, 필수 운영 검사 P01~P09와 ktlint가 통과했다. 실패·오류·건너뜀은 0건이다.
- V14는 일반 `CREATE INDEX`로 기존 두 역할의 주문 인덱스를 생성한다. 독립 리뷰에서 PostgreSQL 16의 정산 20,000행으로 실제 실행 계획이 두 인덱스를 사용함을 확인했다. 운영 데이터 규모에서 인덱스 생성 시간, 동시 쓰기 대기와 조회 성능은 측정하지 않았다.
