# R2 callback CPU·phase·호스트 메모리 압력 독립 대조

기존 no-periodic 진단 산출물만 순서대로 읽었다. 새 부하·제품 코드 변경·계측 도구 변경은 하지 않았다. 이 회차는 periodic statistics를 끈 계측 진단이며, 비계측 성능 기준선이 아니다. 첫 진단의70ms callback을 같은 사건으로 재현한 것은 아니므로 R2 CPU 비율을 그 과거 사건에 소급하지 않는다.

CPU는 `getCurrentThreadCpuTime()`으로 읽은 해당 service platform thread의 user+system 합이다. CPU 시작 읽기는 원래 제품 capture 시작보다 앞, 종료 읽기는 제품 끝보다 뒤에 있다. 따라서 CPU delta는 제품 CPU 시간의 상한으로 보고, `max(0, 제품 wall − CPU delta)`를 제품 off-CPU 경과의 하한으로 사용할 수 있다. 이는 paging·런큐 대기·native blocking·safepoint를 구분하는 원인 태그는 아니다. CPU가 큰 경우에도 kernel fault 처리 비용이 포함될 수 있어 순수 Java 업무 계산으로 단정하지 않는다.

## 같은 callback의 wall·CPU·phase

| node / sequence / kind | advice wall (ms) | 제품 wall (ms) | thread CPU (ms) | 제품 off-CPU 하한 (ms) | 기존 phase에 잡힌 wall |
|---|---:|---:|---:|---:|---|
| 2 / 977840 / EXECUTION | 28.591958 | 28.584208 | 0.291666 | 28.292542 | settlement_input_validation 28.477500ms |
| 2 / 864086 / EXECUTION | 17.592667 | 17.580875 | 0.299000 | 17.281875 | result_validation 17.525083ms |
| 2 / 863989 / EXECUTION | 28.540000 | 28.531584 | 26.127833 | 2.403751 | settlement_candidate 28.446042ms |
| 1 / 978182 / EXECUTION | 19.833000 | 19.826833 | 3.219958 | 16.606875 | settlement_input_validation 19.768125ms |
| 1 / 950595 / EXECUTION | 19.857833 | 19.838875 | 0.154416 | 19.684459 | result_validation 19.815916ms |
| 0 / 858509 / EXECUTION | 16.696917 | 16.687000 | 0.147626 | 16.539374 | settlement_candidate 16.513625ms |
| 0 / 981640 / ADMISSION | 21.448875 | 0.073250 | 20.257917 | 0.000000 | 제품 <1ms: phase 문자열 없음 |
| 2 / 978034 / EXECUTION | 16.230500 | 0.089208 | 11.938251 | 0.000000 | 제품 <1ms: phase 문자열 없음 |

E977840과 E864086은 각각28.29ms·17.28ms 이상이 해당 thread의 연속 CPU 계산 밖 경과다. 두 사건 모두 GC·기록 VM operation·동일 service park/monitor와 직접 겹치지 않았다. 그 순간 validation phase가 열려 있었던 것이며, validation 계산 자체가28ms CPU를 썼다는 뜻은 아니다.

반대로 E863989는 wall28.54ms에 CPU26.13ms가 기록돼 CPU를 소비한 긴 사건도 존재한다. settlement_candidate에28.45ms wall이 기록됐지만 해당 thread의 Java/native stack 표본은0이었고, user/kernel CPU 구분을 따로 수집하지 않았으므로 Java 정산 알고리즘 또는 page fault의 CPU 비용 중 하나로 확정하지 않았다.

GC와 직접 겹친 구체 사례도 있다. node1 E950595는 제품19.838875ms·CPU0.154416ms이며, G1 GC 교집합18.112709ms다. node0 E858509는 제품16.687ms·CPU0.147626ms, GC 교집합7.283625ms였다. GC와 VM operation을 합산하지 않는다.

node0 A981640과 node2 E978034는 advice 구간의 CPU가 높지만 제품 capture는 각각0.07325ms·0.089208ms에 불과하다. 이 CPU를 제품 금융 계산에 배정하면 안 된다. 측정 시작/끝의 handoffFallbacks·degraded·droppedSamples는 모두0이어서 full metric queue의 동기 fallback을 원인으로 지목할 증거는 없다. 다만 CPU 읽기·제품 capture 바깥 metric/finally 수행과 kernel 동작을 더 잘게 구분한 것은 아니다.

## 긴 사건의 범위

| node | 제품 ≥1ms callback | 제품 ≥10ms callback | 제품 ≥1ms 중 CPU/advice wall <10% | 전체 측정 advice wall 합 (ms) | 같은 범위 thread CPU 합 (ms) |
|---|---:|---:|---:|---:|---:|
| 0 | 844 | 2 | 616 | 7368.674311 | 4055.751939 |
| 1 | 191 | 8 | 104 | 5970.239086 | 3958.625336 |
| 2 | 930 | 5 | 685 | 8092.701199 | 4407.713387 |

각 노드의 admission/execution이 측정 건수와 일치하고 DataLoss는0이다. 첫 진단의70ms 같은 제품 callback은 R2에서 관측되지 않았다. 표는 이번 회차의 callback 자체가 CPU 수행과 off-CPU 경과를 함께 포함함을 보여준다. phase timer는 중첩된 wall timer이므로 callback/execution_total과 하위 phase를 더하면 안 된다. periodic ClassLoaderStatsOperation은 세 노드 모두0이었다. G1·JFRSafepointWrite 등 나머지 VM operation은 별도로 남겨 대조했다.

## 호스트 메모리 압력

원문 vm_stat의 page 크기는16KiB다. 아래는 저장된 before/after의 machine-wide 차분이며 정확한 제품 측정10초만의 비용 또는 특정 JVM의 fault 수로 귀속하지 않는다.

| 항목 | 페이지/이벤트 차분 | 페이지 환산 (MiB) |
|---|---:|---:|
| Decompressions | 559,139 | 8736.546875 |
| Compressions | 610,535 | 9539.609375 |
| Swapins | 1,589 | 24.828125 |
| Swapouts | 15,708 | 245.437500 |
| Pageins | 8,203 | 128.171875 |
| Pageouts | 224 | 3.500000 |

종료 시 compressor 점유는7428.515625MiB였고, swap used는 `18720.69M`이었다. 압축·해제·swap이 활발한 호스트에서 실행됐다는 근거다. 다만 per-process fault 추적과 OS scheduler trace가 없어 E977840의28ms를 특정 paging 작업으로 직접 배정하지 않는다. 높은 system CPU의 개별 출처도 확인하지 않았다.

R2 계측 완료 TPS=26315.046005, p99=132.402167ms다. 비계측 대조 약40.8k TPS·62.67ms p99, 앞선 일반42~47ms 결과와 분리한다. CPU 읽기·외부 agent·JFR의 부담과 과부하 호스트 환경이 지연을 만들거나 키웠을 가능성은 유지한다.

원시 대표 callback의 epoch 범위·CPU·16개 phase·같은 스레드 표본·GC/VM operation 교집합은 `cpu-memory-independent.json`에 보존했다. 기존 `.jfr`·`.jsonl.gz`는 변경하지 않았다.


## CPU 경계 원시 필드와 제품 바깥 구간

E977840·E864086 등 대표 event 전체를 JSON의 `proofRawCallbacks`에 보존했다. `cpuStartedNs`·`cpuDurationNs`·adviceEnteredNs/ExitedNs·productCallbackStartedNs/CompletedNs·longPhasesNs 원문이 포함된다. CPU nanoTime과 wall nanoTime은 서로 다른 시계이므로 빼지 않는다. 원래 source 호출 순서로 CPU 구간이 제품 capture를 포함함을 확인했다. advice wall−CPU를 엄밀한 제품 off-CPU 하한이라고 쓰지 않고, 제품 wall−CPU만 사용했다.

| node / sequence | 제품 시작 전 (ms) | 제품 capture (ms) | 제품 종료 뒤 (ms) | thread CPU (ms) |
|---|---:|---:|---:|---:|
| 0 / 981640 | 0.004208 | 0.073250 | 21.371417 | 20.257917 |
| 2 / 978034 | 0.003542 | 0.089208 | 16.137750 | 11.938251 |

이 표는 원래 clock 경계로 확인된 위치만 분리한다. 제품 밖 긴 경과를 특정 metric 연산 또는 JFR 연산의 CPU로 확정하지 않는다. 동기 fallback0이라는 근거 이상으로 내부 원인을 추정하지 않는다.

## Linux guest cgroup과 macOS host의 관측 범위

| guest | CPU throttle 횟수 차분 | throttled 시간 (ms) | pgfault 차분 | pgmajfault 차분 |
|---|---:|---:|---:|---:|
| node0 | 1 | 18.618000 | 3597 | 0 |
| node1 | 0 | 0.000000 | 3727 | 0 |
| node2 | 1 | 0.059000 | 4215 | 0 |
| client | 0 | 0.000000 | 20073 | 1 |

각 guest의 CPU 예산은200000/100000μs(2CPU)이며 OOM 차분은0이다. Linux guest의 pgmajfault와 macOS host의 compressor/swap은 다른 층의 관측이다. 노드 pgmajfault 차분0으로 host의 압축 메모리 복원이나 vCPU 실행 대기를 배제하지 않는다. 반대로 host 압축·swap 차분만으로 특정 callback의 off-CPU 경과를 그 작업에 직접 귀속하지 않는다. cgroup 경계에는 진단 제어·최종 CAPTURE·client dump도 포함된다.

## R1의 진단 safepoint를 일반 성능에서 제외

R1 node0의 ClassLoaderStatsOperation은 epoch1793373247542690223–1793373247606740807ns,64.050584ms다. caller는JFR Periodic Tasks이며, R1의65.275542ms force와66.122875ms park에64.050584ms씩 겹쳤다. 이 두 긴 경과는 진단 자체의 영향이 확인된 사례다. 이를 일반42~47ms 또는 비계측 대조62.67ms p99의 원인으로 사용하지 않는다. R2에서 periodic ClassLoaderStatsOperation이0인 사실도 첫 진단의 다른70ms callback 원인을 소급 증명하지 않는다.

여기서 추가 측정 없이 분석을 종료했다. 원본 데이터는 유지했다.
