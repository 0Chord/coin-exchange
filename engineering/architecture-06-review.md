# ARCH-06 · 포트 계약 검사 구현 흐름

상태: **로컬 구현·검증 완료**. 로컬 검증 기준: 통합 `d31e0fc` 위 `test/port-contracts/19` 작업 스냅샷. 현재는 PR #28의 `9406277` 이후 공개 inner 클래스의 바깥 타입 변수 보완을 포함한다. [합의한 명세](architecture-check-spec.md)를 적용했고, 실행한 소스는 [해시 기록](architecture-06-verification.json)으로 식별한다. 게시·원격 CI·리뷰의 이후 상태는 PR에서 확인한다.

## PR #28 리뷰 보완 · 바깥 타입 변수 연결

공개 inner 클래스가 바깥 클래스의 `T`를 반환할 때, 검사기는 **T가 선언된 바깥 클래스의 제한 조건**까지 읽어야 한다. 이전에는 현재 클래스의 변수만 알고 있어서 반환 위치의 JDBC 진단을 놓쳤다. 원래 리뷰 반례에서는 바깥 상한 진단이 남아 포트 전체 거절은 유지됐다.

흐름: **원본의 실제 포함 관계 확인 → non-static이면 바깥 변수부터 해석 → 현재 클래스·메서드가 새로 선언한 변수와 구분 → 반환·인자·필드·상한의 각 위치에서 판정.** 바깥 변수의 의미는 inner에서 같은 이름을 다시 선언해도 바뀌지 않는다. static 중첩 타입에서는 바깥 변수 탐색을 끊는다.

| 입력과 분기 | 기대 결과·근거 |
| --- | --- |
| `Box<T>`의 T 상한에 JDBC가 있고 `Inner.load()`가 T를 반환 | Box 상한과 Inner 반환의 2곳을 보고. 같은 타입을 직접 반환하는 대조와 일치. 전체 입력·루트만 입력해도 동일 |
| 같은 T를 inner의 필드·인자·메서드 V 상한과 반환에 사용 | 상한 1곳과 사용 자리 6곳, 총 7곳 보고. 같은 타입이어도 위치를 합치지 않음 |
| 바깥 T 상한이 일반 값 | 준비 성공·위반 0. 바깥 선언을 읽었다는 이유만으로 금지하지 않음 |
| 바깥 `U extends T`, inner의 새 T와 메서드의 새 U, 더 깊은 inner | 바깥 상한 2곳과 바깥 U 사용 2곳만 보고. 새로 선언한 정상 변수에 바깥 JDBC 제한을 적용하지 않음 |
| static 중첩 포트만 입력하고 바깥 파일은 수집하지 않음 | 바깥 변수 환경을 요구하지 않으므로 정상 통과 |
| 상속 경로에서 inner를 먼저 만남 | 읽은 순서와 무관하게 실제 바깥 선언을 찾아 해당 반환 1곳 보고. 바깥 선언 자체를 새 공개 계약으로 추가하지 않음 |
| 필요한 내부 바깥 선언이 운영 수집에서 누락 | 클래스패스의 다른 복사본으로 대체하지 않고 `UNRESOLVED_PORT_CONTRACT`·미평가 |
| 필요한 외부 바깥 클래스 파일이 원본 위치에 없음 | 같은 디렉터리/JAR에서 찾지 못하면 준비 실패. 일부 진단만으로 정상 판정하지 않음 |

[원본 선언 보완](#source-bytecode)의 `visibleTypeReferences`는 바깥 → 현재 클래스 순으로 해석한 타입 정보를 보존한다. [계약 순회](#source-reader)는 원본을 캐시하고 필요한 바깥 정의를 찾아 준다. 포함 관계가 순환하는 비정상 원본은 준비 실패로 전파하도록 방어했다. 이 방어는 코드로 검토했으며 순환 바이트코드를 만들어 실행한 것은 아니다.

[회귀 테스트](#source-tests)와 [Java 예제](#source-java-fixtures)는 위 8개 입력을 연결한다. 바깥 T를 inner가 새로 선언한 것처럼 중복 진단하지 않는지도 정확한 진단 집합·개수로 확인한다. 검사 중 제품 객체·포트 메서드·DB는 실행하지 않는다.

새 테스트 8개 추가 후 **47개 중 6개 실패 → 수정 후 47개 통과**를 확인했다. 실패는 진단 누락 4개와 필요한 바깥 정의 누락을 미평가하지 않는 2개였다. 정상 상한과 static 경계 2개는 기존 동작 보존 사례다. 이전 독립 리뷰의 반례 스크립트도 기대값은 유지하고 실행 경로만 수정해 재실행했으며 exit 0이었다. 이번 수정의 독립 재리뷰는 아직이다.

**최종 검증:** 전체 build 성공. 구조 129개 실행·실패/오류/skip 0, 운영 포트 5개·계약 11개·준비 오류/위반 0. 다른 모듈은 UP-TO-DATE 결과를 재사용했다. XML 합계 372개는 기존 제품 243개를 포함한 현재 보고서 합계다.

<details markdown="1">
<summary>앞선 리뷰 보완 이력 · static·이름 가림·소유 타입·외부 중첩</summary>

### 이전 수정 · 상위 인터페이스 static 제외

사용자가 검사 범위를 확정했다. **자식 포트는 부모 인터페이스의 static 함수를 제공하지 않으므로 자식의 계약에서 제외한다.** 부모를 별도 포트로 등록하면 그 부모를 검사할 때 해당 static을 확인한다.

흐름: **공개 메서드 발견 → 지금 읽는 타입이 상위 인터페이스인지 확인 → 상위의 static이면 제외, 나머지는 기존 계약 추출·판정으로 전달.** 이 분기는 선언한 반환·인자·어노테이션·상한을 읽기 전에 적용하며, 제외한 메서드는 계약 수에도 넣지 않는다.

| 입력 | 기대 결과·확인할 이유 |
| --- | --- |
| 부모 static에 JDBC 노출, 자식의 상속 계약은 정상 | 자식 위반 0. 여러 단계 상속에도 동일. 상속 선언 2개와 일반/default 메서드 2개만 계산 |
| 같은 부모와 자식을 모두 포트로 등록 | 부모의 static 반환·인자 2곳만 부모 위반으로 보고. 자식에는 붙이지 않음 |
| 자식 자신이 선언한 static에 JDBC 반환 | 자식의 해당 반환 1곳을 보고. static을 전부 제외하지 않음 |
| 상속한 일반/default 메서드에 JDBC 반환 | 두 반환의 위반 유지. 부모의 static만 제외 |
| 같은 타입이 상위 인터페이스이자 공개 중첩 계약 | 공개 중첩 타입 자신의 static 위반을 유지. 먼저 상위로 방문했다는 이유로 빠뜨리지 않음 |

[계약 순회 코드](#source-reader)는 `inherited`로 상속 경로를 구분하고, 방문 기록도 타입과 경로를 함께 저장한다. [회귀 테스트](#source-tests)는 위 5개 사례를 [Java 예제](#source-java-fixtures)로 검증하며, reflection으로 인터페이스 static이 상속되지 않는 전제도 확인한다. 공개 필드·타입 상한·중첩 계약·본문 제외 정책은 그대로다.

**실행 근거:** 새 테스트 5개 추가 후 39개 중 4개가 부모 static 오탐으로 실패했다. 수정 후 39개가 모두 통과했고, 최종 build에서 구조 121개를 실행해 실패·오류·skip 0을 확인했다. 실제 포트 5개·공개 계약 11개의 결과도 유지됐다. 다른 모듈은 UP-TO-DATE 결과를 재사용했으며, XML 합계 364개 전부를 이번에 다시 실행한 것은 아니다.

### 앞선 수정 · 타입 변수의 선언 범위 보존

[이번 P3 리뷰](https://github.com/0Chord/coin-exchange/pull/28#discussion_r4110576124)는 **같은 이름을 쓰는 서로 다른 타입 변수**를 검사기가 혼동한 문제다. 클래스에서 `U extends T`를 정했다면, 메서드가 별도의 `T`를 만들어도 U의 의미는 바뀌지 않는다. 이전 검사는 두 T를 이름으로 합쳐 반환값의 JDBC 노출을 빠뜨렸다. 기존 클래스 상한 진단은 남아 있어 포트 전체의 거절은 유지됐다.

흐름: **클래스의 타입 관계를 클래스 안에서 먼저 해석 → 메서드가 직접 선언한 변수는 메서드 기준으로 해석 → 클래스 변수를 만나면 보존한 결과 사용 → 각 반환·인자·상한 위치에 위반 보고.** 메서드 변수 이름만 T에서 V로 바꾸어도 같은 계약의 결과가 달라지면 안 된다.

| 새 회귀 테스트의 입력 | 기대값과 이유 |
| --- | --- |
| 클래스 `T → JDBC 소유 타입`, `U → T`; 메서드 `<T> U load()`와 `<V> U load()` | 두 예제 모두 클래스 T·U와 반환값의 3곳을 보고. 메서드 변수의 이름이 클래스 U의 의미를 바꾸지 않음 |
| 위 클래스에서 `<T, V extends U> V exchange(U value)` | 클래스 T·U, 메서드 V 상한·인자·반환의 5곳 보고. 여러 단계를 거쳐도 클래스 상한의 의미 유지 |
| 위 클래스에서 메서드가 자신의 T를 반환. T는 무제한 또는 `Comparable<T>` | 클래스 T·U의 2곳만 보고. 메서드 T에 클래스 T의 JDBC 제약을 붙이지 않으며 재귀도 종료 |
| 클래스 T·U는 일반 값, 메서드 T에만 JDBC 상한 | 메서드 T 상한의 1곳만 보고. 클래스 U 반환을 JDBC 노출로 오인하지 않음 |

구현은 [원본 선언 보완 코드](#source-bytecode)의 `visibleTypeReferences`와 `methodBounds`를 구분한다. [테스트](#source-tests)는 정확한 선언·노출 자리·타입·이유·개수를 확인하고, [Java 예제](#source-java-fixtures)의 컴파일된 선언을 reflection으로 대조한다. 검사기의 결과에서 기대값을 생성하지 않는다.

새 테스트 4개를 추가한 실행에서 **34개 중 3개 실패 → 수정 후 34개 통과**를 확인했다. 실패는 각각 반환 누락, 인자·상한·반환 누락, 정상 반환값 오탐이었다. 나머지 1개는 기존 동작을 보존하는 대조 검사다. 최초 테스트의 타입 추론 컴파일 오류는 수정했으며 위 Red에 포함하지 않았다.

**이전 `92f6371` 검증:** 전체 build 성공. 구조 검사 116개를 실행했고 실패·오류·skip은 0이다. 운영 포트 5개·공개 계약 11개의 준비 오류·위반도 0이다. 다른 모듈은 UP-TO-DATE로 기존 결과를 재사용했으며, XML 합계 359개를 이번에 모두 재실행했다는 뜻은 아니다.

### 앞선 리뷰에서 보완한 내용

[독립 리뷰](https://github.com/0Chord/coin-exchange/pull/28#pullrequestreview-5324994963)가 기존 테스트에서 빠진 공개 계약 두 종류를 찾았다. 제품 실행 코드는 변경하지 않으며 검사기가 읽는 선언 정보를 보완한다.

| 입력 → 판단 → 결과 | 변경 이유 | 확인할 근거 |
| --- | --- | --- |
| `Owner<Connection>.Member<String>` → 원본 Signature의 바깥·안쪽 타입 인자를 읽음 → Connection 위반 | 이전에는 Member와 String만 남아 잘못 통과 | [원본 보완](#source-bytecode), PORT-08 소유 타입 회귀 |
| 외부 상위의 공개 중첩 인터페이스 → 원본 InnerClasses에서 포함 관계 확인 → 같은 디렉터리/JAR의 클래스 파일을 읽고 JDBC 계약 보고 | 이전에는 운영 출력 목록 안에서만 중첩 클래스를 찾았음 | [계약 순회](#source-reader), [외부 예제](#source-external-fixtures), PORT-11 외부·JAR 회귀 |
| 필요한 중첩 타입의 파일 누락 → 내부는 수집 대상 확인, 외부는 원본 위치 확인 → 준비 실패·미평가 | 일부 계약만 읽고 정상으로 통과시키지 않음 | PORT-13 내부/외부 누락 회귀 |

일반 값인 `Owner<String>.Member<Integer>`와 외부 중첩 계약의 String 반환은 통과한다. 메서드가 직접 선언한 변수는 메서드 상한을 사용한다. 클래스 변수의 상한은 클래스 선언 범위에서 해석한 결과를 유지하며, 순환 상한은 탐색을 종료한다. 다른 클래스의 필드나 메서드 본문을 추적하는 기능은 추가하지 않는다.

`PortContractBytecode`는 기존 JDK 25 API로 `.class`의 선언을 읽는다. 객체 생성·클래스 초기화·메서드 실행을 하지 않는다. 추가 외부 라이브러리 의존성도 없다. 원본을 읽거나 파싱할 수 없으면 기존 준비 실패 경로로 전달한다. 기술 정책과 진단 중복 제거는 기존 규칙을 재사용한다.

이전 `2255b9e`의 테스트 근거: 소유 타입 보완 전 24개 중 2개가 누락으로 실패했고 보완 후 24개가 통과했다. 외부 중첩·누락 사례 보완 전 28개 중 4개가 실패했고 보완 후 28개가 통과했다. JAR 사례를 포함한 구조 전체 111개가 통과했고, 원본 삭제·손상 사례 추가 후 최종 build에서 구조 112개가 통과했다. 실패·오류·skip은 0이다. 변경 없는 제품 테스트는 기존 결과를 UP-TO-DATE로 재사용했다.

**확정된 명세:** 상위 인터페이스 static은 자식 계약에서 제외한다. 포트 자신과 공개 중첩 타입이 직접 선언한 static, 상속한 일반/default 메서드는 검사한다. 독립 리뷰는 `2255b9e`에서 위 P3을 발견했다. 이후 수정 코드의 독립 재리뷰·사람의 검토는 아직이다.


</details>

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
4. 상속 계약을 읽는 중 필요한 내부 정의가 운영 출력에 없거나 외부 정의가 미해석이면 역시 미평가한다. 공개 중첩 정의도 원본에서 확인하므로 운영 출력이나 외부 파일 누락을 놓치지 않는다. 클래스패스에서 찾은 내부 클래스가 운영 출력 누락을 숨기게 하지 않는다.

예: 포트 목록에는 `BalanceStore`가 있는데 수집 결과에 없으면 실패한다. `BalanceStore`를 빼고 나머지만 검사해서 성공하는 선택은 하지 않는다. 같은 클래스가 여러 모듈에 배정된 입력도 준비 문제다.

근거: [준비·판정 코드](#source-gate), [실제 운영 연결](#source-production). 테스트: `PortContractRuleTest`의 PORT-12·13. 입력 해석 과정의 예상 밖 RuntimeException도 `CONTRACT_READ_FAILURE`로 막는다. 원본 파일 삭제와 잘린 클래스 파일을 별도 사례로 검사하며, 모든 손상 바이트코드 예외를 전수 재현한 것은 아니다.

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
| inner에서 사용하는 바깥 타입 변수 | 실제 포함 관계와 static 경계를 따라 바깥 변수부터 해석 | 각 사용 자리의 기술 노출 보고. 필요한 바깥 정의가 없으면 미평가 |
| 상위 인터페이스 static | 자식에게 상속되지 않으므로 메서드 계약 추출 전 제외 | 자식 위반·계약 수에 넣지 않음. 포트 자신의 static은 포함 |
| public 중첩·companion 계약 | 실제 포함 관계와 public 접근 수준을 따라 읽음 | 같은 루트 포트 아래 노출로 보고 |
| private 메서드·기본 메서드의 본문 | 공개 시그니처 범위에 넣지 않음 | 이 검사가 본문 I/O를 보장하지 않음을 유지 |

공개 멤버·인자에 직접 붙은 기술 어노테이션과 바이트코드의 throws 타입도 읽는다. KDoc의 문장, 어노테이션 속성 값·메타어노테이션, Kotlin metadata만의 표현은 분석하지 않는다.

근거: [계약 추출 코드](#source-reader), [원본 Signature·중첩 선언 보완](#source-bytecode), [Kotlin 예제](#source-fixtures), [Java 예제](#source-java-fixtures). PORT-05–09·11·13 테스트가 배열·프로퍼티·상속·상한·어노테이션과 누락을 연결한다. Java 예제는 Kotlin에서 직접 쓰기 어려운 와일드카드·다중 상한·브리지를 실제 컴파일하기 위해 사용한다.

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

기대값은 명세의 구체적인 허용/금지 사례에서 적었다. 검사기 결과로 예상값을 만들거나 구현의 정책 목록을 복사하지 않았다. 기대값 검토와 구현 검증은 이번 작업을 수행한 같은 AI가 했으며, `3a93ac7`에서 누락 두 건, `2255b9e`에서 클래스·메서드 이름 가림, `9406277`에서 바깥·inner 타입 변수 연결 누락을 독립 리뷰가 발견했다. 이번 보완의 기대값 검토·구현 검증은 작성 AI가 수행했으며 수정 코드의 독립 재리뷰는 아직이다.

## 최초 구현 실행 기록 · 3a93ac7

- 1단위 Red: 7개 중 6개 실패. 미구현 검사기가 금지 타입·준비 문제·계약 수를 반환하지 못했다. 예제 전제가 확인된 뒤 기대 결과가 없었던 실패다.
- 1단위 Green: 새 7개 + 기존 ARCH-01 23개 = 30개 통과.
- 2단위 초기 실패 중 한 건은 테스트가 메서드 이름을 잘못 잘라낸 오류였다. 이를 수정한 Red는 19개 중 9개 실패. 프로퍼티는 이미 직접 시그니처 검사로 통과했다.
- 2단위 Green: 19개 통과. 중간 Optional 변환 컴파일 오류는 수정했고 행동 수준의 Red로 세지 않는다.
- 구조 전체 첫 실행: 101개 통과, 실패·오류·skip 0. 이때 P03의 포트 5개·계약 11개를 확인했다.
- 보완 리뷰: 외부 상위 계약 허용·중복 상속·실제 JVM 브리지 사례 추가. 21개 중 1개가 브리지 중복 보고로 실패했고 중복 기준을 수정했다.
- 보완 후 ARCH-06 테스트 21개 통과. 최종 전체 빌드는 **346개 통과·실패 0·오류 0·skip 0**이며 구조 검사 **103개**를 포함한다. 현재 보완의 해시·명령은 검증 기록의 최상위에, 이전 실행 근거들은 previousVerification 이력에 보존했다.

```sh
./gradlew build --no-daemon --console=plain --continue --rerun-tasks
```

최초 구현의 전체 빌드에서 app-api 75개, architecture-tests 103개, domain-fee 37개, domain-ledger 16개, domain-matching 65개, domain-order 50개를 실행했다. 독립 구조 검사와 전체 빌드를 구분하며, 전체 빌드의 기존 Testcontainers 테스트는 실행 가능한 Docker 환경에서 확인했다.

보고서: `architecture-tests/build/reports/tests/test/index.html` 및 각 모듈의 `build/test-results/test/*.xml`. 이 기록은 로컬 실행 결과이며 원격 CI·독립 리뷰 완료를 뜻하지 않는다. 검증 기록의 `/tmp` 로그 경로는 로컬 보조 자료이며 저장소에 게시한 로그가 아니다.

## 변경 파일의 역할

| 역할 | 파일 | 연결된 흐름 |
| --- | --- | --- |
| 개발·검증 도구 | PortContractIndependence.kt / PortContractReader.kt | 준비·추출·판정·보고 |
| 원본 선언 보완 | PortContractBytecode.kt | 소유 타입 인자·중첩 선언·준비 실패 |
| 기존 검사 지원 | ExternalTechnologyTypes.kt / DomainTechnologyIndependence.kt | 기존 기술 정책 재사용, ARCH-01 보존 |
| 운영 검사 대상 설정 | ProductionScope.kt | 포트 등록 재사용·영속 모델 등록 |
| 실제 준수 테스트 | ProductionArchitectureTest.kt | P03 연결, P01·P02와 독립 실행 |
| 검사기 테스트 | PortContractRuleTest.kt | 정상·위반·누락·중복·한계 |
| 테스트용 예제 | PortFixtures.kt / JavaPortFixtures.java / ExternalPortContracts.java | 의도한 컴파일 구조. 제품 클래스가 아님 |
| 테스트 빌드 | architecture-tests/build.gradle.kts | 실제 트랜잭션·직렬화 타입의 테스트 의존성 |
| 명세·설명·근거 | architecture-check-spec.md / architecture-06-review.md / architecture-06-verification.json | 합의·실제 흐름·검증 결과 연결 |
| 로컬 표시 도구 | 기존 reader/build.py·template.html·생성 index.html | 접힌 소스·색상·줄 번호, 이전 ARCH-02 기록 분리 |

제품 실행 코드 변경은 없다. 이번 변경 파일은 위 역할에 모두 연결한다. 원본 파일 삭제·손상 실패와 소스에서만 검토한 다른 방어 분기를 구분했으며, 모든 JVM 조합을 실행 검증했다는 뜻은 아니다.

## 근거 코드 펼치기

GitHub에서는 아래 저장소 파일 링크로 근거를 읽는다. 로컬 HTML은 생성 시점의 실제 코드를 펼쳐 보여주므로 소스가 바뀌면 다시 생성해야 한다.

<!-- ARCH06_SOURCES_START -->

| 근거 | 저장소 파일 |
| --- | --- |
| <a id="source-gate"></a>준비·판정 | [PortContractIndependence.kt](../architecture-tests/src/test/kotlin/com/exchange/architecture/rules/PortContractIndependence.kt) |
| <a id="source-reader"></a>공개 계약 추출 | [PortContractReader.kt](../architecture-tests/src/test/kotlin/com/exchange/architecture/rules/PortContractReader.kt) |
| <a id="source-bytecode"></a>원본 선언 보완 | [PortContractBytecode.kt](../architecture-tests/src/test/kotlin/com/exchange/architecture/rules/PortContractBytecode.kt) |
| <a id="source-external-fixtures"></a>외부 계약 예제 | [ExternalPortContracts.java](../architecture-tests/src/test/java/com/exchange/architecture/fixtures/externalports/ExternalPortContracts.java) |
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
