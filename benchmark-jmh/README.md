# 매칭 코어 JMH — 개발 환경의 합성 입력 측정

> **실사용 거래소 성능이나 운영 용량을 검증한 결과가 아닙니다.**
> 개발용 Mac에서 코드로 만든 고정 주문 패턴을 반복한 측정입니다. 실제 주문 로그를 재생한 결과가 아닙니다.

2026-09-12에 메모리 주문장의 명령 처리와 마켓별 작업 스레드를 거치는 비용을 측정했습니다.
Spring 애플리케이션을 띄우지 않으므로 `dev` 또는 `prod` 프로파일을 적용한 서버 측정이 아닙니다.
HTTP 요청, 자금 예약, 수수료 계산, PostgreSQL 정산과 원장 저장은 실행하지 않았습니다.

## 빠른 실행용 기본값과 이번 실행값

[Gradle 설정](build.gradle.kts)과 벤치마크 클래스의 애너테이션에는 빠른 실행 확인용 기본값이 들어 있습니다.
기록된 결과는 JAR 실행 시 아래 CLI 옵션으로 기본값을 덮어쓴 것입니다. 실제 적용 값은 [원본 JSON](results/2026-09-12-core-engine.json)의 `forks`, `warmupIterations`, `measurementIterations`, `jvmArgs` 등에서도 확인할 수 있습니다.

| 설정 | 소스 기본값 — 빠른 실행 확인용 | 이번 기록에 사용한 값 |
| --- | --- | --- |
| 독립 JVM 수 | 1 (`@Fork(1)`) | 3 (`-f 3`) |
| JVM별 워밍업 | 1회 × 0.5초 | 5회 × 1초 (`-wi 5 -w 1s`) |
| JVM별 측정 | 3회 × 0.5초 | 5회 × 1초 (`-i 5 -r 1s`) |
| JVM 힙 | 별도 크기 지정 없음 | 초기·최대 1 GiB (`-Xms1g -Xmx1g`) |
| GC 프로파일러 | 별도 지정 없음 | 사용 (`-prof gc`) |
| 실행 대상 | 명령 생성 벤치마크까지 포함 | 매칭 엔진·프로세서의 6개 시나리오만 선택 |

이번 설정도 단시간 측정입니다. 워밍업 뒤 JIT 컴파일과 처리량이 충분히 안정됐는지 별도 판정하거나, 장시간 실행·메모리 증가를 검증한 것은 아닙니다.
반복 시간을 늘리는 것과 실제 주문 유입을 재현하는 것은 별개의 작업입니다. 현재 결과는 합성 입력에 대한 초기 기준값으로 사용합니다.

## 측정 조건

| 항목 | 값 |
| --- | --- |
| 측정한 코드 | [`84284a0`](https://github.com/0Chord/coin-exchange/commit/84284a09939c1e669b8c699689a909dc21655854), 측정 시작 시 소스 변경 없음 |
| 컴퓨터 CPU / 메모리 | Apple M1 Pro, 8코어 / 16 GiB. 단일 마켓을 8개 스레드로 처리한 것이 아님 |
| OS | macOS 26.4.1, arm64 |
| JVM | Amazon Corretto OpenJDK 25.0.2+10-LTS |
| JMH | 1.37, throughput 모드, 초 단위 |
| 실행 | 벤치마크별 독립 JVM 3개, JVM마다 워밍업 5회 × 1초 + 측정 5회 × 1초 |
| JVM 옵션 | `-Xms1g -Xmx1g` — 컴퓨터 전체 메모리와 별개로 JVM 힙을 1 GiB로 설정 |
| 스레드 | JMH 호출 스레드 1개. 프로세서 경로는 활성 마켓별 작업 스레드 추가 |
| GC 측정 | `-prof gc`, GC 종류는 해당 JVM 기본값 사용 |

전용 성능 측정 서버가 아닌 개발용 컴퓨터입니다. 측정 중 별도 빌드·테스트는 돌리지 않았지만, 백그라운드 앱·CPU 주파수·전원 정책은 통제하지 않았습니다. 아래 숫자는 이 환경과 입력에 대한 기준값이며 운영 용량을 보장하지 않습니다.

## 입력과 측정 범위

모든 입력은 LIMIT/GTC 새 주문입니다. 취소·거절·장애 처리 성능은 측정하지 않았습니다.
데이터 생성 코드는 [BenchmarkCommands](src/jmh/kotlin/com/exchange/core/benchmark/BenchmarkCommands.kt)에 있습니다.
메서드 이름의 `Replay`는 이 코드가 생성한 주문 목록을 다시 넣는다는 뜻이며, 실제 시장 데이터 재생을 뜻하지 않습니다.

| 입력 | 마켓 | 한 배치의 주문 수 | 가격과 수량 |
| --- | --- | ---: | --- |
| 단일 replay | BTC-KRW | SELL 500 + BUY 500 = 1,000 | SELL 가격 100–119, 수량 2 / BUY 가격 105–129, 수량 3 |
| 다중 replay | BTC-KRW, ETH-KRW, SOL-KRW | 마켓별 SELL 200 + BUY 200 = 총 1,200 | 단일 replay와 같은 가격·수량 규칙 |
| 단일 balanced | BTC-KRW | SELL 500 + BUY 500 = 1,000 | SELL 가격 100–119 / BUY 가격 130, 양쪽 수량 1 |
| 다중 balanced | 위 3개 마켓 | 마켓별 SELL 200 + BUY 200 = 총 1,200 | 단일 balanced와 같은 가격·수량 규칙 |

배치에서는 SELL을 먼저 모두 넣은 뒤 BUY를 넣습니다. Replay는 부분 체결과 잔량을 포함합니다. Balanced는 주문이 모두 체결되어 배치 종료 시 주문장이 비게 됩니다. 단일 balanced에서는 500건, 다중 balanced에서는 600건의 체결이 발생합니다. **주문 수와 체결 수는 같은 값이 아닙니다.**

세 가지 실행 경로를 단일·다중 마켓으로 각각 측정합니다.

| 경로 | 포함하는 작업 | 제외하는 작업 |
| --- | --- | --- |
| [엔진 직접 호출](src/jmh/kotlin/com/exchange/core/benchmark/MatchingEngineReplayBenchmark.kt) | 배치마다 새 엔진 생성, 사전 생성된 replay 주문 처리, 이벤트 생성 | 입력 생성, 큐·작업 스레드 |
| [프로세서 생성 포함](src/jmh/kotlin/com/exchange/core/benchmark/MarketCommandProcessorReplayBenchmark.kt) | 배치마다 프로세서·작업 스레드 생성, replay 주문 제출, 모든 future 완료 대기, 종료 요청 | 입력 생성 |
| [프로세서 재사용](src/jmh/kotlin/com/exchange/core/benchmark/MarketCommandProcessorSteadyStateBenchmark.kt) | balanced 주문과 새 ID 생성, 기존 작업 스레드에 제출, 모든 future 완료 대기 | iteration 준비 단계의 프로세서 생성과 작업 스레드 예열 |

엔진 직접 호출은 다중 마켓도 한 스레드에서 순서대로 처리합니다. 프로세서 경로는 단일 마켓에 1개, 다중 마켓에 3개의 작업 스레드가 명령을 처리합니다.
프로세서 재사용의 준비 단계는 세 마켓의 작업 스레드를 모두 미리 시작합니다. 단일 마켓 측정에서는 그중 하나에만 새 명령을 제출합니다.
`close()`는 executor의 종료를 요청하며 스레드 종료 완료까지 기다리지는 않습니다.

## 단위 해석

현재 벤치마크에는 `@OperationsPerInvocation`이 없습니다. 따라서 JMH의 `1 op`는 주문 한 건이 아니라 **벤치마크 메서드가 처리하는 배치 한 번**입니다.

```text
주문 명령/초 = JMH score(배치/초) × 배치당 주문 수
환산 오차 = JMH scoreError × 배치당 주문 수
할당 바이트/주문 = gc.alloc.rate.norm(바이트/배치) ÷ 배치당 주문 수
```

결과의 `±`는 JMH가 출력한 99.9% 신뢰구간의 반폭입니다. JVM 3개에서 5회씩 측정한 총 15개 표본을 사용합니다. 요청 지연시간의 백분위나 개별 요청의 최대 오차가 아닙니다.

## 결과 — 합성 입력 처리량

6개 시나리오 모두 정상 종료했습니다. 아래는 최고값이 아니라 전체 측정 표본의 평균입니다. **주문 명령/초는 배치 결과를 환산한 값이며 API TPS나 정산 완료 건수가 아닙니다.**

| 경로 | 마켓 수 | 원본 배치/초 | 환산 주문 명령/초 | 할당 바이트/주문 |
| --- | ---: | ---: | ---: | ---: |
| 엔진 직접 호출 | 1 | 4,835.85 ± 255.81 | 4,835,853 ± 255,806 | 570.4 |
| 엔진 직접 호출 | 3 | 4,526.31 ± 353.63 | 5,431,570 ± 424,358 | 602.3 |
| 프로세서 생성 포함 | 1 | 1,430.73 ± 161.68 | 1,430,726 ± 161,684 | 695.6 |
| 프로세서 생성 포함 | 3 | 2,227.46 ± 216.55 | 2,672,956 ± 259,857 | 693.4 |
| 프로세서 재사용 | 1 | 1,199.97 ± 198.42 | 1,199,967 ± 198,416 | 825.8 |
| 프로세서 재사용 | 3 | 1,784.37 ± 332.89 | 2,141,241 ± 399,465 | 833.9 |

원본: [JMH JSON](results/2026-09-12-core-engine.json) · [전체 실행 로그](results/2026-09-12-core-engine.log)

JSON은 파일 끝의 빈 줄만 정리했고, 로그는 경고 메시지의 개인 디렉터리 경로만 `<repo>`로 치환했습니다. 측정값과 실행 옵션은 변경하지 않았습니다.
JDK 25에서 JMH 내부의 `sun.misc.Unsafe` 사용에 대한 deprecated 경고가 나왔지만, 벤치마크 실패나 예외 없이 6개 결과가 저장됐습니다.

이번 측정에서는 프로세서 재사용 경로가 생성 포함 경로보다 낮게 나왔습니다. 재사용하면 반드시 더 빠르다는 결론을 낼 수 없는 이유입니다. 두 경로는 입력 생성 포함 여부, 체결 패턴, 중복 방지 ID의 보관 기간이 다릅니다.
재사용 경로의 GC 누적 시간은 단일/다중 마켓에서 각각 2,761/3,630ms, 생성 포함 경로는 52/91ms로 기록됐습니다. GC 시간은 각 경로의 15회 측정에 걸친 JMH 누적값이며 개별 요청의 정지 시간이 아닙니다.
GC 부담과 ID 누적이 차이에 영향을 주었을 가능성은 있지만, 원인을 분리한 비교 실험은 하지 않았습니다.

재사용 경로의 상대 오차는 단일 약 16.5%, 다중 약 18.7%였습니다. 이 결과로 몇 퍼센트 수준의 개선을 주장하기보다는, 입력과 상태 수명을 맞춘 후 다시 비교하는 편이 타당합니다.

## 결과를 비교할 때 주의할 점

- 프로세서 재사용 경로에는 입력 생성이 포함되고 체결 패턴도 다릅니다. 엔진 직접 호출과의 차이를 전부 큐나 스레드 비용이라고 해석할 수 없습니다.
- 단일 마켓과 다중 마켓은 배치 크기와 마켓별 주문장 깊이가 다릅니다. 두 값의 비율이 그대로 3개 마켓의 병렬화 효과는 아닙니다.
- `SteadyStateBenchmark`라는 클래스 이름과 달리 메모리 사용량이 일정한 장기 실행을 검증한 것은 아닙니다. `MatchingEngine.seenOrderIds`는 체결이 끝난 주문 ID도 보관하므로, 주문장이 비어도 iteration 안에서 이 집합은 커집니다. 프로세서는 iteration마다 새로 만듭니다.
- GC의 `B/op`는 객체 **할당량**입니다. 주문장에 남은 메모리나 프로세스 최대 메모리 사용량을 뜻하지 않습니다.
- 모든 배치의 완료를 기다린 후 다음 배치를 넣습니다. 외부 요청이 계속 유입되는 상황의 큐 적체나 과부하 제어를 검증하지 않았습니다. 현재 프로세서 큐의 크기 제한도 없습니다.
- 수수료·DB·HTTP 경로를 실행하지 않았으므로 이 결과를 거래소 TPS, 정산 TPS, API 응답시간으로 사용할 수 없습니다. p95/p99 지연시간도 이번에는 측정하지 않았습니다.

## 실사용에 가까운 검증에 남은 항목

| 검증 단계 | 필요한 구성 | 현재 상태 |
| --- | --- | --- |
| 대표적인 주문 부하 | BUY·SELL·취소 비율, 주문장 깊이, 부분 체결 비율, 마켓별 쏠림을 정한 입력 | 고정된 새 주문 배치만 측정 |
| 전체 주문 경로 | 실행 가능한 API·DB·마켓·수수료 정책 구성 후 예약·정산·원장까지 연결 | 통합 테스트로 기능 확인. 전체 경로 부하 테스트는 미실행 |
| 동시 요청과 과부하 | 여러 클라이언트의 요청 유입률을 조절하고 완료 처리량, 실패율, p95/p99, 큐 적체 측정 | JMH 호출 스레드 1개가 배치 완료 후 다음 배치 제출 |
| 장기 실행과 재현성 | 고정된 서버/JVM/전원 조건, 워밍업 안정성 확인, 장시간 메모리·GC 관찰 | 개발용 Mac의 단시간 측정. 중복 방지 주문 ID의 누적 문제도 남아 있음 |

다음에는 먼저 실제 HTTP·DB 경로의 실행 구성을 마련하고 대표 입력을 정해야 합니다. JMH 옵션만 오래 돌리도록 바꿔서는 위 항목을 검증할 수 없습니다.

## 재현 명령

저장소 루트에서 JDK 25로 실행합니다. 이 벤치마크에는 Docker가 필요하지 않습니다.
`./gradlew build`는 JMH를 실행하지 않으므로 별도로 JAR를 빌드하고 실행해야 합니다.

```bash
./gradlew :benchmark-jmh:jmhJar --no-daemon

# 기존에 기록한 결과를 덮어쓰지 않도록 새 디렉터리에 저장
JMH_RESULT_DIR=$(mktemp -d /tmp/exchange-core-jmh.XXXXXX)

java -jar benchmark-jmh/build/libs/benchmark-jmh-0.0.1-SNAPSHOT-jmh.jar \
  '.*(MatchingEngineReplayBenchmark|MarketCommandProcessorReplayBenchmark|MarketCommandProcessorSteadyStateBenchmark).*' \
  -bm thrpt -tu s -t 1 -f 3 -wi 5 -i 5 -w 1s -r 1s \
  -jvm /usr/bin/java -jvmArgs '-Xms1g -Xmx1g' \
  -prof gc -rf json \
  -rff "$JMH_RESULT_DIR/core-engine.json" \
  -o "$JMH_RESULT_DIR/core-engine.log" \
  -foe true
```

위 명령은 이번 macOS 측정과 같은 옵션입니다. 다른 환경에서는 `-jvm`을 사용할 JDK의 `java` 경로로 바꾸고 버전을 함께 기록하세요. CLI 옵션은 소스와 Gradle에 있는 짧은 기본 워밍업·측정 설정을 덮어씁니다.
실제 측정 시 출력 경로만 `benchmark-jmh/results/2026-09-12-core-engine.{json,log}`를 사용했습니다.

JMH 공식 자료: [배치당 연산 수의 의미](https://github.com/openjdk/jmh/blob/1.37/jmh-core/src/main/java/org/openjdk/jmh/annotations/OperationsPerInvocation.java), [JVM을 분리하는 이유](https://github.com/openjdk/jmh/blob/1.37/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_12_Forking.java).
