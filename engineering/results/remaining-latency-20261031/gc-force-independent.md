# 현재 bounded 런타임의 GC·FileForce·스레드 대조 — 독립 분석

2026-10-31. 기존 진단 산출물을 읽어 계산했다. 새 부하·빌드·측정 도구 변경은 하지 않았다. 원본 `.jfr`·`.jsonl.gz`·cohort binary는 보존했다.

진단 273,087건의 실제 클라이언트 세 사본 완료 RTT 중 가장 느린 2,731건을 선택했다. 경계는 129.044833ms, 최대는 220.539333ms다. 이 계측 실행의 완료 TPS는 27,244.329, p99는 129.044833ms다. 일반 실행의 42~47ms p99에 이번 수치를 기여율로 확대하지 않는다. 프로파일링이 지연을 새로 만들거나 키웠을 가능성도 제외할 수 없다.

## 직접 연결한 결과

세 노드 모두 측정 접수·실행 callback이 각각273,087개이며, tail의 두 callback 5,462개를 누락·중복 없이 연결했다. 위치는 header position과 observation position이 일치했고 계측 실패 및 JFR DataLoss는0이었다. 아래 GC 수는 callback별 시계 오차의 안쪽·바깥쪽 경계를 모두 계산했으며 두 판정이 같았다.

| 항목 | node0 | node1 | node2 |
|---|---:|---:|---:|
| tail 두 제품 callback 합 p50 (ms) | 0.010708 | 0.009251 | 0.008292 |
| tail 두 제품 callback 합 p99 (ms) | 1.010625 | 0.786792 | 1.103291 |
| tail 접수 끝→실행 시작 하한 p50 (ms) | 42.281041 | 62.388957 | 56.372957 |
| tail 접수 끝→실행 시작 하한 p99 (ms) | 193.061710 | 185.234000 | 186.359666 |
| tail 자기 callback에 GC 겹침 (건) | 0 | 0 | 0 |
| tail 접수 시작→실행 끝에 GC 겹침 (건) | 79 | 0 | 0 |
| 위 구간의 GC 교집합 최대 (ms) | 9.091334 | 0.000000 | 0.000000 |
| 측정 callback 창의 GC union (ms) | 150.737917 | 117.152582 | 99.650249 |

이 tail 집합의 긴 구간은 자기 금융 계산 callback 자체보다 접수 뒤 실행 callback에 도달하기 전 구간에 있다. 직접 GC 정지로 대부분의 tail을 설명할 근거는 없다. 단, 앞선 GC가 다른 요청의 처리를 밀어 backlog를 만든 간접 영향과 다른 JVM의 GC 영향은 배제하지 않았다.

## Archive force와 실제 요청 위치

| 항목 | node0 | node1 | node2 |
|---|---:|---:|---:|
| 전체 recording FileForce event 수 | 2711 | 2681 | 2762 |
| 전체 recording FileForce p50 (ms) | 2.224917 | 2.252791 | 2.281083 |
| 전체 recording FileForce p99 (ms) | 15.676833 | 16.507667 | 15.904542 |
| 전체 recording FileForce 최대 (ms) | 82.575167 | 115.297083 | 83.073292 |
| 측정 callback 창의 force union (ms) | 8440.534281 | 8425.893990 | 8750.405122 |
| 측정 창 force와 GC 교집합 (ms) | 142.985709 | 79.036708 | 92.428083 |
| tail과 연결한 고유 Archive 블록 수 | 32 | 36 | 36 |
| 명령별 연결 force p50, 중복 가중 (ms) | 5.469458 | 7.461208 | 7.232958 |
| 명령별 연결 force p95, 중복 가중 (ms) | 81.069459 | 115.297083 | 42.436292 |

각 callback 끝 위치P에 `block.startPosition < P <= block.endPosition`을 적용하고, 그 ArchiveBlock 안에 완전히 포함되는 동일 OS thread의 FileForce를 정확히 한 개씩 연결했다. 한 블록을 여러 접수·실행 명령이 공유한다. 따라서 명령별 force 분위수는 같은 event를 여러 번 포함하며, 횟수·경과시간 합을 물리 flush 횟수·시간으로 표현하면 안 된다. 서로 다른 노드의 force 시간도 더하지 않는다. FileForce 경과는 native I/O·선점·JVM 정지를 포함하므로 순수 물리 디스크 시간으로 단정하지 않는다.

## 대표 node1 블록의 보존 필드

recordingId=0, 논리 범위 `(224664512, 224742208]`, OS thread=28 (`archive-conductor`). 실제 tail 607요청의 명령607개(접수205·실행402)가 이 블록에 속한다. force=115.297083ms, metadata=True, GC 교집합=0.000000ms.

| 사건 | 시작 epoch ns | 종료 epoch ns |
|---|---:|---:|
| ArchiveBlock | 1793373244680349180 | 1793373244800961806 |
| FileForce | 1793373244685621889 | 1793373244800918972 |

| admission sequence | 연결 단계 | callback log position | client RTT (ms) | force 끝→제품 callback 시작 범위 (ms) |
|---|---|---:|---:|---:|
| 877086 | ADMISSION | 224681536 | 211.607083 | 4.174625–4.174792 |
| 877087 | ADMISSION | 224681664 | 211.827792 | 4.274959–4.275084 |
| 877088 | ADMISSION | 224681792 | 211.831167 | 4.298375–4.298459 |
| 876640 | EXECUTION | 224664640 | 191.232542 | 0.498043–0.498168 |
| 876641 | EXECUTION | 224664768 | 191.235876 | 0.690208–0.690250 |
| 876642 | EXECUTION | 224664896 | 191.237042 | 0.712668–0.712709 |

원시 callback의 JFR 시각·nanoTime·제품 시작/끝, 각 event의 offset 경계, client 송신·응답·리더/세 사본 수신 시각은 `gc-force-independent.json`의 `representativeNode1Block.examples`에 보존했다. node2의 같은 논리 범위 force는42.436292ms이고 직접 GC 겹침은0이었다. 이 비교는 순수 네트워크나 물리 장치 시간의 분할이 아니다.

## 공유 CPU·실제 park·클라이언트 관찰

기존 `TradingAeronRuntime.idleStrategy()`의 최대 요청 park는100μs다. 측정 중 service ThreadPark 최장은 node0=66.122875ms, node1=26.763291ms, node2=26.946750ms이며 각 사건의 GC 교집합은0이다. 세 노드의 약26.8~26.9ms park가 가까운 epoch에 나타나고, node0의66.1ms park와65.3ms force도 같은 시기에 겹친다. 추가로 원본 JFR를 읽자 node0의66.1ms park와65.3ms force에는 JFR Periodic Tasks가 호출한 `ClassLoaderStatsOperation`의64.050584ms safepoint가 정확히 겹쳤다. 이 두 사건은 진단 자체의 영향이 실제로 확인된 사례이며 비계측 성능의 원인으로 보고하면 안 된다. 다른 노드의 가까운 epoch 사건을 비교하는 것은 동일 Docker VM의 기록 시계가 충분히 정렬됐다는 가정 아래의 시간상 동시성이다. 직접 OS 스케줄러 추적이 없어 나머지 경과의 호스트 선점·VM 정지·I/O 경합 등을 구분하지 못했다. 서로 다른 JVM nanoTime을 빼지 않았다.

측정 service monitor-enter event는 세 노드 모두0이었다. >=1ms 문턱 이하의 경합은 관측하지 못한다. Archive native sample에서는 `UnixFileDispatcherImpl.force0`이 반복됐고, 서비스 Java sample에는 agent/JFR 자체도 포함됐다. 표본 수를 정확한 CPU 점유율로 환산하지 않았다.

| cgroup 전후 차분 | node0 | node1 | node2 | client |
|---|---:|---:|---:|---:|
| throttle 횟수 | 2.000 | 0.000 | 3.000 | 0.000 |
| throttled 시간 (ms) | 69.057 | 0.000 | 21.683 | 0.000 |
| CPU 사용 증가 (ms) | 13326.764 | 12806.193 | 15458.839 | 14713.412 |

각 cgroup 예산은2CPU/100ms이며 OOM 관련 차분은0이었다. cgroup 경계에는 최종 CAPTURE와 client binary dump도 포함돼 있으므로 특정 요청의 quota 대기를 귀속하거나 순수10초 비용이라고 할 수 없다. 작은 throttle 차분으로 호스트 실행 큐 경합을 배제하지 않는다.

클라이언트 clock anchor 폭은6542ns다. tail 중 GC와 직접 겹친 요청은280개, 최대 교집합은5.250500ms다. monitor event와 시간이 겹친 요청은1847개, 교집합 p50=1.195958ms, p99=21.190625ms다. 이는 해당 요청이 그 monitor를 직접 기다렸다는 귀속은 아니다. monitor는 주로 `HaProbeMarkerCollector.observe`이며 단일 최대18.150667ms다.

`HaProbeMarkerCollector.observe/register/drain`은 같은 monitor를 공유하고, drain은 완료 queue를 비우는 consumer까지 monitor 안에서 호출한다. 측정용 lock 대기는1/256 샘플이며4976개 표본 합0.878536ms였다. 이 표본이 드문 긴 경합을 놓칠 수 있어 JFR의 >=1ms monitor84건·합228.596ms와 모순되지 않는다. Java sample의 top frame은 observeGates491개, drain124개였으며 표본 비율을 CPU 시간 비율로 해석하지 않는다. 완료 지표에는 이 테스트 관찰 경로도 포함된다.

코드 근거: `TradingAeronRuntime.kt`의 BackoffIdleStrategy, `DevelopmentFundingNode.kt`의 SHARED driver/SHARED Archive·consensus·service 구성, `HaTradingProbeClient.kt`의 collector monitor와 발송 gate loop, `HaTradingProbeNode.kt`의 marker writer. 각 노드의 여러 agent·marker·metrics worker가2CPU 예산을 공유한다.

## 대표 블록과 tail 연결을 다시 계산하는 작은 snippet

저장소의 실제 작업 폴더에서 실행하면 기존 파일만 읽는다. 전체 수치·원시 대표 필드는 함께 제공한 JSON에 있다.

```sh
cd /Users/0chord/.codex/worktrees/issue148-http-aeron/exchange-core
python3 - <<'PY'
from pathlib import Path
import gzip, json, math, struct
p = Path('build/remaining-latency-20261031')
b = (p/'recordings/client-cohort.bin').read_bytes()
magic, n, phase_start, phase_end = struct.unpack_from('>iiqq', b)
assert magic == 0x434f4831 and len(b) == 24 + 40*n
rows = list(struct.iter_unpack('>qqqqq', b[24:]))
tail = {r[0] for r in sorted(rows, key=lambda r:r[4]-r[1])[-math.ceil(n*.01):]}
blocks, forces, calls, gc = [], [], [], []
with gzip.open(p/'node1.jsonl.gz', 'rt') as stream:
    for line in stream:
        x = json.loads(line); kind = x['type']
        if kind == 'exchange.ArchiveBlock': blocks.append(x)
        elif kind == 'jdk.FileForce': forces.append(x)
        elif kind == 'jdk.GCPhasePause': gc.append(x)
        elif kind == 'exchange.BoundedServiceCallback' and x['kind'] in ('ADMISSION','EXECUTION') and x['sequence'] in tail: calls.append(x)
block = next(x for x in blocks if (x['startPosition'],x['endPosition']) == (224664512,224742208))
f = [x for x in forces if x['thread']['osId'] == block['thread']['osId'] and block['start'] <= x['start'] <= x['end'] <= block['end']]
assert len(f) == 1
f = f[0]
linked = [x for x in calls if block['startPosition'] < x['position'] <= block['endPosition']]
assert len(linked) == len({x['sequence'] for x in linked}) == 607
assert not any(x['start'] < f['end'] and x['end'] > f['start'] for x in gc)
print({'N':n, 'tail':len(tail), 'linked':len(linked), 'forceMs':(f['end']-f['start'])/1e6})
for x in sorted(linked, key=lambda x:(x['kind'],x['sequence']))[:3]:
    lo = x['start'] - x['adviceEnteredNs']
    hi = x['end'] - x['adviceExitedNs']
    assert lo <= hi and x['position'] == x['observationPosition']
    print(x['sequence'], x['kind'], x['position'],
          x['productCallbackStartedNs']+lo, x['productCallbackStartedNs']+hi)
PY
```


## 선행 callback과 non-GC safepoint 추가 대조

`service-queue-summary.json`의 각 노드·접수/실행별 최장 대기 대표6개를 선택해, commit event 종료→해당 callback JFR 진입 사이에 겹치는 선행 callback을 개별로 읽었다. JFR callback event의 경과, advice 진입→종료, 원래 제품 capture의 시작→종료를 구분했다. 제품 capture 종료 이후에도 metric 기록·adapter finally가 남는다. 외부 advice의 metadata 추출·event commit은 event.end 뒤라 event 경과에도 포함되지 않는다.

| 기다리는 요청 | 실제 긴 선행 callback | JFR event / 제품 capture (ms) | 추가 관측 |
|---|---|---:|---|
| node0 E854014, 대기102.052ms | E853759 | 21.141416 / 21.136542 | 같은 service 표본0, GC·monitor·park·기록 VM operation 교집합0 |
| 위 대기의 다른 선행 요청 | E853739 | 13.033125 / 13.025208 | collectionSizeOrDefault→executePending 표본1개 |
| 위 대기의 다른 선행 요청 | E853785 | 8.686959 / 8.675084 | HashMap.getNode→MatchingEngine.prepareSubmit→executePending 표본1개 |
| 위 대기의 다른 선행 요청 | E853708 | 6.955375 / 0.035834 | 제품 capture 종료 뒤6.918708ms. 이 구간의 metric/finally 수행과 선점을 분리할 자료는 없음 |
| node1 A940030, 대기58.1065ms | E939518 | 38.760709 / 38.747166 | 같은 service 표본0, GC·monitor·park·기록 VM operation 교집합0 |
| node2 A854489, 대기95.18475ms | E853838 | 39.261042 / 39.253000 | 같은 service 표본0, GC·monitor·park·기록 VM operation 교집합0 |
| node2 E849790, 대기96.44125ms | E849578 | 70.257000 / 70.249000 | 같은 service 표본0, GC·monitor·park·기록 VM operation 교집합0 |
| 위 대기의 다른 선행 요청 | A849919 | 4.589333 / 4.587500 | 내부 실행 offer 재시도 중 service idle의 park4.555791ms |

node0의102ms 대기는 단일80ms callback 하나가 아니라 여러 긴 선행 callback이 누적된 사례다. node2의70ms callback은 직접 제품 capture 안에서 길어졌으므로 경계 밖 metadata/JFR commit 시간으로 설명되지 않는다. 그러나 이는 순수 CPU 시간이 아니다. 10ms Java execution sampling에서 그70ms·38ms·39ms 사건의 해당 service 표본이0개였다는 관측만으로 OS 선점을 확정할 수도, 연속 CPU 계산을 확정할 수도 없다. 표본이 있는 HashMap·matching·수수료 검증 경로에도 표본1개가 잡힌 순간 이상의 wall 비용을 배정하지 않았다.

A849919의4.555791ms park는 `DevelopmentFundingAdapter.submitBoundedExecution → ClusteredServiceAgent.idle → BackoffIdleStrategy.idle` 스택이다. 이는 내부 실행 전송 재시도 대기가 앞선 접수 callback 안에 포함되는 구체적인 경로다. 서비스가 오직 금융 계산만 하고 있었던 것으로 해석하면 안 된다.

원래 추출기에서 생략했던 `jdk.SafepointBegin`과 `jdk.ExecuteVMOperation`을 원본 JFR에서 읽었다. node0/1/2의 이벤트 수는 SafepointBegin34/35/35개, ExecuteVMOperation32/33/33개다. SafepointEnd는 기록돼 있지 않으므로 safepoint 수명 전체를 정확히 복원했다고 하지 않는다. 위6개 최장 queue 대표의 대기 span에는 기록된 VM operation이 겹치지 않았다.

node1 대표 force115.297083ms와 node2 같은 논리 블록의force42.436292ms, node2의60.681833ms force, node0의81.069459/82.575167ms force에도 기록된 VM operation의 직접 교집합은0이었다. 그렇다고 이 경과가 전부 물리 I/O 시간이라는 뜻은 아니다.

반면 node0의 force65.275542ms와 service park66.122875ms에는 동일한 ClassLoaderStatsOperation64.050584ms가 온전히 포함됐다. 해당 VM operation은 `2026-10-30T15:14:07.542690223Z`부터 `2026-10-30T15:14:07.606740807Z`까지며 `safepoint=true`, caller=`JFR Periodic Tasks`다. 이는 프로파일링이 일부 지연을 직접 만들거나 키운 실제 근거다. 이64ms를 일반 실행42~47ms p99의 원인으로 사용하지 않는다.

위 표의 원시 predecessor callback 경계·제품 nanoTime 변환 범위·같은 thread 표본·park·VM event는 JSON의 `predecessorCallbackAndSafepointAudit`에, 긴 force/park와 VM event의 정확 교집합은 `longForceAndParkVMOperationAudit`에 보존했다. 원본 recording은 변경하지 않았다.
