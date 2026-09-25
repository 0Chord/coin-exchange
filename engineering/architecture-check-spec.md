# 구조 검사 명세 — #19 첫 구현분

대상: [#19 공통 구조 검사 기반 구현 및 명세 v1 적용](https://github.com/0Chord/coin-exchange/issues/19).
이번 PR은 **검사 대상 준비와 ARCH-01**을 제공한다. #19 전체 완료나 사람의 리뷰 완료를 뜻하지 않는다.

## 목적과 경계

검사 대상이 빠진 상태에서 위반이 없다고 통과하거나, 순수 도메인이 외부 기술에 의존하는 변경을 놓치지 않게 한다.
전용 `architecture-tests` 모듈은 운영 코드를 컴파일한 `.class` 파일을 읽는다. 서버·DB·Docker를 시작하지 않으며 다른 모듈의 테스트를 선행 실행하지 않는다.

| 이번 PR에서 제공 | 후속 작업으로 남음 |
| --- | --- |
| 모듈 등록, 운영 클래스 수집, 역할 구분 | ARCH-02 모듈 의존 방향, ARCH-06 포트 기술 누출, ARCH-08 운영의 테스트·벤치마크 역의존 |
| ARCH-01 정상·위반 예제와 실제 운영 코드 검사 | ARCH-03/04/05 예제 검증. 운영 적용은 #20–21 |
| 독립 실행 명령, 오류의 규칙·대상·위치 표시 | #22–23의 상태·저장·실행 흐름 계약, #25의 후속 CI 운영 검증 |

거래 동작, HTTP API, `UPDATE … RETURNING`, 도메인의 검증 후 새 불변 객체 반환, 가변 주문장과 실행기의 구분은 변경하지 않는다.
구조 검사 통과는 금액 계산, 호출 순서, 동시성, DB 원자성, 시간 초과 후 롤백을 증명하지 않는다.

## 실행 흐름

1. Gradle이 운영 여섯 모듈의 main 코드를 컴파일하고 출력 폴더를 전달한다. 실제 JVM 프로젝트 목록은 등록 목록과 독립적으로 찾는다.
2. 실제 모듈과 운영·비운영 등록을 비교한다. 미등록, 실제로 없는 모듈, 양쪽에 중복 등록한 모듈을 오류로 보고한다.
3. 수집기가 실제 파일의 클래스 이름과 ArchUnit 읽기 결과를 대조한다. 누락·중복·오염·빈 대상·읽기 실패를 오류로 보고한다.
4. 수집한 도메인 타입에서 명시한 포트·실행기를 구분한다. 새 도메인 타입은 기본 포함하고 미분류 인터페이스는 검토를 요구한다.
5. `ProductionArchitectureTest`는 등록·수집·역할 오류가 없을 때만 같은 ARCH-01 규칙을 실제 운영 코드에 적용한다.
6. 위반이 없으면 이번 활성 규칙 범위에서 통과한다. 오류나 위반이 있으면 테스트가 실패한다.

규칙 자체 테스트와 운영 준수 테스트는 별개다. 위반 예제에서 기대한 위반을 찾으면 **그 예제 테스트는 통과**한다. JUnit 테스트 메서드의 실행 순서에 의존하지 않는다.

## 수집·역할 계약

- 운영 모듈: `domain-common`, `domain-fee`, `domain-order`, `domain-ledger`, `domain-matching`, `app-api`.
- 비운영 등록: `architecture-tests`, `benchmark-jmh`. 이름만 보고 자동 제외하지 않는다.
- 모듈별 대표 타입은 입력 검증에 사용한다. 대표 타입만 읽는 방식으로 전체 클래스 수집을 대신하지 않는다.
- 테스트·fixture·JMH 출력, 그 상위 폴더, 등록한 금지 클래스가 운영 원본 집합에 섞이면 실패한다.
- 경로 별칭은 정규화한다. 별도 파일의 같은 클래스 이름과 같은 파일의 복수 모듈 소속은 각각 오류다.
- 실제 파일의 이름은 Java 25 ClassFile API로 읽고, ArchUnit 결과와 비교한다. 파일 하나를 읽지 못해도 정상으로 간주하지 않는다.
- 프로젝트 내부 직접 참조 대상이 운영 출력 목록에 없으면 실패한다. 내부 이름 범위는 `com.exchange.core.` 및 `com.exchange.architecture.`이며 새 네임스페이스 도입 시 갱신한다.
- 도메인 모듈은 기본 검사 대상이다. `app-api` 전체를 순수 도메인으로 분류하지 않는다.
- 포트: `OrderReservationStore`, `BalanceStore`, `LedgerTransactionStore`, `MatchingEventStore`, `MatchingEventPublisher`. 실제 전체 이름은 `ProductionScope`에 등록한다.
- 실행기: `MarketCommandProcessor`, `InMemoryMarketCommandProcessor`, `MarketWorker`, `MarketCommandProcessorKt`와 바이트코드의 포함 관계로 확인한 중첩 타입.
- 검토된 순수 인터페이스: `MatchingCommand`, `MatchingEvent`. 새 인터페이스를 포트로 가정하여 조용히 제외하지 않는다.
- 같은 파일에 있는 값 객체·예외를 포트와 함께 제외하지 않는다. 각 도메인 모듈의 순수 집합이 비면 실패한다.

## ARCH-01 계약

순수 도메인 역할의 타입과 그 중첩 타입을 검사한다.

| 허용 | 위반으로 보고 |
| --- | --- |
| 값 객체·계산기 간 협력, data/value class 생성 멤버 | Spring, JPA, JDBC 의존이 어노테이션·필드·인자·반환값·제네릭 등에 남음 |
| 포트 선언과 포트 타입 보유 | 등록된 외부 포트 또는 구현체의 메서드 호출·메서드 참조 |
| 별도 실행기의 동시성 제어 | 순수 타입의 HTTP·명시된 네트워크 I/O 타입 참조 |
| 역할 밖 애플리케이션·인프라의 기술 사용, URI 값 타입 | 명시된 Kafka·PostgreSQL·Hibernate 클라이언트 의존 |

기술 목록은 `DomainTechnologyIndependence`에 명시한다. JDK/Kotlin 전체를 금지하지 않는다.
Kotlin 생성 타입은 이름 패턴으로 일괄 제외하지 않는다. 중첩·람다·파일 파사드에 남은 기술 호출도 사례로 확인한다.
바이트코드로 사라진 표현, 리플렉션, 모든 간접 콜백과 모든 I/O를 추적한다고 주장하지 않는다.

위반에는 규칙 ID, 원본 모듈·타입, 참조 설명, 대상 타입, 가능한 소스 파일·행, 본 명세 경로를 제공한다.
행 정보가 없으면 정보 없음으로 남기며 추측하지 않는다. 진단은 중복을 제거하고 정렬한다.

## 실행과 근거

```sh
./gradlew :architecture-tests:test --no-daemon --console=plain
```

보고서: `architecture-tests/build/reports/tests/test/index.html`, `architecture-tests/build/test-results/test/*.xml`.
표준 `check`/`build`에도 검사 모듈이 포함된다. 기존 전체 CI의 `build`에는 다른 모듈의 Testcontainers 테스트가 있으므로 Docker가 필요하다.

| 검사 집합 | 사례 수 |
| --- | ---: |
| 모듈 등록 M01–M05 | 5 |
| 대상 수집 S01–S22 | 22 |
| 역할 분류 C01–C04 | 4 |
| ARCH-01 A01–A20 | 20 |
| Gradle 준비 W01–W02 | 2 |
| 실제 운영 적용 P01 | 1 |

실행한 정확한 커밋과 결과는 PR의 검증 기록 및 CI를 따른다. 이 문서의 사례 수 자체가 실행 성공의 증거는 아니다.

## 작은 단위로 읽기

먼저 **파일 두 개 중 하나를 읽지 못했다면 통과할 수 있는가** 하나를 본다.

- 입력: `ScopeValue`, `AnotherScopeValue` 파일이 있지만 일부러 첫 클래스만 반환하는 reader를 사용한다.
- 기대: 두 번째 클래스의 `INCOMPLETE_IMPORT` 오류가 있어야 한다.
- 테스트: `ImportScopeContractTest`의 S09.
- 구현: `ProductionScopeImporter.load`의 `expected - observed` 비교.
- 연결: `ProductionArchitectureTest`가 수집 오류를 확인하여 ARCH-01 이전에 실패한다.

이 사례가 파일 목록 자체의 정확성, 역할 구분, 모든 구조 규칙까지 증명하는 것은 아니다. 다른 판단은 각각 해당 테스트·구현과 대조한다.
