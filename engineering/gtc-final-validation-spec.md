# GTC 주문 단위와 자금 복구 통합 검증

2026-11-28 기준. `feature/phase-3/integration`의 `d5925f54`에 맞춘 명세다. 최신 통합에는 매칭 로그 확정 직후의 장애를 검증한 #221이 포함돼 있다. [통합 기준 커밋](https://github.com/0Chord/coin-exchange/commit/d5925f54cc9c1bd192452646625e9434ee666cdb) · [#221](https://github.com/0Chord/coin-exchange/pull/221)

잘못된 주문은 예약 전에 거절하고, 이미 확정된 체결·취소는 자금 서버가 꺼져도 남은 작업만 이어서 처리한다. 기존 주문 API와 엔진에 대해 다음 시험을 **한 PR**로 추가한다.

| 이슈 | 새로 확인할 동작 |
| --- | --- |
| [#46](https://github.com/0Chord/coin-exchange/issues/46) | 매수·매도의 가격 단위·수량 단위·대금 범위 위반을 실제 HTTP로 보내도 자금과 주문장에 변화가 없다. |
| [#68](https://github.com/0Chord/coin-exchange/issues/68) | 체결 A·B·C 중 어디까지 자금이 반영되었는지에 따라, 재기동 후 남은 체결만 정산한다. |
| [#69](https://github.com/0Chord/coin-exchange/issues/69) | 매수·매도 잔량을 취소할 때, 반환 전이나 반환 직후에 자금 JVM이 꺼져도 남은 예약은 정확히 한 번 반환된다. |
| [#70](https://github.com/0Chord/coin-exchange/issues/70) | #68의 연속 장애로 복구 중 재종료와 정상 재개를 확인한다. 손상 기록의 접수 차단은 #58·#59의 기존 실행 근거를 연결한다. |

사용자 합의 요약. 네 이슈를 한 PR에서 검증하고, 기존 통과 사례는 새 시험으로 반복하지 않는다. #81은 이 문서의 마지막 절에 확인 근거와 남은 전환 목록만 둔다.

## 전체 흐름

```text
HTTP 주문 → 자금이 단위와 대금을 검사
    위반 → 422. 예약과 매칭 전달 없이 종료                     #46
    정상 → 예약 확정 → 매칭이 체결 또는 잔량 취소를 확정
                        ↓
               자금이 정산하거나 남은 예약을 반환
                        ↓
          거래 경로는 매칭에 정산 확인을 보냄
          조회 경로는 Archive를 읽어 PostgreSQL에 반영

       자금 반영 전 종료 → 재기동 → 남은 결과를 이어서 반영      #68·#69
       자금 반영 후 확인 응답 전 종료 → 재기동 → 기존 결과 확인   #68·#69
       복구 중 다시 종료 → 재기동 → 이어서 반영 → 후속 주문 확인 #70
```

매칭의 **결과 확정**, 자금의 **정산·반환 반영**, 매칭의 **정산 확인 기록**, PostgreSQL의 **조회 반영**은 각각 확인한다. PostgreSQL 저장은 기존 조회 경로의 관찰 결과로 사용한다. 금융 반영 여부는 자금 Archive 위치와 실제 잔고·예약으로 판단한다. [자금의 결과 적용 및 확인 응답](https://github.com/0Chord/coin-exchange/blob/d5925f54cc9c1bd192452646625e9434ee666cdb/app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitTradingClusterAdapter.kt#L450-L516) · [기존 Archive 재기동 구성](https://github.com/0Chord/coin-exchange/blob/d5925f54cc9c1bd192452646625e9434ee666cdb/app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/GtcRestartCluster.kt#L187-L218)

## #46 주문 단위 위반은 예약 전에 거절한다

기존 v3 HTTP 통합 시험에 입력 표를 쓰는 사례 한 건을 추가한다. 테스트 마켓의 기준 자산 자릿수는 `2`, 가격 단위는 `25`, 수량 단위는 `4`, 수수료는 `0`으로 설정한다. 운영 마켓의 설정과 무관한 시험 값이다. 매수·매도 사용자에게 미리 자금을 지급하고, 각 입력은 고유한 요청 토큰으로 보낸다.

| 위반 | 가격 | 수량 | 입력을 고른 이유 |
| --- | ---: | ---: | --- |
| 가격 단위 | 101 | 100 | 수량 단위와 대금 계산은 유효하지만 가격이 25의 배수가 아니다. |
| 수량 단위 | 150 | 2 | 전체 대금은 3이지만, 수량 1의 부분 체결을 허용하면 대금이 1.5가 된다. 수량 2는 허용 단위 4에 맞지 않는다. |
| 대금 범위 | 9,223,372,036,854,775,800 | 200 | 가격과 수량은 유효한 배수지만 대금 18,446,744,073,709,551,600이 `Long` 최댓값을 넘는다. |

**통과 기준.** BUY·SELL 여섯 요청이 모두 `422 / REJECTED / UNREPRESENTABLE_RESERVATION`을 반환하며 주문 ID나 승인 순번을 부여하지 않는다. 잔고·활성 예약·승인과 매칭 전달 순번·주문장, PostgreSQL의 주문 행과 `RESERVE` 분개는 그대로다. 요청 중복 방지용 거절 응답은 기록될 수 있다. 마지막에 유효한 BUY `100 × 4`를 넣어 `202`와 예약 4를 확인한다. PostgreSQL이 후속 주문까지 반영했을 때 승인·매칭 결과·`RESERVE`가 그 한 건만 늘어야 한다.

부분 체결과 잔량의 정수 대금 계산은 #146, 일반적인 v3 거절과 무효과는 #219의 기존 근거를 사용한다. [#146](https://github.com/0Chord/coin-exchange/pull/146) · [#219](https://github.com/0Chord/coin-exchange/pull/219) · [실제 예약 계산의 단위 검사](https://github.com/0Chord/coin-exchange/blob/d5925f54cc9c1bd192452646625e9434ee666cdb/domain-trading/src/main/kotlin/com/exchange/core/trading/OrderAdmissionCalculator.kt#L107-L135) · [HTTP 응답 매핑](https://github.com/0Chord/coin-exchange/blob/d5925f54cc9c1bd192452646625e9434ee666cdb/app-api/src/main/kotlin/com/exchange/core/api/trading/api/BoundedTradingOrderController.kt#L90-L126)

## #68·#70 체결 셋 중 남은 정산만 이어간다

테스트가 시장 하나의 매칭 JVM, 자금 JVM, WAS를 띄운다. 매도자가 `100 × 3`을 먼저 호가에 올린다. 매수자는 1,000 USDT에서 `100 × 1` 매수 주문을 세 번 접수한다. 매칭이 확정한 세 체결을 A·B·C로 부른다. 기존 재기동 시험의 매수 수수료 2%, 매도 수수료 1%를 사용한다. 세 결과를 각각 다른 자금 명령으로 적용하도록 시험 설정을 고정하고, 실제 로그 위치를 대조한다. 같은 배치 명령 내부에서는 한 체결만 확정된 중간 상태를 가정할 수 없다. [기존 지급·수수료 설정](https://github.com/0Chord/coin-exchange/blob/d5925f54cc9c1bd192452646625e9434ee666cdb/app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/GtcRestartCluster.kt#L63-L90) · [개별 전송 설정](https://github.com/0Chord/coin-exchange/blob/d5925f54cc9c1bd192452646625e9434ee666cdb/app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentSplitTradingMain.kt#L166) · [#56에서 남긴 경계](https://github.com/0Chord/coin-exchange/issues/56)

| 확인 시점 | 테스트가 잡을 경계 | 매수 USDT 가용 / 예약 | 매수 BTC | 매도 USDT / BTC 예약 | 수수료 합계 / PostgreSQL 정산 |
| --- | --- | ---: | ---: | ---: | --- |
| 첫 정산 전 | 매칭 A·B·C가 모두 확정돼 있고 자금에는 세 결과가 아직 전달되지 않은 상태에서 자금 JVM 종료 | 694 / 306 | 0 | 0 / 3 | 0 / 없음 |
| A만 반영 | 같은 Archive로 자금을 재기동해 A만 반영하고 PostgreSQL에서 확인한 뒤 종료 | 694 / 204 | 1 | 99 / 2 | 3 / A |
| B 반영 후 | 다시 재기동해 B의 자금 반영과 PostgreSQL 커밋을 확인한다. B의 확인 응답은 매칭 전송 작업에 전달하지 않고 C도 보류한 상태에서 종료 | 694 / 102 | 2 | 198 / 1 | 6 / A·B |
| 마지막 복구 | 같은 기록으로 다시 켠 뒤 보류를 풀어 C까지 정산하고 매칭이 연속 순번을 확인 | 694 / 0 | 3 | 297 / 0 | 9 / A·B·C |

**#68 통과 기준.** 체결마다 매수 102 사용, 매도 99 지급, 수수료 3이 성립한다. 매수 주문 세 건과 매도 주문 한 건의 최종 잔량은 0이다. 양쪽 체결 조회에서 같은 A·B·C ID를 확인하고, PostgreSQL에서 각 ID의 `SETTLEMENT` 거래 한 건과 실제 분개 계정·방향·금액을 확인한다. A와 B의 원장 거래 ID·자금 로그 위치·분개는 다시 시작한 뒤에도 그대로고, C만 새로 반영된다. 응답이 끊긴 동안 임의 `RELEASE` 분개나 가용 잔고 증가가 없다. 매칭이 복구된 자금의 처리 순번으로 B를 확인하거나 같은 B의 응답을 다시 받아도 금융 효과는 동일하다. 같은 입력의 중단 없는 실행을 한 번 대조해 최종 주문 상태·체결·잔고·예약·수수료·원장이 위 독립 기대값과 일치함을 확인한다. 실행별 저장 위치와 시각은 실행 간 동등 비교에서 제외하고, 장애 실행 내부에서는 확정된 위치가 바뀌지 않아야 한다.

**#70 정상 복구 통과 기준.** A 반영 후 재기동하고 B 반영 뒤 다시 종료해도 C까지 실제 정산한다. WAS와 매칭 JVM의 PID는 유지한다. 기존 WAS에서 새 토큰으로 정상 주문을 보내 `202`, 실제 매칭 결과, GET의 기대 상태를 확인한다.

최초 장애 시험에서 자금 복구 뒤 기존 WAS가 계속 503을 반환했다. 이번 PR에서는 발견된 장애를 해결하기 위해 WAS 자동 재연결도 포함한다. Aeron 세션을 계속 쓸 수 있는 리더 전환에서는 기존 응답을 계속 읽는다. 세션을 잃으면 새 접수를 차단하고 기존 연결을 정리한 뒤 자금 역할과 lineage를 확인하는 기존 접속 절차를 다시 수행한다. 아직 보내지 않은 요청은 `503 NOT_SUBMITTED`, 먼저 보냈고 결과를 모르는 요청은 `504 RESULT_UNCONFIRMED` 응답으로 끝낸다. 같은 토큰으로 다시 보낼지는 클라이언트가 결정한다. 원격 내구성 확인 대기는 기존 규칙을 따르며, 정상 종료를 시작한 WAS는 교체 세션으로 주문을 열지 않는다.

**#70 차단 근거.** #58에서 가용·예약 배분 차이, 고아 예약, 손상·누락·읽기 불가 원장을 원인별로 차단했다. 누락된 순번에서도 뒤 기록을 회수하지 않고, 소스 계약 버전이 다를 때는 조회 준비와 ACK에 진입하지 않는 시험이 있다. #59에서는 같은 DB에 예약 분개를 `300 → 299`로 바꾼 뒤, 두 번 기동해도 HTTP 주문·취소가 `503 / RECOVERY_BLOCKED`이고 자금 효과와 DB 값이 그대로였다. 새 시험은 정상 복구 쪽의 남은 경계만 채운다. 정상 경로는 Archive 조회 모드, 기존 HTTP 차단 증거는 대사 게이트를 켠 구성으로 기록한다. [#58 대사](https://github.com/0Chord/coin-exchange/pull/198) · [원장 차단 사례](https://github.com/0Chord/coin-exchange/blob/d5925f54cc9c1bd192452646625e9434ee666cdb/app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitFundsTradingHttpTest.kt#L2852-L2914) · [누락 순번](https://github.com/0Chord/coin-exchange/blob/d5925f54cc9c1bd192452646625e9434ee666cdb/app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitFundsTradingHttpTest.kt#L3191-L3226) · [소스 계약 버전](https://github.com/0Chord/coin-exchange/blob/d5925f54cc9c1bd192452646625e9434ee666cdb/app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitFundsTradingHttpTest.kt#L3677-L3728) · [#59 재기동 뒤 HTTP 차단](https://github.com/0Chord/coin-exchange/blob/d5925f54cc9c1bd192452646625e9434ee666cdb/app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitFundsTradingHttpTest.kt#L3354-L3408)

## #69 취소는 남은 예약만 한 번 돌려준다

매수·매도를 매개변수로 둔 통합 시험 한 개를 만든다. 방향마다 새 테스트 데이터를 준비하고 한 주문의 잔량을 취소한 뒤, 같은 취소에서 두 경계를 관찰한다. 두 실행이 같은 코드와 관찰 도구를 사용한다.

1. **매칭 취소 확정 후, 자금 반환 전.** 반대 주문 1개와 검증할 주문 3개를 `100`에 체결시켜 2개를 남긴다. 첫 체결 정산을 확인한 뒤 실제 HTTP로 잔량 취소를 보낸다. 매칭의 취소 결과와 공개 주문장의 잔량 제거를 확인한다. 해당 결과를 자금으로 보내기 전에 보류해 활성 예약이 남아 있으며 PostgreSQL에 해당 `RELEASE`가 없음을 확인하고 자금 JVM을 강제 종료한다.
2. **자금 반환 후, 매칭이 확인 응답을 받기 전.** 같은 기록으로 자금을 켜고 기존 취소 결과가 반영되게 한다. 매칭에 확인 응답을 전달하지 않은 채 자금 잔고, 자금 Archive 위치, PostgreSQL의 `RELEASE`를 확인한다. 매칭에 취소 결과가 남아 있음을 확인한 다음 자금 JVM을 다시 강제 종료한다.
3. **복구 후 확인.** 같은 기록에서 자금을 켠 뒤 대상 결과의 재전송을 풀기 전에 저장된 반환 순번을 확인한다. 보류를 풀면 매칭은 기존 처리 순번을 확인하고 결과를 정리한다. 원래 WAS에 동일 HTTP 취소 토큰을 보내 첫 취소 ID가 유지되는지 확인한다. 별도 사용자의 후속 주문이 PostgreSQL에 반영된 뒤 대상 `RELEASE`의 ID·위치·건수를 다시 읽는다.

| 취소 대상 | 반환 전 | 반환 후 및 같은 토큰 재전송 후 |
| --- | --- | --- |
| BUY 3개 중 잔량 2개 | 매수자 USDT 가용 694 / 예약 204, 체결 1개 | USDT 가용 898 / 예약 0, `RELEASE` 204 USDT 한 건, 매수자 BTC 1 유지 |
| SELL 3개 중 잔량 2개 | 매도자 BTC 가용 0 / 예약 2, USDT 98 정산 완료 | BTC 가용 2 / 예약 0, `RELEASE` 2 BTC 한 건, USDT 98 유지 |

**통과 기준.** 두 방향 모두 반환 전·후의 실제 강제 종료 위치를 증명한다. 두 번째 복구와 재전송 후에도 기존 체결 ID·수량·수수료와 정산 분개가 그대로고, 잔량이 주문장에 다시 나타나지 않는다. 활성 예약이 사라지고 분개는 해당 주문·사용자·자산의 `HOLD` 차변과 `AVAILABLE` 대변에 기대값을 정확히 한 번 기록한다. 취소 거절에 반환이 없는 조건은 #57의 기존 증거를 사용한다. [#57의 기존 검증과 남은 범위](https://github.com/0Chord/coin-exchange/issues/57) · [#183 재기동](https://github.com/0Chord/coin-exchange/pull/183) · [현재 잔량 예약 반환 처리](https://github.com/0Chord/coin-exchange/blob/d5925f54cc9c1bd192452646625e9434ee666cdb/domain-trading/src/main/kotlin/com/exchange/core/trading/BoundedFundingState.kt#L923-L1004)

## 구현 인계와 실행 기준

- #46은 기존 `SplitFundsTradingHttpTest`의 실제 HTTP·PostgreSQL 환경에 입력 표를 붙인다. #68·#70 한 사례와 #69 방향별 사례는 `GtcServerRestartTest`에 두어 기존 HTTP·SQL 검사와 `GtcRestartCluster`를 재사용한다. 정상 경로와 장애 경로 모두 기존 WAS, 자금·매칭의 제품 실행 경로, PostgreSQL Testcontainers를 사용한다. [기존 #45 시험 구조](https://github.com/0Chord/coin-exchange/blob/d5925f54cc9c1bd192452646625e9434ee666cdb/engineering/order-admission-rejection-spec-45.md#L27-L69) · [실제 JVM 재기동 도우미](https://github.com/0Chord/coin-exchange/blob/d5925f54cc9c1bd192452646625e9434ee666cdb/app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/GtcRestartCluster.kt#L150-L182)
- 장애 관찰과 차단은 `src/test`에 둔다. 기존 결과 전달 도우미를 재사용해 대상 순번의 송신과 회신을 제어한다. 자금 반영 전에는 대상 결과가 자금에 도달하지 않았음을, 반영 후에는 실제 자금 응답 또는 적용 위치가 관측됐으나 매칭에 전달되지 않았음을 기록한다. 누적 진행 응답도 대상 매칭 순번을 확인할 수 있으므로 종료 전에는 함께 보류한다. 시각이나 대기 시간만으로 경계를 판정하지 않고 대상 순번, 처리 위치, 매칭에 결과가 남아 있는지를 사용한다. [기존 결과 차단과 관측](https://github.com/0Chord/coin-exchange/blob/d5925f54cc9c1bd192452646625e9434ee666cdb/app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MatchingResultVerificationTool.kt#L506-L562)
- 실제 `SIGKILL` 종료 코드 `137`, 원래 PID와 재기동 PID, 유지된 WAS·매칭 PID, 기존 자금 Archive·클러스터 경로 및 recording ID, PostgreSQL 연결과 namespace를 확인한다. 기존 liveness 만료 확인과 `sync=2` 설정을 재사용한다. #68·#69에서 관찰할 장애는 단일 자금 JVM 프로세스의 강제 종료다. [강제 종료와 재기동 대기](https://github.com/0Chord/coin-exchange/blob/d5925f54cc9c1bd192452646625e9434ee666cdb/app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/GtcRestartCluster.kt#L395-L434)
- PostgreSQL은 기존 `funds_ledger_batch`, `settlement_transaction`, `settlement_posting`, `order_record`를 읽고, HTTP는 실제 주문·취소, 주문 상세·체결·잔고·공개 주문장을 사용한다. 단계마다 예상한 원장·조회 위치에 도달한 후 검사한다. 원장 거래 수, 체결과 취소·반환 순번, 기존 ID와 금액을 기록하며, 실패 시 마지막 실제 HTTP 응답과 프로세스·Archive 위치·DB 진행 상태를 남긴다. [기존 PostgreSQL 확인 코드](https://github.com/0Chord/coin-exchange/blob/d5925f54cc9c1bd192452646625e9434ee666cdb/app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/GtcServerRestartTest.kt#L617-L646)

Java 25와 PostgreSQL Testcontainers 환경에서 이 PR의 브랜치를 체크아웃한 뒤 아래 명령을 실행한다. #220의 정상 종료, #221의 매칭 로그 확정 직후 재생, #58·#59의 차단 사례는 기존 결과를 근거로 사용한다.

```sh
cd "$(git rev-parse --show-toplevel)"
./gradlew --max-workers=2 --continue -PciTestGroup=general :app-api:test \
  --tests '*SplitFundsTradingHttpTest.*주문 단위 위반*' \
  --tests '*GtcServerRestartTest.*자금 강제 종료*' :app-api:ktlintTestSourceSetCheck
```

## 최초 시험과 원인 확인 · 2026-11-28

- Java 25와 PostgreSQL 16에서 신규 시험 4건이 통과했다. 실패·오류·건너뜀은 0건이다. 먼저 #46 한 건에서 여섯 입력의 실제 HTTP 거절과 후속 정상 주문에만 4 USDT 예약이 추가된 것을 확인했다. 이어 변경된 자금 장애 시험 세 건만 골라 실행했다. KtLint와 CI 테스트 그룹 배정 검사도 통과했다. [HTTP 시험](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitFundsTradingHttpTest.kt) · [복구 시험](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/GtcServerRestartTest.kt)
- 첫 장애 시험에서는 자금 JVM 복원과 정산·반환 분개가 정상 반영됐다. 기존 WAS를 유지한 상태에서 마지막 새 주문과 매수·매도 취소 재전송이 각각 `503 NOT_SUBMITTED / GATEWAY_UNAVAILABLE`이었다. 당시 Gateway는 전송 연결을 한 번 열고 전송 작업이 끝나면 요청 수용을 닫았다. 닫힌 세션을 새로 여는 루프가 없었다. 원인 비교를 위해 같은 Archive·PostgreSQL을 유지하고 WAS만 다시 시작한 뒤 주문과 취소를 보냈다. [수정 전 Gateway 실행 루프](https://github.com/0Chord/coin-exchange/blob/d5925f54cc9c1bd192452646625e9434ee666cdb/app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/BoundedAeronGateway.kt#L357-L473) · [수정 전 자금 세션 유지](https://github.com/0Chord/coin-exchange/blob/d5925f54cc9c1bd192452646625e9434ee666cdb/app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitFundsGatewayTransport.kt#L99-L125)
- 최종 장애 실행에서 자금 JVM의 `SIGKILL` 7회를 종료 코드 137과 새 PID로 확인했다. #68·#70의 원장 순번은 7→8→9→10, 매수 예약은 306→204→102→0이었다. A·B·C 정산 위치는 8320, 10944, 15040이었고 기존 A·B의 위치와 PostgreSQL 분개는 유지됐다. #69는 매수 204 USDT와 매도 2 BTC를 각각 한 번 반환했다. 같은 취소 토큰은 WAS를 다시 켠 뒤에도 최초 취소 ID를 돌려줬고, 후속 HTTP 주문은 실제로 접수·매칭됐다.
- 작성 대화를 전달하지 않은 독립 리뷰에서 매도 사례의 매수 maker 예약 차액 1 USDT, 각 장애 시점의 실제 자금 잔고·활성 예약, A→B의 SQL 분개 유지, 재전송 전 원래 결과 복원을 보강했다. 이 시험의 리뷰 대상은 `83b59f87`이었다. 당시 남은 항목은 기존 WAS의 자동 재접속이었다.
- 로컬 XML은 `app-api/build/reports/gtc-final-validation/`의 `http-units.xml`, `funds-recovery.xml`, `retained-was.xml`에 보존했다.

## WAS 자동 재연결 반영 후 검증 · 2026-11-28

- Java 25·PostgreSQL 16에서 실제 자금 JVM 강제 종료 시험 3건이 실패·건너뜀 없이 통과했다. 총 일곱 번 강제 종료하며 최초 WAS PID를 유지했다. #70은 같은 WAS에서 후속 주문이 HTTP 202로 접수되고 주문장과 조회에 반영되는 것까지 확인했다. #69 매수와 매도에서는 같은 WAS에 최초 취소 토큰을 재전송해 최초 취소 ID와 반환 분개 한 건을 유지했다. 반환은 204 USDT와 2 BTC였다. [실제 HTTP 복구 시험](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/GtcServerRestartTest.kt)
- 게이트웨이 시험 25건을 확인했다. 두 연결 개설 오류 및 같은 토큰 재시도, 503·504 분류, 종료 중 연결 유실, 복구 판정과 원격 내구성 경계를 포함한다. 전송 계층을 직접 띄우는 시험 두 건에서 테스트 루트 경로가 없어 준비가 실패했다. JUnit 임시 디렉터리를 사용하도록 고친 뒤 두 건만 재실행해 각각 버전 불일치와 감싸진 Aeron 시간 초과의 분류를 확인했다. 주 소스와 테스트 KtLint, CI 테스트 그룹 배정 검사도 통과했다. [Gateway 회귀 시험](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/BoundedAeronGatewayTest.kt)
- 별도 컨텍스트의 리뷰에서 Aeron 연결 도중 단절과 동시 세션 한도, 클라이언트 측 시간 초과를 확인했다. 연결을 잃은 상황만 재시도하고 프로토콜 불일치 오류는 유지하도록 분류를 보강했다. 재검토한 `fab2a343`의 Kotlin 소스와 최종 파일이 동일하고 변경 범위에서 남은 지적은 없다. [Gateway 연결 소유자](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/BoundedAeronGateway.kt) · [자금 연결과 오류 구분](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitFundsGatewayTransport.kt)
- 실행 대상은 `:app-api:test -PciTestGroup=general`의 `*BoundedAeronGatewayTest`와 `*GtcServerRestartTest.*자금 강제 종료*`다. #46은 앞서 통과한 근거를 유지했다. 로컬 XML은 `app-api/build/reports/gtc-final-validation/`의 `funds-auto-reconnect.xml`, `gateway-reconnect-regression.xml`, `gateway-transport-regression.xml`에 있다.

## #81 기존 실제 HTTP 근거와 남은 전환 목록

[#220](https://github.com/0Chord/coin-exchange/pull/220)은 실제 WAS·Aeron·PostgreSQL에서 매수 주문 3개 중 1개 체결 후 남은 2개를 취소했다. 매수 가용·예약은 `694 / 204 → 898 / 0 USDT`였고, 동일 토큰 재전송 뒤에도 반환 204 한 건과 기존 체결·수수료가 유지됐다. 후속 주문까지 확인했고 `Product tests (general)`이 통과했다. 기존 MockMvc의 대표 사례는 매수가 100,000과 체결가 90,000인 가격 개선까지 포함해, 차액 10,100과 최종 취소 반환 202,000 KRW를 검증한다. 잔량 반환과 중복 방지의 근거는 #220에 있고, 기존 숫자와 응답 계약의 동등 대조는 목록에 남는다. [#220 통합 시험](https://github.com/0Chord/coin-exchange/blob/d5925f54cc9c1bd192452646625e9434ee666cdb/app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/GtcServerRestartTest.kt#L202-L267) · [#220 CI](https://github.com/0Chord/coin-exchange/actions/runs/45758137228/job/139701572539) · [가격 개선이 포함된 기존 시험](https://github.com/0Chord/coin-exchange/blob/d5925f54cc9c1bd192452646625e9434ee666cdb/app-api/src/test/kotlin/com/exchange/core/api/order/application/OrderLifecycleE2ETest.kt#L252-L358)

| 아직 남아 있는 MockMvc 시험 | 동등 대조와 정리가 필요한 항목 |
| --- | --- |
| [`OrderControllerTest` 9건](https://github.com/0Chord/coin-exchange/blob/d5925f54cc9c1bd192452646625e9434ee666cdb/app-api/src/test/kotlin/com/exchange/core/api/order/api/OrderControllerTest.kt#L62-L244) | 접수·예약, 완전 체결, 취소·반환, 없는 주문의 취소, 가격 0, 중복 주문, 지원하지 않는 주문 유형, 타인의 취소, 이미 취소된 주문의 재취소. 기존 `/api/markets` 이벤트 응답과 현행 v3 API 계약을 개별 대조한다. |
| [`OrderLifecycleE2ETest` 6건](https://github.com/0Chord/coin-exchange/blob/d5925f54cc9c1bd192452646625e9434ee666cdb/app-api/src/test/kotlin/com/exchange/core/api/order/application/OrderLifecycleE2ETest.kt#L100-L620) | 완전 체결의 양쪽 정산, 가격 개선이 있는 부분 체결·잔량 취소, 미체결 BUY 전액 반환, 미체결 SELL 전량 반환, 구 경로의 반환 SQL 실패·롤백·후속 명령 차단, 다른 사용자·없는 주문의 취소 거절. 구 경로의 SQL 실패는 현행 경계에서 대응하는 동작부터 확인한다. |

이 목록은 기존 테스트의 위치와 전환 대상을 기록한다. #81의 테스트 전환·삭제와 완료 처리는 후속 작업이다. [#81](https://github.com/0Chord/coin-exchange/issues/81)
