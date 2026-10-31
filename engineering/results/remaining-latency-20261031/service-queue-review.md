# 서비스 큐 분석 독립 검토

`analyze_service_queue.py`와 `service-queue-summary.json`을 읽고, 같은 실행의 `node0.jsonl.gz`, `node2.jsonl.gz`에서 요청 네 건의 callback·정확한 commit crossing·구간 내 다른 callback·같은 서비스 스레드 park·같은 JVM GC pause를 직접 대조했다. 분석기를 재실행하거나 소스·입력을 수정하지 않았다. 빌드·부하도 실행하지 않았다.

## 알고리즘 판정

현재 자료에 대한 핵심 계산 오류는 찾지 못했다.

- `indexed()`는 겹치거나 맞닿는 구간을 먼저 합치고 누적 길이의 적분값 `at(t)`를 만든다. `at(b)-at(a)`는 `[a,b)` 내부 union 길이이며 중복 callback·park·GC 시간을 각각의 집합 안에서 이중 계산하지 않는다.
- 각 요청의 `a=commitBracket[1]`, `b=adviceJfrBracket[0]`는 같은 노드 JFR의 commit method 종료와 대상 callback event 시작이다. 서로 다른 JVM의 raw nanoTime을 섞지 않는다. commit의 실제 release를 정확한 exit 시각으로 주장하는 계산도 아니다. 전체 native bracket 중 exit 이후 남아 있는 서비스 진입 지연을 측정한다.
- 서비스 callback의 JFR thread ID가 하나라는 전제와, park를 그 ID로 제한하는 방식이 현재 자료에 맞는다. GC는 같은 노드 recording의 GCPhasePause만 사용한다.
- 시작 개수는 `bisect_left(starts,b)-bisect_left(starts,a)`이므로 `[a,b)`에 시작한 다른 callback 수다. 구간 시작 때 이미 진행 중인 callback은 union 길이에 포함되지만 이 시작 개수에는 포함되지 않는다. 대상 callback 자체는 `b`에 시작해 둘 다 제외된다.
- 여섯 member/kind 집단의 count가 모두 2,731이므로 현재 tail 집단에서 음수 gap으로 건너뛴 요청은 없다. 코드의 `b<a` 분기는 다른 실행에서 재사용할 때 집단을 조용히 줄일 수 있으므로, 그 경우 count를 함께 확인해야 한다.

## 원문 네 건 직접 대조

각 행은 원문 callback의 sequence·kind·header/observation position과 tail 파일을 일치시켰다. 원문 native commit event의 시작/끝도 tail bracket과 같고, `beforeCommit < position <= afterCommit`을 만족한다. 아래 길이는 prefix 함수를 재사용하지 않고 원문 구간을 직접 잘라 정렬·union한 결과다.

| 노드 / 종류 / sequence | position | commit exit → callback begin | 다른 callback event union | 구간 내 callback 시작 수 | 동일 서비스 park | 동일 JVM GC |
|---|---:|---:|---:|---:|---:|---:|
| 0 / EXECUTION / 854014 | 218837216 | 102.052000ms | 98.545485ms | 617 | 0 | 0 |
| 2 / EXECUTION / 849790 | 217767520 | 96.441250ms | 95.807242ms | 630 | 4.555791ms | 0 |
| 2 / ADMISSION / 890441 | 228114976 | 9.088917ms | 7.710623ms | 483 | 0 | 0 |
| 2 / ADMISSION / 849578 | 217633920 | 21.469166ms | 20.845374ms | 653 | 0 | 8.749167ms |

노드 0의 854014는 FollowerCommit, 노드 2의 나머지는 LeaderCommit이다. 네 건 모두 gap 시작 때 이미 진행 중인 callback이 하나 있으므로, 구간과 겹치는 callback 수는 시작 수보다 하나 많다. 이 수는 주문 수가 아니라 ADMISSION·EXECUTION·일부 UNKNOWN ACK callback 수다.

854014의 원문 시간은 commit exit `1793373243775344513`, callback event begin `1793373243877396513`으로 정확히 102,052,000ns 차이다. 겹치는 618개 callback은 EXECUTION 412개와 ADMISSION 206개이며, union이 gap의 96.5640%를 덮는다. 따라서 이 요청에서는 commit method 종료 후 대상 callback 진입 전까지 서비스 이벤트가 다른 callback들로 주로 덮여 있었다는 설명이 직접 뒷받침된다. 순수 CPU 소비가 98.545485ms라는 뜻은 아니다.

## 해석 시 필요한 정정과 한계

**서로 다른 지표의 중앙값을 같은 요청의 분해로 쓰면 안 된다.** 노드 2 ADMISSION의 gap p50 9.088917ms, callback union p50 8.384374ms, 시작 수 p50 258은 각각 따로 정렬한 중앙값이다. 실제 gap이 중앙값인 890441은 callback union 7.710623ms와 시작 수 483이다. 따라서 “9.089ms 중 8.384ms를 258개 callback이 차지했다”는 표현은 원문이 입증하지 않는다. 각각의 분포 중앙값으로 제시하거나 표처럼 특정 요청의 같은 행을 사용해야 한다.

**서로 다른 집합의 union도 다시 더하면 안 된다.** 849790의 park 4.555791ms는 전부 callback union 안에 들어 있다. 849578의 GC pause 8.749167ms도 전부 callback union 안이다. Callback wall + park + GC는 배타적 분해가 아니며, 단순 차감한 잔여 시간을 “순수 CPU”로 해석할 수도 없다.

Callback event wall에는 제품 callback과 일부 advice 비용뿐 아니라 해당 event가 열려 있는 동안의 preemption·park·GC가 포함될 수 있다. 반대로 callback agent의 metadata 추출과 event commit은 `event.end()` 뒤에 실행되므로 그 비용 전부가 event wall 안에 있는 것도 아니다. Union 바깥 잔여 시간에는 dispatch·계측·관측하지 않은 scheduling 비용 등이 남을 수 있다.

`ThreadPark`는 이 실행에서 1ms threshold로 기록했다. Park overlap 0은 기록된 park와의 교집합이 없다는 뜻이며 모든 짧은 park가 없었다는 뜻은 아니다. GC overlap은 시간적 동시 발생이다. 다른 노드 GC를 원인으로 가져오거나 전체 gap을 GC 탓으로 배분할 수 없다.

이 결과는 JFR와 외부 callback agent가 붙은 동일 진단 실행의 서비스 진입 지연을 설명한다. 다른 callback 이벤트가 앞에서 길게 관측됐다는 근거는 강하지만, 이를 비계측 실행의 처리량·p99 또는 순수 제품 CPU 비용으로 옮길 수 없다. 원문에서 확인된 가장 구체적인 표현은 **“commit method 종료 후 대상 서비스 callback 시작까지의 지연 상당 부분이 같은 서비스 스레드의 다른 callback event 벽시간과 겹친다”**이다.
