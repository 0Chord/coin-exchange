# #55 · 체결별 정산 기록을 PostgreSQL에 한 번 저장하기

자금 엔진이 체결 한 건을 정산하면, 그때 실제로 움직인 대금·예약·수수료를 같은 체결 ID로 나중에도 확인할 수 있어야 한다. 자금 엔진이 확정한 기록을 기존 WAS가 기존 PostgreSQL에 저장한다. PostgreSQL 커밋을 확인한 뒤 자금 엔진이 전달 확인을 기록하고 전송용 원본을 정리한다. 같은 매칭 결과나 같은 저장 요청이 다시 와도 최초 자금 상태와 최초 저장 내역이 그대로 남아야 한다. [#55](https://github.com/0Chord/coin-exchange/issues/55) · [통합 기준 `0b512a0`](https://github.com/0Chord/coin-exchange/commit/0b512a0a3942b2f14e186421590b9dd39587044f)

## 한 체결이 기록으로 남는 흐름

```text
매칭 엔진 ── 확정 체결 ──▶ 자금 엔진
                          잔고·예약·수수료 반영, 당시 정산 내역 함께 보존
                          ├─ 기존 정산 ACK ──▶ 매칭 엔진
                          └─ 확정 정산 내역 ──▶ 기존 WAS 수집 작업
                                                └─ 기존 PostgreSQL 커밋
                          ◀────── 커밋 확인 후 전달 ACK ──────┘
                          자금 클러스터에 ACK 확정 후 전송용 원본 정리
```

1. **자금 엔진이 최초 정산을 확정한다.** 기존처럼 같은 마켓의 다음 결과인지, 이전에 적용한 원문과 같은지 판정한다. 최초 유효 결과라면 잔고·예약·수수료와 최종 적용 순번을 바꾸는 같은 복제 상태 변경에 실제 정산 내역과 전달 의무를 남긴다. 보존할 값은 그때 계산한 분개와 양쪽 주문별 수수료다. 매칭 엔진의 업무용 정산 ACK는 기존 시점에 진행한다.
2. **같은 WAS가 확정 기록을 읽어 저장한다.** 해당 마켓의 DB 저장 위치 다음부터 읽고, 단위별 모든 체결의 분개와 저장 위치를 하나의 PostgreSQL 트랜잭션으로 반영한다. 저장이 늦는 동안 기록은 자금 엔진에 남고 주문 조회의 기존 `APPLIED` 의미는 자금 엔진 적용 완료다. PostgreSQL에 저장됐다는 사실은 이번 기록의 저장 위치에서 별도로 확인한다.
3. **커밋을 확인한 작업만 정리한다.** WAS는 DB에서 확인한 연속 저장 위치까지만 자금에 ACK를 보낸다. 자금 클러스터의 ACK 적용이 확정되면 그 구간의 전송용 원본을 정리한다. PostgreSQL의 정산 내역은 조회·대사에 계속 쓴다. 자금 정산 상태와 PostgreSQL 트랜잭션은 각자 확정된다.

**현재 구현 근거.** 자금 엔진은 정산 시 분개를 계산하고 자금 상태·제한된 결과 영수증을 보존한다. 현재 영수증은 매칭 원문과 최초 적용 시각을 담으며 자체 한도에서 회수된다. 기존 조회 수집은 매칭 원문과 자금 적용 위치를 PostgreSQL에 저장하고, 커밋을 확인한 뒤 조회 원본을 ACK한다. #55는 이 흐름에 자금 엔진이 확정한 체결별 분개와 수수료 전달을 추가하는 명세다. [분개 계산](../domain-trading/src/main/kotlin/com/exchange/core/trading/OrderSettlementCalculator.kt#L110) · [자금 적용](../domain-trading/src/main/kotlin/com/exchange/core/trading/BoundedFundingState.kt#L539) · [현재 정산 영수증](../domain-trading/src/main/kotlin/com/exchange/core/trading/FundsResultSettlement.kt#L57) · [기존 저장 확인](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderQueryFeedWorker.kt#L273)

## 무엇을 한 건으로 저장하고 어떤 응답을 재사용하나

- **한 체결당 `SETTLEMENT` 한 건.** 기존 자금 계보·마켓 고정 번호·체결 엔진 순번으로 만드는 정산 ID를 재사용한다. 두 주문 ID, 매칭 결과가 속한 마켓·계보·결과 순번, 최초 정산 확정 시각, 가격·수량·자산, 양쪽의 역할과 실제 수수료, 순서 있는 분개 항목을 보존한다. 수수료 0도 값으로 남긴다. 하나의 매칭 결과에 체결 여러 건이 들어갈 수 있어 **결과 순번은 전달 위치**, **정산 ID는 체결별 중복 판정 키**다. 무체결 결과는 전달 위치만 전진한다. [정산 ID](../domain-trading/src/main/kotlin/com/exchange/core/trading/TradingRequest.kt#L149) · [매칭 결과의 전체 이벤트](../domain-trading/src/main/kotlin/com/exchange/core/trading/MatchingIntakeValues.kt#L359)
- **자금 재전송과 DB 재시도는 서로 다른 경계다.** 자금은 같은 원문을 받으면 기존 순번·영수증 규칙에 따라 이미 적용된 결과를 응답하고 새 정산 내역을 만들지 않는다. PostgreSQL은 같은 정산 ID에 같은 확정 내용이면 첫 저장 결과를 유지한다. 내용이 다른데 키만 같으면 저장을 실패시키고 해당 위치의 ACK를 보류한다. 최초 정산 시각과 내용은 재시도 시 새로 계산하지 않는다. 원문 비교 창을 벗어난 자금 재전송의 기존 `PRUNED` 처리도 그대로 적용된다. [자금 중복 판정](../domain-trading/src/main/kotlin/com/exchange/core/trading/FundsResultSettlement.kt#L131)
- **정산 안에서 반환한 돈도 같은 기록에 넣는다.** 지정가보다 유리한 체결이나 미사용 수수료 예약의 반환은 해당 `SETTLEMENT`의 예약→가용 이동이다. 별도의 취소 반환 `RELEASE`를 생성하면 이중 기록이다. PostgreSQL에는 체결별 분개와 저장 위치를 같이 확정한다. 기존 V5의 거래·분개 테이블과 저장 매핑은 재사용 후보다. 기존 `MATCHING:<market>:<sequence>`에는 계보가 없어 공유 DB의 유일 키로 쓰면 충돌할 수 있다. 저장 키에는 계보를 포함한 기존 정산 ID를 사용하고, 같은 DB의 과거 원장 및 전체 잔고 대사와 구분할 수 있는 출처를 남긴다. [기존 분개 생성](../domain-trading/src/main/kotlin/com/exchange/core/trading/SettlementLedgerTransactions.kt#L18) · [V5 제약](../app-api/src/main/resources/db/migration/V5__create_ledger_tables.sql#L1)

| 중간에 끊기는 지점 | 남아야 하는 상태와 이어가는 방식 |
| --- | --- |
| **PostgreSQL 트랜잭션이 실패** | 그 결과의 정산 행·분개·저장 위치가 함께 롤백된다. 자금에서 이미 확정한 정산과 전송용 원본은 보존된다. WAS는 동일 기록을 저장부터 재시도한다. |
| **DB는 커밋했는데 WAS가 확인을 못 받음** | WAS가 DB 위치와 같은 정산 ID의 기록을 다시 확인한다. 최초 기록은 1건인 채로 유지하고 확인된 위치만 ACK한다. |
| **DB 저장 후 자금의 전달 ACK가 유실** | 자금에 ACK가 확정되기 전이면 원본을 계속 보관한다. 재연결하면 WAS가 커밋된 DB 위치로 같은 ACK를 보낸다. 이미 자금 로그에 ACK를 적용했다면 복원된 확인 위치에서 이어간다. |

### 기록의 수명과 보관 한도

**제안하는 보존 경계.** DB 확인 대기 내역과 마지막 전달 확인 위치를 자금의 스냅샷과 이후 로그에서 복원할 수 있게 한다. 지금의 중복 비교 영수증은 별도로 회수될 수 있다. 기존 거래와 결과 순번의 복구 의미는 유지한다. 이미 운영 중인 계보의 과거 분개를 자동 채웠다고 표시하지 않으며, 첫 통합 사례는 기존 초기화 구성의 새 테스트 계보에서 증명한다.

정산 내역 보관 공간은 **주문장을 바꾸기 전에** 확보한다. 활성화한 마켓의 기존 매칭 조회 원본 예산에 해당 결과가 낼 수 있는 분개의 최대 크기도 반영한다. 기존 기록이 쌓여 새 결과를 보관할 공간이 일시적으로 부족하면 `BUSY`로 기다린다. 공간을 비워도 한 결과가 들어갈 수 없으면 #166에서 합의한 `RESULT_TOO_LARGE` 최종 거절을 쓰고 예약을 반환하는 흐름을 이어간다. 이미 확정한 체결은 확보한 공간으로 계속 정산한다. 공간은 **기존 매칭 조회의 DB 저장과 자금 쪽 정산 전달 ACK 확정**이 모두 확인된 범위까지만 돌려준다. [#166](https://github.com/0Chord/coin-exchange/issues/166) · [변경 전 결과 한도 확인](../domain-trading/src/main/kotlin/com/exchange/core/trading/ReservedMatchingState.kt#L168)

## 기존 통합 시험에서 확인할 첫 사례

기존 `SplitFundsTradingHttpTest`가 실제 WAS·분리 자금/매칭 각 1노드·Testcontainers PostgreSQL을 준비하는 방식을 사용한다. 새 사례는 초기 매수자 1,000 USDT, 매도자 3 BTC, 메이커 1%, 테이커 2%다. HTTP로 매도 1개를 가격 100에 먼저 대기시키고, 매수 1개를 지정가 150에 보내 **100에 한 번 체결**시킨다. 결과가 저장된 뒤 사용자 주문 POST를 반복하는 대신 테스트의 자금 Aeron 연결로 **같은 매칭 결과 원문**을 다시 전달한다. [기존 시험 실행기](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitFundsTradingHttpTest.kt#L1368) · [기존 결과 재전송 사례](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerMatchingResultDeliveryTest.kt#L195)

| 확인 시점 | 매수자 USDT 가용 / 예약 | 매수자 BTC | 매도자 USDT / BTC 가용 | 누적 수수료 | PostgreSQL 정산 기록 |
| --- | ---: | ---: | ---: | ---: | --- |
| 매수 접수 확정, 결과 적용 전 | 847 / 153 | 0 | 0 / 2, BTC 예약 1 | 0 | 0건 |
| 자금 정산·DB 저장·전달 확인 후 | 898 / 0 | 1 | 99 / 2, BTC 예약 0 | 3 | 해당 ID의 `SETTLEMENT` 1건 |
| 같은 매칭 원문 재전송 후 | 898 / 0 | 1 | 99 / 2, BTC 예약 0 | 3 | 최초 ID·시각·내용 그대로 1건 |

수기 기대값은 **예약 153 = 150 + 최대 수수료 3**, **실제 사용 102 = 100 + 매수 수수료 2**, **정산에 포함한 반환 51 = 153 − 102**다. 매도자는 100 − 1인 99를 받고, **898 + 99 + 수수료 3 = 1,000**이다. DB의 매수 예약 차감 합 153, 가용 반환 51, 매도 수취 99, 양쪽 수수료 1·2와 BTC 1의 이동까지 대조한다. 이 체결에 별도 `RELEASE`는 0건이다. 파일에 쓴 기대값이나 계산기의 재호출로 통과시키지 않고 실제 DB 조회와 자금의 확정 상태를 각각 읽는다.

**첫 합격 기준.** 최초 POST의 주문 ID로 GET이 `FILLED / APPLIED`가 되고, 동일 원문 재전송은 `DUPLICATE`로 처리된다. 신규 체결이나 자금 적용 순번의 전진 없이 표의 잔고·예약·수수료가 유지돼야 한다. DB에서 분개와 해당 결과까지의 저장 위치를 다시 읽고, 자금의 전달 확인 위치 및 원본 회수까지 각각 대조한다.

**#55의 추가 수용 기준.** 기존 저장소 통합 시험의 실 DB 롤백, 실제 커밋 뒤 응답 유실 장치를 재사용해 위 실패 표를 대조한다. 동일 ID의 내용 충돌이 성공 ACK로 처리되지 않는지, 여러 체결을 가진 결과의 모든 분개가 함께 저장되는지, 무체결 결과는 위치만 전진하는지를 좁은 검사로 확인한다. 활성화 전에는 확인 대기 원본의 스냅샷 복원과 보관 한도에서 이미 확정한 체결의 정산이 이어지는지도 검증한다. [실제 커밋 뒤 응답 유실 시험](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitFundsTradingHttpTest.kt#L992) · [SQL 롤백 시험](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/persistence/PostgresOrderQueryFeedStoreTest.kt#L252)

## 착수 범위와 종료 판단

- **첫 구현 단위.** 실제 한 체결의 정산 확정에서 기존 PostgreSQL 저장·확인까지 연결하고, 같은 자금 결과를 재전송해 DB 한 건과 금융 효과 한 번을 증명한다. 스키마·매핑은 정산 원문과 커밋 위치라는 목적에 필요한 만큼만 추가한다. 검증은 기존 통합 시험에 추가할 단일 시나리오로 시작한다.
- **관련 후속.** #61은 체결별 GET, #62는 실제 수수료 조회, #58은 복구 시 원장·잔고 대사다. 이번 명세의 결과는 체결별 확정 원본과 중복 없는 저장 근거다. 전량 과거 원장 이행·공개 API·새 실행 서비스·성능 및 다중 노드 장애 시험은 후속 범위다. [#61](https://github.com/0Chord/coin-exchange/issues/61) · [#62](https://github.com/0Chord/coin-exchange/issues/62) · [#58](https://github.com/0Chord/coin-exchange/issues/58)
- **합의와 구현 선택.** 사용자는 기존 PostgreSQL까지의 저장·확인 흐름과 재전송 검증을 기존 통합 시험에서 다루도록 요청했다. 같은 WAS 수집 경로, 정산 ID 기반 저장, 매칭 원본 보관 한도 및 시험값을 사용했다.

## 구현과 확인 방법

기존 WAS 수집 작업이 자금에서 분개를 읽고, 기존 PostgreSQL의 `order_query.settlement_transaction`과 `order_query.settlement_posting`에 저장한다. 해당 마켓의 `feed_stream.settlement_through`도 같은 트랜잭션에서 전진한다. 거래당 최초 정산 시각과 분개 순서를 보존하며, 마지막까지 체결이 하나도 없는 결과는 위치만 전진한다. 기존 V5 원장과 키 충돌을 피하도록 자금 계보별로 저장한다.

새 자금·매칭 계보의 모든 노드에는 분리 입력 계약 2, `EXCHANGE_ORDER_QUERY_PERSIST_SETTLEMENTS=true`와 기존 조회 소비자 설정을 공급한다. WAS는 소스에 연결하면서 지원 여부를 확인한다. 이전 계보는 기존 설정과 스냅샷 형식을 사용한다. 과거 분개를 채우거나 기존 계보의 실행 설정을 이행하는 작업은 후속 범위다.

`SplitFundsTradingHttpTest`는 HTTP로 실제 주문을 보내고, JUnit이 준비한 PostgreSQL에서 한 건의 `SETTLEMENT`와 여덟 줄을 읽는다. 자금의 잔고·예약·수수료를 대조하고, 커밋 확인을 잃은 동안 원본이 남는지 검사한다. 자금 ACK 적용 뒤 응답을 잃으면 매칭 원본은 대기하고, 다시 연결해 양쪽 순번을 확인하면 회수한다. 같은 분개를 DB에 다시 저장하고 동일 매칭 원문도 자금에 다시 보내 최초 상태가 유지되는지 확인한다. WAS, Aeron과 PostgreSQL은 기존 테스트가 실행하고 종료한다.

```sh
cd /Users/0chord/Desktop/exchange-core
./gradlew --no-daemon --max-workers=2 :app-api:test \
  --tests '*SplitFundsTradingHttpTest.체결 정산 기록을 PostgreSQL에 한 번 저장한다'
```

`PostgresOrderQueryFeedStoreTest`는 무체결 결과의 위치 전진, 두 번째 체결 분개 저장 중 오류가 난 경우의 롤백, 동일 분개의 동시 재시도와 내용 충돌을 확인한다. `QueryRetentionCoreTest`와 `FundsRoleSnapshotCodecTest`는 확보한 보관 공간으로 확정 체결을 정산하고, 저장 대기 분개와 ACK 위치를 복원하는 경로를 확인한다.

## 검증 기록

- 실제 PostgreSQL을 포함한 관련 앱 시험 80건, 도메인 시험 456건, 아키텍처 시험 386건이 모두 통과했다. 실패와 건너뜀은 0건이다. 기존 V9 DB의 마켓 설정 보존과 V12 적용도 포함한다.
- 작업과 분리된 복사본 `571d8a35…`에서 독립 리뷰를 완료했다. 스냅샷 시험 한도와 입력 계약, V12 마이그레이션 기대값, 재전송 후 상태 관찰 순서를 보강했다. 이후 제품 코드 변경은 없었고, 마지막 변경은 HTTP 시험의 ACK 유실 변수명을 짧게 정리한 것이다.
- 마지막 변경 후 대표 HTTP 시험과 `app-api`, `domain-trading`의 ktlint를 다시 실행했고 통과했다.
