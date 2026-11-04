# #133 · 취소 접수부터 잔여 반환까지 구현·검증 기록

**2026-11-04 · 로컬 구현·범위 내 검증 완료.** 기준 통합 `8407b2cb24285add519d00ae27d5d2d80c2feb99`, 작업 브랜치 `feat/split-order-cancellation/133`, 실제 폴더 `/Users/0chord/.codex/worktrees/issue148-http-aeron/exchange-core`다. [명세 원본](split-order-cancellation-spec.md)의 C01~C26과 실행 결과를 구분한다. PR·원격 CI·병합·이슈/보드 변경은 수행하지 않았다.

## 1. 바뀐 흐름부터 읽기

기존 신규 주문 왕복 경로에 취소를 추가했다. 자금과 매칭은 각각3개의 JVM으로 동작하며 현재 리더 안의 기존 전송 작업을 그대로 사용한다. 새 전달 서버는 없다.

1. **자금의 `cancel()`**: 토큰 종류·내용·대상·예산을 확인하고 최초 응답, 취소 표식, 전달 의무를 기록한다. 여기서 돈은 움직이지 않는다.
2. **주문 전달 작업 → 매칭의 `apply()`**: 신규와 취소를 같은 마켓 입력 순서로 처리한다. 취소 후보를 먼저 계산하고, 전체 결과를 보관할 공간을 검사한 뒤 주문장에서 삭제한다.
3. **결과 전달 작업 → 자금의 `applyMatchingResult()`**: 앞선 결과 다음인지 확인한다. 취소 성공이면 현재 예약 잔액만 반환하고 취소 거절이면 반환 없이 진행한다. 성공 반환과 결과 순번을 함께 반영한다.
4. **정산 ACK → 매칭**: 자기 로그에 ACK를 기록한 뒤 결과 payload를 회수한다. 전송 성공·timeout·사용자 receipt ACK는 이 완료 근거가 아니다.

수수료0의 BUY3@100을300 예약하고1개를100에 체결했다면 취소 완료 후 quote는 **사용 가능900/예약0**, base는1이다. 체결에서 이미100을 썼기 때문이다. 취소를 받았다는 접수 응답은 실제 취소·자금 반환 완료와 다르다.

## 2. 코드는 네 입구부터

| 입구 | 파일·핵심 메서드 | 판단할 내용 |
| --- | --- | --- |
| 취소 접수 | `domain-trading/.../BoundedFundingState.kt` · `cancel` | 재전송/내용 충돌/미완료 취소·활성 대상/유한 예산, 돈 무변경 |
| 매칭 확정 | `domain-matching/.../MatchingEngine.kt` · `prepareCancel`, `validate`, `apply`; `ReservedMatchingState.apply` | 계산만으로 주문을 지우지 않음. 공간 확보 후 결과·주문장·prefix 함께 반영 |
| 자금 반환 | `BoundedFundingState.applyCancellationResult`; `FundsResultSettlement.kt` | 앞선 결과 뒤 현재 잔여만 반환, 전량 체결/최종 거절 뒤 취소 거절은 추가0 |
| 실제 연결·복원 | `SplitTradingClusterAdapter`, 기존 두 Agent와 `SplitOrderDeliveryClient`; 두 역할 snapshot codec | 별도 네트워크 왕복·ACK·재시작에서도 같은 의무로 재개 |

`FundsCancellation.kt`는 취소 요청·최초 접수 결과·미완료 의무 값이다. `FundsDispatch`의 기존 envelope에 nullable 취소 태그를 추가해 신규/취소를 구분한다. 요청 종류는 같은 inbox 채널의 공통 토큰 공간에서 대조한다. 취소 전용 무제한 요청 이력은 없다.

새 split wire/domain 형식은2, Funds snapshot은3, Matching snapshot은2다. 기존 INTEGRATED codec/기록 fixture는 유지하고 구 split 실행물·기록은 새 형식으로 자동 복원하지 않는다. 통합 경로를 분리 클러스터의 일반 HTTP 취소 API로 바꾸지 않았다.

## 3. 순번을 분리한 이유

신규 승인1 → 취소 입력2 → 신규 승인2/입력3이 가능하다. 취소는 신규 주문·예약 수를 늘리지 않지만 매칭 입력과 전체 결과의 순번을 하나 사용한다.

- `admissionSequence`와 `marketAdmissionSequence`: 신규 승인만 센다.
- `dispatchSequence/resultSequence`: 신규와 취소를 함께 센다.
- `engineSequence`: 주문장 사건 순서다. 취소 성공·거절은 사건1개다.
- 금융 `executedThrough`: 정산한 신규 승인 순서다. 별도 `fundsSettledThrough`는 취소까지 반영한 결과 순서다.

snapshot에는 이 수를 별도로 보존한다. 미정산 신규 원본의 입력 순번과 미완료 취소 입력 순번의 합집합이 `(resultThrough, issued]`를 빈칸 없이 덮는지 검사한다. 최초 응답과 전달 payload를 모두 회수한 뒤 취소 표식만 누락돼도 복원을 거절한다.

## 4. 실패해도 돈이 다시 움직이지 않는 경계

| 상황 | 코드에서 남기는 상태 |
| --- | --- |
| 취소 접수/전달 ACK만 확인 | 잔여 예약 유지. 업무 의무와 전달 자료 수명을 분리 |
| 취소 결과가 앞선 체결보다 먼저 도착 | GAP, 금융·prefix 무변경 |
| 먼저 전량 체결 또는 신규 NOT_EXECUTED | 앞선 정산/반환 뒤 취소 거절을 추가 반환0으로 완료. 원래 live가 없어도 의무 유지 |
| 동일 취소 요청/결과 재전송 | 유한 inbox·비교 원문에서 대조, 회수 뒤 RETIRED/PRUNED. 재실행 없음 |
| ACTIVE 예약인데 대상 없음 취소 거절 | 금융 상태 모순으로 owner 차단, 성공 정산 ACK 없음 |
| 일시 결과 공간 부족 | BUSY, 주문 삭제·입력 소비 없음. 정산 ACK로 공간 회수 후 재시도 |
| 사용자 receipt ACK/epoch 변경 | 최초 응답만 회수. 미완료 취소·필요 원본은 유지 |

가격 차액 반환은 SETTLEMENT이고, 취소 반환은 그 뒤 남은 예약에 대한 RELEASE다. BUY3@100 중1개가80에 체결되면 먼저20을 돌려주고, 취소에서200만 반환해 최종920/0이다. 수수료 잔여도 예약액 안에 이미 포함되므로 두 번 더하지 않는다.

## 5. 테스트를 읽는 순서

아래 파일은 모두 테스트/도구다. 제품의 전송 제어 장치가 아니다.

| 테스트 | Given → When → 실제 Then |
| --- | --- |
| `PreparedCancellationTest` | 대기 주문 → 후보만 준비 → 주문장 불변; 마켓 전진 뒤 stale 후보 → 적용 거절 |
| `ReservedMatchingCancellationTest` | 신규/체결/취소 순서와 유한 공간 → matching apply/restore → 정확한 잔량 삭제·거절·전체 결과·후속 체결 금지·허위 snapshot 거절 |
| `FundsCancellationTest` | 개시 자금·원래 승인·취소 → 결과 순차 적용/재전송/복원 → 수기 잔고900/920/91,920, RELEASE 두 분개, 수수료·나머지·prefix·의무 연결 검증 |
| 두 split codec·역할 snapshot 테스트 | 최대 문자열/정수·취소 원본·혼합 inbox → encode/decode/restore → 값 일치·상한+1/미지 형식·구버전 거절 |
| `DockerMatchingResultDeliveryTest` 취소4사례 | 독립 자금3+매칭3 실제 Aeron·sync2/2/2 → 실제 단절/스냅샷과 로그 재시작/중복 → 역할별 세 사본의 금융·주문장·prefix·회수 일치 |

원장 분개는 순수 테스트에서 RELEASE 종류·동일 자산·HOLD DEBIT/AVAILABLE CREDIT·정확히 두 항목·반환액을 직접 대조한다. Docker는 현재 잔고·예약·수수료·사건 수·처리 순서와 각 사본을 관찰한다. 전체 원장 이력을 보관·RDB 적재했다고 표현하지 않는다.

## 6. 수용 조건 판정

`implementation-verifier` 기준으로 실제 변경·검증문·원본 XML·Docker run·고정 소스·독립 리뷰를 대조했다. 아래 완료는 #133 명세 범위이며, 전체 제품 운영 준비·HTTP 연결·원격 CI·병합 완료를 뜻하지 않는다.

| 조건 | 주 검사 | 현재 판정 |
| --- | --- | --- |
| C01~C07 순서·중복·취소 경합 | 금융·매칭 순수 테스트, 실제 취소4사례 | 충족 · 실제3+3와 해당 순수 검사 완료 |
| C08~C11 거절·gap·충돌·회수 | typed inbox/금융 결과 테스트 | 충족 · 해당 순수/바이트 경계 검사 완료 |
| C12~C15 가격차·수수료·SELL | 독립 금액 및 두 분개 직접 대조 | 충족 · 해당 순수/바이트 경계 검사 완료 |
| C16~C19 후보·한도·최대 frame | 엔진/매칭·codec·기동 검사 | 충족 · 해당 순수/바이트 경계 검사 완료 |
| C20~C22 서로 다른 회수/ACK 수명 | 금융·역할 snapshot·실제 통신 | 충족 · 실제3+3와 해당 순수 검사 완료 |
| C23~C26 기본 복원·모순·6사본 | owner/codec·실제 snapshot+tail | 충족 · 실제3+3와 해당 순수 검사 완료 |

## 7. 실제 실행 근거

최종 실행 후 원본 XML·로그·소스 manifest·Docker 실행 결과를 `build/issue133-verification/` 아래 보존한다. 이전 실행과 재실행, UP-TO-DATE 재사용, 중첩된 선택 테스트는 합산하지 않는다. 실패는 제품 결함·시험 준비/형식 이행·환경 문제로 구분해 수정과 재실행 근거를 남긴다.

## 8. 독립 검토와 남은 범위

작성 대화를 넘기지 않은 `fork_turns=none`의 별도 리뷰어 두 명이 고정 base/head snapshot에서 금융·매칭과 wire·복원 연결을 나누어 검토했다. 최초 전체 검토와 이후 수정 검토를 합친 최종 미해결 확인 결함은0개다. 구현에 참여한 에이전트의 교차검토는 이 독립 리뷰와 구분한다.

- 도메인 리뷰: v1에서 정식 Gradle25개와 독립 소규모 반례를 직접 실행했다. 종료된 대상의 취소 원본을 보관 중인 최초 승인/정산 원본과 대조하지 않는 P2를 재현했다. v2에서는 별도 도메인 소스 컴파일과 반례 재실행으로 최초 승인 보존/회수 두 경로의 거절, 원래 snapshot의 정상 진행, terminalStatus 모순 거절을 확인했다. v2 취소 테스트19개 직접 호출도 통과했으며 정식 JUnit discovery/XML 실행과 구분한다.
- wire 리뷰: v1에서 codec/두 역할 snapshot/Adapter/INTEGRATED golden64개를 직접 실행했다. v2의 새 Adapter 시험 모드 설정과 실제 ACK tail 검출력을 지적했다. v3에서 `results=true`/미지원 모드 대조, 각 복원 직전 `orders/results/result-records` 송신 hold를 검토해 재전송에 의한 거짓 통과를 차단했음을 확인했다. 이후 직접 Gradle/Docker 실행은 하지 않았고 root의 최종 실행과 구분한다.
- 고정 대상: `/Users/0chord/.codex/visualizations/2026/09/23/01a0ce63-9327-7f32-a7e0-3429380284d8/issue133-implementation/review-v1`, `review-v2`, `review-v3`의 base/head·manifest·보고서다. 최종 v3 manifest SHA256은 `b63cc8abcc2141e7e043624bddc3b92e8973f32e8c314ff1c0df3436c7c9d84d`다. 최종 재실행 직후40개 파일이 일치했고, 이 구현 기록의 최종 수치/판정 갱신은 그 이후의 문서 변경이다. 제품·테스트 소스는 변경하지 않았다.

이번은 고정 개발망·프로세스 신뢰 전제다. HTTP 취소/인증·인가·다중 matching 담당 이동·Kafka/RDB 원장·성능 SLO·리더/과반 전수·서로 다른 복원 위치/단절이 겹치는 #134 조합 장애는 포함하지 않는다. 기본 취소 재시작 검증을 #134로 미루지는 않는다.


## 9. 최종 실행과 근거 위치

| 대상 | 최종 결과 | 실행 수준 |
| --- | --- | --- |
| domain-matching | 166개 · 실패/오류/skip0 | 전체 회귀 성공 결과. 최종 명령에서는 해당 소스가 같아 UP-TO-DATE 재사용 |
| domain-trading | 429개 · 실패/오류/skip0 | 전체 회귀 직접 실행. 새 취소 시험19개 포함 |
| app-api 관련 선택 | 131개 · 실패/오류/skip0 | codec·역할 snapshot·Adapter·기동·두 Agent·INTEGRATED golden의 직접 실행 |
| 실제 Docker | 11개 시나리오 성공 + 강화 C01 한 번 재실행 성공 | 주문 전달1·기존 결과6·취소4. 총12회 실행이지12개 독립 시나리오는 아님 |
| architecture | 386개 · 실패/오류/skip0 | 전체 직접 실행, `verifyArchitectureReport`에서 P01~P09 실행·성공 확인 |
| ktlintCheck | 성공 | 전체 모듈 형식 검사 |

최종 app-api XML의132개는 선택 단위131개와 강화 C01 실제Docker1개를 함께 수집한 결과다. 이를 위 Docker 실행에 다시 더하지 않는다. 독립 리뷰의25/64/19개도 이 검사와 겹치므로 합산하지 않는다.

실제 명령은 모두 다음 폴더에서 실행했다.

```bash
cd /Users/0chord/.codex/worktrees/issue148-http-aeron/exchange-core
./gradlew ktlintFormat :domain-matching:test :domain-trading:test
./gradlew :app-api:test --tests '*DockerMatchingResultDeliveryTest' --tests '*DockerFundsOrderDeliveryTest' :architecture-tests:test verifyArchitectureReport ktlintCheck
./gradlew ktlintFormat :domain-matching:test :domain-trading:test :app-api:test --tests '*Split*' --tests '*FundsRoleSnapshotCodecTest' --tests '*MatchingRoleSnapshotCodecTest' --tests '*FundsOrderDeliveryAgentTest' --tests '*MatchingResultDeliveryAgentTest' --tests '*BoundedTradingRoleGoldenTest' --tests '*DockerMatchingResultDeliveryTest.C01*' ktlintCheck
```

최초 반복에서의 실패와 수정:

- 최대 거절 사건 문자열 `order not found`의 ASCII 길이를14로 계산한 테스트 오류를15로 바로잡았다. 길이 필드를 포함한 독립 산술을 주석에 남겼고 제품 charge는 바꾸지 않았다.
- 기존 Long.MAX_VALUE snapshot fixture가 새 신규/결과 counter와 승인 원본을 복제하지 않아 실패했다. 신규 원본·settled/retired 신규 counter까지 일관되게 이행하고 최대 순번의 원래 기대 동작을 유지했다.
- split v1 설정 지문 시험은 구 writer 지문을 그대로 보존하면서 v2 기대값을 별도 고정 writer로 계산하도록 이행했다. 실제 해시를 정답으로 복사하지 않았다.
- 생성 메시지 이름 `SplitCancelRequest`가 기존 HTTP Request 위치 규칙에 걸렸다. 순수 wire 입력인 `SplitCancelInput`으로 바꿨으며 구조 검사를 제외하지 않았다.
- 독립 리뷰의 종료 원본 대조 누락은 보관 중인 admission/settlement 원문과 terminal 근거를 색인해 검증하도록 수정했다. 전체 완료 이력을 새로 누적하지 않는다. 정상 복원은 계속 허용한다.
- 실제 취소 tail 관측은 스냅샷 뒤 최초 취소·반환·ACK를 기록한 뒤, 복원 중 재송신을 잠가 누락된 tail이 재전송으로 메워지는 거짓 통과를 차단했다.

원본 근거:

- `build/issue133-verification/final/`: 최종 모듈별 XML·counts.json·명령별 실행 로그·강화 tail 원본 JSON·12개 source hash 대조·고정 리뷰 소스 대조.
- `build/issue133-verification/docker-v2/`: 실제11개 시나리오 XML·runs.json. 이후 변경은 C01의 재송신 차단과 별도 Adapter 단위시험 구성뿐이며 제품 코드는 같다. C01만 변경 영향을 받아 추가 실행했다.
- `build/issue133-verification/architecture/`: 구조386개 원본 XML·counts.json.
- `build/issue133-verification/wire-snapshot/`: 구현 중 선택47개 실행 근거. 최종 선택131개와 중첩된다.
- 실제 JSON의 `baseCommit`은 재사용한 runner의 기존 #168/#169 기준을 보존한다. 이번 작업의 기준은8407b2c이며 `headCommit`, frozen image/manifest, 실행별 명시12개 제품/도구 소스 지문과 독립 리뷰의 전체 변경 manifest를 함께 대조한다. 그 옛 `baseCommit` 필드를 이번 변경의 기준으로 해석하지 않는다.

## 10. 원래 불변식을 새 경계에 연결한 확인

| 원래 규칙·출처 | 새 경계의 적용 | 정상·위반 근거 |
| --- | --- | --- |
| `OrderReservation.release` · ACTIVE만 해제, 실제 잔여 금액 보존·수량/수수료 나머지 유지 | 자금 취소 결과에서 현재 예약을 사용. 가격차 반환을 중복하지 않음 | C01/C12~C15 · 잔고/두 분개/수수료/나머지 직접 대조 |
| 최초 승인 가격·수량 단위·예약 수학 관계 | typed 취소는 금액 입력 없이 기존 승인 원본을 전달, `MatchingInputValidation` 재사용 | 최대 원본·잘못된 requester/identity/잔량·retained origin 변조 거절 |
| 요청 토큰의 창·세대·내용 충돌·ACK | 같은 채널에서 신규/취소를 구분하고 합산한도·회수 순서를 사용 | C04/C08/C11/C18/C20 · 잘못된 종류·포화·회수 뒤 재전송 |
| 엔진 사건 순서·완전한 결과 | 취소 후보 계산 후 공간 검사·적용, 성공/거절 한 사건과 정확한 원본 | C06/C07/C16/C19/C25 · stale/허위 거절/최대 한도 |
| 복원 금융 보존·연속 prefix | 신규 승인과 공통 입력/결과 순번을 분리하고 미완료 의무 suffix를 검증 | C23~C26 · 실제3+3 재시작, 표식·원본·counter 누락 반례 |

최종 확인은 로컬 구현·명세 범위 내 검증이다. 운영 인증·HTTP 취소·전체 원장 적재·성능 측정·#134 복합 장애 완료까지 확장하지 않는다. 읽기 설명을 작성한 사실을 사용자의 코드 이해 완료나 PR 게시 승인으로 기록하지 않는다.


### 실제 실행 식별자

| run | 사례 | 결과 | 기록 볼륨·실행 자원 정리 |
| --- | --- | --- | --- |
| `delivery1793790215445eb6cd598` | cancel-before-delivery | 성공 | True / True |
| `delivery1793789948548939a0676` | cancel-partial | 성공 | True / True |
| `delivery17937902911720d213894` | large | 성공 | True / True |
| `delivery179379026443920910722` | cancel-filled | 성공 | True / True |
| `delivery179379003703093e3e5ff` | baseline | 성공 | True / True |
| `delivery179379015283270acdc06` | cancel-active | 성공 | True / True |
| `delivery1793790181857108e2dbf` | fee | 성공 | True / True |
| `delivery179378990547477f102b5` | cap | 성공 | True / True |
| `delivery1793789926197722d71c1` | active | 성공 | True / True |
| `delivery1793790128585838a3bab` | partial | 성공 | True / True |
| `delivery1793789786615058ad45a` | 기존 주문 전달 | 성공 | True / True |
| `delivery1793790805694314e23b1` | 강화 cancel-partial · 재송신 차단 뒤 최초 tail 복원 | 성공 | True / True |

모든 실제 실행은 독립 자금3+매칭3·sync2/2/2이고 자기 기록·컨테이너를 정리했다. 최종 추가 실행의 명시12개 소스 지문은 현재 파일과 모두 일치한다. 관련 컨테이너는 남아 있지 않으며 확인 시 호스트 여유 공간은 약75GiB다. 다른 작업의 이미지·볼륨과 전역 Docker 캐시는 삭제하지 않았다.
