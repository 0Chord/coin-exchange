# #51 · 매칭 결과를 빠짐없이 한 번만 기억하기

**이번 변경:** 한 명령의 매칭 결과를 검사해 메모리에 한 묶음으로 등록한다. 똑같은 결과를 다시 받으면 최초 결과를 반환하고, 누락·순번 오류·내용 충돌은 기존 이력을 바꾸지 않고 거절한다.

현재는 로컬 구현·검증 중이다. 기준 통합은 `1a1486e8bc9faa20c17c2d19f5003db1538bcb46`, 브랜치는 `feat/matching-result-history/51`이다. [합의 명세](https://github.com/0Chord/coin-exchange/issues/51)를 따른다. 실제 Aeron 콜백·주문장·잔고·정산·DB 경로에는 아직 연결하지 않는다.

```text
명령 ID + 원본 명령 + 예상 개수·순번 범위 + 전체 이벤트
                           ↓
       같은 키가 있는가? ── 예 → 전부 같으면 최초 결과 반환
                           │     다르면 내용 충돌, 기존 결과 유지
                         아니오
                           ↓
          개수·마켓·순번·입력 연결·수량·중복 제출 검사
                           ├─ 위반 → 등록 없이 거절, 다음 정상 호출 가능
                           ↓
          반영 시작 → 전체 결과·마지막 순번·사용 주문 ID 반영
                           ├─ 내부 쓰기 실패 → 이 상태의 등록·정상 조회 차단
                           ↓
                       새 결과 반환
```

## 왜 하나라도 빠졌으면 완료로 남기면 안 될까

가격 100·수량 3의 BUY B1을 생각해 보자. 기존 엔진이 만든 결과는 다음과 같다. 수량은 설명을 위한 최소 단위이고 수수료 계산은 하지 않는다.

| 순번 | 결과 |
| --- | --- |
| 3 | S1과 가격 90·수량 1 체결 |
| 4 | S2와 가격 95·수량 1 체결 |
| 5 | B1의 남은 수량 1을 가격 100에 대기 |

예상 개수는 3, 범위는 3~5이며, 원수량 3 = 체결 1 + 체결 1 + 잔량 1이다. 두 번째 체결이 빠진 목록을 완료로 기억하면, 이후 이 기록을 쓰는 정산·복구가 그 체결을 놓칠 수 있다. 따라서 일부만 먼저 등록하고 완료 표시를 덧붙이는 방식으로 만들지 않았다.

이번 코어는 **예상 개수·순번 범위·원본 수량과 실제 목록을 비교한다.** 한 항목이 빠지면 정상 결과를 만들지 않고, 기존 마지막 순번도 유지한다. 잘못된 목록을 바로잡은 뒤 같은 내부 명령 ID로 다시 등록할 수 있다.

이는 실제 엔진에서 나온 결과의 진실까지 확인했다는 뜻은 아니다. 원본과 메타데이터까지 일관되게 위조한 입력, 입력 로그에서 결과 묶음 전체가 사라진 상황은 이 자료만으로 알아낼 수 없다. 실제 실행·보존·복구 연결에서 확인할 범위다. 등록 거절이 이미 실행된 MatchingEngine의 주문장을 되돌리는 것도 아니다.

<details markdown="1">
<summary>코드에서 맡은 역할과 읽을 순서</summary>

| 파일 | 역할 | 먼저 볼 부분 |
| --- | --- | --- |
| [MatchingCommandResult.kt](../domain-matching/src/main/kotlin/com/exchange/core/matching/MatchingCommandResult.kt) | 검증 대상과 등록 결과를 담는 값. 이벤트 목록을 방어적으로 복사 | 원본 명령·개수·범위·이벤트, 전체 값 비교 |
| [MatchingResultState.kt](../domain-matching/src/main/kotlin/com/exchange/core/matching/MatchingResultState.kt) | 순차 호출되는 메모리 이력의 소유자 | register → validate → 쓰기 구간 → snapshot/find |
| MatchingResultStateTest 및 연결 테스트 | 고정 입력·기대값과 위반 시 상태를 대조 | 정상 3항목, 누락, 재전송·충돌, 실제 쓰기 뒤 오류 |

원본 명령과 MatchingEvent의 기존 값 타입을 재사용했다. 새 모듈·의존성·메시지 형식·DB 테이블·실행기·테스트용 production 게이트는 추가하지 않는다.

### 기존 규칙을 새 검증에 연결한 방법

| 원래 규칙·소스 | 새 경로의 적용 | 필요한 정상·위반 대조 |
| --- | --- | --- |
| Price·MarketId·OrderId·UserId 생성자 | 양수 가격·비어 있지 않은 ID는 기존 타입이 보장 | 생성 불가능한 값은 억지로 위조하지 않음 |
| Quantity는 잔량 때문에 0 허용 | 체결과 잔량 대기·취소 성공의 수량은 추가로 양수 검사 | 정상 1과 잘못된 0을 비교 |
| SubmitOrderCommand는 양수 원수량 | 원본 값 재사용. LIMIT/GTC 및 이벤트와의 수량 보존은 여기서 검사 | 전량·부분·미체결, MARKET/IOC 거절 |
| MatchingEngine의 maker 가격·가격/FIFO 우선순위 | 실제 주문장을 새로 복제하지 않음. 지정가 범위·taker 연결·수량만 검사 | 고정 엔진 조합에서 S1→S2 체결 확인. 이 코어만으로 FIFO 입증이라고 하지 않음 |
| 마켓별 이벤트 순번 | 결과 내부 연속성·등록 이력의 다음 순번 확인 | 겹침·건너뜀·역순·중복 거절 |
| Long 수량·순번 | Math.addExact로 합산·범위 계산 | 범위를 넘겨 작은 양수로 감기는 반례 거절 |

</details>

<details markdown="1">
<summary>재전송·새 제출·취소 결과가 다른 이유</summary>

등록 키는 (마켓, 내부 명령 ID)다. BTC의 C3과 SUI의 C3은 서로 다른 결과이고, 문자열을 콜론으로 합쳐 키를 만들지 않는다. 원본 명령과 이벤트·메타데이터 전체를 비교하며 일부 해시만 대조하지 않는다.

- 같은 C3·같은 전체 내용: 최초 결과를 반환한다. 이후 C4까지 등록됐어도 C3의 옛 순번을 새 명령처럼 검사하지 않는다.
- 같은 C3·다른 내용: CONTENT_CONFLICT. 기존 결과를 덮어쓰지 않는다.
- 새 내부 명령 ID·이미 처리한 같은 마켓의 SUBMIT 주문 ID: DUPLICATE_SUBMIT. 같은 주문을 다시 매칭한 결과로 추가하지 않는다.
- 같은 주문에 대한 별도 CANCEL 내부 명령들: 각각 취소 성공 또는 취소 거절 한 건으로 표현할 수 있다. 주문 ID만으로 취소 결과를 합치지 않는다.

개수·순번·수량·이벤트 연결 등 잘못된 새 묶음은 INVALID_RESULT로 구분한다. 이 거절은 거래 접수의 최초 자금 부족 응답을 저장하는 #86 동작과 다르다. 결과 등록이 완료되지 않았으므로 잘못된 묶음을 정상 값으로 고쳐 다시 보낼 수 있다.

내부 명령 ID는 호출자가 제공한다. 현재 코어가 시간·UUID·세션 ID로 새로 만들지 않는다. 실제 승인 기록과의 연결·전송 표현은 다음 통합에서 정한다.

</details>

<details markdown="1">
<summary>검사 실패와 반영 중 오류 뒤 상태</summary>

검사가 끝날 때까지 쓰기를 하지 않는다. 잘못된 새 입력이나 내용 충돌이면 기존 결과·마지막 순번·사용 주문 ID를 유지하고 다음 정상 등록을 허용한다.

반영을 시작하면 isBlocked를 true로 두고 결과·마지막 순번·제출 주문 ID를 반영한다. 전부 끝난 때만 false로 되돌린다. 쓰기 중 예외나 Error가 나면 true가 남으므로 후속 등록·최초 결과 재전송·find·snapshot이 모두 차단된다. 잘못될 수 있는 상태를 정상 조회로 내보내지 않기 위해서다.

이것은 DB 트랜잭션의 롤백이 아니다. 이미 쓴 일부 값은 내부에 남을 수 있으며 자동 초기화·재개 기능은 없다. 오류 전 반환한 결과와 snapshot은 내부 자료와 분리되어 있으므로 이후 쓰기나 호출자 수정에 영향을 받지 않는다.

테스트는 공개 실패 스위치를 만들지 않고 테스트 안에서만 내부 map을 교체한다. 실제 put으로 값을 쓴 직후 예외를 던져 차단을 확인한다. 마지막 순번 Long.MAX_VALUE 경계도 반복 등록 대신 테스트의 내부 값 준비로 다루며, 정상 API 실행만으로 그 순번에 도달했다고 설명하지 않는다.

</details>

<details markdown="1">
<summary>새 테스트의 준비·실행·확인</summary>

아래는 `feat/matching-result-history/51`의 현재 추가 테스트를 실제 검증문과 대조한 설명이다. 각 링크는 해당 테스트 메서드로 이어진다. 테스트 안의 반복 입력은 같은 행에 묶었다. 이 표는 소스 설명이며, 통과 여부와 실제 실행 기록은 다음 절에서 구분한다.

공통 [준비와 검증 도우미](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingResultTestFixture.kt#L19)는 다음 뜻이다.

- **공통 준비:** 새 상태에 BTC-USDT의 C1/S1/U-S1, SELL 90·1·순번 1과 C2/S2/U-S2, SELL 95·1·순번 2를 실제 `register`로 넣는다. 이 준비는 MatchingEngine을 호출하지 않는다.
- **고정 C3:** B1/U-B1의 BUY 100·원수량 3. 이벤트는 S1/U-S1→B1/U-B1의 순번 3·가격 90·수량 1, S2/U-S2→B1/U-B1의 순번 4·가격 95·수량 1, B1/U-B1 BUY의 순번 5·가격 100·잔량 1이다. maker/taker 사용자와 방향까지 값 비교에 포함한다. 예상 3개·범위 3~5를 실제 반환 목록의 크기나 마지막 값에서 계산하지 않는다.
- **공통 보존 검증:** `assertRejectedWithoutChanges`가 `MatchingResultException`의 reason, 호출 전후 snapshot 전체 값 일치, `isBlocked=false`를 함께 확인한다. 기본 reason은 INVALID_RESULT이며 충돌·중복 테스트는 다른 값을 명시한다. 내부 사용 주문 ID 집합을 직접 비교하지는 않으며, 거절 뒤 정상 재등록이 있는 행은 그 후속 호출까지 관측한다.
- **값과 객체 구분:** `assertEquals`는 지정한 값·목록 전체를 비교하고, `assertSame`은 최초 결과와 같은 객체인지 확인한다. 테스트 이름만으로 추가 저장소·자금 상태까지 보존했다고 해석하지 않는다.

### 정상 결과·누락·순번·재전송과 불변성

| 테스트 이름·위치 | Given — 준비 | When — 실행 | Then — 실제 assertion |
| --- | --- | --- | --- |
| [체결 두 건과 잔량 한 건을 모두 확인한 뒤 결과 하나로 등록한다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingResultStateTest.kt#L19) | 공통 S1·S2 준비와 B1 BUY 100·3. | C3, 예상 3개·3~5로 등록. | 명령·메타데이터·세 이벤트 전체 값 일치, find는 같은 객체. 결과 ID는 C1·C2·C3, BTC 마지막 5, 다른 마켓·없는 ID 조회는 null. |
| [체결이나 잔량이 빠지면 완료로 등록하지 않고 기존 순번을 유지한다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingResultStateTest.kt#L39) | 공통 준비. 중간 체결 제거·마지막 잔량 제거·빈 목록을 각각 준비. | 예상 3개·3~5는 그대로 등록하고, 거절 뒤 정상 C3을 등록. | INVALID_RESULT와 공통 보존 검증. C3은 null, BTC 마지막 2. 정정 후 반환 이벤트는 고정 세 값과 같음. |
| [새 마켓의 체결 없는 매수와 매도는 원수량 전부를 순번 1의 잔량으로 등록한다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingResultStateTest.kt#L53) | 빈 상태. BUY와 SELL 각각 O1·U-O1, 가격 100·원수량 3. | 잔량 3 한 개를 C1, 1개·1~1로 등록. | 원본 명령·잔량 이벤트 값 일치, snapshot 결과는 반환 결과 하나, BTC 마지막 1. |
| [한 건 또는 여러 건으로 전량 체결되면 잔량 이벤트 없이 등록한다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingResultStateTest.kt#L69) | 한 S1 준비 뒤 B1 원수량 1, 또는 공통 두 S 준비 뒤 B1 원수량 2. | 체결 한 개를 2~2, 체결 두 개를 3~4로 각각 등록. | 반환 목록은 체결만 정확히 한 개·두 개. 마지막 순번은 각각 2·4. |
| [매수와 매도는 지정가와 같은 가격의 전량 체결을 허용한다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingResultStateTest.kt#L88) | 빈 상태. BUY·SELL 각각 T1, 지정가 100·수량 1. 다른 주문 M1과 가격 100 체결. | C1, 1개·1~1로 등록. | 반환 이벤트가 입력한 체결 한 개와 같고 BTC 마지막 1. maker 실제 존재는 준비하지 않음. |
| [매도 체결가는 지정가 이상이어야 하며 높은 체결가는 허용한다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingResultStateTest.kt#L100) | 빈 상태. SELL 지정가 100·수량 1, 체결가 99. | 99 체결 등록을 거절받은 뒤 같은 키에 101 체결을 등록. | 99는 INVALID_RESULT와 공통 보존 검증, C1 null. 101은 그 체결 하나를 반환. |
| [예상 개수와 범위는 양수이며 실제 연속 순번과 정확히 일치해야 한다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingResultStateTest.kt#L113) | 공통 준비. 개수 0·-1·2·4, 첫 순번 0·-1, 마지막 0·4·6, 역범위 5~3, 이벤트 3·5·4 / 3·3·5 / 3·0·5를 각각 준비. | 각 변경 묶음을 새 C3으로 등록한 뒤 정상 C3 등록. | 모두 INVALID_RESULT와 공통 보존 검증, C3 null. 정상 재시도 이벤트는 고정 세 값과 같음. |
| [빈 내부 명령 ID와 지원하지 않는 제출 조건은 상태 변경 전에 거절한다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingResultStateTest.kt#L141) | 공통 준비. ID 빈 문자열·공백 두 칸, 또는 명령 MARKET·IOC. | 각 입력을 등록한 뒤 정상 C3 등록. | 각각 INVALID_RESULT와 공통 보존 검증. 이어 정상 세 이벤트를 반환. |
| [체결의 마켓 taker 방향 수량과 지정가 위반을 각각 거절한다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingResultStateTest.kt#L155) | 공통 준비. 첫 체결의 마켓·taker 주문·taker 사용자·side를 변경하거나 maker 주문=B1, 가격 101, 수량 0·2로 변경. | 나머지 두 이벤트와 예상 3개·3~5를 유지해 등록 후 정상 재시도. | 각각 INVALID_RESULT와 공통 보존 검증, C3 null. 정정 후 고정 세 이벤트 반환. |
| [maker와 taker의 사용자가 같아도 서로 다른 주문이면 새 거래 정책으로 거절하지 않는다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingResultStateTest.kt#L177) | 공통 준비. 첫 체결 maker 사용자를 U-B1으로 바꾸되 maker 주문 S1과 taker 주문 B1은 다름. | 변경한 세 이벤트를 C3으로 등록. | 반환 목록 전체가 그 입력과 같고 BTC 마지막 5. 새 자기 체결 금지 정책을 덧붙이지 않음. |
| [잔량의 마켓 주문 사용자 방향 가격과 양수 수량을 원본에 대조한다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingResultStateTest.kt#L186) | 공통 준비. 잔량 이벤트의 마켓·주문·사용자·side·가격 99·수량 0 또는 2를 각각 변경. | 두 체결과 예상 3개·3~5는 유지해 등록 후 정상 재시도. | 각각 INVALID_RESULT와 공통 보존 검증, C3 null. 정정 후 고정 세 이벤트 반환. |
| [체결과 잔량이 모두 양수여도 합이 원수량보다 적거나 많으면 거절한다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingResultStateTest.kt#L207) | 공통 준비. 이벤트 수량 합은 3 그대로이고 원본 수량만 2 또는 4. | 새 C3으로 등록한 뒤 원수량 3으로 정정 등록. | 둘 다 INVALID_RESULT와 공통 보존 검증, C3 null. 정정 후 고정 세 이벤트 반환. |
| [수량 합이 같아도 잔량 뒤 체결과 두 잔량 및 취소 혼합을 허용하지 않는다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingResultStateTest.kt#L219) | 공통 준비. 잔량→체결→체결, 체결→잔량→잔량, 두 체결 뒤 취소 성공 또는 거절을 준비. | 각 세 이벤트를 C3으로 등록 후 정상 재시도. | 각각 INVALID_RESULT와 공통 보존 검증. 정정 후 고정 세 이벤트 반환. 수량만 같다고 잘못된 이벤트 구조를 허용하지 않음. |
| [동일한 전체 결과 재전송은 최초 객체를 반환하고 이력을 늘리지 않는다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingResultStateTest.kt#L236) | 공통 준비 뒤 C3 정상 등록과 snapshot 보관. | 같은 값의 원본 복사·새 가변 이벤트 목록으로 C3 재등록. | 반환·find 모두 최초 결과와 같은 객체(assertSame), snapshot 전체 값도 이전과 같음. |
| [뒤에 다른 결과가 등록되어도 오래된 동일 결과를 최초 객체로 재사용한다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingResultStateTest.kt#L249) | 공통 준비와 C3 뒤 B1 취소 C4를 순번 6에 등록. | 예전 C3의 3~5 결과 재등록. | 최초 C3과 같은 객체, snapshot 유지. ID는 C1·C2·C3·C4, BTC 마지막은 6. |
| [같은 키의 원본 명령을 바꾸면 전체 내용 충돌이며 최초 결과를 보존한다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingResultStateTest.kt#L264) | C3 등록 완료. 주문·사용자·side·가격·원수량·MARKET·IOC·CANCEL 종류를 하나씩 변경. | 같은 키와 기존 이벤트로 등록 시도한 뒤 원래 C3 재전송. | CONTENT_CONFLICT와 공통 보존 검증. find와 원본 재전송은 최초 객체를 반환. |
| [같은 키의 이벤트 의미 필드 순서 또는 메타데이터를 바꾸면 충돌이다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingResultStateTest.kt#L289) | C3 등록 완료. 예상 개수·첫/마지막 순번·maker 사용자/주문·가격·수량·목록 순서·항목 누락을 각각 변경. | 같은 C3으로 다시 등록 후 원본 재전송. | CONTENT_CONFLICT와 공통 보존 검증. find와 원본 재전송은 최초 객체를 반환. |
| [새 내부 ID로 같은 제출 주문을 등록하면 중복이며 순번을 소비하지 않는다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingResultStateTest.kt#L313) | C3의 B1 결과 등록 완료. 새 C9에 같은 B1 제출과 순번 6 잔량을 준비. | C9 시도 뒤 다른 주문 B2를 C4·순번 6으로 등록. | DUPLICATE_SUBMIT와 공통 보존 검증, C9 null. B2 이벤트가 반환되고 BTC 마지막 6. |
| [다른 마켓은 같은 내부 명령 ID와 주문 ID를 순번 1부터 독립 등록한다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingResultStateTest.kt#L329) | BTC의 C3·B1 등록 완료. SUI에 같은 C3·B1의 잔량 3을 준비. | SUI 결과를 1개·1~1로 등록. | 마켓별 find가 각각의 원본 객체를 반환. SUI 이벤트 전체 일치, 결과 ID 네 개, BTC 마지막 5·SUI 마지막 1. |
| [마켓과 ID에 구분자가 있어도 명령 키와 제출 키가 충돌하지 않는다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingResultStateTest.kt#L345) | 빈 상태. 마켓 a:b / 명령·주문 c와 마켓 a / 명령·주문 b:c. | 각 마켓에 순번 1 결과를 등록. | 두 find는 각 반환 객체와 동일, 결과 목록은 정확히 두 객체, 두 마켓 마지막 순번은 각각 1. |
| [신규 결과는 기존 범위와 겹치거나 다음 순번을 건너뛸 수 없다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingResultStateTest.kt#L364) | 공통 준비 뒤 신규 B2에 순번 2 또는 4. 별도로 빈 상태에 첫 순번 2. | 등록을 시도하고 B2 사례는 올바른 순번 3으로 다시 등록. | 각 시도는 INVALID_RESULT와 공통 보존 검증. B2 사례의 C3 null, 정정 후 순번 3 이벤트 반환. 빈 상태도 순번 2 시작을 거절. |
| [입력 목록을 바꾸거나 반환 목록 수정을 시도해도 등록 결과는 불변이다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingResultStateTest.kt#L383) | 공통 준비와 가변 목록으로 C3 등록, snapshot 보관. | 입력 첫 체결 가격을 바꾸고 clear. 결과 이벤트·snapshot 결과 목록 clear, 순번 map 변경 시도. | 세 반환 자료 변경은 UnsupportedOperationException. 결과·find의 이벤트는 고정값, snapshot 전체와 BTC 마지막 5 유지. |
| [이전 조회 복사본은 이후 결과 등록으로 변하지 않는다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingResultStateTest.kt#L402) | 공통 준비의 snapshot을 먼저 보관. | C3 정상 등록 후 과거·현재 snapshot 관측. | 과거 ID는 C1·C2, 마지막 2. 현재 ID는 C1·C2·C3, 마지막 5. |

### 취소 결과

| 테스트 이름·위치 | Given — 준비 | When — 실행 | Then — 실제 assertion |
| --- | --- | --- | --- |
| [같은 대상을 여러 내부 명령으로 취소 성공과 거절 결과에 남길 수 있다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingResultCancellationTest.kt#L14) | 공통 준비. S1·U-S1에 취소 성공 순번 3, 거절 순번 4·5. | CC1·CC2·CC3을 등록하고 CC1 재전송. | 세 반환 목록이 각 고정 이벤트와 같고 CC1은 최초 객체 재사용. ID는 C1·C2·CC1·CC2·CC3, BTC 마지막 5. |
| [취소 결과의 대상 요청자 마켓 양수 잔량과 비어 있지 않은 사유를 검사한다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingResultCancellationTest.kt#L33) | 공통 준비. 취소 성공의 마켓·주문·사용자·잔량 0, 거절의 마켓·주문·사용자·빈 사유를 각각 변경. | CC1·1개·3~3으로 등록 후 올바른 취소 성공으로 정정. | 각각 INVALID_RESULT와 공통 보존 검증, CC1 null. 정정 후 정상 취소 한 개 반환. |
| [취소는 성공 또는 거절 하나만 허용하고 체결과 잔량 대기를 거절한다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingResultCancellationTest.kt#L58) | 공통 준비. 성공+거절, 성공 두 개, 성공+체결, 체결 하나, 잔량 대기 하나. | 각 목록에 맞는 명시적 개수·범위로 CC1 등록 후 정상 취소 재시도. | 모두 INVALID_RESULT와 공통 보존 검증, CC1 null. 정상 취소 한 개 반환. 개수 불일치에만 기대어 혼합을 거절하는 검사가 아님. |
| [같은 취소 키의 거절 사유나 성공 여부를 바꾸면 기존 결과와 충돌한다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingResultCancellationTest.kt#L82) | 공통 준비 뒤 CC1 거절 등록 완료. 사유를 바꾸거나 성공 이벤트로 교체. | 같은 CC1에 변경 결과 시도 후 원래 거절 재전송. | CONTENT_CONFLICT와 공통 보존 검증. find와 원래 거절 재전송은 최초 객체를 반환. |

### 산술 경계와 내부 실패

| 테스트 이름·위치 | Given — 준비 | When — 실행 | Then — 실제 assertion |
| --- | --- | --- | --- |
| [원수량이 Long 최댓값인 정상 잔량은 overflow로 오인하지 않는다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingResultFailureTest.kt#L15) | 빈 상태. B1 원수량과 잔량이 모두 Long.MAX_VALUE. | C1·1개·1~1로 등록. | 명령·잔량 이벤트 전체 값 일치, BTC 마지막 1. 최댓값 자체는 허용. |
| [체결 수량 합이 음수나 원수량으로 감기는 overflow를 등록 전에 거절한다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingResultFailureTest.kt#L28) | 빈 상태. 원수량 MAX에 체결 MAX+1, 또는 원수량 1에 MAX+MAX+3. | 각 묶음 등록 시도 뒤 같은 C1을 정상 수량 1·순번 1로 정정. | INVALID_RESULT와 공통 보존 검증, C1 null. 정정 후 정상 잔량 한 개 반환. Long 덧셈이 음수 또는 1로 감겨도 승인하지 않음. |
| [체결과 잔량을 더할 때도 Long 범위를 넘으면 거절한다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingResultFailureTest.kt#L71) | 빈 상태. 원수량 MAX, 체결 MAX와 잔량 1. | C1·2개·1~2로 등록. | INVALID_RESULT와 공통 보존 검증, C1 null. 체결끼리의 합뿐 아니라 잔량 합산도 검사. |
| [선언한 범위의 끝 순번이 overflow되면 쓰기 전에 거절한다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingResultFailureTest.kt#L85) | 빈 상태. 예상 3개, 범위 MAX-1~MAX, 이벤트 순번 MAX-1·MAX·MIN. | C1 등록 시도. | INVALID_RESULT와 공통 보존 검증, C1 null. 범위를 넘긴 순번을 음수 이벤트로 이어 등록하지 않음. |
| [마켓 마지막 순번이 최댓값이면 다음 순번을 음수로 감아 등록하지 않는다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingResultFailureTest.kt#L102) | 공통 준비 뒤 reflection으로 lastSequences의 BTC 값을 MAX로 변경. | 새 B2·C3을 1개·MAX~MAX로 등록 시도. | INVALID_RESULT와 공통 보존 검증, C3 null, BTC 마지막 MAX 유지. 실제 등록을 MAX번 수행한 검사는 아님. |
| [입력 복사 중 예외는 기존 이력을 유지하고 다음 정상 등록을 허용한다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingResultFailureTest.kt#L120) | 공통 준비와 snapshot. 원소를 읽으면 지정한 IllegalStateException을 던지는 목록. | C3 등록 시도 후 정상 C3 등록. | 동일 예외 객체 전달(assertSame), snapshot 전체 유지, isBlocked=false, C3 null. 이어 고정 세 이벤트 반환. |
| [실제 결과 쓰기 직후 예외나 Error가 나면 재전송과 정상 조회를 모두 차단한다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingResultFailureTest.kt#L139) | 공통 준비와 이전 snapshot. records를 실제 put 후 IllegalStateException 또는 AssertionError를 던지는 map으로 교체. | C3 등록 실패 뒤 실패한 C3 재시도·신규 C4(B2) 등록·기존 C1 재전송·기존/실패/없는 키 find·snapshot 호출. | 동일 Throwable 전달, 실제 쓰기 표시와 isBlocked=true. 나열한 후속 호출 모두 IllegalStateException. 보관 snapshot은 C1·C2와 마지막 2 유지. |

### 실제 MatchingEngine과의 메모리 조합

| 테스트 이름·위치 | Given — 준비 | When — 실행 | Then — 실제 assertion |
| --- | --- | --- | --- |
| [실제 엔진의 S1 S2 B1 결과를 고정 oracle과 대조해 한 묶음씩 등록한다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingResultEngineIntegrationTest.kt#L13) | 실제 MatchingEngine과 빈 결과 상태. S1 SELL 90·1, S2 SELL 95·1, B1 BUY 100·3. | 엔진 처리 결과를 C1·1~1, C2·2~2, C3·3~5로 등록. 이후 실제 B1 취소를 C4·6~6으로 등록하고 C3 재전송. | 엔진 준비 결과와 C3 전체 이벤트가 별도 고정값과 일치. 원본·개수·범위·find·ID 순서·마지막 5 확인. 취소는 순번 6·잔량 1, 기존 C3 값·객체 유지, 마지막 6. |
| [실제 엔진의 매도 결과도 높은 maker 가격 두 건과 마지막 잔량을 보존한다](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingResultEngineIntegrationTest.kt#L51) | 실제 엔진과 빈 상태. B1 BUY 110·1, B2 BUY 105·1, S1 SELL 100·3. | 엔진 반환을 명시한 개수·범위로 C1·C2·C3 등록. | 두 준비 잔량, 체결 순번 3·가격 110 / 순번 4·가격 105, 잔량 순번 5·가격 100·수량 1을 전체 값 비교. 원본 명령·ID 순서·BTC 마지막 5 확인. |

### 준비 장치와 확인 범위의 한계

산술 표의 MAX/MIN은 각각 `Long.MAX_VALUE`/`Long.MIN_VALUE`다. 다음 순번 overflow 검사는 reflection으로 `lastSequences`만 MAX로 준비하므로, 정상 API로 그 지점에 도달하는 실행 이력이나 복구 기능을 검증하지 않는다.

[쓰기 실패 준비](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingResultFailureTest.kt#L165)는 `records` 원본 map에 실제 값을 쓴 뒤 실패를 던진다. `wasWritten()` assertion으로 그 지점에 도달했음을 확인하며, 차단 플래그를 테스트에서 직접 설정하지 않는다. 이 사례는 첫 결과 map 쓰기 뒤의 Exception/Error를 검사한다. 마지막 순번 map·사용 주문 ID 집합의 각각 다른 쓰기 지점에 별도 실패를 주입하거나 SQL식 롤백을 입증하지 않는다. 후속 등록은 실패한 C3 재시도·새 C4/B2 등록·기존 C1 재전송을 각각 호출해 차단을 확인한다.

엔진 조합 테스트만 실제 MatchingEngine을 호출하고, 나머지는 제공된 명령과 이벤트를 직접 등록한다. 지정가 동률·사용자 동일 사례처럼 실제 maker 주문장을 준비하지 않은 검사는 입력 묶음의 일관성 계약을 확인한다. 엔진 조합도 같은 JVM 안의 메모리 호출이며 실제 Aeron·DB·정산·재시작·동시성을 확인한 것으로 확대하지 않는다. Price·기존 ID 타입·Submit 원수량의 생성자에서 이미 거절하는 값은 reflection으로 위조하지 않는다.

<!-- test-explanation-source-sha256: fcada164188914e057b77b7a05e719a75679eccc6c80df3482d39516be71fda1 -->

</details>

<details markdown="1">
<summary>실행 기록·독립 리뷰·남은 범위</summary>

첫 두 테스트는 컴파일된 뒤 ‘결과 묶음 등록 미구현’의 NotImplementedError로 실패했다(2개·실패 2). 공통 준비의 등록부터 미구현이었으므로, 누락 분기의 검증문까지 도달한 Red라고 확대하지 않는다. 이후 구현과 테스트를 보강했다.

로컬 실행은 2026-10-23에 아래 폴더에서 했다. 새 테스트는 36개 메서드이며 메서드 안 반복 입력 수를 별도 테스트 개수로 더하지 않았다.

| 검사 | 실제 결과 |
| --- | --- |
| 신규 결과 테스트 | 36개 성공. 초기 집중 실행과 전체 매칭 실행에서 확인 |
| domain-matching 전체 | 110개·실패 0·오류 0·건너뜀 0 |
| domain-trading 전체 | 100개·실패 0·오류 0·건너뜀 0 |
| architecture-tests | 381개·실패 0·오류 0·건너뜀 0. 필수 P01~P09 실행·성공도 별도 확인 |
| 전체 ktlintCheck | 성공 |
| 새 C4 차단 assertion 보강 후 | MatchingResultFailureTest 7개와 매칭 린트 재실행 성공 |

```bash
cd /Users/0chord/.codex/worktrees/issue51-matching-results/exchange-core
./gradlew -p /Users/0chord/.codex/worktrees/issue51-matching-results/exchange-core :domain-matching:test :domain-trading:test :architecture-tests:test :architecture-tests:verifyArchitectureReport ktlintCheck --no-daemon --console=plain
./gradlew -p /Users/0chord/.codex/worktrees/issue51-matching-results/exchange-core :domain-matching:test --tests '*MatchingResultFailureTest' :domain-matching:ktlintCheck --no-daemon --console=plain
```

첫 전체 검증에서는 매칭 포맷 정리 작업도 함께 수행했다. 최종 신규 C4 검증문은 그 뒤 추가해 두 번째 명령으로 재확인했다. 기존 OrderAdmissionState의 data class copy 가시성 경고와 Java/Gradle 사용 중단 예정 경고는 남아 있으나 이번 변경에서 발생한 실패는 없다. 새 Snapshot의 copy 가시성은 명시했다.

독립 리뷰는 고정한 로컬 snapshot을 작성 대화가 없는 별도 에이전트·별도 checkout에서 진행한다. 아래의 결과 기록이 채워지기 전에는 독립 리뷰 완료로 보지 않는다.

이번 구현은 순수 메모리 코어다. HTTP·실제 DB·Aeron 통신·세 노드 복제·정산·재시작 복구·성능 검사를 실행한 것으로 확대하지 않는다. PR 게시·원격 CI·병합은 별도 단계다.

</details>

## 실제 문답과 설명 반영

- 질문 요약: 이미 만든 결과 목록의 누락 검사, 재전송과 충돌, 검사 실패와 반영 중 오류 중 어느 흐름을 더 자세히 설명할지 물었다. 구현은 합의한 범위로 계속한다고 안내했다.
- 사용자 실제 답변: **“누락된 결과를 완료로 등록하면 안 되는 이유 (추천)”**.
- 반영: BUY 3개의 체결 두 건·잔량 한 건 중 하나가 빠졌을 때 등록하지 않는 이유를 첫 화면에 배치했다. 별도 선택 이유는 제출하지 않았다.
- 이 답변과 설명 작성은 전체 코드 이해·게시 승인을 확인한 것으로 기록하지 않는다.
