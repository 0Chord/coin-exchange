# Archive sync0 지속 부하 기록 — 2026-10-29

**목표는 300초였지만 실제 부하는 16.937초에 익명 메모리 안전 기준으로 종료했다.** 신규 주문300,000건의 전체 완료 관측은17,712건/초였다. 5분 지속 성공·OOM 발생·엔진의 절대 최대 처리량으로 기록하지 않는다.

## 이번에 바꾼 설정

- Archive fileSyncLevel만 **0**, catalogFileSyncLevel·Consensus fileSyncLevel은 **2**로 유지했다. 세 노드 실제 READY의 `[0,2,2]`를 확인했다.
- 기본 제품 소스와 기존 sync2 런타임은 변경하지 않았다. 별도 실험 복사본에서 노드 설정과 검사·설명만 변경했다.
- 기존1,038개 런타임 파일 중1,036개가 동일하고, 설정에 관련된 `DevelopmentFundingNode.class`·`HttpPerformanceCluster.class`만 달랐다. 금융 domain JAR은 그대로다.
- 외부 Java 발생기·조정 도구의 변경은300초와 관련 제한 시간·설명이다. 전송률 제한은 없으며, 노드당2CPU·2GiB·Xmx1536MiB·G1, 발생기2CPU·2GiB·Xmx1GiB, BTC/SOL/SUI, 수수료와 묶음 크기를 유지했다.
- 동일 Mac의 Docker VM8CPU·약7.75GiB에서 실행했다. HTTP/Gateway는 제외했다.
- sync0는 Archive 데이터의 강제 동기화를 생략하는 별도 내구성 조건이다. sync2와 같은 저장 보장을 전제로 비교하지 않는다. 이번에 force 호출 자체를 별도 계측한 것은 아니다.

## 작업 부하와 검증 범위

초기 SELL12,000건·BUY12,000건 예열 후 새 SELL6,000건→BUY6,000건을 반복했다. 같은 클러스터와 같은 상태 객체를 유지하고 주문·원장·테스트 관측 이력을 지우지 않았다. 매도 준비·입력 생성·묶음 사이 대기는 전체 측정 시간에 포함한다. 각 묶음은 ACK·세 노드 완료를 확인한 뒤 다음 묶음으로 진행하며, 한 전송 소유자당 최대3,000건이다. 이 발생기 방식도 결과에 포함되므로 최대 지속 TPS의 증명은 아니다.

- 실제 측정: **16.937초** / 목표300초, `durationAchieved=false`.
- 새 SELL150,000건 + BUY150,000건 = **300,000건**, 25개 묶음 쌍.
- 마켓별 새 주문: BTC·SOL·SUI 각각100,000건.
- 예열 포함 승인324,000건·노드 완료 표식972,000개를 전수 대조했다. 요청ID·승인 순번·세 노드 로그 위치·기대 체결/정산 건수·표식 개수의 검증 오류는0건이다.
- 세 노드 정상 종료·OOMKilled=false·런타임 불변성을 확인했다. 시간 측정 중 전체 잔고·원장 스냅샷 대사는 실행하지 않았다.

## 관측 수치

| 항목 | 이전 sync2 연속 시험 | 이번 Archive sync0 |
|---|---:|---:|
| 실제 측정 시간 | 23.293초 | 16.937초 |
| 새 SELL+BUY 주문 | 324,000건 | 300,000건 |
| 전체 주문 완료 관측 TPS | 13,910 | 17,712 |
| 같은 전체 시간의 체결 BUY/초 | 6,955 | 8,856 |
| BUY 접수 p95 | 360.5ms | 271.9ms |
| BUY 접수 p99 | 505.3ms | 400.3ms |
| BUY 세 노드 완료 관측 p95 | 812.3ms | 699.6ms |
| BUY 세 노드 완료 관측 p99 | 1280.7ms | 859.5ms |

전체 주문 TPS에는 매칭 없이 주문장에 들어간 SELL도 포함한다. 체결 BUY/초는 같은 전체 벽시계 시간에 완료된 BUY 수다. 접수 지연은 실제 전송 시작→ACK 수신, 완료 지연은 같은 시작→세 노드의 마지막 완료 표식 수신으로 계산하며 통신·관측 스케줄링도 포함한다.

각 설정 한 번씩의 관측이며, 리더는 이전 노드1·이번 노드2였다. 처리 건수와 측정 시간도 다르다. 이번 수치가 더 좋았지만 그 차이를 sync0의 확정적 개선율로 단정하지 않는다. 이전의 SELL을 미리 준비한 BUY90k 시험과도 작업 부하가 다르다.

## 5초 구간별 완료 처리량

| 실제 구간 | 전체 주문 완료 TPS | 체결 BUY 완료/초 | BUY 완료 관측 p99 |
|---|---:|---:|---:|
| 0–5.000초 | 24,433 | 12,000 | 289.1ms |
| 5–10.000초 | 17,855 | 8,688 | 546.9ms |
| 10–15.000초 | 13,861 | 6,912 | 719.3ms |
| 15–16.937초 | 9,941 | 6,194 | 897.1ms |

마지막 구간은 실제1.937초로 나눴다. 처음5초24,433주문/초에서10–15초13,861주문/초로 내려갔다. 원인별 기여율을 이번 비교에서 확정한 것은 아니다.

## 5분 전에 멈춘 이유

조정 프로그램 상대 시각 약16.030초에 노드0의 익명 메모리가1,379,008,512B로1.25GiB 안전 기준을 넘었다. 새 묶음을 중단하도록 요청하고 진행 중 묶음의 ACK·세 노드 완료를 확인한 뒤 종료했다. 종료 이유는 `NODE_ANON_MEMORY_GUARD`다.

모든 자원 표본의 OOM·OOM kill은0이었다. 종료 후 노드별 힙 사용량은 약904–912MB로 최대 힙1.5GiB보다 작았다. 익명 메모리에는 커밋된 힙·네이티브 메모리가 포함되고, 금융 기록과 테스트 관찰 이력이 함께 누적된다. **안전 기준 도달을 실제 힙 고갈이나 제품의 최대 저장 건수로 해석하지 않는다.** sync0가 상태 보관량까지 줄이는 변경은 아니다.

## 실행 근거

- [요약 JSON](/Users/0chord/.codex/visualizations/2026/09/23/01a0ce63-9327-7f32-a7e0-3429380284d8/issue142-performance/capacity-goal/direct-core-20261028/multi/execution-tail-20261028/request-correlation-20261028/goal-optimize-20261028/current-throughput-record-20261029/sync0-five-minute/measurement-summary.json)
- [5초 구간 CSV](/Users/0chord/.codex/visualizations/2026/09/23/01a0ce63-9327-7f32-a7e0-3429380284d8/issue142-performance/capacity-goal/direct-core-20261028/multi/execution-tail-20261028/request-correlation-20261028/goal-optimize-20261028/current-throughput-record-20261029/sync0-five-minute/run-01-intervals.csv)
- [sync2·sync0 관측 비교](/Users/0chord/.codex/visualizations/2026/09/23/01a0ce63-9327-7f32-a7e0-3429380284d8/issue142-performance/capacity-goal/direct-core-20261028/multi/execution-tail-20261028/request-correlation-20261028/goal-optimize-20261028/current-throughput-record-20261029/sync0-five-minute/comparison.json)
- [실행 명령과 실제 시간](/Users/0chord/.codex/visualizations/2026/09/23/01a0ce63-9327-7f32-a7e0-3429380284d8/issue142-performance/capacity-goal/direct-core-20261028/multi/execution-tail-20261028/request-correlation-20261028/goal-optimize-20261028/current-throughput-record-20261029/sync0-five-minute/execution.json)
- [세 노드 설정·종료 검증](/Users/0chord/.codex/visualizations/2026/09/23/01a0ce63-9327-7f32-a7e0-3429380284d8/issue142-performance/capacity-goal/direct-core-20261028/multi/execution-tail-20261028/request-correlation-20261028/goal-optimize-20261028/current-throughput-record-20261029/sync0-five-minute/validation-summary.json)
- [복사본 런타임 변경 범위](/Users/0chord/.codex/visualizations/2026/09/23/01a0ce63-9327-7f32-a7e0-3429380284d8/issue142-performance/capacity-goal/direct-core-20261028/multi/execution-tail-20261028/request-correlation-20261028/goal-optimize-20261028/current-throughput-record-20261029/sync0-five-minute/runtime-verification.json)
- [요청 원문 분석 코드](/Users/0chord/.codex/visualizations/2026/09/23/01a0ce63-9327-7f32-a7e0-3429380284d8/issue142-performance/capacity-goal/direct-core-20261028/multi/execution-tail-20261028/request-correlation-20261028/goal-optimize-20261028/current-throughput-record-20261029/sync0-five-minute/analyze_records.py)
- [독립 정확성 검토 범위](/Users/0chord/.codex/visualizations/2026/09/23/01a0ce63-9327-7f32-a7e0-3429380284d8/issue142-performance/capacity-goal/direct-core-20261028/multi/execution-tail-20261028/request-correlation-20261028/goal-optimize-20261028/current-throughput-record-20261029/sync0-five-minute/independent-accuracy-review.json)

종료한 이번 실행의 컨테이너·볼륨·이미지를 정리하고, 큰 원문은 압축 후 복원 SHA-256을 대조해 보관한다. 정리 수치는 cleanup-final.json에 기록한다. 기본 제품의 sync2 설정·선택 런타임은 유지하며 커밋·push·PR 생성은 하지 않았다.

정리 수치: 원문 압축으로 477,346,706B, 중복 런타임 제거로 98,715,752B의 논리 용량을 줄였다. 실행 원본은 선택 sync2 기반+9개 overlay 파일로 재구성할 수 있다. 최종 호스트 여유는 약 76.0GiB이며, 논리 절감량이 전체 호스트 사용량의 순감소를 뜻하지 않는다.
