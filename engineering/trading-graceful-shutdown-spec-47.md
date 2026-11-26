# #47 거래 WAS 정상 종료 · 상세 명세

기준은 `feature/phase-3/integration`의 `a27b22e`다. GitHub에 등록된 선행 이슈는 없다. [#47](https://github.com/0Chord/coin-exchange/issues/47) · [통합 기준](https://github.com/0Chord/coin-exchange/tree/a27b22eae393e0ed5f4ae8f8b6993b52b99e6ecd)

## 종료 버튼을 누른 뒤

1. **WAS가 새 접수를 닫는다.** 종료 신호를 받는 순간부터 새 주문·취소는 접수 큐에 들어가지 않는다. 같은 시각 들어온 요청은 큐에 먼저 들어갔는지로 판단한다. 애플리케이션까지 도달한 새 제출에는 기존 `503 NOT_SUBMITTED / GATEWAY_UNAVAILABLE`을 사용한다. 웹 서버가 연결을 먼저 닫으면 호출자는 HTTP 응답 대신 연결 종료를 볼 수 있다. [Spring Boot 정상 종료](https://docs.spring.io/spring-boot/4.1/reference/web/graceful-shutdown.html)
2. **WAS가 먼저 받은 요청의 결과를 처리한다.** 게이트웨이는 큐를 계속 전송하고 자금 엔진의 응답을 읽는다. 자금·매칭 엔진과 양방향 전달 작업도 계속 실행한다. 먼저 들어온 요청의 처리에 기존 요청 시간 제한과 #59 접수 보호를 적용한다. `202`는 예약을 포함한 접수 확인이고 체결 정산이나 취소 반환 완료와는 시점이 다르다.
3. **자금 엔진에서 마켓별 마지막 처리 대상을 확정한다.** 종료 직전까지 접수된 주문·취소의 마지막 전달 순번을 각각 읽는다. 그 순번 이후로 종료 대상을 늘리지 않는다. 예를 들어 마지막 전달 순번이 4이고 자금의 정산 순번이 2라면, 매칭이 4까지 실행했어도 자금이 3의 체결을 정산하고 4의 취소 반환까지 기록할 때까지 기다린다.
4. **WAS가 완료 또는 미완료를 한 번 기록하고 닫는다.** 정산이 따라잡으면 완료를 기록한 뒤 WAS를 닫는다. 시간 안에 증거가 없으면 미완료를 기록하고 WAS를 닫는다. 이미 접수된 요청과 예약은 엔진의 기존 기록에 남는다. 살아 있는 엔진은 처리할 수 있을 때 이어가고, 엔진도 중단되면 기존 복구 흐름이 이어받는다.

GTC 주문이 주문장에 남아 시장의 다음 상대 주문을 기다리는 것은 정상이다. 이미 주문장에 반영된 주문의 남은 수량과 예약금은 정상 종료 뒤에도 유지한다.

## 첫 검증 범위와 운영 시 적용

- **첫 시험은 유일한 주문 접수 경로가 WAS 한 대인 구성에서 진행한다.** 새 접수를 닫은 뒤 다른 송신 경로가 없으므로, 정산을 일부러 멈춰도 종료가 기다리는지 한 사례로 검증할 수 있다. 이 시험의 마켓별 목표 순번은 자금이 확정한 공통 진척이다. 접수 경로가 더 있는 배포에서는 이 WAS의 결과 하나만으로 거래 시스템 전체의 종료 준비를 판정할 수 없다.
- **완료 기준은 자금 엔진에 기록된 정산·반환까지다.** 매칭 쪽의 정산 ACK 반영과 PostgreSQL 조회 반영은 기존 재전송·재생 경로에서 처리한다. 조회 DB가 아직 따라오는 중이어도 Archive 모드에서는 정산·반환 자체를 완료로 판단할 수 있다. 기존 실행에 필요한 연결과 보호 작업은 대기가 끝날 때까지 살려 둔다.
- **종료 대기 기본값 10초를 제안한다.** 최초 종료 요청부터 하나의 제한 시간을 재고, 다시 종료를 요청해도 그 시간을 연장하지 않는다. 개별 요청의 대기 시간과 종료 후 자원 정리 시간은 기존 설정에 따른다. Spring 종료 단계와 배포 환경의 종료 유예 시간은 애플리케이션이 결과를 기록하고 연결을 닫을 수 있도록 맞춘다. 이 값은 운영 환경에서 검증한 수치가 아니다.

| 운영 상황 | 필요한 종료·복구 범위 |
| --- | --- |
| WAS 한 대를 교체하고 나머지 WAS가 계속 주문 접수 | 대상 WAS로 새 요청을 보내지 않게 하고 기존 HTTP 요청과 송신 큐를 정리한다. 기존에 엔진이 받은 작업의 정산은 계속된다. 다중 WAS 배치와 버전 호환 교체는 별도 실행 검증이 필요하다. |
| 같은 빌드·동일 배치로 엔진 노드 한 대를 재기동 | 해당 클러스터의 과반을 유지하고 재합류한 노드의 복제를 확인한다. 리더 전환이 필요한 경우 응답이 잠시 늦어질 수 있다. [자금 과반 시험](https://github.com/0Chord/coin-exchange/blob/a27b22eae393e0ed5f4ae8f8b6993b52b99e6ecd/app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerFundsQuorumLossTest.kt#L16) |
| 자금·매칭 클러스터 전체를 내리는 계획 점검 | 전체 WAS와 직접 Aeron 경로의 신규 제출을 막고, 차단 전에 받은 큐를 처리한다. 모든 접수 경로의 종료 경계가 반영됐다는 증거를 얻은 뒤 마켓별 목표 순번까지 정산·반환을 확인한다. 시간 초과면 종료 준비 미완료를 유지한다. 완료 뒤 각 노드의 영속 기록을 보존하며 엔진을 내리고, 재기동·대사 후 점검 차단을 해제한다. 실제 서버를 이어 검증하는 이슈는 [#66](https://github.com/0Chord/coin-exchange/issues/66)이다. |

**새 엔진 빌드나 멤버·주소를 바꾸는 배포에는 별도의 복구 호환 검증이 필요하다.** 현재 복구 코드는 원래 실행물·스키마·배치·연결 설정의 지문을 동일하게 요구한다. 점검 완료와 디스크 보존만으로 새 빌드가 기존 기록을 열 수 있다고 판단할 수 없다. [실행 지문 구성](https://github.com/0Chord/coin-exchange/blob/a27b22eae393e0ed5f4ae8f8b6993b52b99e6ecd/app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentSplitTradingNode.kt#L128) · [기존 기록 검증](https://github.com/0Chord/coin-exchange/blob/a27b22eae393e0ed5f4ae8f8b6993b52b99e6ecd/app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentSplitTradingNode.kt#L548)

## 완료를 판단하는 방법

현재 코드는 게이트웨이의 `close()`에서 큐의 미전송 요청과 응답 미확인 요청을 나눠 future를 끝내고 연결을 닫는다. 엔진 JVM의 종료 훅은 전달 작업부터 닫는다. 두 경로 모두 자금의 정산·반환까지 기다렸다는 판정을 제공하지 않는다. [게이트웨이 종료](https://github.com/0Chord/coin-exchange/blob/a27b22eae393e0ed5f4ae8f8b6993b52b99e6ecd/app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/BoundedAeronGateway.kt#L403) · [엔진 노드 종료](https://github.com/0Chord/coin-exchange/blob/a27b22eae393e0ed5f4ae8f8b6993b52b99e6ecd/app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentSplitTradingNode.kt#L459)

제안은 기존 게이트웨이의 요청 큐와 Spring 종료 수명주기에 아래 판단을 붙이는 것이다.

- **전송 경계.** 게이트웨이는 접수 잠금 안에서 새 큐 등록을 닫고 기존 작업을 계속 진행한다. 이전 요청의 마지막 송신 시도 이후, **동일한 Aeron 세션**에 내부 진행 조회를 넣어 자금 서비스가 이를 처리한 응답으로 종료 대상 순번을 확정한다. 별도 연결로 조회하면 앞선 주문 메시지보다 먼저 처리될 수 있다. 세션이나 리더 임기가 바뀌어서 그 순서를 증명할 수 없거나, 어느 마켓의 진척인지 확인할 수 없으면 완료 판정을 내리지 않는다. 전송 순서는 기존 큐와 자금 로그에 따르며, 여러 HTTP 응답이 클라이언트에 도착하는 순서까지 요구하지 않는다. [Aeron 세션별 순서](https://aeron.io/docs/aeron/aeron-channel-stream-session/) · [Aeron Cluster 로그](https://aeron.io/docs/aeron-cluster/overview/)
- **금융 완료.** 자금 엔진의 기존 마켓별 `dispatch.issued`를 종료 대상 순번으로 읽고, 같은 마켓의 연속 정산 순번 `matchingSettlementAck.through`가 그 이상인지 확인한다. 일반 주문, 취소, 매칭 최종 거절 모두 전달 순번 하나에 매칭 결과 순번 하나가 대응한다. 지금의 `Progress`에 들어 있는 주문 승인·실행 순번만 비교하면 취소를 빠뜨린다. 기존 내부 `Progress` 응답에 필요한 수치만 추가하는 것을 제안한다. 값이 없는 구버전 응답을 0건으로 해석하지 않는다. [기존 카운터를 함께 읽는 코드](https://github.com/0Chord/coin-exchange/blob/a27b22eae393e0ed5f4ae8f8b6993b52b99e6ecd/app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitTradingClusterAdapter.kt#L645) · [현재 진행 응답](https://github.com/0Chord/coin-exchange/blob/a27b22eae393e0ed5f4ae8f8b6993b52b99e6ecd/app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitTradingClusterAdapter.kt#L897) · [전달·결과 순번 검증](https://github.com/0Chord/coin-exchange/blob/a27b22eae393e0ed5f4ae8f8b6993b52b99e6ecd/domain-trading/src/main/kotlin/com/exchange/core/trading/MatchingInputValidation.kt#L156)
- **실패와 정리.** 만료 시 전송하지 않은 요청은 기존 미전송 경로, 전송했으나 응답을 확인하지 못한 요청은 기존 `504 OUTCOME_UNKNOWN` 경로로 완료한다. 이미 받은 `202`를 소급 변경하거나, 반환이 끝났다고 처리하지 않는다. 종료 결과에는 완료·시간 초과·확인 불가를 구분하고, 확인한 마켓별 목표와 진척을 운영 로그에 남긴다. 반복 종료와 완료 콜백 안의 종료는 같은 종료 작업을 사용한다. 콜백이 자기 종료를 기다리는 경합을 막는다. [기존 future 정리](https://github.com/0Chord/coin-exchange/blob/a27b22eae393e0ed5f4ae8f8b6993b52b99e6ecd/app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/BoundedAeronGateway.kt#L432)

WAS 종료 시작 시 접수 차단을 먼저 실행하고, Spring의 웹 종료 단계와 빈 파괴 사이에 제한 시간 안에서 비동기 종료 대기를 연결한다. 기존 `destroyMethod = "close"`는 남은 연결 정리를 맡을 수 있다. HTTP API, 주문·취소 기록, 스냅샷 형식은 유지한다. 내부 Protobuf 응답을 확장하는 구현이라면 기존 스키마 지문과 실행물 검증의 영향이 있으므로 같은 빌드와 그에 맞는 manifest로 검증하고, 자료를 임의로 재사용하거나 지문 검사를 우회하지 않는다. [WAS 게이트웨이 구성](https://github.com/0Chord/coin-exchange/blob/a27b22eae393e0ed5f4ae8f8b6993b52b99e6ecd/app-api/src/main/kotlin/com/exchange/core/api/config/BoundedTradingGatewayConfig.kt#L27) · [실행물·스키마 지문](https://github.com/0Chord/coin-exchange/blob/a27b22eae393e0ed5f4ae8f8b6993b52b99e6ecd/app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/BoundedExecutionIdentity.kt#L19)

## 수용 사례와 검증 순서

| 사례 | 시험과 통과 조건 |
| --- | --- |
| 종료 중 접수 경합과 여러 요청 | 기존 `BoundedAeronGatewayTest`의 통제 전송으로 앞서 큐에 들어간 주문·취소가 계속 처리되고 각각의 future가 한 번 끝나는지 확인한다. 차단 뒤의 신규 제출은 미전송으로 끝난다. 전송 중 완료와 종료가 겹치거나 같은 종료를 두 번 호출해도 종료 판정과 자원 정리가 한 번만 일어난다. |
| 체결·예약 반환이 끝나야 닫힘 | 실제 HTTP·Aeron 1+1 기존 시험을 사용한다. 구매자 USDT 1,000에서 3개를 개당 100에 주문해 300을 예약한다. 판매자의 101 매도 1개를 주문장에 남겨 둔다. 그 뒤 정산 송신을 보류한 상태에서 100 매도 1개와 구매자 잔량 취소를 차례로 접수한다. WAS를 닫기 시작하면 두 요청의 접수 응답은 유지되지만, 매칭이 따라잡아도 종료 완료가 나오지 않는다. 구매자 잔고는 여전히 700/300이다. 보류를 풀면 100 정산과 200 반환이 한 번씩 일어나 구매자 USDT **900/0**, BTC **1/0**이 된다. 판매자는 USDT **100/0**, BTC **1/1**이며 101 매도 호가는 잔량 1을 유지한다. 이 상태에서만 WAS 종료 완료를 관측한다. |
| 시간 초과와 뒤늦은 처리 | 같은 통합 설비에서 정산 보류를 시험용 짧은 종료 한도까지 유지한다. 정상 완료 표시가 없어야 하고, WAS는 미완료 결과를 남기고 닫혀야 한다. 이미 접수된 주문의 예약은 유지한다. 보류 해제 뒤 살아 있는 엔진이 정산·반환을 한 번만 수행하는지 확인한다. 별도 게이트웨이 시험에서 미송신 future와 송신 후 미확인 future를 각각 확인하고, 종료 경계 조회가 불확실한 경우도 완료 처리하지 않는지 본다. |

기존 통합 시험의 가동 코드를 그대로 쓰고 정산 보류는 단건·배치 결과를 모두 막을 수 있게 테스트에서만 조정한다. 대기 상태는 게이트웨이의 차단 시점, 매칭 진척, 자금의 정산 진척으로 관측한다. 고정 sleep이나 웹 요청 수로 완료를 추정하지 않는다. PostgreSQL 조회 진척은 정산 완료의 판정값에서 제외하며, 정산·반환은 자금 상태와 확정 원장으로 대조한다. [실제 HTTP의 정산 보류 사례](https://github.com/0Chord/coin-exchange/blob/a27b22eae393e0ed5f4ae8f8b6993b52b99e6ecd/app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitFundsTradingHttpTest.kt#L224) · [정산 송신 보류 도구](https://github.com/0Chord/coin-exchange/blob/a27b22eae393e0ed5f4ae8f8b6993b52b99e6ecd/app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitFundsTradingHttpTest.kt#L3849) · [종료 콜백 회귀](https://github.com/0Chord/coin-exchange/blob/a27b22eae393e0ed5f4ae8f8b6993b52b99e6ecd/app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/BoundedAeronGatewayTest.kt#L392)

구현 시 처음에는 게이트웨이 차단·future 시험을 만들고, 내부 진척 응답과 Spring 종료를 연결한 뒤 통합 시험을 실행한다. 실행 대상은 구현 체크아웃의 `:app-api:test -PciTestGroup=general`에서 `BoundedAeronGatewayTest`, `SplitFundsTradingHttpTest`, 변경된 코덱의 시험이다. 실제 Spring 컨텍스트 종료는 통합 시험에서 관측하고, 별도 프로세스나 서버 수동 기동은 필요 없다. 여러 WAS의 공통 종료, OS 차원의 프로세스 종료 시험, 강제 종료·재기동, 새 스냅샷 정책은 이번 범위 밖이다.

[#134](https://github.com/0Chord/coin-exchange/issues/134)의 복합 장애 시험은 미완료 작업을 재시작 후 이어가는 근거다. [#59](https://github.com/0Chord/coin-exchange/issues/59)는 복구 후 언제 새 접수를 열지를 판단하며, #47은 닫기 전에 어디까지 처리됐는지를 판단한다.

## 구현 연결

- 컨텍스트 종료 이벤트에서 새 접수를 막고, 웹 정상 종료 다음 단계에서 거래 종료 결과를 기다린다. 게이트웨이와 조회·보호 빈을 닫기 전에 이 단계가 실행된다. 설정 이름은 `TRADING_GATEWAY_DRAIN_TIMEOUT_MS`며 기본값은 10,000ms다. 이보다 길게 설정하면 Spring 종료 단계와 배포 환경의 종료 유예 시간도 맞춘다. [WAS 수명주기](../app-api/src/main/kotlin/com/exchange/core/api/config/BoundedTradingGatewayConfig.kt#L97)
- 기존 자금 진척 조회는 같은 Aeron 세션에서 전송한다. 주문·취소가 포함된 마켓별 순번은 분리 전송 v2 응답의 선택 필드로 전달한다. 기존 통합 스키마 지문과 스냅샷 형식은 유지된다. 진척 조회 자체에는 사용자 응답에 사용하는 원격 보관 확인을 걸지 않는다. [게이트웨이 종료 판단](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/BoundedAeronGateway.kt#L490) · [분리 전송 응답](../app-api/src/main/proto/split_order_delivery.proto)
- 독립 검토에서 리더 변경으로 종료 경계를 잘못 잡는 경우, 기한 뒤 주문 응답·전송, 내부 조회의 원격 보호 대기를 재현했다. 접수를 닫을 때 본 임기로 확인 기준을 고정하고, 기한 검사를 실제 전송 직전과 응답 확인에 적용했다. 기존 #59 복구 판정의 잠금을 기다릴 때도 잠금을 얻은 뒤 기한을 다시 검사한다. [게이트웨이 회귀 시험](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/BoundedAeronGatewayTest.kt#L61) · [실제 WAS 종료 시험](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitFundsTradingHttpTest.kt#L1904)

## 실행과 독립 검토 결과

- 최종 앱 시험 104건이 통과했다. 이 중 `SplitFundsTradingHttpTest` 33건에는 실제 Spring 종료의 정상 완료·시간 초과와 기존 조회·복구 회귀가 포함된다. `CancellationCoreFlowTest` 12건과 아키텍처 시험 388건, 필수 검사 P01~P09 확인도 통과했다. 실패·오류·건너뛴 시험은 0건이다. 앱·도메인 KtLint와 CI 테스트 분류 검증까지 통과했다.
- 최종 코드 변경 SHA-256 `970960f0409f70ccb7defe55c386e83d9c87a78fcda9f9e4d948a2afb5fc53e9`를 작성 대화를 공유하지 않은 리뷰어가 검토했다. 기한을 넘겨 잠금이 풀린 반례는 종료 `TIMED_OUT`, 주문 `NOT_SUBMITTED`, 송신 0건으로 바뀌었다. 임기 변경, 늦은 응답과 원격 보관 반례도 재실행했고 변경 범위에서 추가 지적은 없었다.

구현 브랜치의 저장소 루트에서 실행한다.

```sh
./gradlew -p "$(git rev-parse --show-toplevel)" --continue \
  :app-api:test -PciTestGroup=general \
  --tests '*BoundedAeronGatewayTest' --tests '*AeronGatewaySettingsTest' \
  --tests '*SplitOrderDeliveryCodecTest' --tests '*SplitFundsGatewayTransportTest' \
  --tests '*BoundedTradingRoleGoldenTest' --tests '*BoundedTradingOrderHttpTest' --tests '*SplitFundsTradingHttpTest' \
  :domain-trading:test --tests '*CancellationCoreFlowTest' \
  :app-api:ktlintCheck :domain-trading:ktlintCheck :architecture-tests:verifyArchitectureReport --console=plain
```
