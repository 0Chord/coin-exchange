# #23 주문장 상태·실행 경계 구현 읽기

**현재: 로컬 구현·검증 완료.** 기준은 #36 병합 커밋 `59dfe6c239f7f9c1bd4990fe476ef2c79e2cee00`이며 실제 작업 폴더는 `/Users/0chord/.codex/worktrees/issue23-state-access/exchange-core`, 브랜치는 `refactor/state-access-boundaries/23`이다. [합의한 명세](state-access-boundaries-spec.md)에서 이어간다. 제품 구현·실행 결과와 사용자 이해·PR·CI·병합은 별도 상태다.

## 처음에 읽을 지도

이번 변경은 **엔진 안의 같은 주문을 바꾸는 것은 유지하고, 외부가 그 주문을 직접 고치는 것은 제한**한다. 콜백·시간 초과·종료 정책은 바꾸지 않고 기존 보장을 직접 관측하는 테스트를 보강한다.

| 읽기 단위 | 입력 → 판단 → 결과 | 지금 확인한 상태 |
| --- | --- | --- |
| 내부 주문을 누구에게 보일까 | 외부 Kotlin 코드의 내부 타입 사용·잔량 대입 → 컴파일 접근성 확인 → 거절 | 변경 전 거절 어설션 4개 실패 → 변경 후 6개 통과 |
| 운영 코드가 엔진을 우회할까 | app-api main 전체 → 엔진/내부 주문 직접 참조 또는 허용 config 밖 실행기 구현 → ARCH-07 위반 | Red 확인 → 사례 8개·실제 운영 P09 통과 |
| 같은 마켓의 작업은 언제 끝날까 | before → 엔진 → publisher → after → Future | 실제 processor 기반 신규·기존 회귀 통과 |
| 실패 뒤 다음 요청은 어떻게 될까 | 실패 위치 → worker 실패 여부 → 같은 마켓 거절 또는 계속/다른 마켓 진행 | 실제 processor 기반 신규·기존 회귀 통과 |
| 기다림이 끝나면 작업도 끝날까 | 3초 대기·interrupt·close → 호출자/worker 상태 구분 | 실제 processor 기반 신규·기존 회귀 통과 |

## 1. 내부 참조와 외부 접근은 다른 경계다

**내부 흐름:** 엔진이 잔량 10의 주문을 주문장에 넣는다 → 주문장과 가격 레벨은 같은 주문 객체를 사용한다 → 엔진이 fill(4)를 요청한다 → 주문이 허용 수량인지 검사하고 잔량을 6으로 바꾼다. 이 내부 참조를 매번 복사하지 않는다.

**외부 흐름:** app-api는 command를 processor에 전달한다 → worker가 자기 엔진을 실행한다 → 호출자는 이벤트 값만 받는다. app-api가 BookOrder를 만들거나 잔량을 직접 대입하는 코드는 허용하지 않는다. 새 외부 조회 API나 읽기 전용 사본 모델을 만들지는 않는다.

`internal`은 다른 Kotlin 모듈에서 타입을 쓰지 못하게 하는 표시다. `private set`은 같은 모듈에서도 잔량을 직접 대입하지 못하게 한다. fill의 규칙 검사와 같은 뜻은 아니다. 하나는 **접근 권한**, 다른 하나는 **허용 수량 판단**이다. Java·reflection의 접근까지 같은 보장을 주지는 않으므로 운영 코드의 직접 참조 검사도 구분한다.

기대 사례는 10 → 4 체결 → 6 → 6 체결 → 0이다. 원수량은 10 유지. 0 또는 잔량 초과 체결은 거절하고 상태를 보존한다. 공개 이벤트는 값이어서 처음 반환한 잔량 10은 이후 4 체결·6 취소를 처리해도 10으로 남는다.

근거: [BookOrder](../domain-matching/src/main/kotlin/com/exchange/core/matching/BookOrder.kt), [상태 동작 테스트](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingStateOwnershipTest.kt), [실제 Kotlin 소비자 컴파일 테스트](../architecture-tests/src/test/kotlin/com/exchange/architecture/MatchingStateAccessCompilationTest.kt). 접근성 6개, 상태 동작 5개 모두 통과했다.

### 실제 코드에서 바뀐 부분

`internal class`는 세 타입을 다른 Kotlin 모듈에 숨긴다. 아래 `remainingQuantity`는 생성할 때 받은 값을 저장하며, `private set` 때문에 내부 코드도 `order.remainingQuantity = ...`로 바꾸지 못한다. 잔량을 바꾸려면 기존 `fill`을 호출해야 한다. `data class`의 공개 copy 경로도 제공하지 않는다. 실제 사용처에는 copy·구조분해·값 동등성 의존이 없었다.

<!-- ISSUE23_BOOK_ORDER_START -->

```kotlin
internal class BookOrder(
    val orderId: OrderId,
    val userId: UserId,
    val side: Side,
    val price: Price,
    val originalQuantity: Quantity,
    remainingQuantity: Quantity,
) {
    /** 잔량은 같은 내부 주문에서 유지하며, 검증된 [fill]만 값을 바꾼다. */
    var remainingQuantity: Quantity = remainingQuantity
        private set
```

<!-- ISSUE23_BOOK_ORDER_END -->

PriceLevel·OrderBook은 내부 타입으로만 바꿨다. firstOrder/get/find는 여전히 같은 주문을 반환한다. snapshot은 사용처가 없고 이름과 달리 값 사본도 아니어서 제거했다. 매칭 알고리즘과 공개 명령·이벤트는 바꾸지 않았다.

### 이 흐름의 문답

- 질문: “외부 코드에는 내부 주문을 숨기지만 엔진 안에서는 같은 객체의 잔량을 바꾸는 이번 변경에서, HTML의 어느 부분을 더 자세히 보고 싶어?”
- AI 설명·근거: 객체 복사 여부와 모듈 밖 접근 가능 여부는 별개다. 필요한 상태 소유권만 좁히고 현재 알고리즘을 유지한다.
- 사용자 실제 답변: “내부 참조 유지와 외부 접근 제한의 차이 (추천)”. 별도 이유는 제시하지 않았다.
- 반영: 이 단위에서 내부 fill의 상태 변화와 외부 컴파일 거절을 나란히 설명한다. 답변을 모든 코드 이해·승인 완료로 기록하지 않는다.

## 2. 운영 코드의 실행기 우회를 막는다

기존 수집기로 운영 클래스 파일을 읽는다 → 수집 오류가 있다면 준비 실패 → app-api main 전체의 직접 참조를 읽는다 → 엔진·내부 주문 참조는 금지하고, 실행기 구현은 정확한 config 위치의 Configuration 타입에서만 허용한다 → 실제 위반 위치를 보고한다. 업무는 processor 인터페이스로 명령을 제출한다.

새 역할 등록표나 분류 플랫폼을 만들지 않는다. P09는 이 정적 직접 참조만 검사한다. Bean 메서드 본문, 실제 스레드 실행과 상태 변경은 이 결과만으로 보장하지 않는다. 필드 타입만 남아 소스 행을 확인할 수 없는 의존은 행번호를 만들어 넣지 않는다.

테스트 작성 → 기대값 검토에서 필드 의존의 행번호를 강제하면 정상 진단도 실패할 수 있다는 점을 수정했다. 필드는 타입·규칙·원본 파일을 확인하고, 실제 엔진 메서드 호출 예제로 확인 가능한 행번호를 별도로 검사한다.

근거: [직접 참조 규칙](../architecture-tests/src/test/kotlin/com/exchange/architecture/rules/MatchingStateBoundary.kt), [정상·위반·누락 예제](../architecture-tests/src/test/kotlin/com/exchange/architecture/ProductionBoundaryInputsTest.kt), [실제 운영 적용](../architecture-tests/src/test/kotlin/com/exchange/architecture/ProductionArchitectureTest.kt).

## 3. 콜백 실패와 후속 요청

같은 마켓의 정상 순서는 before → 엔진 → publisher → after → 결과 완료다. 다음 명령의 before도 앞 명령의 after 뒤에 시작해야 한다. 다른 마켓은 자기 worker로 진행한다.

| 실패 입력 | 현재 요청 | 다음 요청 | 남는 것 |
| --- | --- | --- | --- |
| before 자체 오류 | 엔진 미실행·원래 오류 | 같은 마켓 계속 가능 | callback의 외부 효과를 일반적으로 되돌리지는 않음 |
| before 성공 뒤 엔진 오류 | publisher/after 미실행 | 같은 마켓 신규·대기 명령 거절 | before 표식/예약이 남을 수 있음 |
| publisher 오류 | after 미실행·원래 오류 | 같은 마켓 거절 | 엔진 변경·앞선 예약은 남을 수 있음 |
| publisher 성공 뒤 after 오류 | 원래 오류 | 같은 마켓 거절 | publisher 성공·앞선 정산이 남을 수 있음 |

before 없는 변경 전 엔진 검증 거절은 기존 테스트를 유지한다. 임의 엔진 예외가 전부 무변경이라고 설명하지 않는다. 다른 마켓은 실패 마켓과 분리된다. DB 내부 롤백은 기존 DB 테스트/#22의 경계이며, 새 테스트의 성공 표식을 실제 DB 커밋 근거로 확대하지 않는다.

근거: [Processor 콜백·종료 테스트](../domain-matching/src/test/kotlin/com/exchange/core/matching/MarketCommandProcessorTest.kt), [Coordinator 계약 테스트](../app-api/src/test/kotlin/com/exchange/core/api/matching/application/MatchingCoordinatorContractTest.kt), [기존 실제 저장 실패 테스트](../app-api/src/test/kotlin/com/exchange/core/api/matching/application/MatchingCoordinatorPersistenceFailureTest.kt).

## 4. 3초·interrupt·close는 worker의 작업 취소가 아니다

실제 publisher를 신호로 멈춘다 → Coordinator의 실제 3초 대기가 끝나 호출자는 시간 초과 → worker 작업은 미완료·미취소 → 같은 마켓 다음 명령은 대기하고 다른 마켓은 완료 → publisher를 해제하면 원래 after와 다음 명령이 완료된다. 시간 초과만 보고 자동 재제출하지 않는다.

대기 스레드 interrupt는 flag를 복구해 오류로 전달하지만 worker를 취소하지 않는다. close는 새 접수를 막고 종료를 기다리지 않으며, 이미 접수된 작업은 계속 처리될 수 있다. 늦은 실제 작업 실패가 있으면 앞 절의 마켓 중단 정책을 따른다.

단순 sleep이나 가짜 TimeoutException만으로 계속 실행을 증명하지 않는다. 실제 processor와 latch, 결과 Future를 관측한다. 모든 테스트는 신호 해제와 스레드 정리를 보장하고 무한 대기를 허용하지 않는다.

## 실행 근거와 보장 범위

선택 검증은 구조·접근·보고서 32개, domain-matching 전체 74개, Coordinator 계약 4개가 통과했다. 실패·오류·skip은 모두 0이다. 실제 3초 대기·비취소·해제 뒤 완료를 관측했다. 전체 구조 368개·제품/DB 262개, 총 630개가 통과했고 JMH 컴파일도 성공했다. 필수 P01~P09 각각 한 번 실행·성공을 보고서에서 확인했다. [실행 기록](state-access-boundaries-verification.json)에 기준·명령·테스트 결과·소스 해시를 갱신한다. 제품 테스트 통과, 구조 위반 예제 검출, 실제 운영 준수 검사를 따로 기록한다.

새 규칙 Red 7개 중 6개, 접근성 Red 6개 중 4개, 보고서 Red 18개 중 4개가 의도한 어설션에서 실패했다. 각 실행 당시 소스 해시는 기록에 남겼다. 이후 Green 테스트에는 호출 의존의 실제 행번호 사례도 추가했다. 첫 전체 실행에서는 기존 JPA 테스트의 컨테이너 PostgreSQL SSL 연결 준비 실패로 8개가 실패했다. 이 기록을 지우지 않았고, 코드 변경 없이 app-api 전체 85개를 재실행해 통과했다. 최종 전체 build는 성공했으며 구조·도메인은 직전 전체 재실행의 성공 결과를 재사용했다. 이 재시도만으로 환경 문제를 영구 해결했다고 주장하지 않는다. PR·원격 CI·사람의 검토 완료로 확대하지 않는다.

## 변경 파일과 읽을 목적

| 종류 | 파일 | 이번에 한 일 |
| --- | --- | --- |
| 제품 상태 | [BookOrder](../domain-matching/src/main/kotlin/com/exchange/core/matching/BookOrder.kt) | internal 일반 class, 잔량 private setter. fill 판단은 유지 |
| 제품 상태 | [PriceLevel](../domain-matching/src/main/kotlin/com/exchange/core/matching/PriceLevel.kt), [OrderBook](../domain-matching/src/main/kotlin/com/exchange/core/matching/OrderBook.kt) | internal, 미사용 snapshot 제거. 내부 공유 참조는 유지 |
| 제품 실행 설명 | [Processor](../domain-matching/src/main/kotlin/com/exchange/core/matching/MarketCommandProcessor.kt) | 실패 마켓 판단과 자동 복구 부재를 정확히 주석으로 설명. 알고리즘 유지 |
| 상태 테스트 | [MatchingStateOwnershipTest](../domain-matching/src/test/kotlin/com/exchange/core/matching/MatchingStateOwnershipTest.kt) | 잔량 계산·거절·같은 참조·과거 이벤트 값 5개 |
| 실행 테스트 | [MarketCommandProcessorTest](../domain-matching/src/test/kotlin/com/exchange/core/matching/MarketCommandProcessorTest.kt) | 콜백 순서·실패 위치·대기 요청·close 4개 추가, 기존 오류 원인·미실행 확인 보강 |
| 조율 테스트 | [MatchingCoordinatorContractTest](../app-api/src/test/kotlin/com/exchange/core/api/matching/application/MatchingCoordinatorContractTest.kt) | publisher/after 실패·실제 3초·interrupt 4개 |
| 컴파일 검사 예제 | [MatchingStateAccessCompilationTest](../architecture-tests/src/test/kotlin/com/exchange/architecture/MatchingStateAccessCompilationTest.kt) | 기존 컴파일 도구로 별도 소비자 성공/접근 거절 6개. 제품 실행 코드가 아님 |
| 정적 검사 | [MatchingStateBoundary](../architecture-tests/src/test/kotlin/com/exchange/architecture/rules/MatchingStateBoundary.kt) | 기존 app-api 수집 결과의 직접 참조 판단. 새 역할 플랫폼 없음 |
| 검사 예제·실제 적용 | [ProductionBoundaryInputsTest](../architecture-tests/src/test/kotlin/com/exchange/architecture/ProductionBoundaryInputsTest.kt), [ProductionArchitectureTest](../architecture-tests/src/test/kotlin/com/exchange/architecture/ProductionArchitectureTest.kt) | Java 위반 코드를 임시 컴파일해 검사기 검증 8개, 실제 main에는 P09 |
| 보고서 확인 | [ArchitectureReportVerifier](../architecture-tests/src/test/kotlin/com/exchange/architecture/support/ArchitectureReportVerifier.kt), [대응 테스트](../architecture-tests/src/test/kotlin/com/exchange/architecture/ArchitectureReportVerifierTest.kt), [Gradle](../architecture-tests/build.gradle.kts) | 필수 9개 및 P09 누락·중복·skip·실패·오류·다른 클래스·비슷한 이름 거절 |
| 문서 | README·공통 검사 명세·흐름 계약·이번 명세/읽기/실행 JSON | 범위와 실제 결과 연결. 기존 보고서·HTML reader 재사용 |

실제 거래가 실행되는 곳은 제품 코드다. 임시 Java/Kotlin 코드는 접근·검사기의 정상/위반 사례를 만드는 테스트다. 테스트 안의 Future 관측 래퍼는 실제 processor에 위임하며, 별도 제품 실행기를 추가하지 않는다.

### 최종 실행을 확인하는 순서

1. 입력은 현재 브랜치의 실제 main·테스트다. baseline은 59dfe6c이며, 최종 실행 기록의 소스 해시가 표시 코드와 일치하는지 확인한다.
2. 구조 368개에는 불량 예제를 잡는 검사기 테스트와 실제 운영 P01~P09가 함께 있다. 위반 예제를 잘 잡았다는 사실과 운영 코드가 규칙을 지킨다는 사실은 별도다.
3. 제품/DB 262개에는 매칭 74개, 주문 50개, 수수료 37개, 잔고 16개, app-api 85개가 포함된다. 새 제품 동작 사례는 상태 5·processor 4·Coordinator 4개이며, 기존 FIFO/동시성/DB 저장 실패·Bean 조립도 재사용했다.
4. 시간 초과·interrupt는 worker를 취소하지 않는다. 마켓 중단은 후속 실행을 차단하며 앞선 엔진·DB 상태를 복구하지 않는다. Java/reflection, 모든 스케줄, 자동 보상, #22 DB/불변 전체 완료는 보장하지 않는다.

이 문서의 실행 근거는 PR 게시 전 로컬 검증 스냅샷이다. 게시 후 PR·원격 CI·병합 상태는 해당 PR에서 확인한다. 사람이 이 문서를 읽고 코드를 판단했는지도 별도 상태다.
