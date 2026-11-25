# 거래 상태·복구·성능 검증 요약 — #142

이 문서는 분할된 제품·복구·측정 변경을 모두 적용한 상태의 계약과 기존 검증 기록을 요약한다. 분리하면서 새 영어 주석과 설명을 한국어로 옮겼으며, 실행 로직은 유지했다. 아래 성능은 중간 단계 PR 각각의 성능이 아니다. 기존 설계·실험 문서 15개와 `engineering/results/`의 게시 근거 186개는 [원래 커밋 `3d1248d`](https://github.com/0Chord/coin-exchange/tree/3d1248dd76b514a34577272d65c22d961a437dfd/engineering)에 보존한다. 이 요약은 기존 실행 기록을 다시 연결한 문서이며 새 부하나 전체 코드의 새 검증을 뜻하지 않는다.

## 거래 계약

- 별도 bounded 경로는 현재 잔고·활성 예약·주문장·pending 실행·수수료 누계와 유한한 최초 응답을 보관한다. 기존 legacy 이력·RocksDB 경로도 유지한다.
- 요청은 사용자·채널·epoch·증가 순번으로 식별한다. 같은 토큰과 내용은 보관 중 최초 receipt를 재사용하고, 다른 내용은 충돌한다. ACK로 퇴역한 토큰을 새 주문으로 실행하지 않는다.
- 연속 ACK는 완료 응답 보관만 정리한다. 활성 maker 주문·그 주문의 예약 자금·현재 잔고는 유지하며, 이후 체결에 사용한다.
- BTC/USDT·SOL/USDT·SUI/USDT는 한 거래 클러스터의 공유 사용자 자금과 순차 처리 경계를 사용한다. 접수와 내부 Execute를 구분하고, 매칭·정산의 전체 관계를 검증한 뒤 반영한다.
- bounded HTTP DTO·비동기 Gateway·Protobuf/Aeron 경로를 추가한다. 최종 wire는 Protobuf이며 SBE 실험은 채택하지 않았다. 최종 성능 후보는 불변 매칭 결과 재사용(A)만 유지했다.

계약과 실패 경계의 원문은 [최종 리뷰 지도](https://github.com/0Chord/coin-exchange/blob/3d1248dd76b514a34577272d65c22d961a437dfd/engineering/trading-pr-review-142.md), [상태·복구 설계](https://github.com/0Chord/coin-exchange/blob/3d1248dd76b514a34577272d65c22d961a437dfd/engineering/aeron-trading-ha-code-design.md), [후보 채택·제외 기록](https://github.com/0Chord/coin-exchange/blob/3d1248dd76b514a34577272d65c22d961a437dfd/engineering/trading-candidate-validation-20261031.md)에 있다.

## 복구와 공개 성공의 경계

Snapshot format 2는 현재 금융 상태·Inbox·pending·FIFO·수수료 나머지·순번·격리 상태를 복원한다. Init manifest와 실행 지문을 고정하며, 복원 중 현재 RDB 정책으로 과거 정책을 덮어쓰지 않는다. 부분 Snapshot이나 다른 lineage·실행 identity를 준비 완료 상태로 채택하지 않는다.

선택적인 별도 복구본 보호 모드는 네 번째 ClusterBackup/Archive의 복원 가능한 prefix를 인증하고, 공개할 결과의 적용 위치가 그 prefix 안에 들어올 때까지 성공 공개를 보류한다. 승격에는 원본 프로세스와 저장소의 부재, backup writer 종료·잠금, manifest·Snapshot·tail 재검증, 하나의 target에 대한 authority claim이 필요하다. 로컬 quorum 모드에는 이 별도 저장 확인을 붙이지 않는다.

기존 최종 장애·복구 실행은 별도 JVM 테스트 2개에서 16개 조건을 확인했다. 리더 종료·최초 결과 재전송·Execute 전/공개 응답 전 종료·genesis 로그 복구·새 Driver 경로·부분 Snapshot·전체 재시작·다른 lineage 거절과, 원본 세 저장소 삭제 후 네 번째 사본만으로 복구하는 사례를 포함한다. [실행 집계](https://github.com/0Chord/coin-exchange/blob/3d1248dd76b514a34577272d65c22d961a437dfd/engineering/results/candidate-validation-20261031/completion-summary.json), [최종 검증 대조](https://github.com/0Chord/coin-exchange/blob/3d1248dd76b514a34577272d65c22d961a437dfd/engineering/results/candidate-validation-20261031/final-verification-audit.json)

같은 Mac의 분리된 Docker/JVM·경로 시험이다. 실제 별도 호스트·가용영역·물리 디스크 손실의 RPO0, 운영 fencing·권한 연동, 완성 Snapshot body와 등록 사이 중단, 실제 Archive tail 손상·세 사본 불일치 탐지는 이 기록으로 보장하지 않는다. production bounded main/config 시작과 HTTP→Aeron→별도 복구본 전체 연결도 미검증이다.

## 최종 코드의 직접 Aeron 10분 관측

2026-10-31의 최종 A 코드에서 측정 주문 21,294,294건을 최종 ACK까지 600.052195940초에 완료했다. 전체 경과 기준 평균은 **35,487.4 TPS**다.

| 클라이언트가 관측한 경계 | 전체 경과 기준 TPS | p95 | p99 |
|---|---:|---:|---:|
| 접수 응답 | 35,487.4 | 25.07 ms | 44.30 ms |
| 리더 매칭·정산 완료 | 35,487.4 | 45.42 ms | 79.36 ms |
| 세 사본 매칭·정산 완료 | 35,487.4 | 50.04 ms | 87.29 ms |

세 TPS는 같은 측정 주문 수를 구간 준비·집계·drain·마지막 ACK를 포함한 전체 시간으로 나눈 값이다. 지연은 전송 시도부터 각 응답·완료 표식 수신까지이며, 전체 표본을 합친 HdrHistogram 집계다. 구간 백분위의 평균이 아니다.

- 같은 Mac의 독립 Docker 노드 3개와 별도 클라이언트. 노드당 CPU 2개·메모리 2 GiB·최대 Heap 1,024 MiB, Archive/catalog/consensus sync `[2,2,2]`, MediaDriver `/dev/shm`.
- Protobuf bounded wire3·직접 Aeron, BTC/SOL/SUI 순환, 계정 2개·마켓별 maker 1개의 부분 체결 workload. 예열 20초는 집계에서 제외했다.
- 접수 미응답 512개·세 사본 금융 완료 미관측 1,024개 한도에서 계속 전송했다. 고정 초당 전송률이나 대량 계정·주문장 용량 시험이 아니다.
- HTTP Gateway·Kafka·원격 복구본 저장 확인·장애 주입은 측정에서 제외했다. 세 사본 완료 표식에는 테스트 TCP 전송·수신 대기가 포함된다.
- 최종 세 노드의 상태 지문·잔고·예약·수수료·maker 잔량·순번·ACK floor를 대조했다. pending·보관된 접수 결과·완료 collector 잔여는 0이었다.
- 드문 컨테이너 메모리 표본과 호스트 swap 증가를 기록했다. 한 호스트의 단회 10분 관측이며, 안정적인 5만 TPS·sub-ms p99·절대 최대 용량이나 A의 처리량 개선율을 입증하지 않는다.

[관측 보고서](https://github.com/0Chord/coin-exchange/blob/3d1248dd76b514a34577272d65c22d961a437dfd/engineering/trading-sustained-final-a-20261031.md), [수치 원문](https://github.com/0Chord/coin-exchange/blob/3d1248dd76b514a34577272d65c22d961a437dfd/engineering/results/sustained-final-a-20261031/summary.json), [실행 당시 600초 클라이언트·재현 절차](https://github.com/0Chord/coin-exchange/tree/3d1248dd76b514a34577272d65c22d961a437dfd/engineering/results/sustained-final-a-20261031)

## 엔진 단독 진단과 검사 기록

최종 A의 엔진 단독 반복 진단 집계는 **73,432.81 TPS**였다. 기준/A 각각 새 JVM 3개에서 작은 유한 workload를 5회 실행하고, JVM별 마지막 3회의 중앙값을 다시 JVM 3개의 중앙값으로 집계했다. 호출마다 새 상태를 만든 진단이며 HTTP·Aeron 복제·저장·장기 지속 성능과 범위가 다르다. 이 수치를 앞의 세 노드 10분 결과와 합치거나 네트워크 처리량으로 쓰지 않는다. [집계 원문](https://github.com/0Chord/coin-exchange/blob/3d1248dd76b514a34577272d65c22d961a437dfd/engineering/results/candidate-validation-20261031/engine-repeated-summary.json)

기존 최종 회귀 집계 887개는 matching 156개·trading 276개의 동일 입력 `UP-TO-DATE` 재사용과 architecture 385개·선택 app-api 70개의 새 실행을 합친 값이다. Python 13개와 실제 장애·복구 테스트 2개는 별도다. 전체 앱의 모든 검사를 최종 코드에서 새로 실행했다는 뜻이나 PR 전체의 새로운 독립 승인으로 표현하지 않는다. [회귀 집계](https://github.com/0Chord/coin-exchange/blob/3d1248dd76b514a34577272d65c22d961a437dfd/engineering/results/candidate-validation-20261031/final-regression-summary.json), [경로별 검증 범위와 남은 공백](https://github.com/0Chord/coin-exchange/blob/3d1248dd76b514a34577272d65c22d961a437dfd/engineering/results/pr-publish-20261031/verification-map.md)

분할 후 각 PR의 새 컴파일·검사·CI 결과는 해당 PR에서 별도로 기록한다. 이 문서의 과거 성공을 현재 CI 성공으로 대체하지 않는다. #142의 HTTP 진단 범위와 남은 운영 검증도 별도로 남는다.
