# 주문 접수 한 건을 최신 메모리 상태에 반영하기 · #85

**여러 마켓이 같은 사용자의 돈을 함께 보며, 승인된 주문의 잔고·예약·원본·정책·원장·대기 순서를 한 상태 객체에 남긴다.** 로컬 구현·검증을 마쳤다. 이 문서는 현재 소스의 동작과 테스트 의도를 설명하며, 사용자 이해·승인 완료를 뜻하지 않는다.

예를 들어 USDT **1,000 → BTC 주문에 600 예약 → 사용 가능 400**이 된다. 이어서 SUI 주문이 500을 요구하면 **100 부족으로 거절**한다. 거절 때문에 돈·대기 주문·승인 순번이 늘어나지 않는다. 이후 같은 주문을 400으로 줄여 제출하면 두 번째 승인으로 처리한다.

이번에 자세히 볼 지점은 **반영 전 오류와 반영 중 오류의 차이**다. 첫 변경 전 오류는 쓰기 전이므로 기존 상태를 보존하고 같은 객체를 계속 쓸 수 있다. 반영 중 오류는 잔고만 바뀌고 대기 주문은 없는 부분 상태를 남길 수 있어, 롤백을 보장하는 대신 같은 객체의 후속 접수와 정상 조회를 차단한다.

이전 계산기는 넘겨받은 잔고로 변경 후보만 만들었다. 이제 `OrderAdmissionState`가 최신 잔고를 직접 선택하고, 기존 계산기로 후보를 만든 뒤 실제 메모리 상태에 반영한다.

읽을 전체 흐름은 다음과 같다.

1. 초기 잔고를 소유하고 중복 키·설명 없는 초기 예약액을 거절한다.
2. 차단 상태와 이미 승인된 주문 키를 먼저 확인한다.
3. BUY는 quote, SELL은 base의 최신 잔고를 골라 기존 계산기를 호출한다.
4. 정상 거절이면 상태를 유지한다. 승인 후보면 연결·사용한 ID·다음 순번을 확인한다.
5. 첫 변경 전에 차단 표시를 켜고 잔고·접수 기록·ID·순번을 반영한다. 모두 끝나야 차단을 풀고 승인한다.
6. 정상 조회는 외부 수정과 후속 변경에 영향받지 않는 관찰값을 반환한다. 반영 중 실패한 객체는 후속 접수와 정상 조회를 막는다.

<details markdown="1">
<summary><strong>설명 기준 · 현재 상태와 변경 파일의 역할</strong></summary>

작성 기준일은 2026-10-16이다. 브랜치는 `feat/trading-admission-state/85`, 기준 커밋은 `105bbc146303f081032c6885bfd3f94949fc7b45`이다. 구현 위치는 `/Users/0chord/.codex/worktrees/issue85-admission-state/exchange-core`다. 기준 커밋 위의 미커밋 제품 변경과 새 테스트를 읽었다.

명세 근거는 [#85](https://github.com/0Chord/coin-exchange/issues/85)와 설계 작업 폴더의 `engineering/order-admission-spec.md` 3번이다. 사용자 합의는 ‘반영 중 내부 오류 뒤 같은 상태 객체의 후속 주문 차단’과 ‘없는 잔고는 0으로 보고 유효 주문을 자금 부족으로 정상 거절’이다. 이 설명에서 새 선택을 사용자 합의로 추가하지 않는다.

| 파일 | 성격과 이번 역할 |
| --- | --- |
| [OrderAdmissionState.kt](../domain-trading/src/main/kotlin/com/exchange/core/trading/OrderAdmissionState.kt#L19) | 새 제품 코드. 최신 잔고·승인 기록·ID 사용 이력·순번·차단 상태를 소유하고 접수·관찰을 제공한다. |
| [OrderAdmissionResult.kt](../domain-trading/src/main/kotlin/com/exchange/core/trading/OrderAdmissionResult.kt#L24) | 기존 제품 결과 타입. 정상 업무 거절 이유에 `DUPLICATE_ORDER`를 추가한다. 계산기의 `Calculated`와 상태의 `Accepted`는 계속 구별한다. |
| [OrderAdmissionStateTest.kt](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L38) | 새 순수 도메인 테스트. 실제 계산기와 값 객체를 연결한다. 내부 한계·실패 상태는 테스트에서만 private 필드 접근으로 준비한다. |
| [admission-state.md](admission-state.md) | 이 문서. 제품 실행에는 참여하지 않으며 소스와 테스트의 읽기 순서를 제공한다. |

기존 협력자도 그대로 사용한다. [OrderAdmissionCalculator.calculate](../domain-trading/src/main/kotlin/com/exchange/core/trading/OrderAdmissionCalculator.kt#L32)는 입력 검증·예약 후보·정상 거절을 맡고, [OrderAdmissionCandidate](../domain-trading/src/main/kotlin/com/exchange/core/trading/OrderAdmissionCandidate.kt#L19)는 원본·잔고·예약·원장을 연결한다. [Balance.reserve](../domain-ledger/src/main/kotlin/com/exchange/core/ledger/Balance.kt#L39)는 불변 잔고 계산을, [LedgerTransaction](../domain-ledger/src/main/kotlin/com/exchange/core/ledger/LedgerTransaction.kt#L24)은 자산별 분개 균형과 분개 목록 보호를 맡는다. 새 상태가 금액·수수료·사건 ID 공식을 다시 만들지 않는다.

읽은 파일 내용의 SHA-256은 아래와 같다. 소스가 바뀌면 이 값과 줄 링크·설명을 다시 맞춰야 한다.

| 파일 | SHA-256 |
| --- | --- |
| `OrderAdmissionState.kt` | `2cebf3d3589d6d0a23a08ce74fe1b073186256c656d22f12ce82355a1b476447` |
| `OrderAdmissionResult.kt` | `5c1fa1d3d627fe85b02028b7af15b8df3d2afd6012f7e8f97a2dca2c81674bd1` |
| `OrderAdmissionStateTest.kt` | `9ec672867be559ec5198bf973d67907eb0a8f3b8af1fb14397009a80a4fa6dff` |

**실행 근거:** 아래 최종 검증 기록은 구현 담당자가 실행한 XML 결과와, 작성 대화를 상속하지 않은 별도 checkout의 독립 실행을 구분한다. 문서 설명을 작성한 사실과 실제 테스트 실행, 사람의 이해·판단은 서로 다른 상태다.

구현 담당자가 확인한 첫 두 테스트의 TDD 진행은 **API 미구현으로 컴파일 실패 → 최소 시그니처와 TODO에서 `NotImplementedError` 2건 → 실제 구현 후 통과**다. 앞의 두 실패를 잔고·기록 assertion이 잘못된 제품 동작을 잡은 실패로 표현하지 않는다. 다른 입력 거절 테스트의 초안에는 빈 `OrderId`가 생성자에서 먼저 막히는 준비 오류가 있었다. NUL을 포함한 ID로 고쳐 실제 계산기의 `INVALID_ORDER_IDENTIFIER` 경계에 도달하도록 했으며, 현재 파일의 295행이 그 입력이다. 이 수정 뒤 실행 통과는 구현 담당자의 기록이고, 이 문서 작성자가 별도로 실행한 결과는 아니다.

</details>

<details markdown="1">
<summary><strong>이전 → 이후 · 누가 최신 잔고와 기록을 책임지나</strong></summary>

이전의 계산기는 ‘사용 가능 1,000 USDT에서 600 USDT를 예약한다면 사용 가능 400 USDT·예약 600 USDT’라는 후보를 반환했다. 호출자가 다시 옛 사용 가능 1,000 USDT를 넘기면 다른 주문의 500 USDT도 후보로 계산할 수 있었다. 계산 자체가 순수하기 때문에 후보 생성만으로 중복 사용을 막았다고 볼 수 없다.

이번에는 [State.admit](../domain-trading/src/main/kotlin/com/exchange/core/trading/OrderAdmissionState.kt#L79)에 주문·마켓 정의·전체 수수료 정책·명시적 원장 거래 ID·사건 시각을 넘긴다. 현재 잔고나 외부 후보를 넘기는 인자는 없다. 객체가 소유한 `(userId, assetId)` 잔고에서 다음 후보를 계산하므로, 같은 사용자의 BTC-USDT와 SUI-USDT BUY가 같은 USDT를 사용한다. 다른 사용자의 USDT는 다른 잔고 키다.

승인 기록은 [Record(sequence, candidate)](../domain-trading/src/main/kotlin/com/exchange/core/trading/OrderAdmissionState.kt#L53)다. 이미 검증한 후보에 원본·정책·예약·원장이 있으므로 별도 원본 목록을 여러 개 유지하지 않는다. 승인 기록을 넣는 순서가 전체 대기 순서가 되고, 마켓별 조회는 그 상대 순서를 유지한다.

이 객체는 한 실행자가 순서대로 호출한다. 락·동시 실행기·HTTP 진입점·DB·Kafka·Aeron 연결은 없다. 여기서 승인은 메모리 접수 반영 완료이며 체결·영구 보존·장애 복구 완료가 아니다. 요청 키와 최초 승인/거절 결과 재사용은 후속 #86의 책임이다.

</details>

<details markdown="1">
<summary><strong>전체 흐름 1 · 시작 상태와 정상 승인</strong></summary>

**기대:** 설명할 예약이 없는 새 상태는 초기 hold=0에서 시작하며, 승인된 접수 한 건의 모든 업무 기록이 같은 원본을 가리켜야 한다.

1. [생성자 초기화](../domain-trading/src/main/kotlin/com/exchange/core/trading/OrderAdmissionState.kt#L43)는 입력 목록을 순회해 소유 map에 불변 Balance를 넣는다. 같은 사용자·자산 키가 두 번 있거나 초기 hold가 0이 아니면 `IllegalArgumentException`으로 객체 생성을 중단한다. 초기 available=0은 허용하며, 초기화가 자금을 지급하거나 OPENING 원장을 만들지는 않는다.
2. [접수 시작](../domain-trading/src/main/kotlin/com/exchange/core/trading/OrderAdmissionState.kt#L86)에서 차단 여부를 확인하고, 승인된 `(marketId, orderId)`가 이미 있는지 확인한다. 업무 키에는 userId를 넣지 않는다.
3. [잔고 선택과 계산](../domain-trading/src/main/kotlin/com/exchange/core/trading/OrderAdmissionState.kt#L90)은 BUY의 quote 또는 SELL의 base 잔고를 고른다. 없으면 판단에만 해당 자산의 사용 가능 금액 0·예약 금액 0인 임시 Balance를 쓴다. 실제 계산기는 기존 주문 조건·문맥을 먼저 검증한다.
4. [반영 전 검사](../domain-trading/src/main/kotlin/com/exchange/core/trading/OrderAdmissionState.kt#L96)는 후보가 현재 잔고·주문·마켓·전체 정책에 연결되는지, 원장 거래 ID·사건 ID가 사용되지 않았는지 확인한다. `Math.addExact(lastSequence, 1)`로 다음 순번을 만든다. 이 단계까지 소유 업무 상태는 바뀌지 않는다.
5. [반영 구간](../domain-trading/src/main/kotlin/com/exchange/core/trading/OrderAdmissionState.kt#L107)에서 차단 표시를 먼저 켠다. 새 잔고, 승인 기록, 거래 ID, 사건 ID, 마지막 순번을 넣고 끝에서 차단을 푼다. `Accepted` 결과는 미리 구성하지만 이 구간을 끝내야 반환한다. 도중에 외부 작업이나 콜백을 호출하지 않는다.

BUY는 기존 최대 maker/taker 요율의 수수료까지 quote 자산에 예약한다. SELL은 base 수량만 예약하고 수수료 예약은 0이다. SELL의 실제 체결 대금·수수료 정산은 이번 접수 반영의 범위가 아니다.

**연결 테스트:** 01 정상 BUY, 02 공통 잔고, 03 BUY 수수료, 04 SELL, 08 마켓별 주문 ID, 09 사용자 분리, 18 대기 순서.

</details>

<details markdown="1">
<summary><strong>전체 흐름 2 · 정상 거절은 기록·돈·순번을 소비하지 않는다</strong></summary>

**기대:** 사용자가 수정할 수 있는 업무상 거절은 정상 결과로 돌려주고, 다른 유효 주문은 계속 받을 수 있어야 한다.

이미 승인한 주문 키면 [중복 거절](../domain-trading/src/main/kotlin/com/exchange/core/trading/OrderAdmissionState.kt#L87)을 반환한다. 같은 사용자·같은 내용뿐 아니라 수량이나 사용자가 달라도 재승인하지 않는다. 결과에는 거절 이유와 선택적 부족 정보만 있어 기존 승인 원본을 돌려주지 않는다.

중복이 아니면 기존 계산기를 호출한다. 계산기가 [Rejected를 반환한 지점](../domain-trading/src/main/kotlin/com/exchange/core/trading/OrderAdmissionState.kt#L94)에서 그대로 상태의 거절 결과로 옮긴다. 잘못된 주문 식별자, 미지원 주문 조건, 0 수량, 표현할 수 없는 예약액, 잔고 부족의 구별을 유지한다. 상태 객체가 없는 잔고를 보고 계산기보다 먼저 부족을 반환하지 않는다.

없는 잔고의 유효 주문은 available=0으로 계산되므로 부족 거절이다. 임시 0 잔고를 소유 map에 저장하지 않는다. 거절한 업무 키·거래 ID·사건 ID·순번도 저장하지 않는다. 따라서 500 부족으로 거절한 주문을 400으로 줄여 다시 보내면 새로 계산할 수 있다. 이것은 최초 거절을 영구 재사용하는 안전 재시도 계약이 아니다.

원장 ID 사용 이력 검사는 승인 후보가 생긴 뒤에 있다. 이미 사용한 거래 ID라도 이번 주문이 먼저 잔고 부족이면 부족 결과가 유지된다. 원장 ID 충돌이 사용자가 보는 부족 이유를 덮어쓰지 않는다.

**연결 테스트:** 02 거절 후 다음 승인, 05·06 없는 잔고, 07 업무 중복, 11 부족 판단 순서, 12 기존 거절 유지.

</details>

<details markdown="1">
<summary><strong>전체 흐름 3 · 반영 전 오류와 반영 중 오류는 이후 행동이 다르다</strong></summary>

| 멈춘 위치 | 호출자가 받는 것 | 상태와 이후 접수·조회 |
| --- | --- | --- |
| 초기화 중 중복 잔고·초기 hold | `IllegalArgumentException` | 유효한 상태 객체 생성이 끝나지 않는다. |
| 첫 변경 전 잘못된 서버 문맥 | 계산기가 던지는 예외 | 소유 업무 상태 보존. 객체 차단 표시를 켜지 않는다. |
| 첫 변경 전 원장 ID·사건 ID 사용 이력 충돌 | `IllegalStateException` | 소유 업무 상태 보존. 차단되지 않는다. |
| 첫 변경 전 다음 순번 범위 초과 | `ArithmeticException` | 순번을 되감지 않고 상태 보존. 차단되지 않는다. |
| 첫 변경 뒤 반영 완료 전 실패 | 원래 던져진 예외 또는 `Error` | 승인 반환 없음. 차단 표시가 남아 후속 접수와 정상 조회를 막는다. 부분 변경의 롤백을 주장하지 않는다. |
| 이미 차단된 객체를 접수·정상 조회에 재사용 | `IllegalStateException` | [checkUsable](../domain-trading/src/main/kotlin/com/exchange/core/trading/OrderAdmissionState.kt#L129)에서 더 진행하지 않는다. `isBlocked` 진단은 가능하다. |

핵심은 [첫 변경 전의 차단 표시](../domain-trading/src/main/kotlin/com/exchange/core/trading/OrderAdmissionState.kt#L107)다. `try/catch`로 오류를 정상 거절로 바꾸는 구조가 아니다. 반영 구간이 끝나기 전에 어떤 던짐이든 실행을 빠져나가면 마지막의 `isBlocked=false`에 도달하지 않아 차단 상태가 남는다. 복구를 위해 차단을 풀거나 잔고를 수정하는 공개 API는 없다.

20번 테스트는 실제 balances map의 쓰기를 감싸 **실제 새 잔고를 쓴 직후** 지정한 실패를 던지게 한다. 그러므로 반영 중 실패 경계를 확인한다. 이 검사는 SQL 롤백·프로세스 종료·클러스터 재개를 검증하지 않는다.

**연결 테스트:** 10 거래 ID 충돌, 13 서버 문맥, 14 초기화, 16 순번 한계, 17 사건 ID 이력, 20 반영 중 실패와 재사용 차단.

</details>

<details markdown="1">
<summary><strong>전체 흐름 4 · 조회값·키·사건 ID를 보존한다</strong></summary>

[snapshot](../domain-trading/src/main/kotlin/com/exchange/core/trading/OrderAdmissionState.kt#L119)은 잔고 목록·승인 대기 기록·마지막 승인 순번을 반환한다. [pendingOrders](../domain-trading/src/main/kotlin/com/exchange/core/trading/OrderAdmissionState.kt#L124)는 한 마켓의 기록만 전체 순서에서 고른다. 둘 다 차단 여부를 먼저 확인한다.

[immutableList](../domain-trading/src/main/kotlin/com/exchange/core/trading/OrderAdmissionState.kt#L131)는 현재 목록을 복사한 뒤 수정 불가 목록으로 감싼다. 내부 map의 실시간 view를 내보내지 않으므로 이후 승인으로 예전 관찰값이 바뀌지 않는다. 후보·잔고·예약은 불변 값이고, 기존 원장도 분개 목록을 복사·보호한다.

`BalanceKey(userId, assetId)`와 `OrderKey(marketId, orderId)`는 각각 두 필드다. 문자열을 콜론으로 이어붙인 키가 아니다. 따라서 `(a:b, c)`와 `(a, b:c)`가 섞이지 않는다. 서로 다른 마켓의 같은 주문 ID는 별개지만 같은 마켓의 주문 ID는 사용자 변경으로 재사용할 수 없다.

원장 거래 ID는 호출자가 명시한다. 사건 ID는 기존 계산기의 [reserveSourceId](../domain-trading/src/main/kotlin/com/exchange/core/trading/OrderAdmissionCalculator.kt#L149)가 마켓·주문의 UTF-8 바이트 길이와 원문을 SHA-256으로 만들어 `RESERVE:v1:` 뒤에 붙인다. 새 상태는 그 결과를 보존하고 사용 이력만 확인한다. 사건 ID는 75자로, 외부 거래 ID의 64 code point 제한을 다시 적용하지 않는다. 새로운 trim·정규화·사건 ID 공식을 넣지 않는다.

**연결 테스트:** 08 사건 ID 구별과 길이, 15 목록 소유권·이전 관찰값, 18 마켓별 순서, 19 콜론 키 구별.

</details>

## 테스트를 따라 읽기

모두 `OrderAdmissionStateTest`의 순수 메모리 도메인 테스트다. 실제 `OrderAdmissionCalculator → OrderReservationCalculator → BuyOrderFundingQuoteCalculator → TradingFeeReserveCalculator`를 사용한다. DB·HTTP·브로커·동시 실행은 없다. **아래 20개는 `@Test` 메서드 정의 수다. 실제 실행에서는 이 20개가 모두 통과했고, 테스트 안의 반복 입력은 별도 JUnit 테스트 수에 더하지 않는다.** 테스트 소스의 반복 입력은 별도로 적었다. 파라미터 테스트 어노테이션으로 독립 수집되는 사례는 없다.

공통 준비는 [39행](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L39)과 [order/balance/admit helper](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L482)다. 기본 주문은 `user-1`, BTC-USDT, BUY LIMIT/GTC, 가격 100 USDT, 수량 3 BTC, 수수료 0이다. 시각은 고정된 `2026-10-16T00:00:00Z`, 기본 거래 ID는 `ledger-주문ID`다.

표시 단위는 **USDT scale 6, BTC scale 8**이다. 1,000 USDT=`1_000_000_000`, 가격 100 USDT=`100_000_000`, 3 BTC=`300_000_000`이다. SUI 예시도 테스트에서 base scale 8로 준비한다. 이는 테스트의 명시적 마켓 정의다.

| 번호·위치 | 확인하려는 동작 | 테스트 내 반복 | 설명 상태 |
| --- | --- | --- | --- |
| [01 · 55행](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L55) | BUY 승인 한 건의 전체 연결 | 없음 | 아래 Given/When/Then |
| [02 · 95행](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L95) | 공통 잔고·거절 뒤 승인 순번 | 연속 접수 3단계 | 아래 Given/When/Then |
| [03 · 125행](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L125) | BUY 최대 수수료 예약 | 없음 | 아래 Given/When/Then |
| [04 · 144행](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L144) | SELL은 BTC만 예약 | 없음 | 아래 Given/When/Then |
| [05 · 171행](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L171) | 없는 BTC 잔고의 부족 거절 | 거절 뒤 BUY 승인 | 아래 Given/When/Then |
| [06 · 189행](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L189) | 빈 초기 상태의 BUY 거절 | 없음 | 아래 Given/When/Then |
| [07 · 205행](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L205) | 사용자·내용을 바꿔도 같은 업무 키 중복 | 중복 입력 3개 | 아래 Given/When/Then |
| [08 · 220행](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L220) | 다른 마켓의 같은 주문 ID | 승인 2개 | 아래 Given/When/Then |
| [09 · 237행](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L237) | 사용자별 자금 분리 | 사용자 2명 | 아래 Given/When/Then |
| [10 · 262행](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L262) | 거래 ID 충돌은 반영 전 오류 | 오류 뒤 정상 승인 | 아래 Given/When/Then |
| [11 · 276행](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L276) | 부족 결과가 거래 ID 이력 검사보다 먼저 | 없음 | 아래 Given/When/Then |
| [12 · 292행](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L292) | 기존 입력 거절 이유 유지 | 초기 상태 2종 × 입력 5종 | 아래 Given/When/Then |
| [13 · 314행](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L314) | 서버 문맥 오류를 정상 거절로 숨기지 않음 | 초기 상태 2종 × 오류 3종 | 아래 Given/When/Then |
| [14 · 334행](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L334) | 중복 초기 잔고·초기 hold 제한 | 오류 생성 2종 + 정상 0 잔고 | 아래 Given/When/Then |
| [15 · 345행](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L345) | 입력 목록·반환 목록·이전 관찰값 보호 | 수정 시도 4종 | 아래 Given/When/Then |
| [16 · 377행](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L377) | Long 최대 승인 순번과 다음 범위 초과 | 내부 한계값 준비 | 아래 Given/When/Then |
| [17 · 389행](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L389) | 사건 ID 이력 충돌 | 내부 이력 준비 | 아래 Given/When/Then |
| [18 · 408행](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L408) | 마켓별 상대 대기 순서 | 승인 3개 | 아래 Given/When/Then |
| [19 · 422행](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L422) | 콜론이 있는 업무 키의 구별 | 튜플 2개 | 아래 Given/When/Then |
| [20 · 441행](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L441) | 반영 중 실패 뒤 객체 차단 | 예외·Error 2종 | 아래 Given/When/Then |

<details markdown="1">
<summary><strong>01 · BUY 300을 승인하면 한 주문의 돈과 기록이 함께 남는다</strong></summary>

**목적:** 잔고만 바꾸거나 대기 주문만 남기는 불완전한 접수를 막는다. 심벌은 `BUY 300을 반영하면 잔고와 예약 원본 정책 원장 대기 순번이 함께 남는다`다.

- **Given — 준비:** 사용 가능 1,000 USDT·예약 0 USDT, 기본 BUY 3 BTC × 100 USDT, 수수료 0을 준비한다.
- **When — 실행:** 실제 `state.admit`를 한 번 실행하고 승인 기록과 `snapshot`을 얻는다.
- **Then — 실제 확인:** 승인 순번·마지막 순번은 1, 잔고는 사용 가능 700 USDT·예약 300 USDT, 대기는 그 승인 한 건이다. 후보의 원본·마켓·전체 정책·이전/다음 잔고, 예약의 주문·사용자·마켓·ACTIVE·예약액·잔량, 원장의 거래 ID·시각·RESERVE 종류와 AVAILABLE 차변/HOLD 대변 각 300 USDT를 비교한다. 초기 Balance도 사용 가능 1,000 USDT·예약 0 USDT 그대로다.

**근거:** [준비·호출](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L55), [승인·잔고·기록 assertion](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L63), [원장 assertion](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L81). 순차 호출의 메모리 반영을 확인하며 DB 커밋·동시성 보장은 아니다.

</details>

<details markdown="1">
<summary><strong>02 · BTC에 쓴 돈은 SUI에서도 사용할 수 없다</strong></summary>

**목적:** 마켓별로 옛 잔고를 따로 쓰거나 거절이 승인 순번을 소비하는 일을 막는다. 심벌은 `다른 마켓도 바뀐 공통 잔고를 사용하고 거절은 순번을 소비하지 않는다`다.

- **Given — 준비:** 사용 가능 1,000 USDT·예약 0 USDT와 BTC-USDT·SUI-USDT 마켓을 준비한다.
- **When 1:** BTC 주문 600을 승인하고 그 뒤 관찰값을 보관한다.
- **When 2 / Then 2:** SUI 주문 500을 제출하면 available 400·required 500·shortfall 100의 부족 결과다. 전체 관찰값은 직전과 같다.
- **When 3 / Then 3:** 같은 SUI 주문의 요구액을 400 USDT로 줄여 같은 기본 거래 ID로 다시 제출한다. 승인 순번·마지막 순번은 2다. 후보의 이전 잔고는 사용 가능 400 USDT·예약 600 USDT, 다음은 사용 가능 0 USDT·예약 1,000 USDT이며 원본·마켓도 수정된 SUI 주문과 같다. 전체 대기는 첫 승인과 둘째 승인이고, 각 마켓 조회에는 해당 승인만 있다.

**근거:** [첫 승인·부족 거절](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L95), [후속 승인과 최신 잔고·순번·대기 assertion](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L110). 500 거절 기록을 영구 재사용하는 요청 캐시 검사가 아니다.

</details>

<details markdown="1">
<summary><strong>03 · BUY에는 최대 수수료 0.6 USDT도 함께 예약한다</strong></summary>

**목적:** 주문 대금만 묶고 체결 수수료 자금을 빠뜨리는 일을 막는다. 심벌은 `수수료 포함 BUY는 최대 요율 0점2퍼센트까지 함께 예약한다`다.

- **Given:** 사용 가능 1,000 USDT·예약 0 USDT, 대금 300 USDT, maker 0.1%·taker 0.2% 정책을 준비한다.
- **When:** 해당 정책으로 기본 BUY를 접수한다.
- **Then:** 잔고는 사용 가능 699.4 USDT·예약 300.6 USDT, 주문 예약액은 300.6 USDT, 최초 수수료 예약은 0.6 USDT다. 후보와 예약의 전체 정책은 적용 정책과 같고, 두 원장 항목의 금액은 각각 300.6 USDT다.

**근거:** [테스트·정책 준비](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L125), [실제 assertion](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L131). 기대값은 고정 예시 숫자이며 운영 계산기로 기대 금액을 다시 만들지 않는다.

</details>

<details markdown="1">
<summary><strong>04 · SELL은 USDT를 만들지 않고 팔 BTC만 예약한다</strong></summary>

**목적:** 매도 접수에 quote 잔고를 요구하거나 수수료를 BTC에 더해 묶는 일을 막는다. 심벌은 `SELL은 USDT 항목 없이도 BTC만 예약하고 수수료 예약은 0이다`다.

- **Given:** 사용 가능 3 BTC·예약 0 BTC만 준비하고 USDT 항목은 없다. SELL 2 BTC와 양수 수수료 정책을 준비한다.
- **When:** 매도 주문을 접수한다.
- **Then:** 전체 잔고 목록은 사용 가능 1 BTC·예약 2 BTC인 한 건이다. 주문 예약액은 2 BTC, 최초 수수료 예약은 0 BTC다. 원장 자산은 두 항목 모두 BTC이고, BTC AVAILABLE 차변/HOLD 대변 각각 2 BTC와 대기 승인 한 건을 비교한다.

**근거:** [준비·접수](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L144), [잔고·예약·원장·대기 assertion](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L152). 판매 대금을 받거나 체결 수수료를 차감하는 테스트는 아니다.

</details>

<details markdown="1">
<summary><strong>05 · 없는 BTC 잔고는 0으로 부족 거절하고 다음 BUY를 받을 수 있다</strong></summary>

**목적:** 모든 자산의 0 잔고를 미리 저장해야 하는 제약과 정상 부족 뒤 불필요한 객체 차단을 막는다. 심벌은 `없는 BTC 잔고는 0의 부족 거절이며 빈 잔고 항목을 만들지 않는다`다.

- **Given:** USDT 1,000만 준비하고 BTC 항목은 없다. 유효한 SELL 1 BTC다.
- **When 1 / Then 1:** SELL을 접수하면 BTC available 0·required 1·shortfall 1의 정상 거절이다. 전체 관찰값은 이전과 같고 `isBlocked=false`다. BTC 0 잔고 항목도 추가되지 않는다.
- **When 2 / Then 2:** 기본 BUY를 접수하면 승인 순번 1이다.

**근거:** [테스트·부족 결과·전체 상태 비교](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L171), [차단·다음 승인 assertion](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L183). 초기 준비 누락과 의도한 0 잔고를 구분하는 검사는 아니다.

</details>

<details markdown="1">
<summary><strong>06 · 초기 잔고가 전혀 없어도 BUY 부족 이유를 반환한다</strong></summary>

**목적:** 빈 상태에서 거절을 위해 새 잔고·대기 항목을 만드는 일을 막는다. 심벌은 `빈 초기 상태에서도 BUY의 quote 부족을 반환하고 상태를 만들지 않는다`다.

- **Given:** 초기 Balance 목록이 비어 있다.
- **When:** 대금 300 USDT의 기본 BUY를 접수한다.
- **Then:** USDT available 0·required 300·shortfall 300의 부족 결과다. 전체 관찰값은 빈 초기 상태와 같고 해당 마켓 대기 조회도 빈 목록이다.

**근거:** [테스트와 실제 assertion](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L189). 초기 자금을 지급하는 기능을 호출하지 않는다.

</details>

<details markdown="1">
<summary><strong>07 · 같은 마켓의 승인 주문은 사용자·수량을 바꿔도 재승인하지 않는다</strong></summary>

**목적:** 업무 키를 사용자별로 분리해 같은 마켓의 주문 ID를 다시 쓰거나 기존 원본을 다른 사용자에게 반환하는 일을 막는다. 심벌은 `같은 마켓 주문 ID는 사용자나 내용이 달라도 중복 거절하며 원본을 노출하지 않는다`다.

- **Given:** 기본 주문을 한 번 접수한 뒤 전체 관찰값을 보관한다.
- **When:** 아래 세 입력을 같은 객체에 순서대로 제출한다.

| 반복 입력 | 기대 결과 |
| --- | --- |
| 최초와 같은 주문 | `Rejected(DUPLICATE_ORDER)` |
| 같은 마켓·주문 ID, 수량만 1 BTC | 같은 중복 거절 |
| 같은 마켓·주문 ID, 사용자만 `other-user` | 같은 중복 거절 |

- **Then:** 매 반복마다 결과는 원본 기록이 없는 동일한 거절 값, 전체 관찰값은 이전과 같고 차단되지 않는다. 이어 새로운 주문 `next`는 순번 2로 승인된다.

**근거:** [준비와 3개 반복 입력·assertion](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L205), [후속 승인](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L216). 세 반복은 별도 JUnit 테스트 정의가 아니다.

</details>

<details markdown="1">
<summary><strong>08 · 다른 마켓에서는 같은 주문 ID를 별개로 승인한다</strong></summary>

**목적:** 주문 ID만 비교해 다른 마켓의 유효 주문을 중복으로 거절하는 일을 막는다. 심벌은 `마켓이 다르면 같은 주문 ID를 각각 승인하고 두 원장 ID를 구분한다`다.

- **Given:** USDT 2,000, BTC-USDT·SUI-USDT 두 마켓이다.
- **When:** 같은 주문 ID로 BTC 600과 SUI 500을 순서대로 접수한다. SUI에는 다른 원장 거래 ID `sui-ledger`를 명시한다.
- **Then:** 최종 잔고는 사용 가능 900 USDT·예약 1,100 USDT, 전체 승인 순번은 `[1, 2]`다. 두 원장 거래 ID와 두 사건 ID는 각각 서로 다르고, 두 번째 사건 ID 길이는 75다.

**근거:** [두 접수](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L220), [잔고·순번·ID assertion](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L229). 사건 ID 해시의 모든 입력·알고리즘 정확성을 이 길이 비교 하나로 보장하지 않는다.

</details>

<details markdown="1">
<summary><strong>09 · 같은 USDT라도 다른 사용자의 돈은 분리한다</strong></summary>

**목적:** 자산만으로 잔고를 골라 한 사람의 주문이 다른 사람의 돈을 쓰는 일을 막는다. 심벌은 `사용자별 같은 자산 잔고는 서로 섞지 않는다`다.

- **Given:** `user-1`은 USDT 1,000, `other-user`는 500이다.
- **When:** 두 사용자가 서로 다른 주문 ID로 각각 300을 접수한다.
- **Then:** 잔고 목록은 첫 사용자가 사용 가능 700 USDT·예약 300 USDT, 다른 사용자가 사용 가능 200 USDT·예약 300 USDT다. 두 번째 예약의 소유자는 `other-user`이고 원장 계정도 정확히 `USER:other-user:USDT:AVAILABLE`과 `:HOLD`다.

**근거:** [입력·두 접수](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L237), [잔고·예약 사용자·원장 계정 assertion](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L245).

</details>

<details markdown="1">
<summary><strong>10 · 승인 가능한 주문의 원장 거래 ID 충돌은 내부 오류다</strong></summary>

**목적:** 서로 다른 승인에 같은 원장 거래 ID를 재사용하거나 이 오류 뒤 객체를 불필요하게 차단하는 일을 막는다. 심벌은 `원장 거래 ID 충돌은 반영 전 내부 오류이고 뒤의 정상 주문은 계속된다`다.

- **Given:** USDT 1,000에서 기본 300 주문을 승인하고 관찰값을 보관한다.
- **When 1 / Then 1:** 신규 주문 `second`에 첫 거래 ID `ledger-order-1`을 주면 `IllegalStateException`이다. 전체 관찰값은 같고 차단되지 않는다.
- **When 2 / Then 2:** 같은 `second`를 새 기본 거래 ID로 제출하면 순번 2 승인이고 최종 잔고는 사용 가능 400 USDT·예약 600 USDT다.

**근거:** [오류·상태 보존·차단 assertion](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L262), [후속 정상 승인](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L270). 정상 업무 중복 거절과 별개인 서버 기록 오류다.

</details>

<details markdown="1">
<summary><strong>11 · 먼저 자금이 부족하면 원장 ID 이력으로 거절 이유를 바꾸지 않는다</strong></summary>

**목적:** 승인 후보도 없는 요청에 뒤쪽 이력 검사를 적용해 정상 부족을 내부 오류로 바꾸는 일을 막는다. 심벌은 `자금 부족은 뒤의 원장 ID 충돌 검사로 가려지지 않는다`다.

- **Given:** USDT 300을 기본 주문이 전액 예약한 뒤다.
- **When:** 새 주문 ID로 300을 요구하면서 첫 승인과 같은 원장 거래 ID를 준다.
- **Then:** USDT available 0·required 300·shortfall 300의 정상 부족 결과이며 전체 관찰값은 직전과 같다.

**근거:** [테스트와 부족 결과·전체 상태 assertion](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L276). 원장 ID 중복을 언제나 허용한다는 뜻이 아니라, 기존 계산기의 거절이 앞선다는 뜻이다.

</details>

<details markdown="1">
<summary><strong>12 · 잔고가 없어도 기존 입력 거절 이유를 유지한다</strong></summary>

**목적:** 없는 잔고를 먼저 처리하면서 사용자가 수정해야 할 입력 오류를 모두 자금 부족으로 바꾸는 일을 막는다. 심벌은 `잔고 유무와 무관하게 계산기의 입력 거절 순서를 유지한다`다.

- **Given:** 빈 초기 잔고와 USDT 1,000의 두 상태를 각각 새로 만든다.
- **When:** 각 상태에서 아래 다섯 입력을 순서대로 제출한다. **2 × 5 = 10개 거절 조합**을 한 테스트 메서드 안에서 확인한다.

| 입력 | Then — 실제 거절 이유 |
| --- | --- |
| 주문 ID에 NUL (`bad\u0000id`) | `INVALID_ORDER_IDENTIFIER` |
| 주문 종류 MARKET | `UNSUPPORTED_ORDER_CONDITION` |
| 유효 기간 IOC | `UNSUPPORTED_ORDER_CONDITION` |
| 수량 0 | `INVALID_QUANTITY` |
| 최소 단위 가격 1·수량 1로 정확한 quote 예약액 표현 불가 | `UNREPRESENTABLE_RESERVATION` |

- **Then:** 매 입력 뒤 전체 관찰값이 초기와 같고 차단되지 않는다. 자금이 준비된 상태에 한해 마지막 정상 기본 주문의 승인 순번이 1인지 추가 확인한다. 빈 상태의 마지막 정상 승인을 검사한다고 설명하지 않는다.

**근거:** [입력 공급 목록](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L292), [두 초기 상태·반복 assertion·조건부 후속 승인](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L301).

</details>

<details markdown="1">
<summary><strong>13 · 서버가 잘못 연결한 마켓·정책·거래 ID는 내부 오류로 드러낸다</strong></summary>

**목적:** 서버 문맥 오류를 잔고 부족 같은 사용자 거절로 숨기지 않는다. 심벌은 `잘못된 서버 문맥은 잔고가 없어도 정상 거절로 숨기지 않는다`다.

- **Given:** 빈 초기 잔고와 USDT 1,000의 두 상태를 각각 준비한다.
- **When:** 각 상태에서 ① 주문과 다른 마켓 ID `OTHER`, ② 비현물 `PERPETUAL_FUTURES` 정책, ③ 빈 원장 거래 ID를 차례로 준다. **2 × 3 = 6개 오류 호출**이다.
- **Then:** 각 호출은 `IllegalArgumentException`이며 호출마다 전체 관찰값이 이전과 같다. 세 호출 후 차단 여부가 false인지 확인한다. 이 테스트에는 오류 뒤 정상 주문 접수는 없다.

**근거:** [두 초기 상태와 세 호출·상태 assertion](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L314). 최초 변경 전 실패이므로 반영 중 차단 사례와 구별한다.

</details>

<details markdown="1">
<summary><strong>14 · 새 객체는 중복 잔고와 설명 없는 hold를 받지 않는다</strong></summary>

**목적:** 초기 잔고 덮어쓰기와 예약 기록 없는 금액 동결을 막는다. 심벌은 `초기 사용자 자산 키 중복이나 근거 없는 hold는 생성 단계에서 거절한다`다.

- **Given / When:** ① 같은 사용자·USDT 키의 1,000과 500 잔고, ② USDT available 1,000·hold 최소 단위 1로 각각 생성한다.
- **Then:** 두 생성은 `IllegalArgumentException`이다.
- **Given / When / Then:** available 0·hold 0 잔고는 정상 생성되고, 관찰값에 그 0 잔고가 있으며 차단되지 않는다. 명시적으로 받은 0 잔고와 거절 중 임시 0 잔고를 구별한다.

**근거:** [오류 생성 두 종류와 정상 0 잔고 assertion](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L334). 기존 계산기의 이미 존재하는 hold 계산 지원을 없애는 검사는 아니다.

</details>

<details markdown="1">
<summary><strong>15 · 외부 목록 수정과 다음 주문이 이미 돌려준 관찰값을 바꾸지 않는다</strong></summary>

**목적:** 호출자가 내부 목록을 수정하거나 이전 화면의 잔고가 후속 주문 때문에 소급 변경되는 일을 막는다. 심벌은 `초기 목록 변경과 반환 목록 변경으로 소유 상태를 수정할 수 없다`다.

- **Given:** 수정 가능한 초기 목록에 USDT 1,000을 넣어 상태를 만든다.
- **When 1:** 원래 입력 목록을 비우고, 접수 전 관찰값 `empty`를 얻은 뒤 기본 주문을 승인한다. 첫 승인 뒤 관찰값과 마켓별 대기를 보관한다.
- **When 2 / Then 2:** 첫 관찰값의 잔고 목록, 대기 목록, 마켓별 반환 목록, 후보 원장 분개 목록을 각각 비우려 하면 네 시도 모두 `UnsupportedOperationException`이다.
- **When 3 / Then 3:** 신규 주문 `second`를 승인한다. 접수 전 관찰값은 사용 가능 1,000 USDT·예약 0 USDT·빈 대기·순번 0, 첫 관찰값은 사용 가능 700 USDT·예약 300 USDT·순번 `[1]`·마지막 1로 유지된다. 첫 마켓 대기도 첫 대기 목록과 같다. 최신 잔고만 사용 가능 400 USDT·예약 600 USDT다.

**근거:** [입력 목록 변경·첫 접수·수정 시도](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L345), [둘째 접수 뒤 이전/현재 관찰값 assertion](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L364). 변수 `empty`는 잔고가 빈 상태가 아니라 ‘접수 기록이 아직 빈 때’의 관찰값이다.

</details>

<details markdown="1">
<summary><strong>16 · 최대 승인 순번 다음에는 반영 전에 중단한다</strong></summary>

**목적:** 순번 범위가 넘으면 음수나 1로 되돌아가 기록을 혼동하는 일을 막는다. 심벌은 `승인 순번 Long 최대값 다음에는 변경 전에 중단하며 순번이 되돌아가지 않는다`다.

- **Given:** 정상 상태를 만들고 테스트의 private 필드 접근으로 마지막 순번을 `Long.MAX_VALUE - 1`로 준비한다.
- **When 1 / Then 1:** 첫 주문은 정확히 `Long.MAX_VALUE` 순번으로 승인된다.
- **When 2 / Then 2:** 그다음 신규 주문은 `ArithmeticException`이다. 직전 전체 관찰값은 같고 차단되지 않는다.

**근거:** [내부 한계값 준비와 실제 assertion](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L377), [필드 접근 helper](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L480). 실제로 최대 건수까지 주문을 실행한 성능 시험이 아니다. 제품의 공개 순번 setter도 아니다.

</details>

<details markdown="1">
<summary><strong>17 · 사건 ID가 이미 사용된 이력이면 첫 변경 전에 멈춘다</strong></summary>

**목적:** 거래 ID와 별개인 사건 ID 사용 이력 검사도 유지되는지 확인한다. 심벌은 `사건 ID 사용 이력 충돌은 첫 변경 전에 중단한다`다.

- **Given:** 실제 계산기로 기본 주문 후보를 만들어 그 사건 ID를 얻는다. 테스트에서 상태의 private `sourceEventIds` 집합에 그 값을 미리 넣고 관찰값을 보관한다.
- **When:** 상태에 같은 주문을 처음 접수한다. 승인 업무 키 기록은 아직 없다.
- **Then:** `IllegalStateException`이며 전체 관찰값은 이전과 같고 차단되지 않는다.

**근거:** [계산기로 ID 준비·내부 이력 설정](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L389), [오류·상태·차단 assertion](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L402). 계산기로 기대 ID를 얻는 것은 충돌 경로를 준비하기 위한 장치다. 해시 알고리즘의 독립 정답 검사나 실제 SHA-256 충돌 재현이 아니다. 스냅샷에는 ID 이력 집합 자체가 노출되지 않는다.

</details>

<details markdown="1">
<summary><strong>18 · 마켓별 조회도 전체 승인 순서의 상대 순서를 유지한다</strong></summary>

**목적:** 한 마켓의 대기 주문을 조회하며 순서를 다시 섞는 일을 막는다. 심벌은 `마켓별 대기 조회도 전체 접수 순서에서 상대 순서를 보존한다`다.

- **Given:** USDT 2,000과 BTC-USDT·SUI-USDT 두 마켓이다.
- **When:** BTC 기본 주문, SUI 주문 `sui`, BTC 주문 `third`를 순서대로 접수한다.
- **Then:** 전체 순번은 `[1, 2, 3]`, BTC 조회는 `[1, 3]`, SUI 조회는 `[2]`다. 승인된 적 없는 `UNKNOWN` 마켓 조회는 빈 목록이다.

**근거:** [세 호출과 네 조회 assertion](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L408). 병렬 도착 순서·Aeron 합의 순서를 검증하지 않는다.

</details>

<details markdown="1">
<summary><strong>19 · 콜론을 가진 마켓·주문 튜플을 서로 구별한다</strong></summary>

**목적:** 문자열 이어붙이기로 서로 다른 업무 키가 합쳐져 유효 주문을 잃는 일을 막는다. 심벌은 `업무 키의 콜론을 문자열 합치기처럼 혼동하지 않는다`다.

- **Given:** USDT 1,000, 마켓·주문 쌍 `(a:b, c)`와 `(a, b:c)`를 준비한다. 단순히 콜론으로 합치면 둘 다 `a:b:c`가 된다.
- **When:** 서로 다른 명시적 거래 ID `first`, `second`로 각각 300을 접수한다.
- **Then:** 대기 목록에 두 승인 기록이 정확히 있고 최종 잔고는 사용 가능 400 USDT·예약 600 USDT다. 두 사건 ID도 서로 다르다.

**근거:** [두 튜플과 승인 호출](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L422), [대기·잔고·사건 ID assertion](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L435). 마켓·주문 원문을 trim하거나 콜론에서 분리하지 않는다.

</details>

<details markdown="1">
<summary><strong>20 · 반영 중 실패한 객체는 더 이상 접수·정상 조회에 쓰지 못한다</strong></summary>

**목적:** 부분 반영 가능성이 있는 메모리 상태를 최신 정상 잔고처럼 계속 사용하는 일을 막는다. 심벌은 `반영 중 예외나 Error가 나면 후속 접수와 정상 조회를 차단한다`다.

각 반복마다 새 객체를 만든다. 실패 입력은 `IllegalStateException("반영 중 실패")`과 `AssertionError("반영 중 Error")` 두 종류다. 이는 테스트가 우연히 실패한 assertion을 무시하는 구조가 아니라, 테스트가 의도적으로 던질 `Throwable` 객체를 준비한 것이다.

- **Given:** USDT 1,000에서 기본 300 주문을 승인하고 직전 관찰값을 보관한다. [failAfterBalanceWrite](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L461)가 내부 balances map을 감싼다. 새 값을 실제 원본 map에 넣은 뒤 지정한 실패를 던지도록 준비한다.
- **When 1 / Then 1:** 신규 주문 `second`를 접수하면 `assertFailsWith<Throwable>`로 던짐을 받고 `assertSame`으로 지정한 바로 그 실패 객체인지 확인한다. `Accepted` 반환으로 끝나지 않으며 `isBlocked=true`다.
- **When 2 / Then 2:** 신규 `third`, 기존 기본 주문의 재접수, `snapshot`, 마켓별 `pendingOrders` 네 호출 모두 `IllegalStateException`이다. 업무 중복 처리보다 차단 확인이 앞선다는 경계도 포함한다.
- **Then — 이전 관찰값:** 실패 전에 받은 잔고는 사용 가능 700 USDT·예약 300 USDT, 대기 순번은 `[1]`, 마지막 순번은 1 그대로다. 실패 뒤 내부 최신 잔고가 롤백됐다는 assertion은 없다. 정상 조회를 차단하기 때문에 그 부분 상태를 정상 결과로 읽지 않는다.

**근거:** [실패 두 종류·발생 호출·재사용 차단·이전 관찰값 assertion](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L441), [실제 쓰기 뒤 실패를 던지는 장치](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L469). 한 반영 위치의 통제된 실패를 확인하며 JVM 강제 종료·디스크 보존·복구 재개를 증명하지 않는다.

</details>

<details markdown="1">
<summary><strong>남은 검증과 학습 문답 · 선택한 설명과 아직 확인 중인 것</strong></summary>

이 문서는 현재 변경의 초기화·정상 승인·정상 거절·반영 전 오류·반영 중 오류·후속 차단·조회 소유권을 소스에 연결하고, 20개 테스트 정의와 그 안의 반복 입력을 설명했다. 실제 검증 수치·명령·독립 코드 리뷰 결과는 아래에 기록한다. 문서 설명을 사람의 이해·승인으로 해석하지 않는다.

실제 기록을 보탤 때는 현재 파일 버전과 함께 도메인 테스트, 관련 구조 검사·보고서, 공통 lint의 실행 결과와 미검증 범위를 적는다. 명령을 사용자에게 안내할 때는 이 구현 폴더로 이동하는 `cd` 또는 Gradle `-p`를 포함한다. 테스트 수나 통과 기록으로 사람의 이해·승인을 추정하지 않는다.

이번 메모리 객체의 범위를 넘어서는 미검증 항목은 동시 호출, HTTP 연결, 요청 키별 최초 결과 보존, 영구 저장, 초기 자금 지급·개시 원장, 매칭·정산·취소, Aeron 복제·프로세스 장애·복구다. 이 목록은 후속 범위를 밝히는 것이며 이번 테스트가 이미 다뤘다는 주장이 아니다.

**학습 문답:**

- **질문:** ‘반영 전/반영 중 오류의 차이’, ‘마켓 사이의 공통 잔고’, ‘계산기와 상태 객체의 책임’ 중 어디를 더 자세히 볼까?
- **사용자 실제 답변:** “반영 전 오류와 반영 중 오류의 차이”. 선택 이유는 별도로 말하지 않아 추측하지 않는다.
- **AI 설명·근거:** 10번처럼 쓰기 전 거래 ID 충돌이면 이전 관찰값이 그대로이고 다음 정상 주문을 받을 수 있다. 20번은 실제 잔고 쓰기 직후 실패를 내므로 잔고만 바뀐 부분 상태가 가능하다. 원상 복구됐다고 약속하지 않고, 후속 접수·정상 조회를 차단해 그 상태를 계속 쓰지 못하게 한다. [제품 반영 경계](../domain-trading/src/main/kotlin/com/exchange/core/trading/OrderAdmissionState.kt#L107)와 [20번의 실제 쓰기 뒤 실패](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionStateTest.kt#L469)를 함께 읽는다.
- **반영:** 첫 화면과 전체 흐름 3에서 이 차이를 강조했다. 이 답변은 설명할 주제의 선택이며 이해·검토·승인 완료로 표시하지 않는다.

문답이 이어지면 10번과 20번의 구체적인 입력·이후 호출 결과를 나란히 보며 설명을 조정한다. 이 학습 문답을 구현 진행의 새 승인 절차로 만들지는 않는다.

</details>


## 최종 검증 기록 · 2026-10-16

- 로컬 관련 테스트 **567개 통과**, 실패·오류·skip 0: trading 63(신규 상태 20 + 기존 43), order 53, ledger 39, fee 37, architecture 375.
- 필수 운영 구조 검사 P01~P09 실행·성공 확인, 전체 모듈 `ktlintCheck`, `git diff --check` 통과.
- 작성 대화를 상속하지 않은 별도 에이전트가 격리된 checkout에서 같은 제품·테스트를 검토하고 567개와 필수 검사·린트를 직접 실행했다. 추가로 제출 소스와 분리한 독립 테스트 6개가 통과했다. 독립 사례에는 네 쓰기 지점 × Exception/Error 8개 조합의 후속 차단이 포함된다. 이 반복 8개를 JUnit 테스트 개수에 더하지 않는다.
- 제품·테스트 리뷰 기준은 base `105bbc146303f081032c6885bfd3f94949fc7b45`와 Git tree `4570be185a3bfa8a061fd54ae4f8dee60d23f7f7`이다. 위 세 파일 SHA-256과 일치하는 소스를 검토했고, 검토한 범위의 확인된 미해결 코드 결함은 없다. 이 문서 추가 후의 전체 변경은 같은 코드인지 다시 대조한다.
- 로컬 구현·검증 결과다. PR 생성·원격 CI·병합은 이번 작업에서 하지 않았다. 기존 HTTP·SQL·Bean 연결은 변경하지 않았고 DB 통합·성능·분산 복구 검증으로 확대하지 않는다.

실제 구현 폴더에서 실행한 명령:

```sh
cd /Users/0chord/.codex/worktrees/issue85-admission-state/exchange-core
./gradlew :domain-trading:test :domain-order:test :domain-ledger:test :domain-fee:test :architecture-tests:verifyArchitectureReport ktlintCheck --console=plain
```

위 실행은 `BUILD SUCCESSFUL in 2m 3s`였다. 별도 독립 리뷰 checkout에서도 567개와 P01~P09·전체 lint를 새로 실행해 성공했다. 초기 API 컴파일 실패와 TODO의 `NotImplementedError`는 미구현 단계의 근거이며 금액 assertion이 잘못된 구현을 잡은 행동 수준의 Red라고 주장하지 않는다.
