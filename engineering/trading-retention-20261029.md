# 주문 이력 보관 비용 수정과 짧은 재측정 — 2026-10-29

기존 성능·관찰 코드의 기준선은 `ef803764cfb5d897d182d333100df226e9fd5d5f`이다. 이번 변경은 과거 잔고 객체의 중복 보관과 관찰 원문의 작은 파일 쓰기를 줄인다. 원장·최초 요청 결과를 삭제하지 않으므로, 이력 수에 비례하는 메모리 증가 자체를 해결한 것은 아니다.

## 바뀐 동작

1. `OrderAdmissionCandidate`는 이전·이후 `Balance` 객체 대신 당시 소유자·자산·금액을 보관한다. 과거 잔고 조회는 그 값을 복원한다. 현재 잔고와 후보를 비교할 때는 복원 객체를 만들지 않는다.
2. `TradingState.executePending`는 실제 상태를 쓰기 전에 완료 이력의 잔고 표현을 준비한다. `OrderSettlementCandidate.compactForHistory()`는 잔고 변경 목록만 문자열 참조 배열과 금액 배열로 보관한다. 체결·예약 목록과 정산 계획·원장은 기존 불변 객체를 그대로 공유한다.
3. 체결 없이 주문장에 들어가는 주문의 빈 정산 목록은 공유한다. 반복 조회가 다시 전체 과거 잔고 객체를 보관하도록 캐시하지 않는다.
4. 테스트 관찰 파일은 64KiB 버퍼에 최대 256개 원문을 묶어 쓴다. 가능한 항목만 모으며, 묶음을 채우려고 기다리지 않는다. 완전한 쓰기 뒤에만 각 원문의 offset을 확인해 준다. CRC·파일 크기·pending 상한과 실패 전파를 유지한다.

정상 흐름은 `후보 계산 → 전체 검증 → 이력 표현 준비 → 실제 잔고·예약·원장 반영 → 완료 기록`이다. 이력 준비에서 실패하면 아직 실제 값은 바뀌지 않는다. 실제 반영 중 오류가 발생하면 기존 차단 계약을 유지한다. 같은 요청에는 기존 최초 결과, 같은 실행에는 기존 완료 객체를 반환한다. 이력 조회의 금액은 현재 잔고로 다시 계산하지 않는다.

처음 시도한 전체 정산 배열 변환은 공유 중인 예약 등을 다시 저장하면서 할당·보관 비용이 늘어 채택하지 않았다. 최종 변경은 과거 잔고에 한정한다.

## 엔진 단독 비교

같은 Java 25.0.2, G1, `-Xms256m -Xmx768m -XX:ActiveProcessorCount=2`, SELL 준비 70,000건, BUY warmup 10,000건, 측정 BUY 60,000건이다. 측정 BUY 한 건에는 접수·예약·매칭·정산·수수료·원장이 포함된다. SELL 준비와 네트워크·HTTP·복제·디스크 기록은 이 TPS에 포함되지 않는다.

각 구현을 새 JVM에서 세 번 실행했다. 다음은 **실행별 값의 중앙값**이다. 지연 중앙값은 세 실행의 percentile을 합쳐 다시 구한 percentile이 아니다. 실행을 무작위 교차하지 않았고 실행 편차가 있으므로 TPS 개선율을 보장하지 않는다.

| 항목 | 변경 전 | 최종 변경 후 | 관측 차이 |
| --- | ---: | ---: | ---: |
| 측정 BUY 완료 TPS | 74,023 | 77,236 | +4.3% |
| 호출 스레드 CPU / BUY | 10.254μs | 9.893μs | −3.5% |
| 호출 스레드 할당 / BUY | 9,707B | 10,202B | **+5.1%** |
| p95 bucket 상한 | 16.895μs | 15.871μs | −6.1% |
| p99 bucket 상한 | 20.991μs | 20.991μs | 동일 |
| 측정 뒤 GC 후 Heap | 320,848,504B | 311,771,064B | **−2.8%** |

변경 전 TPS 범위는 70,184–77,745, 변경 후는 73,883–78,956이다. 보관 메모리는 줄었지만 임시 할당량은 늘었다. 따라서 전반적인 할당 최적화나 큰 TPS 개선으로 설명하면 안 된다. 현재 상태를 강하게 참조한 채 명시적 GC 이후 확인한 값이며, 각 실행에서 GC count 증가를 확인했다. 전체 결과 snapshot은 이 메모리 측정 뒤 한 번만 만든다.

여섯 실행 모두 60,000개 측정 BUY 완료, 총 140,000개 완료 이력, 210,002개 원장을 확인했다. 기대 잔고·수수료·자산별 원장 균형·중복 접수/실행·자금 부족 거절 검사에서 오류가 없었다.

## 세 노드·sync=2 비교

HTTP Gateway를 제외한 Direct Aeron 경로다. BTC/SOL/SUI, 세 독립 Docker JVM, 노드마다 2CPU/2GiB·G1/Xmx1536m, 실제 Archive/catalog/consensus sync `[2,2,2]`, 두 클라이언트 세션을 유지했다. SELL/BUY 각각 12,000건을 warmup한 뒤, 각각 120,000건을 6,000건씩 번갈아 보냈다. 한 실행 안에서 클러스터를 초기화하지 않았다.

| 항목 | 변경 전 | 최종 변경 후 |
| --- | ---: | ---: |
| 새 SELL+BUY 240,000건 전체 처리 시간 | 19.954초 | 13.408초 |
| 전체 주문 완료 TPS: 240,000 / 전체 시간 | 12,027 | 17,900 |
| 체결 BUY 완료 TPS: 120,000 / 같은 전체 시간 | 6,014 | 8,950 |
| BUY 접수 cohort TPS | 6,243 | 9,208 |
| BUY 세 노드 완료 cohort TPS | 6,185 | 9,184 |
| BUY 접수 p95 / p99 | 493 / 817ms | 253 / 480ms |
| BUY 세 노드 완료 관측 p95 / p99 | 984 / 1,155ms | 434 / 587ms |

cohort TPS는 첫 측정 BUY 시작부터 마지막 해당 응답/완료 표식까지의 시간으로 나눈 값이다. 전체 주문 TPS와 분모·분자가 다르다. 완료 지연은 엔진 계산 종료만이 아니라 **세 노드의 완료 표식이 발생기에 도착할 때까지**다. 전체 시간에는 행 생성·중간 SELL 처리·대기열 배출도 포함된다. 6,000건씩 묶어 넣는 유한 비교이므로 발생기 상한을 배제한 엔진 최대 TPS로 해석하지 않는다.

두 실행 모두 준비/warmup을 포함한 264,000개 원문 ID와 792,000개 고유 완료 표식을 대조했고 오류·누락·관찰 overflow가 0이었다. 실제 세 노드의 설정도 확인했다. 이 부하 시험에서 별도 전체 잔고 snapshot oracle은 실행하지 않았으며, 금융 값 검증은 앞의 엔진 단독 검사와 회귀 테스트에서 수행했다.

각 구현 한 번씩이며 리더도 변경 전 node2, 변경 후 node0였다. 전체 완료 TPS의 관측 차이는 +48.8%지만, 이 비율 전체를 코드만의 개선율로 확정하지 않는다. 노드 평균 Young GC 시간 증가분은 2,487→1,488ms, 세 노드 CPU 시간 증가분 합계는 87.98→57.98초였다. 노드의 종료 직후 used heap 평균은 약 761→763MB로 감소하지 않았다. 이 값은 강제 GC 후 보관 크기가 아니므로, 보관 메모리 감소 근거는 엔진 단독의 post-GC 비교로 한정한다.

최초 묶음 쓰기 적용 전 시험은 관찰 파일 쓰기가 밀려 8,192개 pending 원문 상한에 도달하여 **무효**로 남겼다. 이 실행은 성능 표에 넣지 않았다. 유효한 전후 비교에는 모두 같은 관찰 묶음 쓰기를 적용했다. 이후 독립 리뷰에서 수정한 버퍼 할당 실패의 채널 종료 경계만 AFTER에 추가됐으며 정상 쓰기 경로는 같다.

## 회귀 테스트와 독립 검토

- domain-trading 208개, 관련 app-api 83개, architecture-tests 385개: **676개, 실패·오류·skip 0**. domain-trading·app-api ktlintCheck도 성공했다. app-api 전체 테스트를 모두 실행한 수치는 아니다.
- 신규 접수 후보 검사: 정상 SELL 후보를 만든 뒤, 이전·이후 잔고 값은 같지만 과거 Balance 객체를 필드에 보관하지 않는지 확인한다. 기존 소유자·자산·예약·원장 위반 검사는 그대로 실행한다.
- 신규 완료 잔고 검사 4개: 무료/유료 및 1/2체결의 모든 잔고 값, 원본 체결·예약·원장 참조, 불변 목록, 조회 비캐시, 빈 결과, 서로 다른 이전·이후 소유자·자산을 검사한다. 내부 저장 금액이 음수로 손상되면 조회 시 기존 Amount 검증으로 실패하는지도 확인한다.
- 관찰 묶음 쓰기 검사: 완료 원문 512건을 대기시킨 뒤 보관 참조가 해제되고, 옛 원문 512건을 정확히 다시 읽으며, 실제 파일 write 호출은 최대 4회인지 확인한다. 기존 종료·쓰기 실패와 CRC 검사를 함께 실행한다.
- 작성 대화를 상속하지 않은 별도 검토자가 최종 9개 소스 변경을 검토했다. 버퍼 할당 실패 시 채널이 닫히지 않는 경계를 발견해 `try/finally` 안으로 옮겼다. 최종 `review-r4`의 확인된 미해결 결함은 0이다. 리뷰어가 성능·OOM 실험을 실행한 것은 아니다.

실행 폴더와 브랜치:

```text
/Users/0chord/.codex/worktrees/issue148-http-aeron/exchange-core
test/http-trading-performance/142
```

회귀 검증 명령:

```bash
cd /Users/0chord/.codex/worktrees/issue148-http-aeron/exchange-core
./gradlew :domain-trading:test :domain-trading:ktlintFormat \
  :app-api:test \
  --tests '*HttpPerformanceFileBackedCollectorHistoryTest' \
  --tests '*HttpPerformanceFileBackedMarkerOutboxTest' \
  --tests '*HttpPerformanceCollectorHistoryTest' \
  --tests '*HttpPerformanceCollectorTest' \
  --tests '*HttpPerformanceCheckpointFilesTest' \
  --tests '*AutomaticOrderExecutionTest' \
  --tests '*DevelopmentFundingAdapterTest' \
  :app-api:ktlintCheck :app-api:stageFundingVerificationRuntime \
  :architecture-tests:test --no-daemon --max-workers=2 --console=plain \
  -Dorg.gradle.jvmargs=-Xmx1536m
```

## 근거와 남은 범위

근거 폴더는 `/Users/0chord/.codex/visualizations/2026/09/23/01a0ce63-9327-7f32-a7e0-3429380284d8/issue142-performance/business-retention-20261029/`이다. `engine-final-comparison.json`, `three-node-before-after-comparison.json`, 각 실행의 `*-execution.json`·`*-summary.json`·구간별 CSV, 런타임 manifest, `final-regression-xml/`, `regression-r4.log`, `independent-review.json`, `review-r4/`에 명령·결과·고정 소스 해시를 남겼다. 기존 측정기를 사용했으며 새로운 측정 플랫폼은 추가하지 않았다.

무효/유효 BEFORE와 AFTER의 큰 원본은 복원 SHA를 확인한 뒤 약 1.03GB에서 98MB로 압축했다. 이번 실행 소유의 컨테이너·이미지·볼륨·네트워크는 모두 종료·제거했다. 종료 시 남은 디스크는 약 78GB(십진 단위)였다. 다른 실험의 자원이나 사용자 앱은 정리하지 않았다.

현재 잔고가 끝없이 늘어나는 문제와, 최초 요청 결과·완료·원장 이력이 늘어나는 문제는 다르다. 이번에는 후자의 객체 보관 비용 일부만 줄였다. 완료 건수와 ID 인덱스는 계속 증가한다. 과거 이력을 메모리에서 해제하려면 영속 보관 확인, 오래된 중복 요청 조회, 복구 가능한 경계와 보관 정책이 필요하다. 이 연결 없이 TTL이나 임의 삭제를 넣지 않았다. **5분 지속 성공·무제한 일정 메모리·50,000 TPS 달성은 이번 검증 결과가 아니다.**


2026-10-29 후속 기록: [완료 이력의 디스크 이관·보관 한도 및 재측정](trading-history-retention-20261029.md)에서 기능 검증과 성능 회귀를 구분한다. 이전 측정의 조건·결과는 그대로 보존한다.
