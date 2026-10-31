# 남은 지연의 원인 분석

2026-10-31 · 제품 코드 수정 없이 원인을 대조했다. 작업 폴더는 `/Users/0chord/.codex/worktrees/issue148-http-aeron/exchange-core`, 브랜치는 `test/http-trading-performance/142`다.

**이번에 확인한 메커니즘은 기록·복제 진행 지연과 서비스 순차 처리의 적체다. 한 주문의 금융 계산이 수십 ms의 CPU를 계속 사용하는 것으로 설명되지 않는다.** 긴 정지와 선행 처리의 경과시간이 뒤 주문에 누적된다. 호스트의 활발한 메모리 압력과 진단 자체의 부담도 확인했다. 물리 디스크·OS/Docker VM 스케줄링이 각각 차지하는 비중은 아직 분리하지 못했다.

## 확인한 흐름

```text
접수 명령 전송
  → 리더 로그 append
  → 로컬 Archive 기록 진행 + 과반수 복제 진행 확인
  → 접수 명령 commit
  → 앞선 서비스 callback 처리 뒤 접수 callback 진입
  → 자금 예약 → 내부 실행 명령 offer → 접수 응답
  → 실행 명령도 기록·복제·commit
  → 앞선 서비스 callback 처리 뒤 실행 callback 진입
  → 매칭·정산 반영
  → 각 노드 표식 전송 → 클라이언트가 세 사본 완료 관측
```

내부 실행 명령의 `offer > 0`은 전송 버퍼 수락이다. 실행의 commit이나 정산 완료를 뜻하지 않는다. 현재 BTC/SOL/SUI는 같은 복제 로그와 각 노드의 한 서비스 스레드를 공유한다. 마켓별 세 개의 독립 엔진을 측정한 결과가 아니다.

실제 연결은 [DevelopmentFundingAdapter.kt](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingAdapter.kt)의 `onSessionMessage`, bounded 접수·실행 분기와 `submitBoundedExecution`에 있다. 실행 전송의 재시도도 접수 callback 안에서 수행한다. Archive 기록 진행과 commit의 실제 라이브러리 경계는 결과 폴더의 RecordingWriter·RecordingSession·ConsensusModuleAgent bytecode 발췌로 함께 보존했다.

## 1. 기록·복제 진행을 기다리는 실제 사례

첫 진단의 요청 순번 **877283**은 세 사본 완료 관측까지 **220.54ms**가 걸렸다. 리더 접수 로그의 append→로컬 Archive 블록 완료는 **51.58ms**, 로컬 기록 완료→유효 peer 진행 보고 도착은 약 **74.02ms**였다. 보고 조건이 갖춰진 뒤 commit 자체는 약 **0.014~0.032ms**였다.

해당 논리 위치를 포함하는 follower node1의 블록 `(224664512, 224742208]`에는 **115.30ms의 FileForce**가 있었다. GC·기록된 VM operation과 직접 겹치지 않았다. 이 블록은 느린 요청 607개의 명령을 함께 담는다. 같은 블록의 force 시간을 요청마다 합산하거나, 주문마다 개별 fsync 한 번이 발생했다고 해석하면 안 된다.

`sync=2`에서 기록 진행 위치가 force 뒤로 늦어지면 그 위치를 기다리는 명령들도 진행하지 못한다. 지연은 자기 명령이 포함된 블록뿐 아니라 그 앞의 기록 prefix에도 영향을 받는다. peer 보고 대기에는 follower 수신·선행 기록·보고 전송·리더 polling이 포함되므로 위 **74ms를 네트워크 지연 하나로 부르지 않는다.** FileForce 경과 역시 순수 SSD 시간만을 뜻하지 않는다.

## 2. commit 이후에도 앞선 처리 때문에 밀린다

같은 진단의 node0 실행 **854014**는 commit callback 반환 뒤 서비스 callback 진입까지 **102.052ms**를 기다렸다. 그 사이 **다른 callback 617개가 시작**했고, 다른 callback의 경과 구간이 겹침 없이 차지한 시간은 **98.545ms**였다. 이 대기 구간에 직접 겹친 GC·서비스 park는 0이었다.

이는 이미 확정된 명령도 앞선 처리가 끝나야 실행된다는 직접 근거다. 다만 617개를 모두 순수 CPU 계산으로 분류하면 안 된다. 선행 callback에는 금융 계산 외의 실행 전송 재시도·metric/finally와 실행 중 정지도 포함된다.

다른 실제 사례에서는 `submitBoundedExecution → ClusteredServiceAgent.idle → BackoffIdleStrategy.idle`의 **4.56ms park**가 선행 접수 callback 안에 있었다. 따라서 기록·복제 지연과 서비스 처리 대기는 별도 병목이며, 두 단계 모두 적체를 키울 수 있다.

## 3. 길게 보이는 validation이 계속 CPU를 사용한 것은 아니다

두 번째 진단은 callback의 스레드 CPU를 함께 읽었다. 제품 capture보다 넓은 advice 구간의 user+system CPU이므로 **제품 CPU의 상한**으로 사용했다.

| 같은 노드·같은 실행 callback | 제품 경과 | CPU 상한 | CPU 실행 밖 경과의 하한 |
|---|---:|---:|---:|
| node2 E977840 | 28.584ms | 0.292ms | **28.293ms** |
| node2 E864086 | 17.581ms | 0.299ms | **17.282ms** |

첫 사례는 `settlement_input_validation`, 둘째는 `result_validation` 타이머 안에서 길어졌다. 두 사건 모두 직접 겹친 GC·기록 VM operation·service park/monitor가 0이었다. **타이머가 validation 안에서 열려 있었다는 사실만으로 validation 계산이 28ms CPU를 썼다고 결론 내릴 수 없다.**

CPU가 큰 사건도 있었다. node2 E863989는 제품 경과 28.532ms에 CPU 상한 26.128ms였다. 이 CPU에는 kernel 작업도 포함될 수 있고 해당 callback의 Java/native stack 표본이 없어, 특정 정산 함수나 page fault에 전부 배정하지 않았다. R2에는 GC와 직접 겹친 긴 callback도 있었다. 따라서 GC 영향이 전혀 없거나 모든 긴 callback이 같은 원인이라고 주장하지 않는다.

첫 진단에서 실제 가장 느린 1% 요청의 접수+실행 두 제품 callback 합의 중앙값은 노드별 **8.29~10.71μs**였다. 반면 같은 요청의 접수 종료→실행 시작 간격 중앙값은 **42.28~62.39ms**였다. 자기 계산 밖의 대기가 핵심이라는 근거다. 이 서로 다른 분포의 중앙값을 더해 한 요청의 시간표로 쓰지는 않았다.

## 4. 측정 머신은 메모리에 안정적으로 상주한 상태가 아니었다

R2 전후 macOS machine-wide 카운터에서 페이지 크기 16KiB를 적용하면 다음 활동이 있었다. 범위에는 진단 제어·마지막 CAPTURE도 포함되며 정확한 10초 업무 비용만은 아니다.

| 활동 | 전후 차분 |
|---|---:|
| 메모리 압축 해제 | **8.53GiB 상당** |
| 메모리 압축 | **9.32GiB 상당** |
| Swap in | 24.83MiB |
| Swap out | 245.44MiB |

반복 페이지를 포함하는 활동량이며 고유한 새 할당량이 아니다. 저장된 swap 총량만으로 원인을 추정한 것이 아니라 **실행 중 실제 압축·해제·swap이 발생**했다. Docker VM을 포함한 실행 환경의 메모리 상주가 안정적이지 않았다는 증거다.

다만 host 카운터와 callback을 per-process OS scheduler trace로 연결한 것은 아니다. E977840의 28ms를 특정 swap 작업으로 직접 귀속하지 않는다. guest node의 major fault 0도 host 메모리 복원이나 vCPU 실행 대기를 배제하는 근거는 아니다. 이번 자료만으로 CPU quota가 주원인이라고 할 근거도 부족하다.

## 5. 진단 도구가 만든 지연도 구분했다

첫 진단에서 JFR의 `ClassLoaderStatsOperation`이 **64.05ms safepoint**를 만들었다. 그 구간과 겹친 65.28ms force·66.12ms park는 일반 실행의 병목 근거에서 제외했다. 두 번째 진단에서는 해당 주기 통계를 껐으며 이 operation은 0이었다. 그렇다고 다른 JFR·CPU 계측의 비용까지 제거된 것은 아니다.

| 실행 · 동일 제품 바이너리 | 접수 TPS | 접수 p95 / p99 | 세 사본 완료 TPS | 완료 p95 / p99 |
|---|---:|---:|---:|---:|
| 비계측 대조 · 단회 | 40,836 | 21.39 / 34.31ms | **40,807** | **45.96 / 62.67ms** |
| R1 · 위치·JFR 진단 | 27,270 | 36.03 / 68.88ms | 27,244 | 75.11 / 129.04ms |
| R2 · 주기 통계 제외·CPU 진단 | 26,404 | 32.15 / 60.92ms | 26,315 | 62.70 / 132.40ms |

세 노드·sync=2·Protobuf·BTC/SOL/SUI·동시 접수 512·미완료 1024, 예열 20초 뒤 제출 10초다. 세 사본 완료는 실제 표식 수신까지 포함한다. HTTP와 원격 Backup 저장 확인은 제외한다. TPS 분모는 측정 창 시작부터 마지막 해당 cohort 관측까지다.

**진단 수치를 제품의 새 회귀나 최대 용량으로 사용하지 않는다.** 비계측 대조도 한 번이므로 계측 overhead의 정확한 비율이나 절대 최대 TPS를 확정하지 않는다. 진단의 구체 사례가 앞서 관측한 비계측 p99 42~47ms에 각각 몇 % 기여했는지도 입증하지 않았다.

## 결론과 다음 수정의 우선순위

1. **확인:** 실제 기록·복제 진행 지연이 있고, 이미 확정된 명령의 순차 처리 적체도 있다. 일부 callback의 긴 경과는 대부분 CPU 계산 밖이었다. 이 조건에서 단순한 직렬화 교체·off-heap 전환을 우선할 근거는 없다.
2. **먼저 안정화할 조건:** 노드·발생기·호스트의 전체 메모리 예산을 정해 압축·swap이 활발한 실행을 성능 비교에서 분리한다. 사용자 앱 종료나 Docker 자원 변경은 이번에 하지 않았다.
3. **이어 검토할 실제 경로:** sync=2를 유지한 기록 batch·Archive/Consensus/서비스 agent 진행, 선행 callback 안의 내부 실행 재시도와 관측 부하를 줄이는 변경이다. 기록 확인 전에 성공을 응답하거나 금융 검증을 제거하는 식으로 지연을 숨기지 않는다.
4. **미확정:** native force의 물리 장치 대기와 OS/Docker VM 스케줄링의 세부 기여율, 개별 off-CPU 사건의 host paging 귀속이다. 이 부분을 해결 완료라고 표시하지 않는다.

이번 요청은 원인 파악이므로 위 후속 수정을 시작하지 않았다. 제품 소스 520개와 실행물 1,344개의 SHA-256은 기존 v2 manifest와 전부 일치했다. 세 실행의 금융 상태·복제 상태·ACK 검사와 소유 자원 정리는 통과했다. 진단 두 회의 요청 위치 대조는 각각 1,638,522개·1,588,146개 callback에서 누락·중복 없이 검증됐다.

## 실행 근거

작은 요약과 독립 검토는 [결과 폴더](results/remaining-latency-20261031/)에 보존했다. `validation-summary.json`, R1/R2 pipeline 요약, `gc-force-independent.md`, `service-queue-review.md`, `cpu-memory-independent.md`를 확인하면 된다. 독립 분석자는 새 부하를 실행하지 않았다.

원본 JFR·JSONL·cohort는 무시 경로 `build/remaining-latency-20261031`에 유지하며 `raw-evidence-manifest.json`에 위치·크기·지문을 남겼다. 첫 실행은 `R1-CallbackAgent.java`·`R1-run_diagnostic.py`, 두 번째는 R2 이름의 소스다. 실행 뒤 변경된 진단 소스를 과거 실행 소스로 인용하지 않았다. 큰 원본을 engineering에 중복 복사하지 않았다.
