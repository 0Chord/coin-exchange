# #21 저장 경계 — 구현과 검증

**합의한 세 단위의 로컬 구현·검증을 마쳤다. 제품·DB 81개와 구조 353개가 실패·오류·skip 없이 통과했다.** 기준은 통합 커밋 `525a8cdc5861441fdfec50441e000755e36a1eea`, 작업 폴더는 `/Users/0chord/.codex/worktrees/issue21-storage-boundaries/exchange-core`, 브랜치는 `refactor/storage-boundaries/21`이다. [명세](storage-boundaries-spec.md)를 따른다. 이 문서는 PR 게시 전 로컬 검증 시점의 기록이다. 게시 후 CI·리뷰·병합 상태는 GitHub PR에서 확인한다.

## 이번에 바뀐 것부터 보기

**저장 계약과 기술 구현을 서로 다른 폴더에 배치했다. 거래 동작은 유지했다.** 운영 파일 12개와 기존 저장 테스트 7개를 이동했고, config·Coordinator·HTTP 테스트의 import와 검사기의 기존 포트/Entity 경로를 맞췄다. 새 도메인 계약·저장 프레임워크·클래스별 역할 등록표는 만들지 않았다.

| 읽을 단위 | 입력 → 판단 → 결과 | 확인한 상태 |
| --- | --- | --- |
| 1. 설정에 맞는 발행기 연결 | true → Persistent + Jpa Store. false/설정 없음 → NoOp. Coordinator는 Publisher 계약으로 호출 | 첫 단위 8개 통과. 최종 패키지에서 다시 포함해 제품 81개 통과 |
| 2. 실제 저장 결과 유지 | 이벤트 목록 → JSON/Entity → Repository. 잔고·예약·원장은 기존 JDBC | PostgreSQL 저장·목록 롤백·경합·정산·HTTP 회귀 포함 81개 통과 |
| 3. 최종 구조 규칙 적용 | 전체 운영 타입·원본 → 기존 이름 17개 규칙과 최종 폴더 → 통과/위반/준비 오류 | 구조 353개 통과. P01~P08 성공, P08 이름 37개·main 원본 64개와 규칙 17개 확인 |

첫 두 단위의 테스트 수는 중복되므로 8+81로 합산하지 않는다. 구조 검사는 실행 참조·위치·이름을 읽는 검사다. 실제 DB 저장 성공이나 업무 결과는 제품 테스트의 근거로 구분한다.

## 1. 설정값이 발행기 하나를 고른다

**포트는 요청 계약이다.** `MatchingEventPublisher`는 “이 이벤트 목록을 발행해 달라”라는 인터페이스이고, `MatchingEventStore`는 “이 목록을 저장해 달라”라는 계약이다. Jpa Store와 Persistent/NoOp Publisher는 그 계약을 실행하는 구현이다. Spring Data Repository는 JPA 저장을 위한 기술 인터페이스다. 이름의 Store/Repository만 보고 같은 역할이라고 합치지 않는다.

| 설정 | 실제로 조립하는 Bean | 호출과 결과 |
| --- | --- | --- |
| `exchange.matching.persistence.enabled=true` | MatchingPersistenceConfig → Jpa Store → Persistent Publisher 하나 | Publisher → Store → JSON/Entity → Repository 저장. 정상 반환 후에만 후속 정산/해제 |
| `false` | MatchingConfig → NoOp Publisher 하나. Jpa Store/Persistent 없음 | 저장 없이 정상 반환 → 후속 callback → 명령 결과 반환 |
| 설정 없음 | `matchIfMissing=true`이므로 false와 같은 선택 | DB 오류를 삼키는 fallback이 아니라 미저장을 선택한 설정 |
| `exchange.ledger.persistence.enabled=true` | 기존 JDBC template → 잔고·예약·원장 Store → 자금 예약·해제·정산 Service | 실제 쿼리는 Store, 트랜잭션 조율은 기존 Service 경계 |
| 원장 false/설정 없음 | LedgerPersistenceConfig의 포트·Service Bean 없음 | 전체 앱의 무DB 기동을 보장한 검사는 아님 |

config의 `@Bean` 함수가 객체를 연결한다. Controller/Advice와 Spring Data 인터페이스의 기존 처리는 유지하고, 업무 객체에 `@Service`나 `@Component` 자동 등록은 추가하지 않았다. Bean 이름·조건·실행기의 종료 설정도 그대로다.

**테스트 근거:** [매칭 조건 3개](../app-api/src/test/kotlin/com/exchange/core/api/config/MatchingPublisherConfigurationTest.kt), [원장 조건 3개](../app-api/src/test/kotlin/com/exchange/core/api/config/LedgerPersistenceConfigurationTest.kt). true의 작은 context는 Repository/JDBC 대역으로 연결만 확인한다. false/설정 없음에서는 저장 의존성을 제공하지 않아도 NoOp가 선택되며, 실제 Coordinator/메모리 실행기로 callback과 반환 이벤트를 확인한다. 이 작은 context는 JPA 프록시·DB 원자성의 근거가 아니다.

<details markdown="1">
<summary>설정 없음도 NoOp를 선택하는 실제 조건 보기</summary>

실제 [MatchingConfig.kt](../app-api/src/main/kotlin/com/exchange/core/api/config/MatchingConfig.kt) 37–43행 · 파일 SHA256 `cc207fa7a674`. 전체 원문은 링크에서 이어서 읽을 수 있다.

```kotlin
    @Bean
    @ConditionalOnProperty(
        name = ["exchange.matching.persistence.enabled"],
        havingValue = "false",
        matchIfMissing = true,
    )
    fun matchingEventPublisher(): MatchingEventPublisher = NoOpMatchingEventPublisher()
```

</details>

## 2. 저장이 완료돼야 다음 작업으로 넘어간다

정상 명령은 **사전 예약 → 매칭 엔진 → 이벤트 발행 → 정산/예약 해제 → 결과 완료**다. 같은 마켓 worker에서 순서대로 처리한다. Coordinator는 Publisher 포트만 받고 실제 구현을 새로 만들지 않는다.

`publish`가 정상 반환하면 바로 다음 줄의 `afterMatching`을 실행한다. NoOp도 정상 반환하므로 그 다음 작업이 계속된다. true 경로의 저장이 실패하면 예외가 발생해 다음 줄로 가지 않는다. 두 경로를 같은 “DB 저장 성공”이라고 부르지 않는다.

| 분기·실패 | 남는 상태와 호출자 결과 | 검증과 한계 |
| --- | --- | --- |
| 영속화 true, 정상 목록 | 실제 DB에 기존 순서·필드·JSON 저장 후 후속 처리 | 기존 Jpa Store/Payload/영속화 연결/Publisher 실제 DB 테스트 |
| NoOp 정상 | 이벤트는 저장하지 않고 callback을 실행해 결과 반환 | 새 false/설정 없음 context 테스트. 전체 제품의 미저장 모드 E2E까지 추가하지 않음 |
| 목록 뒤쪽 이벤트 중복 | 같은 목록 앞쪽 insert도 롤백, 이전 커밋 row 유지 | 기존 Publisher의 PostgreSQL 롤백 테스트 |
| 이벤트 저장 오류 | 정산/해제 callback 미실행. 앞선 예약·메모리 엔진은 남을 수 있음 | Coordinator 본문 보존 + 실제 순번 충돌 3개. 이 충돌 테스트는 자금 예약을 생략함 |
| 오류가 난 마켓의 다음 요청 | 원인을 제거해도 같은 마켓은 후속 명령 거절, 다른 마켓은 계속 처리 | 기존 Coordinator 실제 저장 실패 회귀 |
| 예약/체결별 정산/해제 오류 | 담당 DB 작업은 롤백. 앞선 예약·이벤트·메모리까지 전체 보상하지 않음 | 기존 Service DB 롤백과 호출 경계 대조. 새 복구·재처리 조합은 #22~23 |
| 결과 대기 3초 초과 | 대기만 종료. worker 취소나 DB 롤백을 뜻하지 않음 | 코드 본문 보존. 새 timeout 실험은 이번 범위에 추가하지 않음 |

[실제 저장 실패 검사](../app-api/src/test/kotlin/com/exchange/core/api/matching/application/MatchingCoordinatorPersistenceFailureTest.kt)와 [목록 롤백 검사](../app-api/src/test/kotlin/com/exchange/core/api/matching/infrastructure/persistence/PersistentMatchingEventPublisherTest.kt)를 따로 읽는다. 매칭 이벤트와 원장 정산을 하나의 트랜잭션으로 합치지 않았다.

<details markdown="1">
<summary>발행 성공 뒤에만 후속 작업을 실행하는 실제 코드 보기</summary>

실제 [MatchingCoordinator.kt](../app-api/src/main/kotlin/com/exchange/core/api/matching/application/MatchingCoordinator.kt) 47–57행 · 파일 SHA256 `498183477b19`. 전체 원문은 링크에서 이어서 읽을 수 있다.

```kotlin
            processor
                .submit(
                    command = command,
                    beforeMatching = beforeMatching,
                    eventHandler = { events ->
                        publisher.publish(events)
                        afterMatching(events)
                    },
                ).get(3, TimeUnit.SECONDS)
        } catch (error: ExecutionException) {
            throw error.cause ?: error
```

</details>

## 3. JDBC/JPA의 동작은 그대로이고 위치가 달라졌다

[명세의 12개 이동표](storage-boundaries-spec.md)는 이동 전후를 연결한다. 아래는 모든 이동 파일이 맡는 현재 역할이다. 각 파일의 원문은 링크와 접힌 코드에서 확인한다.

| 제품 파일 | 현재 위치·읽을 계약 |
| --- | --- |
| [MatchingEventStore](../app-api/src/main/kotlin/com/exchange/core/api/matching/application/port/MatchingEventStore.kt), [MatchingEventPublisher](../app-api/src/main/kotlin/com/exchange/core/api/matching/application/port/MatchingEventPublisher.kt) | matching.application.port · 순수 이벤트 계약 |
| [PersistentMatchingEventPublisher](../app-api/src/main/kotlin/com/exchange/core/api/matching/infrastructure/persistence/PersistentMatchingEventPublisher.kt) | matching.infrastructure.persistence · 목록을 Store에 그대로 전달, 오류 전파 |
| [NoOpMatchingEventPublisher](../app-api/src/main/kotlin/com/exchange/core/api/matching/infrastructure/publish/NoOpMatchingEventPublisher.kt) | matching.infrastructure.publish · 설정에 따라 미저장 정상 완료 |
| [JpaMatchingEventStore](../app-api/src/main/kotlin/com/exchange/core/api/matching/infrastructure/persistence/JpaMatchingEventStore.kt) | matching.infrastructure.persistence · 빈 목록 반환, 순서대로 변환·한 목록 트랜잭션 |
| [MatchingEventRepository](../app-api/src/main/kotlin/com/exchange/core/api/matching/infrastructure/persistence/MatchingEventRepository.kt), [MatchingEventEntity](../app-api/src/main/kotlin/com/exchange/core/api/matching/infrastructure/persistence/MatchingEventEntity.kt) | matching.infrastructure.persistence · Spring Data 접근, 기존 테이블/컬럼/고유 제약 |
| [MatchingEventPayload](../app-api/src/main/kotlin/com/exchange/core/api/matching/infrastructure/persistence/MatchingEventPayload.kt), [MatchingEventType](../app-api/src/main/kotlin/com/exchange/core/api/matching/infrastructure/persistence/MatchingEventType.kt) | matching.infrastructure.persistence · 기존 네 이벤트 JSON 값과 enum |
| [PostgresBalanceStore](../app-api/src/main/kotlin/com/exchange/core/api/ledger/infrastructure/persistence/PostgresBalanceStore.kt) | ledger.infrastructure.persistence · 조건부 UPDATE RETURNING, 부족/부재 구분 |
| [PostgresLedgerTransactionStore](../app-api/src/main/kotlin/com/exchange/core/api/ledger/infrastructure/persistence/PostgresLedgerTransactionStore.kt) | ledger.infrastructure.persistence · 거래/항목을 기존 단위로 저장 |
| [PostgresOrderReservationStore](../app-api/src/main/kotlin/com/exchange/core/api/order/infrastructure/persistence/PostgresOrderReservationStore.kt) | order.infrastructure.persistence · 최초 snapshot 저장, FOR UPDATE 잠금, 나머지 수수료 보존 |

12개 파일의 기준 원문과 이동 후 원문을 대조했다. 11개는 package/import를 제외한 본문이 동일하다. NoOp도 실행 동작은 같고, “첫 API 임시 구현”이라는 낡은 설명을 “영속화가 꺼진 설정의 미저장 경로”로 고쳤다. 기존 SQL·migration·DB 제약·API URL/JSON·금액/수수료 기대값은 바꾸지 않았다.

잔고 테스트의 기존 **1000에서 400 예약 → available 600 / hold 400**은 독립적인 산술 기대값이다. 구현의 출력값을 새 정답으로 복사하지 않았다. 부족·부재·동시 예약, 예약 snapshot·수수료 나머지, 체결/해제 롤백도 기존 실제 DB 어설션을 유지했다. 파일 이동으로 Entity 스캔이나 트랜잭션 프록시가 끊겼다면 기존 통합 테스트가 실패해야 하며, 이번 제품 81개에서는 그 오류가 없었다.

<details markdown="1">
<summary>한 이벤트 목록을 저장하는 실제 코드 보기</summary>

실제 [JpaMatchingEventStore.kt](../app-api/src/main/kotlin/com/exchange/core/api/matching/infrastructure/persistence/JpaMatchingEventStore.kt) 30–47행 · 파일 SHA256 `43a18101c24a`. 전체 원문은 링크에서 이어서 읽을 수 있다.

```kotlin
    @Transactional
    override fun append(events: List<MatchingEvent>) {
        if (events.isEmpty()) {
            return
        }

        // entities 순서는 input event 순서와 같아 saveAll에도 engine 순서를 그대로 전달한다.
        val entities = events.map { event ->
            val payloadJson = objectMapper.writeValueAsString(event.toPayload())

            MatchingEventEntity.of(
                event = event,
                payloadJson = payloadJson,
            )
        }

        repository.saveAll(entities)
    }
```

</details>

## 4. 최종 폴더와 기존 17개 규칙을 운영 검사에 연결한다

이 단위는 **검증 도구**의 변경이다. 거래를 실행하거나 새로운 역할 분류 엔진을 만든 작업이 아니다.

1. Gradle이 전달한 전체 운영 출력과 원본 파일을 기존 수집기가 읽는다.
2. 포트 두 개와 Entity의 기존 정의 목록은 이동한 FQN으로 갱신한다. 필요한 정의가 없으면 준비 오류로 멈춘다.
3. P08이 `ProjectLayoutPolicy.target`과 `NamingRules.inspectTypes`를 사용한다. 17개 필수 규칙 ID와 기록 개수가 일치해야 한다. 클래스 수를 고정한 등록표를 추가하지 않는다.
4. Controller/UseCase부터 Store/Publisher/Repository/Entity까지 기존 규칙으로 이름·실제 선언 형태·역할 위치를 본다.
5. 모든 main 원본의 정확한 폴더와 package를 따로 확인한다. 네 옛 경로를 허용하던 임시 API는 제거했다.
6. 정상은 통과, 읽은 코드의 잘못된 의존·위치는 위반, 읽기/필요한 정의 누락은 준비 오류로 남긴다. 필수 P01~P08 누락·중복·skip·실패는 기존 보고서 확인기가 거절한다.

**반례:** 일반 `LegacyHelper`를 허용 infrastructure 폴더에서 옛 `matching/persistence`로 파일과 package를 함께 이동해도 실패한다. package와 파일이 서로 맞는 것만으로는 통과하지 못하고 **허용 폴더**인지도 검사하기 때문이다. 반대로 허용 application 폴더의 일반 `OrderManager`는 역할을 분류하지 못했다는 이유만으로 이름 위반이 되지 않는다. 실제 금지 의존은 계속 검사한다.

[운영 P08](../architecture-tests/src/test/kotlin/com/exchange/architecture/ProductionArchitectureTest.kt), [최종 폴더 정책](../architecture-tests/src/test/kotlin/com/exchange/architecture/policy/ProjectLayoutPolicy.kt), [기존 이름 규칙](../architecture-tests/src/test/kotlin/com/exchange/architecture/rules/NamingRules.kt), [입력 연결 회귀](../architecture-tests/src/test/kotlin/com/exchange/architecture/ProductionBoundaryInputsTest.kt), [이동한 계약 경로](../architecture-tests/src/test/kotlin/com/exchange/architecture/support/ProductionScope.kt)를 연결해 읽는다.

입력 회귀의 Java 클래스들은 임시 폴더에 만든 **고의 위반 예제**다. 실제 DB나 주문을 실행하지 않는다. Controller→새 포트/Store, application→Repository/Entity, 이름을 UseCase로 바꾼 Publisher 구현, 포트/Entity 정의 누락, 옛 네 폴더 복귀를 확인한다. 예제에서 기대 위반을 탐지하면 테스트가 통과한다. 실제 운영 위반은 CI 실패다.

## 전체 변경 파일과 읽기 범위

- **제품 실행 코드:** 위 12개와 MatchingConfig/MatchingPersistenceConfig/LedgerPersistenceConfig의 import, Coordinator의 포트 import. 각자의 함수 본문과 책임은 유지한다.
- **제품 테스트:** 저장 테스트 7개도 목표 폴더로 이동했다. Coordinator 실패/OrderController 테스트의 import를 맞췄다. config 조건 테스트 두 파일은 새로 추가했다.
- **검증 도구·예제 테스트:** ProductionArchitectureTest, ProductionBoundaryInputsTest, ProductionScope, ProjectLayoutPolicy, NamingRules의 다섯 파일. 새 의존·새 보고서 프레임워크는 없다.
- **저장소 문서:** 명세·이 문서·실행 근거 JSON, README·공통 컨벤션·개발/실패 흐름·공통 검사 명세를 현재 위치와 적용 상태에 맞췄다. 과거 실행 기록은 보존한다.

## 실행 결과와 한계

- 첫 단위: 선택한 제품 검사 8개, 실패·오류·skip 0.
- 이동 후 제품 전체: `:app-api:test --rerun-tasks`, 81개, 실패·오류·skip 0. 새 조건 6개와 기존 75개를 포함한다.
- 구조 전체: `:architecture-tests:verifyArchitectureReport --rerun-tasks`, 353개, 실패·오류·skip 0. 이 중 실제 운영 P01~P08 8개와 입력 연결 15개를 포함한다.
- P08의 실제 실행 기록: 이름 규칙 17개, 이름 대상 37개, 전체 main 원본 64개. 이 숫자는 이번 실행의 관측값이며 고정한 허용 등록 수가 아니다.
- 같은 AI의 기대값 검토와 별도 작성 맥락 없는 코드 검토를 병행했다. 별도 검토자는 테스트를 실행하지 않았고 실행 결과와 구분한다.
- 로컬 통과는 PR·원격 CI·병합·사용자 리뷰 완료의 근거가 아니다. 로컬 제품 명령은 전체 domain 테스트 실행을 뜻하지 않는다.

```sh
cd /Users/0chord/.codex/worktrees/issue21-storage-boundaries/exchange-core
./gradlew :app-api:test --no-daemon --console=plain --rerun-tasks
./gradlew :architecture-tests:verifyArchitectureReport --no-daemon --console=plain --rerun-tasks
```

[실행 근거와 코드 해시](storage-boundaries-verification.json)에 명령·실제 suite 목록·P08 대상 기록을 남겼다. 제품 보고서는 `app-api/build/reports/tests/test/index.html`, 구조 보고서는 `architecture-tests/build/reports/tests/test/index.html`에 생성된다. 빌드 산출물은 저장소에 게시하지 않는다. 원격 실행의 보고서는 [README의 CI 결과 읽기 안내](../README.md)에 따라 해당 실행의 artifact에서 확인한다.


## 다음 판단

사람이 다음으로 볼 것은 **NoOp 선택과 true 경로의 저장 실패가 서로 다른 계약인지**, **SQL·트랜잭션·실패 후 상태가 바뀌지 않았는지**다. 일반 이름의 업무 의미·동적 호출 전체·복구의 정확성은 구조 검사로 자동 승인하지 않는다.
