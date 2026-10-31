# #142 게시 범위와 리뷰 순서

## 문제와 결과

완료 주문의 전체 결과·원장 표현을 계속 붙잡거나 매 주문마다 이력 저장을 기다리는 비용 때문에 처리량과 메모리 사용량이 악화됐다. 이번 변경은 별도 bounded 거래 경로에서 현재 잔고·활성 예약·주문장·미완료 실행을 유지하고, 최초 응답은 세대·증가 순번과 ACK로 보관 범위를 제한한다. 확정 입력과 현재 상태 Snapshot을 복구의 근거로 사용한다.

기존 legacy 이력·RocksDB 경로는 호환 경로로 남아 있다. 새 bounded 경로가 주문당 과거 결과 인덱스 저장을 기다리지 않는다는 뜻이며, 저장소 전체에서 RocksDB를 삭제한 변경은 아니다. 자금과 매칭은 여전히 하나의 거래 클러스터에 속하며 세 사본이 같은 입력을 처리한다. 마켓별 별도 클러스터·Kafka·조회 DB는 이번 범위가 아니다.

통합 대상은 `feature/phase-2/integration`이다. 기존 미게시 커밋 9개와 이번 기능·최적화·검증 기록을 포함하므로 작은 최적화 3파일 PR로 표현하지 않는다. 마지막 A 최적화는 별도 커밋으로 분리했다. 이전 커밋의 측정·이력 도구와 최종 bounded 경로를 아래 순서로 나눠 검토한다.

## 전체 흐름

| 입력과 시작 | 담당 판단 | 결과·실패 경계 |
|---|---|---|
| 고정 manifest로 빈 bounded 엔진 시작 | 마켓·수량 단위·정책·자원 한도·프로토콜/실행 지문 확인 | 다른 설정·코드·복구 identity를 같은 이력에 섞지 않음 |
| 사용자·채널·epoch·sequence와 주문 | 유한 Inbox가 기존 토큰/원문·순번·보관 한도 확인, 새 요청만 자금 판단 | 같은 토큰·같은 내용은 보관 중 최초 receipt 재사용. 충돌·오래된 요청·한도 초과는 추가 예약 없이 구분 |
| 신규 승인 | 공유 자금 예약·작은 최초 receipt·실행 대기를 반영 | 마켓이 달라도 같은 사용자 자금 사용. 자금 부족과 준비·정책 거절의 기록 계약 유지 |
| 확정된 내부 Execute | 매칭·여러 체결의 정산 후보와 전체 결과를 검증한 뒤 함께 반영 | 실제 반영 전 실패와 반영 중 상태 불신을 구분. 수수료를 전체 이력 객체 대신 검증된 합계로 유지 |
| 연속 결과 ACK | 실행이 끝난 응답의 보관 floor 전진 | 과거 응답 보관을 정리하되 활성 maker·예약·현재 잔고는 유지. 퇴역한 요청을 새 주문으로 재실행하지 않음 |
| Snapshot·로그 재시작 | 보관 중 응답·현재 자금·활성 주문·pending·순번·설정/코드 지문 복구 | 누락·잘못된 관계·불완전 Snapshot·다른 실행물을 준비 완료로 채택하지 않음 |
| 선택적인 별도 복구본 보호 모드 | 필요한 로그 prefix와 증명·승격 권한 확인 | 성공 공개 전 저장 범위를 기다리고, 같은 authority의 서로 다른 target 승격 차단. 실제 별도 리전 RPO0 검증과 구분 |
| bounded HTTP 요청 | 별도 profile의 DTO·검증 → 비동기 Gateway → bounded Protobuf/Aeron | 정상 결과·충돌·미제출·결과 미확인을 구분. epoch/ACK 요청도 같은 통신 경로 사용 |

전체 코드 설계와 장애 경계는 [상태·복구 설계 §17](aeron-trading-ha-code-design.md)을 함께 읽는다. 절 제목이 바뀌면 문서의 `17.` 절을 찾는다.

## 첫 번째 리뷰 판단: ACK가 돈을 풀어도 되는가

같은 사용자 USDT 1,000, 수수료 0, 상대 주문이 없는 BUY의 필요 자금 300을 가정한다. 주문이 접수·실행돼 잔량 대기 상태가 되면 사용 가능 700·예약 300이다. 사용자가 receipt를 확인해 ACK했다고 해서 예약 300을 풀면 이후 maker 체결에 쓸 자금이 사라진다. ACK는 응답 보관만 줄이고 활성 주문·예약을 보존해야 한다.

이 판단은 [TradingRequestInbox](../domain-trading/src/main/kotlin/com/exchange/core/trading/TradingRequestInbox.kt), [TradingState](../domain-trading/src/main/kotlin/com/exchange/core/trading/TradingState.kt), [bounded 상태 검사](../domain-trading/src/test/kotlin/com/exchange/core/trading/BoundedTradingStateTest.kt), [Inbox 검사](../domain-trading/src/test/kotlin/com/exchange/core/trading/TradingRequestInboxTest.kt), [Snapshot 연속 실행 검사](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/history/BoundedTradingSnapshotContinuationTest.kt)에서 연결해 읽는다. 퇴역 뒤의 과거 결과는 새 주문으로 되살리지 않으며, 외부 과거 결과 조회 서비스는 이번에 구현하지 않았다.

## 파일과 역할

1. 거래 계약·금융 상태: `domain-trading`의 `TradingRequest*`, `TradingResourceLimits`, `BoundedTradingValues`, `TradingState`, 접수·정산 계산기. `domain-matching`의 결과 검증은 원본 명령과 불변 이벤트의 전체 관계를 확인한다.
2. 전송·Gateway: `BoundedTradingCodec`, 기존 `order_admission.proto` 확장, `BoundedAeronClient/Gateway`, bounded HTTP DTO·use case·controller와 Spring 설정.
3. 복구·별도 사본: bounded Snapshot codec/persistence, 실행 지문, `infrastructure/recovery/`의 prefix proof·파일 gate·backup·권한·승격. 이 경로와 local quorum 성능은 분리한다.
4. 운영 계측: Adapter·Telemetry의 기존 phase 계측, bounded handoff·실패 전달. 요청 ID를 운영 metric tag로 늘리지 않는다.
5. 검증 전용: `src/test`의 노드·클라이언트·TCP 완료 표식·fault control·발생기 및 Python 제어기. 제품 API나 거래 상태에 동시 출발 gate를 추가하지 않는다.
6. 문서·근거: 결과 보고서와 선별한 JSON·검사 기록. 수천 원본 로그·컴파일 결과·실험 snapshot ZIP은 로컬에 보존하고 게시하지 않는다.

## 검증 근거

- 최종 회귀 결과 887개: 도메인 432개는 동일 입력의 `UP-TO-DATE` 결과, 구조·관련 앱 455개는 새 실행. Python 13개는 별도다. [실행 집계](results/candidate-validation-20261031/final-regression-summary.json)
- 실제 별도 JVM 장애·복구 2개 테스트의 16조건 통과. [장애 집계](results/candidate-validation-20261031/completion-summary.json), [독립 대조](results/candidate-validation-20261031/final-verification-audit.json)
- 최종 코드의 직접 Aeron·세 Docker 노드·sync `[2,2,2]`·BTC/SOL/SUI·예열20초·측정600초. 21,294,294건 / 최종 ACK까지600.052196초 = **35,487 TPS**. 접수 p95/p99 **25.07/44.30ms**, 리더 완료 **45.42/79.36ms**, 세 사본 완료 **50.04/87.29ms**. [10분 결과](trading-sustained-final-a-20261031.md), [원본 요약](results/sustained-final-a-20261031/summary.json), [측정 소스와 재현 절차](results/sustained-final-a-20261031/README.md)
- A/B/C 후보 28회 비교 뒤 A만 유지. 조건부 깨우기와 클라이언트 단일 디코딩은 원복. 엔진 단독 짧은 진단 73,433 TPS와 세 노드 10분 결과를 같은 범위로 쓰지 않는다. [후보 결과](trading-candidate-validation-20261031.md)
- 기존 bounded HTTP·gate·codec·identity 성공은 같은 제품·검사 소스의 검증 기록을 재사용했다. snapshot 최초 앱 전체 실행의 실패/skip 뒤 수정 클래스의 재검사를 전체 새 실행처럼 합산하지 않는다. 이전9커밋의 검토, bounded snapshot 검토와 이후 좁은 후보 검토는 각 범위의 근거로 연결하며 PR 전체의 새로운 독립 승인이라고 표현하지 않는다.

게시 시 저장소 전체 `./gradlew ktlintCheck --no-daemon --console=plain --continue`도 통과했다. 같은 제품으로 새 장기 부하는 실행하지 않았다. 원격 CI 결과는 PR에서 별도로 확인한다.

## 남은 경계

- 동일 Mac의 Docker·별도 JVM/경로 시험이다. 실제 별도 호스트·가용영역·물리 디스크 손실의 RPO0 보장이 아니다.
- production bounded main/config의 실제 시작과 HTTP→Aeron→별도 복구본 전체 연결은 이번 기록에서 미검증이다. HTTP 단위 fixture의 transport는 대역이며 직접 Aeron 성능을 HTTP TPS로 쓰지 않는다.
- 스냅샷 완성 body와 등록 사이의 중단, 실제 Archive tail 손상, 실제 세 사본 불일치 탐지, 운영 fencing과 권한 연동은 별도다.
- 10분 평균은 절대 최대·안정적 5만 TPS·sub-ms p99 달성이 아니다. 호스트 swap·guard 비용과 드문 메모리 표본의 한계도 보고서에 남겼다.
- #142는 원래 HTTP 진단을 포함한다. 이번 게시만으로 전체 완료 처리하거나 이슈를 자동 종료하지 않는다.
