# #73 주문 취소로 돌려준 금액을 기록하기

**명세에 따른 로컬 구현과 검증을 완료했다. 현재 결과는 [구현 읽기 안내](cancellation-ledger-review.md)에서 확인한다.** [GitHub 이슈 #73](https://github.com/0Chord/coin-exchange/issues/73)

## 이미 되는 것과 이번에 추가할 것

**지금도 주문을 취소하면, 사용하지 않은 돈은 돌려준다. 이번 작업은 그때 ‘어느 주문에 얼마를 돌려줬는지’를 기록으로 남기는 것이다.**

| 지금 되는 것 | 이번에 추가할 것 |
| --- | --- |
| 취소한 주문에 묶여 있던 돈을 다시 쓸 수 있게 한다 | 돌려준 금액을 주문의 예약 정보와 자금 이동 기록에 함께 남긴다 |
| 이미 반환한 주문이면 돈을 다시 돌려주지 않는다 | 반복 요청 때 두 기록의 주문·사용자·자산·금액이 맞는지도 확인한다 |
| 예약 정보 변경과 잔고 반환을 함께 저장한다 | 새 반환 기록도 같은 저장 작업에 포함해, 일부만 저장되지 않게 한다 |

## 예: 300을 묶어두고 100을 쓴 뒤 주문을 취소하면

수수료가 없고, 100원짜리 3개를 사려는 주문이다. 먼저 1,000을 가지고 있었다고 하자.

| 시점 | 지금 쓸 수 있는 돈 | 주문에 묶여 있는 돈 | 거래에 이미 쓴 돈 |
| --- | ---: | ---: | ---: |
| 주문 전 | 1,000 | 0 | 0 |
| 주문에 300을 묶어둠 | 700 | 300 | 0 |
| 1개를 100에 구매함 | 700 | 200 | 100 |
| 남은 2개를 취소함 | 900 | 0 | 100 |

마지막 줄의 돈 반환은 지금도 된다. **이번에 추가하는 것은 ‘이 주문을 취소하면서 200을 돌려줬다’는 기록이다.** 이미 구매에 쓴 100은 반환하지 않으므로, 최종 금액은 1,000이 아니라 900이다.

## 구현은 이렇게 진행한다

1. **취소한 주문에 얼마가 남았는지 확인한다.** 위 예에서는 200이다.
2. **200을 돌려주면서 두 기록을 남긴다.** 주문의 예약 정보에 ‘반환 완료·반환액 200’, 자금 이동 기록에 ‘이 주문의 취소 반환 200’을 저장한다.
3. **잔고와 두 기록을 함께 저장한다.** 저장 도중 하나가 실패하면 이번 반환과 기록 변경을 모두 되돌린다. 이미 성공한 주문장 취소·취소 이벤트까지 되돌리는 것은 아니다.
4. **같은 반환 작업을 다시 요청하면 기록을 확인한다.** 둘 다 200 반환으로 일치하면 돈을 더 주지 않는다. 기록이 없거나 다르면 오류를 알리고 임의로 돈을 움직이지 않는다.

## 기존 체결 기록과 섞지 않는다

체결할 때 가격 차이나 사용하지 않은 수수료 때문에 이미 돌려준 돈이 있을 수 있다. 그 금액은 기존 체결 기록을 유지한다. **이번 기록에는 취소 때문에 마지막에 돌려준 금액만 남긴다.**

아래는 구현자가 읽을 상세 기준이다. 첫 화면의 설명과 같은 작업을 코드·DB 용어로 풀어놓았다.

<details markdown="1"><summary>0. 쉬운 설명과 코드 용어 연결 · 상태별 상세 판단</summary>

| 쉬운 표현 | 코드·DB에서 쓰는 말 |
| --- | --- |
| 지금 쓸 수 있는 돈 | available |
| 주문에 묶여 있는 돈 | hold, 예약의 remainingAmount |
| 이 주문에서 돌려준 금액 | releasedAmount |
| 주문의 예약금을 다 돌려준 상태 | RELEASED |
| 취소 때문에 돌려준 자금 이동 기록 | RELEASE 원장 거래 |
| 체결 과정의 자금 이동 기록 | SETTLEMENT 원장 거래 |
| 관련 저장이 모두 성공하거나 모두 되돌아가게 하는 경계 | DB 트랜잭션 |

200 반환의 원장 표기는 같은 사용자·자산의 HOLD 차변 200 / AVAILABLE 대변 200이다. 묶인 돈을 줄이고 사용 가능한 돈을 늘렸다는 기록이며 새 돈이나 수수료 수익을 만드는 것이 아니다. 예약은 RELEASED·remainingAmount=0·releasedAmount=200을 보존한다.

기준: 최신 통합 `5d0f6ee336382b7b1cb6c1d8bf10cc6c3ceceb4f` (#79 병합). [이슈 #73](https://github.com/0Chord/coin-exchange/issues/73) · 상위 [#42](https://github.com/0Chord/coin-exchange/issues/42). #43 계약과 #72 예약 원장은 통합되어 있다. 이슈/보드의 완료 표시는 별도다.

명세 폴더: `/Users/0chord/.codex/worktrees/issue73-cancellation-spec/exchange-core` · 구현 브랜치 `feat/cancellation-ledger/73`.

1. 기존 취소 유즈케이스가 매칭 엔진에 취소 명령을 보낸다. 엔진 취소 → 이벤트 발행/저장 → `OrderCancelled`일 때만 해제 서비스 호출 순서를 유지한다.
2. 해제 서비스가 Spring 트랜잭션을 시작하거나 외부 트랜잭션에 참여하고, `(marketId, orderId)` 예약을 `FOR UPDATE`로 잠근다.
3. 해당 주문의 안정적인 RELEASE 키로 원장 헤더와 항목을 읽는다. 예약 상태와 함께 아래 표로 판정한다.
4. 최초 ACTIVE 해제라면 **현재 remainingAmount**를 반환액으로 정한다. 도메인 `release()`가 새 예약에 반환액을 보존하고 잔여 금액·잔여 수수료 예약액을 0으로 만든다. 수량·접수 정책·수수료 소수 나머지는 유지한다.
5. 예약 갱신 → 기존 조건부 `BalanceStore.release` → RELEASE 헤더·두 항목 추가 → 트랜잭션 커밋. 반환액은 재계산하거나 외부에서 따로 입력받지 않는다.
6. 반복 RELEASED는 원장까지 정확히 일치하면 저장·잔고 변경 없이 기존 예약을 반환한다. 오류·불일치는 성공으로 바꾸지 않는다.

| 잠근 예약 | 해당 RELEASE 기록 | 판단과 금지할 변화 |
| --- | --- | --- |
| 없음 | 유무와 무관 | 기존 예약 없음 오류. 임의 반환·예약 재생성 없음 |
| ACTIVE, releasedAmount 없음 | 없음 | 최초 해제. 세 저장을 함께 수행 |
| ACTIVE | 존재 | 상태·원장 모순. 내용이 같아도 반환·상태 보정 없음 |
| RELEASED, 반환액 양수 | 정확히 일치 | 이미 완료. 원본 예약·원장·잔고·시각 보존 |
| RELEASED | 없음/불완전/다른 내용 | 기록 불일치. 자동 반환·원장 재생성 없음 |
| RELEASED, 반환액 없음 | 유무와 무관 | 반환 금액의 근거 부족. 추측하지 않고 불일치 오류 |
| SETTLED | 없음 | 기존 전량 체결 해제 거절. RELEASE 생성 없음 |
| SETTLED | 존재 | 원장·예약 모순. 자금 보정 없음 |

여기서 '반복 호출'은 내부 해제 서비스 재호출이다. HTTP 취소 재요청이 엔진에서 거절되는 기존 동작은 바꾸지 않는다. HTTP 멱등 응답은 #65, 저장된 취소를 찾아 재호출하는 복구는 #57의 범위다.


</details>

<details markdown="1"><summary>1. 확인한 현재 코드와 재사용할 책임</summary>

| 현재 파일·심볼 | 확인한 사실 | 이번 변경 |
| --- | --- | --- |
| `order/application/CancelOrderUseCase.cancel` | `OrderCancelled`에 대해서만 해제 서비스 호출 | 호출 조건·인자·API 유지 |
| `order/application/OrderReservationReleaseService.release` | 예약 잠금 → RELEASED 즉시 반환 또는 예약 갱신·잔고 반환. 원장 연결 없음 | 원장 조회·대조·추가를 업무 경계에 연결 |
| `domain-order/OrderReservation.release` | 불변 객체 반환, remainingAmount/remainingFeeReserveAmount=0. 반환액을 별도로 남기지 않음 | 반환 직전 금액을 releasedAmount로 함께 보존 |
| `order/infrastructure/persistence/PostgresOrderReservationStore` | create/find/findForUpdate/update로 수량·금액·정책·시각 저장 | 새 필드의 insert/select/update 연결. 잠금·PK·기존 컬럼 유지 |
| `ledger/infrastructure/persistence/PostgresBalanceStore.release` | hold 조건을 포함한 UPDATE … RETURNING. 부족·행 부재 오류 | SQL 유지. 범용 자동 분개 추가 금지 |
| `domain-ledger/LedgerTransactionStore` | append만 있고 공개 조회 계약 없음 | `findBySourceEventId` 조회 계약 추가 |
| `ledger/infrastructure/persistence/PostgresLedgerTransactionStore` | 헤더와 항목을 기존 트랜잭션에 저장 | 같은 구현에 좁은 원본 ID 조회 추가 |
| `order/application/TradeSettlementService` | 가격 개선·미사용 수수료 반환을 SETTLEMENT 항목에 이미 포함 | 계산·분개·release 호출 유지 |
| `config/LedgerPersistenceConfig` | 기존 영속화 조건 아래 @Bean 조립 | 해제 서비스에 기존 원장 포트 주입 |

도메인은 순수 Kotlin, SQL과 Spring 조립은 app-api에 둔다. @Service 등록, 새 저장 플랫폼, 범용 사건 ID 계층, 별도 REQUIRES_NEW 저장은 추가하지 않는다. 기존 개시 준비 구현의 private 원장 조회는 참고할 수 있지만 #71을 함께 리팩터링하지 않는다.

원본 코드는 위 기준 커밋의 `app-api/src/main/kotlin/com/exchange/core/api/` 및 `domain-order/src/main/kotlin/com/exchange/core/order/`, `domain-ledger/src/main/kotlin/com/exchange/core/ledger/`에서 확인했다.

</details>

<details markdown="1"><summary>2. 반환 금액 보존 — 실제 Ask 합의와 migration</summary>

**질문:** 반환 후 remainingAmount가 0이면 원장의 금액을 무엇과 비교할까? 반환 금액을 예약에도 보존할까?

**AI 추천·근거:** `releasedAmount`를 함께 보존한다. 수량·지정가만으로는 사용하지 않은 수수료와 가격 개선 이력을 정확히 되살릴 수 없다. 원장의 존재·형식만 확인하는 대안은 원래 반환액의 정확한 일치를 보장하지 못한다.

**실제 사용자 답변:** “반환 금액을 예약에도 보존하고 원장과 정확히 대조 (추천)”. 선택 이유는 별도로 답하지 않았으므로 추측하지 않는다. **확정:** 반환액을 보존하고 반복 호출의 금액 기대값으로 사용한다.

구체 저장 표현은 구현 추천안이다. `releasedAmount: Amount?`와 nullable `released_amount bigint`를 추가한다. ACTIVE/SETTLED는 null이며, 이번 구현의 `release()`는 양수 remainingAmount를 그대로 기록한다. RELEASED의 알려진 금액은 0보다 크고 reservedAmount 이하여야 한다. 과거 RELEASED의 null은 '모르는 금액'으로 읽을 수 있지만 해제 서비스에서 성공으로 인정하지 않는다. 0과 알 수 없음을 섞지 않는다.

이번 migration은 `V8__add_order_reservation_released_amount.sql`이다. 기존 V1~V7은 수정하지 않는다. 상태별 null·양수·상한을 새 DB CHECK와 도메인 검사에 맞추되, 기존 RELEASED/null 행은 삭제·추측·backfill 없이 보존한다. 최초 생성·체결 전이에는 null, 취소 전이에는 실제 반환액을 쓴다. 과거 전체 DB를 새 형식으로 전환하거나 시작 시 구형 DB를 판별·차단하는 기능은 넣지 않는다.

상태·금액이 서로 다른 데이터나 조회 오류는 원인을 보존해 실패시킨다. 기존 RELEASED/null에 재호출해도 잔고나 원장을 보정하지 않는다. 이 항목은 학습용 개발 DB를 삭제·초기화하라는 권한이 아니다.

</details>

<details markdown="1"><summary>3. 원장 키·조회·완료 대조 계약</summary>

추천 키: `RELEASE:v1:` + SHA-256(`marketId` UTF-8 바이트 길이 4바이트 big-endian + 원본 바이트 + `orderId` 길이 + 원본 바이트). #72와 동일한 결합 방식, 다른 사건 접두사다. 75자로 기존 source_event_id varchar(128)에 들어간다. trim·대소문자·Unicode 정규화·UUID를 원본 키에 사용하지 않는다. 최종 취소 반환은 주문 생애당 한 건이며 다른 마켓의 같은 주문 ID는 다른 키다. 식별자 재사용 허용 정책을 새로 만들지 않는다. 해시 충돌이 수학적으로 없다고 보장하지 않는다.

원장 거래 ID는 최초 실행의 새 UUID, occurredAt은 현재 해제 처리 시각이다. 엔진 취소 발생 시각이라고 표현하지 않는다. 재호출은 ID·처리 시각·created_at을 새로 만들지 않는다.

`LedgerTransactionStore.findBySourceEventId(sourceEventId): LedgerTransaction?` 제안: 정확한 헤더가 없을 때만 null, 헤더가 있는데 항목이 없거나 손상되면 기록 오류, DB 읽기 실패는 저장소 오류. 추천은 헤더와 항목을 한 SQL의 LEFT JOIN으로 읽어 동일 statement snapshot을 사용하고, 항목 없는 헤더를 '없음'으로 버리지 않는 것이다. 별도 Reader 포트·검색 프레임워크는 만들지 않는다.

완료 대조는 source ID, RELEASE 종류, 해당 사용자·자산의 **HOLD DEBIT / AVAILABLE CREDIT 정확히 두 항목**, 양쪽 금액=예약 releasedAmount, 추가·누락·다른 자산/계정/방향 없음이다. 단순 차변·대변 균형만으로 같은 반환이라고 판단하지 않는다. 원장 ID·처리 시각은 새 요청값과 비교하지 않고 원본을 유지한다. 구현은 구체 불일치를 담는 runtime 예외를 제안하며, DB 오류와 구분한다. 예외 명칭의 세부는 구현자가 정할 수 있다.

예약 잠금은 정상 해제 호출과 기존 정산이 같은 예약을 동시에 수정하지 못하게 한다. 원장 writer는 append만 하고 source UNIQUE를 유지한다. 읽은 뒤 다른 writer가 같은 source를 넣어도 UNIQUE 실패를 삼키지 않고 이번 트랜잭션을 롤백한다. '존재하니 완료'로 바꾸지 않는다. 직접 SQL로 쓰는 운영 외 행위에 대한 분산 잠금·변조 방지는 보장하지 않는다.

반복 호출에서 현재 사용자 잔고가 최초 완료 직후 값인지 비교하지 않는다. 이후 다른 주문으로 잔고가 변할 수 있다. 사용자 전체 원장·잔고·활성 예약의 대조는 #74다.

기존 포트의 메서드 확장이므로 새 포트 역할 등록은 필요 없다. 기존 P06/ARCH-06의 반환·입력 계약 검사에 조회 메서드도 포함되는지 확인하고, 테스트용 포트 구현체가 있다면 컴파일 계약을 함께 맞춘다. 새 범용 구조 검사 규칙은 추가하지 않는다.

</details>

<details markdown="1"><summary>4. 트랜잭션·실패 후 남는 상태</summary>

해제 서비스의 기존 `@Transactional` REQUIRED와 같은 DataSource를 재사용한다. 예약 갱신·잔고 반환·원장 append는 같은 트랜잭션에 참여해야 한다. 외부 트랜잭션이 있으면 메서드 반환이 커밋을 뜻하지 않으며 외부 롤백에 함께 롤백된다.

예약 갱신 실패, hold 부족/잔고 행 부재, available 증가 overflow, 원장 헤더·첫/둘째 분개 오류는 오류를 전파한다. 알려진 저장 본문 실패에서는 이번 변경 전의 예약·releasedAmount·잔고·갱신 시각으로 돌아가고 새 RELEASE/부분 항목은 남지 않는다. 기존 RESERVE·SETTLEMENT·다른 주문 기록은 유지한다. 오류를 잡고 같은 실패 트랜잭션 안에서 성공 재조회하지 않는다. DB 시퀀스의 빈 번호는 rollback 실패가 아니다.

**HTTP 전체 흐름은 하나의 DB 트랜잭션이 아니다.** 엔진 취소와 이벤트 저장 뒤 해제가 실패하면 취소 결과는 남을 수 있고 자금은 반환 전 상태다. 현재 worker가 오류를 전달하고 같은 마켓의 후속 명령을 막는 동작을 유지한다. #57이 저장된 취소의 미완료 해제를 재개한다. 이번에 자동 재개·보상 취소·잔고 수리·마켓 재개를 추가하지 않는다.

3초 응답 대기 초과는 worker 취소·DB 롤백·자금 반환의 증거가 아니다. 커밋 응답 유실도 '자금 미반영'으로 단정하지 않는다. 이미 커밋됐으면 이후 해제 호출이 원본 완료 근거를 대조해 무변경 반환할 수 있으나, 커밋 결과 불명·네트워크 단절·실제 프로세스 종료 실험은 이번 실행 검증에 포함하지 않는다. 현재의 마켓 차단은 메모리 상태이며 재시작 안전성은 후속 복구 작업이다.

</details>

<details markdown="1"><summary>5. SETTLEMENT와 RELEASE를 중복시키지 않는 예</summary>

**정산 반환:** 수수료 0, 지정가 100에 BUY 3을 300 예약한 뒤 1개가 80에 체결되면 그 체결 몫의 예약 100 중 80을 소비하고 20을 반환한다. 잔고는 720/200이다. 20 반환은 기존 SETTLEMENT 안의 HOLD 차변/AVAILABLE 대변으로만 남긴다. 그때 RELEASE는 0건이다.

**이후 취소:** 남은 2개의 예약 200을 취소하면 RELEASE 200 한 건, releasedAmount=200, 잔고 920/0이다. SETTLEMENT 안의 20을 RELEASE에 다시 기록하지 않는다. 전량 체결 후 SETTLED에 해제를 호출해도 RELEASE를 만들지 않는다.

BUY 잔여 수수료는 remainingAmount에 이미 들어 있다. 부분 체결 뒤 remainingAmount=202,000이면 전체 202,000을 반환하고 RELEASE 두 항목도 202,000이다. 잔여 수수료를 따로 더하거나 청구된 수수료 수익을 줄이지 않는다. SELL은 남은 base만 반환한다.

구현 위치는 `OrderReservationReleaseService`이고, `BalanceStore.release`에는 원장 기록을 붙이지 않는다. 같은 저수준 메서드를 호출하는 `TradeSettlementService`를 바꾸면 두 종류에 이중 기록이 생길 수 있기 때문이다.

</details>

<details markdown="1"><summary>6. 정상·반복·충돌·롤백 수용 사례</summary>

아래는 명세의 기대값이다. 실제 테스트와 실행 결과는 구현 읽기 안내에 별도로 연결한다. 금액은 최소 단위이고 수수료 0 예시는 해당 정책을 명시해 준비한다.

| ID | 초기 상태·입력 | 기대 결과·보존할 근거 |
| --- | --- | --- |
| A01 | 개시 1,000 → 예약 300 → 정산 100 → 취소 | available=900/hold=0, RELEASE 200·두 분개, RELEASED/remainingAmount=0/releasedAmount=200. 이전 원장 유지 |
| A02 | 미체결 BUY: 200,000+수수료 예약 2,000 | 202,000 반환·분개, 청구 수수료 0, feeRemainder·남은 수량 유지 |
| A03 | SELL base 10에서 3 예약·1 정산·2 취소 | base 9/0, RELEASE base 2. quote·수수료의 기존 정산 결과 유지 |
| A04 | 같은 해제 순차·동시 두 호출 | 동일 원본 예약 반환, 자금 이동·RELEASE 한 번. 모든 관련 행·시각·ID 동일 |
| A05 | 해제 완료 뒤 다른 주문으로 잔고 변경 후 재호출 | 이미 완료, 현재 잔고를 최초 값으로 덮어쓰지 않음 |
| A06 | RELEASED인데 원장 없음·항목 누락/불균형·다른 종류/계정/자산/방향/금액·추가 항목 | 구체 기록 불일치. 서로 균형 잡힌 잘못된 금액도 거절. 전체 상태 무변경 |
| A07 | ACTIVE인데 같은 source 원장 존재; SELECT 뒤 append 충돌 | 같은 내용도 상태 충돌/UNIQUE 오류. 신규 변경 롤백·기존 원장 보존 |
| A08 | RELEASED/releasedAmount=null인 기존 행 | 근거 부족 오류. 금액 추측·반환·backfill 없음 |
| A09 | 예약 없음·SETTLED·타인/미존재 주문 취소 거절 | 기존 거절, 임의 반환·RELEASE 없음. SETTLED와 RELEASE 동시 존재도 모순으로 보고 |
| A10 | 예약 갱신·잔고 반환·헤더·첫/둘째 분개에서 각각 실패 | 이번 세 저장 전체 롤백. 이전 예약/시각·잔고·원장 유지. 실패 제거 후 재호출은 한 번 완료 |
| A11 | hold 부족·잔고 행 부재·available 증가 overflow | 오류, 예약 전이·releasedAmount·새 RELEASE 없음 |
| A12 | 서로 다른 예약의 해제 경쟁, 실제 hold가 합보다 부족 | 조건부 SQL로 승자만 완료. 패자 예약·반환액·원장 유지/없음. 음수 hold 없음 |
| A13 | 외부 트랜잭션에서 해제 후 rollback | 반환·예약 전이·원장 모두 rollback. REQUIRED 참여 확인 |
| A14 | BUY 300 예약 → 80 체결/20 가격 개선 반환 → 200 취소 | 정산 반환 20은 SETTLEMENT에만, RELEASE는 취소 200 한 건. 최종 920/0 |
| A15 | 콜론 포함 ID 쌍·64자 한글 ID·다른 마켓의 같은 order ID | 독립 고정 기대 키, 결합 모호성·128자 제한·사건 종류 충돌 없음 |
| A16 | 조회 시 헤더만 있음·항목 없음·손상/DB 읽기 실패 | 없음/null·이미 완료로 숨기지 않음. 후속 저장 안 함 |
| A17 | 기존 행을 둔 migration·새 create/fill/release/store roundtrip | 과거 데이터 보존, 기존 RELEASED는 null 유지. 새 반환액 저장·읽기와 CHECK/도메인 범위 일치 |
| A18 | HTTP 취소 이벤트 저장 성공 → 해제 저장 실패 → 다음 명령 | 취소 이벤트 남음, 반환 DB 변경 rollback, 같은 마켓 다음 명령 거절. 정상 복구 성공으로 표시하지 않음 |
| A19 | Bean 생성·설정 false/미설정·순수 도메인 규칙 | 같은 기존 원장 Bean 주입, 생성 시 SQL 없음, 기존 비영속화 조건·구조 경계 유지 |

원장 존재만 확인하거나, 테스트에서 제품의 분개 생성/키 함수를 호출한 결과를 정답으로 쓰지 않는다. 계정·자산·방향·금액·상태·행 수와 실패 전후 전체 행을 독립 기대값으로 확인한다. DB 항목 조회 순서만 달라진 것을 의미적 불일치로 만들지는 않는다.

</details>

<details markdown="1"><summary>7. 기존 테스트와 보강할 사례 · 한 PR의 세 단위</summary>

| 재사용할 테스트 | 현재 확인한 내용 | 이번 보강 |
| --- | --- | --- |
| `domain-order/OrderReservationTest` | 불변 release, 수량 유지, 수수료 예약 해제, 상태 거절 | releasedAmount의 전이·상한·null 의미, 원본 불변 |
| `OrderReservationReleaseServiceTest` (구현 전 6개) | 반환·반복·예약 없음·부족 rollback·서로 다른 주문 경쟁·동시 같은 주문 | RELEASE·금액 근거·충돌·단계별 DB 실패·재시도·외부 rollback |
| `PostgresOrderReservationStoreTest` 및 migration 테스트 | 예약과 정책·소수 나머지 roundtrip | 새 필드 insert/select/update 및 기존 행 보존 |
| `PostgresLedgerTransactionStoreTest` | 기존 writer 저장/제약 | 정확한 source 조회·항목 누락/손상·읽기 실패 구분 |
| `TradeSettlementServiceTest` | 부분 체결·수수료·가격 개선·취소 잔여금·중복 | 기존 SETTLEMENT 항목/수익 그대로, 취소에만 RELEASE. 기존 '해제 후 원장 전체 불변' 기대를 이전 거래 불변+정확한 새 RELEASE로 수정 |
| `OrderLifecycleE2ETest` (구현 전 4개) | 실제 HTTP/DB BUY·SELL 체결·부분 체결 취소·미체결 취소 | 이미 있는 취소 사례에 releasedAmount·RELEASE 두 항목 추가. 기존 JSON·금액·이벤트 순서 유지 |
| processor/유즈케이스/설정 테스트 | 후속 실패 차단·거절 취소의 조건·명시 Bean 조립 | 실제 해제 오류와 다음 명령의 연결, 포트 추가 연결 확인 |

**한 PR, 작은 검토 단위 3개:**

1. 반환 근거 준비: OrderReservation 필드·release 전이·store mapping·V8·기존 원장 source 조회. 단위/DB 읽기·migration 계약부터 검증한다.
2. 업무 경계 연결: ACTIVE 최초 해제의 두 분개·Bean, RELEASED 대조·충돌·순차/동시 반복. A01~A09·A15~A16을 입력 → 판단 → 상태/원장 결과로 읽는다.
3. 실패·회귀: 실제 PostgreSQL rollback·외부 트랜잭션·정산 반환 비중복·기존 HTTP·후속 차단. A10~A14·A18~A19와 전체 검사로 마친다.

테스트 작성 → 기대값 검토 → 구현 → 검증을 단위별로 연결한다. 소스 파일 수나 테스트 개수를 완료 목표로 삼지 않는다. 세 단위는 서로 다른 큰 기능이 아니라 한 반환 계약의 데이터·동작·실패 근거다. 구현 중 다른 독립 흐름이 생기면 포함하지 말고 범위를 다시 논의한다.

실행한 검증 명령:

```sh
cd /Users/0chord/.codex/worktrees/issue73-cancellation-spec/exchange-core
./gradlew :domain-order:test :domain-ledger:test --console=plain
./gradlew :app-api:test --tests '*OrderReservationReleaseServiceTest' --tests '*PostgresOrderReservationStoreTest' --tests '*PostgresLedgerTransactionStoreTest' --tests '*LedgerPersistenceConfigurationTest' --tests '*OrderLifecycleE2ETest' --tests '*TradeSettlementServiceTest' --console=plain
./gradlew ktlintCheck build :architecture-tests:verifyArchitectureReport --continue --console=plain
```

구현 폴더가 바뀌면 실제 경로로 안내한다. Spring Bean 프록시·PostgreSQL/Testcontainers와 테스트 외부 트랜잭션 없는 실제 커밋/rollback을 확인하고, 정상 개수뿐 아니라 실패·오류·skip 및 필수 운영 검사 P01~P09 실행을 대조한다. 실패 주입은 테스트 전용 제약/fixture를 finally에서 제거하고 제품 schema에 남기지 않는다. HTTP JSON은 유지하지만 서비스가 반환하는 내부 OrderReservation에는 새 필드가 추가된다.

</details>

## 완료 기준과 제외 범위

구현 완료는 반환액·예약 상태·잔고·RELEASE의 원자 저장, 완료 대조 후 반복 무변경, 충돌/읽기 오류 구분, 실제 rollback·경쟁·SETTLEMENT 비중복, 기존 HTTP/수수료 회귀와 필수 검사 실행 근거가 충족되어야 한다. 실제 구현·실행·독립 리뷰 결과는 [구현 읽기 안내](cancellation-ledger-review.md)의 최종 검증 기록에 연결했다.

**제외:** #50 접수 입력, #57 취소 기록 검색·복구 재개, #65 HTTP 중복 응답 개편, #74 전체 원장·잔고 대조/자동 수리, 실제 프로세스 종료·재시작(#69~70), 구형 데이터 금액 복원/자동 이행·DB 초기화, 매칭·정산·수수료 계산 변경, 새 플랫폼/구조 검사 프레임워크.

반환 금액 보존은 사용자 확정이며, nullable 표현·단일 SQL 조회·키 함수·구체 오류 명칭은 기존 계약을 만족하는 구현 추천안이다. 결과를 좌우하는 사용자 선택은 남아 있지 않다. 실제 코드·DB 실행 검증을 완료했으며, 전체 729개 테스트가 실패·오류·건너뜀 없이 통과했다. 린트·전체 빌드·P01~P09 운영 구조 검사 보고서 확인도 통과했다. 원격 이슈·보드 상태·우선순위는 이 명세 작성만으로 변경하지 않는다.
