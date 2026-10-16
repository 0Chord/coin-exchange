# #87 주문 값을 바이트로 보내고 같은 값으로 읽기

주문 접수 코어가 받는 Kotlin 값을 SBE 바이트로 바꿨다가 되읽는 변환기를 추가한다. `exchange-core` 안의 변환 코드다. 별도 서버나 실제 서버 통신을 추가하는 작업은 아니다. 기존 HTTP 실행 경로도 이번 변환기를 호출하도록 바꾸지 않았다.

**대표 정상 흐름:** 준비된 접수 명령 → 바이트 → 같은 명령 → 기존 `OrderAdmissionState.admit` → 작은 승인/거절 응답 → 바이트 → 같은 응답.

**대표 실패 흐름:** 잘린 메시지·미지원 헤더·잘못된 값 → `AdmissionMessageException` → 코어에 진입하지 않음. 반대로 코어 승인 뒤 응답 인코딩에 실패하면 이미 반영한 승인을 되돌리지 않는다.

테스트의 BUY 예시는 USDT 소수점 6자리, BTC 소수점 8자리다. 사용 가능 **1,000 USDT = 1,000,000,000**, 가격 **100 USDT = 100,000,000**, 수량 **3 BTC = 300,000,000**을 준비한다. 수수료 0이면 **700 USDT = 700,000,000 사용 가능 / 300 USDT = 300,000,000 예약 / 승인 순번 1**이다. 이 값은 바이트 변환기가 계산하는 것이 아니라 기존 접수 코어에 실제 적용한 뒤 확인한다.

## 변경을 읽는 세 단위

| 단위 | 시작 → 판단 → 결과 | 다른 분기와 실패 |
| --- | --- | --- |
| 1. 명령 하나를 보존한다 | 주문 원본·요청 ID·마켓·수수료·원장 ID·나노초 시각을 SBE로 쓰고 같은 값으로 읽는다 | 빈 requestId/ledgerTransactionId, 알려진 선물 정책·다른 마켓 정의도 읽을 수 있다. 코어의 기존 판단 순서를 앞질러 업무 정합성을 검사하지 않는다 |
| 2. 실제 접수 답을 작게 보낸다 | 읽은 명령을 기존 코어에 넘기고, 실제 반환값에서 요청 대조 값·순번 또는 거절 상세만 꺼낸다 | 같은 원본 재전송은 처음 답을 유지한다. 내용 충돌은 지금 제출한 주문 ID를 응답한다. 다른 주문의 승인을 연결하면 내부 오류다 |
| 3. 해석할 수 없는 메시지는 멈춘다 | 정확한 offset/length → 헤더·고정 블록 → 각 길이/UTF-8/값 → 메시지 끝을 확인한다 | 다음 메시지의 바이트를 빌려 읽지 않는다. 손상 시 코어 진입 0회와 기존 상태 전체를 대조한다. 반환한 값은 입력 버퍼와 분리한다 |

실제 코어 연결은 이번 테스트의 일반 함수 호출로 보여준다. 운영 어댑터·Aeron 전송·복제·스냅샷·재시작 복구·매칭·정산은 후속 범위다. 접수 승인은 체결 완료를 뜻하지 않는다.

## 1. 값과 바이트의 경계

`AdmissionCommandPayload`는 이미 준비된 판단 입력을 묶는다. 인증·정책 조회·ID 발급·현재 시각 생성은 하지 않는다. `AdmissionCommandCodec`은 생성된 Java encoder/decoder를 순서대로 사용하고, 호출마다 별도 바이트 배열과 문자열 값을 반환한다. 같은 명령의 인코딩은 항상 같은 바이트다.

도메인 값 타입으로 만들 수 없는 가격 0/음수, 음수 수량, 공백 사용자/마켓/주문 ID 등은 변환 실패다. 읽을 수 있는 MARKET·IOC·수량 0·65글자 업무 ID·NUL은 기존 코어의 정상 거절 규칙을 따른다. 요청·원장 ID에 공통 nonblank 검사를 추가하지 않는다.

같은 원본을 다시 보내면 빈 원장 ID·다른 마켓 정의·알려진 선물 정책이 새 문맥에 있어도 기존 코어는 처음 결과를 먼저 반환한다. 같은 잘못된 문맥을 새 요청으로 접수하면 기존 내부 오류가 그대로 전달된다. 코덱의 값 읽기 예외 처리 안에서 코어를 호출하지 않으므로 이 내부 오류를 일반 변환 실패나 정상 주문 거절로 합치지 않는다.

## 2. 승인·거절·오류 뒤에 남는 것

| 상황 | 응답/오류 | 실제 상태 |
| --- | --- | --- |
| BUY 승인 | userId·requestId·marketId·orderId·양수 승인 순번 | 잔고·예약·대기 주문·원장·최초 요청 결과는 코어가 보관 |
| 사용 가능 200 USDT, 필요 300 USDT | INSUFFICIENT_BALANCE, available 200,000,000 / required 300,000,000 / shortfall 100,000,000 | 돈·대기·승인 순번은 유지하고 유효한 최초 거절 요청을 기록 |
| 같은 원본 재전송 | 처음 승인 순번 또는 처음 거절 상세 | 추가 예약·원장·대기·요청 기록 없음 |
| 같은 사용자·요청 ID, 다른 원본 | REQUEST_CONTENT_CONFLICT, 현재 제출한 주문의 대조 값 | 처음 기록 유지 |
| 새 요청 ID, 기존 주문 ID | DUPLICATE_ORDER | 자금·대기·승인 순번 유지, 해당 새 요청의 최초 거절 기록 |
| 손상 명령 | AdmissionMessageException | 코어 진입 전 중단 |
| 승인 후 응답 인코딩 실패 | 변환 오류 그대로 전달 | 이미 승인한 자금은 유지. 이를 새 거절로 만들거나 자동 재실행하지 않음 |
| 다른 주문의 승인 결과를 연결 | IllegalStateException | 내부 오류. 승인과 현재 명령의 주문 원본 전체를 대조 |

작은 응답에 내부 Candidate·이전/다음 잔고·예약 객체·원장 전체는 넣지 않는다. 내부 자료를 삭제하거나 기존 코어 반환 계약을 축소한 것은 아니다. 응답 변환에는 읽은 명령과 실제 반환 결과를 함께 넘기며, 상태 이력을 다시 조회해 다른 주문의 식별자를 가져오지 않는다.

## 3. 이 버전의 전송 계약

원본은 [order-admission.xml](../app-api/src/main/sbe/order-admission.xml)이다. 생성기는 SBE **1.40.2**, 스키마 ID **87**, 버전 **1**, **little-endian**이다. 버전·스키마·종류·고정 길이는 정확히 일치해야 한다. 전후 버전을 추측해 읽지 않는다.

헤더는 메시지 시작 기준 blockLength@0, templateId@2, schemaId@4, version@6이며 모두 uint16, 합계 **8바이트**다. blockLength는 헤더나 가변 문자열을 포함하지 않은 고정 본문 길이다. 아래 고정 offset은 **본문 시작 기준**이며 메시지 시작 offset은 8을 더한다. 정수 사이 패딩은 없다.

| 명령 template 1 · blockLength 50 | 필드 ID | 본문 offset | 형식/범위 |
| --- | --- | --- | --- |
| side | 1 | 0 | uint8 · BUY=1, SELL=2 |
| orderType | 2 | 1 | uint8 · LIMIT=1, MARKET=2 |
| timeInForce | 3 | 2 | uint8 · GTC=1, IOC=2 |
| price | 4 | 3 | int64 · 1..Long.MAX_VALUE |
| quantity | 5 | 11 | int64 · 0..Long.MAX_VALUE |
| baseAssetScale | 6 | 19 | uint8 · 0..18 |
| productType | 7 | 20 | uint8 · SPOT=1, PERPETUAL_FUTURES=2, DATED_FUTURES=3 |
| feeTier | 8 | 21 | uint8 · NORMAL=1, VIP=2, VVIP=3, VVVIP=4 |
| scheduleVersion | 9 | 22 | int64 · 1..Long.MAX_VALUE |
| makerPpm | 10 | 30 | int32 · 0..1,000,000 |
| takerPpm | 11 | 34 | int32 · 0..1,000,000 |
| occurredAtEpochSecond | 12 | 38 | int64 · Java Instant로 표현 가능한 초 |
| occurredAtNano | 13 | 46 | int32 · 0..999,999,999 |

명령의 가변 필드는 메시지 시작 **58바이트**부터 requestId(14), userId(15), marketId(16), orderId(17), definitionMarketId(18), baseAssetId(19), quoteAssetId(20), ledgerTransactionId(21) 순서다. 각각 **uint16 바이트 길이 + 원문 UTF-8**이며 다음 필드 위치는 앞 문자열의 실제 길이에 따라 달라진다.

| 응답 | 고정 필드 · ID / 본문 offset / 형식 | 가변 필드 순서 |
| --- | --- | --- |
| 승인 template 2 · blockLength 8 | sequence · 1 / 0 / int64 양수, Long.MAX_VALUE 허용 | 메시지 시작 16부터 requestId(2), userId(3), marketId(4), orderId(5) |
| 거절 template 3 · blockLength 26 | reason · 1 / 0 / uint8; hasInsufficientBalance · 2 / 1 / uint8; available · 3 / 2 / int64; required · 4 / 10 / int64; shortfall · 5 / 18 / int64 | 메시지 시작 34부터 requestId(6), userId(7), marketId(8), orderId(9), assetId(10) |

거절 사유 코드는 Kotlin ordinal과 무관하다: UNSUPPORTED_ORDER_CONDITION=1, INVALID_QUANTITY=2, INVALID_ORDER_IDENTIFIER=3, UNREPRESENTABLE_RESERVATION=4, INSUFFICIENT_BALANCE=5, DUPLICATE_ORDER=6, INVALID_REQUEST_IDENTIFIER=7, REQUEST_CONTENT_CONFLICT=8. 미지 코드·null 코드는 실패다.

금액은 모두 0..Long.MAX_VALUE다. 사유 5일 때만 presence=1이고 assetId·금액 상세가 있다. required > available, shortfall = required − available이어야 한다. 그 외에는 presence=0, 세 금액=0, assetId 길이=0으로 고정한다. 모순된 응답은 신뢰하지 않는다.

각 문자열 상한은 **UTF-8 256바이트**, 전체 구간 상한은 **4,096바이트**다. trim·대소문자 변환·Unicode 정규화·대체 문자 복구를 하지 않는다. 잘못된 UTF-8과 고립 서로게이트는 실패다. 현재 필드 수와 각 상한으로 만들 수 있는 명령 최대 길이는 2,122바이트이며, 전체 4,096 상한은 수신 경계에서 별도로 검사한다. 값이 다른 정상 메시지로 변한 것까지 탐지하는 체크섬은 아니다.

## 4. 잘린 데이터와 버퍼의 소유

먼저 offset ≥ 0, 8 ≤ length ≤ 4096, offset ≤ capacity − length를 검사한다. 덧셈 overflow 뒤에 검사하지 않는다. 전달받은 구간으로 Agrona buffer를 한정한 뒤 고정 블록, 문자열 길이 2바이트, 문자열 본문을 읽기 전에 남은 바이트를 확인한다. 마지막 decoder 위치는 정확히 메시지 끝이어야 한다.

UTF-8 길이는 문자 수와 다르다. 64개 이모지는 256바이트로 보존하고 257바이트 문자열은 실패한다. 읽은 뒤 원래 입력 배열을 덮어써도 반환한 명령과 이미 저장한 접수 기록은 바뀌지 않는다. 다음 encode를 호출해도 이전 반환 배열을 덮어쓰지 않는다.

생성기의 접근 순서 검사를 생성 시 켜고 app-api 테스트 JVM에서 `sbe.enable.precedence.checks=true`를 명시했다. 이 검사만으로 메시지 경계·UTF-8·업무 값 검사를 대신하지 않는다.

## 5. 전체 새 테스트를 상황·실행·결과로 읽기

다음 목록은 실제 테스트 메서드에서 다시 수집한다. 테스트 정의 수와 각 메서드 안의 반복 입력 수는 다르다. 모두 DB·HTTP·Aeron 없이 실제 바이트와 순수 접수 코어를 사용하는 테스트다. 예외 assertion만 있는 응답 손상 사례를 상태 미변경 검증으로 확대하지 않는다.

<!-- tests87:start -->
현재 **네 클래스·26개 테스트 정의**를 설명한다. 아래 반복 입력은 정의 수와 별도다. 최종 실행 판정은 실행 기록에서 확인한다.

<details markdown="1">
<summary>명령의 값·독립 바이트 · 2개</summary>

### 주문과 판단 정보를 SBE로 보냈다가 같은 값으로 읽는다

- **Given · 상황:** 요청 R1, 가격 정수 100·수량 정수 3, maker 10ppm·taker 20ppm과 나노초 시각이 있는 명령.
- **When · 실행:** 인코딩 후 디코딩하고 원본 명령을 다시 인코딩한다.
- **Then · 실제 확인:** 명령 전체가 같고 두 인코딩의 바이트 배열도 같다.

<details markdown="1">
<summary>코드에서 확인하기</summary>

[AdmissionCommandCodecTest.kt · L28–33](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionCommandCodecTest.kt#L28) · 확인문: [assertEquals L31](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionCommandCodecTest.kt#L31), [assertContentEquals L32](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionCommandCodecTest.kt#L32)

</details>

### 스키마에서 독립적으로 정한 바이트와 양방향으로 대조한다

- **Given · 상황:** XML 순서를 따라 직접 적은 헤더 8바이트·고정 본문 50바이트·문자열 8개의 16진수 예시.
- **When · 실행:** 예시 명령의 encode와 예시 바이트의 decode를 각각 실행한다.
- **Then · 실제 확인:** encode 결과가 고정 바이트와 같고 decode 결과도 준비한 명령과 같다.

<details markdown="1">
<summary>코드에서 확인하기</summary>

[AdmissionCommandCodecTest.kt · L36–45](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionCommandCodecTest.kt#L36) · 확인문: [assertContentEquals L43](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionCommandCodecTest.kt#L43), [assertEquals L44](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionCommandCodecTest.kt#L44)

</details>

</details>

<details markdown="1">
<summary>실제 접수 승인·부족 거절 · 2개</summary>

### 실제 접수 승인은 자금 반영 뒤 작은 확인 응답으로 왕복한다

- **Given · 상황:** 사용 가능 1,000 USDT에서 가격 100 USDT·수량 3 BTC의 매수 요청. 수수료는 0이다.
- **When · 실행:** 명령 왕복 → 실제 admit → 작은 승인 응답 왕복.
- **Then · 실제 확인:** 작은 승인 응답과 순번 1을 확인한다. 사용 가능 700 USDT·예약 300 USDT이며 대기 주문과 요청 기록이 각각 1개다. 원장 분개 2개와 예약액 정수 300,000,000도 확인한다.

<details markdown="1">
<summary>코드에서 확인하기</summary>

[AdmissionResultCodecTest.kt · L18–32](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionResultCodecTest.kt#L18) · 확인문: [assertIs L21](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionResultCodecTest.kt#L21), [assertEquals L23](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionResultCodecTest.kt#L23), [assertEquals L24](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionResultCodecTest.kt#L24), [assertEquals L26](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionResultCodecTest.kt#L26), [assertEquals L27](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionResultCodecTest.kt#L27), [assertEquals L28](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionResultCodecTest.kt#L28), [assertEquals L29](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionResultCodecTest.kt#L29), [assertEquals L30](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionResultCodecTest.kt#L30), [assertEquals L31](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionResultCodecTest.kt#L31)

</details>

### 실제 자금 부족 거절은 당시 부족 상세를 그대로 왕복한다

- **Given · 상황:** 사용 가능 200 USDT, 필요한 예약 300 USDT.
- **When · 실행:** 명령 왕복 → 실제 admit → 거절 응답 왕복.
- **Then · 실제 확인:** 응답에 현재 200 USDT·필요 300 USDT·부족 100 USDT가 그대로 남는다. 사용 가능 200 USDT·예약 0 USDT, 대기 주문 없음·승인 순번 0·최초 거절 기록 1개다.

<details markdown="1">
<summary>코드에서 확인하기</summary>

[AdmissionResultCodecTest.kt · L35–56](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionResultCodecTest.kt#L35) · 확인문: [assertEquals L48](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionResultCodecTest.kt#L48), [assertEquals L49](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionResultCodecTest.kt#L49), [assertEquals L51](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionResultCodecTest.kt#L51), [assertEquals L52](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionResultCodecTest.kt#L52), [assertEquals L53](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionResultCodecTest.kt#L53), [assertEquals L54](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionResultCodecTest.kt#L54)

</details>

</details>

<details markdown="1">
<summary>업무 흐름·재전송·응답 · 11개</summary>

### 수수료가 있는 매도도 BTC 두 개만 예약하고 당시 정책을 보존한다

- **Given · 상황:** 사용 가능 BTC 3개 중 2개를 매도한다. VVIP·정책 버전 7, maker 500ppm·taker 1,000ppm이다.
- **When · 실행:** 명령을 왕복해 실제 코어에 적용하고 승인 응답도 왕복한다.
- **Then · 실제 확인:** 명령과 정책이 그대로 보존된다. BTC 사용 가능 1개·예약 2개이며 수수료 선예약은 0이다. 작은 승인 응답도 같다.

<details markdown="1">
<summary>코드에서 확인하기</summary>

[AdmissionProtocolFlowTest.kt · L29–55](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L29) · 확인문: [assertEquals L47](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L47), [assertIs L48](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L48), [assertEquals L49](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L49), [assertEquals L50](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L50), [assertEquals L51](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L51), [assertEquals L52](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L52), [assertEquals L54](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L54)

</details>

### 같은 원본 재전송은 쓰지 않을 새 문맥보다 최초 승인을 우선한다

- **Given · 상황:** R1/O1을 먼저 승인하고 snapshot을 저장한다.
- **When · 실행:** 마켓 불일치·선물 정책·빈 원장ID/Instant.MAX를 각각 바꿔 재전송한 뒤, 같은 문맥을 R2/O2로 새로 보낸다.
- **Then · 실제 확인:** 재전송은 첫 응답·snapshot 유지. 새 요청은 각 문맥의 IllegalArgumentException 메시지가 일치하고 snapshot·비차단 상태도 유지된다.

<details markdown="1">
<summary>코드에서 확인하기</summary>

[AdmissionProtocolFlowTest.kt · L58–89](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L58) · 확인문: [assertEquals L78](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L78), [assertEquals L79](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L79), [assertEquals L80](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L80), [assertFailsWith L82](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L82), [assertEquals L85](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L85), [assertEquals L86](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L86), [assertFalse L88](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L88)

</details>

### 같은 요청의 다른 주문과 새 요청의 기존 주문은 서로 다른 거절이다

- **Given · 상황:** R1/O1을 먼저 승인한다.
- **When · 실행:** R1의 주문ID 또는 수량을 바꾸고, 별도로 R2/O1도 보낸다.
- **Then · 실제 확인:** 앞 둘은 현재 제출 주문을 가리키는 내용 충돌과 전체 snapshot 유지. R2/O1은 주문 중복이며 자금·대기·순번1 유지, 요청 기록은2개다.

<details markdown="1">
<summary>코드에서 확인하기</summary>

[AdmissionProtocolFlowTest.kt · L92–124](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L92) · 확인문: [assertEquals L103](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L103), [assertEquals L113](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L113), [assertEquals L115](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L115), [assertEquals L120](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L120), [assertEquals L121](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L121), [assertEquals L122](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L122), [assertEquals L123](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L123)

</details>

### 현재 잔고가 줄어도 같은 요청은 최초 부족 금액을 반환한다

- **Given · 상황:** 사용 가능 200 USDT에서 R1은 필요 300 USDT·부족 100 USDT로 거절된다.
- **When · 실행:** R2의 100 USDT 주문을 승인해 사용 가능 금액을 100 USDT로 만든 뒤 R1을 다시 보낸다.
- **Then · 실제 확인:** R1의 응답은 처음의 현재 200 USDT·필요 300 USDT·부족 100 USDT를 유지한다. 재전송 직전의 상태 전체도 그대로다.

<details markdown="1">
<summary>코드에서 확인하기</summary>

[AdmissionProtocolFlowTest.kt · L127–141](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L127) · 확인문: [assertIs L131](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L131), [assertEquals L132](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L132), [assertEquals L133](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L133), [assertEquals L134](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L134), [assertIs L136](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L136), [assertEquals L138](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L138), [assertEquals L139](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L139), [assertEquals L140](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L140)

</details>

### 문자열을 정규화하지 않아 서로 다른 요청 키와 원문을 유지한다

- **Given · 상황:** 서로 다른 é, e+결합악센트, 한글:요청 세 요청과 서로 다른 주문ID.
- **When · 실행:** 각 명령을 왕복하고 순서대로 실제 코어에 보낸다.
- **Then · 실제 확인:** 각 원문 명령이 같고 승인 순번1·2·3, 저장 요청ID 목록도 원문 순서 그대로다.

<details markdown="1">
<summary>코드에서 확인하기</summary>

[AdmissionProtocolFlowTest.kt · L144–152](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L144) · 확인문: [assertEquals L148](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L148), [assertEquals L149](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L149), [assertEquals L151](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L151)

</details>

### 승인 뒤 응답 변환 실패는 이미 반영된 자금을 되돌리지 않는다

- **Given · 상황:** 실제 승인 이후의 snapshot과 작은 승인 응답.
- **When · 실행:** 응답 requestId를257바이트로 바꿔 encode 실패시킨 다음 원본 명령을 재전송한다.
- **Then · 실제 확인:** AdmissionMessageException 뒤 snapshot 유지, 재전송은 원래 승인 응답과 같고 snapshot도 유지된다.

<details markdown="1">
<summary>코드에서 확인하기</summary>

[AdmissionProtocolFlowTest.kt · L155–165](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L155) · 확인문: [assertIs L160](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L160), [assertFailsWith L161](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L161), [assertEquals L162](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L162), [assertEquals L163](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L163), [assertEquals L164](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L164)

</details>

### 다른 주문의 승인 결과를 응답으로 연결하면 내부 오류로 중단한다

- **Given · 상황:** O1의 실제 승인 결과.
- **When · 실행:** 현재 명령의 주문ID·사용자·마켓·수량을 각각 바꿔 같은 승인을 응답에 연결한다.
- **Then · 실제 확인:** 모두 IllegalStateException이며 승인 순번은1이다. 이 테스트의 마지막 직접 상태 관측은 순번이다.

<details markdown="1">
<summary>코드에서 확인하기</summary>

[AdmissionProtocolFlowTest.kt · L168–187](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L168) · 확인문: [assertFailsWith L182](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L182), [assertEquals L186](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L186)

</details>

### 다음 변환과 입력 버퍼 덮어쓰기는 이전 바이트와 접수 기록을 바꾸지 않는다

- **Given · 상황:** 첫 명령/응답 바이트 복사본, 디코딩한 값, 실제 승인 후 snapshot.
- **When · 실행:** 다음 명령과 다른 응답을 encode하고, 첫 입력/응답 배열을0으로 덮어쓴다.
- **Then · 실제 확인:** 다음 encode가 이전 배열을 바꾸지 않으며, 덮어쓰기 뒤 명령·snapshot·저장한 요청의 원본/정책/문맥 각 필드가 유지된다.

<details markdown="1">
<summary>코드에서 확인하기</summary>

[AdmissionProtocolFlowTest.kt · L190–216](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L190) · 확인문: [assertContentEquals L203](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L203), [assertContentEquals L204](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L204), [assertEquals L207](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L207), [assertEquals L208](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L208), [assertEquals L210](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L210), [assertEquals L211](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L211), [assertEquals L212](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L212), [assertEquals L213](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L213), [assertEquals L214](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L214), [assertEquals L215](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L215)

</details>

### 작은 승인 응답은 독립 예시 바이트와 최대 순번을 보존한다

- **Given · 상황:** u/r/m/o·순번1 응답과 직접 적은28바이트 예시.
- **When · 실행:** 예시 바이트와 양방향 대조하고 Long.MAX_VALUE 순번도 왕복한다.
- **Then · 실제 확인:** encode 바이트와 decode 응답이 각각 독립 예시와 일치하고 최대 순번도 같다.

<details markdown="1">
<summary>코드에서 확인하기</summary>

[AdmissionProtocolFlowTest.kt · L219–227](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L219) · 확인문: [assertContentEquals L223](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L223), [assertEquals L224](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L224), [assertEquals L226](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L226)

</details>

### 부족 응답의 현재 필요 부족 금액 위치를 독립 바이트로 대조한다

- **Given · 상황:** 부족 사유5·presence1·자산Q·현재200/필요300/부족100과 직접 적은49바이트 예시.
- **When · 실행:** 응답 encode와 독립 바이트 decode를 각각 실행한다.
- **Then · 실제 확인:** 고정 영역 금액 위치를 포함한 바이트 전체와 응답 전체가 각각 일치한다.

<details markdown="1">
<summary>코드에서 확인하기</summary>

[AdmissionProtocolFlowTest.kt · L230–244](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L230) · 확인문: [assertContentEquals L242](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L242), [assertEquals L243](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L243)

</details>

### 모든 거절 사유는 명시된 전송 코드와 상세 유무를 보존한다

- **Given · 상황:** 기존 모든 거절 사유와 독립 명시 코드1..8. 부족 사유에만0/Long.MAX_VALUE/Long.MAX_VALUE 상세.
- **When · 실행:** 각 응답을 encode/decode한다.
- **Then · 실제 확인:** 준비한 코드 표가 기존 사유 전부를 포함하고, 실제 bytes[8] 코드와 응답 전체가 일치한다.

<details markdown="1">
<summary>코드에서 확인하기</summary>

[AdmissionProtocolFlowTest.kt · L247–272](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L247) · 확인문: [assertEquals L259](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L259), [assertEquals L269](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L269), [assertEquals L270](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L270)

</details>

</details>

<details markdown="1">
<summary>전송 경계·손상·버퍼 · 11개</summary>

### 공유 버퍼의 정확한 구간만 읽고 반환한 명령은 원본 버퍼와 분리된다

- **Given · 상황:** 명령을 offset13에 넣고 앞뒤에 다른 바이트를 둔 공유 배열.
- **When · 실행:** 정확 구간을 읽고 배열을0으로 덮어쓴다. 이어서 마지막 바이트를 논리 구간 밖에 두고 읽는다.
- **Then · 실제 확인:** 먼저 읽은 명령은 원본과 같다. 잘린 구간은 공통 helper에서 변환 예외·코어 진입0·기존 snapshot 유지·비차단을 확인한다.

<details markdown="1">
<summary>코드에서 확인하기</summary>

[AdmissionMessageBoundaryTest.kt · L31–45](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L31) · 확인문: [assertEquals L41](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L41), [assertCommandRejected L44](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L44)

</details>

### 구간과 필수 문자열이 잘렸거나 남은 바이트가 있으면 코어 진입 전에 실패한다

- **Given · 상황:** 헤더/고정 블록/문자열 길이/본문 잘림, 잔여 바이트, 음수·Int.MAX offset, 음수/초과 length,4097바이트 입력.
- **When · 실행:** 각 손상 구간을 실제 명령 디코더에 넘기고 성공했을 때만 코어를 호출한다.
- **Then · 실제 확인:** 모든 입력이 변환 예외이며 공통 helper의 진입0·전체 snapshot 유지·비차단 assertion을 통과해야 한다.

<details markdown="1">
<summary>코드에서 확인하기</summary>

[AdmissionMessageBoundaryTest.kt · L48–67](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L48) · 확인문: [assertCommandRejected L66](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L66)

</details>

### 다른 스키마 버전 종류 고정 길이는 추측해 읽지 않는다

- **Given · 상황:** 스키마86, 버전0/2, 종류2/99, 고정 길이49/51로 각각 바꾼 명령.
- **When · 실행:** 각 바이트를 실제 디코더에 넘긴다.
- **Then · 실제 확인:** 변환 예외·코어 진입0·기존 전체 snapshot 유지·비차단을 공통 helper에서 확인한다.

<details markdown="1">
<summary>코드에서 확인하기</summary>

[AdmissionMessageBoundaryTest.kt · L70–84](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L70) · 확인문: [assertCommandRejected L83](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L83)

</details>

### 스키마의 모든 명령 열거 필드에서 미지 코드와 NULL 코드를 거절한다

- **Given · 상황:** side/orderType/timeInForce/productType/feeTier 각각 코드0과255.
- **When · 실행:** 각 조합의 바이트를 실제 디코더로 읽는다.
- **Then · 실제 확인:** 총10개 손상 조합에서 변환 예외·진입0·snapshot 유지·비차단을 확인한다.

<details markdown="1">
<summary>코드에서 확인하기</summary>

[AdmissionMessageBoundaryTest.kt · L87–103](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L87) · 확인문: [assertCommandRejected L100](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L100)

</details>

### 도메인 값으로 표현할 수 없는 숫자와 정규화될 나노초는 변환 오류다

- **Given · 상황:** 가격0/-1·음수 수량·scale19/255·버전0/-1·음수/초과ppm·nano-1/10억·Instant 범위 밖 초.
- **When · 실행:** 각 숫자를 바이트에 직접 넣어 디코딩한다.
- **Then · 실제 확인:** 숫자를 보정하지 않고 변환 예외. 공통 helper에서 진입0·snapshot 유지·비차단을 확인한다.

<details markdown="1">
<summary>코드에서 확인하기</summary>

[AdmissionMessageBoundaryTest.kt · L106–126](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L106) · 확인문: [assertCommandRejected L125](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L125)

</details>

### 256바이트 UTF8 경계는 보존하고 잘못된 길이와 문자열은 대체하지 않는다

- **Given · 상황:** 64개 이모지, 거짓 길이257/남은 구간 초과, 잘못된UTF8·과잉 인코딩·공백 사용자,257바이트 원문과 고립 서로게이트.
- **When · 실행:** 정상 경계는 왕복하고, 손상 바이트를 decode하거나 잘못된 원문을 encode한 뒤 읽는다.
- **Then · 실제 확인:** 64개 이모지는256바이트이고 명령 전체가 같다. 나머지는 변환 예외·진입0·snapshot 유지·비차단이다.

<details markdown="1">
<summary>코드에서 확인하기</summary>

[AdmissionMessageBoundaryTest.kt · L129–157](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L129) · 확인문: [assertEquals L131](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L131), [assertEquals L132](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L132), [assertCommandRejected L152](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L152), [assertRejectedBeforeAdmission L155](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L155)

</details>

### Long 최대 값과 Instant 양 끝 및 수치 상한은 손실이나 NULL 대체 없이 왕복한다

- **Given · 상황:** SELL 최대 가격/수량·버전Long.MAX_VALUE, scale0/18, ppm0/100만, Instant.MIN/MAX와 음수 초. BTC잔고도Long.MAX_VALUE다.
- **When · 실행:** 시각 경계 명령들을 왕복하고, scale0·시각EPOCH의 복원 명령을 실제 코어에 적용한다.
- **Then · 실제 확인:** 각 명령 전체가 같고, 실제 SELL 승인의 주문·예약액Long.MAX_VALUE와 BTC사용 가능0/예약Long.MAX_VALUE 목록이 일치한다. USDT 항목은 없다.

<details markdown="1">
<summary>코드에서 확인하기</summary>

[AdmissionMessageBoundaryTest.kt · L160–194](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L160) · 확인문: [assertEquals L175](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L175), [assertEquals L179](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L179), [assertIs L186](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L186), [assertEquals L187](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L187), [assertEquals L188](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L188), [assertEquals L189](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L189)

</details>

### 표현 가능한 업무 위반은 코어의 정상 거절 사유와 요청 기록 규칙을 유지한다

- **Given · 상황:** MARKET·IOC·수량0·빈/65글자 요청ID·65글자/NUL 주문ID를 각각 준비한다.
- **When · 실행:** 변환에서 원문을 보존한 뒤 실제 코어에 적용한다.
- **Then · 실제 확인:** 명령 전체가 같고 기존 정상 거절 사유가 맞으며 자금·대기·순번은 유지한다. 요청 기록 수는 사례별0 또는1, 비차단 상태다.

<details markdown="1">
<summary>코드에서 확인하기</summary>

[AdmissionMessageBoundaryTest.kt · L197–254](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L197) · 확인문: [assertEquals L244](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L244), [assertIs L245](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L245), [assertEquals L246](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L246), [assertEquals L248](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L248), [assertEquals L249](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L249), [assertEquals L250](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L250), [assertEquals L251](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L251), [assertFalse L252](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L252)

</details>

### 작은 응답도 정확한 구간과 Long 최대 순번 및 금액을 보존한다

- **Given · 상황:** 최대 순번 승인과 금액0/Long.MAX_VALUE/Long.MAX_VALUE 부족 응답.
- **When · 실행:** 승인을 offset7의 정확 구간에서 읽은 뒤 공유 배열을 덮어쓰고, 부족 응답도 왕복한다.
- **Then · 실제 확인:** 먼저 읽은 승인 응답 전체와 최대 금액 부족 응답 전체가 원본과 같다.

<details markdown="1">
<summary>코드에서 확인하기</summary>

[AdmissionMessageBoundaryTest.kt · L257–268](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L257) · 확인문: [assertEquals L264](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L264), [assertEquals L267](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L267)

</details>

### 응답의 종류 고정 블록 필수 문자열과 마지막 바이트도 검증한다

- **Given · 상황:** 응답 종류/블록 길이 오류, 고정/마지막 문자열 잘림, 뒤 잔여 바이트, 다음 바이트가 있는 공유 배열.
- **When · 실행:** 각 응답의 실제 구간을 디코딩한다.
- **Then · 실제 확인:** 각각 AdmissionMessageException이다. 이 테스트는 코어를 호출하지 않으며 별도 상태 snapshot은 관측하지 않는다.

<details markdown="1">
<summary>코드에서 확인하기</summary>

[AdmissionMessageBoundaryTest.kt · L271–290](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L271) · 확인문: [assertFailsWith L286](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L286), [assertFailsWith L287](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L287)

</details>

### 응답 순번과 거절의 상세 유무 금액은 서로 일치해야 한다

- **Given · 상황:** 승인0/-1, 미지/null 사유·presence, 사유/상세 모순, 음수·동일액·잘못된 차액·없는 상세의 잔여값·빈 자산.
- **When · 실행:** 손상 응답을 decode하고 모순된 응답 값도 encode한다.
- **Then · 실제 확인:** 모든 손상 바이트와 모순된 값이 AdmissionMessageException이다. 코어 실행·상태는 이 테스트 범위 밖이다.

<details markdown="1">
<summary>코드에서 확인하기</summary>

[AdmissionMessageBoundaryTest.kt · L293–329](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L293) · 확인문: [assertFailsWith L319](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L319), [assertFailsWith L328](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L328)

</details>

</details>

**공통 손상 명령 관측:** 먼저 별도 유효 요청을 승인한 상태를 준비한다. 변환이 성공했을 때만 진입 횟수를 올려 실제 admit를 호출한다. 변환 예외 뒤 진입0·snapshot 전체 동일·isBlocked=false를 확인한다. [실제 helper·확인문](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L338)
<!-- tests87:end -->

## 6. 파일·빌드와 구조 검사의 책임

| 분류 | 변경 | 연결한 흐름 |
| --- | --- | --- |
| 제품 코드 | AdmissionCommandPayload / AdmissionCommandCodec | 준비된 명령 값과 SBE 변환 |
| 제품 코드 | AdmissionResultPayload / AdmissionResultCodec | 실제 코어 결과와 작은 승인·거절 응답 |
| 제품 코드 | AdmissionMessageFormat·AdmissionMessageReader·AdmissionMessageException | 정확한 구간·문자열·값·오류 경계 |
| 스키마·빌드 | order-admission.xml / app-api/build.gradle.kts | 고정 전송 계약, 생성 선행, Agrona 런타임 API |
| 검증 정책·테스트 | ProjectLayoutPolicy / ModuleDependencyDirection / ModuleDependencyContractTest / NamingPlacementScopeTest | 정확한 생성 루트·폴더·생산 작업과 app-api → domain-trading 허용쌍 |
| 테스트 준비 | AdmissionCodecFixtures | 실제 코어·계산기·잔고·명령 준비와 일반 admit 호출 |
| 테스트 | 네 Codec/Protocol/Boundary Test 클래스 | 위 전체 메서드 목록 |
| 문서·로컬 도구 | 이 문서 / 로컬 render_review.py / 기존 reader HTML | 리뷰 안내·현재 소스 발췌. 제품 실행 코드가 아님 |

생성 도구는 전용 `sbeGenerator` configuration의 `uk.co.real-logic:sbe-tool:1.40.2`다. 운영 의존은 생성 코드·buffer에 필요한 `org.agrona:agrona:2.6.1`이며 Aeron 런타임은 추가하지 않았다. `generateAdmissionCodecs`는 스키마를 입력으로 받고 자기 출력 `app-api/build/generated/sources/sbe/main/java`만 실행 전에 삭제한다. main Java source set에 작업 provider를 연결하고 Kotlin/Java 컴파일이 생성을 기다리게 했다. 생성 Java는 Git에 넣지 않는다.

운영 원본·클래스 수집에서 생성물을 일괄 제외하지 않는다. 정확한 생성 루트와 `com.exchange.core.api.trading.infrastructure.codec.generated` 폴더만 허용한다. domain-trading의 코드·공개 시그니처·의존을 전송 타입으로 바꾸지 않는다. 구조 검사 통과만으로 모든 외부 타입 무노출을 보장한다고 주장하지 않는다. 기존 ARCH-01 Agrona 허용 합의도 유지한다.

Agrona 2.6.1의 buffer를 실제 사용하는 JVM은 **`--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED`**가 필요하다. 이번에는 app-api 테스트 JVM에 이 옵션을 넣었다. 후속 실제 실행 JVM도 이 옵션을 전달해야 하며 현재 bootRun·HTTP 연결 경로는 바꾸지 않았다. 근거는 [Agrona 2.6.1 공식 테스트 설정](https://github.com/aeron-io/agrona/blob/2.6.1/build.gradle#L188-L190)이다.

## 7. 소스 스냅샷과 검증 상태

기준은 통합 `0cf852318e4173f06160fd5bafd4b846b5efdf88`, 구현 위치는 `/Users/0chord/.codex/worktrees/issue87-admission-protocol/exchange-core`, 브랜치는 `feat/admission-protocol/87`이다. 아래 발췌와 해시는 구현 파일을 읽은 생성 시점 기준이며 파일 변경 후 렌더러를 다시 실행해야 한다.

<!-- evidence87:start -->
| 현재 근거 | 확인한 줄 | SHA-256 앞 12자리 |
| --- | --- | --- |
| [AdmissionCommandPayload.kt](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionCommandPayload.kt#L1) | 1–16 | `3d9635128249` |
| [AdmissionCommandCodec.kt](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionCommandCodec.kt#L1) | 1–123 | `7b4dd10a8fe6` |
| [AdmissionResultPayload.kt](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionResultPayload.kt#L1) | 1–60 | `cad4d89f3186` |
| [AdmissionResultCodec.kt](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionResultCodec.kt#L1) | 1–148 | `02603e8938ab` |
| [AdmissionMessageFormat.kt](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageFormat.kt#L1) | 1–117 | `2cc44ad9fc8e` |
| [order-admission.xml](../app-api/src/main/sbe/order-admission.xml#L1) | 1–106 | `07861cc1ca67` |
| [build.gradle.kts](../app-api/build.gradle.kts#L1) | 1–101 | `f6b512f2bb71` |
| [ProjectLayoutPolicy.kt](../architecture-tests/src/test/kotlin/com/exchange/architecture/policy/ProjectLayoutPolicy.kt#L1) | 1–117 | `aa635ce24578` |
| [ModuleDependencyDirection.kt](../architecture-tests/src/test/kotlin/com/exchange/architecture/rules/ModuleDependencyDirection.kt#L1) | 1–188 | `f1d3b7f30a79` |
| [ModuleDependencyContractTest.kt](../architecture-tests/src/test/kotlin/com/exchange/architecture/ModuleDependencyContractTest.kt#L1) | 1–337 | `05014f947d20` |
| [NamingPlacementScopeTest.kt](../architecture-tests/src/test/kotlin/com/exchange/architecture/NamingPlacementScopeTest.kt#L1) | 1–190 | `2139a4e9ca6d` |
| [build.gradle.kts](../architecture-tests/build.gradle.kts#L1) | 1–146 | `55457b89d229` |
| [AdmissionCommandCodecTest.kt](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionCommandCodecTest.kt#L1) | 1–72 | `18f7941fd76b` |
| [AdmissionResultCodecTest.kt](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionResultCodecTest.kt#L1) | 1–56 | `3ac674469785` |
| [AdmissionProtocolFlowTest.kt](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L1) | 1–281 | `f403f1089c4a` |
| [AdmissionMessageBoundaryTest.kt](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L1) | 1–418 | `24cd243910d0` |
| [AdmissionCodecFixtures.kt](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionCodecFixtures.kt#L1) | 1–70 | `0f95068a3158` |
| [OrderAdmissionState.kt](../domain-trading/src/main/kotlin/com/exchange/core/trading/OrderAdmissionState.kt#L1) | 1–210 | `7125b28235f3` |

<!-- evidence87:end -->

**로컬 검사 결과:** 새 코덱 테스트 26개, 기존 접수 코어 테스트 82개, 구조 테스트 375개로 총 483개가 통과했다. 실패·오류·skip은 0이다. 필수 P01~P09의 실행·성공 보고서와 전체 프로젝트 공통 ktlint도 통과했다. app-api의 기존 HTTP·DB 테스트 전체를 실행했다는 뜻은 아니다.

app-api의 clean을 단독 실행한 뒤 생성→Kotlin/Java 컴파일→코덱 테스트를 다시 실행했다. 구조 검사 출력에서 생성 Java 원본 19개와 실제 생성 클래스가 수집됐음을 확인했다. 임시로 스키마에 타입을 추가해 생성한 뒤 원본 스키마로 되돌려, 제거된 타입의 파일이 남지 않는 것도 확인했다. domain-trading의 운영 코드·공개 시그니처·의존 파일은 기준 통합과 동일하다.

명령 테스트 2개와 응답 테스트 2개는 처음에 미구현 함수의 `NotImplementedError`로 실패한 뒤 구현 후 통과했다. 별도로 발견한 Agrona의 JVM 접근 오류는 테스트 JVM 옵션으로 해결했다. 포맷·clean·검사를 한 호출에 묶은 시도는 Gradle 작업 순서에서 진행하지 못해, 이후 clean과 검사를 분리해 최종 성공을 확인했다. 이 실패를 제품 동작의 Red나 테스트 통과로 세지 않았다.

**독립 검토 결과:** 작성 대화를 공유하지 않은 별도 에이전트가 분리된 checkout에서 제품·테스트·빌드·안내 문서를 검토했다. 기존 app-api 전체 211개·접수 코어 82개·구조 375개, 총 668개가 실패·오류·skip 없이 통과했고 P01~P09 보고서와 전체 ktlint도 통과했다. 이 668개는 위 구현 폴더의 483개와 겹치므로 둘을 더하지 않는다.

리뷰어가 인코더를 쓰지 않고 직접 만든 명령·승인·거절 프레임으로 별도 임시 테스트 3개도 실행했다. 모든 잘림 위치 159개, 다섯 열거 필드의 1,280개 코드 조합, 여덟 문자열의 UTF-8·길이 손상 16개, 최대 2,122바이트 명령과 재전송 문맥을 대조했다. 직접 만든 명령의 코어 검증은 자산 Q·수량 scale 0·초기 금액 정수 1,000에서 가격 정수 100·수량 정수 3을 적용해 사용 가능 정수 700·예약 정수 300을 확인했다. 앞서 설명한 700 USDT·300 USDT는 별도로 실행한 저장소의 실제 접수 응답 테스트에서 확인한 값이다. 생성 파일 삭제 뒤 재생성과 오래된 출력 제거도 확인했다. 임시 검증은 저장소의 668개 테스트와 별도이며 운영 테스트 파일로 남기지 않았다.

검토 범위에서 확인된 미해결 결함은 없다. 제품 코드의 검토 시작점은 `67e59ee`, 실행 기록을 추가한 `8a14fc9`와 제품·테스트·빌드가 같다. 최종 문서 변경은 같은 리뷰어가 별도로 대조한다. 문서 설명·테스트 실행·AI 검토 완료를 사람의 이해·승인이나 실제 Aeron 통신·고가용성 검증으로 확대하지 않는다.

실행할 때는 구현 위치를 명시한다. 예: `./gradlew -p /Users/0chord/.codex/worktrees/issue87-admission-protocol/exchange-core :app-api:test --tests '*Admission*Test'`. 이 줄 자체는 실행 결과가 아니다.

## 8. 실제 선택과 읽기 선호

사용자는 SBE 한 가지와 생성 코드의 build 폴더 관리, 작은 접수 응답을 선택했고 이후 #87 구현을 요청했다. 관련 작업은 [GitHub #87](https://github.com/0Chord/coin-exchange/issues/87)이다. 상세 설계의 실제 답변과 선택 이력은 별도 계획 폴더의 전체 명세 5절에 유지한다. 로컬 원본 경로는 `/Users/0chord/.codex/worktrees/issue50-order-admission/exchange-core/engineering/order-admission-spec.md`다. 이 구현 문서가 과거 명세의 ‘당시 구현 전’ 기록을 현재 실행 결과처럼 덮어쓰지 않는다.

읽기 순서 선택에서 사용자가 실제로 답한 내용은 **“접수 명령이 바이트가 됐다가 원래 값으로 돌아오는 과정 (추천)”**이다. 이를 반영해 읽기 화면은 준비된 명령 → 바이트 → 같은 명령의 세 단계와 실제 예시 값을 먼저 보여준다. 별도 이유는 답하지 않았으므로 추측하지 않는다. 이 선택을 이해·승인 완료로 기록하지 않는다.

그다음 판단할 것은 ‘읽을 수 있는 새 문맥이 잘못돼도 같은 원본 재전송에서는 처음 답을 우선하는가’이며, 위 재전송 테스트와 실제 코어 호출 순서로 근거를 확인할 수 있다. 재승인이 필요한 새 업무 선택은 아니다.

설명 범위는 명령·응답 변환과 그 경계, 실제 코어에 적용하는 테스트 흐름이다. 네트워크 전달·응답 유실 후 외부 재시도·동시 처리·영구 보존·장애 복구·성능은 검증하지 않았다.
