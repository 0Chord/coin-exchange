# #167 · 매칭 전체 결과의 순차 정산과 최종 거절 예약 반환

작성: 2026-11-02 · **구현 전에 확정한 상세 명세**다. 현재 로컬 구현·실행·리뷰 결과는 [구현 기록](funds-result-settlement-review.md)에서 별도로 확인한다. 기준은 통합 브랜치 `feature/phase-2/integration`의 `05e28a91a77a1772451bbf6ee8a6e1d308e17054`(#172 병합)다. 이 문서가 #167의 명세 원본이며, #130 설계 20절·읽기 페이지·원격 #167 본문은 이 원본에 연결하는 안내 또는 파생본이다. 별도 설계 문서 PR은 만들지 않는다.

관련: [#167](https://github.com/0Chord/coin-exchange/issues/167) · 상위 [#132](https://github.com/0Chord/coin-exchange/issues/132) · 설계 [#130](https://github.com/0Chord/coin-exchange/issues/130) · 선행 #165·#166 · 후속 #168·#169.

## 1. 이번 PR이 만드는 한 결과

**매칭이 확정한 한 주문의 전체 결과를, 자금 전용 상태가 순서대로 한 번만 금융 반영한다.** 체결된 돈·자산·수수료를 정산하고, 무체결 대기는 예약을 유지하며, 실행 전 최종 거절은 예약을 한 번 반환한다. 반영한 결과까지의 순번을 보존하므로 재전송·정산 확인 유실·복원 뒤에도 효과를 반복하지 않는다.

현재 #166에는 매칭 입력 적용·주문장 변경·전체 결과 보관이 있다. 자금에는 예약·최초 승인 원본·미전달 주문 보관이 있지만, 새 전체 결과를 받아 정산하는 경로는 없다. 기존 통합 정산 계산기는 재사용한다. 이번 PR은 이 자금 상태 코어와 기본 바이트 복원을 완성하며, 실제 클러스터 간 통신은 아직 시작하지 않는다.

- 포함: 결과의 순서·구조·로컬 승인/현재 예약 대조, 복수 체결 계산, 잔고·예약·수수료·분개 후보 검증, 금융 적용·정산 prefix·정산 확인, 최종 거절 반환, 제한된 중복 비교 자료와 회수, 전용 snapshot 복원.
- 유지: 기존 INTEGRATED 실행·HTTP 경로, 사용자 요청 토큰/최초 접수 응답, 자산별 총량 상한, 고정 마켓 배치와 정책, FUNDS/MATCHING 단독 실행 차단.
- 제외: 새 Aeron 명령·송수신·발신자 인증·실제 Archive replay·6노드 연결(#168~169), 취소(#133), 담당 이동/새 epoch cutover, 복합 장애(#134)·리더/과반 장애(#136~138), 성능 측정, Kafka/RocksDB/RDB 원장 적재·완료 이력 전체 조회.

## 2. 두 확인이 뜻하는 것 — 이번 설명의 첫 흐름

수수료 0으로 구매자 quote 1,000, 판매자 base 1, 가격 100×1을 예로 든다. 아래 값은 수용 기준이며 아직 새 실행 결과가 아니다.

1. 자금이 구매 주문에 100을 예약한다. 구매자 quote는 **가용900·예약100**이다.
2. 매칭이 주문을 적용했다고 확인한다(`FundsMatchingAppliedAck`). 자금은 주문 전달 payload를 회수할 수 있다. **가용900·예약100은 그대로**이며, 이 확인만으로 체결 정산을 끝냈다고 말하지 않는다.
3. 매칭이 보관한 완결 결과를 자금이 검증·정산한다. 구매자 quote **가용900·예약0**, base1, 판매자 quote100/base0이 된다. 이 결과까지의 정산 prefix를 함께 반영한 뒤 `MatchingFundsSettledAck`를 만들 수 있다.
4. 미래 #169에서 그 정산 확인을 매칭 로그에 반영한 다음, 매칭이 확인된 전체 결과 payload를 회수한다. 자금 함수가 ACK 값을 반환한 것만으로 원격 로그 기록이 끝났다고 말하지 않는다.

**합의한 역도착 처리:** 2의 적용 확인보다 3의 결과가 먼저 와도 정산을 기다리게 하지 않는다. 정확한 다음 전체 결과를 검증·정산한 경우, 그 결과를 해당 입력까지 매칭이 적용했다는 증거로 함께 사용한다. 금융 효과·정산 prefix·필요한 주문 전달 prefix 전진/회수를 같은 자금 상태 변경에 넣는다. 남은 예약은 별도 수명으로 유지한다. 검증·계산 실패 때에는 전달 자료도 회수하지 않는다. 뒤늦은 유효 적용 ACK는 중복 no-op이다.

```text
자금 예약·전달 기록 (#165)
  → 매칭 적용·전체 결과 확정 (#166)
  → [이번 #167] 출처/순서 → 전체 결과/로컬 예약 → 계산 후보 → 함께 금융 반영
  → 누적 정산 확인 값
  → [후속 #169] 매칭 로그에 확인 반영 → 매칭 결과 payload 회수
```

이 화살표 전체는 하나의 트랜잭션이 아니다. 이번 PR의 쓰기 경계는 한 자금 소유자의 메모리 적용이며, 두 클러스터의 확정은 이후 각각 별도로 연결한다.

## 3. 최신 코드에서 재사용할 부분과 바꿀 부분

모든 경로는 위 기준 커밋의 저장소 상대 경로다. 제안 파일/API 이름은 구현 시 조정할 수 있다.

| 현재 코드·심벌 | 확인한 사실 | 이번 책임 |
| --- | --- | --- |
| `domain-trading/.../BoundedFundingState.kt` · `executionInput`, `applyExecution` | 통합 실행만 허용하며 `dispatch == null` 검사 | 검사 유지. 분리 자금용 `applyMatchingResult(result, appliedAt)` 같은 새 입구를 추가 |
| 같은 파일 · `participantBalances`, `prepareFeeRevenue`, `restoreFundsRecoveryState` | 순수 계산 지원/금융 복원 있음. 자금 전용 복원은 아직 executedThrough0·전부 PENDING만 허용 | 현재 예약 조회·수수료 대조 재사용, 새 결과 수명에 맞게 복원 확장 |
| `domain-trading/.../OrderSettlementCalculator.kt:43` | 여러 체결의 작업용 잔고·예약을 순서대로 갱신하고 같은 사용자 효과 합산 | 현재 예약·잔고를 넣어 재사용. 정산 규칙을 복제하지 않음 |
| `domain-trading/.../MatchingIntakeValues.kt` · `MatchingResultEnvelope` | 전체 사건·주문 효과·최초 승인 원본, 결과 순번과 사건 범위를 보유 | #166 형식 유지. 자금 수신 값으로 사용 |
| `domain-trading/.../MatchingInputValidation.kt` · `requireResult` | 전체 묶음의 구조·수량 관계를 검사하지만 매칭 설정 타입에 결합 | 고정 context/수신 상한으로 공유할 순수 검사를 필요한 만큼 추출. 자금에 주문장/MatchingEngine을 만들지 않음 |
| `domain-trading/.../FundsDispatch.kt` | 전달 ACK는 payload만 회수. 활성 예산은 아직 정산 종료 경로가 없음 | 완료 maker/taker·최종 거절의 활성 예산 회수, 새 진행 관계 복원 |
| `domain-trading/.../FundsRoleRecoveryState.kt`, `app-api/.../history/FundsRoleSnapshotCodec.kt` | 자금 전용 snapshot-v1에는 결과 수신 prefix/receipt가 없음 | 자금 전용 v2와 제한된 v1 읽기 호환, 빈 임시 owner 검증 후 공개 |

도메인 코드는 Spring·Protobuf·Aeron·파일 I/O에 의존하지 않는다. snapshot의 bytes/길이/체크섬은 app-api의 기존 history 경계에서 담당한다. 역할 조립은 설정만 고정하며 노드·Driver·Archive를 시작하지 않는다. 한국어 주석과 기존 구조 규칙을 유지한다.

## 4. 고정 설정·상태·공개 입구

조립 시 전체 funds manifest·배치·lineage·마켓 ordinal·ownershipEpoch·configurationVersion·정확한 마켓/수수료 context를 고정한다. 이번에는 담당 이동을 실행하지 않으므로 현재 배치와 다른 세대의 결과/ACK를 거절한다. 같은 버전 숫자만으로 다른 내용을 허용하지 않는다.

신규 결과 수신/receipt 한도는 명시적인 불변 설정이다. 최소 필드는 단일 결과 최대 events/논리 bytes, 마켓별·공통 receipt 최대 건수/논리 bytes다. 수치는 구현자가 기존 기본 규모에 맞춰 제안하고 작은 한도로 경계를 검증한다. 모든 허용 producer 결과를 consumer가 받을 수 있도록 수신 상한과 receipt 최소 공간의 호환을 조립/연결에서 확인한다. 수신자 한도가 더 작아 정상 확정 결과를 영구히 막는 구성을 허용하지 않는다. 전체 snapshot 상한도 계산해 고정 상한 밖이면 기동 전에 설정을 거절한다.

각 마켓 stream에서 다음 값을 유한하게 보유한다.

- 적용한 연속 `resultThrough`, 그 경계의 `settledEngineSequence`.
- 비교 원문을 회수한 `retiredThrough`와 그 직후부터 `resultThrough`까지의 0개 이상 연속 receipt suffix.
- receipt에는 전체 불변 결과 원문과 **최초 자금 적용 시각**을 보관한다. 같은 순번의 내용 비교는 전체 값 기준이며 재전송 시각은 원문 identity가 아니다.
- 기존 pending·resting 활성 주문, 현재 예약/수수료 나머지와 초기 승인 대조 근거. 전달 payload·활성 원본·중복 receipt의 예산과 수명은 구분한다.

제안하는 입구는 결과 적용과 비소비 누적 정산 ACK 조회다. 이름·반환 클래스는 제안이며 의미는 아래 표를 따른다. mutable Map을 외부에 노출하지 않고 단일 소유 스레드에서 호출한다. locks/별도 제품 관찰 큐를 추가해 스레드 안전을 가장하지 않는다.

| 결과 분류 | 의미·허용 변화 |
| --- | --- |
| APPLIED | 정확한 다음 전체 결과를 금융 반영한 뒤 누적 정산 ACK 제공 |
| DUPLICATE | 보존 중 같은 원문. 금융·receipt·최초 시각을 바꾸지 않고 현재 누적 ACK 제공 |
| PRUNED | 원문 회수한 과거. 다시 적용하지 않고 유효 stream의 현재 누적 ACK만 제공. 내용 일치를 증명했다고 말하지 않음 |
| CONTENT_CONFLICT | 보존 중 같은 identity의 다른 원문. 효과·성공 ACK 없음 |
| GAP | 미래 결과. expectedSequence를 알려주고 금융·확인·보존 상태 무변경 |
| INVALID_STREAM / INVALID_RESULT | 잘못된 고정 식별/정책 또는 구조·로컬 승인·범위 모순. 효과·성공 ACK 없음 |

한도 밖 입력은 순회·할당 전에 가능한 길이/건수부터 제한한다. 변조 원문을 정상 최종 거절로 변환하지 않는다. 현재 owner가 이미 차단됐거나 복원되지 않았으면 정상 적용·ACK 조회를 제공하지 않는다.

## 5. 수신부터 반영까지의 전체 순서

### 5.1 식별·중복·순서부터 판정

1. 사용 가능한 자금 전용 owner인지, 입력 format과 고정 출발 매칭/목적 자금·마켓·방향·epoch·버전·context가 맞는지 검사한다. 잘못된 stream은 과거 순번이어도 ACK하지 않는다.
2. 양수 결과 순번과 허용 크기를 확인한다. `resultSequence <= resultThrough`이면 신규 활성 예약을 찾기 **전에** receipt 경로로 분류한다. 이미 종료해 제거한 주문을 다시 요구하지 않는다.
3. 보존 창 안은 전체 원문을 비교해 DUPLICATE/CONTENT_CONFLICT, 회수된 과거는 PRUNED다. 창 밖 원문 동일성·과거 상세 조회를 약속하지 않는다.
4. 신규는 정확히 다음 순번만 허용한다. 앞 결과 누락이면 GAP이고 미래 결과를 저장하는 무제한 재정렬 Map을 만들지 않는다.
5. v1 SUBMIT 계약은 `resultSequence == input.dispatchSequence == marketAdmissionSequence`다. 글로벌 admissionSequence는 여러 마켓 접수를 섞으므로 이 값과 다를 수 있다. engineSequence는 주문장 진입도 포함해 별도다. 앞 결과 적용 후의 engine 경계가 이번 `engineSequenceBefore`와 같아야 한다.

### 5.2 전체 묶음과 로컬 자금 승인 대조

- taker는 해당 마켓의 첫 PENDING 원본과 정확히 일치한다. 자금이 승인하지 않은 주문·다른 정책·뒤 순번 주문을 성공으로 받지 않는다.
- maker는 앞선 결과를 처리해 RESTING이 된 같은 마켓의 활성 주문이어야 한다. 각 maker ID는 결과 안에서 유일하며 taker와 다른 주문이다. 사용자 ID가 같을 수는 있다.
- `effect.origin`은 **최초 승인 원본**이다. 초기 order/identity/context/예약 근거를 로컬 승인과 대조하되, 부분 체결된 현재 예약을 초기 예약으로 덮어쓰지 않는다. `beforeQuantity`는 로컬 현재 `remainingQuantity`와 같아야 한다. 수수료 나머지는 현재 예약의 값을 사용한다.
- 각 체결에 maker 효과 하나가 대응하고, 별도 taker 효과 하나가 전체 체결 합·마지막 잔량 진입과 일치해야 한다. 즉 효과 수는 체결 수+1이며 전체 사건 수와 항상 같은 것은 아니다. 양의 수량·마켓 단위·가격 조건·maker 가격·방향·사용자·범위를 검사한다. 누락된 효과, 중복 maker, 추가 효과, 잘린 사건·잔량, 취소/알 수 없는 사건은 거절한다. 포함된 사건 순서·동일 가격의 FIFO 근거도 기존 계약대로 검사한다.
- 자금은 주문장을 보유하지 않는다. 묶음 안 구조와 로컬 금융 관계를 검증하며, 보이지 않는 더 좋은 maker의 존재나 상대의 실제 용량 초과를 독립 증명한다고 주장하지 않는다. 매칭 결과의 생성 정확성은 #166, 실제 출처·확정 로그 신뢰는 #169에서 연결한다.

### 5.3 결과 종류와 금융 후보

| 결과 | 필수 모양 | 자금 후보 |
| --- | --- | --- |
| MATCHED·무체결 대기 | 체결0, 잔량 진입 사건1, RESTING 효과. engine은 사건1만큼 진행 | 잔고·예약 불변. PENDING→RESTING과 결과 진행 |
| MATCHED·하나/여러 체결 | reason 없음. 연속 사건과 모든 maker/taker 효과, 필요 시 마지막 잔량 진입 | 기존 계산기로 현재 예약·잔고에서 체결 순서대로 정산. 후보의 afterQuantity/종료와 결과 효과 대조 |
| NOT_EXECUTED | 허용 reason(ACTIVE_ORDER_LIMIT/RESULT_TOO_LARGE), 사건0, taker 효과1, 전후 수량 동일·NOT_EXECUTED, engine 전후 동일 | 최초 PENDING 신규 주문의 남은 예약만 전액 반환. 체결·수수료 효과0. 결과 순번만 진행 |

`OrderSettlementCalculator.calculate()`를 현재 예약·잔고와 안정된 `SettlementExecution` 목록에 한 번 적용한다. 첫 체결의 작업용 결과를 다음 체결이 이어받고, 같은 사용자·자산의 잔고 효과는 합치며 주문별 수수료 나머지는 분리한다. 참가하지 않은 주문의 hold는 보존한다. 후보를 다 계산하기 전 실제 Map을 바꾸지 않는다.

최종 거절은 `calculateEmpty()`로 대신하지 않는다. `Balance.release()`·`OrderReservation.release()`에 해당하는 작은 순수 반환 후보와 HOLD→AVAILABLE 균형 분개를 준비한다. 이 반환은 매칭 실행 전 전체 거절에만 적용한다. 부분 체결 주문을 전액 반환하거나 timeout/BUSY/offer 실패만으로 반환하지 않는다. 최초 HTTP 접수 ACCEPTED receipt는 사후 REJECTED로 덮어쓰지 않는다.

수수료 수익과 분개 수익을 대조하고, 자산별 후보 delta가 보존되며 overflow가 없는지 검사한다. 매 요청마다 전체 계정 Map을 스캔하거나 전체 상태를 복사하지 않는다. 준비 총량과 전체 hold 관계의 전수 검사는 복원/테스트에서 수행한다.

### 5.4 함께 변경하는 경계

모든 검증·계산·후보 예산 준비 후 한 자금 소유자의 변경 경계에서 다음을 반영한다.

1. 후보 잔고·현재 예약·주문별 수수료 나머지·수수료 수익.
2. taker pending 제거, 잔량 RESTING 전환, 종료 maker/taker·최종 거절의 live index 및 활성 건수/bytes 회수.
3. 결과 prefix·engine 경계·금융 executedThrough와 비교 receipt/회수 경계.
4. 합의한 적용 증거에 따라 `matchingAppliedThrough = max(기존 값, 이번 입력 순번)` 및 해당 전달 payload 회수. 이미 더 앞선 명시 적용 ACK를 받았다면 그 값을 낮추지 않는다.

이 경계가 정상 종료한 뒤에만 APPLIED와 정산 ACK를 제공한다. 외부 송신/파일 쓰기는 없다. 신규 입력 공간이 부족해도 이미 확정한 정산과 ACK가 진행해 공간을 회수할 수 있어야 한다.

## 6. 시각·원장 식별자·정합성

`MatchingResultEnvelope`에는 매칭 시각이나 원장 ID가 없다. 이번 적용 입구는 호출자가 공급한 결정적인 `appliedAt`을 받는다. 미래 #169는 확정된 자금 적용 명령의 클러스터 시각에서 공급한다. 계산기 안의 `Instant.now()`/UUID/로컬 난수는 금지한다. 이는 **자금 적용 시각**이며 관측하지 않은 매칭 시각으로 표시하지 않는다. 재전송 시 다른 시각이 와도 재정산하거나 최초 receipt 시각을 바꾸지 않는다.

체결 분개 ID는 기존 `TradingIdentifiers.settlement(fundsLineage, 전역 marketOrdinal, engineSequence)` 규칙을 재사용한다. 마켓 ordinal을 담당 부분집합 순서로 다시 부여하지 않는다. 이번 고정 담당/epoch 범위에서 사건 ID가 충돌하지 않음을 검사한다. 담당 이동은 별도 규격 변경이다.

최종 거절 반환 ID/source ID는 체결과 다른 namespace로, 고정 stream/result identity에서 결정적으로 만든다. 사건0의 거절에 임의 engine 순번을 발급하지 않는다. 분개 계정·금액·양변 균형을 기존 ledger 값으로 검증하되 모든 과거 transaction ID Set을 새로 만들지 않는다.

정상 상태의 불변식은 다음과 같다.

```text
0 <= resultThrough = 금융 executedThrough
   <= matchingAppliedThrough <= issued dispatch 순번

PENDING = 결과가 아직 적용되지 않은 마켓 입력의 연속 suffix
전달 outbox = matchingAppliedThrough 직후부터 issued까지의 연속 suffix
활성 예약 = PENDING + 정산 후 잔량이 남은 RESTING
사용자·자산별 hold = 해당 활성 예약 remainingAmount의 합
자산별 Σ(available + hold) + feeRevenue = 준비 총량 <= Long.MAX_VALUE
```

NOT_EXECUTED도 resultThrough를 전진시키지만 engine은 그대로다. 최초 resultThrough0이면 engine0이다. 준비 총량은 자금 준비에서만 증가하고 거래·반환·수수료 이동에서는 변하지 않는다. 결과·금융·전달 진행은 독립 값이며 위 관계로 결속한다.

## 7. 실패·중복·메모리 수명

계산 전 입력 오류·결과 모순·두 번째 체결 계산 실패는 후보만 폐기한다. 잔고·예약·수수료·pending/live·전달 의무·결과 prefix·receipt 모두 그대로이며 성공 ACK는 없다. 이미 매칭이 확정한 체결을 자금이 새 정상 거절로 바꾸지 않는다. 불변식 오류를 호출자에게 드러내고 앞 결과를 건너뛰지 않는다.

실제 쓰기 중 예상 밖 오류는 일부 변경 가능성이 있으므로 owner를 차단한다. 자동 롤백·새 빈 상태 재개를 보장하지 않는다. 차단 뒤 추가 적용·정상 ACK·사용 가능한 snapshot 공개를 금지하고, 기존 quarantine/incident 규약과 맞춘다. 여기서 모든 정상 클러스터를 함께 종료시키는 기능을 추가하지 않는다.

결과 receipt는 제한된 비교 캐시다. 마켓별 건수/bytes와 공통 건수/bytes를 모두 지킨다. 새 결과를 반영할 때 이미 정산한 오래된 receipt부터 확정적인 순서로 자동 회수한다. 공통 회수가 필요하면 전체 적용 순서를 보존한 FIFO 같은 결정적인 방법을 사용하고, 각 stream에서 회수하는 것은 항상 가장 오래된 prefix다. 복원 뒤에도 같은 회수 순서를 재현할 근거를 snapshot에 담는다. 상세 내부 자료구조는 구현 선택이다.

- receipt 창이 찼다는 이유로 정상 정산/ACK가 영구 BUSY가 되면 안 된다. 단일 허용 결과 한 건은 반드시 보존 가능해야 한다.
- 회수 후에도 resultThrough·retiredThrough·누적 ACK를 유지한다. 창 밖 재전송은 PRUNED이며 새 효과를 만들지 않는다.
- 사용자 응답 ACK/채널 epoch 회전은 사용자 receipt 수명만 바꾼다. 활성 예약·전달 의무·정산 진행을 삭제하지 않는다.
- 결과가 정산돼도 매칭 원본은 상대가 정산 ACK를 자기 로그에 반영할 때까지 남는다. 이번 자금 receipt 회수에 ACK의 ACK를 추가 요구하지 않는다.
- hot path에 JSON 재직렬화·매 결과 전체 상태 해시·전체 Map 복사·중복 수수료 계산을 새로 넣지 않는다. 원본 전체 비교·기존 순수 검사·필요한 후보 계산을 중심으로 한다.

## 8. 전용 snapshot과 기본 복원

자금 전용 **snapshot-v2**를 쓴다. 기존 자금 v1에 필드를 몰래 덧붙이지 않는다. 통합 snapshot-v2·manifest-v1·매칭 snapshot-v1은 변경하지 않는다. v2는 정확한 구성·위치·결과 수신 상한·금융·전달 상태·정산/engine 경계·retiredThrough·receipt 원문/최초 적용 시각/회수 순서·모든 charge를 길이/건수 제한과 checksum으로 보호한다.

v1 읽기는 기존 #165의 엄격한 조건(executedThrough0·모두 초기 PENDING·전달/활성 관계·총량)이 성립하는 자료만 허용한다. 이를 정산 prefix0·빈 receipt로 해석하고 이후 쓰기는 v2다. 기준 커밋의 기존 v1 writer가 만든 실제 바이트를 고정 fixture로 보존해 새 reader의 호환을 확인한다. 새 writer의 version 숫자만 바꾼 자료를 기존 v1 호환의 근거로 삼지 않는다. v1이 담을 수 없는 정산 상태를 추정하거나 손상 파일에서 새 빈 lineage로 자동 재개하지 않는다. 이 제한적 읽기 호환은 메모리/bytes 검증이며 실제 실행물 교체·Archive 업그레이드를 검증했다는 뜻이 아니다.

빈 임시 자금 owner에서 기존 금융 검사와 새 관계를 모두 확인한 뒤 한 번 공개한다.

- 정확한 설정/stream coverage·순번 비음수·result/dispatch/engine 관계·마지막 Long 값 처리.
- receipt는 retained suffix로 연속·중복 없음, byte/count 합과 회수 순서 일치. receipt의 연속 사건 경계가 보존 종료 engine과 일치. 보존 첫 결과 직전 engine 경계도 명시적으로 저장/검증한다. receipt가 비었으면 그 경계는 현재 settledEngineSequence다.
- 보존 결과에서 후속 사건이 없는 활성 주문의 afterQuantity 등은 현재 금융 상태와 대조한다. 이미 끝난 주문을 되살리거나 회수한 전체 과거 이력을 가정하지 않는다.
- pending은 정산 prefix 뒤의 정확한 suffix, RESTING은 자기 결과 적용 뒤의 양수 잔량. live index·시장/공통 활성 건수·초기 승인 charge와 현재 예약 관계가 일치한다.
- 전달 ACK가 먼저 와서 payload가 없더라도 활성 원본/현재 예약의 근거는 남아야 한다. 결과가 먼저 와 종료된 입력은 합의한 matchingApplied prefix에 포함되어 전달 outbox에 남지 않아야 한다.
- hold 합계와 자산별 공급 총량·수수료를 BigInteger 등의 안전한 cold-path 계산으로 복원 검증한다. 총량 counter를 현재 잔고만 보고 임의 재발급하지 않는다.
- checksum이 맞아도 관계가 틀리면 거절한다. checksum을 발신자 인증·회수된 모든 과거의 진실성 증명으로 설명하지 않는다. 실패한 owner는 차단하고 정상 상태를 공개하지 않는다.

기본 복원은 bytes 쓰기/읽기 → 빈 owner 복원 → 같은 명시 결과/ACK/접수 명령의 후속열 적용을 중단 없는 실행과 대조한다. 새 역할의 실제 Archive 재생·리더 교체·서로 다른 클러스터 복원 위치 조합은 이번에 실행하지 않는다.

## 9. 수용 사례와 독립 기대값

아래 F1~F30은 **구현 후 검증할 수용 사례**이며 테스트 개수·통과 결과가 아니다. 정상 matching producer가 만드는 fixture 외에 독립 수기 값과 변조 입력을 사용한다. 계산기 결과를 기대값으로 그대로 복사하지 않는다.

| ID | 초기 상태·입력 | 기대값·금지 변화 |
| --- | --- | --- |
| F1 | 무체결 판매 주문1을 먼저 결과 적용 | 예약 유지, RESTING, result1/engine1, 성공 누적 ACK |
| F2 | 수수료0, 구매자 quote1,000·판매자 base1, 가격100×1 | 구매자 quote900/base1, 판매자 quote100/base0, 해당 hold0, 공급 보존 |
| F3 | 독립 실행: 구매자 quote1,000·같은 판매자 base2에서 SELL1@90·SELL1@95, BUY3@100, 수수료0 | 구매자 quote 가용715/예약100·base2, 판매자 quote185, BUY잔량1. 두 매도 종료. 결과3의 사건은3~5, 정산 engine5 |
| F4 | 현재 부분 체결 maker를 다음 결과가 다시 체결 | beforeQuantity는 현재 잔량과 같음. 초기 예약으로 되돌리지 않고 현재 수수료 나머지 이어 사용 |
| F5 | 같은 사용자 여러 maker 주문·동일 사용자의 양측 체결 | 잔고는 사용자·자산별 합산, 예약/수수료 나머지는 주문별 유지, 무관한 hold 보존 |
| F6 | 수수료 나머지 이월·가격 개선·최종 fill | 기존 fee 규칙의 독립 수기 기대값과 분개 수익 일치, 남은 예약 정확히 유지/해제 |
| F7 | quote1,000 중300 예약 후 ACTIVE_ORDER_LIMIT 최종 거절 | 700/300에서1,000/0, event0·engine불변·result진행·반환 분개1회 |
| F8 | RESULT_TOO_LARGE 최종 거절 | F7과 같은 반환 규칙. timeout/BUSY 자체는 결과 적용·반환을 만들지 않음 |
| F9 | 같은 전체 결과를 정산 후 재전송, 새 appliedAt 포함 | DUPLICATE·누적 ACK, 값/최초 시각/분개 효과/receipt 불변 |
| F10 | 보존 창 안 같은 identity의 다른 원문 | CONTENT_CONFLICT, 추가 돈 이동/성공 ACK0 |
| F11 | 작은 receipt 창을 반복 초과한 뒤 회수한 과거 재전송 | PRUNED·유효 stream 누적 ACK, 재정산0, 모든 건수/bytes 상한 유지 |
| F12 | 앞선 결과 누락 후 미래 체결/최종 거절 도착 | GAP·정확한 expectedSequence, 돈·반환·prefix·전달 회수0. 누락 적용 뒤 정상 진행 |
| F13 | 정산 결과가 명시 매칭 적용 ACK보다 먼저 도착 | 결과 정산과 함께 해당 입력까지 전달 prefix도 확인/회수. 종료 후 복원 가능. 뒤늦은 ACK no-op |
| F14 | 매칭 적용 ACK가 먼저 도착해 전달 payload 회수 | 현재 활성 원본/예약으로 결과 검증·부분/최종 정산 가능. ACK만으로 돈 이동0 |
| F15 | 잘못된 lineage/마켓/epoch/버전/context·음수/0 순번 | 과거 순번이라도 성공 ACK0·상태 무변경 |
| F16 | 위조 승인·없는/아직 PENDING maker·잘못된 beforeQuantity | INVALID_RESULT·부분 정산/전달 자료 회수0 |
| F17 | 사건/효과 누락·중복 maker·추가 효과·잔량/단위/가격 모순 | 전체 거절. 완결된 후보로 인정하지 않음 |
| F18 | 변조 입력·테스트 오류 주입으로 두 번째 체결의 overflow/수수료/분개 후보 계산 실패 | 첫 체결 후보도 실제 반영0, 성공 ACK·prefix·receipt·전달 회수0. 정상 승인·총량 상한 아래 유효 체결의 overflow를 일상적 거절로 취급하지 않음 |
| F19 | 실제 쓰기 중 예상 밖 실패 주입 | owner 차단, 추가 정상 쓰기/ACK/snapshot 공개 금지. 롤백 성공을 기대하지 않음 |
| F20 | 무체결→부분→최종 정산 사이 각각 snapshot 후 같은 후속열 | 중단 없는 실행과 현재 잔고·예약·수수료·prefix·활성/전달/receipt가 같음 |
| F21 | 최종 거절 반환 후 ACK 유실·snapshot 복원·재전송 | 추가 반환0, 같은 누적 정산 확인 재생성 |
| F22 | receipt 회수 후 snapshot 복원·옛 결과 재전송 | PRUNED 유지, 완료 주문 부활/재분개0 |
| F23 | 사용자 응답 ACK·epoch 회전 사이 미완료 주문 | 금융·전달·정산 수명 불변, 후속 결과 정상 처리 |
| F24 | 신규 접수 한도 포화 상태에 기존 정산/ACK | 새 접수만 제한, 정산/ACK로 활성·전달 공간 회수, 후속 접수 가능 |
| F25 | 잘린 bytes/잘못된 위치/길이/형식·checksum | 할당/읽기 한도 준수, 빈 임시 owner 복원 실패·정상 공개0 |
| F26 | checksum을 다시 만든 receipt hole·prefix/engine·hold/총량·charge 손상 | 의미 관계 검증으로 거절. 단순 checksum 통과를 성공으로 세지 않음 |
| F27 | 기존 v1의 유효 초기 PENDING 자료와 변조/정산 위장 v1 | 유효 자료만 prefix0으로 읽고 v2로 저장. 잘못된 자료의 자동 초기화0 |
| F28 | 마지막 표현 가능한 결과/사건 순번 및 과거 중복 | 마지막 값 허용, next overflow/wrap0 금지, 유효 과거 ACK·조회 유지 |
| F29 | 순수 상태에서 두 마켓의 서로 다른 prefix·공유 quote 잔고·전역 ordinal | 수신 순서는 stream별, 자금 효과는 호출 총순서대로 보존. receipt 공통 회수도 복원 뒤 결정적. 실제 다중 매칭 클러스터 연결 시험은 후속 |
| F30 | 기존 통합 golden·matching 원본/byte 회귀·새 역할 시작 요청 | 기존 경로/형식 유지, split runtime은 여전히 자원 생성 전 차단 |

### F6의 수기 수수료 예시

기존 `OrderSettlementContractTest`의 수수료 규칙을 수기 값으로 고정한다. base 수량 scale0, 가격51, maker5,000ppm(0.5%)·taker10,000ppm(1%)이다. 같은 판매자의 별도 SELL1 두 건에 구매자의 BUY2가 체결된다.

| 시점 | 구매자 quote 가용/예약 | 구매자 base | 구매 주문 수수료 |
| --- | --- | --- | --- |
| quote1,000에서 예약 | 896 / 104 | 0 | 거래102 + 수수료 예약2 |
| 첫 체결 계산 뒤 | 896 / 53 | 1 | 청구0, 나머지510,000 |
| 둘째 체결 계산 뒤 | 897 / 0 | 2 | 청구1, 나머지20,000, 미사용 예약1 반환 |

각 판매 주문의 수수료는0, 나머지는 각각255,000이다. 같은 판매자라는 이유로 두 주문의 나머지를 합치지 않는다. 판매자 최종 quote102/base0, 관련 hold0, 수수료 수익1이며 `897 + 102 + 1 = 1,000`이다. 한 결과 안 중간 값은 작업용 후보에서 확인하고 실제 상태를 중간 공개하지 않는다. 종료 주문의 나머지는 후보에서 대조하며 완료 주문을 영구 보관하라는 조건이 아니다. 이 예시는 기존 규칙의 기대값이고 이번에 새 테스트를 실행한 결과는 아니다.

## 10. 한 작은 PR의 구현·검증 순서

1. F1·F2·F9·F12로 순서/중복의 최소 입구를 고정한다. 결과 구조 검증과 금융 소유자의 로컬 승인 대조를 분리한다.
2. 기존 계산기를 연결해 F3~F6의 복수 체결·현재 예약·수수료와 전체 후보 대조를 완성한다.
3. 최종 거절 반환·안정 ID·분개 후보(F7~F8), 결과를 적용 증거로 쓰는 합의(F13~F14)를 같은 owner 변경에 넣는다.
4. receipt/활성/전달의 회수·포화·차단(F10~F19·F23~F24)을 확인한다.
5. 전용 v2·제한적 v1 읽기·빈 owner 복원과 의미 손상(F20~F29)을 같은 PR에 넣는다. 실제 연결은 다음 PR에 남긴다.
6. 기존 도메인/구조/통합 golden·필요한 기존 실제 HTTP 3노드 회귀를 검증하고, 변경·검사·독립 리뷰·미검증을 engineering 기록과 PR에 함께 남긴다.

제안하는 새 테스트 클래스는 `FundsResultSettlementTest`, `FundsResultRecoveryTest`다. 기존 `FundsRoleSnapshotCodecTest`, dispatch/solvency/role golden/startup 검사를 확장한다. 이름·개수는 제안이며 새 범용 테스트 플랫폼/CI job·제품 gate/관찰 큐를 만들지 않는다. 오류 주입과 상세 관찰은 test 소스에 둔다.

다음은 구현 후 사용할 **예상 명령**이다. 이번 명세 작업에서 실행하지 않았다. 실제 구현 폴더/선택 클래스는 착수 때 다시 맞춘다.

```bash
cd /Users/0chord/.codex/worktrees/issue148-http-aeron/exchange-core
./gradlew :domain-matching:test :domain-trading:test :domain-order:test :domain-fee:test :domain-ledger:test :architecture-tests:test :architecture-tests:verifyArchitectureReport ktlintCheck --no-daemon --max-workers=1 --console=plain
```

```bash
cd /Users/0chord/.codex/worktrees/issue148-http-aeron/exchange-core
./gradlew :app-api:test --tests '*FundsRoleSnapshotCodecTest' --tests '*MatchingRoleSnapshotCodecTest' --tests '*BoundedTradingRoleGoldenTest' --tests '*BoundedTradingStateCodecTest' --tests '*BoundedTradingSnapshotContinuationTest' --tests '*TradingRoleStartupTest' --tests '*BoundedHttpTradingClusterTest' --no-daemon --max-workers=1 --console=plain
```

Java25와 기존 Docker 시험 조건을 사용한다. 필요한 기존 실제 HTTP 회귀는 INTEGRATED 3노드·sync2/2/2의 회귀로 기록하며 새 분리6노드 시험으로 부르지 않는다. 한 run 소유 컨테이너·볼륨·이미지·진단물에 한도와 성공/실패 정리를 둔다. 환경 전체 prune·장기 부하·성능 도구 확대는 하지 않는다.

완료는 F1~F30의 실제 제품 분기/검증 근거 대조, 구조 P01~P09 및 형식 검사, 검토 범위의 미해결 결함 처리, 정상 복원 뒤 후속 처리까지 확인한 기록으로 판단한다. 테스트 Green 하나로 실제 원격 ACK·Archive·원장 이력 전달까지 완료라고 쓰지 않는다. 분개는 계산/검증하되 전체 이력의 직접 관찰·외부 적재는 후속이다.

## 11. 실제 문답·미결·다음 인계

### 설명 초점 문답

- 질문: “이번 #167에서는 매칭이 체결을 확정한 뒤 자금 쪽이 정산하고, 그 확인을 받아야 매칭 결과를 정리할 수 있어. 명세 설명에서 어느 연결을 가장 자세히 보고 싶어? 기존 계약은 유지하면서 코드 확인과 명세 작성을 계속할게.”
- 사용자 답변: “매칭 적용 확인과 자금 정산 확인의 차이 (추천)”. 별도 이유는 말하지 않았다.
- 반영: 2절의 돈 전후 값·서로 다른 ACK 수명과 F13~F14. 설명 선호이며 추가 기능/구현 승인으로 확대하지 않는다.

### 결과가 적용 확인보다 먼저 도착하는 경우

- 질문: “매칭의 ‘주문 적용 확인’보다 체결 결과가 먼저 도착할 수 있어. 추천은 자금 쪽이 그 결과를 검증·정산한 뒤, 해당 주문이 매칭에 적용됐다는 사실도 함께 기록해 주문 전달 자료를 정리하는 방식이야. 예약은 남은 수량만큼 유지하고, 검증 실패 시에는 아무것도 정리하지 않아. 이 방식으로 할까?”
- AI 추천·근거: 별도 ACK를 기다리는 불필요한 정지를 피하고, 금융 종료 주문의 전달 payload만 남아 복원 관계가 깨지는 것을 막는다. 전체 결과의 순차 금융 검증·반영이 먼저이며 수신만으로 회수하지 않는다.
- 사용자 답변: “정산한 결과를 적용 증거로 함께 사용 (추천)”. 별도 이유는 말하지 않았다.
- 결정: 합의. 2·5.4·6·8절과 F13~F14에 반영. 대안은 별도 ACK까지 종료 주문의 전달 원본을 독립 보존하는 방식이었으며 이번에는 채택하지 않았다.

### 현재 요청에서 확정하지 않는 것

리더 자금 노드 JVM 안의 별도 전송 작업은 앞선 대화의 유력 후보다. 사용자는 “리더 노드에서 보내는게 나을 것 같기도 하네 그럴 수 있는건가?”라고 물었고 가능성과 조건을 설명했다. 이를 확정된 #168 배치나 이미 있는 코드로 기록하지 않는다. 실제 활성 리더/복원 중 송신 제어·재전송·출처 인증·wire·원격 ACK 로그 확정은 #168~169에서 상세화한다. #167의 비차단 후속이다.

첫 구현 인계는 **고정 stream의 무체결 결과1 → 체결 결과2 → 같은 결과2 재전송**이다. F1·F2·F9·F12를 독립 기대값으로 삼고 기존 순수 계산기를 재사용한다. 이 명세 작성은 구현·테스트 실행·PR 생성·병합·#166 완료 처리·보드 변경을 포함하지 않는다.
