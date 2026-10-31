# 게시 검증 근거 지도 — 2026-10-31

대상: `test/http-trading-performance/142`, 기준 `origin/feature/phase-2/integration`. 이 보고서는 읽기 전용 증거 대조다. 빌드·테스트·부하·원격 작업을 실행하지 않았다.

현재 `build/candidate-validation-20261031/final-source-manifest.json`의 595파일은 live 소스와 모두 일치한다. 게시 diff의 생산/빌드 72파일도 모두 manifest에 포함된다. 72파일 중 63파일은 snapshot-7과 바이트가 같으며, 8개 변경과 1개 추가는 아래 후속 리뷰 체인에 연결된다. 파일별 지문·고정본 동일성은 `verification-map.json`에 있다.

## 경로별 근거

| 경로 | 실행 근거 | 독립 검토/동일성 | 남는 경계 |
|---|---|---|---|
| bounded HTTP DTO·controller·use case·예외 처리 | snapshot-7 앱 전체 실행에서 `BoundedTradingOrderHttpTest` 3건 성공. token/서버 order ID/DTO·UTF8 거절/내부 endpoint 비공개 | 제품과 테스트 모두 snapshot-7과 현재 바이트 동일. §17 app 리뷰 이력 | 최신 app70에 포함 안 됨. transport 대역을 쓰므로 실제 Aeron E2E는 아님 |
| Remote gate·파일 proof·권한·승격 | snapshot-7의 HTTP 1·gate6·archive2·authority1. 최신 remote JVM 1개에서 6조건 재실행 | recovery 제품/HTTP·gate 테스트 현재 snapshot-7 동일. §17 remote 리뷰 기록 | HTTP fixture의 transport는 대역. 실제 HTTP→Aeron→네 번째 사본 전체 경로와 물리적 별도 장애영역은 미검증 |
| BoundedTradingGatewayConfig·boot | schema guard는 후속 wire/Protobuf cleanup 정적 검토에 연결. config 현재 protobuf-only/source·regression-v2·service-final 동일 | snapshot-7 이후 config 실제 delta는 manifest.schemaSha256 검증 추가. 앱 main·나머지 설정은 snapshot-7 동일 | HTTP 테스트는 TestConfiguration으로 gateway를 조립하고 실제 BoundedTradingGatewayConfig를 import하지 않음. 운영 설정의 bounded main 시작·실제 연결을 검증했다고 주장 불가 |
| bounded protobuf wire/identity | snapshot-7 codec4·identity1·initial connect4·funding isolation5 성공. 최신 codec4 및 실제 HA/DR에서 codec·identity 사용 | codec/client/identity/proto 현재 snapshot-7 동일. 이후 SBE 제거 독립 리뷰 기록 | 외부/잘못된 frame 단위 사례와 실제 프로세스 복구는 구분. SBE는 최종 제품에 없음 |
| bounded snapshot/continuation | 최신 `BoundedTradingStateCodecTest`, `BoundedTradingSnapshotContinuationTest`, recovery5와 실제 HA10·remote6조건 | persistence/codec 제품 snapshot-7 동일. §17 HA/app/remote 리뷰 및 후속 wire 리뷰 연결 | H05는 body 도중 중단. 완성 body와 등록 사이 중단·실제 Archive tail 손상 미검증 |
| bounded domain·Inbox·fee·quarantine | matching156·trading276 동일 입력 UP-TO-DATE 재사용, snapshot continuation·HA/DR fresh. domain276에 admission·settlement·bounded/inbox/fee/failure 포함 | snapshot-7 이후 P0/P1/P2/P3a/P3b 전체 domain 리뷰 → fatal observer 수정 delta → final137파일 연결 → Protobuf cleanup → regression-v2 helper → candidate A. 각각 지정 범위만 검토 | 마지막 A 리뷰만으로 domain 전체 신규 승인 아님. JVM 스케줄/실제 세 사본 불일치 탐지·물리적 RPO0는 별도 |
| Adapter·telemetry | service-progress40+arch385, 최신 app70/HA2 실행에서 영향 경로 재검사 | 현재 Adapter/Telemetry는 service-final 고정본과 바이트 동일. reset·retry·SPSC/close/fatal 독립 리뷰. B worker wake 후보는 제거됨 | bounded offer의 엄격한 wall-clock cutoff는 기존부터 보장하지 않음. static concurrency review가 스케줄 전수 검증은 아님 |
| 최종 A: 불변 matching events 재사용 | matching156·trading276 재사용+app70/arch385 fresh, 실제 HA/DR2 fresh, 28회 단기 성능의 금융·3사본 지문 대조 | candidate A 원본 명령·소유권·전체 result 검증·preapply/fatal 검토. current 2 product파일은 final-source 동일 | 세 노드 TPS 향상·50k 지속 최대·HTTP 성능 달성 주장 불가 |
| 기존 legacy history/원장·fee/order/matching 변경 | history 문서: 최종721(도메인228·구조385·app108), 이전676 및 관련 history 회귀. 이후 앱 class matrix 및 최신 domain통합 회귀 | 해당 변경의 별도 고정본 리뷰 기록은 trading-history-retention / trading-retention / http-trading-performance 문서에 존재. 현재 63파일 snapshot-7 동일성 확인 | snapshot-5의 base=a39이므로 이전9커밋의 전체45제품파일을 bounded 리뷰가 새로 감사했다고 표현 불가. 이번 지도로 과거 외부 evidence manifest 전체를 재검증하지 않음 |

## 실제 실행 합계의 의미

- 최신 regression887 = fresh455(architecture385+selected app70) + 동일 입력 재사용432(matching156+trading276). Python13과 opt-in 장애/DR2는 별도다. 반복 합산 금지.
- snapshot-7 앱 최초1373에는 실패13·skip2가 있었다. 수정 클래스83과 임시폴더 영향16의 성공을 조합한 class matrix이지 두 번째 앱 전체 통과 실행이 아니다. remote skip 호출은 이후 별도 실행으로 대체되었고 SavedArchiveThreeCopyReplay는 최종 matrix 미실행이다.
- optimization 당시 전체 API1407/skip3 기록은 SBE 실험 단계 기록이다. 최종 Protobuf 전체 앱 fresh 성공 수치로 바꾸지 않는다.
- 최신 actual HA10+remote6 조건은 모두 true. final rebuild의 새 실행 식별자로 새 실행했다. 성능 runtime과 최종 runtime 전체 해시가 동일한 것은 아니며 debug bytecode 차이의 의미 동등성 감사가 따로 있다.

## 최소 후속 권고

1. 게시 직전에 대상 commit/index가 위 595 소스와 같은지 다시 대조하고 저장소 필수 CI/공백·형식 검사를 확인한다. 변경이 없으면 전체 앱·HA를 무조건 다시 실행할 증거상 이유는 찾지 못했다.
2. fresh 현재 HTTP 근거가 필요하면 기존 `BoundedTradingOrderHttpTest`, `RemoteProtectedTradingHttpTest`, `RecoveryDurabilityGateTest`, `BoundedExecutionIdentityTest`, `BoundedInitialClusterConnectTest`만 선택 실행하면 된다. 동일성이 확인된 과거 성공을 재사용한다면 필수 재실행으로 주장하지 않는다.
3. 실제 production bounded main/config boot와 HTTP→Aeron→backup 전체 연결은 별도 미검증으로 PR에 명시한다. 기존 선택 테스트 재실행만으로 이 공백이 해소되지는 않는다. 이 게시 준비 점검 때문에 새 설계·리팩터링을 요구하지 않는다.
4. 기존9커밋까지 포함한 PR 전체의 새 독립 결함 검토가 요구되면 그 검토 범위는 origin integration→게시 head여야 한다. snapshot5→7 및 후속 A 검토를 전체 새 승인으로 대체하지 않는다.

## 핵심 원본

- engineering/aeron-trading-ha-code-design.md §17
- engineering/results/ha-improvement-20261030/validation-matrix.json, full-app-initial.json, affected-app-retest.json, independent-verification.json
- engineering/results/optimization-sbe-20261030/final-domain-review.md, final-wire-review.md, protobuf-only/independent-review.md
- engineering/results/regression-fix-20261030/code-independent-review.md
- engineering/results/service-progress-fix-20261031/independent-review.md
- engineering/results/candidate-validation-20261031/independent-review.md
- build/candidate-validation-20261031/final-source-manifest.json, completion-summary.json, final-regression-summary.json, final-ha-xml/, final-verification-audit.json
