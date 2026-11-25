# #87 접수 명령과 응답을 Protobuf로 보존하기

준비된 접수 명령과 작은 승인·거절 응답을 Protobuf 바이트로 바꿨다가 같은 값으로 읽는다.

## #128 시장별 가격·수량 단위 전송

`AdmissionCommand`에 `optional int64 price_tick = 22`, `optional int64 quantity_step = 23`을 추가한다. 새 송신자는 두 값을 항상 쓴다. 가격·수량의 정수 의미와 헤더의 schema 87/version 2, 기존 필드 1–21, 승인·거절 응답의 바이트 계약은 유지한다. 체결·정산을 실행하는 전송 명령은 추가하지 않는다.

| 수신한 단위 필드 | 결과 |
| --- | --- |
| 둘 다 있음 | 두 값이 양수이고 `priceTick × quantityStep`이 `10^baseAssetScale`로 정확히 나누어지는 `MarketDefinition`으로 복원 |
| 둘 다 없음 | 단위 추가 전 v2 명령으로 읽고 `priceTick=1`, `quantityStep=10^baseAssetScale`로 복원 |
| 하나만 있음 | `AdmissionMessageException`, 접수 코어 진입 전 중단 |
| 0·음수·서로 호환되지 않는 두 값 | 도메인 생성 불변식 위반을 변환 오류로 처리, 접수 코어 진입 전 중단 |

곱의 호환성 검사는 도메인의 `BigInteger` 계산을 재사용하므로 두 양수 `Long`의 곱이 `Long` 범위를 넘는다는 이유로 유효한 단위를 잃지 않는다. 단위 자체가 유효해도 주문 가격·수량이 해당 단위의 배수가 아니면 접수 코어가 기존 `UNREPRESENTABLE_RESERVATION` 사유로 거절한다. 새 거절 사유와 정산 계산의 반올림 규칙은 추가하지 않는다. READY 목록·접수 후보·최초 요청 관찰과 기록 입력의 의미 비교에도 두 단위를 포함한다.

이 변경의 읽기 호환성은 **새 수신자가 단위 필드 없는 이전 v2 명령을 읽는 범위**다. 이전 수신자는 필드 22·23을 미지 필드로 거절하므로 새 송신자와의 혼합 운영을 보장하지 않는다. 미지 필드를 무시하도록 검사를 약화하지 않으며, 모든 노드를 같은 빌드로 고정해 실행한다. 헤더 버전 2 유지와 Protobuf 필드 추가만으로 rolling deployment·롤백·로그 재생 호환성 전체를 보장하지 않는다.

| 검증 입력 → 실행 → 실제 확인문 | 근거 |
| --- | --- |
| 이전 65바이트 고정 본문 → 새 디코더 → 기본 단위를 포함한 원본 명령과 같음 | `AdmissionCommandCodecTest`의 이전 고정 v2 명령 검사. 새 생성 코드로 이전 바이트를 재생성하지 않음 |
| 단위를 명시한 71바이트 고정 본문 → 인코더·디코더 양방향 대조 → 바이트 전체와 명령 전체가 같음 | 같은 테스트의 독립 고정 바이트 검사. 태그 22=`b0 01`, 23=`b8 01` |
| 이전·새 주문 golden과 자금 golden → 종류별 디코더 대조 → 다른 종류는 모두 거절, 이전 주문을 읽은 새 송신 결과는 단위를 명시한 79바이트 고정 프레임과 같음 | `DevelopmentBalanceCodecTest`의 종류 경계 검사. 이전 73바이트 주문 프레임과 새 79바이트 프레임을 각각 고정해 보존 |
| 사용자 단위·scale 0/8/18의 이전 명령·큰 호환 단위 → 왕복 또는 읽기 → 값·presence·기본값이 같음 | `AdmissionCommandCodecTest`의 단위 보존·이전 기본값·큰 단위 검사 |
| 단일 필드 누락·0·음수·큰 비호환 곱 → 디코더 → 변환 예외·코어 호출 0·기존 snapshot 동일 | `AdmissionMessageBoundaryTest`의 누락·숫자 경계 검사 |
| 두 단위만 바뀐 문맥 → 실제 노드에 신규 요청 → 사유 9·자금과 요청 기록 유지, 고친 요청은 승인 | `DevelopmentOrderNodeTest`, `DevelopmentMultiMarketOrderNodeTest`의 설정 불일치 검사 |
| 단위만 다른 READY·후보·최초 요청·기록 입력 → 독립 기대값 비교 → 불일치 검출 | `DevelopmentFundingEventsTest`, `FundingReplicationComparisonTest`, `OrderReplicationComparisonTest`, `RecordedInputComparisonTest` |

위 표는 추가·수정한 검사의 의미다. 이 작업 단위에서는 병렬 변경 중인 빌드를 실행하지 않았으며, 실행 결과와 독립 완료 리뷰는 전체 변경을 모은 검증 기록에서 확인한다. 아래 #94 문서화와 최초 코덱 실행 기록은 당시 범위를 보존한 이력이다.

<!-- compatibility87:start -->

## #94 당시 확정 범위 · 변환기 마무리와 변경 규칙 문서화

**확정:** 현재 Protobuf 변환기와 기존 검증을 기준으로 #94를 마무리한다. 앞으로 메시지를 바꾸는 절차를 문서에 남긴다. metadata·새 메시지 버전·구신 버전 호환 기능이나 테스트를 지금 추가하지 않는다.

| 현재 만든 동작 | 입력 → 결과 |
| --- | --- |
| 접수 명령 변환 | 주문·마켓 정의·수수료 정책·ID·시각 → Protobuf 바이트 → 같은 업무 값 |
| 작은 응답 변환 | 접수 코어의 승인·거절 → Protobuf 바이트 → 같은 요청 식별자·순번 또는 거절 사유 |
| 잘못된 메시지 거절 | 누락·손상·미지원 형식 → 변환 오류. 명령은 접수 코어 진입 전 중단 |

SBE로 만들던 변환기를 Protobuf로 바꾼 작업이다. 이전 SBE 메시지를 계속 서비스하면서 혼합 운영하는 전환 기능을 만든 것은 아니다. 코덱의 실제 호출·코어 상태 대조·기존 테스트 설명은 아래 기존 구현 기록을 참조한다.

<details markdown="1">
<summary>앞으로 메시지를 추가·변경할 때 따를 규칙</summary>

### 현재 호환 범위를 정확히 기록한다

#94 문서화가 따랐던 제품 구현 기준은 `004e7765cfe333baac4e8940b2b5769371427894`다. 당시 문서 보완에서는 제품 코드를 바꾸지 않았다. schema 87/version 2만 읽고 명령·승인·거절·부족 상세의 미지 필드와 미지원 enum을 거절하는 규칙은 현재도 유지한다. `optional` 필드를 추가했다고 이전 수신자가 자동으로 허용하는 것은 아니다. #128의 구체적인 필드 추가·이전 명령 읽기 범위는 위 절을 따른다. [헤더 검사](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageFormat.kt#L110) · [미지 필드 검사](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageFormat.kt#L73)

가격·수량·금액·순번은 기존 정수 의미를 유지한다. 누락한 필수값을 0이나 빈 문자열로 채우지 않는다. 명시한 수량 0은 현재 코어의 업무 거절 대상으로 보존하고, 수량 누락은 변환 오류로 구분한다.

### 실제 변경이 생겼을 때의 절차

1. **변경 이유와 의미부터 적는다.** 추가할 값이 단순 관측 정보인지, 승인·자금·수수료·주문 조건·순서·재전송·응답 연결을 바꾸는 업무 값인지 구분한다. 모르는 필드를 무조건 무시하면 안전하다고 가정하지 않는다.
2. **기존 필드 번호·타입·의미를 유지한다.** 번호를 다른 값에 재사용하지 않는다. 삭제한 필드의 번호·이름과 삭제한 enum 숫자는 예약한다. 기본값·누락 처리·필수화도 계약 변경으로 검토한다. 배포 버전과 메시지 형식 버전을 구분하고, 어느 메시지 버전을 읽고 쓸지 명시한다.
3. **그때 필요한 호환 사례를 함께 만든다.** 변경 전 고정 메시지를 새 코드가 읽는 경우, 새 메시지를 이전 코드가 읽는 경우의 지원/거절 기대값을 각각 정한다. 같은 생성 코드끼리 왕복하는 검사만으로 혼합 버전 호환을 주장하지 않는다. 지원하지 않는 조건·필드의 잘못된 타입·손상은 코어 호출 전 거절하는지도 대조한다.
4. **준비 → 활성화 → 롤백 범위를 정한다.** 새 형식을 읽는 명령 수신자와 결과 수신자를 먼저 준비하고, 확인 후 새 형식 송신을 켠다. 새 기록이 생긴 뒤에는 그 기록을 읽을 수 있는 준비 버전까지만 되돌린다. 보존 로그·스냅샷·백업·롤백에 필요한 이전 읽기 지원은 임의로 제거하지 않는다.

단순한 호환 추가로 판정되는 경우와 명시적인 새 업무 계약·형식 버전이 필요한 경우를 실제 변경에서 결정한다. 이번 문서화로 새 버전이나 metadata 구조를 미리 추가하지 않는다. 기존 재전송의 `(userId, requestId)` 키·원본 주문 비교와 응답의 requestId·userId·marketId·orderId를 변경하지 않는다. 바이트가 다르다는 이유만으로 다른 업무 요청으로 보지도 않는다.

Aeron Cluster의 거래 계산 규칙 변경은 메시지를 읽을 수 있는지와 별도로 사본·재생에서 같은 판단을 해야 한다. 지원 능력 확인, 활성화 순서, 스냅샷·로그 호환, 실제 혼합 노드 배포·롤백 검증은 그 연결 작업의 명세에서 구체화한다. 현재 문서는 실제 무중단 배포 보장이 아니다.

근거: [Protobuf 변경 가이드](https://protobuf.dev/programming-guides/proto3/#updating), [필드 번호·배포 권장사항](https://protobuf.dev/best-practices/dos-donts/), [Amazon의 준비·활성화·롤백 사례](https://aws.amazon.com/builders-library/ensuring-rollback-safety-during-deployments/), [Aeron의 결정적 업무 처리](https://aeron.io/docs/aeron-cluster/efficient-business-logic/). 절차는 이 자료를 현재 프로젝트에 적용한 합의이며, 자료만으로 실제 실행을 검증한 것은 아니다.

</details>

<details markdown="1">
<summary>이번 완료 기준과 실제 확인 범위</summary>

- 명령과 작은 승인·거절 응답의 Protobuf 값 보존, 손상·누락·미지원 형식 거절을 기존 테스트·고정 예시 바이트·실제 접수 코어의 상태로 확인한다.
- 기존 실행 기록은 코덱 32개·구조 375개·공통 lint 통과이며, 순수 접수 도메인 82개는 변경되지 않은 코드의 UP-TO-DATE 결과를 재사용했다. 상세 근거는 아래 기존 검증 기록을 참조한다.
- 이번 문서 보완에서는 제품 코드·스키마·빌드·테스트를 수정하지 않는다. 기존 코드의 내용 해시와 실행 기록을 대조하며, 그 결과를 재사용한다.
- 미래 호환 정책을 실제로 구현했다거나 혼합 버전·클러스터 배포를 검증했다는 완료 조건은 추가하지 않는다. 독립 PR 리뷰·CI·병합 상태는 별도로 확인한다.

</details>

<details markdown="1">
<summary>채택한 범위와 다시 검토할 때</summary>

현재 변환기와 검증 범위를 유지하고 메시지 변경 규칙을 문서화한다. 새 필드의 사용 사례가 생기기 전에 metadata 구조나 새 형식 버전을 추가하지 않는다.

실제로 필드·enum·기본값·업무 의미를 바꾸거나 메시지 수신자를 연결할 때, 해당 변경의 지원 조합·구버전 동작·기록 재생·롤백 사례를 함께 설계하고 검증한다.

</details>

<!-- compatibility87:end -->

## 현재 구현과 기존 검증 기록

<!-- flows87:start -->

| 흐름 | 시작 → 판단 → 관측 가능한 결과 | 실제 근거 |
| --- | --- | --- |
| 1. 명령 보존 | 주문 원본·정책·원장 ID·나노초 시각 → Protobuf 프레임 → 같은 Kotlin 값. 업무상 거절할 수 있는 수량 0·빈 요청 ID 등도 표현 가능하면 보존한다 | [명령 쓰기·읽기](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionCommandCodec.kt#L29) |
| 2. 작은 접수 응답 | 읽은 명령 → 기존 접수 코어 → 요청 대조 식별자와 승인 순번 또는 거절 사유·부족 상세 → 응답 왕복. 재전송·충돌 판단은 기존 코어가 한다 | [실제 결과에서 응답 만들기](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionResultPayload.kt#L31) · [자금 반영 확인](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionResultCodecTest.kt#L18) |
| 3. 해석 실패 중단 | 정확한 구간·헤더·필수 필드·UTF-8·알려진 값 검사 → 잘못되면 AdmissionMessageException. 손상 명령은 코어 진입 0회·기존 상태 유지 | [구간·본문 읽기](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageFormat.kt#L92) · [실패 뒤 상태 확인](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L565) |

<!-- flows87:end -->

**전송 형식:** Protobuf 한 가지를 사용한다. 이 변경은 준비된 주문 값과 작은 접수 응답을 보존하는 경계 구현이며, 새 제품 코덱의 성능 우위를 주장하지 않는다.

<!-- state87:start -->

Protobuf 변경 · 실행 검증 완료 · 독립 리뷰 완료 · 사람의 이해·승인과 별도.

<!-- state87:end -->

<details markdown="1">
<summary>전송 계약과 실패 경계</summary>

<!-- contract87:start -->

### 프레임과 메시지

한 프레임은 **8바이트 little-endian 헤더 + Protobuf 본문**이다. 헤더 네 값은 각각 unsigned 16비트이며 순서는 아래와 같다. 본문 안의 값 위치는 고정 오프셋으로 계산하지 않는다.

| 헤더 위치 | 값 | 이 버전의 의미 |
| --- | --- | --- |
| 0–1 | bodyLength | 8바이트 헤더를 제외한 본문 길이, 전달한 length−8과 정확히 같아야 함 |
| 2–3 | kind | 1 명령 · 2 승인 · 3 거절 |
| 4–5 | schema | 87 |
| 6–7 | version | 2 · 다른 버전은 추측해 읽지 않음 |

프레임 전체는 최대 **4096바이트**, 식별자는 각 **UTF-8 256바이트**다. 디코딩은 호출자가 준 offset/length 안에서만 진행한다. 본문 잘림·잘못된 태그/varint·남은 바이트·다른 스키마/버전·잘못된 종류는 변환 실패다. 정확한 길이 안에서 유효하게 반복된 알려진 필드는 아래 중복 규칙을 따른다.

### 누락, 명시값, 모르는 필드

스키마는 proto3이며 scalar·string·enum에 `optional`을 붙여 존재 여부를 보존한다. 기본값을 채워서 누락을 숨기지 않고 코덱이 `hasX`로 필수 여부를 확인한다.

| 본문 | 코덱에서 반드시 존재해야 하는 값 |
| --- | --- |
| AdmissionCommand | 필드 1–21 전부: side·orderType·timeInForce·price·quantity·scale·productType·feeTier·scheduleVersion·maker/taker ppm·초/나노초·식별자 8개. 필드 22·23은 함께 존재하거나 이전 v2 명령에서 함께 생략 |
| AdmissionAccepted | sequence와 requestId·userId·marketId·orderId 5개 |
| AdmissionRejected | reason과 requestId·userId·marketId·orderId 5개 |
| InsufficientBalance | 상세가 있다면 assetId·available·required·shortfall 4개 |

`insufficient_balance` 메시지는 자금 부족 사유에만 반드시 존재하며, 다른 거절 사유에는 없어야 한다. 명시한 수량 0·scale 0·epoch 0·ppm 0·빈 요청/원장 ID는 누락과 다르다. 명시값이 도메인에서 표현 가능하면 보존한 뒤 코어의 기존 업무 판단 순서에 맡긴다.

각 enum의 0은 미지정 값이며 업무 값으로 인정하지 않는다. 기존 알려진 코드는 side BUY=1/SELL=2, orderType LIMIT=1/MARKET=2, timeInForce GTC=1/IOC=2, productType SPOT=1/PERPETUAL_FUTURES=2/DATED_FUTURES=3, feeTier NORMAL=1/VIP=2/VVIP=3/VVVIP=4다. 거절 사유의 1–8은 스키마에 명시된 기존 이름과 대응하며 미지정·알 수 없는 숫자는 거절한다.

모든 바깥 메시지와 부족 상세의 **알 수 없는 필드도 거절**한다. Protobuf가 기본으로 보관하는 미지 필드를 이 버전 코덱에서 허용하지 않는 선택이다. 알려진 scalar·string·enum 단일 필드가 반복되면 **마지막 값**을 사용한다. 단일 메시지 필드 반복은 Protobuf의 병합 규칙을 따른다. 따라서 중복 필드가 있다는 이유만으로 손상으로 취급하지 않으며, 같은 의미의 메시지가 하나의 유일한 바이트 표현을 가진다고 보장하지 않는다. [공식 presence·중복 필드 설명](https://protobuf.dev/programming-guides/field_presence/)

### 값 검사와 코어의 판단

문자열은 UTF-8을 엄격하게 읽고 쓴다. 잘못된 바이트나 고립 서로게이트를 대체 문자로 바꾸지 않으며 문자열 정규화도 하지 않는다. 숫자는 도메인 값 생성 시 가격·정책 버전 양수, 수량·금액 0 이상, scale 0–18, ppm 0–1,000,000을 확인한다. 나노초는 0–999,999,999인지 먼저 검사하고 초는 Instant 범위 안이어야 한다. 나노초를 다음 초로 보정하지 않는다.

응답의 순번은 양수다. 부족 상세는 필요액 > 현재액이며 부족액 = 필요액−현재액이어야 한다. 명령의 다른 마켓 정의·선물 정책·빈 원장 ID처럼 표현할 수 있는 문맥은 코덱이 먼저 업무 거절하지 않는다. 같은 원본의 재전송이면 기존 코어가 이미 저장한 답을 먼저 돌려줄 수 있기 때문이다.

변환 실패는 `AdmissionMessageException`, 코어의 업무 거절은 작은 `Rejected` 응답, 다른 주문의 승인 결과를 연결한 오류는 `IllegalStateException`이다. 코어 승인 뒤 응답 인코딩이 실패해도 이미 반영한 자금·순번을 되돌리는 기능은 없다. 매번 새 배열과 값 객체를 반환하므로 다음 인코딩이나 입력 배열 덮어쓰기가 이전 반환값을 바꾸지 않는다.

실제 판단 위치: [본문·필드 번호](../app-api/src/main/proto/order_admission.proto#L11) · [명령 presence](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionCommandCodec.kt#L73) · [사유·부족 상세 일치](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionResultCodec.kt#L123) · [미지 필드 거절](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageFormat.kt#L73)

<!-- contract87:end -->

</details>

<details markdown="1">
<summary>전체 테스트를 상황·실행·결과로 읽기</summary>

종류는 외부 서버·DB 없이 실제 코덱과 접수 코어를 호출하는 메모리 테스트다. 준비·호출·확인문을 읽은 설명과 실제 실행 결과를 구분한다.

<!-- tests87:start -->

현재 **네 클래스·32개 테스트 정의**의 입력·실행·확인문을 설명한다. 반복 입력은 정의 수와 다르다. 실행 상태는 위 현재 기록과 대조한다.

<details markdown="1">
<summary>명령 값·독립 예시 바이트 · 3개</summary>

**접수 명령은 Protobuf 전송 계약 버전 2로 인코딩한다** · [테스트](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionCommandCodecTest.kt#L28)

- **Given:** 일반 접수 명령을 준비한다.
- **When:** 인코딩한 프레임의 헤더를 little-endian으로 읽는다.
- **Then:** 버전이 2인지 직접 확인한다.

확인문: [assertEquals L36](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionCommandCodecTest.kt#L36)

**주문과 판단 정보를 Protobuf로 보냈다가 같은 값으로 읽는다** · [테스트](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionCommandCodecTest.kt#L40)

- **Given:** 요청 R1, 가격 정수 100·수량 정수 3, maker 10ppm·taker 20ppm과 나노초 시각이 있는 명령.
- **When:** 인코딩 후 디코딩하고 원본 명령을 다시 인코딩한다.
- **Then:** 명령 전체가 같고 두 인코딩의 바이트 배열도 같다.

확인문: [assertEquals L43](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionCommandCodecTest.kt#L43), [assertContentEquals L44](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionCommandCodecTest.kt#L44)

**스키마에서 독립적으로 정한 바이트와 양방향으로 대조한다** · [테스트](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionCommandCodecTest.kt#L48)

- **Given:** 직접 적은 79바이트 프레임: 본문71·명령1·스키마87·버전2, 명시한 scale0·두 단위1과 초·나노초가 있는 명령. 이전 본문65 프레임은 별도 읽기 검사로 보존한다.
- **When:** 예시 명령을 encode하고 고정 예시 바이트를 decode한다.
- **Then:** 바이트 전체와 복원 명령 전체가 각각 예시와 일치한다.

확인문: [assertContentEquals L57](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionCommandCodecTest.kt#L57), [assertEquals L58](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionCommandCodecTest.kt#L58)

</details>

<details markdown="1">
<summary>실제 코어 승인·부족 거절 · 2개</summary>

**실제 접수 승인은 자금 반영 뒤 작은 확인 응답으로 왕복한다** · [테스트](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionResultCodecTest.kt#L18)

- **Given:** 사용 가능 1,000 USDT에서 가격 100 USDT·수량 3 BTC의 매수 요청. 수수료는 0이다.
- **When:** 명령 왕복 → 실제 admit → 작은 승인 응답 왕복.
- **Then:** 작은 승인 응답과 순번 1을 확인한다. 사용 가능 700 USDT·예약 300 USDT이며 대기 주문과 요청 기록이 각각 1개다. 원장 분개 2개와 예약액 정수 300,000,000도 확인한다.

확인문: [assertIs L21](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionResultCodecTest.kt#L21), [assertEquals L23](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionResultCodecTest.kt#L23), [assertEquals L24](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionResultCodecTest.kt#L24), [assertEquals L26](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionResultCodecTest.kt#L26), [assertEquals L27](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionResultCodecTest.kt#L27), [assertEquals L28](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionResultCodecTest.kt#L28), [assertEquals L29](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionResultCodecTest.kt#L29), [assertEquals L30](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionResultCodecTest.kt#L30), [assertEquals L31](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionResultCodecTest.kt#L31)

**실제 자금 부족 거절은 당시 부족 상세를 그대로 왕복한다** · [테스트](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionResultCodecTest.kt#L35)

- **Given:** 사용 가능 200 USDT, 필요한 예약 300 USDT.
- **When:** 명령 왕복 → 실제 admit → 거절 응답 왕복.
- **Then:** 응답에 현재 200 USDT·필요 300 USDT·부족 100 USDT가 그대로 남는다. 사용 가능 200 USDT·예약 0 USDT, 대기 주문 없음·승인 순번 0·최초 거절 기록 1개다.

확인문: [assertEquals L48](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionResultCodecTest.kt#L48), [assertEquals L49](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionResultCodecTest.kt#L49), [assertEquals L51](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionResultCodecTest.kt#L51), [assertEquals L52](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionResultCodecTest.kt#L52), [assertEquals L53](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionResultCodecTest.kt#L53), [assertEquals L54](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionResultCodecTest.kt#L54)

</details>

<details markdown="1">
<summary>재전송·충돌·버퍼·작은 응답 · 11개</summary>

**수수료가 있는 매도도 BTC 두 개만 예약하고 당시 정책을 보존한다** · [테스트](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L30)

- **Given:** 사용 가능 BTC 3개 중 2개를 매도한다. VVIP·정책 버전 7, maker 500ppm·taker 1,000ppm이다.
- **When:** 명령을 왕복해 실제 코어에 적용하고 승인 응답도 왕복한다.
- **Then:** 명령과 정책이 그대로 보존된다. BTC 사용 가능 1개·예약 2개이며 수수료 선예약은 0이다. 작은 승인 응답도 같다.

확인문: [assertEquals L48](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L48), [assertIs L49](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L49), [assertEquals L50](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L50), [assertEquals L51](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L51), [assertEquals L52](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L52), [assertEquals L53](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L53), [assertEquals L55](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L55)

**같은 원본 재전송은 쓰지 않을 새 문맥보다 최초 승인을 우선한다** · [테스트](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L59)

- **Given:** R1/O1을 먼저 보내 첫 답과 snapshot을 저장한다. 바꿀 문맥은 다른 마켓 정의·선물 정책·빈 원장 ID/Instant.MAX 세 가지다.
- **When 1:** 각 문맥을 바꾼 명령을 바이트 왕복한 뒤 같은 R1/O1로 재전송한다.
- **Then 1:** 명령 전체가 원문과 같고, 재전송의 답은 첫 답과 같으며 snapshot도 유지된다.
- **When 2:** 각 문맥은 그대로 두고 새 R2/O2로 보낸다.
- **Then 2:** 문맥별 IllegalArgumentException 메시지가 기대값과 같고 snapshot이 유지된다. 세 경우를 끝낸 뒤 비차단 상태도 확인한다.

확인문: [assertEquals L79](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L79), [assertEquals L80](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L80), [assertEquals L81](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L81), [assertFailsWith L83](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L83), [assertEquals L86](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L86), [assertEquals L87](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L87), [assertFalse L89](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L89)

**같은 요청의 다른 주문과 새 요청의 기존 주문은 서로 다른 거절이다** · [테스트](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L93)

- **Given:** R1/O1을 먼저 보내 snapshot을 저장한다.
- **When 1:** R1의 주문 ID를 O2로 바꾸거나 수량을 바꿔 각각 보낸다.
- **Then 1:** 지금 제출한 주문 ID가 담긴 내용 충돌 응답이며 전체 snapshot이 유지된다.
- **When 2:** 새 요청 R2로 기존 주문 O1을 보낸다.
- **Then 2:** 주문 중복 응답이다. 잔고·대기 주문·승인 순번 1은 유지되고 요청 기록은 2개다.

확인문: [assertEquals L104](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L104), [assertEquals L114](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L114), [assertEquals L116](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L116), [assertEquals L121](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L121), [assertEquals L122](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L122), [assertEquals L123](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L123), [assertEquals L124](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L124)

**현재 잔고가 줄어도 같은 요청은 최초 부족 금액을 반환한다** · [테스트](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L128)

- **Given:** 사용 가능 200 USDT에서 R1을 먼저 보낸다. 첫 응답의 현재 200·필요 300·부족 100 USDT를 직접 확인한다.
- **When 1:** R2/O2로 100 USDT가 필요한 작은 주문을 보낸다.
- **Then 1:** 승인 응답이고 사용 가능 잔고는 100 USDT다. 이 시점의 snapshot을 저장한다.
- **When 2:** 원래 R1을 재전송한다.
- **Then 2:** 첫 부족 응답 전체와 같고, 재전송 직전 snapshot도 그대로다.

확인문: [assertIs L132](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L132), [assertEquals L133](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L133), [assertEquals L134](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L134), [assertEquals L135](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L135), [assertIs L137](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L137), [assertEquals L139](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L139), [assertEquals L140](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L140), [assertEquals L141](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L141)

**문자열을 정규화하지 않아 서로 다른 요청 키와 원문을 유지한다** · [테스트](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L145)

- **Given:** 서로 다른 é, e+결합악센트, 한글:요청 세 요청과 서로 다른 주문ID.
- **When:** 각 명령을 왕복하고 순서대로 실제 코어에 보낸다.
- **Then:** 각 원문 명령이 같고 승인 순번1·2·3, 저장 요청ID 목록도 원문 순서 그대로다.

확인문: [assertEquals L149](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L149), [assertEquals L150](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L150), [assertEquals L152](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L152)

**승인 뒤 응답 변환 실패는 이미 반영된 자금을 되돌리지 않는다** · [테스트](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L156)

- **Given:** 실제 코어에 접수한 뒤 작은 승인 응답과 snapshot을 저장한다.
- **When 1:** 응답 requestId를 257바이트로 바꿔 encode한다.
- **Then 1:** AdmissionMessageException이며 snapshot은 그대로다.
- **When 2:** 원래 명령을 다시 바이트 왕복해 보낸다.
- **Then 2:** 원래 승인 응답 전체와 같고 snapshot도 유지된다.

확인문: [assertIs L161](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L161), [assertFailsWith L162](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L162), [assertEquals L163](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L163), [assertEquals L164](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L164), [assertEquals L165](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L165)

**다른 주문의 승인 결과를 응답으로 연결하면 내부 오류로 중단한다** · [테스트](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L169)

- **Given:** O1의 실제 승인 결과.
- **When:** 현재 명령의 주문ID·사용자·마켓·수량을 각각 바꿔 같은 승인을 응답에 연결한다.
- **Then:** 모두 IllegalStateException이며 승인 순번은1이다. 이 테스트의 마지막 직접 상태 관측은 순번이다.

확인문: [assertFailsWith L183](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L183), [assertEquals L187](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L187)

**다음 변환과 입력 버퍼 덮어쓰기는 이전 바이트와 접수 기록을 바꾸지 않는다** · [테스트](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L191)

- **Given:** 첫 명령·응답 배열과 복사본, 복원 명령, 실제 승인 뒤 snapshot을 저장한다.
- **When 1:** 다른 명령과 다른 응답을 각각 encode한다.
- **Then 1:** 첫 명령·응답 배열은 복사본과 바이트 전체가 같다.
- **When 2:** 첫 명령·응답 배열을 0으로 덮어쓴다.
- **Then 2:** 이전에 복원한 명령·snapshot이 유지된다. 저장한 요청의 원본 주문·마켓·정책·원장 ID·시각도 각각 같다.

확인문: [assertContentEquals L204](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L204), [assertContentEquals L205](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L205), [assertEquals L208](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L208), [assertEquals L209](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L209), [assertEquals L211](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L211), [assertEquals L212](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L212), [assertEquals L213](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L213), [assertEquals L214](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L214), [assertEquals L215](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L215), [assertEquals L216](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L216)

**작은 승인 응답은 독립 예시 바이트와 최대 순번을 보존한다** · [테스트](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L220)

- **Given:** u/r/m/o·순번1 응답과 Protobuf 본문14바이트를 담은 독립22바이트 프레임.
- **When:** 예시 바이트와 양방향 대조하고 Long.MAX_VALUE 순번도 왕복한다.
- **Then:** encode 바이트와 decode 응답이 각각 독립 예시와 일치하고 최대 순번도 같다.

확인문: [assertContentEquals L224](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L224), [assertEquals L225](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L225), [assertEquals L227](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L227)

**부족 응답의 현재 필요 부족 금액 위치를 독립 바이트로 대조한다** · [테스트](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L231)

- **Given:** 부족 사유5·자산Q·현재200/필요300/부족100을 담은 독립35바이트 프레임. 본문27바이트다.
- **When:** 응답 encode와 고정 Protobuf 바이트 decode를 각각 실행한다.
- **Then:** 중첩 상세의 태그·값을 포함한 바이트 전체와 응답 전체가 각각 일치한다.

확인문: [assertContentEquals L243](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L243), [assertEquals L244](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L244)

**모든 거절 사유는 명시된 전송 코드와 상세 유무를 보존한다** · [테스트](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L248)

- **Given:** 기존 거절 사유 전부와 독립 코드표1–8. 부족 사유에만 현재0/필요Long.MAX_VALUE/부족Long.MAX_VALUE 상세.
- **When:** 각 응답을 encode한 본문을 생성 파서로 읽고 실제 코덱으로 decode한다.
- **Then:** 코드표가 기존 사유 전부를 포함하고 파서의 reasonValue가 독립 코드와 같으며 복원 응답도 전체가 같다.

확인문: [assertEquals L260](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L260), [assertEquals L270](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L270), [assertEquals L271](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L271)

</details>

<details markdown="1">
<summary>누락·미지 값·길이·UTF-8 경계 · 16개</summary>

**공유 버퍼의 정확한 구간만 읽고 반환한 명령은 원본 버퍼와 분리된다** · [테스트](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L38)

- **Given:** 명령을 offset13에 넣고 앞뒤를 다른 값으로 채운 공유 배열.
- **When:** 정확한 구간을 읽은 뒤 배열을0으로 덮어쓴다. 이어서 명령의 마지막 바이트를 논리 구간 밖에 두고 읽는다.
- **Then:** 먼저 복원한 명령 전체가 원본과 같다. 잘린 구간은 공통 helper의 변환 예외·코어 진입0·전체 snapshot 유지·비차단을 확인한다.

확인문: [assertEquals L46](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L46), [assertCommandRejected L47](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L47)

**구간과 필수 문자열이 잘렸거나 남은 바이트가 있으면 코어 진입 전에 실패한다** · [테스트](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L51)

- **Given:** 헤더/선언 본문/문자열 잘림·미완 varint·프레임 뒤 잔여값·음수/Int.MAX offset·음수/배열초과 length·4097바이트의10입력.
- **When:** 각 구간을 실제 명령 디코더에 넘기고 성공할 때만 코어를 호출한다.
- **Then:** 모두 변환 예외이며 공통 helper에서 코어 진입0·전체 snapshot 유지·비차단을 확인한다.

확인문: [assertCommandRejected L67](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L67)

**다른 스키마 버전 종류와 본문 길이는 추측해 읽지 않는다** · [테스트](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L71)

- **Given:** 스키마 86, 이전 버전 1·미지원 3, 종류 2·99, 실제보다 작은·큰·0인 본문 길이의 8개 입력.
- **When:** 각 헤더를 바꾼 프레임을 디코딩한다.
- **Then:** 모두 변환 예외다. 공통 helper에서 코어 진입 0·전체 snapshot 유지·비차단을 확인한다.

확인문: [assertCommandRejected L85](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L85)

**모든 명령 열거 필드에서 미지 코드와 미지정 코드를 거절한다** · [테스트](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L89)

- **Given:** side/orderType/timeInForce/productType/feeTier 각 필드에 미지정0과 미지255를 준비한다.
- **When:** 생성 builder로 만든5×2조합의 프레임을 디코딩한다.
- **Then:** 10조합 모두 변환 예외이며 공통 helper에서 진입0·snapshot 유지·비차단을 확인한다.

확인문: [assertCommandRejected L100](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L100)

**알려진 명령 열거 코드는 모두 정해진 업무 값으로 읽는다** · [테스트](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L106)

- **Given:** side 2개·orderType 2개·timeInForce 2개·productType 3개·feeTier 4개의 알려진 코드와 기대 명령.
- **When:** 13개 입력을 생성 builder로 설정해 디코딩한다.
- **Then:** 명령 전체가 기대값과 같다. 코어의 승인 가능 여부는 이 테스트의 판단 범위 밖이다.

확인문: [assertEquals L129](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L129)

**명령의 필수 필드 하나라도 누락하면 0이나 빈 문자열로 채우지 않는다** · [테스트](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L133)

- **Given:** 필드 1–23을 모두 채운 명령과, 수량·scale·초·나노초 0 및 빈 요청·원장 ID를 명시한 명령.
- **When:** 필드를 하나씩 지운 23개 프레임과 빈 본문을 읽고, 이어서 명시한 기본값이 있는 프레임을 읽는다. 단위 두 필드를 함께 지운 이전 명령은 별도 검사에서 읽는다.
- **Then:** 누락과 빈 본문은 공통 실패 관측을 만족한다. 명시한 수량 0·scale 0·Instant.EPOCH·빈 요청/원장 ID는 그대로다.

확인문: [assertEquals L135](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L135), [assertCommandRejected L138](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L138), [assertCommandRejected L140](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L140), [assertEquals L154](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L154), [assertEquals L155](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L155), [assertEquals L156](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L156), [assertEquals L157](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L157), [assertEquals L158](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L158)

**도메인 값으로 표현할 수 없는 숫자와 정규화될 나노초는 변환 오류다** · [테스트](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L162)

- **Given:** 가격 0/-1, 수량 -1, scale 19/-1, 정책 버전 0/-1, maker -1/taker 1,000,001ppm, nano -1/10억, Instant 범위 밖 최소·최대 초의 13개 입력.
- **When:** 각 값을 생성 builder에 넣어 프레임을 읽는다.
- **Then:** 값을 보정하지 않고 변환 예외다. 공통 helper에서 진입 0·snapshot 유지·비차단을 확인한다.

확인문: [assertCommandRejected L179](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L179)

**256바이트 UTF8 경계는 보존하고 잘못된 길이와 문자열은 대체하지 않는다** · [테스트](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L183)

- **Given:** 64개 이모지, 잘못된 길이·UTF-8·과잉 인코딩·공백 사용자, 식별자 14–21 각각 257바이트, 257바이트 원문과 고립 상·하위 서로게이트.
- **When:** 정상 경계는 왕복한다. 손상·초과 바이트는 decode하고, 잘못된 원문은 encode 뒤 decode한다.
- **Then:** 64개 이모지는 256바이트이고 명령 전체가 같다. 나머지는 공통 helper에서 변환 예외·진입 0·snapshot 유지·비차단이다.

확인문: [assertEquals L185](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L185), [assertEquals L186](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L186), [assertCommandRejected L196](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L196), [assertEquals L199](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L199), [assertCommandRejected L202](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L202), [assertRejectedBeforeAdmission L205](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L205)

**모든 메시지와 부족 상세의 알 수 없는 필드를 거절한다** · [테스트](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L210)

- **Given:** 알 수 없는 99번 varint 필드를 명령·승인·거절·중첩 부족 상세에 각각 추가한다.
- **When:** 각 프레임을 실제 코덱으로 읽는다.
- **Then:** 네 경우 모두 AdmissionMessageException이다. 명령은 진입 0·상태 유지도 관측하고, 응답은 예외만 관측한다.

확인문: [assertCommandRejected L213](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L213), [assertFailsWith L228](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L228)

**중복 단일 필드는 Protobuf의 마지막 값 규칙을 따르고 4096바이트 구간도 읽는다** · [테스트](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L232)

- **Given:** 원본 뒤에 가격 200,000,000 필드를 추가한 본문과, 알려진 단일 필드를 반복해 4088바이트로 채운 본문.
- **When:** 첫 프레임을 읽고, 정확히 4096바이트 프레임을 읽은 다음 1바이트를 추가해 읽는다.
- **Then:** 마지막 가격이 적용된 주문 전체가 같다. 4096바이트는 원래 명령을 복원하고, 4097바이트는 공통 실패 관측을 만족한다.

확인문: [assertEquals L241](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L241), [assertEquals L242](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L242), [assertEquals L249](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L249), [assertEquals L250](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L250), [assertCommandRejected L251](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L251)

**Long 최대 값과 Instant 양 끝 및 수치 상한은 손실이나 NULL 대체 없이 왕복한다** · [테스트](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L255)

- **Given:** SELL 최대 가격/수량·버전Long.MAX_VALUE, scale0/18, ppm0/100만, Instant.MIN/MAX와 음수 초. BTC잔고도Long.MAX_VALUE다.
- **When:** 시각 경계와 scale0 명령을 왕복하고, scale0의 최대 가격·수량은 요청 ID 없는 내부 접수에 넣는다. 이어 가격1·최대 수량인 대조 명령을 왕복해 실제 코어에 넣는다.
- **Then:** 각 명령 전체가 같다. 최대 가격×최대 수량의 quote 대금은 `UNREPRESENTABLE_RESERVATION`으로 거절되고 snapshot 전체가 유지된다. 가격1 대조 명령은 승인되며 주문·예약액Long.MAX_VALUE와 BTC사용 가능0/예약Long.MAX_VALUE 목록이 일치한다. USDT 항목은 없다.

확인문: [assertEquals L270](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L270), [assertEquals L274](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L274), [assertIs L281](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L281), [assertEquals L282](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L282), [assertEquals L283](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L283), [assertEquals L284](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L284)

**표현 가능한 업무 위반은 코어의 정상 거절 사유와 요청 기록 규칙을 유지한다** · [테스트](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L292)

- **Given:** MARKET·IOC·수량0·빈/65글자 요청ID·65글자/NUL 주문ID를 각각 준비한다.
- **When:** 변환에서 원문을 보존한 뒤 실제 코어에 적용한다.
- **Then:** 명령 전체가 같고 기존 정상 거절 사유가 맞으며 자금·대기·순번은 유지한다. 요청 기록 수는 사례별0 또는1, 비차단 상태다.

확인문: [assertEquals L339](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L339), [assertIs L340](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L340), [assertEquals L341](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L341), [assertEquals L343](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L343), [assertEquals L344](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L344), [assertEquals L345](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L345), [assertEquals L346](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L346), [assertFalse L347](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L347)

**작은 응답도 정확한 구간과 Long 최대 순번 및 금액을 보존한다** · [테스트](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L352)

- **Given:** 최대 순번 승인과 금액0/Long.MAX_VALUE/Long.MAX_VALUE 부족 응답.
- **When:** 승인을 offset7의 정확 구간에서 읽은 뒤 공유 배열을 덮어쓰고, 부족 응답도 왕복한다.
- **Then:** 먼저 읽은 승인 응답 전체와 최대 금액 부족 응답 전체가 원본과 같다.

확인문: [assertEquals L359](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L359), [assertEquals L361](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L361)

**응답의 필수 필드와 부족 상세의 필드도 하나씩 누락을 거절한다** · [테스트](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L365)

- **Given:** 승인의 필수 5개·거절의 필수 5개·상세의 필수 4개 필드. 별도로 현재액 0인 상세와 빈 요청 ID 승인을 준비한다.
- **When:** 각 필드를 하나씩 지운 14개 프레임을 decode한 뒤, 값을 명시한 프레임도 읽는다.
- **Then:** 누락은 모두 변환 예외다. 명시한 현재액은 Amount.ZERO이고 빈 요청 ID도 그대로다. 코어 상태는 관측하지 않는다.

확인문: [assertFailsWith L372](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L372), [assertEquals L378](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L378), [assertFailsWith L381](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L381), [assertEquals L392](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L392), [assertIs L394](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L394), [assertEquals L399](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L399)

**응답의 종류 본문 길이 문자열과 마지막 바이트도 검증한다** · [테스트](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L403)

- **Given:** 헤더 오류·프레임/문자열 잘림·잔여값·승인/거절/중첩 자산의 잘못된 UTF-8인 14개 입력과, 두 응답의 식별자 각 4개를 257바이트로 설정한 프레임.
- **When:** 각 응답을 읽고, 공유 배열의 마지막 바이트를 논리 구간 밖에 두고도 읽는다.
- **Then:** 모두 AdmissionMessageException이다. 식별자 반복은 두 응답×4개이며, 코어 호출·상태 관측은 없다.

확인문: [assertFailsWith L454](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L454), [assertEquals L462](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L462), [assertFailsWith L465](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L465), [assertFailsWith L470](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L470)

**응답 순번과 거절의 상세 유무 금액은 서로 일치해야 한다** · [테스트](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L476)

- **Given:** 순번 0/-1·사유 0/255·상세 유무 모순·음수 금액·현재액=필요액·차액 불일치·빈/257바이트 자산의 13개 프레임과 모순된 응답 객체 5개.
- **When:** 손상 프레임은 decode하고 모순된 응답 객체는 encode한다.
- **Then:** 모두 AdmissionMessageException이다. 코어 실행·상태는 이 테스트 범위 밖이다.

확인문: [assertFailsWith L546](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L546), [assertFailsWith L555](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L555)

</details>

**공통 손상 명령 관측:** 별도의 유효 요청을 승인한 뒤 snapshot을 저장한다. 변환이 성공했을 때만 진입 횟수를 올려 admit를 호출한다. 변환 예외 뒤 코어 진입 0·snapshot 전체 동일·isBlocked=false를 확인한다. [실제 helper](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L565)

응답만 읽는 손상 테스트는 예외를 확인하며 코어 상태를 별도로 관측하지 않는다. 복합 재전송 테스트는 Given 이후 When에 적은 순서대로 요청하고 각 단계의 응답·상태를 대조한다.

<!-- tests87:end -->

</details>

<details markdown="1">
<summary>변경 파일·빌드·생성 코드의 책임과 소스 스냅샷</summary>

Protobuf Gradle 플러그인 `0.9.5`와 `protoc`·전체 Java 런타임 `4.33.2`를 사용한다. `:app-api:generateProto`가 `app-api/src/main/proto/order_admission.proto`에서 `app-api/build/generated/sources/proto/main/java` 아래 Java 코드를 만든다. 생성 코드는 build 폴더에서 관리한다. Spring Boot가 자동 등록하는 gRPC 생성기는 사용하지 않으므로 생성 작업에서 제거하고 Java 생성 작업·소스 연결은 유지한다. 구조 검사에는 생성 메시지의 상위 타입을 읽는 Protobuf 런타임을 연결하고, 생성 루트와 생산 작업을 정확히 등록한다.

<!-- evidence87:start -->

| 역할·연결 흐름 | 현재 원문 | SHA-256 앞 12자리 |
| --- | --- | --- |
| 제품 값·명령 지원 | [AdmissionCommandPayload.kt](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionCommandPayload.kt#L1) | `3d9635128249` |
| 제품 실행·명령/응답/실패 흐름 | [AdmissionCommandCodec.kt](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionCommandCodec.kt#L1) | `590b5a4bdca0` |
| 제품 응답·결과 연결 | [AdmissionResultPayload.kt](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionResultPayload.kt#L1) | `cad4d89f3186` |
| 제품 실행·명령/응답/실패 흐름 | [AdmissionResultCodec.kt](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionResultCodec.kt#L1) | `e880b1e6ca99` |
| 제품 실행·명령/응답/실패 흐름 | [AdmissionMessageFormat.kt](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageFormat.kt#L1) | `321eb80c5229` |
| 제품 전송 스키마 | [order_admission.proto](../app-api/src/main/proto/order_admission.proto#L1) | `44acf59017eb` |
| 빌드·생성 코드 연결 | [build.gradle.kts](../app-api/build.gradle.kts#L1) | `70f51f046a77` |
| 빌드·생성 코드 연결 | [build.gradle.kts](../build.gradle.kts#L1) | `712a6a9c3c2f` |
| 개발 검증·정확한 생성 루트 정책 | [ProjectLayoutPolicy.kt](../architecture-tests/src/test/kotlin/com/exchange/architecture/policy/ProjectLayoutPolicy.kt#L1) | `1c36292a1057` |
| 개발 검증·모듈 방향 정책 | [ModuleDependencyDirection.kt](../architecture-tests/src/test/kotlin/com/exchange/architecture/rules/ModuleDependencyDirection.kt#L1) | `f1d3b7f30a79` |
| 개발 검증·구조 규칙 테스트 | [ModuleDependencyContractTest.kt](../architecture-tests/src/test/kotlin/com/exchange/architecture/ModuleDependencyContractTest.kt#L1) | `05014f947d20` |
| 개발 검증·구조 규칙 테스트 | [NamingPlacementScopeTest.kt](../architecture-tests/src/test/kotlin/com/exchange/architecture/NamingPlacementScopeTest.kt#L1) | `0cc96247e9e0` |
| 개발 검증·컴파일 산출물 수집 | [build.gradle.kts](../architecture-tests/build.gradle.kts#L1) | `b119bdde90c2` |
| 제품 동작 테스트 | [AdmissionCommandCodecTest.kt](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionCommandCodecTest.kt#L1) | `16537d3f2c55` |
| 제품 동작 테스트 | [AdmissionResultCodecTest.kt](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionResultCodecTest.kt#L1) | `3ac674469785` |
| 제품 동작 테스트 | [AdmissionProtocolFlowTest.kt](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionProtocolFlowTest.kt#L1) | `8e4b643736b5` |
| 제품 동작 테스트 | [AdmissionMessageBoundaryTest.kt](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageBoundaryTest.kt#L1) | `fc5ffca5866c` |
| 테스트 공통 준비·독립 프레임 생성 | [AdmissionCodecFixtures.kt](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionCodecFixtures.kt#L1) | `be92adbf2f67` |
| 기존 제품 코어·변경하지 않은 호출 대상 | [OrderAdmissionState.kt](../domain-trading/src/main/kotlin/com/exchange/core/trading/OrderAdmissionState.kt#L1) | `7125b28235f3` |

비교 기준은 통합 브랜치의 `0cf852318e4173f06160fd5bafd4b846b5efdf88`이다. 표의 소스 해시는 실행 검증과 독립 리뷰를 받은 구현과 일치한다. 문서는 흐름·계약·실제 테스트 근거의 읽기 안내다.

<!-- evidence87:end -->

### 검증 결과와 재실행

- 접수 코덱 4개 클래스·32개 테스트: 실패·오류·skip 0. 생성물이 없는 clean 상태에서 `generateProto`가 Kotlin/Java 컴파일에 앞서 실행되는 것을 확인했다.
- 구조 검사 375개와 필수 운영 검사 P01–P09: 통과. 공통 `ktlintCheck`: 통과.
- 기존 `domain-trading` 82개 테스트는 변경되지 않은 소스의 UP-TO-DATE 결과를 재사용했다.
- 작성에 참여하지 않은 별도 컨텍스트의 리뷰어가 고정된 전체 소스 사본에서 clean 빌드·코덱 32개·구조 375개·app-api 린트를 실행했다. 별도 손상 입력 11개도 확인했고, 검토 범위의 확인된 미해결 결함은 없었다.
- 독립 리뷰 사본의 manifest SHA-256은 `5bf90597fe3f33c454d38805956f1896929b1d5d923473fdc0d9ae15651f0631`이다. 실제 게시할 코드·스키마·테스트·설정의 내용 해시를 대조했다. 게시용 문구·실행 기록을 보완한 것은 제품 동작 변경이 아니다.

`REVIEW_ROOT`에 이 브랜치를 체크아웃한 폴더의 절대 경로를 지정하고 실행한다. 다음은 확인한 검사 범위를 다시 실행하는 명령이다.

```sh
./gradlew -p "$REVIEW_ROOT" :app-api:clean :app-api:test --tests '*Admission*Test' --console=plain
./gradlew -p "$REVIEW_ROOT" ktlintCheck :architecture-tests:test :domain-trading:test :architecture-tests:verifyArchitectureReport --console=plain
```

최초 전환 중 생성 작업 연결과 검사 런타임 누락을 발견해 수정했다. 최종 clean 검증을 통과한 상태를 위에 기록했으며, 초기 컴파일 실패를 행동 수준의 TDD Red 증거로 사용하지 않는다. HTTP·DB·실제 Aeron 통신·Cluster 복제·배포 호환성·새 코덱 성능은 이 실행 범위에 포함하지 않는다.

</details>

<details markdown="1">
<summary>이번 변경에서 확인하는 범위와 다음 단계</summary>

`AdmissionCommandCodec`·`AdmissionResultCodec`·`AdmissionMessageFormat`과 전송 스키마·빌드·생성 코드 정책을 바꾼다. 기존 `AdmissionCommandPayload`·`AdmissionResultPayload`는 명령의 업무 값과 작은 응답의 경계로 계속 사용한다. 접수 코어와 HTTP API의 동작은 변경하지 않는다. 코어의 승인·거절·재전송 규칙은 실제 코어를 부르는 테스트에서 대조한다.

DB 저장·HTTP 연결·Aeron 전송·Cluster 복제·배포와 호환성 운영은 후속 작업이다. 이번 버전은 기존 SBE 바이트를 해석하거나 버전 1로 되돌리는 호환 계층을 제공하지 않는다. 실제 통신·복제·재시작 복구의 성공과 새 제품 코덱의 성능은 여기서 검증하지 않는다.

다음 판단은 **필수 값 누락은 거절하면서 명시한 0·빈 문자열은 업무 판단에 필요한 원문으로 보존하는지**다. 상세 코드와 테스트에서 입력·실행·관측을 확인한다. 문서를 열거나 테스트가 통과했다는 사실로 사람의 이해·승인을 기록하지 않는다.

</details>
