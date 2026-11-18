# #61 · 주문별 체결 내역과 정산 완료 여부 조회

3개 매수 주문이 먼저 1개, 나중에 2개 체결되면 사용자는 두 체결의 가격·수량·순서와 각 체결의 정산 상태를 볼 수 있다. 각 매도 주문에서 같은 체결을 조회하면 매수 주문에서 본 체결 ID와 일치한다. 기존 WAS와 공용 PostgreSQL에 주문별 체결 GET을 추가하는 작은 PR 하나로 진행한다. [#61](https://github.com/0Chord/coin-exchange/issues/61) · [통합 기준 `20d8a41`](https://github.com/0Chord/coin-exchange/commit/20d8a415307cc64105fd67a7e60c5de21b20668a)

## 사용자 흐름

1. 사용자가 주문을 접수하고 주문 ID를 받는다. 주문이 조회 DB에 반영됐고 체결이 없으면 체결 목록은 빈 목록이다.
2. 매칭 결과가 주문 조회 DB에 반영되면 실제 체결 건별로 한 줄이 보인다. 사용자는 가격·수량과 이 주문이 먼저 기다렸는지(`MAKER`), 기다리던 주문과 즉시 맞붙었는지(`TAKER`)를 본다.
3. 체결 기록이 있고 해당 정산 분개의 DB 커밋을 기다리면 `PENDING`이다. 같은 체결의 분개가 커밋되면 그 줄만 `COMPLETED`로 바뀐다. 최초 체결 ID와 체결 순서는 유지된다.

두 기록은 각자 커밋된다. 주문 상태의 `financialStatus=APPLIED`는 자금 엔진에 적용됐다는 뜻이다. 체결 내역의 `COMPLETED`는 해당 체결 분개가 PostgreSQL에 저장됐다는 뜻이다. 한 주문의 첫 체결이 `COMPLETED`이고 후속 체결이 `PENDING`일 수 있다. [기존 주문 조회 상태](../app-api/src/main/kotlin/com/exchange/core/api/trading/api/OrderQueryController.kt#L110) · [자금 적용 위치와 분개 수집 순서](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderQueryFeedWorker.kt#L280)

## 합의한 결정과 구현 제안

| 판단 | 반영 |
| --- | --- |
| 양쪽 주문에서 어떤 체결 ID를 보여줄까? | **사용자 선택은 ‘기존 정산 ID로 통일’.** 자금 계보·마켓 고정 번호·체결 엔진 순번으로 만드는 #195의 정산 ID를 그대로 `tradeId`로 쓴다. 매칭 원문으로 이미 계산할 수 있어 정산 대기부터 완료까지 동일하고, 매수·매도 주문과 재조회에서도 같은 값을 쓴다. 대안은 출처·마켓·순번을 각각 응답하는 방식이었다. 사용자 답변에서 별도 이유는 제시하지 않았다. |
| 작은 조회 경로는 어디에 둘까? | **구현 제안.** 기존 WAS 주문 조회 아래에 `GET /api/trading/v3/markets/{marketId}/orders/{orderId}/fills` 한 경로를 추가한다. 기존 주문 응답과 공용 DB를 재사용한다. |
| 어떤 시각을 응답할까? | **구현 제안.** 첫 응답은 체결 순서만 제공한다. 보관 중인 매칭 이벤트에는 체결 시각 필드가 없고, V12의 시각은 자금 정산 시각이다. |

[기존 정산 ID 생성](../domain-trading/src/main/kotlin/com/exchange/core/trading/TradingRequest.kt#L149) · [매칭 이벤트의 체결 필드](../domain-matching/src/main/kotlin/com/exchange/core/matching/MatchingEvent.kt#L46) · [V12 정산 거래](../app-api/src/main/resources/db/migration/V12__create_order_query_settlement_ledger.sql#L5)

## GET의 응답과 완료 기준

- 성공 응답에는 조회한 마켓·주문 ID, `fills`, `nextCursor`, 현재 조회가 포함하는 접수 순번인 `coveredAdmissionSequence`가 있다. 각 체결은 `tradeId`, `price`, `quantity`, 조회한 주문 기준의 `role`, `settlementStatus`를 담는다. 실제 체결 가격과 수량은 기존 주문 조회와 같은 정수 단위를 쓴다. 상대 주문 ID·사용자 ID와 실청구 수수료는 응답 범위에서 제외한다.
- 체결은 마켓의 엔진 순번 오름차순이다. 한 매칭 결과에 체결이 여러 개 들어갈 수 있어 페이지 위치는 **결과 순번과 그 안의 체결 엔진 순번**을 함께 쓴다. 구현 제안은 `limit` 기본 50·최대 100, 다음 페이지의 입력은 `afterResultSequence`와 `afterEngineSequence`를 함께 받는 것이다. 두 순번은 양수이고 둘 다 제공하거나 둘 다 생략한다. 다음 체결이 있으면 마지막 반환 체결의 두 순번으로 `nextCursor`를 만들고, 끝이면 `null`이다.
- 기존 주문 GET의 ID·마켓·`minAdmissionSequence` 검증과 읽기 상태를 재사용한다. 알려진 주문에 체결이 없으면 200과 빈 `fills`다. 최소 접수 순번에 미달하거나 아직 주문의 부재를 판정할 수 없으면 202, 확인된 범위에서 주문이 없으면 404다. 입력 오류는 400, 조회 미준비·DB 오류·저장 기록 불일치는 기존 503 계약을 따른다. 정산 저장을 켜지 않은 계보에서는 새 체결 GET을 `QUERY_NOT_READY`로 표시한다. 기존 주문 GET은 그 계보에서도 기존 조회 계약을 사용한다.
- **체결 저장이 먼저인 경우**에는 매칭 체결을 반환한다. 같은 DB에서 `settlement_through`가 그 결과 순번보다 뒤처져 있고 해당 정산 행을 아직 못 찾으면 `PENDING`이다.
- **정산 분개까지 커밋된 경우**에는 같은 출처·마켓·결과 순번·체결 엔진 순번, `tradeId`, 양쪽 주문·가격·수량이 일치하는 V12 정산 행과 그 결과를 포함하는 저장 순번을 확인해 `COMPLETED`로 반환한다. 자금 적용 순번과 주문 전체의 `financialStatus`는 이 판정에 사용하지 않는다.
- 저장 순번이 이미 해당 결과를 넘겼는데 정산 행이 없거나, 정산 행 내용이 매칭 원문과 다르거나, 정산 행이 저장 순번보다 앞서 있으면 `QUERY_INCONSISTENT`다. V12는 분개 전체와 저장 순번을 한 트랜잭션에 저장한다. 정산 행이 매칭 조회보다 먼저 도착하면 주문 체결 목록은 매칭 원문이 DB에 반영될 때 시작한다.

[주문 조회의 200·202·404·503 분기](../app-api/src/main/kotlin/com/exchange/core/api/trading/application/OrderQueryUseCase.kt#L24) · [분개와 순번의 원자 커밋](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/persistence/PostgresOrderQueryFeedStore.kt#L250)

## 기존 DB 기록을 읽는 방법

**확인한 사실.** #60은 매칭 결과를 저장하는 트랜잭션에서 영향받은 주문 목록이 든 `applied_batch`와 원문 `source_receipt`를 같은 적용 리비전으로 남긴다. 주문 목록에는 마켓과 접수 순번이 있고, 원문에는 양쪽 주문 ID가 있는 실제 체결이 들어 있다. #195 V12는 각 체결의 정산 거래·분개와 마켓별 저장 순번을 보관한다. [V11 매칭 원문](../app-api/src/main/resources/db/migration/V11__create_order_query_source_feed.sql#L19) · [매칭 반영과 원문 저장](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/persistence/PostgresOrderQueryFeedStore.kt#L184) · [저장된 주문 목록의 형식](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/persistence/PostgresOrderQueryStore.kt#L296)

**작은 PR의 구현 제안.** 이미 저장한 주문 목록과 원문을 이용한다. 새 기록 파이프라인이나 별도 체결 테이블의 과거 데이터 채우기 작업을 만들 필요가 없다.

1. 주문 ID로 기존 주문 행과 조회 가능 범위를 확인한다. 요청한 주문의 마켓·접수 순번이 포함된 반영 기록을 먼저 찾는다. 필요한 DB 변경은 그 주문 검색을 위한 인덱스 한 개다. 첫 후보는 `applied_batch`의 `records` 배열로 주문을 찾는 GIN 인덱스다. DB 안에 이미 보존한 옛 매칭 원문도 같은 방식으로 조회한다.
2. 같은 자금 계보와 적용 리비전으로 해당 반영 기록을 `PROJECTED`인 `MATCHING` 원문과 연결한다. 기존 해석기로 읽고 원문의 출처·마켓·결과 순번이 DB의 저장 키와 맞는지 확인한다. 조회 주문이 실제로 참여한 체결만 뽑는다. 접수·주문장 등록·취소·거절, 같은 결과에 들어 있는 다른 주문의 체결은 목록에서 제외한다. 금융 진행 위치만 갱신한 기록도 `MATCHING` 원문과 연결되지 않는다.
3. 조회 범위를 후보 순번으로 좁힌 뒤 원문을 유한한 묶음으로 읽는다. 필요한 페이지의 체결 수와 다음 페이지 존재 여부까지만 읽는다. 한 결과 안의 다음 체결도 다음 페이지에서 이어가며, 다른 주문을 포함한 전체 원문 이력을 요청마다 읽지 않는다. 같은 읽기 스냅샷에서 주문·매칭 원문·마켓 저장 순번·정산 행을 확인해 동시 커밋으로 서로 다른 시점을 섞지 않는다.
4. 정산 거래는 현재 계보·마켓·체결 엔진 순번으로 찾고, 완료 판정 전에 나머지 식별 값과 체결 내용을 대조한다. GET은 PostgreSQL에서 읽고, WAS의 기존 수집과 ACK는 기존 절차로 계속 진행한다.

운영 적용 전에는 기존 V11 원문으로 조회가 되는지와 필요한 후보만 읽는지 실제 PostgreSQL로 확인한다. 인덱스 생성 비용과 과거 기록이 많은 환경의 조회 성능은 이 명세에서 수치로 확정하지 않는다. 주문별 결과가 많은 환경에서 이 방식의 읽기 비용이 제약이 되면, 정규화된 체결 테이블과 기존 원문을 채우는 작업을 별도 검토한다.

## #44를 재사용한 실제 HTTP 수용 사례

기존 시험과 같이 `BTC-USDT`에서 첫 매도 1개를 100에 대기시키고, 매수 3개를 100에 접수한 뒤, 후속 매도 2개를 100에 보낸다. `A`와 `B`는 시험에서 각 체결의 실제 ID를 처음 관측해 붙인 이름이다. 기존 테스트가 WAS·Aeron·PostgreSQL을 준비하며, 이 사례에는 #195 정산 저장 설정을 켠다. [#44 실제 HTTP 사례](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitFundsTradingHttpTest.kt#L451) · [#195의 실제 DB 끊김 시험](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitFundsTradingHttpTest.kt#L318)

| 시점 | 매수 3개 주문의 체결 조회 | 상대 주문의 체결 조회 | 실제 PostgreSQL에서 확인 |
| --- | --- | --- | --- |
| 첫 매도 1개만 대기, 매칭 결과 1 | 매수 접수 전 | 첫 매도는 200, 빈 목록 | 체결 거래 0건 |
| 매수의 첫 1개 체결, 결과 2 | `A / 100 / 1 / TAKER / PENDING`, 주문 잔량 2 | 첫 매도가 `A / 100 / 1 / MAKER / PENDING` | 결과 2의 매칭 기록은 존재. `A` 정산 행은 부재. 자금 적용은 2까지 확인 |
| `A` 분개 커밋 뒤 | **같은 `A`**가 `COMPLETED` | 첫 매도도 **같은 `A`**가 `COMPLETED` | `A` 정산 행 1건과 결과 2의 저장 순번 |
| 후속 매도의 2개 체결, 결과 3 | `A`는 완료. `B / 100 / 2 / MAKER / PENDING`이 두 번째. 주문 체결량 3·잔량 0 | 후속 매도가 `B / 100 / 2 / TAKER / PENDING` | `A`는 존재하고 `B`는 아직 부재. 매칭과 자금 적용은 3까지 확인 |
| `B` 분개 커밋 뒤 | `A`, `B` 순서로 둘 다 완료 | 첫 매도는 `A` 한 줄, 후속 매도는 `B` 한 줄 | 서로 다른 `A`, `B`가 각각 1건이며 최초 ID와 금액 유지 |

**대기를 관찰하는 방법.** 기존 WAS의 테스트 전용 저장소 장식에서 체결이 있는 결과 2와 3을 각각 실제 V12 저장 직전에 잠시 멈춘다. 매칭 결과가 DB에 있고 자금 반영이 `APPLIED`인 것을 확인한 뒤에도 체결 GET의 해당 줄이 `PENDING`인 것을 읽는다. 시험의 문턱은 DB 트랜잭션을 열기 전에 두고 `finally`에서 풀어 준다. 문턱을 풀어 실제 커밋을 기다린 뒤, 같은 ID의 상태만 `COMPLETED`로 바뀌는지 본다. 이 방법으로 자금 적용과 분개 커밋을 혼동한 구현을 잡는다. [기존 WAS 시험 장치](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitFundsTradingHttpTest.kt#L574)

**추가로 필요한 좁은 검사.** 기존 PostgreSQL 저장소 시험을 활용한다. 한 매칭 결과에 서로 다른 체결이 여러 건 있는 데이터로 `limit=1` 페이지가 결과 중간에서 이어지고 다른 주문의 체결이 새지 않는지 확인한다. 취소만 있는 결과와 별개의 체결을 구분한다. 인덱스 추가 전에 미리 저장한 #195 활성 계보의 V11 매칭 원문과 V12 정산 행으로 조회를 다시 열어 목록과 ID가 유지되는지 확인한다. 정산 저장 위치만 앞서거나 정산 행 내용이 다른 손상 사례는 503이어야 한다. 정산 저장이 꺼진 계보의 기존 주문 조회와 새 체결 GET의 응답도 대조한다.

## 작업 범위와 인계

- 변경할 부분은 기존 WAS의 체결 읽기 경로, 기존 조회 DB의 주문 후보 인덱스, 실제 HTTP·PostgreSQL 검증이다. 체결 식별과 원문 저장은 #60·#195의 형식을 재사용한다. 신규 엔진 메시지·ACK, 신규 서비스, 실제 청구 수수료 상세(#62), 원장 대사(#58)와 성능·리더 교체 시험은 후속 범위다.
- 첫 구현에서는 기존 #44 데이터를 사용한 체결 GET 시험을 붙이고, 양쪽 주문의 동일 ID와 `PENDING → COMPLETED`를 각각 관측한다. 이후 조회 경로와 인덱스를 연결하고 위 DB 회귀를 확인한다.
- **검증 실행.** Java 25·Docker 환경에서 기존 Gradle 테스트가 WAS·Aeron·PostgreSQL을 준비한다. 실제 PostgreSQL 행과 HTTP 응답을 같은 사례에서 대조한다.

```sh
cd "$(git rev-parse --show-toplevel)"
./gradlew --no-daemon --max-workers=2 :app-api:test \
  --tests '*SplitFundsTradingHttpTest.주문별 체결 조회는 양쪽 주문과 정산 저장 상태를 연결한다' \
  --tests '*SplitFundsTradingHttpTest.같은 지정가 매수 주문이 첫 체결 뒤 남은 수량까지 실제 HTTP로 체결된다' \
  --tests '*SplitFundsTradingHttpTest.체결 정산 기록을 PostgreSQL에 한 번 저장한다' \
  --tests '*PostgresOrderQueryFeedStoreTest' \
  --tests '*PostgresOrderQueryStoreTest' \
  --tests '*OrderQueryHttpTest' \
  --tests '*OrderQueryUseCaseTest' \
  --tests '*OrderQueryConfigurationTest' \
  :app-api:ktlintCheck :architecture-tests:test :architecture-tests:verifyArchitectureReport --continue --console=plain
```

## 구현 기록

- `feat/order-fill-query/61`에 기존 주문 조회 컨트롤러의 `/fills`와 V13 주문 후보 인덱스를 추가했다. 기존 WAS의 DB 연결에서 읽기 전용 `REPEATABLE READ`로 주문·매칭 원문·정산 상태를 같이 본다. V11 원문을 저장 당시 형식으로 읽으므로 과거 주문을 다시 실행하거나 정산을 다시 보낼 필요가 없다. [HTTP 읽기 경로](../app-api/src/main/kotlin/com/exchange/core/api/trading/api/OrderQueryController.kt#L35) · [DB 읽기 경로](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/persistence/PostgresOrderFillReader.kt#L17)
- 체결 가격과 양수 수량은 기존 매칭 원문 해석기의 검증을 거친다. 조회한 주문과 원문의 주문 ID·소유자·방향을 대조해 역할을 정하고, 첫 페이지에서 전체를 반환할 때는 체결 합계를 주문의 체결 수량과도 확인한다. 정산 완료는 V12의 저장 순번과 행이 모두 일치할 때만 판정한다. 손상 검사는 체결·정산 식별 값의 일치까지 다루며 전체 원장 대사는 #58 범위다. [매칭 원문 해석기의 검증](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/codec/SplitResultDeliveryCodec.kt#L518) · [기존 정산 원본 사건 ID](../domain-trading/src/main/kotlin/com/exchange/core/trading/SettlementLedgerTransactions.kt#L42)
- 신규 HTTP 시험을 먼저 실행해 구현 전 `/fills`가 404를 반환하고 알려진 미체결 주문의 기대값인 200을 충족하지 않는 것을 확인했다. 실제 HTTP·PostgreSQL 시험의 다음 단계에서는 #44의 결과 2·3을 V12 저장 트랜잭션을 열기 직전에 각각 멈춰, `APPLIED`인 주문과 `PENDING`인 체결을 동시에 관찰했다. 저장을 재개한 뒤 양쪽 주문이 같은 ID와 반대 역할을 유지하며 `COMPLETED`가 되는지 읽었다. [실제 HTTP 시험](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitFundsTradingHttpTest.kt#L570)
- 독립 리뷰에서 빈 페이지 순번이 생략으로 처리되는 반례와 기존 정산 원본 사건 ID의 대조 누락을 확인했다. HTTP 입력 원문에서 순번을 검증하고 DB의 `source_event_id`를 매칭 원문과 대조했다. 회귀에는 빈 값·빠진 순번, 다른 사건 ID, 정산 순번과 행이 서로 앞서는 경우를 포함했다. [HTTP 입력 시험](../app-api/src/test/kotlin/com/exchange/core/api/trading/api/OrderQueryHttpTest.kt#L210) · [실제 DB 손상 시험](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/persistence/PostgresOrderQueryFeedStoreTest.kt#L160)

## 검증 결과

- 실제 HTTP·PostgreSQL 수용 사례와 기존 #44·#55 회귀를 포함해 `app-api` 62건이 통과했다. 실패·건너뜀은 0건이다. 실행 결과는 `app-api/build/reports/tests/test/index.html`에 생성된다.
- 아키텍처 테스트 386건과 필수 운영 검사 P01–P09가 통과했다. 실패·건너뜀은 0건이다. `:app-api:ktlintCheck`, `:architecture-tests:ktlintCheck`, `git diff --check`도 통과했다. 실행 결과는 `architecture-tests/build/reports/tests/test/index.html`에 생성된다.
- 작성 대화를 공유하지 않은 리뷰어가 통합 기준 `20d8a41`의 고정 변경을 검토했다. 수정본의 검토 대상 목록 SHA-256은 `1c117bda8c9f26aee903e0f930498629d25db47bc88137856662b3d00c271f85`다. 리뷰어가 그 사본에서 HTTP 입력과 PostgreSQL 손상 회귀를 각각 실행해 2건 모두 통과했다. 검토 범위에 남은 지적은 없다. 이 문서는 검증 결과와 GitHub에서 열 수 있는 코드 링크를 리뷰 뒤에 반영했다.

- 과거 체결은 V13 적용 전 DB에 저장하고 적용 뒤 저장소를 다시 열어 확인했다. 실제 노드 재기동·리더 교체와 운영 규모의 SQL 실행 계획·인덱스 생성 시간은 이번 검증 범위 밖이다.
