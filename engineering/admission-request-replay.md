# 같은 접수 요청에 첫 결과를 돌려주기 — #86

> #86의 메모리 접수 계약과 검증 안내다. 기준 통합 커밋은 72f4bd99이며, HTTP·영구 저장·동시 실행은 이번 범위에 포함하지 않는다.

## 무엇이 달라졌나

**한 번 승인한 요청을 다시 보내도 돈을 또 예약하지 않는다. 한 번 거절한 요청을 다시 보내면 그때의 거절을 돌려준다.**

기존에는 승인한 주문을 다시 보내면 ‘이미 있는 주문’이라고 거절했다. 이제 새로 추가한 요청 ID 입구에서는 먼저 **그 사용자의 같은 요청을 처리한 적이 있는지** 찾는다. 처음 처리한 원본과 같으면 첫 승인 또는 첫 정상 거절을 돌려주고, 다른 내용이면 충돌로 거절한다. 처음 보는 요청만 기존 자금 계산으로 보낸다.

| 이번에 추가한 흐름 | 입력 → 판단 → 결과 |
| --- | --- |
| 같은 요청 다시 보내기 | 사용자·요청 ID가 같고 주문 원본도 같음 → 첫 결과 반환 → 돈·원장·순번 추가 없음 |
| 같은 요청 ID의 내용 변경 | 사용자·요청 ID는 같지만 가격 등 원본이 다름 → 내용 충돌 → 첫 기록 그대로 |
| 새 요청으로 같은 승인 주문 보내기 | 새 요청 ID이지만 마켓·주문 ID가 이미 승인됨 → 주문 중복 거절 → 그 새 요청의 첫 거절만 기록 |
| 입력 식별자가 잘못됨 | 요청·주문 식별자 검사 실패 → 입구 거절 → 요청 이력도 만들지 않음 |
| 반영 전 내부 오류 | 잘못된 마켓 정의·사용한 원장 ID 등 → 오류 전달 → 돈·요청 이력 그대로, 객체 계속 사용 가능 |
| 반영 중 내부 오류 | 돈이나 첫 결과를 기록하던 중 실패 → 오류 전달 → 같은 객체의 신규 요청·재전송·정상 조회 차단 |

HTTP 응답을 못 받은 상황을 대비할 수 있는 **메모리 코어의 계약**이다. 기존 HTTP 경로에 연결하거나 서버 재시작 뒤 결과를 복구하는 기능은 아직 아니다.

## 1. 첫 승인을 돌려주면 돈은 어떻게 남나

수수료 없는 BUY 예다. 처음 사용 가능한 돈은 1,000 USDT, 가격 100 USDT인 BTC를 3개 주문한다.

1. 사용자 A의 요청 R1이 처음 들어오면 기존 계산기로 300 USDT를 예약한다.
2. 사용 가능 금액은 700 USDT, 예약 금액은 300 USDT가 된다. 대기 주문과 RESERVE 원장, 승인 순번 1, R1의 원본·첫 승인 결과를 함께 메모리에 남긴다.
3. 사용자 A가 같은 R1과 같은 주문 원본을 다시 보내면 첫 승인 결과를 반환한다. 사용 가능 금액 700 USDT·예약 금액 300 USDT를 유지하고 승인·원장·대기 주문·요청 이력은 각각 한 건이다.

SELL도 같은 순서다. BTC 3개 중 2개 매도는 사용 가능 1 BTC·예약 2 BTC가 되며, 재전송해도 그대로다. 수수료 정책이 있어도 판매대금에서 수수료를 차감하는 기존 규칙에 따라 사전 수수료 예약은 0이다.

## 2. 잔고가 늘어도 왜 처음의 거절을 돌려주나

**같은 요청의 재전송은 새 주문 판단이 아니라, 처음 그 요청에 무슨 답을 했는지 확인하는 행동이다.** 돈이 생겼다는 이유로 같은 요청의 답이 바뀌면, 사용자는 이전에 끝난 줄 알았던 요청 때문에 뒤늦게 돈이 묶이는 상황을 겪을 수 있다.

| 순서 | 요청과 돈의 상태 | 반환 결과·남는 기록 |
| --- | --- | --- |
| 1 | 사용 가능 200 USDT에서 R1로 300 USDT 예약 요청 | 잔고 부족. 당시 사용 가능 200·필요 300·부족 100 USDT를 R1의 첫 결과로 보존. 자금 이동·원장·승인 순번 없음 |
| 2 | 이후 사용 가능 금액이 1,200 USDT가 된 뒤 같은 R1 재전송 | 여전히 첫 부족 결과 반환. 지금의 1,200 USDT를 다시 계산하지 않음 |
| 3 | 새 요청 R2로 같은 주문을 다시 시도 | 처음 보는 요청이고 아직 승인된 주문이 없으므로 300 USDT 예약 승인. 사용 가능 900 USDT·예약 300 USDT, 승인 순번 1 |
| 4 | 그 뒤 R1 재전송 | 주문이 이미 승인됐어도 R1의 첫 부족 결과 반환. 요청 이력을 주문 중복보다 먼저 확인하기 때문 |

거절 기록은 **돈을 움직였다는 원장 기록과 다르다.** 첫 거절을 보관하는 데에는 금전 원장 ID나 승인 순번을 소비하지 않는다.

이 테스트에서는 공개 입금 기능을 추가하지 않고, 테스트 안에서만 같은 객체의 잔고를 기존 Balance.credit 결과로 준비한다. 실제 입금 원장·복구를 검증한 것이 아니다.

## 3. 요청 ID와 주문 ID는 무엇이 다른가

- **사용자 + 요청 ID:** 같은 요청의 답을 다시 찾는 키다. 서로 다른 사용자가 같은 R1 문자열을 써도 별개 요청이다.
- **마켓 + 주문 ID:** 이미 승인된 주문을 두 번 만들지 않는 업무 키다. 다른 사용자의 새 요청이라도 이 키가 이미 승인됐다면 주문 중복이다. 다른 사용자의 승인 원본을 반환하지 않는다.

같은 요청 ID로 가격만 바꾸면 ‘내용 충돌’이다. 새 요청 ID로 이미 승인한 주문을 보내면 ‘주문 중복’이다. 후자는 정상 거절이므로 새 요청의 첫 답으로 남는다. 전자는 기존 첫 기록을 덮어쓰지 않는다.

비교하는 주문 원본은 사용자·마켓·주문 ID·방향·주문 종류·유효 조건·가격·수량의 여덟 값이다. trim·대소문자 변환·유니코드 정규화로 합치지 않고, 해시 하나로만 동일성을 판단하지 않는다.

## 4. 재전송은 왜 새로운 수수료 정책을 쓰지 않나

주문 원본과 **서버가 공급하는 문맥**을 나눈다. 마켓 자산 정의, 수수료 정책, 원장 거래 ID, 발생 시각은 당시 판단의 근거로 첫 요청 기록에 남긴다.

알려진 요청·같은 원본이면 이 기록을 돌려준다. 호출자가 새 정책·시각·원장 ID를 넘기거나, 사용하지 않을 새 마켓 정의·원장 ID가 잘못돼 있어도 재계산하지 않는다. 새 문맥으로 과거의 답을 바꾸지 않기 위해서다.

처음 보는 요청은 다르다. 새 주문 키와 충분한 잔고를 준비했어도 잘못된 서버 문맥이면 반영 전 내부 오류다. 이를 잔고 부족 같은 정상 거절로 저장하지 않으며, 문맥을 고친 같은 요청으로 다시 판단할 수 있다.

요청 ID는 1~64 Unicode code point, 공백만인 문자열·NUL·깨진 surrogate 불가다. 사용자·마켓·주문 ID는 기존 같은 검사 기준을 재사용한다. 기존 주문이 있더라도 새 입구의 잘못된 식별자는 먼저 거절하고 이력에 저장하지 않는다. 요청 ID 없는 기존 입구의 검사 순서는 유지한다.

## 5. 어디서 실패했는지에 따라 달라진다

계산과 검증을 마친 뒤에야 쓰기를 시작한다. 승인에서는 잔고·대기 주문·원장 ID 사용 이력·순번·요청의 첫 답을 기록한다. 정상 거절에서는 요청의 첫 답만 기록한다.

| 실패 시점 | 왜 다음 요청을 처리하거나 막나 |
| --- | --- |
| 쓰기 전 | 아직 상태를 바꾸지 않았다. 오류를 전달하고 같은 객체를 계속 사용할 수 있다. 요청 첫 결과도 없으므로 올바른 문맥으로 재시도 가능 |
| 쓰기를 시작한 뒤 | 일부만 반영됐을 수 있다. SQL 트랜잭션처럼 되돌린다고 약속하지 않는다. 차단 상태를 남겨 이후 접수·재전송·snapshot·대기 주문 정상 조회를 막는다 |

거절 기록 저장 중 실패해도 차단한다. 돈이 안 바뀌었더라도 ‘첫 답이 확실히 기록됐는지’를 신뢰할 수 없기 때문이다. 차단 검사를 가장 먼저 하므로, 전에 성공한 요청을 그대로 재전송해도 차단 오류를 받는다. 차단 여부만 진단할 수 있고 자동 해제는 없다.

실패 테스트는 테스트용 저장 map이 쓰기 전 또는 쓴 직후에 오류를 내도록 통제한다. 실제 계산기와 값 객체를 사용하며, DB나 네트워크 장애를 재현했다고 주장하지 않는다.

## 6. 바꾼 파일과 읽을 지점

| 파일 | 성격·이번 역할 |
| --- | --- |
| OrderAdmissionState.kt | 실행 코드. 사용자·요청 키와 첫 결과 보관, 재전송/충돌 입구, 승인·거절의 반영 구간, 요청 이력 snapshot |
| OrderAdmissionResult.kt | 실행 코드. 입구의 잘못된 요청 ID와 내용 충돌 사유 두 개 추가 |
| OrderAdmissionCalculator.kt | 기존 협력자. 식별자 검사 두 메서드를 같은 모듈에서 재사용하도록 가시성만 조정. 자금·수수료·원장 공식은 유지 |
| OrderAdmissionRequestTest.kt | 테스트. 실제 계산기로 승인·거절·충돌·실패·관찰 경계를 검사 |
| admission-request-replay.md | 문서. 이번 전체 흐름과 테스트를 사람이 읽는 순서로 연결 |

기존 #85의 요청 ID 없는 입구도 같은 잔고와 승인 주문을 사용한다. 두 입구를 번갈아 호출해도 동일 업무 키를 두 번 승인하지 않는다. 새 요청 이력은 외부에서 수정할 수 없는 목록으로 복사해 반환한다. 이후 요청을 받아도 이미 반환한 목록·첫 결과는 바뀌지 않는다.

## 핵심 판단과 실제 코드

| 판단 | 실제 코드에서 볼 것 |
| --- | --- |
| [새 요청 입구의 순서](../domain-trading/src/main/kotlin/com/exchange/core/trading/OrderAdmissionState.kt#L97) | 차단 → 식별자 → 요청 이력 → 새 판단 순서 |
| [최초 원본 대조](../domain-trading/src/main/kotlin/com/exchange/core/trading/OrderAdmissionState.kt#L110) | 주문 원본이 같으면 저장된 결과, 다르면 충돌 |
| [쓰기 전 계산과 검증](../domain-trading/src/main/kotlin/com/exchange/core/trading/OrderAdmissionState.kt#L152) | 기존 주문 중복·최신 잔고·원장 ID·다음 순번 검증 |
| [승인 결과와 요청 기록 반영](../domain-trading/src/main/kotlin/com/exchange/core/trading/OrderAdmissionState.kt#L178) | 차단 표시를 켜고 돈·대기·ID·순번·첫 답을 쓰고, 모두 끝나야 차단을 해제 |
| [정상 거절의 첫 답 기록](../domain-trading/src/main/kotlin/com/exchange/core/trading/OrderAdmissionState.kt#L199) | 요청 기록도 반영 중 실패하면 차단 상태를 유지 |
| [정상 조회와 이전 반환값](../domain-trading/src/main/kotlin/com/exchange/core/trading/OrderAdmissionState.kt#L142) | 반환 목록을 복사하고 변경을 막음 |

## 테스트를 하나씩 읽기

공통 준비는 실제 OrderAdmissionCalculator와 주문·수수료·잔고 값 객체다. USDT는 소수 6자리, BTC는 소수 8자리의 최소 단위를 저장한다. 예를 들어 300 USDT는 Amount(300_000_000)이다. DB·HTTP·mock 서버를 사용하지 않으며, 내부 실패와 잔고 증가를 만들 때만 비공개 저장 구조를 테스트에서 통제한다.

신규 테스트 함수는 19개다. 함수 안의 반복 입력·조합은 실행 사례이며 JUnit 테스트 건수와 구별한다. R01~R12는 수용 그룹 번호다.

| 읽을 테스트 | 실제 확인 |
| --- | --- |
| [승인한 구매 요청을 다시 보내도 돈을 두 번 묶지 않는다](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L54) | R01 · 순수 메모리 동작 |
| [거절한 요청은 잔고가 늘어도 갑자기 승인되지 않는다](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L85) | R05 · 순수 메모리 동작 |
| [매도 재전송도 팔 BTC를 한 번만 묶는다](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L128) | R02 · 순수 메모리 동작 |
| [요청 ID를 유지한 채 내용을 바꾸면 첫 답을 덮어쓰지 않는다](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L182) | R03 · 순수 메모리 동작 |
| [새 요청으로 기존 주문을 보내면 새 요청의 첫 답은 중복 거절이다](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L215) | R04 · 순수 메모리 동작 |
| [기존 입구와 새 입구를 번갈아 써도 같은 주문은 두 번 승인되지 않는다](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L241) | R04 · 순수 메모리 동작 |
| [정상 거절도 그 요청의 첫 답으로 남는다](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L265) | R06 · 순수 메모리 동작 |
| [서버 정책이 바뀌어도 재전송의 첫 답은 바뀌지 않는다](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L292) | R07 · 순수 메모리 동작 |
| [다른 사용자의 같은 요청 ID를 내 요청과 섞지 않는다](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L332) | R08 · 순수 메모리 동작 |
| [요청 ID 길이는 글자의 code point로 센다](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L375) | R09 · 순수 메모리 동작 |
| [잘못된 식별자는 이미 아는 요청이어도 먼저 거절한다](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L385) | R09 · 순수 메모리 동작 |
| [허용 길이와 마켓·주문 ID의 콜론을 과도하게 거절하지 않는다](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L425) | R09 · 순수 메모리 동작 |
| [겉보기 비슷한 요청 ID를 임의로 합치지 않는다](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L444) | R09 · 순수 메모리 동작 |
| [상태 변경 전 내부 오류는 요청의 첫 거절로 저장하지 않는다](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L460) | R10 · 순수 메모리 동작 |
| [승인 순번 범위를 넘으면 쓰기를 시작하지 않는다](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L490) | R10 · 순수 메모리 동작 |
| [원장 사건 ID가 이미 쓰였다면 요청 결과를 남기기 전에 멈춘다](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L505) | R10 · 순수 메모리 동작 |
| [승인 쓰기 일부만 끝났으면 이후 정상 요청을 받지 않는다](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L535) | R11 · 순수 메모리 동작 |
| [거절의 첫 답 기록이 실패해도 객체를 차단한다](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L546) | R11 · 순수 메모리 동작 |
| [반환 목록을 수정하거나 거절 기록을 이용해 내부 돈을 바꿀 수 없다](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L555) | R12 · 순수 메모리 동작 |

### 1. 승인한 구매 요청을 다시 보내도 돈을 두 번 묶지 않는다

- **준비:** 사용 가능 1,000 USDT, 가격 100 USDT·BTC 3개 주문이다.
- **실행:** 같은 R1을 두 번 보낸다.
- **확인:** 두 번 모두 첫 승인·순번 1이다. 사용 가능 700 USDT·예약 300 USDT, 대기 주문·요청 이력·금전 원장 ID는 한 건이다. 원본·정책·시각·예약·두 원장 항목도 독립 숫자로 확인한다.
- **근거:** [실제 실행 순서](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L54) · [검증문 시작](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L59).

### 2. 거절한 요청은 잔고가 늘어도 갑자기 승인되지 않는다

- **준비:** 사용 가능 200 USDT에서 300 USDT가 필요한 R1을 거절한다.
- **실행:** 같은 객체의 잔고를 테스트에서 1,200 USDT로 준비 → R1 재전송 → 새 R2 승인 → R1 재전송 순서다.
- **확인:** R1은 계속 당시 사용 가능 200·필요 300·부족 100 USDT다. R2만 300 USDT를 예약해 사용 가능 900 USDT·예약 300 USDT, 승인 순번 1이 된다. 거절에는 원장 ID·승인 순번이 생기지 않는다.
- **근거:** [실제 실행 순서](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L85) · [검증문 시작](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L89).

### 3. 매도 재전송도 팔 BTC를 한 번만 묶는다

- **준비:** 사용 가능 3 BTC와 1,000 USDT, maker 0.1%·taker 0.2% 정책에서 2 BTC를 판다.
- **실행:** 같은 R1을 두 번 보낸다.
- **확인:** 사용 가능 1 BTC·예약 2 BTC, 사전 수수료 예약 0이다. USDT는 1,000 그대로다. 첫 정책·원장·시각·승인 순번과 요청 이력 한 건을 확인한다.
- **근거:** [실제 실행 순서](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L128) · [검증문 시작](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L134).

### 4. 요청 ID를 유지한 채 내용을 바꾸면 첫 답을 덮어쓰지 않는다

- **준비:** R1의 BUY 승인을 먼저 저장한다.
- **실행:** 마켓·주문 ID·방향·종류·유효 조건·가격·수량 중 하나씩 바꾼 7개 원본을 R1로 보내고 마지막에 원래 원본을 다시 보낸다.
- **확인:** 변경한 7개는 내용 충돌이다. 돈·순번·대기·요청 원본·첫 결과가 보존되고 원래 원본은 첫 승인을 받는다. 사용자 값 변경은 별도 사용자 키 테스트에서 다룬다.
- **근거:** [실제 실행 순서](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L182) · [검증문 시작](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L185).

### 5. 새 요청으로 기존 주문을 보내면 새 요청의 첫 답은 중복 거절이다

- **준비:** R1이 이미 같은 마켓·주문 ID를 승인했다.
- **실행:** 새 R2로 그 주문 제출 → R2 재전송 → R2의 수량 변경 순서다.
- **확인:** 앞의 두 번은 주문 중복, 내용 변경은 충돌이다. R1 승인과 R2 최초 중복 거절 이력만 있고 돈·원장·대기·승인 순번은 하나다.
- **근거:** [실제 실행 순서](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L215) · [검증문 시작](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L218).

### 6. 기존 입구와 새 입구를 번갈아 써도 같은 주문은 두 번 승인되지 않는다

- **준비:** 요청 ID 없는 기존 입구와 새 입구를 같은 상태 객체에 연결한다.
- **실행:** 기존 입구 승인 후 새 입구로 제출하는 경우와, 새 입구 승인 후 기존 입구로 제출하는 경우를 각각 실행한다.
- **확인:** 두 번째는 모두 주문 중복이다. 사용 가능 700 USDT·예약 300 USDT, 승인 순번·대기는 하나다. 요청 이력에는 새 입구에서 처리한 승인 또는 거절 한 건만 남는다.
- **근거:** [실제 실행 순서](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L241) · [검증문 시작](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L246).

### 7. 정상 거절도 그 요청의 첫 답으로 남는다

- **준비:** 유효한 식별자에 MARKET, IOC, 수량 0, 표현할 수 없는 1 최소단위 가격·수량을 각각 준비한다.
- **실행:** 각 입력을 별도 상태에서 같은 요청 ID로 두 번 보낸다.
- **확인:** 앞의 두 입력은 미지원 조건, 세 번째는 수량 오류, 네 번째는 예약 금액 표현 불가다. 요청 원본·최초 사유는 한 건, 자금·원장 ID·대기·승인 순번은 추가되지 않는다.
- **근거:** [실제 실행 순서](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L265) · [검증문 시작](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L276).

### 8. 서버 정책이 바뀌어도 재전송의 첫 답은 바뀌지 않는다

- **준비:** R1의 정상 BUY 승인과 당시 마켓·정책·원장 ID·시각을 보관한다.
- **실행:** R1에 바뀐 마켓 단위·수수료·ID·시각을 제공한다. 또 불일치 마켓·빈 원장 ID·현물 외 정책을 각각 제공하고, 같은 잘못된 문맥을 새 요청·새 주문에도 제공한다.
- **확인:** R1은 첫 문맥과 결과를 유지한다. 새 요청 대조군은 실제로 내부 오류를 내며 상태·요청 이력은 그대로다. 재전송에서만 새 문맥을 사용하지 않음을 확인한다.
- **근거:** [실제 실행 순서](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L292) · [검증문 시작](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L295).

### 9. 다른 사용자의 같은 요청 ID를 내 요청과 섞지 않는다

- **준비:** 사용자 A·B가 각각 사용 가능 1,000 USDT를 가지고 다른 주문에 같은 R1을 사용한다.
- **실행:** 두 주문 승인 → B의 새 R2로 A의 승인된 주문 키 제출 → B의 기존 R1을 그 내용으로 변경 → B의 원래 R1 재전송 순서다.
- **확인:** A·B는 각각 300 USDT만 예약하고 승인 순번은 1·2다. B의 R2는 중복 거절, 바꾼 R1은 충돌, 원래 R1은 B의 첫 승인을 받는다. B에게 A의 승인 원본을 반환하지 않는다.
- **근거:** [실제 실행 순서](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L332) · [검증문 시작](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L337).

### 10. 요청 ID 길이는 글자의 code point로 센다

- **준비:** 요청 ID x 한 개, x 64개, 보조문자 64개를 각각 준비한다.
- **실행:** 각각 새 상태에서 승인 요청을 보낸다.
- **확인:** 모두 승인된다. 원문 ID가 그대로 기록되고 사용 가능 700 USDT·예약 300 USDT가 된다. 보조문자 64개를 UTF-16 128칸이라는 이유로 거절하지 않는다.
- **근거:** [실제 실행 순서](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L375) · [검증문 시작](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L378).

### 11. 잘못된 식별자는 이미 아는 요청이어도 먼저 거절한다

- **준비:** R1을 승인한 상태에 잘못된 요청 ID와 기존 주문 식별자들을 준비한다.
- **실행:** 요청 ID 8개 오류, 사용자·마켓·주문 ID 각각 6개 오류와 사용자 콜론을 제출한다. 빈 기존 ID는 생성자에서 따로 확인한다.
- **확인:** 요청 ID 오류와 기존 주문 ID 오류 사유를 구분한다. 충돌·중복으로 바뀌지 않으며 이력·돈·순번·원장 사용 이력이 추가되지 않는다. 빈 기존 ID는 코어에 도달한 사례로 세지 않는다.
- **근거:** [실제 실행 순서](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L385) · [검증문 시작](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L392).

### 12. 허용 길이와 마켓·주문 ID의 콜론을 과도하게 거절하지 않는다

- **준비:** 64개 일반 문자·64개 보조문자를 사용자/마켓/주문 ID에 넣고, 콜론은 마켓·주문 ID에만 넣는다.
- **실행:** 마켓 정의·사용자 잔고를 해당 입력과 연결해 8개 유효 조합을 각각 제출한다.
- **확인:** 모두 순번 1로 승인되고 원문이 남는다. 사용 가능 700 USDT·예약 300 USDT다. 사용자 콜론은 앞의 잘못된 식별자 테스트에서 거절한다.
- **근거:** [실제 실행 순서](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L425) · [검증문 시작](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L434).

### 13. 겉보기 비슷한 요청 ID를 임의로 합치지 않는다

- **준비:** R1, 앞뒤 공백이 있는 R1, r1, é, 분해형 e+악센트, R:1의 6개 원문과 다른 주문 ID를 준비한다.
- **실행:** 같은 사용자가 여섯 요청을 차례로 제출한다.
- **확인:** 원문 여섯 개가 그대로 별개 이력에 남는다. 대기 6건·승인 순번 6, 사용 가능 1,200 USDT·예약 1,800 USDT다.
- **근거:** [실제 실행 순서](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L444) · [검증문 시작](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L449).

### 14. 상태 변경 전 내부 오류는 요청의 첫 거절로 저장하지 않는다

- **준비:** 첫 주문을 승인해 700 USDT가 남은 상태에서 새 주문 키와 새 R1을 준비한다.
- **실행:** 불일치 마켓·현물 외 정책·빈 원장 ID·이미 쓴 원장 ID를 각각 제공한 후 같은 R1을 올바른 문맥과 새 원장 ID로 재시도한다.
- **확인:** 첫 오류에는 이력·돈·순번 변화와 객체 차단이 없다. 수정 후에는 순번 2로 승인돼 사용 가능 400 USDT·예약 600 USDT가 된다. 중복·잔고 부족으로 오류 위치를 가리지 않는다.
- **근거:** [실제 실행 순서](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L460) · [검증문 시작](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L475).

### 15. 승인 순번 범위를 넘으면 쓰기를 시작하지 않는다

- **준비:** 테스트에서 승인 순번을 Long 최대값으로 준비한다.
- **실행:** 새 R1의 승인 계산을 시도한다.
- **확인:** 범위 초과 오류가 난다. 요청 이력·원장 사용 ID·돈·대기 상태는 그대로이며 객체가 차단되지 않는다.
- **근거:** [실제 실행 순서](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L490) · [검증문 시작](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L495).

### 16. 원장 사건 ID가 이미 쓰였다면 요청 결과를 남기기 전에 멈춘다

- **준비:** 첫 주문을 승인해 700 USDT가 남은 상태에서 새 주문의 실제 사건 ID를 계산해 충돌 준비로만 사용 이력에 넣는다.
- **실행:** 새 R1을 처리해 충돌을 확인하고 테스트 준비의 충돌 ID를 제거한 뒤 같은 R1을 재시도한다.
- **확인:** 첫 실패는 내부 오류이고 요청 이력·돈·순번은 그대로, 객체는 사용 가능하다. 충돌을 제거한 뒤 순번 2로 승인되고 사용 가능 400 USDT·예약 600 USDT가 된다. 계산기를 기대 금액 생성에 쓰지 않는다.
- **근거:** [실제 실행 순서](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L505) · [검증문 시작](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L511).

### 17. 승인 쓰기 일부만 끝났으면 이후 정상 요청을 받지 않는다

- **준비:** 이전 승인·이전 거절을 보관한 상태에서 실제 저장 구조의 쓰기 위치만 테스트로 통제한다.
- **실행:** 잔고·대기 주문·금전 원장 ID·사건 ID·요청 이력의 5개 위치 × 쓰기 전/후 × Exception/Error의 20개 조합에서 새 승인을 시도한다.
- **확인:** 같은 오류가 그대로 전달되고 객체가 차단된다. 신규·실패 요청·이전 승인/거절 재전송·충돌/잘못된 식별자·기존 입구·정상 조회 모두 차단된다. 이전 반환값 보존만 확인하며 내부 롤백은 주장하지 않는다.
- **근거:** [실제 실행 순서](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L535) · [검증문 시작](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L539).

### 18. 거절의 첫 답 기록이 실패해도 객체를 차단한다

- **준비:** 이전 정상 이력이 있는 상태에서 새 IOC 거절을 준비하고 요청 이력 쓰기를 통제한다.
- **실행:** 요청 이력 쓰기 전/후 × Exception/Error의 4개 조합에서 처리한다.
- **확인:** 정상 거절을 반환하지 않고 같은 오류를 전달한다. 이후 모든 접수·재전송·정상 조회를 차단한다. 돈을 안 옮겼어도 첫 답 기록을 신뢰할 수 없는 경계를 확인한다.
- **근거:** [실제 실행 순서](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L546) · [검증문 시작](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L549).

### 19. 반환 목록을 수정하거나 거절 기록을 이용해 내부 돈을 바꿀 수 없다

- **준비:** 초기 잔고 목록과 첫 거절 snapshot을 보관한다.
- **실행:** 반환 목록 수정 시도 → 같은 업무 키·같은 원장 ID를 새 유효 요청으로 승인 → 옛 거절 재전송 순서다.
- **확인:** 수정 시도는 거절된다. 거절이 쓰지 않은 원장 ID를 승인에서 한 번 쓸 수 있으며 순번은 1이다. 사용 가능 700 USDT·예약 300 USDT다. 초기/첫 관찰값과 옛 거절, 실제 원장 항목은 그대로 보호된다.
- **근거:** [실제 실행 순서](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L555) · [검증문 시작](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L562).

공통 검증문은 [BUY의 독립 금액·예약·두 원장 항목](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L600)과 [첫 부족 금액](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L643)을 비교한다. [요청 기록](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L652)과 [첫 결과의 의미 있는 필드](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L672)를 대조하며 후보 객체의 참조 동일성을 요구하지 않는다. [전체 snapshot 대조](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L705)는 돈·대기·이력·순번의 누락과 추가를 확인한다.

[실패 뒤 차단 검증](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L730)은 [테스트용 쓰기 실패](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L768)만 주입한다. [후속 자금 준비](../domain-trading/src/test/kotlin/com/exchange/core/trading/OrderAdmissionRequestTest.kt#L803)는 R05의 같은 객체 이력을 유지하기 위한 장치이며, 제품 입금·복구 기능이 아니다.

## 실행·독립 리뷰 상태

테스트 기대값은 합의한 R01~R12에서 도출했다. BUY 300 USDT, SELL 2 BTC, 부족 100 USDT 등은 고정 숫자와 실제 잔고·예약·원장 필드를 비교한다. 재전송에서는 최초 결과의 의미 있는 필드를 비교하며 객체 참조 동일성을 계약으로 추가하지 않는다. 원장 사건 ID 계산기를 다시 사용한 곳은 충돌을 만드는 테스트 준비이며 기대 금액의 정답 생성이 아니다.

2026-10-16에 아래 검사를 완료했다. 이 결과는 로컬 검사이며, PR의 CI 결과와 구분한다.

| 검사 | 실제 실행 결과 |
| --- | --- |
| domain-trading | 82개 통과. 기존 계산기 36개·후보 7개·상태 20개·신규 요청 19개 |
| domain-order / domain-ledger / domain-fee | 각각 53개 / 39개 / 37개 통과 |
| architecture-tests | 375개 통과. 실제 운영 구조 P01~P09 수집·성공을 verifyArchitectureReport로 별도 확인 |
| 공통 ktlintCheck | 전체 프로젝트 통과 |
| 합계 | 586개, 실패·오류·skip 0 |

저장소 안에서 다음과 같이 실행한다. `cd`는 현재 체크아웃의 저장소 루트로 이동한다.

```sh
cd "$(git rev-parse --show-toplevel)"
./gradlew :domain-trading:test :domain-order:test :domain-ledger:test :domain-fee:test :architecture-tests:verifyArchitectureReport ktlintCheck --console=plain
```

구조 검사에는 검사 도구의 정상·위반 예제도 포함한다. 이 결과로 DB 저장·HTTP·동시성·복구를 검증했다고 해석하지 않는다. 기존 HTTP·DB 경로는 변경하지 않았고 해당 통합 테스트는 이번에 새로 실행하지 않았다.

작성 대화를 공유하지 않은 별도 리뷰어가 고정 사본의 별도 체크아웃에서 전체 변경을 검토하고, 위 검사와 독립 경계 실험을 실행했다. 요청 ID의 hash 충돌 분리, 수수료가 있는 BUY 재전송, 첫 거절에 잘못된 새 서버 문맥을 제공한 경우, 거절 후 내용 충돌을 추가로 확인했다. 문서 링크와 테스트 구성 표기의 지적을 수정한 뒤 재검토했으며, 확인한 범위에 미해결 결함은 없다. 테스트 통과와 독립 리뷰는 사람의 이해·승인을 뜻하지 않는다.

## 이번 보장의 한계

한 실행자가 순서대로 호출하는 **같은 메모리 객체의 생존 기간**에 대한 계약이다. 스레드 안전성·동시 호출, 영구 저장·재시작 복구, 무기한 보관·이력 삭제/TTL, HTTP 외부 재시도 API, Kafka·DB·Aeron, 체결·정산·취소·대기 제거, 원장 생성 공통화는 포함하지 않는다. 기존 API·SQL 경로를 바꾸지 않았다.
