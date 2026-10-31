# Service progress 변경의 독립 측정 검증

판정은 **측정 유효, 전체 성능 개선의 일반화에는 한계 있음**이다. 여섯 실행의 원문·재무·복제·관측·정리 검사에서 모순을 찾지 못했다. 완료 TPS와 p99의 중앙값은 개선됐지만 마지막 쌍의 완료 TPS·p95·p99는 악화됐다. 마지막 after를 포함한 전체 여섯 회로 계산했다.

이 검증에서는 새 부하·빌드·테스트·제품 수정을 하지 않았다. 읽은 자료는 [paired-runs.json](paired-runs.json), `run-1-before.json`부터 `run-6-after.json`, 각 항목이 가리키는 원문 summary·client 로그·최종 상태 캡처·시작/끝 checkpoint·vm_stat/swap 원문이다. 독립 계산 뒤 [comparison-summary.json](comparison-summary.json)의 실행별·중앙값·paired 변화율 116개 숫자를 대조했으며 불일치는 0개다. 정밀 수치와 검사 결과는 [measurement-independent-review.json](measurement-independent-review.json)에 보존했다.

## 조건과 전후 격리

모두 직접 Aeron 3노드, 실제 sync `[2,2,2]`, BTC/SOL/SUI, Protobuf, partial-maker, admission 512 / execution 1,024, async ACK, 20초 예열 / 10초 offering, sample/funding cap 2,000,000이다. 실행 명령은 기존 `ha-trading-probe.py`이며 진단 agent가 붙은 이전 JFR 실행과 섞지 않았다. 고유 run 경로를 정규화하면 compose override 여섯 개가 같다.

기준과 후보의 runtime manifest는 각각 1,344개 실제 파일과 일치한다. 차이는 다음 제품 클래스 세 개뿐이며 `test-classes/`와 `lib/`는 바이트가 같다.

- `DevelopmentFundingAdapter.class`
- `TradingNodeMetricHandoff.class`
- `TradingNodeMetricHandoff$Companion.class`

기존 `build/regression-fix-20261030/source-v2/`의 520개 파일도 기존 manifest와 모두 일치한다. 이번 `before-source-manifest.json`은 4개 파일의 부분 manifest이며, 그 네 항목도 원래 520개 manifest와 같다. 부분 manifest를 전체 원본이 사라졌다는 뜻으로 해석하지 않았다.

## 여섯 실행과 리더

| 실행 | 구성 | 리더 member | 측정 BUY | All-three TPS | All-three p95 / p99 (ms) | ACK 포함 cohort TPS |
|---|---|---:|---:|---:|---:|---:|
| 1 | before | 2 | 464,579 | 46,390.689531 | 32.915833 / 63.052500 | 46,385.541431 |
| 2 | after | 2 | 494,589 | 49,384.838560 | 32.815458 / 51.072375 | 49,384.429490 |
| 3 | after | 0 | 486,056 | 48,536.924276 | 31.882500 / 43.833333 | 48,530.227078 |
| 4 | before | 0 | 449,433 | 44,901.660988 | 39.619125 / 71.464542 | 44,900.372604 |
| 5 | before | 2 | 402,872 | 40,226.699362 | 37.768875 / 68.655000 | 40,218.592258 |
| 6 | after | 2 | 396,517 | **39,432.376119** | **44.479541 / 73.443167** | **39,423.734863** |

각 실행 안에서 시작/끝 리더 역할은 바뀌지 않았다. 리더는 실행 사이에 달라졌지만, 1↔2는 member 2, 4↔3은 member 0, 5↔6은 member 2로 **각 비교 쌍의 리더가 같다**. 구성별 리더 분포도 같다. 리더가 다르다는 이유로 실행을 무효화하거나 제외할 근거는 없다. 다만 고정된 특정 member 또는 다른 호스트에서 같은 효과가 난다는 증거도 아니다.

두 번째 쌍은 시간상 after 3이 before 4보다 먼저다. 변화율은 항상 `after / before - 1`로 계산하므로 **4→3**이다. 3→4로 뒤집지 않았다.

## 중앙값과 paired 변화

아래는 구성별 세 실행의 중앙값이다. 서로 다른 지표의 중앙값은 같은 한 요청이나 같은 한 실행의 분해가 아니며, pooled latency percentile도 아니다.

| 지표 | Before | After | 변화율 |
|---|---:|---:|---:|
| Admission TPS | 44,923.316406 | 48,565.555252 | +8.1077% |
| Admission p95 / p99 (ms) | 17.112792 / 34.809334 | 16.574875 / 25.757167 | −3.1434% / −26.0050% |
| All-three TPS | 44,901.660988 | 48,536.924276 | +8.0961% |
| All-three p95 / p99 (ms) | 37.768875 / 68.655000 | 32.815458 / 51.072375 | −13.1151% / −25.6101% |
| 최종 ACK 포함 cohort TPS | 44,900.372604 | 48,530.227078 | +8.0842% |
| 서비스 CPU (ns/order) | 11,193.681940 | 10,508.753042 | −6.1189% |
| 서비스 할당 (B/order) | 11,244.875611 | 11,239.459984 | −0.0482% |
| Execution offer 벽시간 (ns/order) | 490.514893 | 158.290872 | −67.7297% |
| 추가 execution offer / 1,000주문 | 0.991216 | 1.143215 | **+15.3346%** |

CPU·할당은 같은 노드의 끝−시작 counter 차이를 측정 주문 수 N으로 나눈 뒤 세 노드를 산술평균하고, 이 실행별 평균 세 개의 중앙값을 냈다. Execution offer도 `execution_offer.totalNs / N`의 세 노드 평균이다. CPU·할당 checkpoint에는 CAPTURE와 경계 제어 비용이 포함된다. Phase totalNs는 벽시간이며 순수 CPU가 아니다.

| 쌍, before→after | All-three TPS 변화 | All-three p95 변화 | All-three p99 변화 | ACK cohort TPS 변화 | 서비스 CPU/order 변화 | Offer 벽시간/order 변화 |
|---|---:|---:|---:|---:|---:|---:|
| 1→2 | +6.4542% | −0.3049% | −19.0002% | +6.4651% | −1.5666% | −67.7297% |
| 4→3 | +8.0961% | −19.5275% | −38.6642% | +8.0842% | −9.6187% | −68.0861% |
| 5→6 | **−1.9746%** | **+17.7677%** | **+6.9742%** | **−1.9763%** | −7.7709% | −68.0093% |

앞 두 쌍은 완료 처리량과 tail이 개선되고 마지막 쌍은 악화됐다. 따라서 모든 실행에서 개선됐다거나 안정적인 +8% 효과가 재현됐다고 표현할 수 없다. 서비스 CPU와 execution offer 벽시간은 마지막 쌍에서도 낮아졌지만 전체 경로 TPS·tail 개선을 보장하지 않았다. 할당 차이는 약 0.05%로 매우 작으며 메모리 최적화 성과로 내세울 근거가 부족하다.

## Execution offer 횟수와 비용

각 노드의 phase count는 N이며 실제 offer 시도 횟수는 별도 counter로 검증했다. Phase의 count가 N이라고 실제 시도가 한 번씩이었다는 뜻은 아니다. 현재 측정 경계의 벽시간에는 local offer와 retry idle이 포함된다.

| 실행 | 노드 0 / 1 / 2 추가 시도 수 | 노드 평균 추가 시도 | 추가 시도 / 1,000주문 | Offer 벽시간 노드 평균 (ns/order) |
|---|---:|---:|---:|---:|
| 1 before | 464 / 472 / 456 | 464.000000 | 0.998754 | 490.514893 |
| 2 after | 553 / 613 / 516 | 560.666667 | 1.133601 | 158.290872 |
| 3 after | 530 / 587 / 550 | 555.666667 | 1.143215 | 161.736504 |
| 4 before | 438 / 450 / 442 | 443.333333 | 0.986428 | 506.790074 |
| 5 before | 406 / 393 / 399 | 399.333333 | 0.991216 | 481.372290 |
| 6 after | 453 / 475 / 447 | 458.333333 | 1.155898 | 153.994506 |

추가 시도는 `offerAttempts.execution - N`이다. 원시 추가 횟수는 실행별 N이 달라 단독 비교하지 않는다. 주문당 추가 시도는 세 쌍 모두 +13.50%, +15.89%, +16.61%로 늘었다. 따라서 비용 감소를 **“재시도가 줄었다”**고 설명할 수 없다. 뒷받침되는 관측은 **“추가 시도는 소폭 늘었지만 execution offer 벽시간/주문은 세 쌍 모두 약 68% 낮았다”**이다. 이 감소를 전체 CPU 절약이나 TPS 향상률과 동일시하지 않는다.

## 원문 유효성 검사

여섯 copied summary는 원래 report의 summary와 완전히 같고 paired aggregate의 선택 지표도 같다. 각 원문 client 로그에는 완전한 HA_PERFORMANCE 이벤트가 하나이며, 추가로 계산한 service/collector interval을 제외한 필드가 저장 결과와 같다.

- 세 노드 최종 상태 18개를 직접 읽었다. 실행별 state hash가 세 사본에서 같고 실제 sync `[2,2,2]`, pending 0, retained receipts 0, partial maker 3개다.
- 각 실행의 예열+측정 BUY 수로 buyer/seller USDT 잔액, BTC/SOL/SUI 수량·hold, maker 원래/잔여 수량, 수수료 `BUY 수×30`, 마지막 admission 순번과 buyer/seller ACK floor를 재계산해 일치시켰다. 실행 사이 N이 다르므로 실행 사이 hash 동일성을 요구하지 않았다.
- 각 노드의 측정 admission/execution callback과 marker offered/sent가 N개다. Collector는 3N frames, N correlated responses, N drained completions다. 모든 marker는 전달됐으며 최종 실패·queue 잔여가 없다.
- CPU·할당·phase count/total·offerAttempts의 same-node checkpoint 차이를 직접 다시 계산했다. 서비스 스레드와 역할이 유지된다. 시작/끝/최종 metrics는 valid이며 dropped/degraded/observationFailures/handoffFallbacks가 모두 0이다.
- 실제 offering 창과 drain 및 최종 ACK를 분모에 포함한다. `cohortElapsedIncludingAckNs = actualOfferingWindowNs + drainAfterSendNs = phaseElapsedIncludingAckNs`가 성립하고, 모든 rate 산술이 맞는다. Raw span으로 유리한 분모를 고르지 않았다.
- Sample cap 미도달, malformed frame 0, collector retained 0, 설정된 미응답·미완료 한도 이하이며 gate-mask 시간은 겹치지 않고 창 안에 있다.
- 모든 disk sample이 통과했다. 최대 기록 할당은 986,517,504B, 최소 host free는 83,255,463,936B다. 1.75GiB 조기중단선·2GiB cap·8GiB reserve를 지켰다. Client 회수·exit 0·각 owned-project cleanup 성공과 빈 cleanupFailures를 확인했다. 이 읽기 검증에서는 Docker에 재접속해 최종 이미지 삭제 상태를 별도로 조회하지 않았다.

## Host 메모리 관측과 한계

vm_stat와 swap 전후는 **호스트 전체의 시작·부팅·예열·측정·drain·CAPTURE·정리 전후 범위**다. 측정 10초 또는 특정 JVM·요청의 값이 아니다. 페이지 크기는 원문상 16,384B다.

| 실행 | 전체 창 Swapins / Swapouts (pages) | 호스트 swap used 전→후, 원문 M 표기 |
|---|---:|---:|
| 1 before | 3,138 / 70,620 | 17,027.81 → 18,091.25 |
| 2 after | 1,805 / 6,496 | 18,091.25 → 18,160.75 |
| 3 after | 2,505 / 10,776 | 18,160.75 → 18,289.12 |
| 4 before | 2,323 / 3,420 | 18,289.12 → 18,310.56 |
| 5 before | 2,561 / 9,668 | 18,310.56 → 18,421.62 |
| 6 after | 6,627 / 67,056 | 18,421.62 → 19,421.38 |

원문은 시간에 따른 host 압박 변화와 swap 활동을 보여준다. 마지막 창에서 swap이 늘었지만 이를 마지막 after의 악화 원인이나 그 요청의 p99 지연으로 귀속할 수 없다. 첫 before 창도 Swapouts가 컸다. 이 자료를 마지막 after를 제외하는 사유로 쓰지 않았다. 반대로 이 변동이 있는 같은 호스트의 짧은 세 쌍만으로 코드의 정확한 인과적 개선율을 확정하기도 어렵다.

현재 결과는 local execution-offer 비용 감소와 개선된 중앙값을 지지하지만, 마지막 쌍의 처리량·tail 악화도 함께 보존해야 한다. 지속 최대 용량·장시간 안정성·HTTP 처리량·원격 장애 RPO0를 입증한 측정은 아니다.
