# #138 · 자금 과반 상실과 분할 뒤 새 예약 차단 검증

**2026-11-17: 최종 소스의 실제9노드 Q1~Q4가 모두 통과했다.** 초기 예약 보호·정상 과반 진행·같은 요청 해결·현재9사본·후속 체결/정산·자원 회수를 확인했다. 관련 회귀·구조·필수 검사·스타일도 모두 통과했다. 로컬 검증이며 PR·원격 CI·병합 완료를 뜻하지 않는다. 계약은 [이슈 #138](https://github.com/0Chord/coin-exchange/issues/138)이고 기존 [#137 검증](funds-leader-failover-verification.md)의 실행·동일 기록 복귀 지원을 확장한다.

구현 폴더는 `/Users/0chord/.codex/worktrees/issue148-http-aeron/exchange-core`, 브랜치는 `test/funds-quorum-failure/138`이다. 경량 검사 성공을 실제 장애 성공 건수에 더하지 않는다.

## 확인할 업무 흐름

구매자 자금1000에서 B0의600을 먼저 예약하고 실제 매칭 대기 결과·자금 반영·확인까지 마친다. 그 뒤 자금 노드를 종료하거나 서로 통신하지 못하게 만든다. 서로 통신하는 자금2노드의 과반만 P_B·P_S의 새 예약 각100을 확정해야 한다. 리더라는 표시나 전송 수용만으로 승인을 주장하지 않는다.

```text
B0 완료: 가용400 / 예약600
  → 실제 종료·분할 성립
  → 과반 없는 영역: 새 P 확정 없이400/600 보호
  → 과반 유지·회복 영역: 같은 P token·원문을 한 번 해결 →200/800
  → 같은 기록의 현재9사본 대조
  → 새 N_B → BTC·SUI SELL 각1 → 실제 체결2·정산·ACK·자료 회수
```

연결 미확인은 `CONNECT_UNCONFIRMED`, 전송 수용 뒤 무응답은 `SENT_NO_REPLY`로 기록한다. 어느 쪽도 금융 거절이나 승인으로 바꾸지 않는다. 회복 뒤 같은 P를 재확인해서 최초 성공 하나와 주문·예약 하나로 해결해야 한다. 고립된 영역의 시도와 실제 성공을 결정한 과반 영역을 따로 대조한다.

## Q1~Q4의 준비·실행·기대값

각 사례는 새 RDB 출처·동결 설정·이미지·기록에서 시작한다. 가격100·수량 단위1·LIMIT/GTC·수수료0, 구매자USDT1000·base0, 판매자BTC1/SUI1·USDT0이다. L은 장애 직전 실제 리더, F1/F2는 그때의 팔로워다. P 최초 전송은 장애 성립 뒤에 한다.

| 사례 | 준비(Given) | 실행(When) | 기대 확인(Then) |
| --- | --- | --- | --- |
| Q1 · 팔로워1대 손실 | B0 완료, 실제 L/F 확인 | F1 SIGKILL → 과반에서 P 둘 완료 → F1 같은 기록 복귀 | live8에서200/800·매칭/자금 확인 완료, 현재9사본 재합류. 같은 임기도 허용 |
| Q2 · L 포함2대 손실 | B0 완료, L/F1/F2 식별 | L·F1 SIGKILL → F2 scope 시도·연속10초 관찰 → F1부터 복구·P 해결 → L 복구 | live7의400/600·새 P 미적용, live8의 더 높은 같은 임기·과반 진행, 마지막live9 |
| Q3 · 1:2 분할 | B0를 처리한 L의 기존 세션 유지 | L↔F1/F2 양방향 차단 → L scope 시도·관찰 → F1/F2 scope에서 같은 P 해결 → 규칙 해제 | L의 새 성공·예약 없음, 과반은 더 높은 임기에서200/800. 분할 중9프로세스·boot 유지, 해제만으로 L 따라잡음 |
| Q4 · 1:1:1 분할 | B0 완료·L 기존 세션 유지 | 세 peer 쌍 차단 → 세 singleton scope 직렬 시도·관찰 → F1↔F2만 회복·P 해결 → 나머지 해제 | 세 영역 모두400/600·새 P 미적용, 구 L 고립을 유지한 과반 진행, 재시작 없이 현재9사본 회복 |

no-quorum의 연속10초에는 시작·중간·끝의 새 nonce 관찰과 같은 boot의 background 진행을 확인한다. P callback 진입/완료 사이의 반쪽 관찰은 미준비다. Q3의 과반 처리 뒤에는 minority의 B0 prefix와 majority의 B0+P prefix가 달라도 정상이며, 전체 일치는 규칙 해제 뒤에 요구한다.

네 사례의 공통 후반부는 최초 응답 재확인 → 새 N_B → S_B/S_S 실제 체결 → 여섯 원문 재확인 → P_B 수량 변경의 `REQUEST_CONTENT_CONFLICT`와 금융 무변경 확인이다. 사용자 응답 회수 ACK·epoch 회전은 보내지 않는다.

| 업무 지점 | 구매자USDT 가용/예약 | 독립 정답 |
| --- | ---: | --- |
| 초기 |1000/0|업무 순번0·주문 없음, 판매자base 각1 |
| B0 보호·no-quorum |400/600|BTC BUY6, BTC 입력/결과/확인1·SUI0, 미확인 payload0 |
| P 둘 해결 |200/800|BUY3개: B0·P_B·P_S, BTC 진행2·SUI1 |
| N_B 완료 |100/900|BUY4개, BTC 진행3·SUI1 |
| 두 SELL 완료 |100/700|구매자BTC/SUI 각1·판매자USDT200·수수료0, 체결2, 최초 receipt6·고유 주문ID6 |

최종 BTC 주문장은 B0잔량5·P_B1·N_B1이고 SUI는 비어야 한다. 활성 BUY3·취소0, BTC 입력/결과/정산 확인4·SUI2, 미확인 payload0이다. `600+100+100+100−100−100=700`, USDT는 `100+700+200+0=1000`이다. 서로 다른 마켓의 raw logPosition이나 전역 주문ID 숫자 순서는 맞추지 않는다.

## 파일 책임과 경량 반례

| 파일 | 책임 |
| --- | --- |
| [두 손실 JUnit](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerFundsQuorumLossTest.kt) · [두 분할 JUnit](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerFundsQuorumPartitionTest.kt) · [FundsQuorumScenario](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/FundsQuorumScenario.kt) | 각기 다른 Q1~Q4와 공통 후반부를 실제 실행·단언 |
| [MultiMatchingRun](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerMultiMatchingReplicationTest.kt) | 기존 기동·현재 runtime·복수 종료/재합류·한도·정리·보고서를 재사용하고 영역별 witness/관찰을 연결 |
| [FundsQuorumConvergence](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/FundsQuorumConvergence.kt) | 고정 금융 정답·원 token/receipt·예약·주문장·결과·효과와 호출자가 명시한 사본 집합 판정 |
| [FundsQuorumNetworkControl](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/FundsQuorumNetworkControl.kt) | funds peer 양방향 전체IP 차단, client 자신의 namespace에서 scope 제한, 규칙/counter/도달 행렬·회수 확인 |
| [FundsQuorumObservation](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/FundsQuorumObservation.kt) · [MultiMatchingVerificationTool](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MultiMatchingVerificationTool.kt) | 서비스 소유 스레드의 완결된 불변 관찰·P callback·background 진행을 게시하고 새 로컬 nonce에 현재 runtime을 결속 |
| [FundsQuorumClient](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/FundsQuorumClient.kt) · [SplitOrderDeliveryClient](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitOrderDeliveryClient.kt) | B0 세션 유지·정상 poll/keepAlive/redirect·실제 endpoint/시도 기록, 연결 미확인·수용 후 무응답·실제 reply 구분 |
| [시험 Dockerfile](../app-api/src/test/docker/aeron-multi-matching/quorum.Dockerfile) · [시험 compose 추가 설정](../app-api/src/test/docker/aeron-multi-matching/quorum.compose.yml) · [CI 배정표](../.github/ci-test-groups.json) | 동결 backend와 funds/client만의 NET_ADMIN, Q1/Q2는quorum-loss·Q3/Q4는quorum-partition에 배정 |

| 경량 테스트 | 준비 → 실행 → 실제 검증문 |
| --- | --- |
| [Convergence 7개](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/FundsQuorumConvergenceTest.kt) | 독립 수기 정상5단계·live7/8/9·singleton/majority/매칭6을 판정. 빈 대상·사본 누락·같은 총액의 다른 계정/마켓/주문·중복token/ID·잘못된 예약 상태·누락 결과/주문장·순번/SHA·FIFO 상대/효과 변형은 위반을 요구. 임의 주문ID와 P 둘의 전역 승인 순서 교환은 허용 |
| [Network 8개](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/FundsQuorumNetworkControlTest.kt) | 수기 규칙·도달 자료를 판정. 단방향 누락·단일포트 차단·client ingress 차단·counter0·허용 경로 실패·사본 누락·외부/중복 주소는 거절 |
| [Observation 8개](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/FundsQuorumObservationTest.kt) | callback 진입/완료·background 진행·nonce 파일을 실행. 반쪽 상태 미준비, 읽기만으로 callback 불변, 새 nonce/current boot 결속, 자료 불변·상한 초과 실패를 확인. 실제 선거의 recorder 비활성과 미식별 누락을 구분하며, runtime 관찰 실패는 같은 nonce의 실패로 남고 새 nonce에서 다시 시도한다 |
| [Quorum client 3개](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/FundsQuorumClientTest.kt) | 연결 대역으로 offer 후 무응답·실제 reply·연결 실패를 실행. 성공을 꾸미지 않는 분류, 최초 receipt/위치·원문 지문 보존, 연결 미확인의 미전송을 확인 |
| [client 5개](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitOrderDeliveryClientTest.kt) | 연결 대역으로 correlation 응답·무응답·연결 만료·정리 실패를 실행. 맞는 응답만 반환하고 미확인·client/연결 시도 자원 정리를 유지. 실제 Aeron channel parser로 같은 tag의 동적 소켓 공유와 다른 tag의 분리를 확인 |

경량 client 검사의 하부 연결과 network 검사의 규칙 자료는 대역이다. 실제 Aeron 합의·redirect·프로세스 생존·peer 분할·동일 Archive 복구를 이31개로 입증하지 않는다. 매칭 진단 cache는 재시작 뒤 비어도 되지만 자금의 전체 결과는 필수이고, 남은 cache 원문은 자금 결과와 SHA까지 같아야 한다.

## 유지한 계약과 실행 한도

자금3+BTC3+SUI3, RDB 유래 동결 설정·manifest/image·고정 배치/주소·ownershipEpoch·sync2/2/2를 유지한다. 종료 복구는 지정 노드의 같은 Archive/volume과 새 boot를 확인하며, 분할 복구는 boot를 유지하고 규칙만 해제한다. 같은 token·원문은 최초 receipt를 반환하며 중복 금융 효과를 만들지 않아야 한다. 운영 wire·snapshot·금융 정책과 기존 정상·#136/#137의 완료 기준을 바꾸지 않는다.

backend는 시험 Dockerfile의 `iptables-legacy 1.8.8-3.amzn2023.0.2`와 Corretto25 기반 digest로 고정한다. 시험 run 소유 funds/client namespace만 변경한다. host firewall flush·전역 prune·다른 run 삭제·사용자 앱 종료를 정리 동작에 넣지 않는다.

| 항목 | 코드·계약의 한도 — 실제 측정치는 아래 실행 근거 참조 |
| --- | --- |
| 노드/client | 각768MiB·거래 JVM heap256MiB·shm256MiB·CPU1·pids128. 한 머신9node와 client/측정 helper 한 개만 사용, DB는512MiB·CPU0.5로 준비 뒤 종료 |
| 환경·기록 | Docker 실제MemTotal≥9GiB·host 여유20GiB·기록 파일시스템 여유12GiB, 죽은 volume 포함 기록9개 합계2GiB·DB/client 임시512MiB |
| 진단 | run 합계16MiB·관찰각256KiB·결과각4MiB·node 로그각2초/128KiB. backend 규칙/counter도 같은 예산에 포함 |
| 시간 | 사례별480초 중 업무390초·마지막 정리90초, 각 새 CI 그룹 두 사례 합계960초. 제품 matrix job별30분이며 workflow 전체 벽시계·운영 RTO가 아님 |

build180초·RDB60초·초기기동90초·client 외부60초·관찰45초·재합류90초·규칙 설치/해제 각15초는 남은 상위 기한으로 다시 제한한다. client 내부 연결/요청10초와 no-quorum 연속10초를 구분한다. 성공·실패 모두 finally에서 run의 규칙·client/helper·node·image·volume/network를 회수하고 최종 자원 부재까지 확인해야 한다. 한도 초과·환경 부적합·정리 실패는 성공이 아니다.

## 실제 실행 근거

네 사례 모두 기준 `3067b0c2dfb3cf261c1a13c74718c016ea19d6e7`의 동일한 최종 구현에서 실행했다. 각 run의 실행 소스28개 해시는 현재 소스와 일치한다. 시간은 이미지·RDB 준비·기동·의도한 무응답·확인·정리를 포함하며 운영 RTO·TPS가 아니다.

| 사례 | 최종 run ID | 전체 실행 | 결과 |
| --- | --- | ---: | --- |
| Q1 | `multi17948859300809a83b701` | 107,098ms | 위반0·현재9사본 일치·자원 회수 |
| Q2 | `multi17948860385013b4458cc` | 138,382ms | 위반0·현재9사본 일치·자원 회수 |
| Q3 | `multi1794885465578c82ef79e` | 174,269ms | 위반0·현재9사본 일치·자원 회수 |
| Q4 | `multi17948856408737e92c441` | 220,434ms | 위반0·현재9사본 일치·자원 회수 |

`quorum-loss`는 정확히 Q1/Q2 두 건, `quorum-partition`은 Q3/Q4 두 건이며 실패·오류·skip0이다. 각 그룹의 합산 사례 시간은245,480ms/394,703ms로960초 한도 안이다. Gradle 포함 실행 로그는 `/tmp/issue138-loss-final-v1.log`(4분18초), `/tmp/issue138-partition-final-v1.log`(6분47초)다. 보존 XML·후보 목록은 `/tmp/issue138-native-results/loss-final`과 `partition-final`에 있다.

가장 큰 기록 합계는16,211,968bytes, 진단5,747,356bytes, 결과2,887,465bytes다. 규칙 설치·해제 전체 작업의 최댓값은12,428ms로15초 안이었다. 네 run 모두 `issues=[]`, `runResourcesRemoved=true`이며 현재 실행의 container·volume·network·image 회수 명령과 빈 최종 목록을 남겼다. source/image/RDB/manifest·member/container/boot/volume·규칙/counter/route·token/원문/최초 receipt·nonce witness는 각 `app-api/build/reports/aeron-funding/multi-matching/<run ID>/result.json`과 같은 폴더의 원자료에서 대조한다.

```bash
cd /Users/0chord/.codex/worktrees/issue148-http-aeron/exchange-core
JAVA_HOME=/Users/0chord/Library/Java/JavaVirtualMachines/corretto-25.0.2/Contents/Home \
./gradlew :app-api:test --rerun -PciTestGroup=quorum-loss --no-daemon --max-workers=1 --console=plain
JAVA_HOME=/Users/0chord/Library/Java/JavaVirtualMachines/corretto-25.0.2/Contents/Home \
./gradlew :app-api:test --rerun -PciTestGroup=quorum-partition --no-daemon --max-workers=1 --console=plain
```

기존 정상9노드1건·#136 매칭 리더 장애2건·#137 자금 리더 장애2건과 관련 경량61개, 합계 앱66개가 통과했다. 구조386개·P01~P09·전체 스타일도 `/tmp/issue138-regression-final-v1.log`의 `BUILD SUCCESSFUL in 9m 27s`로 확인했다. 새 장애4개를 더한 서로 다른 JUnit 사례는456개이며 실패·오류·skip0이다. 같은 테스트 재실행 횟수를 더하지 않았다. CI 배정 스크립트13개 검사는 통과했고, 그룹 배정뿐 아니라 기본 무필터의 전체 후보도 실제 Gradle 후보와 대조한다. 원격 CI는 아직 실행하지 않았다.

## 발견한 문제와 수정

- JSON 배열의 요소 순회와 Docker의 `CAP_NET_ADMIN` 표기를 바로잡았다. 단계별 `originals`를 복사해 미래 응답이 과거 단계에 섞이지 않게 했다. Q1/Q2의 B0/P도 최초 응답 직후 실제 client route와 서비스 결정을 결속한다.
- Aeron이 선거 준비에서 정상 해제하는 recorder는 이전 실제 identity·새 선거·해제 근거로 구분한다. 값을0으로 채우거나 미식별 누락을 허용하지 않는다.
- 실제 client와 응답 관찰 subscription이 같은 UDP 소켓을 공유하도록 한다. tag는 서버에도 전달되므로 Driver마다 다시 시작하는 correlation ID 대신 연결별 UUID tag를 사용한다.
- witness는 필수 합의·Archive·log stream counter만 유한하게 선택하고 전체 덤프가 아님을 명시한다. runtime 관찰 오류는 현재 nonce의 명시적 실패로 게시하여 노드 JVM을 종료시키지 않는다. 서비스의 반쪽 관찰·미식별 자료·overflow는 계속 시험 실패다.
- 실제 UDP 비교에서 OUTPUT DROP은 `SocketException: Operation not permitted`, INPUT DROP은 송신18bytes·수신 차단, 규칙 해제 후에는 정상 수신으로 재현됐다. 로컬 송신 오류가 Aeron manual MDC의 다른 목적지 진행까지 막는 장애를 섞지 않도록 peer는 **양끝 INPUT에서 상대 전체IP를 차단**한다. 양방향·모든 포트의 격리, 정확 규칙·양끝 양수 DROP counter·행렬 검사를 유지한다. client의 자기 namespace INPUT/OUTPUT scope는 유지한다.
- 단일 namespace 적합성 원자료는 `/tmp/issue138-udp-drop-preflight.json`(21.167초, 자원 회수)이다. 실제 peer 격리·정상 과반·복구 성공의 근거는 위 Q3/Q4의9노드 자료로 구분한다. 실패 뒤 custom client 회수로 비워진 run network도 마지막에 제거한다.

초기 컴파일 누락·Kotlin compiler heap OOM은 assertion에 도달하지 않았으므로 행동 수준 TDD Red로 세지 않는다. `gradle.properties`의 compiler daemon1GiB는 거래 노드 heap256MiB와 별개다. 초기 실패 run과 보완 전 Q1/Q2의 성공을 최종 수정본 성공으로 대체하지 않았다.

## 독립 검토와 보장 범위

작성 대화를 상속하지 않은 별도 에이전트가 기준 소스와 전체 고정 snapshot을 비교하고 지적 수정 뒤 재검토했다. 최종 코드 검토본은 `/tmp/issue138-review-v5`, manifest 파일 SHA는 `863f7e87b7fdb33c39d8edde151f5a8db27172b4f07c7d88f8b8ae8995509f13`이며22개 변경 항목이 모두 일치했다. 선거 recorder·egress socket·과반 진행 뒤 minority 재확인 등의 지적은 수정됐고 최종 검토 범위에서 확인된 미해결 코드 결함은0이다.

별도 컨텍스트의 수용 근거 감사는 실제 CLI·nonce·scope/규칙·client/service 결정·단계별 금융 상태·사본·회수를 대조한다. 처음 발견한 단계 map alias와 Q1/Q2 최초 결정 결속 누락을 보완했고 최신 손실 그룹에서 해소를 재확인했다. 최신 Q1~Q4의 전체 원자료 감사에서 확인된 미해결 항목은0이며, 후속 실제 회귀5건과 필수 검사도 통과했다. XML·실행 소스 일치·후보 목록 요약은 `/tmp/issue138-local-verification-summary.json`, 이번 실행17개의 최종 자원 부재 대조는 `/tmp/issue138-resource-cleanup-audit.json`에 보존했다. 실행 후 사용 가능 디스크는 약78GiB였다. 코드 리뷰와 실제 실행 근거 감사는 서로 다른 판단이다.

고정 배치의 노드 손실·자금 peer 분할을 검증한다. 전체 기록 유실·서버/AZ 장애·운영 네트워크의 모든 장애·장기 부하/TPS·보안·담당 이동의 검증을 포함하지 않는다. 제품의 자금 계산·예약/정산 규칙·wire·snapshot 형식은 변경하지 않았다. 학습 자료를 작성한 것을 사용자의 코드 이해나 PR 게시 승인으로 간주하지 않는다.
