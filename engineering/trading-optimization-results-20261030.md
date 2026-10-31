# 거래 엔진 최적화 결과와 Protobuf 유지 결정

2026-10-30 · `test/http-trading-performance/142`

> **후속 수정과 재측정:** [Protobuf 경로의 성능 회귀 수정 결과](trading-regression-fix-results-20261030.md)를 먼저 확인한다. 큰 실행 함수의 컴파일 문제·발생기의 빈 조회 비용을 줄였고, 같은 한도 256에서 세 노드 완료 중앙값 31,687→34,375 TPS를 확인했다. 아래 문서는 그보다 앞선 SBE 비교·제거 시점의 이력이다. 후속 한도 512 결과와 섞지 않는다.

업무 코드 최적화와 SBE 비교 실험을 진행했다. **최종 선택은 Protobuf다. SBE 생성 빌드·스키마·런타임 선택 경로를 현재 소스에서 제거했다.** 도메인 계산 개선과 금융 오류 차단 수정은 유지한다. 아래 SBE 수치·장애 시험·고정 실행물은 과거 실험의 증거로 보존하며, 현재 실행 가능한 경로라는 뜻이 아니다. 50k TPS는 달성하지 못했다.

## 왜 이전보다 낮아 보이나

서로 다른 두 비교를 구분해야 한다.

1. **같은 새 측정에서 Protobuf와 SBE 비교:** 개선 Protobuf의 세 사본 완료 중앙값은28,326 TPS/p99 49.54ms, SBE는28,156 TPS/p99 58.39ms다. SBE의 CPU·할당 감소가 전체 처리량·tail 개선으로 이어진다는 근거가 없어 전환하지 않는다. 이 SBE 스키마의 공통 Reply 고정 필드 때문에 승인 응답도66B→196B로 커졌다. 모든 SBE 설계의 한계라는 뜻은 아니다.
2. **과거35,133과 새 기준선26,014 비교:** 둘 다 Protobuf이고 `BoundedTradingCodec`과 Protobuf 스키마는 동일하다. 예열20초·제출10초, 창256/1,024, async ACK, partial-maker, sync2와 TPS 분모 정의도 같다. 달라진 것은 실행6단계 observer 연결, 발생기 gate 관측, collector·노드 표식의 원자 카운터와 표본 시간 계측이다. 각각의 비용을 제거해 분리 측정한 기록은 없으므로, 계측이 하락분 전부의 원인이라고 확정하지 않는다. **역사적 하락의 원인은 미확정이다.**

CAPTURE는 주요 TPS 측정 phase의 전후에 있다. CAPTURE 대기가 TPS 분모에 새로 추가되어 떨어졌다고 설명하지 않는다. 서비스 스레드 CPU·할당 차분에는 종료 CAPTURE 비용이 포함된다. 별도로 짧은 새 JVM 엔진 프로브의 초기 회귀는 아래 JIT 절에 남겼으며, SBE 제거만으로 이 문제가 해결됐다고 주장하지 않는다.

이번 Protobuf 복원에서는 새 성능 시험을 실행하지 않았다. 따라서 Protobuf로 돌려서 과거35k가 복구됐다는 새 결과도 없다.

## 무엇을 바꿨나

| 영역 | 구현 |
|---|---|
| 예약 접수 P1 | bounded 접수에서 쓰지 않는 RESERVE 원장·분개·계정 문자열·source SHA 생성 제거. 공개 후보 계산과 같은 예약 규칙과 관계 검증 사용 |
| 정산 입력 P2 | 체결이 있는 실행의 초기 예약 수치 검증을 계산기에서 한 번 수행. 무체결·복구 검증과 변경 후 예약 검증은 유지 |
| 중간 값 P3a·P3b | 체결 목록을 한 번 순회. 무체결에서 사용하지 않는 정산 인덱스 생성을 생략하고 소유자·자산·잔고 hold 검사는 유지 |
| SBE P5·P6 — 실험 종료 | schema88/version0의 생성 스키마·변환기·선택 경로를 구현해 비교한 뒤 현재 소스에서 제거. 원본 결과와 고정 실행물 보존 |
| 복구 경계 | 현재 Protobuf schema87/version3과 Snapshot 형식2의 manifest/schema/artifact 지문 대조 유지. 과거 SBE 실험은 새 lineage·새 실행 루트였으며 로그 혼용 없음 |
| 계측 P0 | 예열 후 CAPTURE와 종료 CAPTURE의 같은 서비스 스레드 CPU·할당·운영 메트릭 차분. 세 gate 대기를 중복 없이 기록. 전수 금융·완료 검증 유지 |

조건부 P3c 인덱스 공유·P4 매칭 참조 축소·scratchpad·단일 확정 단계·sync0는 추가하지 않았다. 검증을 생략하거나 금융 계약을 바꾸는 최적화는 하지 않았다.

## 세 노드 성능

동일 Mac M1 Pro 8코어·16GiB, Docker VM 8CPU·약7.75GiB. 세 노드와 별도 발생기 각각 CPU2·메모리2GiB·Heap1GiB, G1. BTC/SOL/SUI partial-maker, 공유 USDT, maker1,000ppm·taker2,000ppm, Archive/catalog/Consensus 모두 sync2, Driver `/dev/shm`. 접수 창256·세 사본 미완료 관측 창1,024·async ACK trigger256·최대2,000,000샘플을 유지했다.

**직접 Aeron 측정이며 HTTP API와 원격 Backup 저장 확인은 제외한다.** 각 실행은 예열20초 뒤10초 제출하고 마지막 완료 관측까지 분모에 포함했다. 세 구성 A/B/C를 ABC→CAB→BCA 순서로 총9회 실행했다. 아래 각 값은 구성별3회 중앙값이다. TPS와 percentile의 중앙값은 동일한 단일 실행의 쌍을 뜻하지 않는다.

| 구성 | 접수 TPS | 접수 p95 / p99(ms) | 세 사본 완료 TPS | 완료 p95 / p99(ms) |
|---|---:|---:|---:|---:|
| 계산 기준선 + Protobuf | 26,031 | 17.99 / 27.39 | 26,014 | 37.59 / 54.74 |
| 계산 개선 + Protobuf | 28,365 | 16.35 / 24.02 | 28,326 | 35.98 / 49.54 |
| 계산 개선 + SBE | 28,177 | 16.86 / 29.09 | 28,156 | 35.08 / 58.39 |

노드당 서비스 스레드의 주문당 비용은 다음과 같다. 같은 노드의 전후 누적값을 차분하고 노드3개의 평균을 계산한 뒤 실행3회의 중앙값을 냈다. 종료 CAPTURE·경계 제어 비용이 포함되며 순수 매칭 CPU나 전체 서버 CPU가 아니다.

| 구성 | CPU / 주문 | 할당 / 주문 |
|---|---:|---:|
| 계산 기준선 + Protobuf | 18.27 μs | 13,057 B |
| 계산 개선 + Protobuf | 16.51 μs | 11,625 B |
| 계산 개선 + SBE | 15.28 μs | 10,821 B |

계산 개선+Protobuf는 기준선 대비 중앙 CPU 약9.6%, 할당 약11.0% 감소했다. 완료 TPS 중앙값은26,014→28,326, 완료 p99는54.74→49.54ms였다. 같은 블록 내 TPS 변화는−0.6% / +5.6% / +15.0%여서 고정 향상률이나 지속 최대 용량으로 주장하지 않는다.

SBE는 개선 Protobuf 대비 CPU 약7.5%, 할당 약6.9%를 더 줄였으나, 같은 블록의 완료 TPS 변화가+9.5% / +9.9% / −16.1%로 흔들렸다. 중앙 완료 p99도49.54→58.39ms로 높았다. **이번 근거로 SBE 기본 전환을 채택하지 않는다.**

과거35,133 TPS와 이번26,014 TPS 기준선의 하락은 원인이 미확정이다. 새 계측이 추가됐으므로 이 차이를 SBE나 도메인 개선의 영향으로 귀속하지 않는다. 모든 실행은 같은 Mac의 CPU·디스크를 공유했고, 완료 관측에는 테스트 TCP 표식 전달도 포함된다.

### 9회 원본 값

| 블록 | 구성 | 접수 TPS | 완료 TPS | 완료 p95(ms) | 완료 p99(ms) |
|---|---|---:|---:|---:|---:|
| 1 | p0-protobuf | 28,503 | 28,483 | 35.57 | 54.13 |
| 1 | p3-protobuf | 28,365 | 28,326 | 35.98 | 49.54 |
| 1 | p3-sbe | 31,031 | 31,019 | 32.13 | 44.91 |
| 2 | p3-sbe | 28,177 | 28,156 | 35.08 | 58.39 |
| 2 | p0-protobuf | 24,272 | 24,252 | 50.47 | 79.45 |
| 2 | p3-protobuf | 25,663 | 25,614 | 41.47 | 71.39 |
| 3 | p3-protobuf | 29,981 | 29,906 | 31.64 | 44.94 |
| 3 | p3-sbe | 25,094 | 25,083 | 44.25 | 65.02 |
| 3 | p0-protobuf | 26,031 | 26,014 | 37.59 | 54.74 |

## 엔진 단독의 초기 회귀와 JIT

동일한1,000예열+5,000측정 프로브를 새 JVM으로3쌍 실행하면 P0 중앙34,916 TPS, 후보27,057 TPS로 **초기 처리량 회귀가 남았다.** 할당은 약13.0KB→11.5KB로 줄었지만 이를 TPS 개선으로 취급하지 않는다.

원인을 분리하려고 기존 프로브를 같은 JVM에서5회 반복했다. P0는35,354→45,216, 후보는27,281→66,195 TPS로 바뀌었다. 이는 원인 진단이며5회째 값을 새 성능 기준선으로 바꾸지 않는다. PrintCompilation에는 후보 실행 메서드의 초기 tier3 컴파일이 `out of virtual registers in linear scan`으로 건너뛰어진 기록과 이후 tier4 컴파일이 있다. 건수 기반의 짧은 예열만으로 컴파일 완료를 보장하지 못한다. 특정 컴파일 이벤트가 몇 μs의 지연을 만들었는지는 확정하지 않았다.

프로세스 첫 구간의 회귀와20초 예열 후 세 노드 결과를 구분해 보존했다. 큰 실행 함수의 분리와 시작 시 예열 완료 조건은 후속 검토 사항이다. 이번에는 이 가설만으로 금융 코드를 다시 바꾸거나 테스트를 계속 연장하지 않았다.

## SBE 실험의 비용과 종료 결정

명령·응답을 소유 바이트로 만든 뒤 소유 도메인 값까지 되읽는 짧은 비교도 실행했다. 전송·엔진·복구 TPS를 뜻하지 않는다. 실제 라이브러리 생성 코드와 callback 길이 검증을 포함했다.

| 메시지 | Protobuf 바이트 | SBE 바이트 |
|---|---:|---:|
| 접수 | 51 | 84 |
| Execute | 44 | 60 |
| 승인 응답 | 66 | 196 |
| 잔고 부족 응답 | 90 | 213 |

SBE 명령의 CPU·할당은 줄었으나, 이 스키마는 공통 Reply 고정 필드와 presence 때문에 응답 크기가 커진다. 승인·거절 응답의 CPU가 모든 반복에서 더 낮지도 않았다. 모든 데이터를 off-heap에 옮기거나 GC가 없어졌다는 변경은 아니다. 문자열 변환과 소유 도메인 객체는 남는다.

- 현재 node·client·Gateway는 기존 `BoundedTradingCodec`의 Protobuf 경로만 사용한다. `FUNDING_WIRE_FORMAT` 선택 기능과 SBE 생성 의존성을 제거했다.
- 기존 Protobuf 스키마 원문과 schema 지문은 유지한다. 금융 계산·최초 결과 재사용·복구 보장 코드는 되돌리지 않는다.
- 과거 SBE 실행물·소스·결과는 `build/optimization-sbe-20261030`과 아래 증거 폴더에 보존한다. 기존 SBE 로그를 Protobuf로 재해석하지 않는다.
- 기존 Protobuf 보존 실행물은 `build/optimization-sbe-20261030/baseline-protobuf`에 파일별 SHA와 함께 남긴다.

## 실험 고정본에서 무엇을 검증했나

다음 표는 **SBE 경로 제거 전 고정본**의 실행 결과다. 제거 후 검증은 마지막 절에 별도로 기록한다.


| 검사 | 실제 결과 |
|---|---|
| 전체 API 회귀 | 1,407 tests, 실패0, opt-in3 skip. 이후 SBE opt-in 장애/복구2개 실제 실행 |
| 도메인 | 276 tests, 실패0 |
| 매칭 | 153 tests, 실패0 |
| 아키텍처 | 387 tests, 실패0. 생성 SBE 경로·생성 task·패키지만 정확히 허용 |
| SBE 변환 | 13 cases 통과. 독립 golden bytes, 모든 잘림 prefix, 잘못된 UTF-8·enum·presence·길이·후행 바이트·소유권 검사 |
| 두 codec의 Adapter/Gateway | Adapter14 invocations, Gateway8 invocations 통과. retry 바이트 보존·외부 Execute 거절·내부 origin·timeout 미확인·원격 저장 gate 확인 |
| SBE 실제 세 JVM HA | 10 checks 전부 true: 리더 종료, 중복 예약 방지, genesis 로그 재시작, Snapshot+tail, 부분 Snapshot 배제, Driver 파일 없이 복구, identity 불일치 거절 |
| 네 번째 Backup만으로 복구 | 6 checks 전부 true. 원래 세 프로세스와 저장소 삭제, 보존 실행물·Snapshot+tail로 상태 일치, 승계 권한 중복 소비 거절, 늦은 maker 체결·최초 결과 유지 |
| 성능9회 | 전부 금융·세 사본 상태 일치, 샘플 상한 미도달, 소유 컨테이너·볼륨 정리 성공 |
| 계측 controller | Python11 tests 통과. 같은 스레드 차분·지원 불가 null·gate 경계·예산 검사 |
| 독립 리뷰 | 도메인·wire·계측 별도3컨텍스트. 설정 전달 누락과 fatal observer 오류 전파2건 수정 후 재리뷰. 미해결 확정 결함0 |

실제 시험의 Given/When/Then: 주문과 자금을 준비한 세 사본에서, 리더를 끊거나 모든 원본 저장소를 제거한 뒤, 복원된 잔고·예약·수수료·최초 결과·주문 watermark를 원본과 대조했다. 단지 프로세스가 다시 떴는지만 확인한 시험이 아니다. 같은 Mac의 별도 JVM 검증이며 여러 EC2/AZ의 네트워크 분할·물리 전원 장애 시험은 아니다.

## 증거와 재현

- 원본 성능9회: `engineering/results/optimization-sbe-20261030/runs/`.
- 집계와 paired 변화: [comparison-summary.json](results/optimization-sbe-20261030/comparison-summary.json).
- 초기 엔진 비교: [engine-final-paired.json](results/optimization-sbe-20261030/engine-final-paired.json).
- JIT 진단: [jit-diagnostic.json](results/optimization-sbe-20261030/jit-diagnostic.json), 원본 compiler 로그는 `build/optimization-sbe-20261030/jit-*.log`.
- Codec 비교: [codec-comparison.json](results/optimization-sbe-20261030/codec-comparison.json).
- HA 원본: [sbe-ha-evidence.json](results/optimization-sbe-20261030/sbe-ha-evidence.json).
- 최종 소스는 `build/optimization-sbe-20261030/final-source`와 보조3개 스크립트, 파일별 manifest로 고정했다. 실제 공통 실행물은 `runtime-domain-candidate`, P0는 같은 실행물에서 `engine-p0-domain.jar`만 교체했다. 두 실행물의 차이가 해당 JAR 하나뿐임을 확인했다.
- 실행 절차: `build/optimization-sbe-20261030/perf-runbook.md`. 재현 명령은 반드시 `/Users/0chord/.codex/worktrees/issue148-http-aeron/exchange-core`에서 실행한다.

이번 측정 중 세 노드 기록의 최대 실제 할당은650.3MiB였고, 1.75GiB 조기 중단·2GiB 예산·host8GiB 여유 guard를 유지했다. guard 대기 시간을 TPS 분모에서 빼지 않았다. 성능 시험 이미지와 임시 runtime 복제본은 정리 대상이며, 결과·지문·공통 실행물·기존 Protobuf 보존본은 남긴다.

정리 완료: 이번 성능 실행의 컨테이너·볼륨과 두 시험 이미지를 제거했다. 24시간 이상 사용하지 않은 재생성 가능한 Docker 빌드 캐시9.073GB를 정리했고, host 여유는약85GiB다. 공통 실행물·Protobuf 보존본·원본 결과는 남겼다.


## Protobuf 단일 경로 복원 후 검증

SBE 선택기·변환기·생성 스키마·생성 task·해당 전용 테스트를 제거했다. 원래 Protobuf 변환기·스키마는 기준 소스와 동일하다. Adapter의 실행 observer 연결, 차단 상태의 예외 재전파, 내부 메시지 origin 검사는 유지했다. 기존 HA 변경 전체를 되돌리지 않았다.

- 관련 API 회귀71개 통과: Protobuf codec4, Adapter 관측7, bounded Gateway4, artifact identity1, 자금 입력 경계5, 기존 Gateway24, 기존 Adapter26. 실패·skip0.
- 아키텍처385개 통과. SBE 생성 경로 예외를 제거한 상태로 production 경계·배치 규칙을 확인했다.
- 변경4파일의 범위 지정 ktlint 검사 통과. 기존 전체 초안의 포맷을 일괄 변경하지 않았다.
- Python 계측 controller11개 통과. 기존 측정 계측은 유지하고 wire 선택 인자만 제거했다.
- 활성 main 클래스·리소스·두 배포 준비 폴더에서 SBE 업무 codec/생성 클래스/스키마 잔존0건. Aeron 자체 내부 프로토콜 의존성은 제거 대상이 아니다.
- 작성 대화를 받지 않은 별도 리뷰어가 고정 소스520개를 검토했다. 금융 변경 없음·Protobuf 지문 유지·observer/차단 예외 보존을 확인했고, 검토 범위 내 확정 결함0건이다. 검토 후 테스트 import 순서만 정렬했다.
- 성능·실제 HA 장애 시험은 재실행하지 않았다. 위456개 회귀 통과가 처리량 복원이나 새 장애 시험 결과를 뜻하지 않는다.

회귀·출력 검증 근거는 `engineering/results/optimization-sbe-20261030/protobuf-only/`에 보존한다. 실행은 `/Users/0chord/.codex/worktrees/issue148-http-aeron/exchange-core`에서 관련7개 테스트 클래스만 선택한 `:app-api:test`와 `:architecture-tests:test`로 진행했다.
