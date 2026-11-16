# #136 · BTC 매칭 리더 장애 전환 검증

**로컬 구현·실제 두 장애 사례·관련 회귀·독립 검토 완료. 아래는 커밋·PR·원격 CI 전의 로컬 검증 기록이다.** 기준은 통합 `9001e14a5afe96f91aeb461861052dd62c57ef9a`이며, 상세 계약은 [이슈 #136](https://github.com/0Chord/coin-exchange/issues/136)이다. 작업 폴더는 `/Users/0chord/.codex/worktrees/issue148-http-aeron/exchange-core`, 브랜치는 `test/matching-leader-failover/136`이다. #137의 자금 리더 종료와 #138의 과반 상실·분할은 이번 변경에 포함하지 않는다.

## 사용자가 확인할 결과

BTC 리더 한 대가 종료돼도 남은 두 노드가 미완료 거래를 이어가고, 그동안 SUI 거래는 별도로 완료돼야 한다. 종료한 BTC 노드도 자기 Archive 기록으로 돌아와 현재 상태를 따라잡아야 한다.

```text
① 자금 예약 후 BTC 전달 전 보류 ─┐
                                ├─ 실제 BTC 리더 종료 → 생존 두 노드의 새 임기 확인
② BTC 체결 후 자금 정산 전 보류 ─┘                     │
                                                       ▼
                 BTC 보류 유지 + 구 리더 정지 중 SUI 체결·정산
                                                       │
                                                       ▼
                 보류 해제 → 실제 전달 작업의 BTC 정산·확인 완료
                                                       │
                                                       ▼
                 구 리더 동일 기록 재합류 → 새 BTC 주문 완료
```

이 흐름 전체가 하나의 트랜잭션은 아니다. 접수 성공, 매칭 확정, 자금 정산, 결과 회수는 각 클러스터의 확정 기록으로 구분한다. 전송 성공만으로 정산 완료라고 판단하지 않는다.

## 두 시험이 검증하는 것

| 시험 | 준비 | 실행 | 실제 확인할 결과 |
| --- | --- | --- | --- |
| 전달 전 장애 | 구매자1000, BTC BUY2@100 예약202. BTC 입력1의 실제 송신만 보류 | 현재 BTC 리더 SIGKILL. 생존 두 노드가 새 임기를 확정한 뒤 SUI BUY3/SELL1 실행 | BTC 입력은 아직 미적용인데 SUI 체결·정산 완료. 보류 해제 후 기존 자금 전달 작업이 BTC를 자동 전달하고 SELL1까지 정산 |
| 정산 전 장애 | BTC BUY2의 대기 결과까지 완료. BTC SELL1의 체결 결과2가 자금으로 나가기 직전에 보류 | 실제 체결 결과가 생존 사본에 보존된 상태에서 BTC 리더 SIGKILL. 보류 중 SUI 거래 실행 | BTC 체결은 확정됐으나 자금에는 미정산인 경계를 유지. 새 BTC 리더의 결과 작업이 보류 해제 후 같은 결과를 한 번 정산 |

두 시험 모두 구 리더가 정지한 동안 새 리더를 확인한다. 구 리더 재합류 뒤에는 최초 요청을 재전송하고 새 BTC BUY1을 처리한다. 이미 끝난 요청의 재생 횟수를 새 체결 수로 합산하지 않는다.

## 독립 기대값

가격100·수량 단위1·maker/taker 각1%다. 구매자 USDT1000, 판매자 BTC1/SUI1로 시작한다.

| 관측 지점 | 구매자 USDT 가용/예약 | 판매자 USDT | 수수료 USDT |
| --- | ---: | ---: | ---: |
| BTC BUY2 접수 |798/202|0|0|
| BTC 보류 중 SUI BUY3·SELL1 정산 |495/404|99|2|
| BTC SELL1도 정산 |495/303|198|4|
| 구 리더 재합류 뒤 새 BTC BUY1 완료 |394/404|198|4|

최종 USDT는 `394+404+198+4=1000`이다. 구매자는 BTC/SUI 각1을 받으며 판매자의 base 잔고·예약은0이다. BTC에는 기존 잔량1과 새 주문1, SUI에는 기존 잔량2가 남는다. 취소하지 않았으므로 예약404·활성 주문3개가 정상이다. BTC 입력/결과/자금 확인은3, SUI는2여야 한다.

## 거짓 성공을 막는 경계

- 현재 리더는 실제 새 임기 callback의 임기·리더 ID와 현재 boot·복제 probe를 결속해 정한다. 종료한 파일의 LEADER 표시를 현재 선출 근거로 쓰지 않는다.
- 보류는 실제 Aeron 연결의 대상 원문 송신 직전 한 건만 막는다. 선출·poll·keepAlive·SUI·ACK는 계속 처리한다.
- SUI 요청은 BTC 종료 확인 뒤 시작하고, BTC 보류 해제·구 리더 재합류 전에 체결·정산까지 완료한다.
- 구 리더는 같은 이미지·manifest·member·endpoint·records volume·Archive를 사용한다. 복귀한 한 노드의 boot만 새 값이어야 한다.
- 같은 금액 합계만 보지 않고 최초 receipt·원 주문·잔량·체결 상대·결과 ID·입력/정산 prefix·payload 회수를 대조한다.
- 노드별768MiB·힙256MiB, 전체 기록2GiB, 사례별480초·정리90초를 유지한다. 죽은 노드의 기록 볼륨도 합산한다.

## 실행 근거

두 사례는 각각 새 RDB 설정과 동결 이미지, 자금3+BTC3+SUI3의 기록으로 실행했다. 실제 sync는2/2/2다. 아래 시간은 준비·관찰·정리를 포함한 전체 실행 시간이며 선출 자체 시간이나 운영 RTO가 아니다.

| 실제 사례·보고서 run ID | 종료 대상·임기 | 전체 실행 | 결과·자원 회수 |
| --- | --- | ---: | --- |
| 전달 전 · `multi17948102093889e13befe` |BTC1 종료, 생존 BTC0/BTC2의 임기0→1|90,232ms|두 체결·재합류·후속 주문 완료, `issues=[]`, 기록/실행 자원 회수 |
| 정산 전 · `multi1794810299954b6186368` |BTC2 종료, 생존 BTC0/BTC1의 임기0→1|82,982ms|동일한 경제 정답, `issues=[]`, 기록/실행 자원 회수 |

원자료는 `app-api/build/reports/aeron-funding/multi-matching/<run ID>/result.json`과 같은 폴더의 명령·관찰 파일이다. 두 실행 모두 기록9개 합계의 관찰 최댓값은13,082,624bytes, 진단은 각각3,303,126/3,347,226bytes다. 정지한 노드의 볼륨도 합산했다. 최종 구매자394/404·판매자198·수수료4, BTC/SUI 각1, 체결2개·BTC3/SUI2 진행과 미확인 payload0을 현재9사본에서 확인했다.

실제 장애 시험 명령:

```bash
cd /Users/0chord/.codex/worktrees/issue148-http-aeron/exchange-core
JAVA_HOME=/Users/0chord/Library/Java/JavaVirtualMachines/corretto-25.0.2/Contents/Home \
./gradlew :app-api:test --tests '*MultiMatchingLeaderFailoverTest' \
  --rerun --no-daemon --max-workers=1 --console=plain
```

`/tmp/issue136-native-v5.log`의 `BUILD SUCCESSFUL in 3m 3s`, XML에 기록된2개 사례 실패/오류/skip0과 연결한다. 이전 실패 실행이나 경량 판정기의 성공을 이 두 사례의 성공 건수에 더하지 않는다. 최종 회귀·독립 검토 결과는 아래에 연결한다.

<details>
<summary>코드와 테스트를 읽을 지도 · 준비 → 실행 → 실제 검증</summary>

제품 실행 변경은 [노드의 기존 연결 조립](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentSplitTradingNode.kt#L443)에 시험용 연결을 전달하는 부분이다. 원래 `start`의 기본값과 마지막 함수 인자 호출을 보존한다. 아래 보류·관찰·판정은 시험 코드다.

### 실제 거래·장애의 두 사례

[MultiMatchingLeaderFailoverTest](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MultiMatchingLeaderFailoverTest.kt)는 앞의 두 행을 실제 Aeron 연결로 실행한다. 첫 사례는 입력1 전송 전, 둘째는 실제 체결 결과2의 전송 전에 보류한다. 두 경우 모두 실제 현재 리더를 종료하고 생존 두 사본의 더 높은 임기와 새 probe를 확인한다.

그다음 처음 시작하는 SUI 매수·매도를 정산할 때까지 BTC 보류를 유지한다. `awaitFailover`가 해당 중간창의 경제값·BTC 미완료·SUI 진행2를 함께 검사한다. 보류를 풀면 `awaitReleased`가 같은 원문을 보낸 원래 전송 작업과 실제 응답을 대조하고, 다시 경제 상태와 결과/확인 prefix를 검사한다. `finishWithRecovery`가 원 Archive 재합류·최초 응답 재전송·새 BTC token을 순서대로 검증한다. 시간 경과만 기다리는 것을 완료 조건으로 쓰지 않는다.

### 검사 도구가 잘못된 관찰을 통과시키지 않는지

[MultiMatchingFailoverConvergenceTest](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MultiMatchingFailoverConvergenceTest.kt#L16)는 수기 fixture를 사용한8개 테스트다. 실제 Docker 장애 시험과 구분한다. 배열의 여러 변형 입력을 별도 JUnit 테스트 수로 더하지 않는다.

| 테스트 | 준비·실행 | 실제 검증 |
| --- | --- | --- |
| 두 장애창·후속 정상 |10개 단계의 독립 경제값과 재합류 관찰을 넣음|위반 목록이 비어 있음. 복귀 노드의 비영속 진단 cache가 비어도 영속 상태는 정상 |
| 실제 종료 대상 |종료 대상·Running·OOM·exit code·생존 boot를 하나씩 변조|잘못된 리더 종료/예상 밖 종료·재시작은 위반 |
| 임기·현재성 |동일/낮은 임기, 이전 probe, 구 boot를 복제|새 선출/재합류로 통과하지 않음. 정상 초기 임기0은 허용 |
| 두 장애 시점 |이미 매칭/정산됐거나 체결 결과가 누락된 상태를 넣음|전달 전·정산 전이라는 지정 경계 위반 |
| SUI 진행 순서 |종료 전 요청·해제 뒤 완료·신규 진행 누락으로 변조|BTC 미완료 중 SUI 처리 증거로 통과하지 않음 |
| 같은 합계·다른 의미 |예약·수수료·원 체결 상대·결과 ID·마켓 prefix를 변조|총액이 같아도 위반 |
| 재합류·후속 |빈 기록·미회수 payload·틀린 prefix·옛 응답 재사용|같은 기록 복구·새 후속 처리로 통과하지 않음 |
| 실제 전송 작업 |원문·worker boot·connection·응답을 함께 변조|실제 미완료 원본과 전송/응답의 결속이 없으면 위반 |

### 보류가 금융 처리 대신 가짜 성공을 만들지 않는지

[MultiMatchingFailureControlTest](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MultiMatchingFailureControlTest.kt#L60)는4개 테스트다. 연결 대역의 offer 호출을 세어 BTC 대상만 실제 delegate 호출 전에 막고, 조회·다른 순번·SUI는 그대로 전달하는지 확인한다. 해제 marker 생성 뒤에는 같은 bytes를 delegate에 보내고, 재시도 상관번호가 달라도 업무 원문 지문은 같아야 한다. 주문 내용 변경과 손상된 최초 제어 JSON은 예외가 나고 delegate에 전달되지 않는다. 이 대역 검사를 실제 Aeron 송신 성공이라고 쓰지 않는다.

### 기록 공간을 세다가 시험을 깨거나 한도를 누락하지 않는지

[MultiMatchingRunBudgetTest](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MultiMatchingRunBudgetTest.kt#L45)의 추가3개는 존재하는 일반/임시/하위 파일을 모두 합산하고, 실제 atomic 게시와 동시에 측정해도 회수된 임시 파일 때문에 실패하지 않으며, 없는 root는 예외가 나는지 확인한다. 기존3개는 메모리·디스크 최소 조건이다. 살아 있는8개만 측정하거나 용량 검사를 생략하는 것으로 경합을 피하지 않는다.

</details>

## 실제 시험에서 수정한 경계

| 관찰한 실패 | 수정한 책임 | 근거 |
| --- | --- | --- |
| 삭제된 임시 client 이름을 새 리더/다른 마켓이 해석하다 DNS 오류 |노드와 client의 주소를 최초부터 run 수명 동안 숫자 고정 주소로 공급 |v1 BTC2/SUI0 로그의 응답 publication 실패. 오류 분류/한도 완화 없이 수정 |
| 공유 제어 JSON 교체 중 EOF로 실제 worker 종료 |설정은 한 번만 게시하고, 해제는 별도 빈 marker로 알림 |v3 `funds2.log`의 EOF. 초기 손상 JSON은 계속 예외. 공유 파일 교체의 하부 플랫폼 원인을 별도로 확정했다고 쓰지 않음 |
| 진단 측정 중 atomic 임시 파일이 사라져 NoSuchFile |읽어 둔 파일 속성의 크기를 합산, 트리 안에서 이미 사라진 숨김 임시 파일만 허용 |동시 게시/존재 temp 합산/missing root 회귀3개. 다른 IO는 계속 실패 |
| 같은 고정 주소 재합류를 주소 변경으로 오판 |종료 후 빈 주소 대신 불변 초기 관찰과 새 실제/고정 주소4개 대조 |실제 초기·복귀 주소는 모두 `172.24.1.22`, 정지 inspection의 실제 주소만 비었음 |

네 수정은 자금 계산이나 매칭/정산 계약을 바꾸지 않는다. 최종 실제 실행은 위 v5 두 run이며 이전 실패를 성공으로 세지 않는다.

## 최종 회귀·수용 판정

| 실제 실행 범위 | JUnit 테스트 수 | 실패/오류/skip | 실행 근거 |
| --- | ---: | --- | --- |
| #136 실제9노드 장애 |2|0/0/0|`/tmp/issue136-native-final-v5-results` XML·두 원본 run |
| 보류/판정기 자체 |12|0/0/0|`/tmp/issue136-unit-final-v4-results` XML |
| 실행 예산·동시 파일 게시 |6|0/0/0|`/tmp/issue136-budget-final-v5-results` XML |
| 관련 앱 회귀: 설정·전달 작업·Adapter·snapshot·codec·관찰/CLI·기존6노드8개·정상9노드2개 |189|0/0/0|`/tmp/issue136-regression-final-v1-results/app-api` XML |
| 자금·정산 도메인 전체 |444|0/0/0|`/tmp/issue136-domain-final-v1-results` XML |
| 구조 검사 |386|0/0/0|`/tmp/issue136-regression-final-v1-results/architecture-tests` XML |
| 구조 report·전체 ktlintCheck |명령 성공|해당 없음|`/tmp/issue136-regression-v1.log` |

합계1,039개는 최종 XML의 서로 다른 suite를 모은 수다. 중간 실패·재실행·테스트 안의 반복 입력·검토자의 직접 probe를 추가하지 않았다. 초기 시작/복구17개는 앱 회귀189개에 포함하므로 별도로 중복 합산하지 않았다. 집계는 `/tmp/issue136-regression-final-v1-results/unique-summary.json`이다. JUnit skip0과 Gradle의 변경 없는 task 캐시/조건부 task 표시는 구분한다.

도메인은 `:domain-trading:test --rerun`으로 별도 실행했다. 회귀 명령은 다음과 같고 `BUILD SUCCESSFUL in 10m 27s`를 확인했다.

```bash
cd /Users/0chord/.codex/worktrees/issue148-http-aeron/exchange-core
JAVA_HOME=/Users/0chord/Library/Java/JavaVirtualMachines/corretto-25.0.2/Contents/Home \
./gradlew :app-api:test \
  --tests '*SplitMultiMatchingFlowTest' --tests '*SplitMultiMatchingSettingsTest' \
  --tests '*SplitTradingRoleStartupTest' --tests '*FundsOrderDeliveryAgentTest' \
  --tests '*MatchingResultDeliveryAgentTest' --tests '*SplitCancellationFlowTest' \
  --tests '*SplitTradingClusterAdapterTest' --tests '*FundsRoleSnapshotCodecTest' \
  --tests '*MatchingRoleSnapshotCodecTest' --tests '*SplitOrderDeliveryCodecTest' \
  --tests '*SplitResultDeliveryCodecTest' --tests '*MultiMatchingConvergenceTest' \
  --tests '*VerificationCommandsTest' --tests '*DockerFundsOrderDeliveryTest' \
  --tests '*DockerSplitCancellationTest' --tests '*DockerMatchingResultDeliveryTest' \
  --tests '*DockerMultiMatchingReplicationTest' --rerun \
  :architecture-tests:test --rerun :architecture-tests:verifyArchitectureReport ktlintCheck \
  --no-daemon --max-workers=1 --console=plain
```

| #136의 로컬 수용 항목 | 실제 근거 | 판정 |
| --- | --- | --- |
| 지정한 두 장애창에서 실제 리더 종료와 생존 과반의 새 임기 |두 run의 초기·종료 command/exit137·OOM false·현재 probe·elected8 |충족 |
| BTC 미완료 중 SUI 신규 체결·정산 |각 중간 checkpoint·controller eventOrder의 SUI 완료→해제 순서 |충족 |
| 같은 원본을 실제 작업이 재개하고 한 번 정산·확인 |held binary/canonical SHA·worker offer/APPLIED·BTC 결과2와 ACK |충족 |
| 구 리더의 기존 Archive·신원으로 재합류와 현재9사본 |동일 named volume/identity SHA·이미지/manifest·새 boot·생존8개 유지 |충족 |
| 최초 응답 보존과 새 BTC 주문의 완료 |같은4개 token의 최초 receipt·새 sequence2/orderId·최종394/404·BTC3/SUI2 |충족 |
| 예산·회귀·필수 검사·자원 정리 |위 XML·report·명령과 각 run의 최종 소유 자원 조회 |충족 |
| PR·원격 CI·통합 후 티켓 완료 |로컬 검증 종료 당시 미실행. 후속 게시·CI 상태는 PR에서 확인 |로컬 검증과 별도 |

## 독립 검토와 보장 범위

작성 대화를 상속하지 않은 `/root/review136_implementation_v1`이 base와 고정 전체 snapshot을 읽고 최초 변경 전체 및 수정 delta를 검토했다. 코드 검토 최종 대상은 `/tmp/issue136-review-v5`, manifest SHA256은 `aba37846d7d266f641a18576800b074b412ed370eb59232ceef517a6f444728e`다. 이후 변경은 이 문서의 실행 기록뿐이다. 검토자는 별도 작은 JVM에서 정상/변조 관찰, marker 경계, 실제 동시 파일 게시를 직접 확인했다. 확인된 미해결 결함은0이다.

실제 실행 수용 판정은 별도 컨텍스트 `/root/verify136_native_v5`가 작성자의 집계 요약·다른 검토 결과를 읽지 않고 두 원본 report·command·runtime/control·binary에서 다시 대조했다. 사례별19개 로컬 수용 항목이 모두 충족됐으며 `/tmp/issue136-native-acceptance-audit-v5.json`에 기록했다. 이전 코드 검토자의 추가 실행 audit은 `/tmp/issue136-reviewer-native-audit-v5.json`이며, 그 과정에서 작성자 summary를 함께 조회한 한계와 문서 delta를 따로 명시했다. 검토자의 점검 횟수를 제품 테스트 수나 모든 미래 결함의 부재로 해석하지 않는다.

검증한 것은 단일 Mac의 Docker3+3+3노드·sync2/2/2에서 한 BTC 매칭 프로세스가 종료되는 두 시점이다. 자금 리더 종료·과반 상실/네트워크 분할·전체 기록 유실·독립 물리 장애 영역·운영 RTO·TPS는 이 결과로 보장하지 않는다. #137/#138과 후속 재해 검증은 별도 PR 범위로 남긴다. 로컬 검증 종료 시점에는 이슈/보드 상태 변경·커밋·PR 게시를 수행하지 않았다. 이후 게시와 원격 CI 결과는 해당 PR에서 구분해 확인한다.
