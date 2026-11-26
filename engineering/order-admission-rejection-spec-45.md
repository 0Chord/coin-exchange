# #45 주문 접수 거절 · 상세 명세

[GitHub 이슈 #45](https://github.com/0Chord/coin-exchange/issues/45) · 기준 [`feature/phase-3/integration` `a27b22e`](https://github.com/0Chord/coin-exchange/tree/a27b22eae393e0ed5f4ae8f8b6993b52b99e6ecd) · 선행 [#43 완료](https://github.com/0Chord/coin-exchange/issues/43)

## 주문 한 건이 거절되는 흐름

1. **WAS가 HTTP 입력을 검사한다.** JSON을 읽을 수 없거나 필수 값이 잘못되면 WAS가 `400 INVALID_INPUT`을 반환한다. 자금 엔진으로 주문을 보내지 않으므로 사용자는 값을 고쳐 같은 요청 토큰으로 보낼 수 있다.
2. **자금 엔진이 요청 토큰과 마켓 설정을 확인한다.** 처음 보는 토큰이어도 주문 대상이 등록된 마켓·설정과 맞지 않으면 `409 EXECUTION_CONTEXT_MISMATCH`가 돌아온다. 이 요청의 최초 접수 결과는 만들어지지 않는다.
3. **자금 엔진이 주문 조건과 예약에 쓸 잔고를 확인한다.** BTC-USDT 매수는 USDT, 매도는 BTC를 확인한다. 지원하지 않는 주문 조건이거나 사용 가능액이 부족하면 `422 REJECTED`를 반환하고 이 토큰의 최초 거절 결과를 기록한다. 잔고와 예약은 그대로이고 매칭 엔진에 전달할 주문도 생기지 않는다.
4. **접수가 승인되면 자금 엔진이 예약하고 주문을 넘길 준비를 한다.** WAS가 접수 결과를 받으면 주문 ID와 `202 ACCEPTED`를 반환한다. 이미 주문이 전달됐을 수 있는데 끝내 응답을 못 받으면 `504 OUTCOME_UNKNOWN`이다. 그 경우 자금 예약이 이미 확정됐을 수 있다. 주문을 접수한 뒤 정산이 지연되면 최초 접수 결과는 유지되고, 주문 조회에서 정산 대기 상태를 확인할 수 있다.

## 한 PR에서 확인할 결과

현재 v3 HTTP에서 어떤 거절이 돌아오고 어떤 상태가 남는지 테스트로 고정한다. 기존 WAS·자금 엔진·매칭 엔진을 사용하고 HTTP 응답과 거래 상태를 함께 확인한다. 확인한 코드에는 표의 응답이 이미 구현되어 있어 이번 PR의 변경 대상으로 HTTP 테스트와 검증 기록을 잡는다.

| 입력 또는 상황 | HTTP · 응답 상태 / 사유 | 요청 토큰과 상태 |
| --- | --- | --- |
| 깨진 JSON, 모르는 enum 문자열, 빈 `userId`, 가격 0, 수량 0 | `400 INVALID_INPUT / INVALID_REQUEST` | Aeron 주문 송신 0건. 유효한 토큰은 수정 후 사용 가능 |
| 형식이 맞는 미등록 마켓 `ETH-USDT` · 테스트에는 `BTC-USDT`만 등록 | `409 EXECUTION_CONTEXT_MISMATCH / EXECUTION_CONTEXT_MISMATCH` | 주문 ID·접수 순번 없음. 마켓을 고쳐 같은 토큰 사용 가능 |
| 유효한 enum을 사용한 `LIMIT + IOC` 또는 `MARKET + GTC` | `422 REJECTED / UNSUPPORTED_ORDER_CONDITION` | 최초 거절 기록. 승인·예약·원장·매칭 변화 없음 |
| BUY의 USDT 또는 SELL의 BTC 사용 가능액이 부족함 | `422 REJECTED / INSUFFICIENT_BALANCE` | 최초 거절 기록. 승인·예약·원장·매칭 변화 없음 |
| 예약할 자산의 잔고 행이 없거나 사용 가능액이 0 | `422 REJECTED / INSUFFICIENT_BALANCE` | 잔고 행이 없으면 그대로 없고, 있던 0원 행은 그대로 유지 |
| 기록된 거절을 같은 토큰·내용으로 다시 보냄 | 최초와 같은 `422`·거절 사유 | 같은 결과를 반환. 같은 토큰에 다른 주문 내용을 보내면 `409 REQUEST_CONTENT_CONFLICT` |
| 전송 전에 큐에서 시간이 다 됨 | `503 NOT_SUBMITTED / QUEUE_TIMEOUT` | 기존 시험에서 미접수와 같은 토큰의 후속 성공 확인 |
| 전송 이후 전달·접수 여부를 WAS가 확인하지 못함 | `504 OUTCOME_UNKNOWN / RESULT_UNCONFIRMED` | 토큰 유지. 기존 시험에서 예약이 이미 확정된 경우와 같은 토큰으로 주문을 이어받는 동작 확인 |

`422`로 확정된 거절은 자금 클러스터의 요청 기록에 남는다. 따라서 거절 검증은 **승인된 주문과 금전 효과가 증가하지 않았는지**를 확인한다. 전체 스냅샷이나 Aeron 로그 위치를 거절 전과 같다고 비교하면 정상 기록까지 실패로 잡게 된다. 잔고 조회의 기존 계약은 미존재 `404`와 존재하는 0원 `200`을 구분한다.

## 수용 사례

대상은 `POST /api/trading/v3/markets/{marketId}/orders`다. 통합 시험은 기존 자금 1노드·매칭 1노드·Spring Boot WAS·Testcontainers PostgreSQL을 재사용한다. 시작 상태는 수수료 0, 구매자 USDT 1,000, 판매자 BTC 3, 빈 BTC-USDT 주문장이다. 각 사용자의 채널 세대를 발급하고 요청은 현재 마켓 설정과 가용 용량에서 보낸다.

1. **HTTP 입력 표를 검증한다.** 실제 WAS에서 표의 다섯 입력을 보내 정확한 400 응답을 확인한다. 정상 토큰을 넣을 수 있는 경우 구매자 순번 1을 사용한다. 그 순번은 다음 단계의 부족 주문까지 소비되지 않아야 한다. 기존 컨트롤러 시험의 가격 0 사례에는 전송 관찰 도구를 사용해 Aeron 주문 송신이 0건임을 직접 확인하는 검증을 붙인다.
2. **예약 전 거절을 실제 노드에서 확인한다.** 구매자 순번 1로 미등록 마켓을 요청하면 409다. 같은 토큰으로 BTC-USDT의 11개를 개당 100에 매수하면, 필요액 1,100이 보유액 1,000을 넘어 422다. 같은 내용을 다시 보내면 같은 최초 거절이고, 수량을 3개로 바꿔 같은 토큰을 보내면 409다. 구매자 순번 2로 BTC 1개를 매도하면 BTC 행이 없어 422다. 판매자는 순번 1로 BTC 4개를 매도해 422를 받는다. 판매자 순번 2와 3은 BTC 1개로 미지원 조건 두 가지를 요청해 422를 받는다. 이 구간이 끝나면 승인 순번 0, 원장 순번 2(초기 지급 두 건), 구매자 USDT 1,000/0, 판매자 BTC 3/0, 빈 주문장과 매칭 결과 순번 0을 확인한다. 구매자 BTC 행은 여전히 없다.
3. **같은 마켓의 정상 주문과 이미 존재하는 0원 잔고를 확인한다.** 구매자 순번 3으로 개당 100에 3개를 매수하면 202와 최초 주문 ID가 나온다. 주문은 대기하며 매수 호가 100에 잔량 3, USDT 700/300, BTC 0/0을 확인한다. 이때 승인·매칭 결과 순번은 1이고 원장 순번은 3이다. 이어서 구매자 순번 4로 BTC 1개를 매도하면 같은 422이며, 앞선 주문 잔량·잔고·원장 순번이 그대로다. 입력이 유효한 새 순번 5로 같은 가격에 1개를 더 매수하면 202이고 매수 호가 잔량은 4, USDT는 600/400, 승인·매칭 결과 순번은 2, 원장 순번은 4다. 정상 예약 두 건 외 추가 예약·정산·수수료가 없어야 한다.

거절 처리 후에는 응답만 보는 데서 끝내지 않는다. 기존 자금 상태 관찰로 해당 요청의 거절 기록이 보인 뒤 잔고·활성 예약과 주문 전달 순번을 대조한다. Archive 조회가 정해진 진척에 도달한 뒤 PostgreSQL의 원장 순번과 공개 호가의 매칭 결과 순번·호가 잔량도 본다. 정상 주문은 주문 GET에서 대기 상태와 잔량까지 확인한다. 대기가 필요한 관측값은 통합 테스트의 기존 `await`를 사용한다.

## 재사용할 실패 근거

| 경계 | 이미 있는 시험·관측값 |
| --- | --- |
| 가격 0의 HTTP 입력 검증 | 현행 v3 컨트롤러 시험에 HTTP 400이 있다. 실제 노드 시험에는 입력 표를, 컨트롤러 시험에는 송신 0건 검증을 추가한다. [BoundedTradingOrderHttpTest](https://github.com/0Chord/coin-exchange/blob/a27b22eae393e0ed5f4ae8f8b6993b52b99e6ecd/app-api/src/test/kotlin/com/exchange/core/api/trading/api/BoundedTradingOrderHttpTest.kt#L77) |
| 예약 후 응답 유실 / 전송 전 대기 초과 | 기존 실제 HTTP 시험은 예약 후 504, 새 WAS에서 같은 토큰으로 한 번만 접수·정산, 기존 BUY 부족 재전송의 422를 확인한다. 전송 전 대기 초과 시험은 503 뒤 같은 토큰으로 정상 접수를 확인한다. [응답 유실](https://github.com/0Chord/coin-exchange/blob/a27b22eae393e0ed5f4ae8f8b6993b52b99e6ecd/app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitFundsTradingHttpTest.kt#L1901) · [전송 전 대기 초과](https://github.com/0Chord/coin-exchange/blob/a27b22eae393e0ed5f4ae8f8b6993b52b99e6ecd/app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitFundsTradingHttpTest.kt#L2029) |
| 접수 후 정산 지연 / 정산 내부 쓰기 실패 | 실제 HTTP 시험에는 접수 뒤 정산 대기가 보이는 경우가 있다. 정산 쓰기 실패는 별도 도메인 시험이 자금 상태의 부분 반영을 숨기지 않고 후속 처리·정산 ACK를 차단하는지 확인한다. HTTP를 거친 실제 쓰기 실패 주입은 이번 수용 범위 밖이다. [정산 대기](https://github.com/0Chord/coin-exchange/blob/a27b22eae393e0ed5f4ae8f8b6993b52b99e6ecd/app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitFundsTradingHttpTest.kt#L224) · [정산 쓰기 실패](https://github.com/0Chord/coin-exchange/blob/a27b22eae393e0ed5f4ae8f8b6993b52b99e6ecd/domain-trading/src/test/kotlin/com/exchange/core/trading/FundsResultSettlementTest.kt#L589) |

## 구현 순서와 완료 기준

1. `BoundedTradingOrderHttpTest`에서 기존 가격 0 사례의 Aeron 주문 송신 0건을 확인한다.
2. `SplitFundsTradingHttpTest`에 입력 표부터 정상 접수까지 실제 노드 사례 하나를 추가한다. 초기 자금 설정, Archive 기반 잔고·호가 확인과 DB를 재사용한다. 기존 공개 호가 옵션은 해당 시험에서 켠다.
3. 구현이 들어간 체크아웃에서 `:app-api:test -PciTestGroup=general`의 테스트 필터로 두 클래스를 실행한다. 정산 실패 근거를 재확인할 때는 `:domain-trading:test`에서 `FundsResultSettlementTest`를 실행한다. 실패·재시도 시험은 기존 것을 재사용하며, 별도 수동 서버 기동은 필요 없다. 실행 결과와 근거를 PR에 남긴다.
4. 표의 응답, 거절 후 상태, 정상 주문 두 건의 예약·주문장 반영이 모두 일치하면 완료다. 예기치 않은 응답 차이가 나오면 코드 매핑과 요구를 먼저 대조한다.

가격·수량 증분(#46), 정상 종료(#47), 재시도 수명 정책(#64·#65), 서버 재시작 후 접수 게이트(#59), 인증, 새 주문 유형은 각각 기존 이슈에서 다룬다.

## 구현과 실행 결과

- [기존 컨트롤러 시험](../app-api/src/test/kotlin/com/exchange/core/api/trading/api/BoundedTradingOrderHttpTest.kt#L77)은 가격 0 요청의 응답 본문과 전송 건수 불변을 확인한다.
- [실제 HTTP 통합 시험](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitFundsTradingHttpTest.kt#L135)은 400·409·422 뒤 자금 엔진의 거절 기록과 잔고, Archive로 읽은 주문장, PostgreSQL 원장을 대조한다. 독립 리뷰에서 확인한 누락을 보강해 0원 매도 거절 전후의 활성 예약과 주문 GET, 최종 두 주문의 예약 300·100과 수수료 0을 확인한다.
- 리뷰 보강 전 전체 회귀에서 `BoundedTradingOrderHttpTest` 4건과 `SplitFundsTradingHttpTest` 32건이 통과했다. 32건에 기존 전송 전 503·응답 유실 504와 신규 거절 시험이 포함된다.
- 최종 변경에서 컨트롤러 4건과 변경된 통합 시험 1건을 다시 실행했다. 정산 도메인 회귀 `FundsResultSettlementTest` 17건, `:app-api:ktlintCheck`와 `:app-api:verifyCiTestPartition`까지 통과했다. 테스트에서 WAS·Aeron·PostgreSQL을 기동했다.
- 최종 코드 변경의 SHA-256 `f22f4fd8e6041b88c133cf5bd84b5aa21c20db8a21cceb9ce2e67ac76477bef5`를 작성 대화를 공유하지 않은 리뷰어가 재검토했다. 위 예약·주문 조회·수수료 검증 보강을 확인했고 추가 지적은 없었다.

구현 브랜치의 저장소 루트에서 실행한다.

```sh
./gradlew -p "$(git rev-parse --show-toplevel)" :app-api:ktlintCheck :app-api:test -PciTestGroup=general \
  --tests '*BoundedTradingOrderHttpTest' --tests '*SplitFundsTradingHttpTest.실제 HTTP 주문 거절*' \
  :domain-trading:test --tests '*FundsResultSettlementTest' --console=plain
```

## 현재 코드 근거

- WAS 입력 오류 처리와 최종 HTTP 매핑. [HTTP 예외 처리기](https://github.com/0Chord/coin-exchange/blob/a27b22eae393e0ed5f4ae8f8b6993b52b99e6ecd/app-api/src/main/kotlin/com/exchange/core/api/common/TradingOrderHttpExceptionHandler.kt#L12) · [현행 주문 컨트롤러](https://github.com/0Chord/coin-exchange/blob/a27b22eae393e0ed5f4ae8f8b6993b52b99e6ecd/app-api/src/main/kotlin/com/exchange/core/api/trading/api/BoundedTradingOrderController.kt#L110)
- 자금 엔진이 토큰·마켓을 확인하고 잔고 없음과 0원을 같은 부족으로 판단한다. [자금 접수 상태](https://github.com/0Chord/coin-exchange/blob/a27b22eae393e0ed5f4ae8f8b6993b52b99e6ecd/domain-trading/src/main/kotlin/com/exchange/core/trading/BoundedFundingState.kt#L140) · [주문 조건과 예약 계산](https://github.com/0Chord/coin-exchange/blob/a27b22eae393e0ed5f4ae8f8b6993b52b99e6ecd/domain-trading/src/main/kotlin/com/exchange/core/trading/OrderAdmissionCalculator.kt#L115)
