# #24 주석과 흐름 문서 정리 — 상세 설계

**로컬 구현 완료 기록: 구현·컴파일·독립 리뷰를 마쳤다. 아래 설계 당시 원문과 [실제 구현 기록](#구현-기록)을 구분한다. 게시 이후 리뷰·원격 CI 상태는 이 브랜치의 PR에서 확인한다.**

기준은 최신 통합 브랜치 `feature/phase-2/integration`의 `9be693631c158eac630f3017f5b0a05c813bd067`이다(2026-10-03 확인). 작업 폴더는 `/Users/0chord/.codex/worktrees/issue24-comment-spec/exchange-core`, 브랜치는 `docs/comment-contracts/24`다. 이슈는 [#24](https://github.com/0Chord/coin-exchange/issues/24), 공통 기준은 [주석·학습 설명](conventions-design.md#주석학습-설명과-예외)이다. 여기의 변경 후 문장은 **제안**이며 현재 코드로 표현하지 않는다.

## 먼저 볼 것

이번 결과는 **코드 옆에서 핵심 이유를 떠올리고, 긴 흐름은 한 문서에서 실제 코드·테스트까지 따라가는 것**이다. 주석 수나 길이를 목표로 삼지 않는다.

1. 사실과 다른 설명부터 고친다. 현재 지원 주문은 LIMIT/GTC이고, publisher 성공이 항상 DB 저장 성공을 뜻하지는 않는다.
2. 금액 단위·반올림·원본 보존·실패 후 남는 상태는 코드 가까이에 유지한다.
3. 여러 클래스에 걸친 처리 순서·긴 BUY/SELL 비교·회계 배경은 기존 흐름 문서에 모은다.
4. 자연어 설명이 말하는 보장을 기존 코드·테스트와 대조한다. 테스트를 새로 많이 만드는 작업이 아니다.

주석과 흐름 문서에서 중요한 판단을 찾고, 해당 코드·테스트로 확인할 수 있게 한다. 읽기 단위는 아래 **현재 설명 수정 → 금액·불변식 → 실행·상태 → API·저장·문서 대조** 순서다.

## 범위와 보존할 것

이번 구현 대상은 아래 표에 지정한 **대표 주석 블록과 연결 문서**다. 프로젝트 전체에 동일한 기준을 쓰지만, 모든 함수의 주석을 한 PR에서 재작성하지 않는다. 같은 파일 안에서도 유지할 블록과 축약할 블록을 구분한다.

| 포함 | 제외·유지 |
| --- | --- |
| 도메인·HTTP·유즈케이스·저장·실행기의 대표 KDoc/인라인 주석 | 함수 본문·시그니처·가시성·어노테이션·SQL·JSON·예외 메시지 변경 없음 |
| 한국어 설명, 필요한 수치 예시, 실패 경계, 문서와 코드 연결 | 영어 식별자·외부 API 이름은 유지. 문자열을 번역하는 코드 변경은 하지 않음 |
| 긴 설명의 기존 문서 이동, 오래된 현재형 설명 수정 | 새 문서 플랫폼·주석 검사기·KDoc 생성 도구·아키텍처 규칙 도입 없음 |
| 기존 테스트의 관측 내용과 설명의 대조 | 새 테스트 작성만을 위한 TDD, 주석 수·줄 수·커버리지 목표 없음 |
| 이번 대상과 직접 연결된 README·공통 컨벤션·흐름 문서 | 과거 PR 명세·실행 기록의 역사 재작성, ARCH 검사 코드·JMH 전체 주석 정리 제외 |

거래·수수료 정책, DB 트랜잭션, config의 Bean 조립, 순수 도메인과 내부 가변 주문장의 경계는 유지한다. 실제 동작 결함이 발견되면 주석만 바꿔 덮지 않고 영향과 후속 수정 범위를 별도로 제시한다.

## 합의한 주석의 깊이

**Ask 기록 · 2026-10-03**

- 질문: 긴 학습 설명을 문서로 옮긴 뒤에도 코드에서 바로 기억할 수 있도록, 주석에 어느 정도를 남길까?
- AI 추천과 근거: 역할·핵심 이유·실패 경계와 문서 연결을 남기고, 수수료 반올림처럼 숫자가 있어야 이해되는 곳에만 짧은 예시를 유지한다. 설명을 두 곳에 길게 복제하는 비용을 줄이면서 기억할 단서를 남긴다.
- 사용자 실제 답변: **“핵심 계약과 필요한 숫자 예시만 코드에 유지 (추천)”**.
- 별도의 선택 이유는 답변에 없으므로 추측하지 않는다. 다른 대안은 각 핵심 클래스에 자세한 학습 설명도 남기는 것이었다.
- 반영: FeeRemainder의 `510,000 → 0.51원`, 내부 주문의 `10에서 4 체결 → 원수량 10/잔량 6`처럼 차이를 설명하는 예시는 유지할 수 있다. 긴 정산 순서·대안 비교는 문서로 이동한다. 모든 클래스에 수치 예시나 `@param`을 의무적으로 추가하지 않는다.

핵심 보장·범위에 남은 차단 결정은 없다. 표현과 블록 길이는 구현자가 조정할 수 있으나, 아래 보존 계약을 삭제하거나 바꾸면 다시 판단해야 한다. 이 답변을 전체 코드의 이해·구현 승인으로 기록하지 않는다.

## 네 가지 분류 기준

| 분류 | 판단 기준 | 바꾸면 안 되는 것 |
| --- | --- | --- |
| 유지 | 코드만 보고 놓치기 쉬운 이유·단위·동시성·실패 계약 | 나머지의 의미, SQL 원자성, timeout 이후 계속 실행 같은 핵심 정보 |
| 축약 | 이름·바로 아래 코드·다른 주석과 같은 설명 반복 | “무엇을 안 한다”가 실제 오용을 막는 정보면 단순 반복으로 삭제하지 않음 |
| 문서 이동 | 여러 클래스의 전체 흐름, 긴 비교표·회계 배경·미래 대안 | 먼저 문서에 옮겨 의미를 대조하고 코드에는 핵심 계약과 찾아갈 위치를 남김 |
| 수정 | 현재 필드·지원 조건·설정·이미 검증한 범위와 설명 불일치 | 원하는 미래 동작으로 고치지 않고 실제 현재 동작에 맞춤 |

분류는 파일이 아니라 설명 블록에 적용한다. 예를 들어 정산 클래스의 전체 흐름은 이동해도, 그 함수의 수수료 이중 차감 금지와 한 체결의 롤백 경계는 유지한다.

## 도메인 대표 사례

줄 번호는 기준 커밋의 위치이며, 구현 후에는 심볼과 최신 줄을 다시 연결한다. 테스트 링크는 **소스에서 확인한 기존 사례**이며 이번 설계에서 실행했다는 뜻이 아니다.

| 대상·현재 설명 | 처리와 제안 | 보존 계약·근거 |
| --- | --- | --- |
| [TradingFeeCalculator.calculateFee](https://github.com/0Chord/coin-exchange/blob/9be693631c158eac630f3017f5b0a05c813bd067/domain-fee/src/main/kotlin/com/exchange/core/fee/TradingFeeCalculator.kt#L34): 이전 나머지에 요율을 다시 곱하지 않음 | **유지**. 중간 곱셈에 BigInteger를 쓰는 이유도 유지 | `(금액×ppm+이전 분자)÷1,000,000`의 몫/나머지와 호출자의 이월 책임. [계산 테스트](https://github.com/0Chord/coin-exchange/blob/9be693631c158eac630f3017f5b0a05c813bd067/domain-fee/src/test/kotlin/com/exchange/core/fee/TradingFeeCalculatorTest.kt#L66)의 overflow·이월·분할 동일 요율 사례 |
| [FeeRemainder](https://github.com/0Chord/coin-exchange/blob/9be693631c158eac630f3017f5b0a05c813bd067/domain-fee/src/main/kotlin/com/exchange/core/fee/FeeRemainder.kt#L3): 값 설명 뒤 계산·청구·저장 부재 반복 | **축약**. “이월할 소수 나머지이며 예약액·청구액이 아니다”와 510,000=0.51원 예시는 유지 | `0≤numerator<1,000,000`, 최소 금액 단위. [범위·단위 테스트](https://github.com/0Chord/coin-exchange/blob/9be693631c158eac630f3017f5b0a05c813bd067/domain-fee/src/test/kotlin/com/exchange/core/fee/FeeRemainderTest.kt#L14) |
| [LedgerTransaction](https://github.com/0Chord/coin-exchange/blob/9be693631c158eac630f3017f5b0a05c813bd067/domain-ledger/src/main/kotlin/com/exchange/core/ledger/LedgerTransaction.kt#L7): KRW 차이를 BTC로 상쇄할 수 없음, 목록 복사·외부 변경 차단 | **유지**. 자산별 균형과 검증 후 내용 보존의 이유 | [분개 테스트](https://github.com/0Chord/coin-exchange/blob/9be693631c158eac630f3017f5b0a05c813bd067/domain-ledger/src/test/kotlin/com/exchange/core/ledger/LedgerTransactionTest.kt#L13)는 목록 격리·자산별 균형·빈 목록 거절을 확인. 합계 Long 초과 직접 사례는 없으며 BigInteger 이유는 구현 근거로 구분 |
| [calculateQuoteAmount](https://github.com/0Chord/coin-exchange/blob/9be693631c158eac630f3017f5b0a05c813bd067/domain-order/src/main/kotlin/com/exchange/core/order/QuoteAmountCalculator.kt#L8): KDoc와 인라인의 중간 곱셈·나눗셈 설명 중복 | **축약**. KDoc에 입력 단위·scale·BigInteger 이유·정확한 나눗셈 거절을 남기고 반복 인라인만 줄임 | `price×quantity÷10^scale`에 나머지가 있으면 버림/반올림하지 않고 거절. [예약 계산 테스트](https://github.com/0Chord/coin-exchange/blob/9be693631c158eac630f3017f5b0a05c813bd067/domain-order/src/test/kotlin/com/exchange/core/order/OrderReservationCalculatorTest.kt#L94) |
| [OrderFillSettlementCalculator](https://github.com/0Chord/coin-exchange/blob/9be693631c158eac630f3017f5b0a05c813bd067/domain-order/src/main/kotlin/com/exchange/core/order/OrderFillSettlementCalculator.kt#L52): 클래스 KDoc의 긴 BUY/SELL 전체 비교 | **문서 이동**. 기존 흐름 문서의 정산 절에 비교표를 두고 클래스에는 입력 예약 불변·계획 반환·저장 책임 제외를 남김 | 함수 옆 실제 수수료 버림/잔여 예약 올림/전량 체결 0과 Plan의 예약·소비·반환 구분·수수료 재차감 금지는 유지. [분할·올림 경계 테스트](https://github.com/0Chord/coin-exchange/blob/9be693631c158eac630f3017f5b0a05c813bd067/domain-order/src/test/kotlin/com/exchange/core/order/OrderFillSettlementCalculatorTest.kt#L521) |
| [SubmitOrderCommand](https://github.com/0Chord/coin-exchange/blob/9be693631c158eac630f3017f5b0a05c813bd067/domain-matching/src/main/kotlin/com/exchange/core/matching/MatchingCommand.kt#L21): TimeInForce에 따라 잔량을 넣거나 취소한다고 설명 | **수정**. 현재 엔진은 LIMIT/GTC만 지원하고 미체결 잔량은 주문장에 둔다고 명시 | 명령 생성자의 수량 검증과 엔진의 지원 조건 검증을 구분. [IOC·MARKET 거절 테스트](https://github.com/0Chord/coin-exchange/blob/9be693631c158eac630f3017f5b0a05c813bd067/domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingEngineTest.kt#L698)는 이벤트·ID·sequence 소비 전 거절 확인 |

## API·저장 대표 사례

| 대상·현재 설명 | 처리와 제안 | 보존 계약·근거 |
| --- | --- | --- |
| [OrderController](https://github.com/0Chord/coin-exchange/blob/9be693631c158eac630f3017f5b0a05c813bd067/app-api/src/main/kotlin/com/exchange/core/api/order/api/OrderController.kt#L20): URL과 예약·정산 전체 흐름을 반복 | **축약**. 요청→명령→UseCase→같은 순서의 응답 DTO라는 책임만 남김 | 취소의 주문 부재·소유자 불일치는 정상 거절 이벤트라는 함수 설명 유지. [HTTP 테스트](https://github.com/0Chord/coin-exchange/blob/9be693631c158eac630f3017f5b0a05c813bd067/app-api/src/test/kotlin/com/exchange/core/api/order/api/OrderControllerTest.kt#L211)와 입력 오류의 무변경 사례 |
| [SubmitOrderUseCase.submit](https://github.com/0Chord/coin-exchange/blob/9be693631c158eac630f3017f5b0a05c813bd067/app-api/src/main/kotlin/com/exchange/core/api/order/application/SubmitOrderUseCase.kt#L35): 발행·저장이 끝나야 정상 반환 | **수정**. “설정된 publisher 호출과 체결별 정산 완료”로 쓰고 NoOp는 저장 보장이 없음을 명시 | 예약 실패면 엔진 미실행, 뒤쪽 실패/timeout이 앞선 커밋을 되돌리지 않음. [설정 테스트](https://github.com/0Chord/coin-exchange/blob/9be693631c158eac630f3017f5b0a05c813bd067/app-api/src/test/kotlin/com/exchange/core/api/config/MatchingPublisherConfigurationTest.kt#L50), [조율자 실패 테스트](https://github.com/0Chord/coin-exchange/blob/9be693631c158eac630f3017f5b0a05c813bd067/app-api/src/test/kotlin/com/exchange/core/api/matching/application/MatchingCoordinatorContractTest.kt#L45) |
| [MatchingConfig.matchingEventPublisher](https://github.com/0Chord/coin-exchange/blob/9be693631c158eac630f3017f5b0a05c813bd067/app-api/src/main/kotlin/com/exchange/core/api/config/MatchingConfig.kt#L30): 영속화가 꺼지면 NoOp라는 설명 | **수정·축약**. 정확히 `false` 또는 미지정이면 NoOp, `true`이면 영속 publisher 조립을 명시 | `matchIfMissing=true`의 기본값. [true/false/미지정 Bean 테스트](https://github.com/0Chord/coin-exchange/blob/9be693631c158eac630f3017f5b0a05c813bd067/app-api/src/test/kotlin/com/exchange/core/api/config/MatchingPublisherConfigurationTest.kt#L36). 별도 ledger 설정과 혼동하지 않음 |
| [NoOpMatchingEventPublisher](https://github.com/0Chord/coin-exchange/blob/9be693631c158eac630f3017f5b0a05c813bd067/app-api/src/main/kotlin/com/exchange/core/api/matching/infrastructure/publish/NoOpMatchingEventPublisher.kt#L6): 저장 장애 fallback이 아님 | **유지**. 전달·후속 실행의 주체는 Coordinator이며 NoOp 자체가 작업을 전달한다고 확대하지 않음 | 비활성 설정의 정상 무저장과 활성 저장 실패를 구분. 실제 DB 실패는 [저장 실패 테스트](https://github.com/0Chord/coin-exchange/blob/9be693631c158eac630f3017f5b0a05c813bd067/app-api/src/test/kotlin/com/exchange/core/api/matching/application/MatchingCoordinatorPersistenceFailureTest.kt#L41) |
| [TradeSettlementService](https://github.com/0Chord/coin-exchange/blob/9be693631c158eac630f3017f5b0a05c813bd067/app-api/src/main/kotlin/com/exchange/core/api/order/application/TradeSettlementService.kt#L24): 실행 순서와 회계 배경이 여러 블록에 있음 | **문서 이동**. 긴 순서·사용자 잔고를 부채로 보는 배경은 정산 절, 코드에는 한 체결의 DB 원자성과 분개 함수의 비변경 책임 | taker 방향, 정산 처리 시각, 수수료 재차감 금지·0원 분개 제외·양쪽 합친 자산별 균형 유지. [롤백 테스트](https://github.com/0Chord/coin-exchange/blob/9be693631c158eac630f3017f5b0a05c813bd067/app-api/src/test/kotlin/com/exchange/core/api/order/application/TradeSettlementServiceTest.kt#L602) |
| [PostgresBalanceStore](https://github.com/0Chord/coin-exchange/blob/9be693631c158eac630f3017f5b0a05c813bd067/app-api/src/main/kotlin/com/exchange/core/api/ledger/infrastructure/persistence/PostgresBalanceStore.kt#L15): 단일 갱신 이유와 조회 설명 반복 | **축약**. “잔고를 먼저 읽고 쓰면 경쟁할 수 있어 조건·변경을 UPDATE RETURNING 한 문장에 묶는다. 미갱신 시 현재 잔고를 조회해 원인을 구분한다” | 실패 뒤 조회는 그때의 snapshot이며 실패 순간의 값을 고정한 보장이 아님. [경쟁 테스트](https://github.com/0Chord/coin-exchange/blob/9be693631c158eac630f3017f5b0a05c813bd067/app-api/src/test/kotlin/com/exchange/core/api/ledger/infrastructure/persistence/PostgresBalanceStoreTest.kt#L271)·부족/부재/overflow 사례 |
| [PostgresOrderReservationStore.findForUpdate](https://github.com/0Chord/coin-exchange/blob/9be693631c158eac630f3017f5b0a05c813bd067/app-api/src/main/kotlin/com/exchange/core/api/order/infrastructure/persistence/PostgresOrderReservationStore.kt#L142): 객체가 lock을 보유하는 것은 아님 | **유지**. 호출자 트랜잭션 안에서 판단·update까지 해야 한다는 이유를 남김 | 반환값 비교만으로 잠금 수명을 검증했다고 쓰지 않음. [동일 예약 동시 해제 테스트](https://github.com/0Chord/coin-exchange/blob/9be693631c158eac630f3017f5b0a05c813bd067/app-api/src/test/kotlin/com/exchange/core/api/order/application/OrderReservationReleaseServiceTest.kt#L246)는 반환 1회 확인 |

기존 DB migration은 편집하지 않는다. 특히 [V6 수수료 나머지 주석](../app-api/src/main/resources/db/migration/V6__add_order_reservation_fee_remainder.sql)은 과거 값 복원이 아닌 초기 0, 범위 `[0,1,000,000)`, 이후 INSERT 요구를 설명하므로 유지한다.

## 실행기·상태 소유 대표 사례

| 대상·현재 설명 | 처리와 제안 | 보존 계약·근거 |
| --- | --- | --- |
| [MarketCommandProcessor](https://github.com/0Chord/coin-exchange/blob/9be693631c158eac630f3017f5b0a05c813bd067/domain-matching/src/main/kotlin/com/exchange/core/matching/MarketCommandProcessor.kt#L11): Kafka/channel/큐 대안과 개선 목록 | **문서 이동·축약**. 클래스는 마켓별 직렬 실행·Future 완료 계약, 긴 대안은 현재 한계 절로 이동 | [순서 테스트](https://github.com/0Chord/coin-exchange/blob/9be693631c158eac630f3017f5b0a05c813bd067/domain-matching/src/test/kotlin/com/exchange/core/matching/MarketCommandProcessorTest.kt#L50). `close`는 신규 거절·종료 시작이며 완료를 기다리지 않음([591행](https://github.com/0Chord/coin-exchange/blob/9be693631c158eac630f3017f5b0a05c813bd067/domain-matching/src/test/kotlin/com/exchange/core/matching/MarketCommandProcessorTest.kt#L591)) |
| [MarketWorker.submit](https://github.com/0Chord/coin-exchange/blob/9be693631c158eac630f3017f5b0a05c813bd067/domain-matching/src/main/kotlin/com/exchange/core/matching/MarketCommandProcessor.kt#L158): 여러 실패 분기 설명 | **유지 중심 축약**. 아래 실패 표와 같은 조건을 남김 | 마켓 중단 판단은 부작용 관측이 아닌 `beforeMatching != null || matchingCompleted`. [before 없는 엔진 거절](https://github.com/0Chord/coin-exchange/blob/9be693631c158eac630f3017f5b0a05c813bd067/domain-matching/src/test/kotlin/com/exchange/core/matching/MarketCommandProcessorTest.kt#L403), [before 성공 뒤 엔진 실패](https://github.com/0Chord/coin-exchange/blob/9be693631c158eac630f3017f5b0a05c813bd067/domain-matching/src/test/kotlin/com/exchange/core/matching/MarketCommandProcessorTest.kt#L453) |
| [MatchingCoordinator.process](https://github.com/0Chord/coin-exchange/blob/9be693631c158eac630f3017f5b0a05c813bd067/app-api/src/main/kotlin/com/exchange/core/api/matching/application/MatchingCoordinator.kt#L25): 3초·원인 전달·interrupt와 인라인 순서 반복 | **핵심 유지·중복 축약**. “HTTP thread” 대신 “호출자 스레드” 사용 가능 | publisher 성공 뒤 후속 작업, 3초는 취소/롤백 아님, interrupt flag 복구. [실제 timeout 이후 계속 실행](https://github.com/0Chord/coin-exchange/blob/9be693631c158eac630f3017f5b0a05c813bd067/app-api/src/test/kotlin/com/exchange/core/api/matching/application/MatchingCoordinatorContractTest.kt#L172) |
| [OrderBook](https://github.com/0Chord/coin-exchange/blob/9be693631c158eac630f3017f5b0a05c813bd067/domain-matching/src/main/kotlin/com/exchange/core/matching/OrderBook.kt#L8): 긴 가격별 자료구조 그림, “cancel command에는 orderId만” | **그림 문서 이동·사실 수정**. 인덱스는 orderId로 side/price를 찾는 용도라고 설명 | CancelOrderCommand에는 marketId/orderId/userId가 있음. 가격 우선·같은 가격 FIFO는 유지. [가격 우선 테스트](https://github.com/0Chord/coin-exchange/blob/9be693631c158eac630f3017f5b0a05c813bd067/domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingEngineTest.kt#L69) |
| [PriceLevel.firstOrder/get](https://github.com/0Chord/coin-exchange/blob/9be693631c158eac630f3017f5b0a05c813bd067/domain-matching/src/main/kotlin/com/exchange/core/matching/PriceLevel.kt#L59), [OrderBook.find](https://github.com/0Chord/coin-exchange/blob/9be693631c158eac630f3017f5b0a05c813bd067/domain-matching/src/main/kotlin/com/exchange/core/matching/OrderBook.kt#L139) | **공유 참조 유지·반복 축약**. 같은 내부 주문 참조이며 없으면 null이라는 경계는 각 조회의 짧은 계약으로 남김 | 값 사본이나 불변 snapshot이라고 쓰지 않음. [상태 소유 테스트](https://github.com/0Chord/coin-exchange/blob/9be693631c158eac630f3017f5b0a05c813bd067/domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingStateOwnershipTest.kt#L53)의 assertSame·잔량 6 확인 |
| [BookOrder.fill](https://github.com/0Chord/coin-exchange/blob/9be693631c158eac630f3017f5b0a05c813bd067/domain-matching/src/main/kotlin/com/exchange/core/matching/BookOrder.kt#L49): 대입식·속성 풀이 반복 | **축약**. 유효 체결량과 동일 객체 변경, 원수량 유지 및 필요하면 10→6 예시만 남김 | [상태 테스트](https://github.com/0Chord/coin-exchange/blob/9be693631c158eac630f3017f5b0a05c813bd067/domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingStateOwnershipTest.kt#L23)의 정상·거절 후 보존. internal은 Kotlin 모듈 접근 경계이며 Java/reflection 전체 차단으로 확대하지 않음 |

`PriceLevel.snapshot()`은 #23에서 제거됐다. 과거 얕은 복사가 왜 위험했는지는 [#23 기록](state-access-boundaries-review.md)에 유지하되, 현재 실행 경로는 `firstOrder/get/find`의 공유 참조라고 쓴다. 역사적 이유와 현재 API를 섞지 않는다.

## 문서 이동의 목적지와 현재 설명 수정

긴 설명의 목적지는 새 설명 문서 묶음이 아닌 **[기존 개발·주문 흐름 문서](flow-and-scope-contract.md)**다. 코드가 바뀔 때 두 개의 전체 흐름을 함께 고치게 만들지 않는다.

| 위치 | 이번 구현에서 할 일 |
| --- | --- |
| ‘실제 코드의 역할과 읽는 순서’ | 35행의 현재 Service/목표 UseCase 문장을 현재 SubmitOrderUseCase·CancelOrderUseCase·MatchingCoordinator로 수정 |
| ‘제출 → 체결 → 정산’ | BUY/SELL의 예약 감소·소비·반환·지급 비교와 수수료 나머지/반올림 이유, 회계 배경을 접을 수 있는 하위 설명으로 이동. 상세 계약은 함수와 연결 |
| ‘거절·부분 실패·시간 초과’ | 실행기 실패 조건·close 비대기·복구 부재를 현재 소스와 일치시키고 긴 실행 대안은 현재 한계로 분리 |
| ‘저장과 상태 판단의 경계’ | matching 영속화 설정→Bean→publisher→후속 작업과 내부 가변 참조/불변 도메인의 차이 연결 |
| ‘기존 테스트에서 찾을 대표 사례’ | 121행의 HTTP 부분 체결 후 취소·timeout 이후 결과·후반 정산 실패를 일괄 미검증 후보로 두지 않음. #22·#23의 실제 테스트 위치와 해당 수준의 한계를 연결 |
| README·공통 컨벤션 | 현재 흐름으로 진입하는 링크와 이번 분류 원칙 연결. 과거 기준 커밋은 당시 기록으로 유지하고 현재 기준과 구별 |

#25 초기 명세의 P01~P08와 현재 P01~P09 차이처럼 **CI 적용표·실행 기록의 최종 정합성은 #25**에 넘긴다. #24에서 새 CI 규칙을 만들거나 과거 실행 수를 현재 결과로 다시 쓰지 않는다.

## 설명에서 반드시 유지할 정상·거절·실패 결과

| 입력·상황 | 설명에 남길 판단과 결과 | 허용하지 않는 설명 |
| --- | --- | --- |
| LIMIT/GTC 주문, 입력 조건 오류 또는 IOC/MARKET | 지원 주문만 엔진 처리. 미지원 조건은 상태 변경 전 거절 | “IOC 잔량을 취소한다”는 현재 미지원 기능 설명 |
| matching 영속화 false/미지정 | NoOp의 정상 반환 뒤 후속 작업 진행. 이벤트 DB 저장은 없음 | “성공 응답이면 항상 이벤트가 저장됨”, “저장 장애면 NoOp로 전환” |
| 사전 콜백 실패 | 현재 요청 실패, 엔진 미실행, 다음 요청은 진행 가능 | 무조건 마켓 전체 중단 또는 이전 모든 상태 롤백 |
| 사전 콜백 정상 반환 후 엔진 실패, 또는 매칭 완료 후 후속 콜백 실패 | 같은 마켓 대기·신규 명령을 거절하고 원인을 전달. 기존 변경은 자동 복구하지 않음 | before의 존재를 실제 금전 부작용 발생 여부로 바꿈 |
| 사전 콜백 없는 엔진 거절 | 해당 명령만 실패. 다음 처리 가능하지만 상태 복구 보장과는 별개 | 엔진 예외는 전부 마켓 중단한다고 일반화 |
| 호출자 대기 3초 초과·interrupt·close | timeout/interrupt가 worker 취소 아님. close는 신규 접수 차단·종료 시작이며 접수 작업 완료 대기 아님 | “시간 초과면 예약 반환”, “close 반환이면 모든 작업 종료” |
| 내부 주문 10개에서 4개 체결 | 같은 내부 객체 잔량 6, 원수량 10. 외부 Kotlin 모듈 접근 제한과 내부 불변성은 다른 개념 | 모든 도메인이 불변, 조회는 항상 값 복사 |
| BUY 3개 중 1개 체결 후 2개 취소 | 남은 예약 202,000만 반환, 구매 현금 909,100/hold 0·BTC 1·기존 원장 보존 | 전체 303,000 반환, 체결·수수료까지 취소 |
| 한 체결 정산 후반 DB 실패 | 그 체결의 예약·잔고·원장만 같은 트랜잭션으로 롤백 | 이미 커밋한 예약·다른 체결·이벤트·메모리 주문장까지 전체 롤백 |

수치 사례의 기대값은 기존 명세와 [HTTP·DB 테스트](https://github.com/0Chord/coin-exchange/blob/9be693631c158eac630f3017f5b0a05c813bd067/app-api/src/test/kotlin/com/exchange/core/api/order/application/OrderLifecycleE2ETest.kt#L259)를 대조한다. 이 문서 작업이 그 사례를 새로 실행한 근거는 아니다.

## 실제 원문과 제안문 비교

아래 원문은 기준 커밋에서 직접 가져온 설계 당시 기록이다. 현재 주석과 다를 수 있다. 코드의 행 번호는 원문 정보로 표시하며 **제안 코드는 1부터 표시**한다. HTML의 긴 원문은 접어 두고 필요한 사례만 펼친다. 문서 이동 제안의 경로는 찾을 위치를 설명한 것으로, 구현 시 실제 링크·절을 대조한다.

<details markdown="1">
<summary>유지: 나머지에 요율을 두 번 곱하지 않는다</summary>

[현재 파일](https://github.com/0Chord/coin-exchange/blob/9be693631c158eac630f3017f5b0a05c813bd067/domain-fee/src/main/kotlin/com/exchange/core/fee/TradingFeeCalculator.kt#L34) · 실제 원문 34~46행 · 파일 SHA256 `5add2c47c54832bc254113a3d45404164a7036191fad038d0f192cf88891b026`.

```kotlin
    /**
     * 같은 주문의 이전 소수 나머지를 합산해 이번 수수료와 다음 나머지를 계산한다.
     *
     * `이번 체결 금액 × 이번 요율의 백만분율 정수 + 이전 나머지 분자`를
     * [FeeRate.DENOMINATOR]로 나눈 몫은 이번 청구액, 나머지는 다음 체결로 넘길 분자다.
     * 이전 나머지는 당시 요율이 이미 반영된 값이므로 이번 요율을 다시 곱하지 않는다.
     * 호출부가 반환된 나머지를 주문에 보관하고 다음 체결 때 전달해야 한다.
     *
     * @param feeBaseAmount 수수료를 계산할 이번 체결 금액으로, 수수료 자산의 최소 단위로 표현한다.
     * @param feeRate 이번 체결의 maker/taker 역할에 적용할 수수료율
     * @param previousRemainder 같은 주문의 이전 계산에서 넘겨받은 소수 나머지. 최초 계산은 [FeeRemainder.ZERO].
     * @return 이번에 청구할 정수 금액과 다음 체결로 넘길 소수 나머지
     */
```

**제안: 그대로 유지.** 이전 나머지를 금액·예약액과 혼동하거나 현재 요율을 다시 곱하는 오류를 막는 이유이므로 길이만으로 줄이지 않는다.

</details>

<details markdown="1">
<summary>축약: 값 객체라는 반복은 줄이고 단위 예시는 남긴다</summary>

[현재 파일](https://github.com/0Chord/coin-exchange/blob/9be693631c158eac630f3017f5b0a05c813bd067/domain-fee/src/main/kotlin/com/exchange/core/fee/FeeRemainder.kt#L3) · 실제 원문 3~12행 · 파일 SHA256 `4d754f76fce85eb58c6728848fe1450a5a55b3c1e2906e0a0b6d92bc46459abd`.

```kotlin
/**
 * 누적 수수료 계산에서 다음 체결로 넘기기 위한 소수 나머지.
 *
 * `numerator / FeeRate.DENOMINATOR`만큼의 최소 금액 단위를 나타낸다.
 * 예를 들어 최소 단위가 1원이면 분자 510,000은 0.51원이다.
 * 예약 잔액이나 이미 청구한 금액과는 다르며, 이 객체 자체는 계산·청구·저장을 수행하지 않는다.
 *
 * @property numerator 0 이상 [FeeRate.DENOMINATOR] 미만인 소수 나머지의 정수 분자
 * @throws IllegalArgumentException 분자가 허용 범위를 벗어난 경우
 */
```

**미적용 제안문 — 제안문 번호 1부터.** 위 분류표의 보존 계약과 함께 검토하며, 생략한 세부 계약은 함수 가까이 또는 이동 문서에 남긴다.

```kotlin
/**
 * 다음 체결로 이월할 수수료 나머지.
 * `numerator / FeeRate.DENOMINATOR` 최소 금액 단위이며 예약액·청구액이 아니다.
 * 최소 단위가 1원이면 분자 510,000은 0.51원이다.
 *
 * @property numerator 0 이상 [FeeRate.DENOMINATOR] 미만인 정수 분자
 * @throws IllegalArgumentException 분자가 허용 범위를 벗어난 경우
 */
```

</details>

<details markdown="1">
<summary>문서 이동: 긴 정산 비교와 함수의 계산 계약을 나눈다</summary>

[현재 파일](https://github.com/0Chord/coin-exchange/blob/9be693631c158eac630f3017f5b0a05c813bd067/domain-order/src/main/kotlin/com/exchange/core/order/OrderFillSettlementCalculator.kt#L52) · 실제 원문 52~87행 · 파일 SHA256 `547f44a790f1ee4c4a7e5a1beae2eb69f66e35e4752c143c3f8d7e7c04a77a9e`.

```kotlin
/**
 * 체결 가격과 체결 수량을 한 주문의 [OrderFillSettlementPlan]으로 변환한다.
 *
 * BUY는 quote 자산 hold에서 실제 체결 대금과 maker/taker 수수료를 소비하고 가격 개선분과
 * 사용하지 않은 수수료 예약액을 반환한 뒤 체결 수량만큼 base 자산을 지급한다. SELL은
 * 체결 수량만큼 base 자산 hold를 소비하고 실제 체결 대금에서 maker/taker 수수료를
 * 차감한 quote 자산을 지급한다.
 *
 * BUY 계산:
 * - 거래 예약 감소액 = 지정가 × 체결 수량
 * - 실제 수수료 = (체결가 대금 × maker/taker 수수료율 + 이전 소수 나머지)의
 *   최소 금액 단위 미만을 버린 금액
 * - 다음 수수료 예약액 = (남은 지정가 대금 × 최대 수수료율 + 새 소수 나머지)를
 *   최소 금액 단위로 올림한 금액. 전량 체결이면 0이다.
 * - 수수료 예약 감소액 = 현재 수수료 예약액 - 다음 수수료 예약액
 * - hold 소비액 = 체결가 대금 + 실제 수수료
 * - hold 반환액 = 전체 예약 감소액 - hold 소비액
 * - 지급 = base 자산 체결 수량
 * - 새 소수 나머지는 주문 예약에 반영해 다음 체결 계산으로 넘긴다
 *
 * SELL 계산:
 * - 예약 감소액 = hold 소비액 = base 자산 체결 수량
 * - hold 반환액 = 0
 * - 총 판매 대금 = quote 자산 기준 체결가 × 체결 수량
 * - 실제 수수료 = (총 판매 대금 × maker/taker 수수료율 + 이전 소수 나머지)의
 *   최소 금액 단위 미만을 버린 금액
 * - 지급 = 총 판매 대금 - 실제 수수료
 * - 새 소수 나머지는 주문 예약에 반영해 다음 체결 계산으로 넘긴다
 *
 * 이 계산기는 순수 도메인 계산만 담당하며 DB 조회, Reservation 저장 또는 Balance 변경을
 * 수행하지 않는다. 실제 저장과 자산 이동은 이후 TradeSettlementService가 담당한다.
 *
 * @property tradingFeeCalculator 체결가 대금과 maker/taker 요율로 실제 수수료를 계산하는 객체
 * @property tradingFeeReserveCalculator 남은 지정가 대금, 최대 요율과 소수 나머지로
 * 유지할 수수료 예약액을 계산하는 객체
 */
```

**미적용 제안문 — 제안문 번호 1부터.** 위 분류표의 보존 계약과 함께 검토하며, 생략한 세부 계약은 함수 가까이 또는 이동 문서에 남긴다.

```kotlin
/**
 * 한 체결의 예약 감소·hold 소비와 반환·자산 지급 계획을 계산한다.
 * 입력 예약을 변경하지 않고 새 계획을 반환하며 저장·잔고 반영은 호출부가 담당한다.
 *
 * BUY/SELL 전체 비교는 저장소 engineering/flow-and-scope-contract.md의
 * ‘제출 → 체결 → 정산’에서 설명한다.
 */
```

</details>

<details markdown="1">
<summary>수정: 명령의 필드와 인덱스의 검색 키를 혼동하지 않는다</summary>

[현재 파일](https://github.com/0Chord/coin-exchange/blob/9be693631c158eac630f3017f5b0a05c813bd067/domain-matching/src/main/kotlin/com/exchange/core/matching/OrderBook.kt#L50) · 실제 원문 50~55행 · 파일 SHA256 `1cc42a7007d0d32d379c2671760e8e0410a5ddd6234987ab556c37ca6b872980`.

```kotlin
    /**
     * 주문 취소용 인덱스.
     *
     * cancel command에는 orderId만 들어오므로,
     * orderId로 주문의 side와 price를 바로 찾기 위해 둔다.
     */
```

**미적용 제안문 — 제안문 번호 1부터.** 위 분류표의 보존 계약과 함께 검토하며, 생략한 세부 계약은 함수 가까이 또는 이동 문서에 남긴다.

```kotlin
/** orderId로 대기 주문의 side와 price를 찾는 취소·조회용 인덱스. */
```

</details>

## 작은 구현 단위

| 순서·관측할 결과 | 정확한 첫 범위 | 완료 확인 |
| --- | --- | --- |
| 1. 현재 지원·설정 설명이 사실과 맞는다 | MatchingCommand·OrderBook의 오래된 문장, SubmitOrderUseCase·MatchingConfig의 publisher/설정 설명, 흐름 문서의 Service 문장 | 미지원 주문·취소 필드·NoOp 결과를 코드/기존 테스트와 대조. 다음 단위의 대규모 주석 이동 없이 검토 가능 |
| 2. 금액 계약을 코드에서 바로 읽는다 | FeeRemainder·QuoteAmountCalculator 반복 축약, OrderFillSettlementCalculator 전체 비교 이동 | ppm·scale·버림/올림·이월·원본 보존·수수료 재차감 금지 유지. TradingFeeCalculator·LedgerTransaction은 유지 판정만 기록 |
| 3. 실행·상태·실패 경계가 흐려지지 않는다 | Processor/Worker·Coordinator·PriceLevel·BookOrder 대표 블록, OrderBook 그림 이동 | before 유무·완료 flag·후속 거절·timeout·close·공유 참조를 위 실패 표와 대조 |
| 4. API·DB 설명과 문서가 연결된다 | OrderController 중복 축약, TradeSettlementService 긴 설명 이동, PostgresBalanceStore 반복 축약, README·컨벤션·흐름 링크 최종 대조 | NoOp·예약 잠금 계약은 유지 판정. 단위별 이동 대상/전후/이유/근거·남은 한계를 같은 명세의 구현 기록 절에 추가 |

문서 이동은 각 단위에서 목적지에 먼저 내용을 반영하고 코드 주석을 줄인다. 단위 4까지 링크가 깨진 중간 상태를 방치하지 않는다. 어느 단위든 실제 코드 변경이 필요해지면 단순 주석 정리와 분리해 보고한다. 모든 파일을 쪼개 PR로 만들 의무는 없으며 각 단위의 diff를 따로 읽을 수 있으면 된다.

## 검증 방법과 완료 기준

이번 첫 검증은 새 테스트 작성이 아니라 **주석의 주장과 코드·기존 테스트 대조**다. 아래 정상·위반·누락 사례는 문서 리뷰의 수용 기준이며 신규 자동 검사기를 만들라는 요구가 아니다.

| 사례 | 기대 판정·근거 |
| --- | --- |
| 수수료 KDoc에 단위·나머지 의미가 있고 긴 BUY/SELL 비교는 유효한 문서 링크로 이동 | 정상: 핵심 계산 이유는 남고 전체 설명은 한 곳에서 찾음 |
| “정산 실패면 모든 주문 작업 롤백” 또는 “NoOp는 장애 fallback”으로 축약 | 실패: 기존 트랜잭션·설정 계약을 바꿔 설명함 |
| 영속화 미지정 경우, before 없는 엔진 거절, 잠금의 트랜잭션 수명이 빠짐 | 누락: 실제 조건별 보장 차이를 숨김. 해당 블록을 보완 |
| 이전 Service 이름·삭제된 snapshot을 현재 API로 설명하거나 이동 링크가 사라짐 | 실패: 현재 소스/심볼 또는 목적지와 불일치 |
| 관련 코드·테스트는 그대로인데 주석 정리를 위해 동작 테스트를 복제 | 제외: 기존 근거 재사용. 테스트 추가는 별도 확인된 빈틈이 있을 때 판단 |

구현 시 확인 순서는 다음과 같다.

1. 기준과 최종 diff를 대조해 운영 변경이 주석/공백에 한정됐는지 확인한다. 어노테이션·문자열·SQL·시그니처·제어 흐름 변경은 이번 범위 실패다. 주석을 제거하는 단순 정규식만으로 Kotlin 문자열과 코드를 구별했다고 주장하지 않는다.
2. 실제 상대 링크의 파일·심볼·문서 절이 있는지 확인한다. KDoc의 코드 심볼 연결은 가능한 기존 `[Symbol]` 형식을 쓰고, 문서 이동 위치는 저장소에서 찾을 수 있게 남긴다. 개인 작업 경로를 제품 주석에 넣지 않는다.
3. 아래 기존 명령으로 문법·컴파일을 확인한다. 오류가 발견된 주석/모듈만 다시 확인한다. 새 하네스·플러그인·문서 생성 패키지는 설치하지 않는다.
4. 기존 PR CI의 전체 build·구조 검사·보고서는 그대로 재사용한다. 로컬 컴파일, 기존 테스트 소스 확인, 과거 테스트 실행, 이번 CI 실행을 각각 구분한다. 문서 의미는 테스트 Green만으로 확인되지 않으므로 전후 사례를 사람이 검토한다.

**후속 구현에서 사용할 예상 명령 — 이번 설계에서는 실행하지 않음:**

```bash
cd /Users/0chord/.codex/worktrees/issue24-comment-spec/exchange-core
git diff --check
./gradlew :domain-fee:compileKotlin :domain-ledger:compileKotlin :domain-order:compileKotlin :domain-matching:compileKotlin :app-api:compileKotlin --no-daemon --console=plain
```

JDK 25가 필요하며 위 작업은 주석 변경의 컴파일 확인이다. DB 동작·모든 링크·의미 정확성을 증명하지 않는다. 동작 변경이 발견되면 해당 기능 테스트와 범위를 다시 정한다. 변경 없는 거래·구조 테스트를 로컬에서 반복 실행하거나 주석만으로 인위적인 Red를 만들지 않는다.

- [x] 대상 블록마다 유지/축약/이동/수정과 이유가 있고, 유지 판정도 실제 변경처럼 부풀리지 않는다.
- [x] 금액 단위·불변/가변 소유·SQL 원자성·트랜잭션·순서·실패 후 상태의 핵심 설명이 남는다.
- [x] 대표 전후안과 실제 변경을 구분하고, 이동 문서·코드·기존 테스트의 링크가 유효하다.
- [x] 운영 코드·설정·스키마·테스트 동작을 바꾸지 않았음을 diff로 확인한다.
- [x] 로컬 검사/CI의 실제 결과·미실행 범위와 설명의 한계를 기록한다. 이번 설계만으로 완료 체크하지 않는다.
- [x] 개발자가 읽을 첫 판단은 ‘publisher 성공과 실제 저장 성공의 차이’로 잡고 질문·답변이 생기면 실제 내용만 기록한다.

## 지금 상태와 다음 행동

설계 단계에서 완료한 것은 소스·기존 테스트 조사, 위 분류와 제안, Ask 답변 반영이었다. 이 절까지의 원문·제안은 설계 당시 기록이다. 실제 반영과 검증은 다음 구현 기록을 따른다. 설계 단계에서는 새 제품 테스트·컴파일·CI를 실행하거나 GitHub 이슈/보드 상태를 바꾸지 않았다.

합의했던 다음 구현의 첫 결과는 **단위 1의 오래된 지원 조건·취소 필드·설정 설명 수정과 그 전후 대조**다. `spec-implementer`에 이 문서를 넘기면 구현·검증 범위를 다시 넓히지 않고 이어갈 수 있다. #25 최종 확인과 #18 완료 처리는 후속이다.


<!-- ISSUE24_IMPLEMENTATION_START -->
## 구현 기록

2026-10-03 · 기준 `9be693631c158eac630f3017f5b0a05c813bd067` → `docs/comment-contracts/24`의 로컬 변경. **네 단위의 로컬 구현·검증 완료.** 제품 동작·SQL·설정·테스트는 수정하지 않았다. PR·CI·병합과 사람의 검토는 아직 완료로 기록하지 않는다. 실제 작업 폴더는 `/Users/0chord/.codex/worktrees/issue24-comment-spec/exchange-core`다.

### 전체 흐름과 읽는 순서

| 단위 | 입력 → 판단 → 결과 | 이번 변경 |
| --- | --- | --- |
| 1. 현재 지원·설정 | LIMIT/GTC·영속화 설정 → 지원 검사·Bean 선택 → 처리 또는 거절·저장 여부 | 미지원 IOC 설명, 취소 필드 혼동, publisher 성공=저장 성공이라는 표현 수정 |
| 2. 금액과 불변식 | 체결 금액·요율·이전 나머지 → 버림/올림·예약 소비 → 새 정산 계획 | 필요한 숫자 예시는 유지하고 긴 BUY/SELL 비교는 흐름 문서에 이동 |
| 3. 실행·상태 | 명령·before → 엔진·publisher·after → 결과 또는 마켓 중단 | timeout·interrupt·close와 rollback의 차이, 같은 내부 객체의 잔량 변경 설명 보존 |
| 4. API·DB | HTTP 변환·정산 계획 → SQL·트랜잭션 → DTO·반환값 또는 해당 TX 롤백 | 역할 중복 축약, 회계 배경 이동, SQL 실패 뒤 조회와 잠금 수명 구분 |

제품의 실행 순서는 바꾸지 않았다. 긴 흐름은 [개발·주문 흐름 문서](flow-and-scope-contract.md)에 먼저 반영한 뒤 코드 주석을 줄였다. 아래 실제 코드 링크와 접힌 발췌는 구현 뒤의 내용을 가리킨다.

### 1. 정상 응답과 실제 저장은 다르다

`exchange.matching.persistence.enabled`가 false 또는 미지정이면 MatchingConfig가 NoOp를 선택한다 → publisher는 저장·전송 없이 정상 반환한다 → Coordinator가 체결 정산 또는 예약 해제를 진행한다. true이면 영속 publisher가 실제 저장한다 → 저장 실패 시 후속 작업은 호출하지 않고 같은 마켓의 다음 요청을 거절한다. NoOp는 장애 fallback이 아니다.

정상 요청이라도 NoOp면 이벤트 저장이 없다. 저장 오류가 나면 먼저 커밋한 예약·메모리 주문장 상태가 남을 수 있다. 3초를 넘겨 대기가 끝나도 작업 취소나 전체 롤백은 일어나지 않는다. [설정·Bean·결과 표](flow-and-scope-contract.md#영속화-설정과-후속-처리)와 함께 읽는다.

수정: MatchingCommand, SubmitOrderUseCase, MatchingConfig, OrderBook의 인덱스 설명. 기존 근거는 [설정 테스트](../app-api/src/test/kotlin/com/exchange/core/api/config/MatchingPublisherConfigurationTest.kt), [실제 저장 실패 테스트](../app-api/src/test/kotlin/com/exchange/core/api/matching/application/MatchingCoordinatorPersistenceFailureTest.kt), [엔진 거절 테스트](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingEngineTest.kt)다. 소스를 대조했으며 이번에 이 테스트들을 재실행한 것은 아니다.

**Ask 문답:** 위 차이를 제시하고 ‘설정→Bean→저장 여부→후속 처리’, ‘실제 저장 실패 뒤 상태’, ‘현재 설명이면 충분’ 중 더 자세히 볼 부분을 물었다. 아직 답변이 없어 사용자 이해나 선호를 확정하지 않았다. 현재는 설정 표와 실패 경계를 둘 다 제공한다.

### 2. 짧게 해도 금액 계약은 남는다

이전 수수료 나머지 510,000은 최소 단위가 1원일 때 0.51원이다 → 이번 금액×ppm에 더한다 → 1,000,000으로 나눈 몫을 청구하고 나머지를 넘긴다. 이미 요율이 반영된 나머지에 요율을 다시 곱하지 않는다. BUY의 남은 수수료 예약은 올림하고 전량 체결이면 0이다. 실제 수수료를 BUY 소비액·SELL 지급액 외에 다시 차감하지 않는다.

수정: FeeRemainder의 값 객체 반복, QuoteAmountCalculator의 중복 인라인, OrderFillSettlementCalculator의 긴 클래스 설명. 유지: TradingFeeCalculator와 LedgerTransaction의 계산·목록 격리 이유, 함수 가까이의 반올림·정확한 나눗셈·overflow 계약. [BUY/SELL 비교](flow-and-scope-contract.md#buy와-sell-정산-계산)와 [기존 계산 테스트](../domain-order/src/test/kotlin/com/exchange/core/order/OrderFillSettlementCalculatorTest.kt)를 연결한다.

### 3. 내부에서 바꾼다는 것과 외부에 열었다는 것은 다르다

외부 명령 → 마켓 worker → 엔진 → 내부 BookOrder.fill 순서다. 내부 조회는 같은 가변 객체를 반환한다. 잔량 10에서 4를 체결하면 같은 객체의 잔량은 6이고 원수량은 10이다. internal은 Kotlin 모듈 접근 경계이며 모든 객체 불변이나 Java/reflection 차단을 뜻하지 않는다.

before 실패면 엔진을 실행하지 않고 그 명령만 실패한다. before 정상 반환 뒤 엔진 실패 또는 매칭 뒤 eventHandler 실패면 같은 마켓의 대기·신규 명령을 거절한다. before 없는 엔진 실패는 해당 명령만 실패시키며, 이 차이를 전체 상태 복구 보장으로 해석하지 않는다. close는 접수를 막고 종료를 시작하지만 기존 작업 완료를 기다리지 않는다.

수정: Processor/Worker·Coordinator·PriceLevel·BookOrder와 OrderBook 그림/인덱스. [실패 표·종료 경계](flow-and-scope-contract.md#거절부분-실패시간-초과), [동일 참조 테스트](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingStateOwnershipTest.kt), [worker 테스트](../domain-matching/src/test/kotlin/com/exchange/core/matching/MarketCommandProcessorTest.kt), [timeout·interrupt 테스트](../app-api/src/test/kotlin/com/exchange/core/api/matching/application/MatchingCoordinatorContractTest.kt)를 연결한다.

### 4. 한 체결의 DB 계약을 전체 요청으로 확대하지 않는다

정산은 잠금 조회 → 두 계획 → 양쪽 분개의 자산별 균형 → 원장 저장 → maker/taker 예약·잔고 반영이다. 뒤쪽 지급 실패는 이 체결의 DB 작업을 롤백한다. 앞선 체결·이벤트·메모리 주문장까지 복원하지 않는다. PostgresBalanceStore의 조건부 UPDATE RETURNING은 read-then-write 경쟁을 피하며, 미갱신 뒤 원인 조회는 조회 시점 값이다. 예약 잠금은 객체가 아닌 트랜잭션에 속한다.

수정: OrderController, TradeSettlementService, PostgresBalanceStore. 유지: NoOpMatchingEventPublisher, PostgresOrderReservationStore의 잠금 계약과 V6 migration. [분개·정산 설명](flow-and-scope-contract.md#정산-순서와-원장-분개), [잔고 경쟁 테스트](../app-api/src/test/kotlin/com/exchange/core/api/ledger/infrastructure/persistence/PostgresBalanceStoreTest.kt), [정산 롤백 테스트](../app-api/src/test/kotlin/com/exchange/core/api/order/application/TradeSettlementServiceTest.kt)를 연결한다.

기존 문서의 미검증 후보도 수준을 구분했다. HTTP 부분 체결 후 취소는 실제 E2E가 있고, timeout은 조율자+메모리 worker, 앞선 정산 성공 뒤 다음 정산 롤백은 DB 서비스 테스트가 있다. **한 HTTP 명령의 여러 체결 중 후반 실패를 끝까지 실행한 E2E가 있다는 뜻은 아니다.** 새 테스트 범위를 추가하지 않았다.

### 바뀐 파일과 그대로 둔 근거

| 구분 | 파일 | 읽기 단위 |
| --- | --- | --- |
| 제품 파일의 주석만 수정 | [MatchingConfig](../app-api/src/main/kotlin/com/exchange/core/api/config/MatchingConfig.kt) | 1 |
| 제품 파일의 주석만 수정 | [PostgresBalanceStore](../app-api/src/main/kotlin/com/exchange/core/api/ledger/infrastructure/persistence/PostgresBalanceStore.kt) | 4 |
| 제품 파일의 주석만 수정 | [MatchingCoordinator](../app-api/src/main/kotlin/com/exchange/core/api/matching/application/MatchingCoordinator.kt) | 3 |
| 제품 파일의 주석만 수정 | [OrderController](../app-api/src/main/kotlin/com/exchange/core/api/order/api/OrderController.kt) | 4 |
| 제품 파일의 주석만 수정 | [SubmitOrderUseCase](../app-api/src/main/kotlin/com/exchange/core/api/order/application/SubmitOrderUseCase.kt) | 1 |
| 제품 파일의 주석만 수정 | [TradeSettlementService](../app-api/src/main/kotlin/com/exchange/core/api/order/application/TradeSettlementService.kt) | 4 |
| 제품 파일의 주석만 수정 | [FeeRemainder](../domain-fee/src/main/kotlin/com/exchange/core/fee/FeeRemainder.kt) | 2 |
| 제품 파일의 주석만 수정 | [BookOrder](../domain-matching/src/main/kotlin/com/exchange/core/matching/BookOrder.kt) | 3 |
| 제품 파일의 주석만 수정 | [MarketCommandProcessor](../domain-matching/src/main/kotlin/com/exchange/core/matching/MarketCommandProcessor.kt) | 3 |
| 제품 파일의 주석만 수정 | [MatchingCommand](../domain-matching/src/main/kotlin/com/exchange/core/matching/MatchingCommand.kt) | 1·3 |
| 제품 파일의 주석만 수정 | [OrderBook](../domain-matching/src/main/kotlin/com/exchange/core/matching/OrderBook.kt) | 1·3 |
| 제품 파일의 주석만 수정 | [PriceLevel](../domain-matching/src/main/kotlin/com/exchange/core/matching/PriceLevel.kt) | 3 |
| 제품 파일의 주석만 수정 | [OrderFillSettlementCalculator](../domain-order/src/main/kotlin/com/exchange/core/order/OrderFillSettlementCalculator.kt) | 2 |
| 제품 파일의 주석만 수정 | [QuoteAmountCalculator](../domain-order/src/main/kotlin/com/exchange/core/order/QuoteAmountCalculator.kt) | 2 |
| 문서 | [흐름](flow-and-scope-contract.md)·[공통 컨벤션](conventions-design.md)·[README](../README.md)·이 명세 | 전체 설명·기록 |

테스트·검사 도구·빌드 설정 변경은 없다. 전체 클래스에 새 주석을 붙이거나 유지한 파일을 수정 성과로 세지 않는다.

### 실제 변경 후 코드 펼치기

발췌는 현재 파일에서 추출했다. 전체 파일 링크와 SHA256은 생성 시점의 내용이며 자동 갱신되는 실행 결과가 아니다. 위 설계 원문의 제안과 구분한다.

<details markdown="1">
<summary>SubmitOrderUseCase의 실제 변경 후 계약</summary>

[전체 파일](../app-api/src/main/kotlin/com/exchange/core/api/order/application/SubmitOrderUseCase.kt) · 실제 반영 35~46행 · SHA256 `3943daebafb7745561819e15f42f9249688927246f03718acbb80056336e75ec`.

```kotlin
    /**
     * 지원 여부를 검사하고 자금 예약이 성공한 주문만 매칭한 뒤 발생한 체결들을 정산한다.
     *
     * 예약 실패 시 엔진은 실행하지 않는다. 설정된 publisher 호출과 체결별 정산이 끝나야
     * 정상 결과를 반환하며, NoOp 설정은 이벤트 저장을 보장하지 않는다. 예약 후 실패하거나
     * 응답 대기 시간이 초과되어도 자금을 임의 반환하지 않으며, 엔진 처리 여부를 확인한 뒤
     * 복구하는 기능은 별도 구현이 필요하다.
     *
     * @param command 주문 마켓, 소유자, 방향, 지정가와 수량을 담은 새 주문 명령
     * @return 설정된 publisher 호출과 체결별 정산까지 끝난 매칭 이벤트 목록
     * @throws IllegalArgumentException 구성된 마켓과 다르거나 LIMIT/GTC 주문이 아닌 경우
     */
```

</details>

<details markdown="1">
<summary>FeeRemainder의 실제 변경 후 계약</summary>

[전체 파일](../domain-fee/src/main/kotlin/com/exchange/core/fee/FeeRemainder.kt) · 실제 반영 3~10행 · SHA256 `d4ed4826593c0d3a97fa47f1768e265c6258bc80c94fdd6f94fcce6141385e38`.

```kotlin
/**
 * 다음 체결로 이월할 수수료 나머지.
 * `numerator / FeeRate.DENOMINATOR` 최소 금액 단위이며 예약액·청구액이 아니다.
 * 최소 단위가 1원이면 분자 510,000은 0.51원이다.
 *
 * @property numerator 0 이상 [FeeRate.DENOMINATOR] 미만인 정수 분자
 * @throws IllegalArgumentException 분자가 허용 범위를 벗어난 경우
 */
```

</details>

<details markdown="1">
<summary>MarketCommandProcessor의 실제 변경 후 계약</summary>

[전체 파일](../domain-matching/src/main/kotlin/com/exchange/core/matching/MarketCommandProcessor.kt) · 실제 반영 143~158행 · SHA256 `58d26c9e64f8258c00901976608086fd97736cccd4f154f873ad5aec8129ad91`.

```kotlin
    /**
     * command를 이 마켓의 단일 thread queue에 넣는다.
     *
     * [beforeMatching] → 엔진 → [eventHandler] 순서로 실행하고 모두 성공해야 완료된다.
     * 사전 작업 실패는 이 명령만 실패시킨다. 사전 작업 정상 반환 뒤 엔진이 실패하거나
     * eventHandler가 실패하면 같은 마켓의 대기·신규 명령을 최초 원인과 함께 거절한다.
     * 이때 중단 조건은 실제 부작용이 아닌 `beforeMatching != null || matchingCompleted`다.
     * 사전 작업 없는 엔진 실패는 이 명령만 실패시키며 상태 복구를 보장하지 않는다.
     * 마켓 중단도 이미 반영한 예약·엔진 상태·저장 결과를 되돌리지 않는다.
     *
     * @param command 이 worker의 [marketId]와 일치해야 하는 입력
     * @param beforeMatching 엔진 실행 전에 완료해야 할 함수. null이면 바로 엔진을 실행한다.
     * @param eventHandler 엔진 상태 변경 직후 실행할 event 저장 또는 발행 함수
     * @return 처리 결과 또는 실패 원인을 전달하는 future
     * @throws IllegalArgumentException command의 market이 worker market과 다른 경우
     */
```

</details>

<details markdown="1">
<summary>TradeSettlementService의 실제 변경 후 계약</summary>

[전체 파일](../app-api/src/main/kotlin/com/exchange/core/api/order/application/TradeSettlementService.kt) · 실제 반영 24~32행 · SHA256 `f2d75dfdfa470a60a27a78dff9ee327f46c460e4bff510a54a86d412af55305e`.

```kotlin
/**
 * 확정된 한 체결을 maker·taker 예약, 잔고와 원장에 반영한다.
 *
 * Spring Bean을 통해 호출하면 이 변경들은 한 체결의 트랜잭션에 참여하며,
 * 뒤쪽 잔고 지급 실패 시 먼저 저장한 원장과 양쪽 주문의 변경도 함께 롤백된다.
 *
 * 실행 순서와 회계 배경은 저장소 engineering/flow-and-scope-contract.md의
 * ‘정산 순서와 원장 분개’에서 설명한다.
 */
```

</details>

### 실행 검증과 독립 리뷰

- 컴파일: 명세의 다섯 compileKotlin 작업을 실행해 **BUILD SUCCESSFUL, 20초, 6개 작업 실제 실행**(domain-common 포함)을 확인했다. JDK 25.0.2, 종료 코드 0. 테스트 실행 결과가 아니다.
- 변경 대조: 기존 Kotlin 2.3.21 컴파일러의 KotlinLexer로 기준 HEAD와 변경 파일을 토큰화해 주석·공백을 제외한 토큰 종류·내용을 비교했다. **14개 Kotlin 파일 전부 동일**. 임시 확인은 `/tmp/Issue24TokenCheck.java`에서 수행했고 저장소에 새 검사 도구를 추가하지 않았다.
- 링크·형식: 명세·흐름·컨벤션의 상대 링크 118개 파일 존재와 `git diff --check` 통과. 현재 발췌 4개의 해시·줄 범위·내용과 문서 절 링크 6개도 확인했다.
- 실제 명령: `cd /Users/0chord/.codex/worktrees/issue24-comment-spec/exchange-core` 후 `./gradlew :domain-fee:compileKotlin :domain-ledger:compileKotlin :domain-order:compileKotlin :domain-matching:compileKotlin :app-api:compileKotlin --no-daemon --console=plain`. 로컬 로그 `/tmp/issue24-compile.log`, 토큰 대조 로그 `/tmp/issue24-token-check.log`.
- 거래 테스트·Docker DB·새 원격 CI는 실행하지 않았다. 과거 테스트 소스와 이번 실행 결과를 구분한다.
- 독립 리뷰: 작성 대화를 상속하지 않은 `issue24_independent_review` 에이전트가 별도 base/head 스냅샷에서 전체 18개 변경 파일과 관련 코드·기존 테스트를 확인했다. 범위 안의 확인된 미해결 결함은 없었다. 리뷰어도 Kotlin lexer 대조·JDK 25 컴파일(6개 작업)을 직접 수행했고 통과했다. 작성자의 구현 기록·이전 리뷰·실행 주장은 판정 근거로 읽지 않았다.
- 리뷰 대상: `/var/folders/7f/l04jcvpd6755klk1rb37j0d40000gn/T/issue24-review-oojkhfg5`, base `9be693631c158eac630f3017f5b0a05c813bd067`, manifest SHA256 `387b01e7ec95de68dfd597e9a2c37e7c74e579ce7c58d5736c2d05392e07462f`. 리뷰어는 대상 273개 파일 해시를 검사 전후 대조했다. 이 판단은 검토한 범위에서의 결과이며 무결함 보장은 아니다.
- 리뷰 후 제품 코드·명세 계약·흐름 설명은 변경하지 않았다. 이 문서의 완료 상태·실행 경로·검증 결과만 갱신했고 로컬 HTML은 같은 소스로 다시 생성했다.
- 이 기록은 PR 게시 전의 로컬 검증 결과다. 이후 게시·원격 CI는 PR에서 확인하며, #25의 최종 적용표·CI 확인은 후속이다.

로컬 수용 기준은 충족했다. 다음은 이 변경의 PR·CI 확인이며, #24 이슈 종료와 #25 최종 대조는 해당 근거를 확인한 뒤 진행한다.
