# #60 A · 주문 상태·잔량의 영속 단건 조회 기반

테스트가 준비한 주문 상태를 조회 전용 PostgreSQL에 보존하고 단건 GET으로 읽는다. 3개 중 1개 체결 후 잔량 2개를 취소하면 체결 1·대기 0·취소 2이며, 반환 확인 전에는 `PENDING(RELEASE)`다. 종료 주문도 같은 DB를 다시 열어 조회할 수 있다.

기준은 `b2579e2f99447783b35b00a4f64cd8f31a469e4f` 위 브랜치 `feat/order-query-foundation/60`의 구현이다. 실제 작업 폴더는 `/Users/0chord/.codex/worktrees/issue148-http-aeron/exchange-core`다. 아래 설명은 새 테스트 5개 파일의 41개 메서드와 공통 DB 준비 코드를 모두 읽은 결과다. 반복문 속 입력은 별도 테스트 수로 더하지 않았다.

기능은 기본 비활성이다. `aeron-bounded-gateway`와 `exchange.order-query.enabled=true`를 함께 사용해야 조회 설정과 GET 경로를 조립한다. A에는 실제 Aeron 기록을 수집·재전송하는 연결이 없으며, 실제 거래→DB→GET 제공과 활성화는 다음 B의 범위다. 기존 POST/ACK·자금/매칭 소유권은 유지한다. 인증·인가와 성능 검증은 포함하지 않는다.

새 조회 범위는 INITIALIZING으로 시작한다. A에는 제품 HTTP 쓰기·준비 완료 제어가 없고, 테스트만 내부 저장 포트로 기록과 READY를 준비한다. 설정은 `exchange.order-query.` 아래 `jdbc-url`, `username`, `password`, `funds-lineage-high/low`, `supported-markets`(쉼표 구분)로 공급한다.

## 보존한 규칙과 새 조회 경계

| 원래 규칙·근거 | 조회 모델에 적용한 검사와 근거 |
| --- | --- |
| [Price/Quantity](../domain-common/src/main/kotlin/com/exchange/core/common/ScaledNumbers.kt), [LIMIT/GTC 입력](../domain-trading/src/main/kotlin/com/exchange/core/trading/MatchingInputValidation.kt) | [OrderQueryRecord](../app-api/src/main/kotlin/com/exchange/core/api/trading/application/OrderQueryModel.kt#L40)는 양수 가격·최초 수량, 음수 없는 최소 단위 정수, LIMIT/GTC를 검사한다. 마켓별 tick/step·금액 계산은 다시 수행하지 않는다. |
| [기존 주문 ID 생성](../domain-trading/src/main/kotlin/com/exchange/core/trading/TradingRequest.kt), [funds 결과 문맥](../domain-trading/src/main/kotlin/com/exchange/core/trading/BoundedFundingState.kt) | funds lineage와 양수 전역 접수 순번으로 ID를 생성한다. DB 읽기는 저장 ID·순번과 생성 결과를 대조한다. 전역 주문/접수 순번 및 마켓별 접수 순번은 DB 유일 제약으로 지킨다. |
| [MatchingResultStream](../domain-trading/src/main/kotlin/com/exchange/core/trading/MatchingIntakeValues.kt), [주문·금전 구분](gtc-recovery-contract.md) | 같은 stream의 마지막 관련 결과와 금융 적용 순번을 비교한다. 미확정 매칭의 대기 수량은 null이다. 금융 상태·사유는 별도 입력으로 받지 않고 위치와 주문 상태에서 도출한다. 옛 RDB 실행·ACK 흐름은 가져오지 않는다. |
| 확인한 결과와 종료 수량은 되돌릴 수 없음 | [requireSuccessorOf](../app-api/src/main/kotlin/com/exchange/core/api/trading/application/OrderQueryModel.kt#L101)는 원 주문 변경·수량 감소·순번 역행·stream 교체·종료 후 활성화를 거절한다. 같은 결과 참조의 수량/상태 변경도 거절하며, 종료 뒤에는 금융 확인만 전진한다. |
| 조회는 거래 기록의 파생본 | 새 포트와 [PostgreSQL 저장소](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/persistence/PostgresOrderQueryStore.kt)는 매칭·원장 계산 없이 완결된 행을 받는다. 합계가 맞아도 RESTING+filled>0 등 상태 모순은 거절한다. |

저장은 namespace 행을 잠근 뒤 같은 batch ID의 정규화 내용을 먼저 대조한다. 동일 재전송은 최초 revision을 반환한다. 새 batch는 expected revision·관찰 범위·접수 prefix·주문 이행을 검사하고 모든 주문 행, batch 기록, 진행 위치를 한 트랜잭션으로 커밋한다. 행 목록 순서는 중복 내용 판정에 영향을 주지 않는다.

조회는 주문과 진행 위치를 한 SQL 관찰 시점에서 읽는다. 입력/지원 마켓 → 읽기 실패·모순 → 준비 여부 → 요청한 최소 접수 prefix → 행 존재 → 지연 중 부재 → 관찰 기준까지의 부재 순서로 판단한다. 따라서 행이 있어도 미준비는 503, 최소 prefix 미반영은 202다. `CAUGHT_UP`은 마지막 관찰 기준까지 반영됐다는 뜻이다.

## 테스트 지도와 Given·When·Then

모든 표의 위치 링크는 해당 메서드로 이어진다. 수량 기대값은 3→체결1/잔량2 등의 고정 예에서 정했다. ID 기대값은 기존 `TradingIdentifiers`를 재사용하므로 ID 인코딩 자체의 독립 검사는 아니다.

### 순수 모델·유즈케이스: 13개

유즈케이스의 `StubStore`는 지정한 snapshot/예외를 반환하고 읽기·쓰기 횟수를 센다. DB나 HTTP를 실행하지 않는다.

| 위치·확인할 동작 | Given — 준비 | When — 실행 | Then — 실제 검증 |
| --- | --- | --- | --- |
| [M · 매칭/금융 구분](../app-api/src/test/kotlin/com/exchange/core/api/trading/application/OrderQueryModelTest.kt#L18) | 3개 주문의 여섯 상태 | 금융 적용 위치를 0→7 변경 | 미확정 잔량 null, 취소 1/0/2, 미실행 0/0/3, 상태별 사유와 APPLIED의 null 사유 |
| [M · 상태별 수량](../app-api/src/test/kotlin/com/exchange/core/api/trading/application/OrderQueryModelTest.kt#L46) | 합계만 맞는 상태, 음수·0가격·큰 수량·MARKET/IOC | 레코드 생성 | 각 입력에서 IllegalArgumentException |
| [M · 결과 문맥](../app-api/src/test/kotlin/com/exchange/core/api/trading/application/OrderQueryModelTest.kt#L68) | 누락/음수 위치, 다른 funds/마켓 stream | 레코드 생성 | 잘못된 참조 거절 |
| [M · 체결→취소→반환](../app-api/src/test/kotlin/com/exchange/core/api/trading/application/OrderQueryModelTest.kt#L80) | 대기3, 이후 체결1·취소2 | 후속 결과와 금융 확인을 차례로 검사 | 새 체결은 다시 PENDING 가능, 반환은 APPLIED, 부활·수량/금융 역행·종료 결과 변경 거절 |
| [M · 같은 결과와 원 정보](../app-api/src/test/kotlin/com/exchange/core/api/trading/application/OrderQueryModelTest.kt#L110) | 대기 상태와 동일/후속 결과 | 같은 결과로 부분 체결, 원 정보·위치 변경 | 같은 결과의 사실 변경 거절, 후속 결과의 부분 체결 허용, 원 정보/stream/위치 변경 거절 |
| [M · 범위·입력 보존](../app-api/src/test/kotlin/com/exchange/core/api/trading/application/OrderQueryModelTest.kt#L129) | 가변 마켓·행 목록 | 생성 뒤 원 목록 변경, 잘못된 batch/progress 생성 | 저장 입력 복사본 유지, 범위·prefix·중복·revision 모순 거절, freshness 구분 |
| [U · 발견과 지연](../app-api/src/test/kotlin/com/exchange/core/api/trading/application/OrderQueryUseCaseTest.kt#L20) | READY의 기존 행 | 관찰 revision을 늘려 두 번 GET | 같은 행 반환, CAUGHT_UP→LAGGING, 읽기2·쓰기0 |
| [U · 최소 접수 위치](../app-api/src/test/kotlin/com/exchange/core/api/trading/application/OrderQueryUseCaseTest.kt#L36) | 행 존재·covered1 | 최소2로 조회 | CATCHING_UP, 확정 행 없음, 기준1 |
| [U · 기준부 부재](../app-api/src/test/kotlin/com/exchange/core/api/trading/application/OrderQueryUseCaseTest.kt#L45) | 행 없음 | 지연/반영 완료, 다른 lineage ID 조회 | 지연은 CATCHING_UP, 완료는 NOT_FOUND_AS_OF와 기준 위치 |
| [U · 준비 우선](../app-api/src/test/kotlin/com/exchange/core/api/trading/application/OrderQueryUseCaseTest.kt#L59) | 빈 DB 또는 행이 남은 INITIALIZING/BLOCKED | 최소9로 조회 | NOT_READY, 확정 행 없음 |
| [U · 실패/모순](../app-api/src/test/kotlin/com/exchange/core/api/trading/application/OrderQueryUseCaseTest.kt#L72) | 읽기 예외·손상 변환·범위/행/prefix 모순 | 조회 | UNAVAILABLE 또는 INCONSISTENT이며 404/입력400으로 치환하지 않음 |
| [U · 진행 정보 누락](../app-api/src/test/kotlin/com/exchange/core/api/trading/application/OrderQueryUseCaseTest.kt#L92) | 진행 정보 없는 행, 이어 완전히 빈 DB | 각각 조회 | 전자는 INCONSISTENT, 후자는 NOT_READY |
| [U · 입력 우선](../app-api/src/test/kotlin/com/exchange/core/api/trading/application/OrderQueryUseCaseTest.kt#L103) | DB 실패 대역·잘못된 마켓/ID/최소값 | 조회 | 입력 거절, 읽기0·쓰기0 |

### 실제 PostgreSQL 저장: 14개

클래스가 PostgreSQL 17 컨테이너를 시작하고 전용 Flyway migration을 실행한다. 각 테스트는 다른 funds lineage를 사용한다. `rawState`는 주문·batch·진행 테이블의 실제 행을 비교하며 새 DataSource로 같은 DB를 다시 연다.

| 위치·확인할 동작 | Given — 준비 | When — 실행 | Then — 실제 검증 |
| --- | --- | --- | --- |
| [P68 · 종료 보존](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/persistence/PostgresOrderQueryStoreTest.kt#L68) | 접수3 | 부분체결1→취소2→반환→재개방→다른 주문→최초 batch 재시도 | 3/1/0/2/0·APPLIED·진행 위치·종료 행 보존, 최초 revision1 재사용 |
| [P104 · 두 주문 체결](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/persistence/PostgresOrderQueryStoreTest.kt#L104) | maker3 대기, taker1 | 두 행 batch→maker 추가2 체결→재개방 | maker1/2·taker1/0·revision2, 최종 maker 전량체결 레코드 일치 |
| [P131 · 미실행 반환](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/persistence/PostgresOrderQueryStoreTest.kt#L131) | NOT_EXECUTED3 | 저장 후 금융 적용 | 취소0·미실행3·RELEASE→APPLIED |
| [P146 · 중복/충돌](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/persistence/PostgresOrderQueryStoreTest.kt#L146) | 두 행의 최초 batch | 행 순서 변경 재시도→내용 변경→후속 batch→최초 재시도 | 최초 revision·시각·전체 행 유지, 변경 내용 충돌, 후속 revision 유지 |
| [P167 · 동시 저장](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/persistence/PostgresOrderQueryStoreTest.kt#L167) | 같은 expected0의 두 writer | 두 latch로 준비/시작을 맞춰 병렬 apply | 성공1·충돌1, revision1·주문1·batch1 |
| [P199 · 유일성](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/persistence/PostgresOrderQueryStoreTest.kt#L199) | 기존 전역 주문·마켓 순번 | 다른 마켓에 재연결, 마켓 순번 중복 저장 | 충돌, 먼저 저장하려던 행까지 미반영, 전체 DB 값 유지 |
| [P219 · 실제 SQL 실패](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/persistence/PostgresOrderQueryStoreTest.kt#L219) | 두 번째 행을 거절할 임시 DB CHECK | 첫 행 변경 후 두 번째 행 SQL 실패 | 충돌, 첫 행·다른 행·batch·진행 위치 전부 원래 값 |
| [P246 · 저장 데이터 손상](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/persistence/PostgresOrderQueryStoreTest.kt#L246) | JSON 수량/타입/주문방식/파생 필드/필수 필드, 저장 ID 손상 | 실제 SQL로 변경 후 read | 모든 사례가 INCONSISTENT 예외 |
| [P277 · 연결 실패](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/persistence/PostgresOrderQueryStoreTest.kt#L277) | 닫힌 DB 포트와 별도 자격정보 | read | UNAVAILABLE, 예외 cause와 메시지에 자격정보 노출 없음 |
| [P287 · 명시적 재준비](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/persistence/PostgresOrderQueryStoreTest.kt#L287) | INITIALIZING→저장→BLOCKED | initialize·직접 READY·batch·다른 마켓 설정, 이후 INITIALIZING→READY | 기존 값 보존, 차단 중 갱신/직접 READY 거절, 설정 불일치 거절, 명시적 재준비 후 READY |
| [P307 · 전역 prefix/체결 지연](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/persistence/PostgresOrderQueryStoreTest.kt#L307) | 두 마켓 접수1/2 | 관찰 전진→접수 없는 체결 batch→관찰 역행 | covered2 유지, CAUGHT_UP→LAGGING→CAUGHT_UP, 역행 무변경 충돌 |
| [P328 · 원 정보/부활 거절](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/persistence/PostgresOrderQueryStoreTest.kt#L328) | 대기 후 전량체결 | 가격·마켓순번 변경, 부활·prefix 역행 | 각 충돌 뒤 전체 DB 값 유지 |
| [P353 · 읽기와 값 바인딩](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/persistence/PostgresOrderQueryStoreTest.kt#L353) | 주문1 | 반복 read와 SQL 모양의 ID/마켓 조회 | 원 주문 동일, SQL 모양 입력은 부재, DB 값·행 수 유지 |
| [P367 · 길이 경계](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/persistence/PostgresOrderQueryStoreTest.kt#L367) | 마켓·사용자·batch ID 256자 | 실제 저장/조회, batch ID 257자 생성 | 256자 보존, 257자 거절·revision1 유지 |

### 테스트 Spring 조립·실제 HTTP: 14개

[OrderQueryTestDatabase](../app-api/src/test/kotlin/com/exchange/core/support/OrderQueryTestDatabase.kt)는 실제 PostgreSQL과 테스트 설정을 제공한다. 설정 테스트는 매번 조회 schema를 지우고, HTTP 테스트는 제품 조회 Controller/설정과 실제 서버를 임의 포트에 조립한 뒤 테이블을 비우고 내부 저장 포트로 기록을 준비한다. 기존 주문 gateway는 Mockito 대역이다. 제품 main/Aeron 클러스터를 실행한 거래 통합 검증은 아니다.

| 위치·확인할 동작 | Given — 준비 | When — 실행 | Then — 실제 검증 |
| --- | --- | --- | --- |
| [C · 기본/명시 비활성](../app-api/src/test/kotlin/com/exchange/core/api/config/OrderQueryConfigurationTest.kt#L59) | 잘못된 DB·lineage 설정, enabled 누락/false | 설정 context 조립 | Controller·UseCase·Store·DB·Flyway·JDBC·트랜잭션 bean 및 조회 schema 없음 |
| [C · 활성 조립](../app-api/src/test/kotlin/com/exchange/core/api/config/OrderQueryConfigurationTest.kt#L86) | 올바른 전용 DB 설정 | context 조립·초기 GET 유즈케이스 | 전용 bean 각1, 조회 테이블4, public 테이블0, legacy funding service 없음, NOT_READY |
| [C · 제품 메인의 자동 설정](../app-api/src/test/kotlin/com/exchange/core/api/config/OrderQueryConfigurationTest.kt#L109) | 제품 main의 실제 제외 목록·패키지와 조회 DB | 자동 설정을 포함해 context 시작 | 조회 DataSource만 존재하며 JPA 저장소·EntityManager·기존 금융 writer 없음 |
| [C · 조립 실패](../app-api/src/test/kotlin/com/exchange/core/api/config/OrderQueryConfigurationTest.kt#L124) | lineage 누락/연결 실패/기존 unmanaged schema | 각각 조립 | BeansException, unmanaged 테이블에 행 없음 |
| [C · 다른 프로필](../app-api/src/test/kotlin/com/exchange/core/api/config/OrderQueryConfigurationTest.kt#L138) | enabled=true·다른 프로필 | 조립 | 조회 Controller·DB 없음 |
| [H · 여섯 상태 응답](../app-api/src/test/kotlin/com/exchange/core/api/trading/api/OrderQueryHttpTest.kt#L78) | 3개 주문의 여섯 상태·READY | 각 HTTP GET | 200·ID/원 정보/수량/상태/금융 사유/결과 참조/기준 위치/freshness, 시각 해석 가능 |
| [H · 반환 완료](../app-api/src/test/kotlin/com/exchange/core/api/trading/api/OrderQueryHttpTest.kt#L143) | 취소2·미실행3 | GET→금융 위치 적용→각 GET | PENDING(RELEASE)→APPLIED·사유null, 종료 수량 보존 |
| [H · 준비/최소값 우선](../app-api/src/test/kotlin/com/exchange/core/api/trading/api/OrderQueryHttpTest.kt#L162) | 기존 행·미준비 | READY/최소값/BLOCKED/메타데이터 없는 DB를 차례로 GET | 503→202→200→503, 준비 전 부재도503, 미확정 응답에 상태/ID 없음 |
| [H · 지연/부재](../app-api/src/test/kotlin/com/exchange/core/api/trading/api/OrderQueryHttpTest.kt#L177) | READY 행·관찰만 전진 | 기존/없는 주문 GET→빈 batch 반영→없는 주문 GET | 발견은200 LAGGING, 지연 부재202, 반영 후404 AS_OF·기준 위치, 최소값 미달202 |
| [H · 기존400 유지](../app-api/src/test/kotlin/com/exchange/core/api/trading/api/OrderQueryHttpTest.kt#L196) | 음수/문자/overflow 최소값·잘못된 ID/마켓·SQL 모양 경로 | GET들과 잘못된 기존 POST | 모두 동일 INVALID_INPUT/INVALID_REQUEST 400 본문, 주문 gateway 호출 없음 |
| [H · DB 오류 우선순위](../app-api/src/test/kotlin/com/exchange/core/api/trading/api/OrderQueryHttpTest.kt#L224) | 실제 손상 JSON, 이어 주문 테이블 이름 변경 | 각각 GET·미지원 마켓 GET | 손상503 INCONSISTENT·SQL 실패503 UNAVAILABLE, 입력 오류는 먼저400 |
| [H · 조회 범위](../app-api/src/test/kotlin/com/exchange/core/api/trading/api/OrderQueryHttpTest.kt#L243) | 한 마켓/lineage의 종료 행 | 다른 지원 마켓·다른 lineage ID GET | 404 AS_OF, 다른 주문 노출 없음 |
| [H · 반복/재개방](../app-api/src/test/kotlin/com/exchange/core/api/trading/api/OrderQueryHttpTest.kt#L253) | 반환 완료 취소 행 | 반복 GET→context 닫기/재개방→GET | 응답과 모든 조회 DB 행 동일, 주문 gateway 호출 없음 |
| [H · 비활성 route](../app-api/src/test/kotlin/com/exchange/core/api/trading/api/OrderQueryHttpTest.kt#L269) | enabled 누락·잘못된 DB 설정 | 별도 HTTP context 시작·GET | 404, 조회 Controller·DataSource 없음 |

## 실행 근거와 남은 확인

2026-11-17 실행 XML은 새 41개와 기존 HTTP 12개를 합쳐 **53개·실패0·오류0·skip0**이다. [로컬 HTML 보고서](../app-api/build/reports/tests/test/index.html)와 `app-api/build/test-results/test/TEST-*.xml`을 대조했다. 이 문서 작성 과정에서는 테스트를 다시 실행하지 않았다.

```sh
cd /Users/0chord/.codex/worktrees/issue148-http-aeron/exchange-core
JAVA_HOME=/Users/0chord/Library/Java/JavaVirtualMachines/corretto-25.0.2/Contents/Home ./gradlew --no-daemon --max-workers=1 :app-api:test --tests '*OrderQuery*Test' --tests '*BoundedTradingOrderHttpTest' --tests '*TradingOrderHttpTest'
```

전체 구조 검사 386개가 실패0·오류0·skip0으로 통과했고, 필수 운영 검사 P01~P09 실행도 확인했다. 독립 리뷰의 JPA 자동 조립 지적은 제품 main의 JPA repository 자동 설정 제외와 실제 main 설정을 사용하는 회귀 테스트로 해소했다. ARCH-06 등록 누락은 새 포트를 등록하고 검사 결과의 포트 목록 포함을 검증해 해소했다. 마지막 변경 후 P01~P09와 경계 회귀 44개도 실패0·오류0·skip0으로 재검증했다. 전체 스타일 검사와 최종 구조 스타일 검사도 통과했다. 원격 CI·사람의 검토·커밋·PR 게시·#60 전체 완료를 뜻하지 않는다. B에서는 실제 권위 기록의 누락 없는 연결·보존/재개·커버리지 증명과 실제 거래→GET을 검증해야 한다.

구조 등록은 조회 포트/저장 폴더와 공개 조회 자료형에 한정했다. 검사 제외나 패키지 전체 허용을 추가하지 않았다. 새 포트는 `application/port/OrderQueryStore.kt`에 두고 ARCH-06의 기술 독립 계약 검사에도 등록했다.

독립 리뷰는 작성 대화를 상속하지 않은 `/root/review60_a_independent`가 고정 사본을 읽고 수행했다. 검토 대상은 `/tmp/issue60-a-review-20261117-v3`의 22개 변경 파일이며, 코드·설정·테스트는 전달된 작업 폴더와 SHA-256이 일치한다. v1 전체 검토와 v2/v3 변경분 재검토를 합쳐 범위 안의 확인된 미해결 결함은 없다. 리뷰어는 조회 41개·기존 HTTP 12개 및 최종 구조 44개 XML을 직접 대조했고, 별도 모델/reader/JPA 반례도 실행했다. 이는 향후 모든 결함이 없다는 보장이나 사용자 이해·승인을 뜻하지 않는다. 이 문서의 최종 실행/리뷰 근거 문장만 v3 검토 이후 갱신했으며 제품 코드·설정·테스트는 바뀌지 않았다.

전체 구조·스타일 실행 로그는 로컬 `daemon-73750.out.log`(2026-11-17, 3분1초 성공), 최종 포트 등록 후 구조 재검증은 `daemon-74601.out.log`(1분10초 성공)에 있다. 위치는 `/Users/0chord/.gradle/daemon/9.5.1`이다. 최종 구조 XML은 재검증한 44개로 갱신돼 앞선 전체 386개 결과와 구분한다.

```sh
cd /Users/0chord/.codex/worktrees/issue148-http-aeron/exchange-core
JAVA_HOME=/Users/0chord/Library/Java/JavaVirtualMachines/corretto-25.0.2/Contents/Home ./gradlew --no-daemon --max-workers=1 ktlintCheck :architecture-tests:verifyArchitectureReport
```

## 커밋별 리뷰 순서

1. `97d2bcf` — 상태·수량·금융 처리 구분과 저장 포트, 순수 테스트 및 구조 검사 등록.
2. `3a9bb73` — PostgreSQL 저장·중복 판별·전체 롤백과 실제 DB 테스트.
3. `4b4d47f` — 기본 비활성 조립·단건 GET·HTTP/설정 회귀 테스트.
4. 이 문서 — 테스트별 준비·실행·검증문과 실행/독립 리뷰 근거.

게시할 제품 코드·설정·테스트는 `4b4d47f`에 모두 포함되며 고정 v3 리뷰 대상과 내용이 같다. 이 문서의 상태 표현·서식·커밋 읽기 순서만 게시 준비 중 보완했다. 동일 코드의 유효한 실행 근거를 재사용하며 새 기능이나 검증하지 않은 실제 거래 연결을 게시 범위에 더하지 않는다.
