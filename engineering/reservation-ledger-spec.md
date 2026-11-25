# #72 예약·잔고·RESERVE 원장을 함께 저장하기

**확정: 같은 주문을 다시 요청하면 중복 오류를 반환한다. 기존 예약·잔고·RESERVE는 변경하지 않는다.**

단계: 로컬 구현·검증 완료. 관련 PostgreSQL·설정·HTTP·정산 검사 37개와 전체 검사 691개, ktlint·P01~P09 통과. 독립 리뷰에서 확인된 미해결 결함 없음. [구현 흐름과 실행 기록](reservation-ledger-review.md)에서 확인한다.

기준은 `feature/phase-2/integration`의 `76cb78d3e87edac7c7b99870ed64841b2e8ff05a`다. #78의 초기 자금·공통 ktlint 변경이 병합됐고 해당 PR의 두 CI가 성공한 것을 확인했다. 이 결과는 #72 구현의 검증 결과가 아니다.

관련: [#72](https://github.com/0Chord/coin-exchange/issues/72), 상위 [#42](https://github.com/0Chord/coin-exchange/issues/42), 공유 계약 [#43](https://github.com/0Chord/coin-exchange/issues/43). #43의 문서는 PR #75로 통합돼 있다. GitHub 이슈가 Open인 상태와 산출물 준비 여부는 구분한다.

## 이번 PR에서 만들 한 가지

**한 주문의 예약과 available → hold 이동에 RESERVE 원장을 함께 남긴다. 저장 본문에서 하나라도 실패하면 이번 호출의 변경을 모두 롤백한다.**

수수료 없는 설명용 BUY: available=1,000 / hold=0에서 300을 예약하면 available=700 / hold=300, ACTIVE 예약 300, RESERVE 거래 한 건과 분개 두 항목이 남아야 한다. 300은 사라지거나 새로 지급된 돈이 아니라 같은 사용자의 계정 사이에서 이동한 돈이다.

이번에는 예약이 성공한 뒤 매칭하는 기존 흐름을 유지한다. 주문 입력·접수 순번의 영구 기록(#50), 취소 반환 원장(#73), 전체 원장/잔고 대조(#74), HTTP 중복 성공 응답(#65)은 만들지 않는다.

## 확정한 중복 처리

같은 `(marketId, orderId)`의 예약이 이미 있으면 **`OrderReservationAlreadyExistsException`을 반환**한다. 입력이 같아도 성공 결과나 원본 예약을 반환하지 않는다. 사용자·가격·수량·수수료 정책이 달라도 기존 주문을 덮어쓰지 않는다.

| 요청 | 호출 결과 | DB에 남는 상태 |
| --- | --- | --- |
| 첫 300 예약 | 정상 예약 결과 | available 700 / hold 300, 예약 한 개, RESERVE 한 건·분개 두 항목 |
| 같은 주문 재요청 | 중복 오류 | 위 상태 그대로. 추가 예약·자금 이동·원장 기록 없음 |
| 같은 주문·다른 입력 | 중복 오류 | 최초 완료한 주문과 모든 기록 그대로 |
| 같은 주문 동시 요청 두 개 | 성공 한 개 / 중복 오류 한 개 | 한 번 예약한 결과만 남음 |

기존 HTTP 오류 매핑은 유지한다. 이 이슈에서 새 HTTP 상태·오류 코드·성공 응답을 만들지 않는다. 여기서 중복 거절이 보장하는 것은 추가 변화 없음이며, 기존 원장의 완전성을 새로 대조하는 기능은 아니다.

<details markdown="1">
<summary>결정 기록 · 검토했던 대안과 실제 답변</summary>

- 처음 검토한 질문: 동일 주문 재요청을 오류로 끝낼지, 같은 입력에 원본 예약을 반환할지.
- AI 추천·근거: 현재 중복 거절을 유지해 #72의 원장 보강에 응답 계약 변경을 섞지 않는다. 두 방식 모두 기록은 한 건이어야 하며 차이는 호출 결과다.
- 첫 실제 답변: **“현재 중복 거절 유지, 추가 자금 이동·기록 없음 (추천)”**.
- 추가 사용자 의견: **“한건을 유지하는게 낫지 않을까?”**.
- 최종 명시 요청: **“중복 오류를 반환하도록 하도록 ㄱㄱ 명세 아직 수정 잘 안되어있는거 ㄱ타아”**.
- 결정: 중복 오류 반환으로 확정. 원본 반환 대안은 이번에 채택하지 않았다. 별도 선택 이유는 추측하지 않는다.
- 반영: 위 처리 표, A05~A07, 서비스 반환 계약과 완료 기준. 입력·복구(#50)와 HTTP 응답 개편(#65)은 제외한다.

</details>

이전에 합의한 원장 보완, 작은 PR, 현재 SQL·동기 DB 트랜잭션·순수 Kotlin 코어/config Bean 조립은 유지한다.

<details markdown="1">
<summary>코드 대조 · 기존 책임과 바꿀 파일</summary>

## 현재 코드에서 확인한 것

| 현재 대상 | 확인한 사실 | 이번 변경 |
| --- | --- | --- |
| `OrderFundingService.reserve` | 도메인 계산 → 예약 insert → 조건부 잔고 reserve. Spring `@Transactional` 사용 | 기존 포트 `LedgerTransactionStore`를 주입하고 같은 예약 업무에 RESERVE append 연결 |
| `PostgresOrderReservationStore.create` | `(market_id, order_id)` PK로 중복 거절. 다른 입력으로도 덮어쓰지 않음 | SQL·중복 거절 유지 |
| `PostgresBalanceStore.reserve` | available 조건과 차감·hold 증가를 `UPDATE … RETURNING`으로 수행 | SQL·금액 계산·동시성 방식 유지 |
| `LedgerTransaction` / `LedgerPosting` | 자산별 차변·대변 균형과 양수 분개를 순수 Kotlin으로 검사 | 기존 타입 재사용 |
| `PostgresLedgerTransactionStore.append` | 헤더 → 항목 순서로 insert. 외부 Spring 트랜잭션에 참여. source ID 고유 제약 사용 | 기존 append 재사용. 중복을 성공으로 바꾸는 catch 추가 없음 |
| `LedgerPersistenceConfig` | ledger 설정 true일 때 동일 DataSource의 세 포트를 명시적 `@Bean`으로 연결 | funding Bean에 기존 원장 포트 전달 |
| `SubmitOrderUseCase` | 마켓 worker의 beforeMatching에서 reserve 성공 후 엔진 실행 | API·매칭·정산 순서 유지 |
| V5/V7 및 `LedgerTransactionType` | 이미 RESERVE와 원장 테이블·고유 제약 존재 | 새 migration·테이블 불필요 |

근거는 [예약 서비스](https://github.com/0Chord/coin-exchange/blob/76cb78d3e87edac7c7b99870ed64841b2e8ff05a/app-api/src/main/kotlin/com/exchange/core/api/order/application/OrderFundingService.kt), [예약 저장](https://github.com/0Chord/coin-exchange/blob/76cb78d3e87edac7c7b99870ed64841b2e8ff05a/app-api/src/main/kotlin/com/exchange/core/api/order/infrastructure/persistence/PostgresOrderReservationStore.kt), [잔고 저장](https://github.com/0Chord/coin-exchange/blob/76cb78d3e87edac7c7b99870ed64841b2e8ff05a/app-api/src/main/kotlin/com/exchange/core/api/ledger/infrastructure/persistence/PostgresBalanceStore.kt), [원장 저장](https://github.com/0Chord/coin-exchange/blob/76cb78d3e87edac7c7b99870ed64841b2e8ff05a/app-api/src/main/kotlin/com/exchange/core/api/ledger/infrastructure/persistence/PostgresLedgerTransactionStore.kt), [Bean 조립](https://github.com/0Chord/coin-exchange/blob/76cb78d3e87edac7c7b99870ed64841b2e8ff05a/app-api/src/main/kotlin/com/exchange/core/api/config/LedgerPersistenceConfig.kt)에서 확인했다.


</details>

## 입력 → 판단 → 저장 → 결과

1. 기존 `reserve` 입력(마켓, 주문 ID, 사용자, BUY/SELL, 가격, 수량, 수수료 snapshot)을 받는다. 가격·수량 검증과 계산은 기존 도메인이 맡는다.
2. 기존 계산 결과로 ACTIVE `OrderReservation`을 만든다. 원장 금액은 새로운 계산식이 아니라 그 예약의 `reservedAmount`다.
3. 예약 상태로 RESERVE 거래를 준비한다. 같은 자산의 AVAILABLE 차변·HOLD 대변으로 균형을 검증한다. 아직 DB는 바뀌지 않는다.
4. 기존 트랜잭션 안에서 **예약 생성 → 잔고 reserve → 원장 append(거래 헤더 + 두 분개)** 순서로 저장한다.
5. 세 저장이 완료되고 호출 경계의 트랜잭션이 커밋되면 기존과 같은 예약 결과를 반환한다. 직접 독립 호출에서는 Bean 프록시가 커밋한 뒤 호출자에게 반환한다.
6. 매칭 엔진은 beforeMatching이 정상 완료한 뒤에만 실행한다. 이 PR은 체결 이벤트·SETTLEMENT·취소 결과를 추가로 만들지 않는다.

### 분개를 어떻게 읽나

| 항목 | 값 | 의미 |
| --- | --- | --- |
| 거래 종류 | `RESERVE` | 주문 자금 예약 사건 |
| 첫 항목 | `USER:{userId}:{assetId}:AVAILABLE`, DEBIT, 예약액 | 사용자 사용 가능 계정을 줄임 |
| 둘째 항목 | `USER:{userId}:{assetId}:HOLD`, CREDIT, 같은 예약액 | 같은 사용자의 예약 계정을 늘림 |
| 자산 | 예약의 `assetId` | BUY는 quote, SELL은 base |
| 합계 | 차변=대변=예약액 | available+hold 총액 보존 |
| 원장 거래 ID·시각 | 새 거래 ID, 예약 처리 시각 | 매칭 이벤트 ID·매칭 시각으로 표현하지 않음 |

BUY 수수료는 최대 예약 수수료를 포함한 **총 예약액**을 함께 HOLD로 옮긴다. 아직 거래소 수수료 수익이 발생한 것은 아니므로 FEE_REVENUE 분개는 없다. SELL은 base 수량을 예약하며 quote 자산의 수수료는 기존 체결 정산에서 처리한다.

업무 금액·예약 상태 전이는 순수 도메인이 검증한다. application의 작은 비공개 함수는 검증된 예약을 기존 원장 타입으로 연결한다. domain-order에 domain-ledger 의존을 새로 넣거나, 별도 factory 계층·저장 플랫폼·범용 분개 엔진을 만들지 않는다.

<details markdown="1">
<summary>상세 · 같은 사건의 식별자와 충돌 처리</summary>

## 같은 사건과 충돌 구분

### source ID의 채택한 형식

같은 시장·주문은 항상 같은 source ID를 사용하고, OPENING/MATCHING/향후 RELEASE와 다른 이름 공간을 사용한다. 사용자·금액·시각·시도 횟수는 키에 넣지 않는다. 다른 입력을 같은 주문 키로 덮어쓰는 방식도 쓰지 않는다.

**구현 선택:** `RESERVE:v1:` + 시장·주문의 정규 바이트 표현에 대한 SHA-256 소문자 hex. 입력은 UTF-8 바이트 두 묶음 각각의 길이를 4-byte big-endian으로 앞에 붙인 순서 `[market 길이][market bytes][order 길이][order bytes]`다. 정규화·trim·대소문자 변환은 하지 않는다.

이렇게 하면 128자 컬럼 안의 고정 75자이고, `(a:b, c)`와 `(a, b:c)`가 단순 콜론 연결로 같은 문자열이 되는 문제를 피한다. 기존 64자 마켓·주문 입력을 새 길이 제한으로 줄이지 않는다. 이 규칙은 한 작은 함수로 두고 별도의 ID 프레임워크는 만들지 않는다. 해시는 실용적 사건 키이며 수학적으로 충돌이 불가능하다고 보장하지 않는다. DB 충돌은 항상 오류로 처리한다.

수용 사례에서는 독립적인 바이트/고정 기대값과 실제 저장 값을 대조한다. 테스트 기대값을 운영 source ID 함수로 계산하지 않는다. 버전·바이트 규칙을 변경하려면 별도 설계가 필요하다.

### 중복과 상충 기록

- **기존 예약이 있는 같은 키:** 합의에 따라 같은 입력도 다른 사용자·금액·방향·정책도 기존 중복 예외로 거절한다. 내용이 같다고 판단하거나 원본 결과를 성공으로 반환하지 않는다. 기존 예약·분개·잔고와 갱신 시각을 보존한다.
- **예약은 없지만 동일 source ID 원장이 있음:** 내용이 같든 다르든 append의 DB 고유 제약 오류를 전파한다. 이번에 만든 예약과 hold를 롤백한다. 기존 원장을 고치거나 정상 완료로 간주하지 않는다.
- **같은 키 동시 요청:** 예약 PK가 최초 성공을 하나로 제한한다. 한 호출만 성공, 다른 호출은 중복 거절. 성공 호출의 자금·원장만 남는다.
- **서로 다른 주문이 같은 잔고를 경쟁:** 기존 조건부 UPDATE가 available 부족을 막는다. 실패한 주문의 예약·분개가 남지 않아야 한다.
- **기존 예약에는 원장이 없는 과거 데이터:** 중복 호출로 새 분개를 보충하거나 초기화하지 않는다. 중복 거절은 원장의 완전성을 확인했다는 뜻이 아니다. 과거 기록 이행·전체 불일치 대조는 이 PR의 결과가 아니다.


</details>

## 롤백과 보장하지 않는 경계

| 실패 위치 | 기대 결과 | 하지 않을 일 |
| --- | --- | --- |
| 계산·예약 객체·분개 구성 | DB 부수효과 없음 | 임의 금액 보정 |
| 예약 insert | 신규 예약·hold·RESERVE 없음 | 기존 주문 덮어쓰기 |
| 잔고 부족·행 부재·잔고 쓰기 실패 | 먼저 만든 예약도 롤백, 원장 없음 | 기존 잔고 자동 생성·추가 지급 |
| 원장 헤더 실패 | 이번 예약·잔고 이동 롤백 | DuplicateKey를 완료로 변환 |
| 첫/둘째 원장 항목 실패 | 원장 헤더·앞선 항목·예약·잔고 모두 롤백 | 부분 원장만 보존 |
| 커밋 응답 유실·커밋 후 예외 | 호출 실패만으로 DB 롤백 여부 단정 불가 | 무조건 다시 자금 이동·새 주문 ID로 자동 재시도 |
| 예약 커밋 뒤 매칭·저장·정산 실패 | 이미 완료한 예약·RESERVE는 유지 | 전체 주문 처리 롤백으로 표현 |
| 3초 응답 대기 초과 | worker가 계속 실행할 수 있음 | 취소·해제·롤백으로 표현 |

`@Transactional`의 기존 REQUIRED 참여 방식을 유지한다. #50이 외부 트랜잭션에 접수 입력을 연결할 수 있어야 하므로 REQUIRES_NEW나 외부 트랜잭션 금지를 추가하지 않는다. 외부 트랜잭션에서 내부 메서드가 반환돼도 실제 커밋은 외부 경계에서 완료된다. 롤백 테스트는 테스트 자체의 자동 롤백에 의존하지 않고 독립 Bean 호출이 끝난 뒤 새 DB 읽기로 확인한다.

현재 worker는 beforeMatching 오류를 요청 실패로 전달하고 엔진을 실행하지 않으며, 이 위치의 모든 오류를 마켓 차단으로 바꾸지는 않는다. 따라서 #72가 **커밋 결과 불명 뒤 마켓 차단·DB 원본 확인·재개를 구현했다는 보장은 하지 않는다.** #50 및 복구 조율에서 연결해야 한다. 실제 네트워크 단절·프로세스 종료도 #67~70에서 검증한다. 본문 중 명확히 롤백되는 오류와 불명확한 커밋 결과를 분리한다.

<details markdown="1">
<summary>검증 사례 · 정상·중복·충돌·롤백</summary>

## 정상·중복·충돌·롤백 수용 사례

아래 숫자는 설명/테스트용 정책이며 운영 마켓 정책 변경이 아니다. 각 테스트는 관련 원장을 source ID와 종류로 좁혀 조회하고, 분개 두 항목의 사용자·자산·방향·금액을 함께 검증한다.

| ID | 초기 상태·입력 | 기대 결과·근거 | 기존/보강 |
| --- | --- | --- | --- |
| A01 기본 BUY | 1,000/0, price=100, quantity=3, scale=0, fee=0 | 700/300, ACTIVE 예약 300, RESERVE 한 건, AVAILABLE D300/HOLD C300, 총액 1,000 | 기존 자금 검사에 원장 검증 추가 |
| A02 수수료 포함 BUY | 1,000/0, price=100, quantity=5, 최대율 10,000ppm | 원금 500+수수료 예약 5=505, 495/505, 분개 두 항목 각 505, FEE_REVENUE 없음 | 기존 기대값 유지·원장 보강 |
| A03 SELL | base 10/0, quantity=3 | base 7/3, 예약 3, base AVAILABLE D3/HOLD C3. quote·수수료 수익 불변 | SELL 원장 보강 |
| A04 전액 예약 | 505/0, A02와 같은 주문 | 0/505, 예약·원장 각각 하나 | 기존 전액 사례 보강 |
| A05 순차 중복 | A01 완료 후 같은 키/입력 재요청 | 확정: 기존 중복 예외, 700/300·예약 한 개·RESERVE 한 건·원본 항목·시각 유지 | 기존 중복 사례 보강, 중복 오류 확정 |
| A06 같은 키 다른 입력 | 완료 키로 사용자·방향·가격·수량·수수료 정책 각각 변경 | 확정: 동일 중복 거절, 기존 데이터 불변. 다른 사용자의 잔고도 불변 | 보강, 중복 오류 확정 |
| A07 같은 키 동시 요청 | 서로 다른 DB 연결로 같은 주문 두 호출 | 확정: 성공 1/중복 1, 자금 이동 한 번/RESERVE 한 건. 상충 입력은 먼저 성공한 입력만 남음 | 동시 중복 보강, 중복 오류 확정 |
| A08 서로 다른 주문 잔고 경쟁 | 1,000/0에서 서로 다른 주문이 각각 707 요구 | 성공 1/부족 1, 293/707, 승자 예약·RESERVE만 존재 | 기존 경쟁 사례 보강 |
| A09 잔고 부족·행 부재 | 요구 505, available=400 또는 잔고 행 없음 | 기존 부족/행 부재 예외, 신규 예약·RESERVE·항목 0, 기존 DB 불변 | 기존 롤백 사례 보강 |
| A10 원장 source 충돌 | 예약 없는 키의 source ID로 같은/상충 원장 미리 저장 | append 오류, 이번 예약·hold 롤백, 사전 원장 완전 보존. 성공 변환 없음 | 보강 |
| A11 저장 실패 네 지점 | 예약 쓰기, 잔고 갱신, 원장 헤더, 둘째 분개에서 실제 DB 실패 | 각각 호출 실패, 신규 예약·헤더·항목 없고 잔고·updated_at 원상. unrelated 사전 원장 보존 | 보강 |
| A12 source ID 경계 | 동일 키 반복, 다른 주문/마켓, 콜론 포함 쌍, 64자·Unicode 입력 | 동일 키 같은 키; 서로 다른 정규 입력 예제 다른 키; 75자 저장 가능; DB 제한 위반은 오류이며 성공 아님 | 작은 함수·실제 DB 연결 검증 보강 |
| A13 커밋 후 매칭 | 기존 제출 → 체결/부분 체결 → 취소 HTTP·DB 테스트 | 금액·수수료·API 유지, RESERVE 증가만 추가, SETTLEMENT 기존 분개/결과 유지. #73 전까지 취소 RELEASE 추가 없음 | 기존 회귀를 종류별로 보강 |
| A14 독립 마켓 키 | 같은 order ID를 서로 다른 market에서 예약 | 예약·RESERVE 두 개, source 서로 다름. 자금은 두 예약액의 합만 이동 | 보강 |
| A15 계산/분개 사전 거절 | 기존 불가능한 금액·overflow 입력 | DB 변화 없음, 기존 도메인 거절 유지 | 기존 도메인 사례 재사용 |

DB 실패 주입은 작은 테스트 전용 constraint/trigger 또는 기존 저장 포트의 제한된 테스트 대역을 사용할 수 있다. 최종 확인은 실제 PostgreSQL과 Spring 프록시 트랜잭션에서 하며, 특히 둘째 분개 실패는 첫 항목 insert까지 실제로 수행된 뒤 실패하도록 한다. 대역이 예외를 던진 것만으로 DB 원자성을 입증했다고 표시하지 않는다. 테스트 전용 DDL은 해당 사례 뒤 정리하고 운영 migration에 넣지 않는다.

커밋 응답 유실은 위 A11의 ‘확정 롤백’ 사례에 섞지 않는다. 실제 commit 전후를 통제하지 않은 포트 예외 주입을 네트워크 장애 검증이라고 부르지 않는다.


</details>

## 한 작은 PR 안의 구현·검토 단위

### 1. 기존 예약 결과를 두 분개로 연결

첫 테스트는 A01·A02다. 현재 실패해야 할 이유는 ‘잔고·예약은 생기지만 RESERVE가 없다’다. 검증된 예약 금액을 사용해 원장을 만들고 저장하도록 서비스와 기존 Bean 연결을 바꾼다. 같은 PR에서 동작과 테스트를 함께 읽는다.

### 2. 실패와 중복에서 새 돈·부분 기록이 남지 않음

확정한 중복 오류 반환에 맞춰 A05~A12·A14를 보강한다. 원장 append를 기존 트랜잭션에 참여시키며 오류를 성공으로 바꾸지 않는다. 동일 주문·동시 잔고 경쟁을 실제 별도 연결로 검사한다.

### 3. 기존 체결·취소 검증을 종류별로 유지

E2E에는 현재 원장 전체가 SETTLEMENT 한 건이라고 보는 검사와 전체 항목 조회가 있다. RESERVE가 생겼으므로 **SETTLEMENT 기대값은 종류/source로 좁혀 유지하고 RESERVE는 따로 검증**한다. 단순히 전체 예상 개수만 늘려 분개 검사를 약하게 하지 않는다. 정산 실패 전 생성된 정상 RESERVE는 남아야 하므로 ‘원장 0건’ 대신 정산 호출 전 스냅샷과 비교하거나 해당 SETTLEMENT가 없음/예약 원장이 그대로임을 확인한다.

세 단위는 같은 결과의 생성·실패·기존 연동 검증이다. 별도 3개 PR이나 전체 복구 기능을 뜻하지 않는다.

### 예상 파일 범위

- 제품: `app-api/.../order/application/OrderFundingService.kt`, `app-api/.../config/LedgerPersistenceConfig.kt`. 작은 비공개 분개·source ID 함수는 서비스 안에 두는 추천안이다.
- 테스트: 기존 `OrderFundingServiceTest`, 설정 검사, `OrderLifecycleE2ETest`, 영향받는 `TradeSettlementServiceTest`와 소수 unit 사례. 테스트를 흉내 내는 별도 실행 플랫폼을 추가하지 않는다.
- 설명: 이 명세와 구현 후 짧은 흐름/검증 근거. 오래된 ‘예약 원장 미연결’ 설명을 현재 동작으로 수정한다.
- 필요 없다고 확인한 변경: schema, 저수준 BalanceStore, 기존 SETTLEMENT 계산, Controller JSON, domain-order 의존 방향, 원장 read 포트, CLI/서버 시작 훅, 추가 린트 설정.

<details markdown="1">
<summary>구현 인계 · 검증 명령과 완료 기준</summary>

## 검증과 완료 기준

**구현 검증 명령**이다. 실제 결과와 최종 리뷰 상태는 구현 읽기 문서에서 구분해 기록한다.

```sh
cd /Users/0chord/.codex/worktrees/issue72-reservation-spec/exchange-core
./gradlew :app-api:test --tests '*OrderFundingServiceTest' --tests '*LedgerPersistenceConfigurationTest'
./gradlew :app-api:test --tests '*OrderLifecycleE2ETest' --tests '*TradeSettlementServiceTest'
./gradlew ktlintCheck --continue
./gradlew build :architecture-tests:verifyArchitectureReport --continue
```

실제 구현을 다른 폴더에서 시작한다면 그 절대 경로와 브랜치를 먼저 안내하고 명령의 cd를 바꾼다. 구현 브랜치 추천은 `feat/reservation-ledger/72`다. 명세 브랜치에서 `feat/reservation-ledger/72` 구현 브랜치를 만들었으며, 위 작업 폴더에서 로컬 구현·검증을 마쳤다.

- [x] 같은 주문 재요청은 중복 오류를 반환하고, 예약·잔고·RESERVE 한 건을 보존하도록 확정했다. A05~A07에 반영했다. 다른 완료 기준의 실행·독립 검토 근거는 연결한 구현 읽기 문서에 기록했다.
- [x] A01~A15의 범위 내 기대값·거절·보존 상태를 검토하고 실제 PostgreSQL 근거를 남긴다.
- [x] 세 저장의 확정 성공과 저장 본문 오류의 확정 롤백, 커밋 결과 불명을 구분한다.
- [x] 기본 `@Bean` 조립과 REQUIRED 참여·현재 SQL·금액/수수료 규칙을 유지한다.
- [x] 676개라는 이전 숫자를 고정하지 않고 새 전체 검사·P01~P09·ktlint 결과를 기록한다. 실제 skip/미실행/환경 실패를 성공으로 취급하지 않는다.
- [x] 변경한 테스트가 RESERVE와 SETTLEMENT를 구분하며 기존 정산·취소 금액 검증을 약하게 만들지 않는다.
- [x] 사람에게 1,000→700/300의 계정 이동과 둘째 분개 실패의 결과를 코드·테스트에 연결해 설명한다. 읽기 문서 생성과 사람의 판단은 따로 기록한다.
- [x] 한 PR에서 검토 가능한 이슈 범위를 유지하고 #50 입력·복구·순번을 넣지 않는다.


</details>

## 후속으로 넘길 것

| 이슈 | #72 결과를 어떻게 사용하나 |
| --- | --- |
| #50 | 예약·hold·RESERVE에 주문 입력·접수 순서를 같은 트랜잭션으로 연결. 커밋 결과 불명/접수 후 실패 차단을 연결 |
| #73 | 성공 취소의 남은 HOLD → AVAILABLE과 RELEASE를 원자 기록. 정산 내부 반환과 구분 |
| #74 | OPENING·RESERVE·SETTLEMENT·RELEASE 합계와 DB 잔고·활성 예약을 읽기 전용 대조 |
| #65 | 주문 재요청에 어떤 HTTP 결과·처리 상태를 반환할지 결정 |
| #67~70 | 실제 프로세스 강제 종료·응답 유실·재시작·재복구 검증 |

이 문서는 최신 코드·기존 테스트에 근거한 합의 명세다. 구현과 실행 근거는 연결한 구현 읽기 문서에 기록한다. PR 생성·이슈 종료·보드 상태 변경은 아직 하지 않았다. 중복 처리 선택은 실제 답변으로 확정했으며, source ID 구성과 작은 비공개 함수 배치는 구현에서 채택한 기술안이다. 사용자 답변에 근거한 중복 거절 합의와 구현 선택을 구분한다.
