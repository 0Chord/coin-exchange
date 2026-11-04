# #169 · 매칭 결과 전달·자금 정산·매칭 로그 ACK 구현 기록

작성: 2026-11-04. 기준 통합 `33e464fef96327bc19100c8a098aae9b7eafd0ff`, 작업 브랜치 `feat/aeron-result-delivery/169`. [명세](matching-result-delivery-spec.md)가 계약 원본이다. **로컬 구현·검증 완료 기록**이다. 명세 R1~R28의 해당 수준을 대조했고 독립 최종 재검토의 확인된 미해결 결함은 없다. 운영 전체 장애·성능·보안 보장을 뜻하지 않는다. 게시 준비 시점(2026-11-04)의 기록이며, 이 기록을 얻을 때는 커밋·PR·원격 CI·병합·이슈/보드 변경을 수행하지 않았다.

**완료 범위:** 별도 자금3 + 매칭3 JVM의 전체 결과 전달 → 자금 정산 → 매칭 자기 로그의 정산 ACK → 결과 전달 자료 회수를 연결했다. 새 Docker 시나리오6개와 기존 한 방향 전달1개가 통과했다. 최종 선택 회귀·구조 검사591개(실패/오류/skip0), 전체 `ktlintCheck`와 구조 보고서 확인도 성공했다. 전체 저장소의 모든 제품/DB 테스트를 한 번에 실행했다는 뜻은 아니다.

**실행 소스 식별:** 아래 실행 기록은 기준 통합 commit 위의 미커밋 제품·시험 변경을 대상으로 얻었다. 실제 Docker 보고서는 동결 이미지와 제품·시험 파일의 SHA-256으로 실행 대상을 구분한다. 기준 commit을 구현 commit이라고 부르지 않는다.

## 1. 이번에 연결하는 흐름

기존 #168은 예약 주문을 매칭에 전달하고 적용 ACK로 주문 전달 자료만 회수했다. 이번에는 매칭에 보관한 전체 결과를 자금에 돌려보내 정산하고, 정산 확인을 매칭 로그에도 반영한다.

1. 매칭 서비스 스레드의 `readResults()`가 아직 확인되지 않은 불변 결과를 한도 안에서 게시한다. 조회로 원본을 지우지 않는다.
2. 매칭 리더의 `MatchingResultDeliveryAgent`가 결과를 typed Protobuf로 인코딩해 자금 ingress로 보낸다. 팔로워·재생·snapshot·구세대는 새 송신을 하지 않는다.
3. `SplitTradingClusterAdapter`의 자금 확정 callback이 기존 `applyMatchingResult()`를 실행한다. 전체 검증·후보 계산 후 잔고·예약·수수료·정산 prefix를 함께 반영하고 누적 ACK를 응답한다.
4. 작업은 그 ACK를 matching ingress로 돌려보낸다. matching 확정 callback의 `acknowledgeFundsSettled()`가 정산 prefix와 결과 count/bytes를 함께 바꾼다.
5. ACK를 자기 로그에 기록하기 전까지 매칭 전체 결과는 남는다. 이미 금융 정산된 결과가 다시 와도 자금은 중복 효과 없이 ACK를 다시 만든다. 대기 원본·잔량 예약은 별도 수명으로 유지한다.

이 다섯 단계 전체가 하나의 분산 트랜잭션은 아니다. 각 클러스터가 자기 로그의 확정 명령으로 상태를 바꾸며, 사이의 단절은 미확인 결과와 연속 처리 prefix로 이어간다.

## 2. 설계 결정 — 자금 정산 후에도 매칭 결과를 보관하는 이유

자금 정산과 매칭의 자료 회수는 서로 다른 로그에서 확정된다. 따라서 자금 정산 응답을 받아도 매칭 로그에 ACK를 기록하기 전까지 매칭 결과를 보관한다. 아래 코드·테스트는 이 보존·회수 경계를 설명한다.

판매1@100 뒤 구매1@100·수수료0의 수용 기대값은 구매자Q900/X1, 판매자Q100/X0, 관련 예약0이다. 이때 matching ACK가 아직 자기 로그에 없으면 매칭 결과는 남아 있어야 한다. ACK를 받았다는 로컬 기억만으로 삭제하면 재시작 후 어떤 결과를 다시 확인할지 잃을 수 있다. 로그에 ACK를 확정한 뒤의 삭제는 재생해도 같은 회수 지점을 재현할 수 있다.

### 실제 코드가 이어지는 곳

다음은 현재 소스에서 발췌한 코드다. 위치는 작성일의 최종 파일 기준이며 생성한 예시 코드가 아니다.

1. [adapter의 비소비 조회](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitTradingClusterAdapter.kt#L593)는 서비스 스레드에서 `matching.readResults(...)`를 호출해 future를 완료한다. 전송 작업이 owner의 mutable Map을 직접 읽지 않는다. [기존 코어 readResults](../domain-trading/src/main/kotlin/com/exchange/core/trading/ReservedMatchingState.kt#L180)는 결과와 issued/settled 진행 정보를 함께 반환한다.
2. [worker의 receiveFunds](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MatchingResultDeliveryAgent.kt#L394)는 correlation·stream·context·through를 확인한다. `APPLIED`, `DUPLICATE`, `PRUNED`는 유효 누적 ACK가 있을 때만 다음 단계로 간다. 오래된 source read보다 ACK가 앞서면 소유 스레드에 최신 issued를 다시 확인한다. 낮은 ACK로 prefix를 후퇴시키지 않는다.
3. [자금 확정 callback](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitTradingClusterAdapter.kt#L309)은 기존 자금 코어를 그대로 사용한다.

```kotlin
val applied =
    checkNotNull(funds).applyMatchingResult(
        command.result,
        Instant.ofEpochMilli(timestamp),
        settlementCalculator,
    )
```

`timestamp`는 확정 callback의 시각이다. 원격 작업이 자기 시계로 돈을 바꾸지 않는다. [기존 applyMatchingResult](../domain-trading/src/main/kotlin/com/exchange/core/trading/BoundedFundingState.kt#L272)는 원본·정책·체결/효과·순서와 정산 후보를 검증하고 한 번 적용한다. 뒤늦은 같은 결과는 최초 금융 효과·receipt 시각을 다시 만들지 않는다.

4. [worker의 flushAck](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MatchingResultDeliveryAgent.kt#L665)는 자기 matching 연결로 `SplitResultDeliveryCommand.RecordAck(ack)`를 보낸다. 자금 응답을 받은 것과 이 명령의 확정은 별개다. ACK를 한 번에 최신 누적값 하나로 보관하고 기본16건/2ms 또는 drain 조건에서 제출한다. 회수 제어용 byte 여유를 별도로 남겨 결과가 찼다고 ACK까지 굶지 않게 한다.
5. [매칭 확정 callback](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitTradingClusterAdapter.kt#L339)의 아래 호출이 실제 source 회수 경계다.

```kotlin
val owner = checkNotNull(matching)
val recorded = owner.acknowledgeFundsSettled(command.ack)
```

[기존 acknowledgeFundsSettled](../domain-trading/src/main/kotlin/com/exchange/core/trading/ReservedMatchingState.kt#L244)가 확인 prefix와 보관 결과 수·bytes를 함께 전진시킨다. worker의 전송 중 목록을 비우는 행위가 이 source 원본 삭제를 대신하지 않는다. Archive의 기존 입력 기록을 삭제하는 호출도 아니다.

### 두 로그 사이에서 중단되면

| 중단 시점 | 남는 사실과 재개 결과 |
| --- | --- |
| 결과를 아직 자금에 적용하지 못함 | 매칭 전체 결과·자금 예약 유지. 같은 전체 결과를 다시 보낸다. timeout을 최종 거절이나 예약 반환으로 바꾸지 않는다. |
| 자금은 정산했으나 응답이 유실됨 | 자금 금융/prefix는 전진, 매칭 결과는 보존. 재전송은 DUPLICATE 또는 회수된 과거의 PRUNED와 누적 ACK를 돌려준다. |
| 자금 ACK는 받았으나 matching 로그 미기록 | worker 로컬 기억이 사라져도 source 결과는 남는다. 실제 전체6노드 재시작에서 같은 정산 ACK를 재생성해 회수했다. |
| matching 로그에 ACK가 기록됐으나 응답 유실 | 회수 prefix가 기록되어 재생 후에도 결과가 부활하지 않는다. 같은 ACK 반복은 추가 회수·정산을 만들지 않는다. |
| 잘못된 stream/context/ACK 또는 양쪽 복구 prefix 모순 | 정상 완료 ACK를 만들거나 자료를 재생성하지 않는다. 해당 전달을 오류로 멈추고 예약·원본을 임의로 풀지 않는다. |

팔로워는 같은 확정 명령으로 같은 역할의 상태를 만든다. 결과를 외부로 보내는 작업만 리더에서 활성화된다. [세대 갱신/무효화](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitTradingClusterAdapter.kt#L651)로 팔로워 전환·snapshot·종료·오류 중의 늦은 future/응답은 새 송신에 사용하지 않는다. 이 단위 경계를 실제 모든 리더/과반 장애 조합 시험을 완료한 것으로 확대하지 않는다.

## 3. 파일과 책임

| 분류 | 파일 | 입력과 결과 |
| --- | --- | --- |
| 제품 실행 | `codec/SplitResultDeliveryCodec.kt`, `proto/split_result_delivery.proto` | 결과 전체·정산 ACK를 schema89 바이트로 변환. 기존 schema88 bytes 유지 |
| 제품 실행 | `aeron/SplitResultDeliveryClient.kt` | 실제 async Aeron 연결·세션 헤더 포함 길이 검사·완결 응답 수신·연결 정리 |
| 제품 실행 | `aeron/MatchingResultDeliveryAgent.kt` | 비소비 조회·제한 pipeline·재전송·GAP/PRUNED·ACK 환류. mutable owner 직접 접근 없음 |
| 제품 실행 | `aeron/SplitTradingClusterAdapter.kt` | schema별 수신 경계·확정 금융 적용·ACK 기록·소유 스레드 조회·세대 무효화 |
| 제품 실행 | `aeron/SplitTradingConfiguration.kt`, `DevelopmentSplitTradingNode.kt`, `DevelopmentSplitTradingMain.kt` | 명시 RESULT_SETTLEMENT_DEV·결과 한도/지문·리더 작업 생명주기. 기존 한 방향/통합 실행 유지 |
| 공통 지원 | 기존 `SplitOrderDeliveryCodec.kt` | dispatch/context/lineage/role 순수 필드 변환만 internal로 공유 |
| 테스트 | 새 codec/client/worker 테스트와 기존 adapter/startup 테스트 확장 | 아래 Given/When/Then의 단위 경계 |
| 검증 도구 | `MatchingResultVerificationTool.kt`, 새 Docker 테스트·compose override | 시험 wrapper의 관찰·응답 보류·실제 독립3+3·기록 복원. 제품 gate/전체 상태 endpoint 없음 |
| 검증 공통 | 기존 `DockerFundsOrderDeliveryTest.kt` | 기존 기본 시나리오 유지, 같은 이미지/정리/한도 기반을 결과 시나리오에서 재사용 |

## 4. 단위 검사의 읽기 순서

모든 fixture의 테스트 이름·준비·실제 assertion은 최종 소스에 맞춰 아래 표에 이어 기록한다. 공통 Mockito callback은 실제 역할 owner를 사용하지만 실제 Aeron·quorum·Archive를 대신 증명하지 않는다. worker 대역은 실제 도메인 소유자와 전송 응답 순서를 통제하며, 네트워크 증거는 Docker 검사에서 따로 얻는다.


기준 통합 `33e464fef96327bc19100c8a098aae9b7eafd0ff` 위의 미커밋 diff와 새 파일을 읽고 테스트의 준비·행동·assertion을 대조했다. 명세의 [수용 사례](matching-result-delivery-spec.md#11-수용-사례--구현-후-확인할-기대값) R1~R28은 의도이고, 구현 설명 자체는 통과의 증거로 사용하지 않았다. 이 읽기 검토는 새 빌드·Docker 실행과 구분한다.

| 읽기 지도 | 실제 대상·종류 | 설명·실행 지위 |
| --- | --- | --- |
| 전체 결과를 빠짐없이 바이트로 전달 | codec 새 11개 / 순수 변환·경계 | 전부 설명. U |
| 응답을 확인한 경우만 완료로 취급 | client 새 4개 / 연결 대역 | 전부 설명. U |
| 재전송·누적 ACK·보존량·세대 | worker 새 20개 / 실제 도메인 + 전송·조회 대역 | 전부 설명. U |
| 확정 callback의 금융 적용과 자료 회수 | adapter 기존 8개에 새 6개 / callback 단위 | 새 6개 전부 설명. U |
| 새 실행 모드·한도·복구 지문 | startup 기존 11개에 새 3개 / 설정·임시 파일 경계 | 새 3개 전부 설명. U |
| 독립 자금 3 + 매칭 3의 통신·복원 | Docker 새 6개 / 실제 통합을 수행하는 테스트 | 전부 설명. D |

신규 대상은 단위 `@Test` 정의 44개와 Docker 정의 6개다. 단위 전체 클래스 63개에는 기존 adapter 8개·startup 11개가 포함된다. JUnit 파라미터 공급자·skip은 없고, 아래 반복 분기는 한 메서드 안에서 모두 assertion을 수행한다. 이 수는 수용 조건의 개수나 결함 없음 보장을 뜻하지 않는다.

아래 실행·리뷰 로그의 `build/issue169-*` 경로는 미게시 로컬 산출물이다. 실행 수치의 기록과 CI에서 재현·수집할 보고서 경로(§9)를 구분한다.

**실행 지위 U:** units7 XML·합계 (`build/issue169-verification/units7/counts.json`)에서 다섯 클래스 63개, 실패·오류·skip 각각 0을 확인했다. XML 시간은 2026-11-04 05:42:32~34 UTC이며 로그 (`build/issue169-verification/units7/execution.log`)는 `BUILD SUCCESSFUL`이다. XML testcase 이름을 현재 정의와 전수 대조해 누락 0이었다. adapter의 산술 넘침 사례, codec의 MAX frame·산술 넘침 사례도 실제 XML에 있다. XML에는 소스 해시가 없으므로 아래 현재 소스의 바이트 동일성까지 XML만으로 증명하지 않는다. 이후 native frame Docker 도구·harness 변경의 실행 근거로 이 단위 기록을 쓰지 않는다.

**실행 지위 D:** baseline XML의 테스트1개와 cases XML의 테스트5개가 각각 실패·오류·skip0이고, 해당6개 JSON은 success=true·issues=[]·자원정리=true다. R14 중간 예약·거절 재전송과 R9 실제 FIFO/PRUNED 단계를 추가한 뒤 **D05·D06의 최종 재실행도 성공**했다. 최종 XML (`build/issue169-verification/docker-final-boundaries/TEST-com.exchange.core.api.trading.infrastructure.aeron.DockerMatchingResultDeliveryTest.xml`)은2개·실패/오류/skip0, 로그 (`build/issue169-verification/docker-final-boundaries/execution.log`)는 BUILD SUCCESSFUL이다. 최종 두 JSON은 success=true·issues=[]·자원정리=true이며, 저장한 제품/시험 소스12개의 SHA-256을 현재 파일과 직접 대조해 모두 일치했다. 아래 표에서 이전 실행과 마지막 재실행을 나눠 연결한다. 전체 필수 회귀는 아래 §5의 required-final 실행 근거에 별도로 연결한다.

| 실제 시나리오 | 확인한 실행 근거 |
| --- | --- |
| D01 baseline | 성공 보고서 (`build/issue169-verification/docker-baseline/result.json`), 05:45:15 UTC 완료,90.862초. 실제 전체 로그·snapshot tail checkpoint 포함. |
| D02 partial | 성공 보고서 (`build/issue169-verification/docker-cases/delivery17937712841206de9de12-result.json`),18.433초. |
| D03 fee | 성공 보고서 (`build/issue169-verification/docker-cases/delivery17937713025666366d00b-result.json`),18.156초. |
| D04 cap | 성공 보고서 (`build/issue169-verification/docker-cases/delivery17937712514215fe7ac75-result.json`),16.425초. |
| D05 active | 최종 성공 보고서 (`build/issue169-verification/docker-final-boundaries/delivery1793771536737879df1a1-result.json`),05:52:41 UTC 완료·24.823초. Q700/hold300 중간 보존→Q1,000/hold0→DUPLICATE 재전송 뒤 같은 최종값까지 확인. |
| D06 large | 최종 성공 보고서 (`build/issue169-verification/docker-final-boundaries/delivery179377156165999f6ee9a-result.json`),05:53:24 UTC 완료·42.804초. 정상 큰 정산·실제 FIFO 회수·PRUNED 재전송·native MAX/MAX+1 모두 확인. |

당시 cases XML에는 결과 5개만 수집되었고 baseline은 별도 XML에 있다. 함께 있는 기존 DockerFundsOrderDeliveryTest 1개 성공은 신규 결과 시나리오 6개 수에 더하지 않았다.

### 공통 준비와 용어

`resultThrough`는 자금이 정산한 연속 결과 번호, `fundsSettledThrough`는 매칭 자기 로그에 기록한 정산 확인 번호다. `ACK`는 이 연속 구간을 확인한 응답이다. 자금 ACK를 받는 단계와 매칭 자기 로그에 ACK를 기록해 결과 자료를 회수하는 단계가 다르다. `PRUNED`는 자금에서 과거 비교 receipt를 회수한 응답일 수도, 매칭에서 이미 정산 확인한 결과 구간을 조회한 응답일 수도 있다. 각 행에서 어느 쪽인지 구분했다.

| 공통 준비 | 실제 사용 값·대역과 실제 코드·한계 |
| --- | --- |
| codec [codec:66](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/SplitResultDeliveryCodecTest.kt#L66) · [codec:403](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/SplitResultDeliveryCodecTest.kt#L403) · [codec:447](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/SplitResultDeliveryCodecTest.kt#L447) | X/Q, scale 0, 가격100. lineage 자금(1,2)/매칭(3,4), epoch13/config19, context ordinal7, maker0.7%/taker1.5%. 기본 `result(n)`은 BUY(n+1), SELL1 maker n명, 체결 n건 후 BUY 잔량1의 대기 사건을 직접 구성한다. 실제 예약 계산기를 쓰지만 일반 fixture는 실제 매칭 실행이 아니다. C05만 실제 매칭 owner가 결과를 만든다. |
| client [client:58](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitResultDeliveryClientTest.kt#L58) | `/existing-node-driver`와 endpoint를 Factory 대역에 넘긴다. offer는 codec으로 correlation을 읽고 poll은 **다른 correlation 응답을 먼저**, 맞는 응답을 뒤에 준다. 실제 Aeron/Driver 생성·서버·복제 로그 없음. |
| worker [worker:593](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MatchingResultDeliveryAgentTest.kt#L593) · [worker:722](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MatchingResultDeliveryAgentTest.kt#L722) · [worker:750](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MatchingResultDeliveryAgentTest.kt#L750) | 실제 자금·매칭 도메인 상태. buyer Q1,000 / seller A5, 수수료0, A/Q scale0, epoch1/config7. 기본 `produce()`는 BUY1@1을 실제 승인·예약 후 매칭에 적용한다. 예약·준비·epoch 회전 시각은 EPOCH. source 대역은 future를 즉시/지연 완료한다. 전송 대역 offer는 실제 자금 정산(고정 `1970-01-01T00:00:00.123Z`)·매칭 ACK 도메인 함수를 호출한다. `holdFunds`는 정산 **후 응답만 보류**한다. override 응답이 있으면 그 도메인 호출 자체를 건너뛰므로 그 행의 가짜 ACK를 실제 금융 정산 증거로 읽지 않는다. `cycles()`는 고정 가상시계로 doWork를 반복하며 실제 시간 경과가 아니다. |
| worker 시간·한도 [limits:35](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MatchingResultDeliveryAgent.kt#L35) | 가상 now=1ns. 시도3초, 재연결50ms, BUSY1~20ms, ACK2ms/16건, keepalive500ms, 종료5초. 조회8건·논리2MiB / 전송8건·실제4MiB가 기본. 아래 시각 경계는 `advance()`로 이동한다. |
| adapter [adapter:76](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitTradingClusterAdapterTest.kt#L76) · [adapter:677](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitTradingClusterAdapterTest.kt#L677) · [adapter:711](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitTradingClusterAdapterTest.kt#L711) | 실제 역할 owner와 실제 codec/callback. buyer QUOTE1,000, BASE/QUOTE scale0, 수수료0, 기본 BUY1@100. callback 확정 시각 T=`1,793,664,000,000ms`(2026-11-03T00:00:00Z). Mockito Cluster·ClientSession·snapshot publication/Image가 통신·Archive를 대신한다. command당 모의 logPosition +64, frame 앞32byte를 두고 callback에 본문 offset32를 준다. 따라서 실제 quorum·force·Archive 재생 증거는 아니다. |
| startup [startup:44](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitTradingRoleStartupTest.kt#L44) · [startup:352](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitTradingRoleStartupTest.kt#L352) · [startup:377](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitTradingRoleStartupTest.kt#L377) | `@TempDir` 아래 root와 실제 설정 객체·identity bytes를 사용한다. 새 3개는 설정 단계만 호출하며 실제 노드/Driver를 시작하지 않는다. 경로 미생성 assertion을 실제 네트워크 기동 성공으로 넓히지 않는다. |

### codec — 원문을 복원하거나 잘못된 입력을 거절한다 (모두 U)

| 사례·소스 / 계약 | Given → When | Then: 실제 assertion·검증 경계 |
| --- | --- | --- |
| C01 maker 원본 보존 [codec:73](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/SplitResultDeliveryCodecTest.kt#L73) / R2 | maker1명의 SELL1@100과 BUY2@100이 체결1·잔량1을 만드는 결과. correlation91로 encode→decode→재encode. | schema89·body길이 일치, 결과 전체 equality·resultId 동일, maker 최초 원본은 taker와 다름, effect before 수량[2,1], 재encode bytes 동일. wire에 파생 result_id 필드가 없음. 금융 정산은 실행하지 않는다. |
| C02 명령·응답 선택값 [codec:99](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/SplitResultDeliveryCodecTest.kt#L99) / R2,R24 | 결과3이 active cap 최종 거절, engine5→5·사건0·원본 수량 유지. Describe/Apply/Progress/RecordAck(through MAX)를 각각 왕복. ACK·expected·issued MAX 응답 및 전부 null인 응답 왕복. | 각 명령 envelope·응답 전체 equality. 선택 필드가 없을 때도 그대로 복원한다. 거절의 예약 반환은 검사하지 않는다. |
| C03 최대 식별자·정수 [codec:129](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/SplitResultDeliveryCodecTest.kt#L129) / R2,R24 | UTF-8 256byte 이름(`한`85+a), 256byte 자산명, 음수 비트 lineage, 정책/epoch/config/result/입력 순번 MAX, engine MAX→MAX의 최종 거절. | 파생 resultId는 256byte 초과임을 먼저 확인하고 결과 전체 왕복 equality. 마지막 순번의 worker cursor 처리는 W17에서 다룬다. 실제 도메인 MAX 발급·정산은 기존 [ReservedMatchingStateTest:508](../domain-trading/src/test/kotlin/com/exchange/core/trading/ReservedMatchingStateTest.kt#L508) / [FundsResultRecoveryTest:308](../domain-trading/src/test/kotlin/com/exchange/core/trading/FundsResultRecoveryTest.kt#L308) 회귀에서 별도로 확인한다. |
| C04 전송 상계 [codec:167](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/SplitResultDeliveryCodecTest.kt#L167) / R2,R3,R23 | descriptor 계약과 기본 논리1,048,576byte/사건1,024, 최대 이름 원본을 준비. 상계 계산 및 fixture encode. | 기본 상계1,377,824byte < frame MAX, Long.MAX 논리 크기는 산술 예외, 사건16,385는 거절. 원본 charge=293+12문자열 UTF-8 합을 독립 산식으로 대조하고 예시 wire≤상계. 모든 실제 네트워크 메시지를 보낸 증거는 아니다. |
| C05 정상 큰 결과 [codec:201](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/SplitResultDeliveryCodecTest.kt#L201) / R3 | 수수료0 실제 매칭 owner. 최대 사용자 이름으로 논리상한 안에 들어갈 maker 수 n을 이진탐색. SELL1 maker를 실제 적용하고 각 결과 ACK 회수 후 BUY(n+1) 적용. | 매 입력 APPLIED, 최종 논리1,040,000~1,048,576byte, wire>32KiB·상계 이내, 전체 결과 왕복 equality. n은 공급자에서 계산되며 고정 사전 개수가 아니다. codec 안의 검사로 실제 Aeron 도달을 주장하지 않는다. |
| C06 누락·미지 중첩필드 [codec:245](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/SplitResultDeliveryCodecTest.kt#L245) · [codec:525](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/SplitResultDeliveryCodecTest.kt#L525) / R2,R18 | `result(2)`의 **실제 채워진 필드**를 재귀 순회. reason/released_amount를 제외한 필드를 하나씩 제거하고 각 중첩 Message에 unknown field127을 하나씩 추가. | 생성한 모든 변형이 AdmissionMessageException. optional field를 무조건 필수라고 가정한 검사가 아니다. 비어 있는 모든 가능한 protobuf 필드 조합을 전수한 것은 아니다. |
| C07 모순·미지 enum [codec:256](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/SplitResultDeliveryCodecTest.kt#L256) / R2,R18,R24 | 유효 result2를 한 항목씩 변조: format2, result0/100, count+1, outcome0/99, engineBefore -1, direction99, funds lineage 누락, ordinal8, disposition99, maker origin을 taker로 교체. ACK -1; 응답 role누락·expected0·issued-1·미지 lifecycle·잘못된 SHA. | 나열한 변형 전부 AdmissionMessageException. 실제 owner의 상태 무변경은 이 codec 테스트가 관측하지 않는다. |
| C08 engine 산술 경계 [codec:302](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/SplitResultDeliveryCodecTest.kt#L302) / R2,R24 | 사건2건이 있는 결과의 before/after를 MAX/MAX로 바꿈. 별도 정상 경계는 before MAX-2, 사건 MAX-1·MAX, after MAX. | 넘침 입력은 AdmissionMessageException. 정확한 MAX는 after·사건 순번 목록 보존, 재encode bytes 동일. |
| C09 frame 정확한 경계 [codec:341](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/SplitResultDeliveryCodecTest.kt#L341) · [boundary:66](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/SplitResultDeliveryBoundaryFixture.kt#L66) / R3 | boundary helper가 여러 maker 중 첫 두 maker의 사용자 문자열 길이와 correlation varint 폭을 조정해 **2,097,120byte**와 그 +1byte의 전체 결과 frame을 만든다. | MAX는 decode 결과·correlation 전체 일치와 재encode bytes 동일. MAX+1은 decode AdmissionMessageException, encode IllegalArgumentException. 이 helper는 기본 논리1MiB producer 수용한도와 별개의 wire 경계 fixture다. 정상 금융 수용 사례가 아니다. |
| C10 header·범위 [codec:358](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/SplitResultDeliveryCodecTest.kt#L358) / R2 | Describe frame의 header offset0/2/4/6/8 bit를 각각 바꿈. 1byte 절단/추가, 음수 offset, MAX+1 배열, 앞뒤 padding, command를 reply decoder에 제출. | 변조·범위·종류 오류 모두 AdmissionMessageException. offset4의 정확한 본문 구간은 원본과 같은 envelope. |
| C11 중복·반복량 방어 [codec:374](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/SplitResultDeliveryCodecTest.kt#L374) / R2,R18 | command body를 두 번 붙임. 실제 Entered 사건을 MAX_RESULT_EVENTS+1(16,385)개 반복. 응답 code257글자. | 모두 AdmissionMessageException. 실제 반복 사건은 빈 Message가 아니다. assertion은 거절을 관측하며 parser 할당량·할당 시점은 계측하지 않는다. |

### client — 전송 접수와 완료를 구분한다 (모두 U)

| 사례·소스 / 계약 | Given → When | Then: 실제 assertion·검증 경계 |
| --- | --- | --- |
| L01 맞는 correlation만 완료 [client:18](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitResultDeliveryClientTest.kt#L18) / R11 | Factory가 틀린 id 다음 맞는 id의 DESCRIBED 응답을 poll에서 준다. 요청 후 use 종료. | DESCRIBED 반환, offer1·poll>0·원래 endpoint 전달, 시도 객체 닫힘, 최종 connection 닫힘. 대역이므로 OS 자원 누수 전수를 뜻하지 않는다. |
| L02 응답 없는 접수 [client:31](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitResultDeliveryClientTest.kt#L31) / R15 | offer는1로 수용하지만 reply=false. timeout250ms 요청. | UnconfirmedException의 transportAccepted=true, offer1, connection 닫힘. 성공 응답을 꾸미지 않는다. 서버가 실제로 정산했는지는 알 수 없다. |
| L03 미완료 연결 [client:42](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitResultDeliveryClientTest.kt#L42) / R15 | connect=false로 poll이 계속 null, timeout5ms 생성. | IllegalStateException, 시도 닫힘, 전달 driver 경로 동일, offer0. 새 Driver를 만들지 않았다는 OS 계측은 없고 대역에 전달된 경로를 확인한다. |
| L04 정리 자체의 오류 [client:51](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitResultDeliveryClientTest.kt#L51) / R15 | 연결은 열리지만 attempt.close가 실패하도록 제어. client 생성. | IllegalStateException, attemptClosed=true이며 이미 연 connection도 closed=true. |

### worker — ACK를 자기 로그로 돌려보내기까지 (모두 U)

표의 `확인 n`은 실제 매칭 도메인 `fundsSettledThrough=n` assertion이다. 응답 override를 쓰는 행은 금융 정산을 대신한 제어 응답임을 공통 준비와 함께 읽는다.

| 사례·소스 / 계약 | Given → When(단계) | Then: 해당 단계에서 실제 확인 |
| --- | --- | --- |
| W01 무체결 후 실제 체결 [worker:61](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MatchingResultDeliveryAgentTest.kt#L61) / R4,R5 | SELL1@100 → 15cycle. 이어 BUY1@100, 빈 조회 backoff1ms 이동 →15cycle. | ① seller A가용4/hold1, 대기1, 결과0. ② buyer Q900/A1, seller Q100/A4, 관련 hold0, 확인2·결과0·주문장0·failure없음. seller 초기는 A5다. |
| W02 앞 응답 없이 pipeline [worker:85](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MatchingResultDeliveryAgentTest.kt#L85) / R11,R23 | BUY1@1 2개. 자금 응답만 보류→8cycle. 누적 ACK2 응답 **마지막 하나만** release→10cycle. 늦은 첫 correlation ACK1 주입→2cycle. | ① 실제 Apply 순번[1,2], source 결과2·ACK 제출0. ② 기록 ACK목록[2], 확인2·결과0. ③ ACK목록 여전히[2], failure없음. |
| W03 자금 응답 유실 재전송 [worker:109](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MatchingResultDeliveryAgentTest.kt#L109) / R8,R15 | BUY1@1. 자금은 정산하고 응답만 hold. 8cycle 후 상태 저장→3초 이동·1cycle→hold 해제·50ms 이동·16cycle. | ① timeout 뒤 모든 연결 closed. ② Apply 원문 목록 정확히[first,first], 최초 receipt 시각123ms 동일, buyer Q999/hold1, 확인1·failure없음. 대기 주문 예약은 반환하지 않는다. 새 확정시각을 준 중복은 A02가 검사한다. |
| W04 매칭 기록 응답만 유실 [worker:134](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MatchingResultDeliveryAgentTest.kt#L134) / R11,R19 | ACK 도메인 기록은 수행하되 응답 drop. 8cycle→3초 timeout→drop 해제·50ms 재연결·16cycle. | 첫 단계 확인1. 복구 뒤 Apply 총1·결과0·from1 source 조회≥2·failure없음. 이미 기록한 source prefix를 다시 읽어 payload를 되살리지 않는다. |
| W05 자금 PRUNED 처리 [worker:154](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MatchingResultDeliveryAgentTest.kt#L154) / R9 | 실제 funds 적용 대신 PRUNED+typed ACK1을 제어 응답으로 제공. | 확인1·결과0·failure없음. **실제 receipt FIFO 축출·금융 재정산0을 검사하는 사례는 아니다.** |
| W06 낮은 ACK 두 경로 [worker:167](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MatchingResultDeliveryAgentTest.kt#L167) / R11 | 두 독립 fixture: ① result1에 funds APPLIED+ACK0, ② 제출 ACK1에 matching ACKNOWLEDGED+ACK0. | 두 경우 failure 존재·확인0·결과1 보존. 둘째는 recorded 응답 override가 실제 matching 적용도 대체한다. |
| W07 잘못된 ACK 세 변형 [worker:188](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MatchingResultDeliveryAgentTest.kt#L188) / R18,R24 | funds 성공 응답의 ACK를 각각 epoch2 / ordinal1 / 미래 through2로 변조. | 세 경우 failure 존재·확인0·결과1·기록 ACK목록 empty. 각 경우 실제 금융값은 별도 비교하지 않는다. |
| W08 오래된 조회보다 앞선 ACK [worker:210](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MatchingResultDeliveryAgentTest.kt#L210) / R11,R23 | result1 조회 future를 지연하고 old issued1 결과를 보관. result2를 만든 뒤 old future 완료. funds가 ACK2를 응답. | 확인2·bytes1의 issued 재확인 조회 존재·failure없음. 오래된 관측만 보고 정상 누적 ACK2를 거절하지 않는다. |
| W09 재확인 중 더 높은 ACK [worker:229](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MatchingResultDeliveryAgentTest.kt#L229) / R11,R23 | result1 조회 완료→아직 result2가 없던 empty 관측 준비→result2 생성·ACK2 release. issued2 재확인 future를 지연→result3 생성·같은 correlation ACK3 주입·2ms 이동. 이후 issued2 future 완료. | ① issued 검증 전에는 ACK제출0·확인0·결과3. ② bytes1 재조회 정확히2회, 최종 기록 ACK목록[3]·확인3·결과0·failure없음. 단순 마지막 상태만으로 앞 단계를 설명하지 않는다. |
| W10 앞 결과를 요구하는 GAP [worker:276](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MatchingResultDeliveryAgentTest.kt#L276) / R12 | BUY 2개, 첫 funds 응답만 GAP expected1, 그 뒤 실제 도메인 처리. 25cycle. | 확인2·result1 Apply≥2·funds receipt2·failure없음. 모든 offer 순서 목록의 완전 일치는 검사하지 않는다. |
| W11 이미 회수한 구간의 GAP 두 경우 [worker:300](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MatchingResultDeliveryAgentTest.kt#L300) / R12,R22 | result1은 실제 자금 EPOCH 정산·matching ACK1 회수 후 result2 생성. 첫 응답 GAP1. progress 대역을 ACK1 또는 뒤처진 ACK0으로 제공. | 공통 progress조회1. ACK1이면 확인2·결과0·failure없음. ACK0이면 failure 존재·확인1·결과1 보존. 잔고·예약 전체 equality assertion은 없다. |
| W12 backpressure 중 제어 경로 [worker:336](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MatchingResultDeliveryAgentTest.kt#L336) / R15,R17,R23 | 전송창2, 결과4개·funds응답 hold→10cycle. Apply offer를 BACK_PRESSURED로 하고 응답 release·500ms 이동. ACK delay의2ms-1ns→+1ns로 진행. 이후 backpressure 해제, 첫 응답 NOT_READY,20ms 이동 후 재시도. | ① 먼저 Apply2. ② 모든 연결 poll·keepalive>0. ACK기한 전 확인0, 정확한 기한 뒤 확인2·결과2. ③ 최종 확인4·failure없음. 실제 네트워크 포화·시간벽 계측은 아니다. |
| W13 byte 창 먼저 포화 [worker:377](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MatchingResultDeliveryAgentTest.kt#L377) / R23 | producer 단일4,096byte/사건1, 전송창7,000byte, BUY 결과8개·응답 hold. | ① Apply1~7건, 그 frame 실제 encode 합+제어 reserve≤7,000, source 결과8·확인0. ② 응답 release 뒤 확인8·결과0·failure없음. private future·준비 버퍼 전체 실메모리를 계측하는 assertion은 아니다. |
| W14 ACK offer만 차단 [worker:408](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MatchingResultDeliveryAgentTest.kt#L408) / R17,R23 | 전송창1, 결과4개. matching ACK offer만 BACK_PRESSURED→25cycle. 해제·2ms 이동. | ① Apply4·funds 누적정산4, source 결과4·확인0. ② 확인4·결과0·failure없음. 응답/ACK 경로가 신규 결과창과 별도로 진행한다. |
| W15 세대 변경과 늦은 응답 [worker:428](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MatchingResultDeliveryAgentTest.kt#L428) / R19 | result1 조회 future 지연→source generation=null→3cycle. 다시 generation2·15cycle. 옛 funds connection으로 미래 ACK2 응답 callback 주입. | ① future cancelled·모든 연결 closed·Apply0. ② 확인1. ③ 옛 응답을 준 직후 failure없음. 팔로워 실제 선출·네트워크는 대역이다. |
| W16 음수 시계·재연결 경계 [worker:451](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MatchingResultDeliveryAgentTest.kt#L451) / R19 | now=-1,000,000,000,000ns, NOT_CONNECTED로 연결 실패. 재연결50ms-1ns 이동→1cycle, +1ns·연결 정상화. | 기한 전 새 연결 수 불변, 정확한 경계 뒤 확인1·failure없음. JVM nanoTime 절댓값의 양수 가정에 의존하지 않는다. |
| W17 마지막 Long cursor [worker:471](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MatchingResultDeliveryAgentTest.kt#L471) / R24 | source override: from1은 PRUNED·확인 MAX-1·issued MAX·next MAX. fromMAX는 마지막 결과1개·next=null. 양쪽 ACK MAX를 제어 응답. | source 조회 from 목록 정확히[1,MAX], Apply1·failure없음. 다른 from은 fixture가 error로 실패한다. 실제 도메인에서 MAX까지 발급·정산한 검사는 아니다. |
| W18 진행 불가·오배치 [worker:526](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MatchingResultDeliveryAgentTest.kt#L526) / R18,R23 | ① BATCH_TOO_SMALL(required3,000,000) 조회. ② 다른 fixture의 peer topology를 f×64로 변조. | ① failure 존재·Apply0. ② IllegalArgumentException·결과1 보존·Apply0. 서로 다른 실패 경계를 한 fixture로 섞지 않는다. |
| W19 설정 불일치 [worker:548](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MatchingResultDeliveryAgentTest.kt#L548) / R1,R23 | worker에 config와 다른 readEntries1을 전달하거나 readBytes1 / inFlightBytes1을 호환 검사. | 모두 IllegalArgumentException, 전송 연결 생성0. 작은 한도가 실제 큰 결과를 전송한 뒤 실패하는 사례는 아니다. |
| W20 연결의 단일 작업 스레드 [worker:576](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MatchingResultDeliveryAgentTest.kt#L576) / R19 | 실제 worker thread 시작. Apply offer latch를 최대3초 기다리고 finally close. | latch 도달, begin/poll/offer/keepalive/close에서 기록한 owner thread 집합1개·이름 matching-result-delivery·종료 후 살아있지 않음, 모든 connection closed·failure없음. 실제 Aeron thread 안전 전수 검사는 아니다. |

### adapter — 실제 callback이 정산과 회수를 분리한다 (새 6개, 모두 U)

| 사례·소스 / 계약 | Given → When(단계) | Then: 해당 단계에서 실제 확인 |
| --- | --- | --- |
| A01 결과1이 먼저 도착 [adapter:352](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitTradingClusterAdapterTest.kt#L352) / R4,R13 | buyer BUY1@100 승인→matching 대기 결과1 생성. 명시 주문 적용 ACK보다 결과를 funds callback에 먼저 전달. 이후 그 정산 ACK를 matching callback에 기록. 마지막에 늦은 명시 주문 ACK 전달. | ① APPLIED+through1·지문 일치, buyer Q900/hold100·executed1·dispatch0, matching 결과1 유지. ② ACKNOWLEDGED·issued1·through1·결과0, 대기/active1 유지, buyer 예약 그대로. ③ 늦은 ACK는 ACKNOWLEDGED·executed1 유지. late ACK 뒤 전체 금융 equality는 별도 비교하지 않는다. |
| A02 체결 정산·새 시각 중복 [adapter:411](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitTradingClusterAdapterTest.kt#L411) / R5,R8 | seller BASE1을 추가 준비·SELL1@100 결과1 정산. buyer BUY1@100 결과2를 T에 정산. 같은 결과를 T+1,000ms에 재전달. | 최초 APPLIED: buyer Q900/BASE1·seller Q100/BASE0, 관련 hold0. 중복 DUPLICATE, 전후 balances·feeRevenue·receipts 전체 동일, live0, 마지막 receipt appliedAt=T, resultThrough2. 최초 시각을 새 요청시각으로 바꾸지 않는다. |
| A03 조회 큐·세대 수명 [adapter:471](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitTradingClusterAdapterTest.kt#L471) / R19,R23 | matching 초기화 후 future4개 대기,5번째 요청. follower→leader 전환, 옛 세대/entries9 요청. 현재 future를 두고 snapshot·그 뒤 terminate. | 처음4개 미완료·5번째 예외. follower에서 대기4개 예외·세대null. leader 새 세대 증가·옛세대와 초과건수 예외. snapshot 중 세대null·background0·future예외, 후 새 세대 증가·조회BATCH. 종료 때 대기future예외·세대null. |
| A04 mode·역할·GAP·충돌 [adapter:503](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitTradingClusterAdapterTest.kt#L503) / R1,R12,R18 | 기존 모드 Describe / 새 funds Describe·미초기화 Progress. BUY 두 개 예약(Q800/hold200), matching 결과2를 먼저 funds로 전달→결과1→같은 순번 다른 유효 engine/events 내용→원래 결과2. | 기존 UNSUPPORTED_MODE, 새 DESCRIBED/NOT_READY, matching Apply는 WRONG_ROLE. GAP expected1·ACK없음·executed0·Q800/hold200. config99 Progress INVALID_STREAM. result1 APPLIED 뒤 충돌 CONTENT_CONFLICT·ACK없음·executed1 유지. result2는 APPLIED로 재개. GAP 전후 receipt·dispatch 전체 equality는 없음. |
| A05 snapshot+tail 시각·회수 [adapter:569](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitTradingClusterAdapterTest.kt#L569) / R8,R19,R21 | 대기 결과1이 남은 funds/matching snapshot bytes 저장. follower 역할로 funds snapshot 복원→원래 T의 결과 tail replay→leader에서 T+999ms 중복. matching snapshot 복원→그 ACK tail replay→leader→회수 후 snapshot 재복원. | follower funds replay 응답0·receipt T, 이후 DUPLICATE. matching onStart만으로 전송세대 없음·결과1, ACK replay 응답0·결과0, leader 조회PRUNED·대기1. 회수 snapshot 복원도 결과0·확인1. **mock Image 재생이며 실제 Archive 복원은 D01**이다. |
| A06 malformed 산술 넘침 [adapter:624](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitTradingClusterAdapterTest.kt#L624) / R2,R18,R24 | BUY1 예약·matching 결과1 원문 준비. wire engineBefore/After만 MAX/MAX로 바꿔 funds callback에 직접 투입. 이후 정상 원문 투입. | malformedMessages1·started/ready 유지·failure없음·응답0·executed0·buyer Q900/hold100·matching 결과1. 정상 후 APPLIED·executed1. 입력 산술 오류가 owner 종료/성공 ACK가 되지 않음을 관측한다. |

목록은 실제 diff와 `HEAD`의 메서드 이름을 대조했다. adapter는 기존 8개 + 신규 6개 = 현재 14개다. 마지막 산술 넘침 사례까지 신규 6개에 포함되며 units7 XML에도 존재한다.

### startup — 별도 모드와 한도 지문 (새 3개, 모두 U)

| 사례·소스 / 계약 | Given → When | Then: 실제 assertion·한계 |
| --- | --- | --- |
| S01 별도 실행 identity [startup:281](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitTradingRoleStartupTest.kt#L281) / R1,R27 | 기본 config와 결과 inFlight7 config. ORDER_DELIVERY_DEV와 RESULT_SETTLEMENT_DEV 설정 생성. | 기존 topology SHA 같음, result SHA 다름. 결과 작업 enabled false/true, 두 mode identity bytes 다름, 새 mode에만 result SHA 포함. 한도 변경 identity도 다름. root 미생성. |
| S02 한 건·제어공간 부족 [startup:300](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitTradingRoleStartupTest.kt#L300) / R1,R23 | result maxReadBytes1 또는 maxInFlightBytes1,024를 각각 설정. 기존 mode 생성 후 새 mode 생성 시도. | 기존 mode 결과작업 off, 새 mode IllegalArgumentException, 각 root 미생성. 실제 파일·Driver를 만든 뒤 정리한 검사는 아니다. |
| S03 모든 한도 지문 [startup:317](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitTradingRoleStartupTest.kt#L317) / R1,R23 | 기본값에서 read건수/bytes·inFlight건수/bytes·ACK batch/delay·attempt/reconnect·busy최소/최대·keepalive·shutdown **12종을 각각** 변경. | 각 기존 SHA 유지·각 result SHA는 기본과 다름,12개 결과 SHA도 서로 고유. 설정마다 실제 시간/전송을 실행한 검사는 아니다. |

### Docker — 실제 두 클러스터를 통과하는 여섯 시나리오 (D: 실행 근거 확인)

공통 준비는 [harness:82](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerFundsOrderDeliveryTest.kt#L82) 와 [tool:480](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MatchingResultVerificationTool.kt#L480) 의 `prepare`다. 매 시나리오마다 새 기록·동결 이미지로 funds0~2/matching0~2의 독립 JVM·Driver·기록 볼륨을 기동한다. market은 UTF-8 256byte 코인명, X/USDT scale0, 구매자/판매자 이름도 각각 이모지64개(256byte)다. buyer Q1,000, seller X는 baseline/fee=1, partial/cap/active=2, large=1,024이다. large의 buyer Q는1,000,000. 준비금 payload 시각은 2026-11-04T00:00:00Z지만 결과 receipt 시각은 각 funds **실제 확정 callback timestamp**이며 미리 그 값으로 고정하지 않는다. epoch1/config1. fee만 maker1%/taker2%, 나머지0이다. 모든 `admit`는 최초 응답 ACCEPTED를 helper가 확인한다.

`expect()`의 [docker:372](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerMatchingResultDeliveryTest.kt#L372) 는 6개 상태 파일 전부 initialized인지 확인하고, **자금 세 사본 각각** 잔고·hold·fee·활성/대기/잔량·dispatch bytes/count0·issued/matchingApplied/executed/result prefix·정산 trade 수를 고정 상수에 비교한다. **매칭 세 사본 각각** book/수량/active·engine·applied/issued/confirmed prefix·남은 result count를 비교하고, 회수가 끝난 경우 resultBytes0도 확인한다. `settlementTrades`는 보관 receipt의 사건 수 합이며 영구 전체 거래 내역 조회가 아니다. 금액 정답은 수기 산식이며 응답을 복사해 만들지 않는다. 아래 금액은 Q가용/hold, X가용/hold의 순서다.

| 사례·소스 / 계약 | Given → 단계별 When | Then: 그 단계의 직접 관측·제한 |
| --- | --- | --- |
| D01 응답 유실·전체 로그·snapshot tail [docker:28](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerMatchingResultDeliveryTest.kt#L28) / R4,R5,R8,R10,R11,R13,R20,R21,R25,R26 | baseline. 주문 적용 ACK와 funds 결과 응답을 보류→SELL1@100. | 상세 단계표를 아래에 둔다. 마지막 결과만으로 모든 중간 단계를 설명하지 않는다. |
| D02 두 maker 가격·잔량 [docker:107](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerMatchingResultDeliveryTest.kt#L107) / R6,R25 | seller X2. SELL1@90 결과1 확인·회수→SELL1@95 결과2 확인·회수→BUY3@100. | 첫 대기: seller X1/hold1, book1. 둘째: X0/hold2, book2. 최종 buyer Q715/hold100·X2, seller Q185·X0/hold0, live/book/잔량1·engine5·trade2, 양쪽 prefix3·결과0. Q715=1,000-90-95-100. |
| D03 수수료·ACK차단 중 snapshot [docker:121](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerMatchingResultDeliveryTest.kt#L121) / R7,R17,R25 | maker1%/taker2%. SELL1@100 정산→정산 ACK offer 보류→BUY1@100. 두 역할 snapshot 요청 후 해제. | 차단 중 funds buyer Q898/X1·seller Q99/X0·feeQ3·hold0·trade1, funds prefix2이지만 matching 확인1·결과1. 이 상태에서 두 역할 모든 사본 snapshotCount>0. 해제 후 matching 확인2·결과0. Q898+99+3=1,000을 각 고정값으로 확인한다. |
| D04 결과 한 칸 포화 [docker:139](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerMatchingResultDeliveryTest.kt#L139) / R15,R16,R25 | cap=결과1개. 결과 Apply offer 보류→SELL1@100→SELL1@100 두 번째 승인. | 첫 단계 matching 모든 사본 applied1·result1. BUSY 관찰 파일 존재 후 funds 모든 사본 issued2·seller hold2·resultThrough0, matching applied1·result1. 해제 후 funds/matching prefix2·hold2·book2·engine2·trade0·결과0. **BUSY 파일 내용의 code를 이 메서드에서 다시 assert하지 않고**, wrapper가 BUSY 응답 때만 이 파일을 쓴다([tool:235](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MatchingResultVerificationTool.kt#L235)). |
| D05 active cap 최종 거절 [docker:169](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerMatchingResultDeliveryTest.kt#L169) / R14,R25 | active=1, seller X2. SELL1@400 대기→결과 offer 보류→BUY1@300(최초 ACCEPTED)→예약 중간 상태 대기→offer 해제→거절 결과2 재전송. | 첫 seller X1/hold1·book1. funds 세 사본 buyer Q700/hold300·resultThrough1/issued2·seller hold1, matching 세 사본 resultIssued2/확인1·결과1·engine1. 해제 후 buyer Q1,000/hold0·X0, 기존 seller X1/hold1·live/book1·engine1·trade0·fee0, 양쪽 prefix2·결과0. 재전송은 정확히 DUPLICATE+through2, 같은 최종값 유지. 최종 재실행 JSON의 네 checkpoint와 XML 성공으로 이 단계들을 확인했다. |
| D06 큰 정상 결과·FIFO 회수·native frame 경계 [docker:205](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerMatchingResultDeliveryTest.kt#L205) / R3,R9,R25 | large. 실제 SELL1 원본을 읽어 논리1MiB 이내 최대 maker n(2~1,023)을 계산→실제 maker n개 승인·결과 회수→BUY n@100. 이후 원래 결과1 PRUNED 재전송, 별도 MAX/MAX+1 frame 도구 호출. | 상세 단계표를 아래에 둔다. n은 결과 원문 크기에 따라 계산되며 고정 n으로 실행됐다고 미리 쓰지 않는다. |

D01의 여러 행동은 다음 순서이며, 각 `expect`는 위 6사본 공통 비교를 한다.

| 단계·관측 위치 | When | Then: 해당 지점의 값 |
| --- | --- | --- |
| 1 [docker:30](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerMatchingResultDeliveryTest.kt#L30) | 주문 ACK 응답·funds 정산 응답을 listener에서 버리고 SELL1@100. | funds resultThrough1·dispatch0, seller X0/hold1, buyer Q1,000; matching issued1/확인0·result1·book1·engine1·trade0. 즉 **정산 응답이 없어도 자금 정산은 확정**했다. |
| 2 [docker:37](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerMatchingResultDeliveryTest.kt#L37) | ACK 기록 offer도 막고 funds 응답 보류 해제. 재전송 DUPLICATE+through1을 같은 bootId 관찰 파일로 확인. `replay-result1`을 직접 수행. | replay helper는 DUPLICATE/PRUNED 응답을 요구한다. 6사본 값은 단계1 그대로이고 funds 세 사본 receipt 요약(sequence/appliedAt/event/effect/logicalBytes)이 최초 funds0 요약과 동일. timestamp 숫자를 사전 고정하지 않고 최초값을 보존하는지 비교한다. |
| 3 [docker:52](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerMatchingResultDeliveryTest.kt#L52) | matching ACK 기록 offer 해제→명시 주문 ACK 직접 재전달→주문 ACK listener 해제→같은 주문 원문 재전달. | matching 확인1·result bytes/count0, book1·seller hold1 유지. 늦은 주문 ACK는 ACKNOWLEDGED, 원문 재전달은 DUPLICATE/PRUNED를 helper가 요구한다. |
| 4 [docker:59](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerMatchingResultDeliveryTest.kt#L59) | matching ACK 기록을 다시 막고 BUY1@100→전체6노드 stop/같은 기록 재시작(snapshot 없음)→result2 직접 재전달. | 전후 buyer Q900/X1·seller Q100/X0·hold/live/book0·trade1·engine2·funds prefix2. matching 확인1·result2만1개 보존. 복원본 모두 restoredSnapshot=false·bootId 변경·같은 receipt 요약. 재전달은 DUPLICATE/PRUNED, 금액 그대로. |
| 5 [docker:67](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerMatchingResultDeliveryTest.kt#L67) | matching 기록 응답만 보류하고 ACK offer 해제→ACK 직접 재전달→응답 보류 해제→다시 전체 로그 재시작. | matching 확인2·result bytes/count0, 금액·engine·trade 불변. ACK 직접 재전달은 ACKNOWLEDGED. 전체 로그 복원도 같은 완료값·restoredSnapshot=false·receipt 요약 불변. |
| 6 [docker:77](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerMatchingResultDeliveryTest.kt#L77) | 양 역할 snapshot→원래 buyer가 취득한 X1을 SELL1@100로 예약. | 모든 사본 snapshotCount>0·각자 snapshotPosition>0. 새 결과3 확인·회수, buyer Q900·X0/hold1, seller Q100·X0, live/book1·engine3·trade1. 준비금을 추가하지 않는다. |
| 7 [docker:87](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerMatchingResultDeliveryTest.kt#L87) | matching ACK offer 보류→원래 seller가 BUY1@100→snapshot+자기 tail에서 전체 재시작→result4 재전달→ACK offer 해제. | buyer Q1,000/X0, seller Q0/X1·hold0, trade2·engine4·funds prefix4, matching 확인3·result4 보존. **각 사본 자기 logPosition>자기 snapshotPosition**을 먼저 확인. 첫 복원은 restoredSnapshot=true·receipt 요약 불변, 이후 확인4·결과0. |
| 8 [docker:102](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerMatchingResultDeliveryTest.kt#L102) | 같은 snapshot과 ACK가 추가된 자기 tail로 다시 전체 재시작. | 모든 사본 restoredSnapshot=true, 같은 최종 금액·trade2·engine4·prefix4·result bytes/count0. 서로 다른 클러스터의 logPosition이 같아야 한다고 요구하지 않는다. |

D06은 **기본 논리 한도에 근접한 정상 정산**, **receipt FIFO 회수 뒤 재전송**, **native 전송 한계만 도달하는 별도 입력**을 나눈다. 최종 실제 실행에서는 n=366, 논리1,047,281byte·wire971,606byte·사건366·효과367이었다. buyer Q963,400/X366, seller Q36,600/X658, hold0을 대조한 기록이다. 같은 실행에서 FIFO 회수·PRUNED와 native 경계 단계도 완료했다.

| 단계·소스 | Given/When | Then: 직접 관측 |
| --- | --- | --- |
| 1 [docker:207](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerMatchingResultDeliveryTest.kt#L207) · [docker:456](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerMatchingResultDeliveryTest.kt#L456) | 실제 maker 첫 원문을 보존, 크기 계산 helper가 n을 선택. `makers n`은 seller sequence1부터 전부 실제 요청(첫 요청은 같은 token 재요청)한다. | n∈2..1,023. 양쪽 prefix n·matching book n 뒤 taker를 보낸다. helper는 별도 합성 원문으로 **크기만** 계산하며 실제 전송·주문·정산에는 실제 승인 원본을 쓴다. |
| 2 [docker:238](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerMatchingResultDeliveryTest.kt#L238) | BUY n@100, 모든 결과 전달·회수 기다림. | buyer Q1,000,000-100n·X n, seller Q100n·X1,024-n, hold/live/book0, engine2n·trade n, 양쪽 prefix n+1·결과0. 보존된 실제 wire는32KiB 초과·MAX이하, 논리(1MiB-4,096)~1MiB, event n·effect n+1. funds 각 receipt의 event/effect/logicalBytes도 실제 원문과 일치. |
| 3 [docker:280](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerMatchingResultDeliveryTest.kt#L280) | funds 세 사본 retiredThrough≥1 확인 후 원래 result1을 실제 다시 전송. | 최종 관측은 retiredThrough162·PRUNED+through367. 직접 보고서 상태를 전후 대조해6사본 업무 JSON 전체 동일, funds0/1/2 각각 logPosition3,089,024→3,093,984, matching0/1/2는840,352 유지였다. 금융/예약/prefix·주문장·결과 상태는 바뀌지 않고 자금 로그에 재요청이 실제 반영됐다. |
| 4 [tool:417](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MatchingResultVerificationTool.kt#L417) · [docker:314](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerMatchingResultDeliveryTest.kt#L314) | **다른 stream**의 boundary 합성 전체 원문을 실제 Aeron 연결에 MAX=2,097,120byte로 offer하고 poll. MAX+1byte도 같은 연결에 offer. | MAX의 positive offerPosition>0·응답 role FUNDS/ACK없음·code INVALID_STREAM 또는 INVALID_RESULT·appliedLogPosition>0. 즉 **native 전송은 도달했지만 금융 입력은 거절**했다. MAX+1은 IllegalArgumentException. 최대 frame의 정상 금융 수용을 검사한 것이 아니다. |
| 5 [docker:329](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerMatchingResultDeliveryTest.kt#L329) | native 경계 전후 6개 상태 비교. | role 하위 업무 JSON 전체 동일, funds 세 사본 logPosition은 이전보다 증가. MAX frame callback이 로그에 도달했으며 금융값·예약·회수 상태를 바꾸지 않음을 관측한다. |

### 실제 도구·보류·복원·정리에서 확인하는 경계

| 소스 | 실제 동작·확인 범위 |
| --- | --- |
| [tool:64](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MatchingResultVerificationTool.kt#L64) · [tool:109](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MatchingResultVerificationTool.kt#L109) · [tool:111](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MatchingResultVerificationTool.kt#L111) | 서비스 decorator는 실제 delegate callback **후** owner 복구상태를 JSON으로 캡처하고, Java default `doBackgroundWork`도 명시 전달한다. 테스트 전용 reflection 관측이지 제품 전체 상태 endpoint가 아니다. |
| [tool:211](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MatchingResultVerificationTool.kt#L211) · [tool:257](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MatchingResultVerificationTool.kt#L257) | 실제 Aeron factory를 감싼다. `hold-matching-acks`/`hold-funds-result-replies`/`hold-record-result-replies`는 **실제 응답을 관측·파일 기록한 뒤 worker에 전달만 하지 않는다**. `hold-results`/`hold-result-records`는 실제 offer 전 BACK_PRESSURED를 반환한다. 프로세스·네트워크 전체 단절로 확대하지 않는다. |
| [harness:249](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerFundsOrderDeliveryTest.kt#L249) | restartResults는6노드 stop·ExitCode0/143·OOM없음, 이전 state/runtime 파일 삭제, recovery=true, bootId 변경 후 기대값을 기다린다. 각 복원 snapshot 사용 여부·funds receipt 요약·manifest hash·동일 기록 볼륨/실행 이미지를 확인한다. 실제 Archive 재생을 사용하는 검사다. |
| [harness:438](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerFundsOrderDeliveryTest.kt#L438) | container6개, 전용 internal bridge1개, 기록 volume6개/재시작 동일, runRoot·Driver 경로6개, 동결 imageId, sync2/2/2, JDK25·자원 제한·지문 일치를 검사한다. PID는 각 상태에 양수인지 확인하며 서로 다른 namespace의 PID 숫자가 모두 다르다고 요구하지 않는다. |
| [harness:530](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerFundsOrderDeliveryTest.kt#L530) · [json:165](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitTradingVerificationTool.kt#L165) · [writer:366](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/BoundedHttpTradingNode.kt#L366) | 원자적 rename 전 임시파일에0644 설정. baseline에서 실제 Linux UID65534가 자기 소유가 아닌 runtime/state/주문ACK JSON 세 파일을 읽어 object 파싱한다. 모든 결과ACK 파일을 다른 UID로 읽는 별도 assertion은 아니다. |
| [harness:187](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerFundsOrderDeliveryTest.kt#L187) · [harness:515](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerFundsOrderDeliveryTest.kt#L515) · [harness:595](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerFundsOrderDeliveryTest.kt#L595) · [harness:739](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerFundsOrderDeliveryTest.kt#L739) | 사전 디스크8GiB/Docker6GiB, 실행8분(정리45초 포함), 기록2GiB·진단16MiB. 결과 단일 보고서4MiB. finally에서 run 전용 compose 자원과 임시 image만 정리하고 자기 label의 container/volume/network가 사라졌는지 확인. 성공 조건은 failures empty **그리고** cleaned. 모든 중간 실패/권한오류 주입을 실제로 수행한 테스트는 아니다. 파일 순회는 삭제된 임시파일(NoSuchFileException)만 허용한다. |

### 명세와 assertion의 판단 범위

읽은 범위에서 빈 테스트·예외를 삼켜 통과시키는 assertion·잘못된 금액 기대값은 발견하지 않았다. U는 이 단위 경계의 기준으로 사용할 근거가 있다. 다음은 남은 관측 한계이며, 해당 R 전체 충족 판정을 이 문서에서 내리지 않는다.

| 계약 | 현재 직접 확인 / 남은 범위 |
| --- | --- |
| R3 | C05가 정상 논리 한도 근접 결과의 codec을 확인, C09가 정확한 frame MAX/MAX+1을 확인. D06 최종 재실행의 실제 완결 정산·native MAX/MAX+1 checkpoint도 확인했다. |
| R9 | W05는 PRUNED 대역으로 worker ACK 처리만 확인. D06 최종 실제 실행에서는 FIFO retiredThrough162·원래 result1의 PRUNED/through367·자금3사본 로그 전진·6사본 업무 상태 전체 동일까지 관측했다. 기존 코어 FIFO 회귀와는 별개의 실제 경계 근거다. |
| R12 | A04는 GAP의 ACK없음·예약 잔고·executed prefix를 확인한다. GAP 직후 receipt·dispatch 전체 불변을 별도로 비교하지 않는다. W10/W11은 재개/불일치 중단 제어를 보완한다. |
| R14 | D05 최종 실제 실행에서 최초 ACCEPTED 뒤 Q700/hold300·기존 seller hold1과 matching 거절 결과1 보존, 이후 Q1,000/hold0·기존 예약/engine/trade/fee 불변, 정확한 DUPLICATE/through2 뒤 같은 최종값을 확인했다. |
| R23 | W13은 실제 frame 합+제어 reserve 한도와 source 보존을 확인하고 A03은 read큐 상한을 확인한다. private 모든 준비/future/응답 버퍼의 합산 실메모리를 직접 계측하지는 않는다. |
| R26 | harness가 정상·실패 경로의 finally와 자원 소유 범위를 구현했으며 baseline은 다른 UID 읽기를 포함한다. 모든 실패 주입·동시 교체 stress를 실행했다고 말하지 않는다. |
| R27,R28 | 새 테스트만으로 기존 integrated/snapshot golden 전체 회귀, 계산실패·반영 중 owner 차단의 모든 금융 불변식을 완료 판정할 수 없다. 아래 §5~6의 기존 코어·선택 회귀 근거에 별도로 연결한다. |

이 절은 대상별 설명과 assertion 대조다. 실제 실행 기록(§5)과 독립 결함 검토(§7)는 별도로 기록한다. 미설명 대상으로 남긴 새 테스트는 없으며 위 직접 관측 한계를 함께 적용한다.

### 설명 기준의 현재 파일 지문

아래 SHA-256은 이 문서 작성 때 읽은 소스 내용의 식별자다. 단위 실행기록 자체의 source manifest는 아니다.

| 파일 | SHA-256 |
| --- | --- |
| SplitResultDeliveryCodecTest.kt | `3c11ef04a7eda5e4e094e8adb66965128194113426c9aed2346bdb1d54d823dc` |
| SplitResultDeliveryBoundaryFixture.kt | `320a2aaf4e12ccf865c4bb2d5697268e8855a1d39d5baca38bf6433dbf11d271` |
| SplitResultDeliveryClientTest.kt | `5b63649b8e7947f6e43b09860437f9aaa1451c2044ffc94bed9f1ce24c21a2d2` |
| MatchingResultDeliveryAgentTest.kt | `18ff18d74dd9e46ba40daf57dac9a0539b132c2b7ac6c86dd255cf3b523eef15` |
| SplitTradingClusterAdapterTest.kt | `b247baa9cdfbba01dc47fa00baa0b7b070873e70407ec8689f5545438576d0b9` |
| SplitTradingRoleStartupTest.kt | `14be576a2998a119d5235a12dcc5d2c908253094e544054be661c21d38b0d0a0` |
| DockerMatchingResultDeliveryTest.kt | `329e0d6c7e41e5228a51b150b8380387738edd1fb9e7c715c1031724ea59c5d0` |
| MatchingResultVerificationTool.kt | `7248c9fd7dbfda9ca28b49d92fa6f18d1ce292e50be49aa84527640560c3eeda` |
| DockerFundsOrderDeliveryTest.kt | `777c1486b4c1d61e288d693429480f8c9fbf7054774dfb441e991e84ae28e8e9` |
| SplitTradingVerificationTool.kt | `7925c4164ed76afcecdd604fedcbd50a8881febbad0aaba6fe820c1560be8932` |
| BoundedHttpTradingNode.kt | `3079c1ce9fb440172b6d0e4633ef6d02d6210f0ae394027ca74100cc09b327c9` |


## 5. 실제 실행 기록

- 첫 컴파일: 제품 코드 컴파일 성공. 새 test helper의 Int 값에 `.size`를 쓴 준비 코드와 기존 trailing-lambda API 바인딩 오류를 수정했다. 테스트 assertion까지 도달한 TDD Red라고 기록하지 않는다.
- 첫 선택 unit 실행(`build/impl169-units3.log`):58개 중4개 실패. 고정 가상시계의 empty-read/ACK backoff 경계 미전진2건, Long.MAX 결과 fixture의 dispatch identity 불일치1건, 전체 결과의 사건 범위를 깨뜨린 충돌 fixture1건이었다. 명세를 완화하지 않고 각각 시간 경계·원문 identity·유효 구조를 갖춘 충돌로 준비를 수정했다.
- 다음 선택 unit(`build/impl169-units4.log`):59개 중58개 통과. 추가 byte-window fixture의2KiB producer 상한이 최대 식별자의 최종 거절 최소 크기보다 작아 기동 검증에서 거절됐다. 테스트 producer를4KiB로, 작은 전송창을7,000byte로 맞춰 실제 byte 한도 동작을 검사하도록 수정했다.
- `build/impl169-units5.log`:60개 선택 unit 통과. 이후 새 독립 반례와 경계를 추가했다.
- 독립 리뷰에서 결과 사건 순번 덧셈 overflow가 malformed 처리 대신 owner 종료로 이어지는 P1을 직접 확인했다. `build/issue169-verification/overflow-red`에1개 실패의 실제 RED를 보존했다. 계산 전 범위 검사로 수정하고, codec의 정상 마지막 Long.MAX_VALUE와 adapter의 malformed 계수/owner 생존/정상 후속 정산을 함께 검사했다.
- `build/issue169-verification/units7/counts.json`: codec11·client4·worker20·adapter14·startup14, **63개 통과·실패/오류/skip0**. 정확2,097,120byte typed frame의 encode/decode와+1byte 거절도 포함한다. 실제 native 전송은 별도 large 실행에서 확인한다.
- 실제 baseline run `delivery1793771025102896fa520`: **성공**,90,862ms, 기록 최고25,878,528byte·진단210,077byte, run 소유 자원/볼륨 정리true·issues[]다. 무체결 결과 회수/활성 예약 유지,100×1 금융 정산, 응답 유실·ACK 미기록 full-log 재시작, 양 역할 snapshot+정산/ACK tail 재시작까지 각3사본의 독립 기대값과 최초 receipt 시각을 대조했다. R21은 추가 개발 자금을 입금하지 않고, 첫 체결로 X1을 얻은 구매자가 다시 매도하고 Q100을 얻은 판매자가 매수하는 역거래다. 기존 사용 계정에 시작 자금을 다시 준비하는 금지 경로를 테스트가 요구하지 않는다.
- 나머지 실제 fresh 실행5개와 기존 #168 회귀1개: `build/impl169-docker-cases.log`, **BUILD SUCCESSFUL(2분58초)**. partial18.433초·fee18.156초·cap16.425초·active16.239초·large32.644초이며, 각 run success=true/issue없음/자원정리=true다. 실행 ID와 금융 기대값은 위 D01~D06에 연결했다.
- R14의 중간 예약과 R9의 실제 FIFO 회수를 관찰하도록 시험만 보강한 뒤 active·large2개 재실행: `build/issue169-verification/docker-final-boundaries`, **BUILD SUCCESSFUL(1분29초)**. active24.823초·large42.804초, failures/errors/skip0. 제품 main은 앞 실행과 동일하다. 신규 Docker 정의6개+기존 정의1개이며 두 경계 재실행을 새 테스트2개로 더 세지 않는다.
- 최종 필수 검사: `build/issue169-verification/required-final`, **BUILD SUCCESSFUL(2분17초)**. app-api13클래스144개, domain-trading5클래스61개, architecture35클래스386개, 합591개·실패/오류/skip0. 전체 `ktlintCheck`와 `verifyArchitectureReport`의 P01~P09 실행·성공도 확인했다. 앞63개와 이번144개는 중첩되므로 합산하지 않는다.
- required-final의 app-api는 새5클래스63개뿐 아니라 기존 주문 worker/client/codec, 두 역할 snapshot, 역할 artifact, integrated DevelopmentFunding adapter/snapshot 회귀다. domain은 FundsResultSettlement/Recovery/ValidationRegression와 ReservedMatchingState/Recovery다. 구조386개는 금융 통합 테스트 숫자로 소개하지 않는다.
- 실제 보고서의 native MAX는 **2097120byte 전송·callback 경계**다. 정상 논리 결과 상한을 넘는 별도 typed fixture이며 응답 INVALID_STREAM/금융 무변경이 정답이다. 정상 큰 금융 정산은 논리1,047,281byte·wire971,606byte·체결366/효과367의 별도 결과로 증명했다.
- 기존 CI의 Build and test 기한만20→30분으로 조정했다. 새6개 시나리오를 기존 test 작업에 추가했기 때문이며, 테스트 제외·실패 무시·별도 CI 플랫폼/권한은 추가하지 않았다. 각 run8분/정리45초/기록2GiB/진단16MiB 상한은 유지한다. 게시 준비 시점의 원격 CI는 미실행이다.

## 6. 수용 조건과 검증 수준

명세 R1~R28의 실제 코드·검사·실행 근거를 최종 결과에 연결한다. 테스트 수·그린 구조 검사만으로 금융/HA 전체를 완료라고 하지 않는다.

| ID | 구현·확인 근거 | 판정과 한계 |
| --- | --- | --- |
| R1 | S01~S03, 기존 startup/한 방향 Docker 회귀 | 충족. 새 모드만 역전달 작업 활성화, 잘못된 구성은 자원 전 거절. |
| R2 | C01~C04/C06~C11, 실제 codec strict scan·typed mapping | 충족. 누락/중복/미지 필드·enum·순번/문맥 모순 거절. |
| R3 | C05/C09, L01~L04, D06 최종 large | 충족. 정상 약1MiB 결과 완결 정산, 별도 정확 MAX/native/+1 경계 구분. |
| R4 | D01 무체결 checkpoint, 기존 FundsResultSettlementTest | 충족. 결과 전달 자료0이어도 대기 주문·seller 예약1 유지. |
| R5 | D01 100×1 각3사본 고정 기대값 | 충족. buyer Q900/X1·seller Q100/X0·관련 hold/live0. |
| R6 | D02 partial, 기존 여러 체결 정산 회귀 | 충족. buyer Q715/hold100/X2·seller Q185, 잔량1·prefix3. |
| R7 | D03 fee, 기존 수수료 이월 회귀 | 충족. Q898+99+fee3=1000. |
| R8 | W03/A02, D01 응답 유실·재전송 | 충족. 최초 receipt 시각·금융 불변, DUPLICATE ACK로 재개. |
| R9 | W05/A02·기존 FIFO 회귀, D06 최종 retired162/result1 재전송 | 충족. 실제 PRUNED through367·6업무상태 불변. worker 대역만을 FIFO 증거로 쓰지 않음. |
| R10 | D01 정산 후/매칭 ACK 전 full-log 재시작 | 충족. funds 금융/prefix와 matching 결과 보존 후 ACK로만 회수. |
| R11 | W04/W06/W07 등 낮은 ACK·correlation 경계, D01 기록 응답 유실 | 충족. 확인 prefix 후퇴·미래 결과 회수0. |
| R12 | A04/W10/W11, 기존 [FundsResultSettlementTest:398](../domain-trading/src/test/kotlin/com/exchange/core/trading/FundsResultSettlementTest.kt#L398) | 충족. GAP 무변경·순서 재개/늦은 GAP progress 대조. actual 네트워크 역순 주입을 실행했다는 주장은 아님. |
| R13 | D01 명시 적용 ACK 보류·결과 먼저 정산 | 충족. 결과로 전달 자료 회수, 뒤늦은 주문 ACK 무변경. |
| R14 | D05 최종 중간·최종·중복 checkpoint | 충족. Q700/hold300→Q1000/hold0, 기존 예약/book/engine 유지, DUPLICATE 추가 반환0. |
| R15 | W03/W12 등 timeout/backpressure, D04 실제 BUSY | 충족. 일시 장애로 예약 반환·거절 재분류 없음. |
| R16 | D04 result cap1·ACK 회수 후 같은 다음 입력 진행 | 충족. 한도에 찬 상태에서도 회수 제어가 진행. |
| R17 | W14, D03 ACK offer 차단 중 양 역할 snapshot | 충족. 금융1회·source 결과 보존·snapshot 진행. |
| R18 | C06~C11/A04/A06, W07/W18/W19 등 peer/context/미래 ACK | 충족. 정상 성공 ACK 없음. 새 산술 overflow 반례도 malformed·owner 생존. 내부 신뢰망 범위, 보안 보장 아님. |
| R19 | W15/W16 등 세대·음수 시계, A03의 snapshot/role/read 무효화 | 충족. 구세대 새 송신 없음. 모든 운영 리더 장애 조합은 제외. |
| R20 | D01 양쪽 ACK 뒤 full-log 재시작 | 충족. 금융·회수 prefix 복원/결과 부활0. |
| R21 | D01 양 역할 snapshot 뒤 역거래/정산 tail, ACK 전후 두 재시작 | 충족. restoredSnapshot/boot/log증거와 각3사본 직접 대조. |
| R22 | W10/W11의 실제 progress/불일치 대역 경계 | 충족. source 회수 앞섬을 가짜 재생성/자동 수리하지 않음. 실제 디스크 유실 재해 시험은 아님. |
| R23 | C04/S02/W12/W13 등, adapter read큐 상한 | 충족. count·실제 frame bytes·제어 reserve·미완료 조회 한도. private 버퍼 총 실메모리를 별도 계측한 것은 아님. |
| R24 | C02/C03/C08, W17, 기존 코어 MAX 경계 | 충족. 마지막 순번 뒤 null cursor, wrap·미래 회수 없음. |
| R25 | D01~D06 각 역할3사본별 고정 금융·원본/결과 예산 | 충족. 합계나 노드간 일치만으로 정합성을 대신하지 않음. |
| R26 | 실제9회 실행의 finally/보고서와 다른 UID 읽기, 소스 정리 경로 리뷰 | 충족. 이번 관측 run 모두 소유 자원 정리. 모든 정리 실패/파일 교체 stress를 수행했다고 확대하지 않음. |
| R27 | required-final의 기존 #168 client/codec/worker·역할 artifact·snapshot·integrated 회귀 + 기존 한 방향 Docker | 충족. 기존 경로의 bytes/ACK/hold·실행 분리 유지. 전체 제품/DB suite 실행과는 다름. |
| R28 | 기존 [FundsResultSettlementTest:555](../domain-trading/src/test/kotlin/com/exchange/core/trading/FundsResultSettlementTest.kt#L555)/[588](../domain-trading/src/test/kotlin/com/exchange/core/trading/FundsResultSettlementTest.kt#L588), adapter의 fail 경계 | 충족. 계산 실패 전체 무변경과 실제 쓰기 실패 owner 차단을 재확인. malformed만 좁게 거절하고 금융 오류를 삼키는 catch를 추가하지 않음. |

위 C/L/W/A/S/D 번호의 준비·행동·실제 assertion은 §4의 모든 신규 검사 설명에 있다. R28의 기존 단위는 내부 손상을 시험에서만 주입한다. 네트워크 장애로 실제 금융 쓰기 오류를 만든 시험이라고 소개하지 않는다. 향후 범위인 조합 장애·실제 리더/과반 failover 전수·운영 보안·전체 원장 적재는 이 판정에 포함하지 않는다.


## 7. 독립 리뷰와 최종 대상

구현 작성 과정과 분리된 별도 컨텍스트에서 고정 base/head와 명세를 읽고 소스·호출 경계·반례를 검토했다. 독립 검토는 소스 수정·게시 없이 수행했다.

| 고정 대상 | 검토·실행·해소 |
| --- | --- |
| v1 · manifest `4ef99692b1fe3482be77d320b05687dc55a1719761f5515fdcfb40c768d569dd` | 전체 흐름 검토. F1 P1 산술 overflow owner종료, F2 P2 준비가 잘못된4실패, F3 P2 정확 MAX/native 증거 누락 지적. |
| v2 · manifest `841a9eebdd3dd31147b26637f5d5683657d817fa3fbf9f784143cf26c16193de` | F1 좁은 codec 수리·인접 정상 MAX, F2 fixture 수정, F3 실제 큰/정확 MAX 검증을 재확인. 독립67개(선택63+별도반례4) 실패/skip0. 기본 실제 복원 JSON도 별도 assertion. 미해결0. |
| v3 · manifest `872997eddc0e2e75673de3ab0005be961118b32eb545a4a4b335f838caafaa73` | main이 v2와 byte동일임을 확인. R14/R9 최종 두 실제 run 원문·소스hash·금융 상태·MAX 유지, CI/README delta 검토. 새 결함/필수검증누락0. 새 Gradle/Docker를 리뷰어가 또 실행한 것으로 세지 않음. |

지적별 상태는 **F1/F2/F3 모두 해소**, 최종 제품/시험 범위에서 확인된 미해결 결함0이다. 앞으로 모든 버그가 없다는 보장이 아니다. v3 소스 판정 뒤에는 문서 사실 대조를 별도로 받았다. W17 대역과 실제 코어 MAX 검증의 구분, R24의 C08 근거 매핑 두 건을 교정했고 숫자·원문·코드 발췌 대조도 완료했다. 문서 사실 대조·해소 기록 (`build/issue169-review-final-docs/report-final-docs/fact-check.md`)은 제품 독립 리뷰와 구분한다.

리뷰 보고서: v1 (`build/issue169-review-v1/review-report.md`), v2 (`build/issue169-review-v2/review-report.md`), 최종 v3 (`build/issue169-review-v3/review-report.md`). 고정 manifest에는 구현 소스가 들어 있고, 최종 문서 변경을 제품 재수정으로 섞지 않는다.

## 8. 범위와 한계

이번에는 개발용 고정 두 클러스터의 실제 연결·기본 중복/유실/복원을 다룬다. 인증·인가·보안, 일반 HTTP 전환, 성능, 취소 #133, 조합 장애 #134, 다중 매칭/담당 이동, 리더·과반 장애 전수, Kafka/RocksDB/RDB 원장 적재와 전체 과거 분개 직접 대조는 제외다. 기존 정산의 분개 후보·균형 검증은 재사용하며 전체 원장 목록을 메모리에 새로 쌓지 않는다. 명세 수용 조건은 각 지정 수준에서 위 근거로 대조했다. 제외 범위의 운영 기능이나 모든 장애 조합을 완료라고 하지 않는다.

## 9. 재현할 명령과 로컬 근거

다음은 실제 실행한 최종 선택 회귀의 재현 명령이다. JDK 25가 `JAVA_HOME`과 `PATH`에 설정되어 있어야 한다. 새 Docker6개와 기존 Docker1개는 앞서 별도 작은 실행으로 완료했으며 이 명령이 그것들을 다시 실행하는 것은 아니다.

```bash
cd "$(git rev-parse --show-toplevel)"
./gradlew \
  ktlintCheck \
  :domain-trading:test --tests '*FundsResult*' --tests '*ReservedMatching*' \
  :app-api:test \
  --tests '*SplitResultDeliveryCodecTest' --tests '*SplitResultDeliveryClientTest' \
  --tests '*SplitTradingClusterAdapterTest' --tests '*SplitTradingRoleStartupTest' \
  --tests '*MatchingResultDeliveryAgentTest' --tests '*FundsOrderDeliveryAgentTest' \
  --tests '*SplitOrderDeliveryCodecTest' --tests '*SplitOrderDeliveryClientTest' \
  --tests '*FundsRoleSnapshotCodecTest' --tests '*MatchingRoleSnapshotCodecTest' \
  --tests '*SplitTradingArtifactTest' --tests '*DevelopmentFundingAdapterTest' \
  --tests '*DevelopmentFundingSnapshotTest' \
  :architecture-tests:test :architecture-tests:verifyArchitectureReport \
  --no-daemon --max-workers=1 --console=plain
```

실제 새 Docker6개를 다시 확인할 때는 다음 명령을 사용한다. 이미지·디스크·메모리 전제를 검사하며 Docker가 없으면 skip 성공으로 처리하지 않는다. 이미 통과한 검사를 문서 생성만을 이유로 다시 실행하지 않았다.

```bash
cd "$(git rev-parse --show-toplevel)"
./gradlew \
  :app-api:test --tests '*DockerMatchingResultDeliveryTest' \
  --no-daemon --max-workers=1 --console=plain
```

최종 필수검사 합계 (`build/issue169-verification/required-final/counts.json`) · 실행 로그 (`build/issue169-verification/required-final/execution.log`) · 첫 RED (`build/issue169-verification/overflow-red`) · 실제 baseline (`build/issue169-verification/docker-baseline/result.json`) · 최종 active (`build/issue169-verification/docker-final-boundaries/delivery1793771536737879df1a1-result.json`) · 최종 large (`build/issue169-verification/docker-final-boundaries/delivery179377156165999f6ee9a-result.json`).

위 `build/issue169-*` 실행·리뷰 근거는 로컬 ignored 산출물이며 이 PR에 게시하지 않는다. 경로 문자열은 로컬 실행을 식별하기 위한 기록이고 원격 링크가 아니다. 공개 검토에서는 본 기록과 상대 링크로 연결한 제품·시험 소스, 이후 CI가 수집하는 재현 보고서를 구분해 확인한다. [CI 보고서 수집 설정](../.github/workflows/build-and-test.yml#L135)의 `test-reports`에는 모듈별 `build/test-results/test/*.xml`·`build/reports/tests/test/**`, `build/reports/ktlint/**`, 결과 시나리오의 `app-api/build/reports/aeron-funding/result-settlement/**`가 포함된다. 로컬 `build/issue169-*` 디렉터리를 업로드한다는 뜻은 아니다. 게시 준비 시점에는 push·PR·원격 CI·병합·배포·이슈/보드 변경을 수행하지 않았고, 원격 CI의 성공이나 보고서 생성을 주장하지 않는다.

종료 점검에서 이번 결과 시나리오의 잔존 기록 볼륨0·실행 중 테스트 컨테이너0을 확인했다. 독립 리뷰용 임시 복사본의 재생성 가능한 빌드 산출물705.2MiB도 정리했다. 고정 소스·manifest·리뷰 보고서·JUnit XML·시험 HTML은 유지했고 다른 실행의 자원은 삭제하지 않았다.
