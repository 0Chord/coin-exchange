# ARCH-06 · 포트 계약 검사 구현 흐름

상태: **로컬 구현·검증 완료**. 로컬 검증 기준: 통합 `d31e0fc` 위 `test/port-contracts/19` 작업 스냅샷. [합의한 명세](architecture-check-spec.md)를 적용했고, 실행한 소스는 [해시 기록](architecture-06-verification.json)으로 식별한다. 게시·원격 CI·리뷰의 이후 상태는 PR에서 확인한다.

## 먼저 볼 전체 흐름

기존에는 포트를 순수 도메인에서 분리해 두었지만, 포트가 어떤 타입을 노출하는지는 검사하지 않았다. 이제 **포트의 공개 계약에서 기술·영속 타입이 드러나면 실패**한다. 거래·저장 제품 코드의 동작은 바꾸지 않았다.

| 읽을 흐름 | 입력 → 판단 → 결과 | 이 흐름에서 끝나는 책임 |
| --- | --- | --- |
| 준비 자료가 빠지면 멈춘다 | 기존 운영 출력·역할 검사 → 포트/영속 타입 정의 확인 → 준비 실패 또는 다음 단계 | 자료가 빠진 검사를 정상으로 표시하지 않음 |
| 공개된 계약만 꺼낸다 | 포트 → 인자·반환·필드·상위/중첩 선언 → 목록 안쪽 타입까지 펼침 | 메서드 본문·임의 DTO 객체 그래프는 펼치지 않음 |
| 타입을 판정하고 근거를 보고한다 | 노출 타입 → 기술 정책·영속 타입 비교 → 위반/허용, 중복 제거 | 어느 선언이 무엇을 노출했는지 설명 |
| 실제 포트에 적용한다 | 운영 포트 5개 → 동일 검사 함수 → 공개 계약 11개, 위반 0 | 구조 준수. DB 원자성·금액·호출 순서를 증명하지 않음 |

예제에 메서드가 있어도 테스트가 그 메서드를 실행하지 않는다. 테스트는 `.class`에 남은 계약을 읽는다. `JdbcValueAdapter`의 JDBC 호출이나 `DefaultBodyPort`의 네트워크 타입 사용은 **구조를 보여주기 위한 예제**이며 실행하지 않았다.

## 1. 자료가 빠졌다면 판정하지 않는다

1. 실제 운영 검사 P03이 기존 `ModuleRegistration`, `ProductionScopeImporter`, `RoleClassifier`를 호출한다. 새 모듈 미등록·출력 누락·미분류 역할이 있으면 여기서 실패한다.
2. 준비된 클래스를 ARCH-06에 전달한다. 검사기는 등록된 포트와 영속 모델의 정의가 있는지, 포트가 실제 인터페이스인지 확인한다.
3. 등록이 비었거나 포트가 사라졌으면 **준비 실패·미평가**다. 위반 목록이 비었다고 통과시키지 않는다.
4. 상속 계약을 읽는 중 필요한 내부 정의가 운영 출력에 없거나 외부 정의가 미해석이면 역시 미평가한다. 클래스패스에서 찾은 내부 클래스가 운영 출력 누락을 숨기게 하지 않는다.

예: 포트 목록에는 `BalanceStore`가 있는데 수집 결과에 없으면 실패한다. `BalanceStore`를 빼고 나머지만 검사해서 성공하는 선택은 하지 않는다. 같은 클래스가 여러 모듈에 배정된 입력도 준비 문제다.

근거: [준비·판정 코드](#source-gate), [실제 운영 연결](#source-production). 테스트: `PortContractRuleTest`의 PORT-12·13. 입력 해석 과정의 예상 밖 RuntimeException도 `CONTRACT_READ_FAILURE`로 막는다. 이 마지막 방어 분기는 소스로 검토했으며 손상된 모든 바이트코드 예외를 재현한 것은 아니다.

## 2. 호출자가 보는 계약만 읽는다

`PortContractReader`가 포트의 공개 선언을 읽는다. `BalanceStore`의 실제 저장 구현을 찾아 실행하지 않는다.

| 들어온 모양 | 읽는 과정 | 결과 |
| --- | --- | --- |
| `load(): Balance` | 반환 타입 Balance 확인 | 기술·영속 모델이 아니면 허용 |
| `load(): Map<String, List<Entity>>` | Map → 타입 인자 List → 타입 인자 Entity | Entity의 영속 역할 발견 |
| `val connection: Connection` | 컴파일된 getter의 반환 타입 확인 | JDBC 타입 노출 발견 |
| `var entity: Entity` | getter 반환과 setter 인자 확인 | 서로 다른 노출 자리 두 개를 보존 |
| `Child : Parent<Entity>` | 상속 선언의 Entity와 Parent의 공개 계약을 읽음 | 상속 선언도 빠뜨리지 않음 |
| 다중 상한·와일드카드·제네릭 배열 | 바깥 raw 타입만 보지 않고 상·하한/원소까지 탐색 | 안쪽 Connection도 발견 |
| public 중첩·companion 계약 | 실제 포함 관계와 public 접근 수준을 따라 읽음 | 같은 루트 포트 아래 노출로 보고 |
| private 메서드·기본 메서드의 본문 | 공개 시그니처 범위에 넣지 않음 | 이 검사가 본문 I/O를 보장하지 않음을 유지 |

공개 멤버·인자에 직접 붙은 기술 어노테이션과 바이트코드의 throws 타입도 읽는다. KDoc의 문장, 어노테이션 속성 값·메타어노테이션, Kotlin metadata만의 표현은 분석하지 않는다.

근거: [계약 추출 코드](#source-reader), [Kotlin 예제](#source-fixtures), [Java 예제](#source-java-fixtures). PORT-05–09·11·13 테스트가 배열·프로퍼티·상속·상한·어노테이션과 누락을 연결한다. Java 예제는 Kotlin에서 직접 쓰기 어려운 와일드카드·다중 상한·브리지를 실제 컴파일하기 위해 사용한다.

## 3. 허용·위반을 나누고 중복을 없앤다

읽은 타입이 다음 중 하나면 위반이다.

- 기존 ARCH-01의 Spring·JPA·JDBC·명시적 네트워크/클라이언트 타입 정책.
- ARCH-06에 합의한 트랜잭션 기술 타입과 `ObjectMapper`.
- 명시적으로 등록한 영속 모델 또는 운영 출력에서 JPA 표식을 발견한 타입.

`Balance`, `List`, `URI`, `Instant` 자체는 금지하지 않는다. `CompletableFuture<Balance>`도 이 규칙의 금지 타입은 아니지만 `CompletableFuture<Entity>`의 Entity는 발견한다. `Any`·raw 목록·사용자 DTO 안에 숨긴 런타임 객체는 별도 의미 리뷰가 필요하다.

위반은 루트 포트·실제 선언·노출 자리·대상 타입·이유·파일/행·명세 링크를 포함한다. 추상 메서드에는 실행 행이 없을 수 있어 **행 정보 없음**으로 표시한다. 숫자를 지어내지 않는다.

**이번에 실제 수정한 결함:** JVM 브리지와 원래 메서드는 같은 계약을 가리키면서 소스 행은 달랐다. 진단 객체 전체로 중복을 제거하면 같은 인자 위반을 두 번 보고했다. 이제 `포트 + 선언 + 노출 자리 + 대상 타입`으로 묶고, 서로 다른 계약은 남긴다. 중복 상속과 브리지 예제에서 정확한 진단 집합·개수를 확인한다.

근거: [판정·진단 코드](#source-gate), [공통 기술 정책](#source-policy), [기존 ARCH-01 연결](#source-arch01), [회귀 테스트](#source-tests). ARCH-01의 두 기술 목록은 기준 커밋과 문자 단위로도 대조했고 동일했다. ARCH-06 추가 정책이 ARCH-01까지 확대되지 않도록 분리했다.

## 4. 실제 포트 다섯 개에 연결한다

P03은 운영 여섯 모듈의 출력에서 다음 포트를 읽는다.

| 포트 | 소속 | 실제 공개 계약 수 |
| --- | --- | ---: |
| OrderReservationStore | domain-order | 4 |
| BalanceStore | domain-ledger | 4 |
| LedgerTransactionStore | domain-ledger | 1 |
| MatchingEventStore | app-api | 1 |
| MatchingEventPublisher | app-api | 1 |

`ProductionScope`에 `MatchingEventEntity`를 영속 모델로 등록했다. JPA 표식에 의한 발견도 병행한다. `MatchingEventRepository`는 저장 구현 내부의 Spring Data 도구라 포트 목록에 넣지 않는다. 제품의 SQL·트랜잭션 경계·반환값은 그대로다.

P03 결과: **포트 5개·공개 계약 11개·준비 오류 0·위반 0**. 예제와 실제 운영 검사는 같은 ARCH-06 함수를 호출한다. Gradle의 기존 main 컴파일·테스트 입력 추적을 재사용하며 새 CI 작업은 만들지 않는다.

근거: [운영 등록](#source-registration), [운영 준수 검사](#source-production), [빌드 변경](#source-build). `check`/`build`는 기존 테스트 모듈을 통해 새 검사도 실행한다. 구조 검사만 실행할 때는 DB를 시작하지 않지만 전체 프로젝트 빌드에는 기존 Testcontainers 검사가 포함된다.

## 테스트 기대값과 검토 근거

| 계약 | 기대값의 출처 | 확인할 관측값 |
| --- | --- | --- |
| PORT-01·10 허용 경계 | 도메인 값을 쓰는 포트, 기술을 쓰는 저장 구현은 기존 의도 | 정상 입력은 평가됨·위반 0. 구현체의 실제 JDBC 참조가 있어도 포트 위반으로 세지 않음 |
| PORT-02–04 직접 노출 | JDBC/Spring·영속 모델 노출 금지 | 정확히 한 진단의 규칙·출발·선언·자리·대상·이유·소스 확인 |
| PORT-05–09 안쪽 계약 | 컨테이너·프로퍼티·상속으로 금지 타입을 감추지 않음 | 컴파일된 예제의 타입 전제, 기대 대상·노출 자리의 집합/개수 |
| PORT-11 공개 범위 | public 중첩 계약은 포함, private·본문은 제외 | 중첩/companion 위반 두 개, 기본 메서드 본문 기술 참조는 이 검사에서 제외 |
| PORT-12–13 누락 | 빈 대상·누락·미해석 입력을 정상으로 처리하지 않음 | evaluated=false와 구체 문제 코드. 읽을 수 있는 외부 상위 계약은 정상 |
| PORT-14 보고 | 입력 순서·중복 탐색이 판정을 바꾸지 않음 | 정렬 결과 일치, 다중 상속·브리지 중복 제거, 서로 다른 노출은 유지 |
| P03 실제 적용 | 등록한 운영 포트 전부에 동일 정책 적용 | 포트 5개·공개 계약 11개, 실제 출력에서 위반 0 |

기대값은 명세의 구체적인 허용/금지 사례에서 적었다. 검사기 결과로 예상값을 만들거나 구현의 정책 목록을 복사하지 않았다. 기대값 검토와 구현 검증은 이번 작업을 수행한 같은 AI가 했으며, 별도 컨텍스트의 독립 PR 리뷰는 아직이다.

## 실행 기록

- 1단위 Red: 7개 중 6개 실패. 미구현 검사기가 금지 타입·준비 문제·계약 수를 반환하지 못했다. 예제 전제가 확인된 뒤 기대 결과가 없었던 실패다.
- 1단위 Green: 새 7개 + 기존 ARCH-01 23개 = 30개 통과.
- 2단위 초기 실패 중 한 건은 테스트가 메서드 이름을 잘못 잘라낸 오류였다. 이를 수정한 Red는 19개 중 9개 실패. 프로퍼티는 이미 직접 시그니처 검사로 통과했다.
- 2단위 Green: 19개 통과. 중간 Optional 변환 컴파일 오류는 수정했고 행동 수준의 Red로 세지 않는다.
- 구조 전체 첫 실행: 101개 통과, 실패·오류·skip 0. 이때 P03의 포트 5개·계약 11개를 확인했다.
- 보완 리뷰: 외부 상위 계약 허용·중복 상속·실제 JVM 브리지 사례 추가. 21개 중 1개가 브리지 중복 보고로 실패했고 중복 기준을 수정했다.
- 보완 후 ARCH-06 테스트 21개 통과. 최종 전체 빌드는 **346개 통과·실패 0·오류 0·skip 0**이며 구조 검사 **103개**를 포함한다. 현재 소스 해시와 명령은 아래 검증 기록에 연결한다.

```sh
./gradlew build --no-daemon --console=plain --continue --rerun-tasks
```

전체 빌드에서 app-api 75개, architecture-tests 103개, domain-fee 37개, domain-ledger 16개, domain-matching 65개, domain-order 50개를 실행했다. 독립 구조 검사와 전체 빌드를 구분하며, 전체 빌드의 기존 Testcontainers 테스트는 실행 가능한 Docker 환경에서 확인했다.

보고서: `architecture-tests/build/reports/tests/test/index.html` 및 각 모듈의 `build/test-results/test/*.xml`. 이 기록은 로컬 실행 결과이며 원격 CI·독립 리뷰 완료를 뜻하지 않는다. 검증 기록의 `/tmp` 로그 경로는 로컬 보조 자료이며 저장소에 게시한 로그가 아니다.

## 변경 파일의 역할

| 역할 | 파일 | 연결된 흐름 |
| --- | --- | --- |
| 개발·검증 도구 | PortContractIndependence.kt / PortContractReader.kt | 준비·추출·판정·보고 |
| 기존 검사 지원 | ExternalTechnologyTypes.kt / DomainTechnologyIndependence.kt | 기존 기술 정책 재사용, ARCH-01 보존 |
| 운영 검사 대상 설정 | ProductionScope.kt | 포트 등록 재사용·영속 모델 등록 |
| 실제 준수 테스트 | ProductionArchitectureTest.kt | P03 연결, P01·P02와 독립 실행 |
| 검사기 테스트 | PortContractRuleTest.kt | 정상·위반·누락·중복·한계 |
| 테스트용 예제 | PortFixtures.kt / JavaPortFixtures.java | 의도한 컴파일 구조. 제품 클래스가 아님 |
| 테스트 빌드 | architecture-tests/build.gradle.kts | 실제 트랜잭션·직렬화 타입의 테스트 의존성 |
| 명세·설명·근거 | architecture-check-spec.md / architecture-06-review.md / architecture-06-verification.json | 합의·실제 흐름·검증 결과 연결 |
| 로컬 표시 도구 | 기존 reader/build.py·template.html·생성 index.html | 접힌 소스·색상·줄 번호, 이전 ARCH-02 기록 분리 |

제품 실행 코드 변경은 없다. 이번 변경 파일은 위 역할에 모두 연결한다. 소스에서 검토한 방어 분기와 실행한 사례를 구분했으며, 모든 JVM 조합을 실행 검증했다는 뜻은 아니다.

## 근거 코드 펼치기

GitHub에서는 아래 저장소 파일 링크로 근거를 읽는다. 로컬 HTML은 생성 시점의 실제 코드를 펼쳐 보여주므로 소스가 바뀌면 다시 생성해야 한다.

<!-- ARCH06_SOURCES_START -->

| 근거 | 저장소 파일 |
| --- | --- |
| <a id="source-gate"></a>준비·판정 | [PortContractIndependence.kt](../architecture-tests/src/test/kotlin/com/exchange/architecture/rules/PortContractIndependence.kt) |
| <a id="source-reader"></a>공개 계약 추출 | [PortContractReader.kt](../architecture-tests/src/test/kotlin/com/exchange/architecture/rules/PortContractReader.kt) |
| <a id="source-policy"></a>공통 기술 정책 | [ExternalTechnologyTypes.kt](../architecture-tests/src/test/kotlin/com/exchange/architecture/rules/ExternalTechnologyTypes.kt) |
| <a id="source-arch01"></a>ARCH-01 연결 | [DomainTechnologyIndependence.kt](../architecture-tests/src/test/kotlin/com/exchange/architecture/rules/DomainTechnologyIndependence.kt) |
| <a id="source-registration"></a>운영 등록 | [ProductionScope.kt](../architecture-tests/src/test/kotlin/com/exchange/architecture/support/ProductionScope.kt) |
| <a id="source-production"></a>실제 운영 연결 | [ProductionArchitectureTest.kt](../architecture-tests/src/test/kotlin/com/exchange/architecture/ProductionArchitectureTest.kt) |
| <a id="source-tests"></a>기대값·회귀 | [PortContractRuleTest.kt](../architecture-tests/src/test/kotlin/com/exchange/architecture/PortContractRuleTest.kt) |
| <a id="source-fixtures"></a>Kotlin 예제 | [PortFixtures.kt](../architecture-tests/src/test/kotlin/com/exchange/architecture/fixtures/portcontracts/PortFixtures.kt) |
| <a id="source-java-fixtures"></a>Java 예제 | [JavaPortFixtures.java](../architecture-tests/src/test/java/com/exchange/architecture/fixtures/portcontracts/JavaPortFixtures.java) |
| <a id="source-build"></a>테스트 빌드 | [build.gradle.kts](../architecture-tests/build.gradle.kts) |
| <a id="source-evidence"></a>실행 근거 | [architecture-06-verification.json](architecture-06-verification.json) |

<!-- ARCH06_SOURCES_END -->

## 남는 한계와 다음 판단

등록한 포트와 명시한 기술/영속 타입 범위의 **구조**를 검사한다. 새 app-api 인터페이스가 포트인지, 비-JPA 모델이 영속 모델인지의 의미 판단은 등록·리뷰가 필요하다. 임의 DTO 내부, Any/raw 타입의 실제 객체, 포트/기본 메서드 본문의 I/O, 모든 Kotlin metadata·리플렉션은 보장하지 않는다.

DB 원자성·동시성·금액 계산·호출 순서는 별도 테스트 책임이다. #19 전체 완료도 아니다. ARCH-08과 ARCH-03/04/05 예제 검증이 남아 있다.

먼저 볼 판단은 이것이다. **“저장 구현이 Connection을 사용하는 것은 허용하지만, 포트가 Connection을 반환하면 실패한다.”** 두 경우를 구분하는 코드와 반대 사례가 위 흐름 2·3에 연결돼 있다.
