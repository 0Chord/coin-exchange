# #209 마켓별 정산 차트 조회

[이슈 #209](https://github.com/0Chord/coin-exchange/issues/209) · [기준 통합 커밋](https://github.com/0Chord/coin-exchange/commit/589265d0bf572c1f0232ce1b7e558cebf3a781b9)

## 동작과 리뷰 순서

기존 최신 체결가 조회에 이어, 정산 체결을 분 단위로 모아 마켓별 시가·고가·저가·종가·거래량을 제공한다. 한 분에 `100 × 1`, `105 × 2`가 들어오면 `100 / 105 / 100 / 105`, 거래량 `3`이다. 다음 분의 `90 × 4`는 별도 1분봉이고 두 분이 같은 상위 구간에 속하면 그 봉의 거래량은 `7`이다. [두 분과 여덟 주기 HTTP 검증](../app-api/src/test/kotlin/com/exchange/core/api/trading/api/OrderQueryHttpTest.kt#L520)

1. **정산 커밋 → 봉 반영.** 기존 WAS의 백그라운드 작업이 PostgreSQL에 저장된 정산 범위를 확인하고 반복이 끝나면 100ms 뒤에 다시 확인한다. 마켓별 저장 위치 뒤부터 최대 512개 체결의 결과 순번을 찾는다. 512번째 체결이 속한 결과까지 온전히 포함한다. 이번에 읽은 체결을 1분 단위로 합친 뒤 상위 일곱 주기에 같은 변경분을 보탠다. 체결이 빠짐없이 반영된 결과까지만 순번을 넘기므로, 다음 배치도 같은 위치에서 정확하게 이어간다. [WAS 집계 작업](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/persistence/CandleProjectionWorker.kt#L23) · [배치 처리](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/persistence/PostgresCandleProjection.kt#L60) · [배치 경계 시험](../app-api/src/test/kotlin/com/exchange/core/api/trading/api/OrderQueryHttpTest.kt#L460)
2. **봉·순번 커밋 → 재시도.** Flyway V18이 기존 `order_query`에 봉과 마켓별 처리 위치를 저장한다. 두 값은 한 트랜잭션에서 커밋된다. 여러 작업자는 해당 차트 진행 행을 잠그고 차례대로 처리한다. 커밋이 실패하면 둘 다 롤백된다. 커밋 후 응답만 유실되거나 WAS가 재시작하면 저장된 위치에서 이어간다. [V18 스키마](../app-api/src/main/resources/db/migration/V18__create_order_query_candles.sql) · [커밋 응답 유실·재시작·동시 처리 시험](../app-api/src/test/kotlin/com/exchange/core/api/trading/api/OrderQueryHttpTest.kt#L547)
3. **GET → 집계 결과.** 기존 WAS의 읽기 전용 트랜잭션에서 원본 준비 상태, 저장된 두 순번과 요청 범위의 봉을 함께 읽는다. 봉의 기준 시각은 UTC로 묶은 정산 적용 시각이다. 주는 월요일에 시작하고, 월·연은 달력 경계다. 동일 시각에는 엔진 순번으로 시가·종가를 고른다. 거래량은 정확하게 합산한 십진 문자열이다. [HTTP 진입점](../app-api/src/main/kotlin/com/exchange/core/api/trading/api/OrderQueryController.kt#L103) · [UTC 경계 시험](../app-api/src/test/kotlin/com/exchange/core/api/trading/api/OrderQueryHttpTest.kt#L649) · [실제 주문·정산 커밋 시험](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitFundsTradingHttpTest.kt#L1215)

`GET /api/trading/v3/markets/{marketId}/candles?interval=1m&count=60`에서 `1m`, `5m`, `15m`, `1h`, `1d`, `1w`, `1mo`, `1y`를 지원한다. 최근 시간 구간 개수는 1~200개다. 체결이 없던 구간은 생략하고, 현재 구간에 반영된 체결이 있으면 그 봉도 반환한다.

| 상황 | HTTP 결과 |
| --- | --- |
| 원본 확인과 최초 집계 완료 | `200 CANDLES_FOUND`. 봉과 `asOfSettlementResultSequence`, `sourceSettlementResultSequence`를 제공한다. 빈 기간은 `candles: []`다. |
| 최초 과거 집계 중 | `202 QUERY_CATCHING_UP`. 확인한 순번으로 진행 상태를 알려준다. |
| 잘못된 마켓·주기·개수 | `400`. 기존 WAS 입력 오류 형식을 사용한다. |
| 원본 준비 중·정산 수집 미지원·범위 불일치·DB 실패 | 기존 조회 오류의 `503`. |

변경은 WAS의 조회와 PostgreSQL 집계에 한정한다. 초기 복원 목표를 최초 확인한 정산 순번으로 고정한다. 그 목표에 도달한 뒤에는 최신 정산과 시차가 있더라도 마지막으로 커밋한 봉을 제공하고, 응답에 포함된 두 순번으로 시차를 알 수 있다.

## 실행 검증

기준 `589265d0bf572c1f0232ce1b7e558cebf3a781b9`의 제품·테스트 변경 스냅샷 SHA-256 `77ca195d18dcf8dcce4bcb1ec5b8b3e08efb92e9f90f56b8a303522f6326835e`에서 실행했다. 관련 앱 회귀 **68건**, 아키텍처 **388건**이 실패·건너뜀 없이 통과했다. CI 테스트 분할, 아키텍처 보고서 검증, Kotlin 서식 검사도 통과했다. 독립 리뷰에서 확인한 전체 원본 마켓 검사 누락은 기존 주문 조회 규칙을 재사용해 수정한 뒤 재검토했다.

저장소 체크아웃의 루트에서 JDK 25로 실행한다.

```sh
./gradlew --no-daemon --max-workers=2 :app-api:test \
  --tests '*api.OrderQueryHttpTest' --tests '*OrderQueryUseCaseTest' \
  --tests '*OrderQueryConfigurationTest' --tests '*PostgresOrderQueryFeedStoreTest' \
  --tests '*SplitFundsTradingHttpTest.주문별 체결*' \
  :app-api:verifyCiTestPartition :app-api:ktlintCheck --continue --console=plain
./gradlew --no-daemon --max-workers=2 :architecture-tests:test \
  :architecture-tests:verifyArchitectureReport :app-api:verifyCiTestPartition --console=plain
```

## 정산 체결 1,000건/s와 차트 GET 50건/s 비교

Apple M1 Pro 8코어·16GiB Mac에서 Docker PostgreSQL 16.15의 `fsync=on`, `synchronous_commit=on`으로 측정했다. 회차마다 같은 단일 마켓의 과거 체결 10만 건을 준비하고 초기 집계를 끝낸 뒤 시작했다. 회차당 10초 예열 후 30초를 기록했다. 기존 정산 저장 코드에 초당 결과 50개, 결과당 체결 20개, 체결당 분개 4개를 공급했다. A는 정산 쓰기, B는 같은 정산 쓰기와 실제 WAS 차트 집계·HTTP를 실행했다. GET은 여덟 주기를 순환하며 60개 시간 구간을 요청했다.

| 회차 | 커밋 체결/s | 정산 DB 쓰기 p95 / p99 (ms) | GET/s | GET p95 / p99 (ms) | 커밋 → 차트 관측 p95 / p99 (ms) |
| --- | ---: | ---: | ---: | ---: | ---: |
| A1 | 1,000 | 16.818 / 45.411 | — | — | — |
| B1 | 1,000 | 12.775 / 34.612 | 50 | 10.614 / 45.508 | 120.816 / 149.099 |
| A2 | 1,000 | 16.970 / 39.318 | — | — | — |
| B2 | 1,000 | 13.077 / 19.491 | 50 | 8.443 / 24.852 | 118.935 / 132.383 |

- 모든 회차가 측정 구간에서 체결 30,000건을 커밋했다. B1·B2는 GET 1,500건씩 성공했고, 두 회차 모두 여덟 주기의 최종 거래량이 각각 140,000이었다. 이는 과거 100,000과 예열·측정 체결 40,000의 합이다.
- 원본 순번과 차트 순번 차이의 첫·마지막 5초 평균은 B1 `2.936 → 2.824`, B2 `2.744 → 2.756` 결과였다. 최대 차이는 각각 12·13개 결과였고, 부하 종료 후 마지막 반영 관측은 각각 61.110·60.585ms에 확인했다.
- GET p95 ≤ 50ms, 차트 관측 p95 ≤ 2초, 기존 쓰기 p95 증가 ≤ 20% 기준을 두 회차 모두 충족했다. 차트 지연은 정산 저장 반환부터 첫 성공 HTTP에서 순번을 관측할 때까지이며 GET 관측 간격도 포함한다. 측정한 범위는 단일 마켓의 정산 DB 저장과 차트 HTTP 부하다.
- PostgreSQL `EXPLAIN (ANALYZE, BUFFERS)`에서 원본 탐색은 `settlement_transaction_result_idx`로 512행, 반영 범위는 같은 인덱스로 520행을 읽었다. 차트 GET은 준비 상태와 저장된 봉을 조회했다. `candle_pkey`로 요청한 마켓·주기·시간의 56개 봉을 찾았고 실행 계획의 서버 실행 시간은 0.181ms였다.
