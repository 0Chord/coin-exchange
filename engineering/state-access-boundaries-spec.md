# #23 주문장 상태와 실행 경계 상세 명세

**상태: 합의한 범위 로컬 구현·검증 완료.** [구현 읽기](state-access-boundaries-review.md)와 [실행 기록](state-access-boundaries-verification.json)에 현재 코드·실행 결과를 따로 연결한다. 이 문서의 수용 사례와 실제 실행 결과를 구분한다.

[이슈 #23](https://github.com/0Chord/coin-exchange/issues/23)의 범위를 유지한다. 기준은 [PR #36](https://github.com/0Chord/coin-exchange/pull/36)이 병합된 `feature/phase-2/integration`의 `59dfe6c239f7f9c1bd4990fe476ef2c79e2cee00`이다. 실제 구현 폴더는 `/Users/0chord/.codex/worktrees/issue23-state-access/exchange-core`, 브랜치는 `refactor/state-access-boundaries/23`이다. 과거 테스트 통과 수를 이번 조사나 앞으로의 변경 결과로 쓰지 않는다.

## 먼저 볼 결과

**가변 주문장은 유지하되 변경 권한을 좁히고, 콜백·실패·대기 종료 뒤에 실제로 무엇이 남는지 테스트로 확인한다.** 전체 불변화나 새로운 실행 프레임워크를 만들지 않는다.

| 작은 구현 단위 | 결과 | 현재 상태 |
| --- | --- | --- |
| 1. 주문장 변경 권한 | 내부 주문을 직접 고치거나 운영 코드에서 실행기를 우회하는 경로 제한 | 구현 및 접근·상태 테스트 통과 |
| 2. 콜백과 실패 후 요청 | 사전 작업 실패·엔진 실패·발행 실패·후속 작업 실패를 구분 | 정책 유지, 신규 회귀 통과 |
| 3. 시간 초과와 종료 | 대기는 끝나도 작업은 계속될 수 있음. 종료는 새 접수를 막음 | 실제 3초·interrupt·close 테스트 통과 |

사용자의 구현 요청에 따라 테스트 작성 → 기대값 검토 → 최소 구현 → 회귀 검증을 연결했다. 단계 이동을 다시 승인받지 않는다. 접근성과 새 검사에는 Red를 확인했고, 기존 콜백·시간 초과 정책은 인위적으로 실패를 만들지 않고 회귀 사례를 보강했다.

## 1. 현재 확인한 상태와 공유 참조

### 운영 진입점과 엔진 소유권

운영 호출은 UseCase → `MatchingCoordinator` → `MarketCommandProcessor.submit` → 마켓 전용 worker → `MatchingEngine.process`다. 마켓별 worker·engine과 엔진의 주문장 map은 private이다. HTTP나 application에 엔진 내부 주문을 반환하는 경로는 찾지 못했다.

다음 조사 근거는 변경 전 기준 커밋 `59dfe6c239f7f9c1bd4990fe476ef2c79e2cee00`에 고정한다. 현재 구현과 신규 테스트는 [구현 읽기](state-access-boundaries-review.md)의 상대경로 링크에서 확인한다.

- [MatchingConfig](https://github.com/0Chord/coin-exchange/blob/59dfe6c239f7f9c1bd4990fe476ef2c79e2cee00/app-api/src/main/kotlin/com/exchange/core/api/config/MatchingConfig.kt#L28)는 processor를 Bean으로 생성한다.
- [MatchingCoordinator](https://github.com/0Chord/coin-exchange/blob/59dfe6c239f7f9c1bd4990fe476ef2c79e2cee00/app-api/src/main/kotlin/com/exchange/core/api/matching/application/MatchingCoordinator.kt#L40)는 명령과 콜백을 processor 계약에 넘긴다.
- [MatchingEngine](https://github.com/0Chord/coin-exchange/blob/59dfe6c239f7f9c1bd4990fe476ef2c79e2cee00/domain-matching/src/main/kotlin/com/exchange/core/matching/MatchingEngine.kt#L28)은 내부 주문장과 가변 주문을 소유한다. 반환하는 [MatchingEvent](https://github.com/0Chord/coin-exchange/blob/59dfe6c239f7f9c1bd4990fe476ef2c79e2cee00/domain-matching/src/main/kotlin/com/exchange/core/matching/MatchingEvent.kt#L42)는 식별자·가격·수량 등의 값이며 `BookOrder` 참조를 담지 않는다.
- [JMH 엔진 replay](https://github.com/0Chord/coin-exchange/blob/59dfe6c239f7f9c1bd4990fe476ef2c79e2cee00/benchmark-jmh/src/jmh/kotlin/com/exchange/core/benchmark/MatchingEngineReplayBenchmark.kt#L36)는 자기 엔진을 만들어 직접 실행한다. 이 사용과 코어 단위 테스트는 유지한다.

### 변경 전 기준 커밋에서 공개 API로 가능했던 우회

다음은 **저수준 타입을 직접 사용할 때 가능한 문제**다. 운영 엔진의 참조가 이미 외부에 유출됐다는 뜻은 아니다. 아래 표는 변경 전 기준 커밋을 조사한 기록이며, 구현에서는 internal·private setter·snapshot 제거로 접근 경계를 제한했다. 세 타입의 실제 소비자는 매칭 엔진과 서로의 구현뿐이며, 테스트·JMH의 직접 사용도 찾지 못했다.

| 변경 전 공개 경로 | 실제 의미 | 놓치기 쉬운 문제 |
| --- | --- | --- |
| [BookOrder.remainingQuantity](https://github.com/0Chord/coin-exchange/blob/59dfe6c239f7f9c1bd4990fe476ef2c79e2cee00/domain-matching/src/main/kotlin/com/exchange/core/matching/BookOrder.kt#L36) 공개 setter | 생성 뒤 잔량을 직접 대입할 수 있음 | 원수량을 넘는 값으로 대입해 생성자·fill 검증을 우회 |
| [PriceLevel.add](https://github.com/0Chord/coin-exchange/blob/59dfe6c239f7f9c1bd4990fe476ef2c79e2cee00/domain-matching/src/main/kotlin/com/exchange/core/matching/PriceLevel.kt#L41), OrderBook.addRestingOrder | 입력 객체의 참조를 그대로 저장 | 호출자가 보관한 입력 주문을 바꾸면 저장 주문도 바뀜 |
| firstOrder·get·[OrderBook.find](https://github.com/0Chord/coin-exchange/blob/59dfe6c239f7f9c1bd4990fe476ef2c79e2cee00/domain-matching/src/main/kotlin/com/exchange/core/matching/OrderBook.kt#L145) | 저장된 가변 주문을 그대로 반환 | 조회한 객체를 fill하면 내부 잔량도 바뀜 |
| [PriceLevel.snapshot](https://github.com/0Chord/coin-exchange/blob/59dfe6c239f7f9c1bd4990fe476ef2c79e2cee00/domain-matching/src/main/kotlin/com/exchange/core/matching/PriceLevel.kt#L101) | 목록만 복사하고 원소는 공유 | 과거 시점의 고정된 잔량 사본이 아님. 기준 커밋에서 호출자는 없음 |
| bestBidLevel·bestAskLevel | 실제 가격 레벨을 반환 | 레벨을 직접 remove하면 주문장 인덱스가 함께 갱신되지 않음 |

예: 잔량 10인 주문을 레벨에 넣고 `snapshot()`의 첫 주문에 `fill(4)` 하면 레벨의 잔량도 6이다. 또한 레벨에서만 주문을 제거하면 주문장 인덱스에는 주문이 남을 수 있다. 엔진은 현재 이 API를 정해진 순서로 호출한다. 모든 저수준 메서드가 중복 주문·취소 소유자·인덱스 일관성을 독립적으로 보장한다고 설명하지 않는다.

## 2. 합의한 상태 접근과 최소 구현안

**합의: 내부 타입·변경 경로를 제한하고 내부 참조는 유지한다.** 구현안은 BookOrder·PriceLevel·OrderBook을 matching 모듈 내부용으로 제한하고, 잔량 setter를 private로 만들어 검증된 fill만 잔량을 변경하는 것이다. 내부 알고리즘의 가변 객체·참조와 공개 command/event 계약은 유지한다.

- 세 타입은 `internal`로 제한한다. app-api는 내부 book을 만들거나 조회하지 않는다.
- BookOrder는 일반 class의 본문 속성에 `private set`을 두는 형태로 구현했다. 현재 저장소에 이 타입의 copy·구조분해·값 동등성 의존은 없다. 변경하면 생성자 검증·속성 이름·fill 동작을 보존한다.
- 내부의 firstOrder·get·find·가격 레벨 접근은 엔진 알고리즘용으로 유지한다. 엔진이 아닌 코드의 임의 상태 변경은 허용 계약으로 만들지 않는다.
- 호출자가 없는 snapshot은 삭제했다. 읽기 기능이 실제로 필요해질 때 값 사본 조회 계약을 별도로 설계한다. 내부 참조를 계속 쓰는 것과 외부에 변경 가능한 조회 API를 제공하는 것은 다른 선택이다.
- MatchingEngine.process는 공개로 유지한다. 운영 application은 processor를 거치지만, 독립 코어 테스트·JMH는 자기 엔진을 직접 실행할 수 있다.

`internal`은 Kotlin 컴파일의 모듈 경계다. Java에서는 공개 선언으로 보일 수 있으므로 JVM 보안 경계나 reflection 차단으로 설명하지 않는다. [Kotlin 공식 문서](https://kotlinlang.org/docs/java-to-kotlin-interop.html#visibility)에 이 차이가 명시돼 있다.

### 실제 운영 우회 검사

기존 운영 출력 수집과 ArchUnit의 직접 의존 검사를 재사용해 **app-api main의 MatchingEngine·BookOrder·PriceLevel·OrderBook 직접 참조를 금지**한다. `InMemoryMarketCommandProcessor` 구현 참조는 `app-api`의 정확한 `com.exchange.core.api.config` package에 있는 `@Configuration` 타입에서만 허용하고, 업무는 processor 인터페이스를 사용한다. 테스트·JMH를 검사 출발점에 넣지 않는다. reflection·문자열 로딩·실제 스레드 실행은 이 검사로 보장하지 않는다.

이 규칙은 ‘허용 config의 구현 직접 참조’까지만 검사한다. 생성 호출이 반드시 `@Bean` 메서드 본문에 있는지까지 증명하는 규칙은 만들지 않는다. 기존 P05는 Bean 선언 위치, 기존 app-api 조립 테스트는 실제 연결을 검증하며, config 본문의 업무 판단 여부는 리뷰한다.

별도 일반 역할 등록표·분류기·공유 참조 분석기를 만들지 않는다. 규칙 구현 위치는 기존 `architecture-tests` 안이다. 실제 운영 검사를 독립 `P09`로 추가하고, 보고서 확인의 필수 목록도 P01~P09로 변경했다. P01~P08을 약화하거나 건너뛰지 않는다. 이는 #23의 매칭 접근 부분이지 #22의 DB·불변 상태까지 ARCH-07 전체가 끝났다는 선언이 아니다.

정상 예제는 application → processor 계약, config → processor 구현, 테스트/JMH → 자체 engine이다. 위반 예제는 application → engine, application → 내부 book, application → processor 구현이다. 검사 대상 app-api 출력이 없거나 손상되면 기존 수집기의 준비 오류이며, 위반 0건 통과로 바꾸지 않는다.

## 3. 정상 흐름과 책임 경계

1. UseCase가 요청을 만들고 Coordinator에 전달한다. 제출은 지원 마켓·LIMIT·GTC를 먼저 검사한다.
2. Processor가 마켓 worker의 큐에 넣는다. 같은 마켓은 한 작업 스레드에서 직렬 처리하고 다른 마켓은 자기 worker를 사용한다.
3. `beforeMatching`이 있다면 실행한다. 주문 제출에서는 자금 예약이다. 취소에는 before가 없다.
4. 엔진이 주문장 상태를 바꾸고 이벤트 값 목록을 만든다.
5. Coordinator의 handler가 **publisher → afterMatching** 순서로 실행한다. 제출의 after는 체결별 정산, 취소의 after는 취소 성공 이벤트의 예약 해제다.
6. handler까지 끝난 다음 Future가 성공한다. Coordinator가 결과를 받아 호출자에게 반환한다.

변경 전 조사 근거는 [SubmitOrderUseCase](https://github.com/0Chord/coin-exchange/blob/59dfe6c239f7f9c1bd4990fe476ef2c79e2cee00/app-api/src/main/kotlin/com/exchange/core/api/order/application/SubmitOrderUseCase.kt#L46), [CancelOrderUseCase](https://github.com/0Chord/coin-exchange/blob/59dfe6c239f7f9c1bd4990fe476ef2c79e2cee00/app-api/src/main/kotlin/com/exchange/core/api/order/application/CancelOrderUseCase.kt#L26), [MarketWorker](https://github.com/0Chord/coin-exchange/blob/59dfe6c239f7f9c1bd4990fe476ef2c79e2cee00/domain-matching/src/main/kotlin/com/exchange/core/matching/MarketCommandProcessor.kt#L173)다. 이 처리 순서는 이번 구현에서도 유지했다. Future는 ‘나중에 성공 값이나 오류가 채워지는 결과’다. after가 아직 실행 중이면 성공 결과가 완성된 것이 아니다.

예약·이벤트 저장·체결 하나의 정산·예약 해제는 각각의 DB 트랜잭션이다. 전체 요청이 하나의 트랜잭션은 아니다. NoOp publisher는 설정상 저장 없이 성공하며 after로 진행한다. 이 설정/Bean 계약은 #21의 기존 테스트를 재사용한다.

## 4. 실패와 후속 요청 — 현재 정책 유지

before 자체의 실패는 해당 요청만 실패시킨다. **before 구간을 지나 engine/handler에서 오류가 났을 때**, before가 존재했거나 engine이 정상 반환했다면 마켓을 중단한다. before의 실제 부수효과를 분석하지 않는다. 아무 일도 하지 않는 before라도 정상 반환 후 엔진 오류가 나면 이 조건에 해당한다.

| 실패 위치 | 해당 요청과 생략되는 작업 | 같은 마켓 다음 요청 | 남는 상태와 한계 |
| --- | --- | --- | --- |
| before 자체가 오류 E를 던짐 | E로 실패. engine·publisher·after 미실행 | 계속 처리 가능 | callback 일반 계약은 외부 부수효과를 되돌리지 않음. 예약 서비스 자체 롤백은 #22/기존 DB 테스트 |
| before 없음, engine 정상 반환 전에 오류 E | E로 실패. handler 미실행 | 현재 코드는 중단하지 않음 | 기존 테스트의 MARKET·중복 ID는 변경 전 거절. 모든 engine 예외가 무변경/롤백이라는 보장은 없음 |
| before 정상 반환 뒤 engine 오류 E | E로 실패. publisher·after 미실행 | 최초 E를 원인으로 거절 | before가 남긴 예약/표식이 남을 수 있음. 자동 반환 없음 |
| publisher 오류 E | E로 실패. after 미실행 | 최초 E를 원인으로 거절 | 엔진 변경·앞서 커밋된 예약은 남을 수 있음. 실패한 저장 트랜잭션만 롤백 |
| publisher 성공 뒤 after 오류 E | E로 실패 | 최초 E를 원인으로 거절 | 저장 이벤트·엔진 변경·앞선 정산 커밋은 되돌리지 않음 |
| 이미 실패한 마켓의 새 요청 또는 큐 대기 요청 | RejectedExecutionException, 원인은 최초 E. before/engine/handler 미실행 | 계속 거절 | 같은 worker의 실패 상태를 자동 해제하지 않음. 다른 마켓은 진행 가능 |

취소 거절 `OrderCancelRejected`는 오류 throw가 아닌 정상 이벤트다. 실패 마켓 중단과 구분한다. 운영 제출의 LIMIT/GTC 사전 검증 때문에 아래 ‘before 성공 + 엔진 검증 실패’ 사례는 실행기 계약용 코어 테스트로 재현한다.

**별도 후속 선택:** before가 없는 엔진에서 변경 도중 예상치 못한 예외가 난 경우의 복구/중단 정책은 이번에 바꾸지 않는다. 이를 전부 안전한 검증 거절이라고 설명하지 않는다. 실제 재현이 발견되면 그 보장 차이를 논의한다.

## 5. 시간 초과·인터럽트·종료 — 현재 정책 유지

### 3초가 지나면 무엇이 끝나는가

Coordinator는 `get(3, SECONDS)`로 결과를 최대 3초 기다린다. 시간 초과가 나면 **호출자의 기다림이 끝날 뿐** worker 취소·주문 취소·DB 롤백·마켓 실패 표시를 하지 않는다. 원래 작업은 계속되거나 나중에 실제 오류로 실패할 수 있다. [Future.get 공식 계약](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/Future.html#get(long,java.util.concurrent.TimeUnit))과 Coordinator의 cancel 호출 부재를 구분해 근거로 삼는다.

예: 엔진 처리는 끝났지만 publisher가 아직 대기 중이면, 응답은 시간 초과이고 after는 아직 미실행이다. 같은 마켓 다음 명령은 큐에서 기다리고 다른 마켓은 진행할 수 있다. publisher를 풀어주면 원래 after가 실행되고 다음 명령도 진행한다. 뒤늦게 publisher/after가 실패하면 앞 절의 실패 중단 정책을 따른다. 요청자는 시간 초과만 보고 성공·실패 중 어느 하나로 확정하거나 자동 재제출하지 않는다.

대기 스레드가 interrupt되면 Coordinator는 그 스레드의 interrupt flag를 복구하고 IllegalStateException으로 전달한다. 이것도 worker 취소가 아니다. 오류 E가 Future에 담기면 ExecutionException의 원인을 꺼내 E를 전달한다.

### close는 새 접수를 막는다

Processor.close는 processor/worker를 닫고 executor.shutdown을 호출한다. 이미 executor에 접수된 작업은 계속 처리될 수 있으며 **close가 종료 완료를 기다리지는 않는다**. 기존 작업이 실패 마켓을 만들면 큐 대기 작업은 거절될 수 있으므로 ‘접수된 모든 작업이 성공한다’고 쓰지 않는다. 기존 경쟁 테스트처럼 처리 자체 오류가 없는 정상 명령에서는 종료와 submit의 경쟁 결과가 성공 또는 거절이며 순서를 고정하지 않는다. before/engine/handler 오류가 있다면 원래 오류로 실패할 수도 있다. [ExecutorService.shutdown 공식 계약](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ExecutorService.html#shutdown())도 이 종료 의미의 근거다.

## 6. 기존 테스트와 이번 보강 사례

아래 ‘기존 근거’의 링크와 줄 번호는 변경 전 기준 커밋 `59dfe6c239f7f9c1bd4990fe476ef2c79e2cee00`에서 조사한 기록이다. ‘직접 사례 없음’도 그 기준에서의 관측이며, 이번 보강 뒤의 현재 테스트 위치는 [구현 읽기](state-access-boundaries-review.md)에 연결한다. 이 표는 실행·통과 결과가 아니다. 기대값은 독립적인 상태·순서·오류 계약에서 정하며 구현값을 복사하지 않는다.

| 계약 | 기존 근거 | #23에서 보강할 관측 |
| --- | --- | --- |
| 가격·FIFO·부분 체결·소유자·중복 ID | [MatchingEngineTest](https://github.com/0Chord/coin-exchange/blob/59dfe6c239f7f9c1bd4990fe476ef2c79e2cee00/domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingEngineTest.kt#L176), 부분 체결 231, 중복 ID 414, 소유자 524 | 기존 회귀 재사용. 잔량 10 → fill(4) → 6 → fill(6) → 0, 원수량 10 유지. fill(0)/잔량 초과 거절 뒤 잔량 불변을 직접 추가 |
| 내부 참조·접근 제한 | 세 helper 전용 테스트 없음 | 별도 Kotlin 소비자의 세 타입 사용 컴파일 거절, 같은 모듈에서도 잔량 대입 거절. 정상 engine/processor/JMH 컴파일 보존 |
| 반환 이벤트의 값 안정성 | 이벤트 값 타입 사용, 엔진 처리 테스트 | SELL 10의 OrderEnteredBook을 보관 → BUY 4 체결(TradeExecuted.quantity=4) → SELL 주문자 취소 결과 OrderCancelled.remainingQuantity=6. 과거 OrderEnteredBook은 계속 10. 내부 주문 객체 반환 없음 |
| 운영 진입점 | [ProductionBoundaryInputsTest](https://github.com/0Chord/coin-exchange/blob/59dfe6c239f7f9c1bd4990fe476ef2c79e2cee00/architecture-tests/src/test/kotlin/com/exchange/architecture/ProductionBoundaryInputsTest.kt#L89)는 HTTP의 엔진 직접 참조 검증 | app-api의 엔진/내부 타입·구현 우회 정상/위반 예제와 실제 출력 검사. 누락 출력은 준비 오류 |
| 같은 마켓 순서·독립 마켓·취소 경합 | [MarketCommandProcessorTest](https://github.com/0Chord/coin-exchange/blob/59dfe6c239f7f9c1bd4990fe476ef2c79e2cee00/domain-matching/src/test/kotlin/com/exchange/core/matching/MarketCommandProcessorTest.kt#L28), 동시 제출 118·156, 취소 경합 260 | before/after를 latch로 멈춤. 같은 마켓 다음 before는 앞선 after 종료 전 미시작, 첫 Future 미완료. 콜백 실행 스레드가 같고, 막힌 동안 다른 마켓 완료 |
| before 실패 | 처리기 테스트에 beforeMatching 사용 사례 없음 | 정상 LIMIT/GTC 명령의 before가 E throw → E 전파, handler 0회 → 같은 orderId 정상 재제출은 seq=1 성공. 첫 요청에서 엔진을 실행했다면 중복 ID로 실패하도록 구성 |
| before 없는 변경 전 검증 거절 | ProcessorTest의 MARKET 309·중복 ID 334 | 기존 유지. 모든 엔진 오류의 롤백 증거로 확대하지 않음 |
| before 정상 반환 뒤 엔진 실패 | 직접 사례 없음 | non-null before 표식 유지 + MARKET 거절 → E 전파, 같은 마켓 신규/미리 큐에 넣은 명령 거절·후속 before 0회, 다른 마켓 seq=1 성공 |
| publisher 실패 | [MatchingCoordinatorPersistenceFailureTest](https://github.com/0Chord/coin-exchange/blob/59dfe6c239f7f9c1bd4990fe476ef2c79e2cee00/app-api/src/test/kotlin/com/exchange/core/api/matching/application/MatchingCoordinatorPersistenceFailureTest.kt#L41), 동일 마켓 59·다른 마켓 78 | 기존 실제 DB 오류/격리 테스트 재사용. 가짜 publisher가 E throw하는 조율 테스트에서 after 0회와 원래 E 전파를 명시 |
| 실패 전에 큐에 들어온 요청 | ProcessorTest의 handler 실패 507 | 기존 latch 사례의 후속 명령에 before를 추가하여 before도 0회임을 확인 |
| after 실패 | 서비스 단위 트랜잭션 롤백 테스트만 있음 | publisher 성공 표식 → after E throw → E 전파, 성공 표식 보존, 같은 마켓 거절·다른 마켓 성공. 전체 롤백 기대 금지 |
| 종료·접수 경쟁 | ProcessorTest의 drain 384, 종료 뒤 거절 410·424, 반복 종료 439, 경쟁 454 | 기존 경쟁 재사용. 실행 중/대기 중 작업을 latch로 만든 뒤 close → 새 요청 거절·before 0회 → 해제 뒤 기존 after/Future 완료 |
| 3초 시간 초과 | Coordinator의 코드만 확인, 직접 사례 없음 | 실제 processor + publisher latch → 호출자 TimeoutException, 작업 미취소·before 유지·after 미실행 → 다른 마켓 성공/같은 마켓 대기 → 해제 뒤 원래 after·후속 요청 성공 |
| 대기 스레드 interrupt | 직접 사례 없음 | 실제 대기 중 호출자 interrupt → flag 복구·IllegalStateException(cause=InterruptedException). latch 해제 뒤 worker 작업은 완료 |

### 불안정하거나 약한 테스트를 피하는 방법

- CountDownLatch 등 신호로 ‘before 진입’, ‘publisher 대기’, ‘다음 명령 접수’를 확인한다. 단순 sleep으로 타이밍을 추측하지 않는다.
- 3초 테스트는 실제 Coordinator 대기 경로를 한 번 이상 통과한다. 테스트 시간 제한을 두고 finally에서 latch 해제·processor close·테스트용 스레드 종료를 보장한다. 정확히 3.000초 같은 벽시계 어설션은 하지 않는다.
- 가짜 processor가 TimeoutException만 던지는 사례는 예외 전달만 검증한다. 실제 worker의 계속 실행이나 다음 요청 순서의 증거로 쓰지 않는다. 작업 Future는 submit을 위임하는 얇은 관측용 테스트 객체로 보관하거나 후속 완료 신호로 확인한다.
- 컴파일 거절은 정상 소비자 컴파일과 대조하고 정확한 접근성 오류를 확인한다. 라이브러리 해석 실패·classpath 누락을 의도한 거절로 세지 않는다. 같은 모듈 test의 friend 접근을 외부 모듈 거절 증거로 쓰지 않는다.
- 접근성 fixture는 architecture-tests에 이미 선언된 Kotlin compiler embeddable 2.3.21을 재사용하는 안이다. MatchingStateAccessCompilationTest에서 정상 소비자 컴파일과 정확한 접근성 오류를 대조했다. 외부 소비자는 friend 설정 없이 실제 main 출력과 의존 모듈을 classpath로 사용한다. 같은 모듈 setter 예제는 실제 BookOrder 소스와 소비자를 함께 컴파일하거나 friend 접근으로 타입을 보이게 하되 정상 생성/대입 거절을 대조한다. 세 내부 타입은 각각 독립 예제로 확인한다. 새 의존성·범용 컴파일 테스트 프레임워크는 제외한다.
- 순서 검사는 before → engine 결과 → publisher → after → Future 완료를 관측한다. engine 단계는 실제 이벤트·순번으로 확인하고 임의 mock 호출만으로 주문장 변화까지 보장하지 않는다.

## 7. 변경 위치와 검증 순서

단위 1은 `domain-matching`의 BookOrder·PriceLevel·OrderBook 및 대응 코어 테스트, `architecture-tests`의 작은 직접 참조 규칙·예제·운영 진입점·보고서 필수 목록을 대상으로 한다. 기존 Gradle 보고서 작업 설명과 README의 필수 검사 목록도 P09 추가에 맞춘다. 단위 2는 기존 MarketCommandProcessorTest 보강과 app-api의 Coordinator 계약 테스트다. 단위 3은 같은 Coordinator 계약 테스트와 processor 종료 사례다. 새 사례는 MatchingStateOwnershipTest, MatchingStateAccessCompilationTest, MatchingCoordinatorContractTest와 기존 ProductionBoundaryInputsTest·MarketCommandProcessorTest에 연결했다.

운영 Processor/Coordinator 알고리즘·3초 상수는 현재 정책을 바꾸지 않는 한 유지한다. 주석은 내부 참조의 수명·중단 조건·대기 종료의 의미를 정확히 설명하는 데 필요한 만큼만 맞춘다. #24의 전체 주석 정리를 앞당기지 않는다.

다음은 **로컬 재확인 명령**이다. 실제 실행한 선택 명령·전체 검증 결과는 실행 기록을 따른다. 실행 폴더가 바뀌면 `-p`의 실제 경로도 안내한다.

```sh
./gradlew -p /Users/0chord/.codex/worktrees/issue23-state-access/exchange-core :domain-matching:test --rerun-tasks
./gradlew -p /Users/0chord/.codex/worktrees/issue23-state-access/exchange-core :app-api:test --tests '*MatchingCoordinator*Test' --rerun-tasks
./gradlew -p /Users/0chord/.codex/worktrees/issue23-state-access/exchange-core :architecture-tests:test :architecture-tests:verifyArchitectureReport --rerun-tasks
./gradlew -p /Users/0chord/.codex/worktrees/issue23-state-access/exchange-core :benchmark-jmh:compileJmhKotlin
./gradlew -p /Users/0chord/.codex/worktrees/issue23-state-access/exchange-core build --no-daemon --continue --rerun-tasks
```

실제 존재하는 선택 테스트명/Gradle 작업을 실행 전에 확인한다. Coordinator의 기존 DB 테스트와 전체 build에는 Docker가 필요하다. 새 콜백/시간 초과 계약 테스트와 코어·구조 검사는 DB 없이 실행 가능하게 한다. 보고서는 기존 각 모듈 `build/reports/tests/test/index.html`과 `build/test-results/test/TEST-*.xml`을 사용한다.

## 8. 완료 기준과 제외 범위

로컬 검증: 구조 368개·제품/DB 262개, 총 630개 실패·오류·skip 0. P01~P09 필수 보고서와 JMH 컴파일 성공. 첫 전체 실행의 기존 JPA 컨테이너 연결 준비 실패 8개는 보존했고 같은 코드로 app-api 전체를 재실행해 통과했다. 다른 모듈은 직전 전체 재실행 결과를 Gradle 입력 대조 후 재사용했다. [실행 기록](state-access-boundaries-verification.json)에 명령·집계·기준 커밋·소스 해시를 남겼다. 이 실행 기록은 PR 게시 전 로컬 검증 스냅샷이며, 이후 PR/원격 CI/병합과 이슈 완료 상태는 원격 기록에서 확인한다.

- [x] 상태 접근 방식을 Ask 답변으로 확정하고 구현·수용 사례에 연결했다.
- [x] 내부 참조는 실제 사용 경계에 맞게 제한되고, 임의 잔량 대입을 막는다. 정상 엔진·processor·JMH 사용은 유지한다.
- [x] 새 직접 참조 규칙의 정상·위반·누락 예제를 확인하고 실제 운영 코드에 활성화한다. 필수 보고서 목록의 추가/누락/skip 거절도 함께 확인한다.
- [x] 기존 검증 거절·FIFO·동시 제출·종료 경쟁·실제 DB 발행 실패 테스트를 재사용해 회귀를 확인한다.
- [x] 표의 빠진 before/after·콜백 순서·후속 요청·시간 초과·인터럽트 사례를 관측하고 원래 오류·남는 상태를 확인한다.
- [x] 테스트 작성 → 기대값 검토 → 구현 → 검증 근거와 실행 기준 커밋·범위·실패/오류/skip을 기록한다. 준비 실패를 규칙 통과로 세지 않는다.
- [x] HTML에 작은 단위별 입력 → 판단 → 결과, 실제 소스·테스트 근거와 한계를 연결한다.

제외: 전체 주문장 불변화, 외부 조회 projection 기능, 매칭 알고리즘/수량·가격·수수료 변경, SQL·DB 제약·트랜잭션 변경(#22), 모듈 전면 이동, 테스트/JMH의 processor 경유 강제, 자동 복구·보상·재시도, 시간 초과 취소·새 HTTP 상태 정책, 큐 용량/백프레셔·shutdown 대기 정책, PIT/Sonar/보안 도구 도입.

단위 1의 구현은 확인된 코드 소비자를 기준으로 공개 범위를 줄인다. 이를 임의 외부 Java/reflection 공격까지 방어하는 경계로 확대하지 않는다. 동시성 테스트 통과도 가능한 모든 스케줄에 대한 증명은 아니다.

## 9. Ask 문답과 남은 기술 확인

**질문(요약):** “현재 PriceLevel.snapshot()은 목록만 복사하고 주문 객체는 공유해. 잔량 10인 반환 객체를 fill(4)하면 내부 잔량도 6이 돼. 내부 타입·변경 경로를 제한하고 내부 참조는 유지할까, 아니면 읽기 전용 값 조회와 내부 변경 경로를 분리할까?”

**AI 추천·근거:** 내부 제한. 운영 엔진의 book은 이미 private이고 세 helper의 소비자는 내부뿐이다. 외부 조회 요구 없이 값 사본 API를 추가하면 변경과 이해 비용이 늘어난다. 잔량 setter 제한과 운영 우회 검사로 필요한 경계부터 만든다.

**사용자 실제 답변:** “내부 타입·변경 경로를 제한하고 내부 참조는 유지 (추천)”. 별도 이유는 제시하지 않았다.

**결정 상태:** 상태 경계 합의 완료. 2절과 접근성/공유 참조 수용 사례, 첫 구현 단위에 반영했다. 읽기 전용 외부 조회 API는 이번 범위에서 제외한다. 실패·3초·종료 계약은 기존 정책을 유지한다.

**기술 확인 결과:** 기존 Kotlin 컴파일 도구와 실제 main 출력으로 정상 소비자 성공 및 접근 거절을 재현했다. 클래스 누락을 접근성 거절로 세지 않는다. 새 외부 의존성은 추가하지 않았다. 별도의 미결정 제품 정책은 없다. 이슈 본문·Projects·PR은 이번 로컬 구현 요청으로 갱신하지 않았다.

## 10. 구현 중 흐름 문답

질문: “외부에는 내부 주문을 숨기지만 엔진 안에서는 같은 주문 객체를 바꾼다. HTML에서 어느 부분을 더 자세히 볼까?” 사용자 실제 답변: “내부 참조 유지와 외부 접근 제한의 차이 (추천)”. 내부 주문의 동일 참조와 fill 상태 전이, 외부 소비자의 컴파일 거절, 운영 코드의 직접 참조 거절을 구현 읽기에 나란히 반영했다. 이 답변을 모든 코드 이해나 검토 완료로 기록하지 않는다.
