# DB 설정을 읽은 뒤, BTC와 SUI 주문은 같은 메모리 잔고를 쓴다

**DB는 BTC·SUI의 거래 규칙을 알려준다. 돈은 P1 자금 준비 명령이 각 노드의 메모리에 만든다.** U1에게 1,000 USDT를 준비하고 BTC 주문에 600 USDT를 예약하면, 다음 SUI 주문은 사용 가능 금액 400 USDT로 판단해야 한다.

이번 #116은 이 기존 제품 동작을 실제 PostgreSQL과 세 노드 컨테이너로 연결해 검사한다. **제품 main과 자금 엔진 코드는 바꾸지 않는다.** 추가한 것은 고정 입력·독립 기대값·실행 및 비교 장치다. 실제 DB·세 노드 검사와 독립 리뷰까지 마쳤다. 첫 화면은 전체 흐름이며, 코드·테스트·실행 근거는 아래에서 필요한 부분을 펼쳐 볼 수 있다.

```text
고정 BTC/SUI 문서 → DB에 버전 V로 등록
    → 각 노드 시작: V 조회 · 원문 지문 H 검사 · 설정을 메모리에 보관
    → 세 READY 확인: BTC/SUI 전체 설정 + V/H + 노드 격리
    → P1부터 여섯 명령을 하나씩 전송
    → 각 노드의 메모리에서 U1·USDT 하나를 갱신
    → 매 단계 응답과 세 사본을 독립 정답과 비교
    → 종료 후 전체 적용 기록 재확인 · 기록 보존 · DB와 컨테이너 정리
```

아래는 사람이 읽는 USDT 단위다. 승인 순번은 새 주문이 승인될 때만 증가한다.

| 보낸 명령 | 응답 | 현재 사용 가능 USDT | 현재 예약 USDT | 마지막 승인 순번 |
| --- | --- | ---: | ---: | ---: |
| P1: 1,000 준비 | 준비 완료 | 1,000 | 0 | 0 |
| R-BTC: BTC 6개 × 100 | 승인 | 400 | 600 | 1 |
| R-SUI: SUI 25개 × 20 | 필요 금액 500, 부족 금액 100으로 거절 | 400 | 600 | 1 |
| 같은 R-SUI 재전송 | 최초 거절 그대로 | 400 | 600 | 1 |
| 새 R-SUI-2: SUI 20개 × 20 | 승인 | 0 | 1,000 | 2 |
| 처음 R-SUI 재전송 | 최초 거절 그대로 | 0 | 1,000 | 2 |

마지막 응답의 **당시 사용 가능 금액은 400, 필요 금액은 500, 부족 금액은 100 USDT**다. 현재 사용 가능 금액 0으로 거절 내용을 다시 계산하지 않는다. 최종 대기 주문은 BTC 한 건과 SUI 한 건이며, 아직 매칭·체결한 거래는 없다.

<details markdown="1">
<summary>1. DB에 있는 설정과 노드 메모리에 있는 돈은 어떻게 연결되는가</summary>

| 보관 장소 | 보관하는 것 | 읽거나 바꾸는 시점 |
| --- | --- | --- |
| PostgreSQL `market_configuration_release` | 버전 하나의 BTC/SUI 자산, 수량 단위, 수수료 정책 | 노드·클라이언트 시작 시 지정 버전 조회 |
| 각 노드의 설정 메모리 | 검증한 전체 마켓 목록과 V/H | 주문이 고른 마켓과 정책을 확인할 때 읽음 |
| 각 노드의 자금 메모리 | U1·USDT 잔고, 예약, 대기 주문, 최초 요청 결과, 전표 | P1과 주문 명령을 처리할 때 읽고 바꿈 |

테스트가 [실제 PostgreSQL을 시작하고 기존 Flyway 마이그레이션을 실행한다][database-start]. [고정 원문][fixture-document]을 한 행으로 등록해 얻은 V와, 그 원문의 UTF-8 바이트로 계산한 H를 노드와 클라이언트에 지정한다. H는 실제 READY에서 가져온 정답이 아니다. [기대 마켓 목록][fixture-settings]도 제품 파서나 실제 출력으로 만들지 않는다.

BTC는 수량 소수 8자리·수수료 정책 버전 1, SUI는 수량 소수 9자리·수수료 정책 버전 2다. 둘 다 USDT로 가격을 표시하고 maker/taker 수수료율은 0이다. **설정 문서의 버전 V와 마켓별 수수료 정책 버전 1·2는 다른 값**이다.

기존 제품 [시작 로더][product-settings]는 지정 V를 읽고 H를 검사한 뒤 `DevelopmentMarketRegistry`를 반환한다. [JDBC 연결은 `use` 범위를 벗어나면서 닫힌다][product-connection]. 이 시점에 돈을 넣는 코드는 없다. [노드는 빈 초기 잔고로 `OrderAdmissionState`를 만든다][product-empty-state]. P1을 처리해야 사용 가능 금액 1,000 USDT가 생긴다.

테스트 실행기는 노드 JVM을 시작하기 전에 전용 bridge를 만들고, PostgreSQL을 `market-db`라는 이름으로 연결한다. 호스트의 문서 등록 연결과 컨테이너의 조회 주소를 구분한다. [DB 연결 준비][runner-connect]와 [세 READY 비교][runner-ready]가 끝나기 전에는 P1도 보내지 않는다.

세 노드의 메모리는 각각 따로 있다. 각 노드 안에서 BTC와 SUI가 같은 사용자 잔고를 공유하고, Aeron이 정한 같은 명령 순서를 처리한 세 사본의 결과를 비교하는 구조다. BTC 노드와 SUI 노드로 역할을 나누는 실행은 아니다.

새 Docker 사례는 마지막 클라이언트가 시작할 때까지 DB를 유지한다. 매번 새로 시작하는 클라이언트도 설정을 조회하기 때문이다. 주문 처리 중 추가 DB 조회가 없는 설명은 아래 기존 제품 호출 흐름에 근거한다. 이 사례 자체가 DB를 중간에 끊어 그 성질을 증명하는 테스트는 아니다.

</details>

<details markdown="1">
<summary>2. 같은 USDT를 찾는 판단과, 최초 거절을 돌려주는 판단</summary>

현재 판단을 이해할 핵심은 두 군데다. 아래 발췌는 이번에 새로 만든 알고리즘이 아니라, 이번 Docker 검사가 실행하는 기존 제품 코드다.

**잔고는 사용자와 자산으로 찾는다.** [잔고 키][product-balance-key]에 마켓 ID가 없다.

```kotlin
private data class BalanceKey(
    val userId: UserId,
    val assetId: AssetId,
)
```

매수의 예약 자산은 해당 마켓의 표시 자산이다. [주문 준비 경로][product-prepare]에서 BTC-USDT와 SUI-USDT 모두 `BalanceKey(U1, USDT)`를 찾는다. BTC 주문 승인 후 그 키의 사용 가능 금액은 400, 예약 금액은 600 USDT다. SUI 25개를 사려면 500 USDT가 필요하므로 첫 요청은 거절된다. [거절 처리][product-reject]는 최초 요청 결과를 기억하고 잔고·예약 전표를 추가하지 않는다.

**같은 요청은 최초 결과를 먼저 찾는다.** [요청 재전송 판단][product-replay]은 새 예약 계산 전에 다음 분기를 실행한다.

```kotlin
val key = RequestKey(order.userId, requestId)
val previous = requestRecords[key]
if (previous != null) {
    return if (previous.order == order) previous.result else Result.Rejected(OrderAdmissionResult.Reason.REQUEST_CONTENT_CONFLICT)
}
```

그래서 SUI 20개가 새 요청으로 승인된 뒤에도 R-SUI의 최초 거절은 바뀌지 않는다. 새 요청 R-SUI-2는 이 분기에 걸리지 않아 현재 사용 가능 금액 400 USDT로 판단하고 승인 순번 2를 받는다. 어댑터는 [새 요청의 마켓·정책을 시작 때 읽은 설정과 대조하고][product-adapter], 이어 같은 상태 객체의 `admit`을 호출한다.

두 번의 R-SUI 재전송은 요청 ID뿐 아니라 주문·원장 ID·시각을 포함한 입력 전체가 같다. 테스트의 출력 파일명만 서로 다르다. [여섯 단계 정의][fixture-steps]는 처음 거절 단계에서 입력과 결과를 복사하고, 마지막 재전송에만 최종 현재 상태를 기대값으로 지정한다.

과거 승인 후보도 현재 잔고와 구분한다. BTC 후보에는 이전 사용 가능 1,000·예약 0, 다음 사용 가능 400·예약 600이 남는다. SUI400 후보에는 이전 사용 가능 400·예약 600, 다음 사용 가능 0·예약 1,000이 남는다. [독립 기대 후보][fixture-record]는 이 숫자를 별도로 조립한다.

| 최종 확인 대상 | 정확한 기대값 |
| --- | --- |
| 잔고 목록 | U1·USDT 한 항목, 사용 가능 0·예약 1,000 |
| 대기 주문 | BTC/O-BTC 순번 1 → SUI/O-SUI-2 순번 2 |
| 최초 요청 | R-BTC 승인 → R-SUI 부족 거절 → R-SUI-2 승인 |
| 실제 전표 | OPENING 1건 + BTC RESERVE 1건 + SUI400 RESERVE 1건 |
| 거절 주문 | 최초 요청 원본은 보존, 활성 예약·실제 RESERVE 전표는 없음 |

원시값은 USDT 소수 6자리, BTC 수량 8자리, SUI 수량 9자리를 사용한다. 자금 준비 원장 ID는 `OPENING-1`, 주문 원장 ID는 `ledger-R-BTC`, `ledger-R-SUI`, `ledger-R-SUI-2`다. 자금 준비 시각은 `2026-10-22T00:00:00Z`이고 주문 시각은 `2026-10-22T00:01:00.123456789Z`다. [기대 시각][fixture-instant]은 문자열뿐 아니라 epochSecond/nano도 고정한다. 거절 입력의 `ledger-R-SUI`는 최초 요청 기록에만 존재한다.

</details>

<details markdown="1">
<summary>3. 실제 제품 실행과 테스트 장치가 각각 무엇을 하는가</summary>

| 구분 | 역할 | 근거 |
| --- | --- | --- |
| 기존 제품 실행 코드 | 시작 설정 조회, 명령 복원, 메모리 자금 판단, 결과 출력 | [시작 로더][product-settings], [노드 상태 생성][product-empty-state], [어댑터][product-adapter], [상태 판단][product-prepare] |
| 테스트 DB 준비 | PostgreSQL·Flyway 시작, 문서 등록, 컨테이너 주소 제공, 정리 | [MarketConfigurationDatabase][database-start] |
| 테스트 실행기 | 이미지 재사용, DB bridge 연결, 세 READY 확인, 순차 전송, 종료·기록 수집 | [DockerFundingVerification.run][runner-run] |
| 테스트 입력·정답 | 고정 두 마켓 문서, 여섯 입력, 각 단계의 전체 기대 상태 | [MultiMarketOrderReplicationScenario][fixture-steps] |
| 검사기 | READY 설정, 응답, 노드별 전체 의미 필드, 전체 적용 위치를 대조 | [READY 비교][readiness-comparison], [적용 위치 비교][coverage-comparison] |
| 검사기 예제 테스트 | 관찰 JSON을 의도적으로 바꿔 검사기가 오류를 놓치지 않는지 확인 | 아래 Given/When/Then 표 |
| 진단 자료 보호 | Docker 출력에서 환경을 제외하고, 보고서에 비밀번호가 남았는지 검사 | [출력 필드 선택][inspect-helper], [전체 파일 검사][leak-helper] |

`OrderReplicationScenario`는 새 사례를 기존 반복 실행 목록에 넣고 ORDER 단계에 마켓을 명시한다. `ReplayedOrderScenarios`와 `ConcurrentOrderReplicationScenario`의 변경은 기존 BTC 단계의 마켓 명시다. 이 두 파일에서 재전송·동시 주문의 제품 정책을 바꾸지 않는다.

실행기는 [한 단계의 응답과 새 적용 위치를 확인한 뒤에만 다음 단계로 진행한다][runner-inputs]. 여기서 적용 위치는 Aeron의 바이트 위치이므로 `L0 < L1 < … < L5`이면 되고 1씩 증가할 필요는 없다. 승인 순번의 최종값 2와 여섯 적용 위치는 다른 개념이다.

마지막 수집에서는 [모든 APPLIED 관찰을 전송 당시 확정한 위치에 다시 대응시킨다][runner-recheck]. 같은 요청 R-SUI가 세 위치에 나타나는 것은 정상이다. 같은 위치에서 응답 시도 진단만 PENDING/OFFERED/NOT_LEADER로 달라지는 것도 허용한다. 그 위치의 입력·업무 상태까지 달라지면 실패다. 계획 밖 위치·입력은 정상 여섯 단계 뒤에 붙어도 실패한다.

테스트 스레드는 제품 `OrderAdmissionState`를 직접 읽거나 갱신하지 않는다. 기존 불변 관찰 자료를 수집해 대조한다. 여기서 상태 사본은 관찰용이며 디스크 복구용 스냅샷을 추가한 것이 아니다.

| 문제가 발견되는 때 | 남기는 판단 | 이후 처리 |
| --- | --- | --- |
| DB 준비·연결·버전·지문·READY | SETUP | P1 전 중단하고 소유한 자원 정리 |
| 입력 뒤 응답·새 관찰이 확인되지 않음 | UNVERIFIABLE | 다음 입력을 보내지 않고 수집·정리 |
| 읽은 입력·상태가 정답과 다름 | MISMATCH | 차이를 보존하고 다음 입력 중단 |
| 기록 복사 실패 | COLLECTION | 기존 차이와 함께 보존 |
| 프로세스·DB·네트워크 정리 실패 | CLEANUP | 성공으로 처리하지 않음 |

[기록 복사 뒤 DB를 bridge에서 분리·종료하고 Compose를 내린다][runner-cleanup]. 부분 기동 실패에서도 `finally`가 DB 정리를 호출한다. 기록 볼륨은 기존 정책대로 보존한다. 시간 초과는 미접수나 예약 반환으로 해석하지 않는다.

Docker 조회는 필요한 필드만 Docker CLI 단계에서 출력한다. 이후 JSON에서 비밀번호를 지우는 방식이면 이미 `command-N.stdout.log`에 남을 수 있기 때문이다. 실제 #116 실행은 고유한 DB 비밀번호를 사용하고 [최종 보고서 작성 뒤 전체 파일을 검사한다][runner-secret-check]. 빠른 검사와 실제 실행 근거는 아래에서 구분한다.

</details>

<details markdown="1">
<summary>4. 새로 추가되거나 영향받은 테스트를 준비·실행·확인으로 읽기</summary>

**Given은 준비, When은 실행, Then은 실제 검증문이다.** 관찰 JSON을 바꾸는 빠른 테스트는 제품을 고장 내거나 실제 노드를 실행하지 않는다. 잘못된 자료를 검사기가 거절하면 그 테스트는 통과한다. 아래의 “확인”은 소스가 확인하도록 작성된 내용이며, 이번 변경의 실제 실행 결과는 다음 절에 따로 남긴다.

### 실제 DB·세 노드 실행

| 테스트/사례 | Given — 준비 | When — 실행 | Then — 실제 확인 |
| --- | --- | --- | --- |
| 기존 Docker 주문 반복에 `MULTI_MARKET_SHARED_FUNDS` 추가 | 실제 PostgreSQL, 고정 BTC/SUI 문서, 독립 컨테이너·JVM·기록 경로 세 개 | READY를 통과한 뒤 표의 여섯 명령을 순서대로 보냄. 매 단계 및 종료 후 재대조 | [최상위 `assertTrue(failures.isEmpty())`][docker-assert]가 각 실행의 문제 유무를 확인. 내부 비교가 전체 상태·위치를, [비밀번호 `check`][runner-secret-check]가 보고서 유출 부재를 확인 |
| DB 등록의 실제 잠금 제한시간 | 실제 PostgreSQL의 설정 테이블을 별도 연결로 배타 잠금 | 다른 연결에서 고정 문서 등록 시도 | [등록 시도 구간이 12초 안에 끝나고 SQLException 발생][database-lock-timeout], [SQLState `57014`][database-lock-cancel] 확인. 단순 테스트 시간 초과가 아니라 DB의 문장 취소를 구분 |
| 등록 실패 뒤 0행·재등록 | 위 잠금으로 첫 등록이 취소된 DB | 잠금을 rollback하고 테이블 행 수 조회 후 같은 문서 재등록 | [남은 행이 0개][database-empty], [다음 등록 버전이 2][database-retry]인지 확인 |
| 실패를 검사한 DB fixture 정리 | 위 등록 실패·재등록 검사를 마친 fixture의 컨테이너 ID | `database.use`를 빠져나온 뒤 종료된 컨테이너까지 포함해 Docker 목록 조회 | [그 ID의 컨테이너가 없다는 `assertFalse`][database-cleanup] 확인 |

DB 잠금·0행·정리의 세 행은 `MarketConfigurationDatabaseTest` 한 메서드의 서로 다른 검증 지점이다. 12초 검사는 등록 호출 구간에 적용하며 DB 기동 전체 시간의 상한으로 해석하지 않는다.

Docker 주문 JUnit 테스트 메서드 하나가 enum의 여러 시나리오와 기존 동시 시나리오를 반복한다. “테스트 메서드 하나”를 “클러스터 실행 하나”로 세지 않는다. 이번에 추가하는 실제 세 노드 사례는 순차 공유 자금 사례 하나다.

### 시작 설정 비교

| 테스트/사례 | Given — 준비 | When — 실행 | Then — 실제 확인 |
| --- | --- | --- | --- |
| 지정 V/H 정상 | 기대 버전 7·고정 지문, BTC/SUI READY 세 개 | `compareFundingReadiness` 호출 | [문제 목록이 비었는지][ready-ok] 확인 |
| V/H 누락·null·오류 | 두 필드 각각에 세 종류 손상을 세 노드 모두 동일하게 적용 | 같은 독립 기대 V/H로 비교 | [해당 설정 필드의 SETUP 문제 수가 3인지][ready-bad] 확인. 노드끼리 같다는 이유로 통과할 수 없음 |
| 설정 버전의 숫자 범위 | 기대 V와 세 READY의 V가 `Long.MAX_VALUE`인 정상 자료 | 먼저 정상 비교, 이어 세 READY의 V를 `9223372036854775808`로 변경 | [정상 최댓값은 문제 없음][ready-number-max], [범위 초과는 SETUP 3개][ready-number-range] 확인 |
| 자금 금액·순번의 숫자 범위 | 정상 자금 준비 관찰 세 개 | node2의 사용 가능 금액 또는 마지막 순번을 `9223372036854775808`·`-9223372036854775809`로 변경 | [필드명과 실제 큰 정수를 포함한 MISMATCH][funding-number-range] 확인. 숫자 변환 예외로 검사 중단을 허용하지 않음 |
| 자금 적용 위치 손상 | 정상 P1 관찰 | 리더 위치를 누락·null·문자열·소수·0·음수·Long 범위 밖 정수로 변경 | [적용 위치의 UNVERIFIABLE][funding-position] 확인 |
| 기존 자금 비교의 진단 갱신 자료 조립 수정 | 정상 세 사본에 같은 적용 위치의 추가 응답 진단 행 | 자금 비교 실행 | [성공 여부][funding-diagnostic] 확인. 목록 결합을 명시적으로 해 진단 행 하나를 추가 |

기존 READY 테스트가 이미 다루는 SUI 누락·전체 목록·스케일·정책 오류는 [목록 반례][ready-list]와 [동일한 잘못된 필드 반례][ready-fields]로 연결된다. DB 버전 미존재·해시 불일치·조회 제한시간은 기존 `PostgresDevelopmentMarketSettingsTest`의 회귀 대상이며, 이 기존 검사들도 수정 후 집중 회귀 실행에 포함해 통과했다.

### 주문 결과와 전체 상태 비교

아래 주문 반례의 실패 검증은 공통 [assertIssue][order-assert-issue]에서 `succeeded == false`와 해당 분류·메시지의 존재를 함께 확인한다. 오류가 정확히 하나만 존재한다고 검사하는 것은 아니다. 필드별 변형은 [assertMutations][order-assert-mutations]가 실제 기대값·변경값 경로와 연결한다.

| 테스트/사례 | Given — 준비 | When — 실행 | Then — 실제 확인 |
| --- | --- | --- | --- |
| SUI 식별자 오류 | SUI400 승인 단계의 정상 응답·세 관찰 | 노드 또는 클라이언트의 마켓을 BTC로 변경하거나 삭제 | [각각 node2/client의 marketId MISMATCH][order-market] |
| 주문 금액·순번의 숫자 범위 | SUI400 승인 단계의 정상 관찰·응답 | 예약액·마지막 순번·승인 순번을 Long 최댓값보다 1 큰 정수 또는 최솟값보다 1 작은 정수로 변경 | [노드 관찰의 MISMATCH][order-number-range]와 [클라이언트 응답의 MISMATCH][order-response-number-range]에 실제 큰 정수가 남는지 확인 |
| 정상 최댓값 대조 | 금액·순번 기대값은 `Long.MAX_VALUE`, 관찰 JSON은 같은 값의 BigInteger 정수 표현 | `compareOrderFields` 직접 호출 | [문제 목록이 비어 있음][order-number-max] 확인. 큰 정수를 일괄 거절하는 검사가 아님 |
| 주문 적용 위치 손상 | 정상 주문 관찰 세 개 | 리더 또는 팔로워 위치를 누락·null·문자열·소수·0·음수·Long 범위 밖 정수로 변경 | [적용 위치의 UNVERIFIABLE][order-position] 확인 |
| 잔고를 마켓별로 분리 | 최종 사용 가능 0·예약 1,000인 정상 상태 | BTC 예약 600, SUI 예약 400인 두 잔고 항목으로 변경 | [잔고 개수 expected=1 차이][order-balance] |
| 순번·순서·예약 문맥 오류 | BTC 순번 1, SUI 순번 2인 최종 상태 | 순번·마켓·수량·스케일·정책·예약액·원장 연결을 하나씩 변경하고 대기 목록도 뒤집음 | [각 의미 필드 차이][order-context]와 [대기 순서 차이][order-order] |
| 최초 거절·과거 후보 덮어쓰기 | 현재 사용 가능 0인 마지막 재전송 | 최초 사용 가능 400을 0으로, BTC 과거 후보를 현재 상태로 변경 | [각 필드 차이 검출][order-history]. 세 노드가 모두 같은 오답이면 [멤버별 MISMATCH][order-history-all] 확인 |
| 거절 이력 삭제·부수 효과 추가 | SUI 첫 거절의 재전송 단계 | 최초 요청 삭제 또는 대기 예약·전표 항목 추가 | [requests 개수 차이][order-rejection]와 [pendingOrders/transactions 개수 차이][order-rejection-effects] |
| 기존 전체 시나리오 정상 반복에 새 사례 포함 | 각 시나리오 각 단계의 고정 기대값으로 만든 관찰 | 기존 반복이 새 여섯 단계까지 비교 | [성공 및 선택한 위치 일치][order-all] |
| 기존 단계명 고유성 반복에 새 사례 포함 | 각 시나리오 단계 이름 | 소문자 이름을 집합으로 모음 | [단계 수와 이름 수 일치][order-names]. 같은 R-SUI 재전송도 서로 다른 출력 파일명 사용 |

### 종료 후 전체 적용 위치 비교

아래 자료는 여섯 명령에 서로 다른 증가 위치를 직접 붙인 JSON 예제다. 실제 Aeron 위치를 만들어내는 테스트는 아니다. 공통 [assertIssue][coverage-assert-issue]는 지정 분류·메시지를 포함한 문제가 있는지 검사한다.

| 테스트/사례 | Given — 준비 | When — 실행 | Then — 실제 확인 |
| --- | --- | --- | --- |
| 여섯 위치·재전송·진단 갱신 정상 | R-SUI가 포함된 여섯 checkpoint와 세 멤버 관찰 | 마지막 위치에 응답 진단 갱신 행을 추가 | [checkpoint 6개·R-SUI 세 번][coverage-count]과 [문제 목록 비어 있음][coverage-ok] |
| 계획 밖 추가 위치 | 정상 전체 관찰 | 같은 identity 또는 다른 identity로 위치 9999를 추가 | [계획 밖 위치 UNVERIFIABLE, 다른 identity이면 MISMATCH도 존재][coverage-extra] |
| 같은 위치의 모순 | 정상 전체 관찰 | 정상 행 뒤에 마켓 오류나 예약액 오류 행을 추가 | [각 필드 MISMATCH][coverage-conflict] |
| 같은 위치의 숫자 범위 초과·기존 차이 보존 | 정상 여섯 checkpoint와 전체 관찰 | 마지막 위치에 잘못된 마켓, Long 범위 밖 예약액·마지막 순번이 함께 있는 행 추가 | [마켓·예약액·순번 세 MISMATCH가 모두 남음][coverage-number-range] 확인. 큰 정수 변환 때문에 앞선 차이나 종료 재검사가 사라지지 않음 |
| 마지막 사본 누락 | 이전 SUI 관찰이 있는 상태 | node2의 마지막 관찰만 제거 | [새 동일 위치 관찰 누락][coverage-missing] |
| checkpoint 누락·중복·역순·빈 목록 | 정상 관찰은 유지 | checkpoint만 각각 훼손 | [checkpoint 근거 부족][coverage-checkpoint] |
| 위치 손상·관찰 실패 | 정상 여섯 단계 유지 | null·문자열·소수·0·음수·Long 범위 밖 위치, 위치 누락 또는 관찰 큐 초과 ERROR 추가 | [손상된 위치][coverage-malformed], [누락된 위치][coverage-no-position], [큐 초과][coverage-error] 각각 UNVERIFIABLE 확인 |
| 이번 업무와 무관한 행 | 정상 전체 관찰 | 이전 runId 기록과 READY 행 추가 | [문제 목록이 비어 있음][coverage-unrelated] |

### 마켓 식별자와 보고서

| 테스트/사례 | Given — 준비 | When — 실행 | Then — 실제 확인 |
| --- | --- | --- | --- |
| 마켓별 identity | 같은 사용자·요청·주문 ID | 마켓만 SUI로 바꾸고 전송 단계명도 바꿔봄 | [마켓이 다르면 identity가 다르고, 단계명만 바뀌면 같음][identity-market] |
| ORDER의 마켓 누락 | 정상 주문 단계 | 마켓을 null·빈 문자열·공백으로 변경 | [IllegalArgumentException][identity-empty] |
| FUNDING의 identity | P1 단계 | 마켓 없는 자금 준비 identity 조회 | [marketId null과 preparationId=P1][identity-funding] |
| Docker 출력 필드 선택 | 컨테이너 ID 한 개 | 조회 명령 목록 생성 | [필수 진단 필드 집합·배열 JSON 형태·Config/Env/Args/Path 배제][inspection-fields]. 실제 Docker 실행은 하지 않는 빠른 검사 |
| 세 보고서 경로의 유출 | 원본 stdout, inspect JSON, result JSON에 고정 비밀 문자열 삽입 | 전체 파일 검사 | [정확한 세 경로 반환, 반환 문자열에는 비밀 없음][inspection-leaks] |
| 바이너리·읽기 경계 | 하위 바이너리 파일의 64KiB 경계를 가로질러 UTF-8 비밀 삽입 | 전체 파일 검사 | [해당 파일 검출][inspection-boundary] |
| 안전한 자료·빈 입력 | 진단 JSON과 빈 파일 | 없는 비밀 및 빈 비밀로 검사 | [유출 경로 없음, 빈 비밀은 예외][inspection-clean] |

</details>

<details markdown="1">
<summary>5. 실제 실행 결과와 독립 리뷰</summary>

**전체 검사를 통과한 뒤 독립 리뷰에서 확인한 두 경계를 보완했고, 영향받는 검사를 다시 실행했다.** 제품 `src/main`, 빌드 설정, CI workflow는 변경하지 않았다.

| 항목 | 확인한 결과 |
| --- | --- |
| 작업 브랜치·기준 | `test/multi-market-replication/116` · 통합 `32509c1ed03bb463cb26cb1def58472a93ff582f` |
| 전체 검사 | `build test ktlintCheck :architecture-tests:verifyArchitectureReport` 성공. 1,186개, 실패·오류·생략 0, 6분 7초 |
| 리뷰 수정 후 검사 | 영향받는 app-api 126개 + 구조 검사 375개, 합계 501개 모두 통과. 전체 lint·필수 구조 보고서도 통과. 3분 28초 |
| 실제 Docker 실행 | 기존 사례 8개 + 새 BTC/SUI 사례 1개, 모두 `success=true`, `cleanStop=true`. 수정 후 9개 모두 재실행 |
| 원격 검증 | 이 표는 로컬 실행 근거다. 게시한 커밋의 CI 결과는 PR Checks에서 별도로 확인한다. |

전체 검사 1,186개는 리뷰 보완 전의 실행이다. 보완 뒤에는 바뀐 경로와 관련 회귀를 검사했으며, 이를 “최종 소스 전체 1,186개 재실행”으로 표시하지 않는다. Docker 사례 수는 JUnit 메서드 수와 다르다. 실행 시간은 로컬 검증 소요 시간이고 거래 처리 지연 측정값이 아니다.

**실제 공유 자금 실행 근거**

- runId: `funding1792658908362b07cf82d`
- DB 설정 버전: `1`, 원문 SHA-256: `ad7f03a451841c9d62d0e98581ea7aeff35b1b310e44d23c72404473d50baa3a`
- 세 노드 READY가 각각 이 버전·지문과 전체 BTC/SUI 설정을 충족했다.
- 여섯 적용 위치: `352 → 768 → 1184 → 1600 → 2016 → 2432`. 각 위치에서 세 멤버의 입력·상태를 대조했다.
- 최종 세 사본: U1·USDT 사용 가능 0·예약 1,000, 대기 주문 2건, 최초 요청 3건, 승인 순번 2, OPENING 1건 + RESERVE 2건.
- 마지막 R-SUI 응답은 최초 거절의 사용 가능 400·필요 500·부족 100 USDT를 유지했다.
- 전체 보고서 파일의 실행별 DB 비밀번호 검사도 통과했다. 원문 비밀번호는 문서에 남기지 않는다.

로컬 상세 보고서는 `app-api/build/reports/aeron-funding/funding1792658908362b07cf82d/result.json`이다. 재실행하면 새 runId 아래 보고서가 생성된다. 원격 실행의 보고서는 기존 CI의 `test-reports` artifact에서 확인한다. 생성 보고서·컨테이너 기록·개인 실행 로그는 소스에 포함하지 않는다.

**검증 명령**

로컬에서 실행한 검사 인자다. 아래 `EXCHANGE_CORE`를 이 저장소 체크아웃의 절대 경로로 바꾸면 같은 검사 범위를 실행할 수 있다.

```bash
EXCHANGE_CORE=/absolute/path/to/exchange-core
"$EXCHANGE_CORE/gradlew" -p "$EXCHANGE_CORE" build test ktlintCheck :architecture-tests:verifyArchitectureReport --no-daemon --console=plain --continue
```

리뷰 수정 후에는 같은 폴더에서 다음 범위를 실행했다.

```bash
EXCHANGE_CORE=/absolute/path/to/exchange-core
"$EXCHANGE_CORE/gradlew" -p "$EXCHANGE_CORE" :app-api:test \
  --tests '*FundingReplicationComparisonTest' \
  --tests '*OrderReplicationComparisonTest' \
  --tests '*OrderReplicationCoverageTest' \
  --tests '*OrderReplicationStepIdentityTest' \
  --tests '*DockerInspectionReportTest' \
  --tests '*MarketConfigurationDatabaseTest' \
  --tests '*PostgresDevelopmentMarketSettingsTest' \
  --tests '*DockerFundingReplicationTest' \
  --tests '*DockerOrderReplicationTest' \
  --tests '*DevelopmentMarketMainTest' \
  --tests '*DevelopmentMultiMarketOrderNodeTest' \
  --tests '*ConcurrentOrderReplicationComparisonTest' \
  --tests '*FundingVerificationCollectorTest' \
  ktlintCheck :architecture-tests:verifyArchitectureReport \
  --no-daemon --console=plain --continue
```

**독립 리뷰와 보완**

작성과 분리한 리뷰어가 고정 사본에서 코드·명세·테스트를 검토했다. 최종 검증·재리뷰 대상의 `app-api` Git tree는 `83aa13650d78cd0fff1c187c12508a9d8a3ffa4a`다. 게시할 16개 변경 코드 파일과 해당 사본의 바이트가 같음을 대조했으며, 이후 수정은 이 설명 문서에 한정된다.

| 확인한 문제 | 수정 및 검증 근거 |
| --- | --- |
| DB 설정 등록이 잠금에 막히면 준비 단계가 오래 대기할 수 있음 | JDBC/Flyway 연결·조회와 SQL 대기 시간을 제한했다. 실제 PostgreSQL의 배타 잠금으로 수정 전 시간 초과 실패를 재현했다. 수정 후 SQL 취소, 등록 행 0건, 잠금 해제 뒤 새 등록 성공, 컨테이너 종료를 확인했다. |
| 관찰 숫자가 Long 범위를 넘으면 비교기가 예외를 던져 최종 보고가 끊길 수 있음 | 공통 숫자 비교에서 정수·Long 변환 범위를 먼저 검사한다. 수정 전 예외를 일으킨 반례 4개를 확인했고, 수정 후 SETUP/MISMATCH로 기록한다. Long 최댓값의 정상 대조도 유지했다. |

독립 재리뷰에서도 두 지적이 해소됐음을 확인했다. 리뷰어는 실제 DB 잠금 취소(SQLSTATE `57014`), 등록 행 부재·후속 정상 등록·정리와 숫자 범위 반례를 직접 재현하고, 관련 97개 검사를 실행해 실패·오류·생략 0건을 확인했다. 최초 전체 리뷰와 변경분 재리뷰의 범위 안에 확인된 미해결 결함은 없다. 리뷰어는 세 노드 Docker 9개와 전체 구조 검사를 중복 실행하지 않았으며, 이 부분은 위의 구현자 실행 기록으로 구분한다.

이번 검증이 다루지 않는 범위는 동시 BTC/SUI 전송, 매칭·체결·정산·취소, 동적 설정 교체, 장애 전환·재시작 복구, 여러 호스트의 장애 독립성이다. 또한 새 사례는 DB를 중간에 끊는 장애 실험이 아니다.

</details>

[fixture-document]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MultiMarketOrderReplicationScenario.kt#L4
[fixture-settings]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MultiMarketOrderReplicationScenario.kt#L12
[fixture-steps]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MultiMarketOrderReplicationScenario.kt#L15
[fixture-record]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MultiMarketOrderReplicationScenario.kt#L116
[fixture-instant]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MultiMarketOrderReplicationScenario.kt#L226
[database-start]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MarketConfigurationDatabase.kt#L32
[product-settings]: ../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentMarketSettings.kt#L48
[product-connection]: ../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentMarketSettings.kt#L111
[product-empty-state]: ../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingNode.kt#L112
[product-balance-key]: ../domain-trading/src/main/kotlin/com/exchange/core/trading/OrderAdmissionState.kt#L27
[product-prepare]: ../domain-trading/src/main/kotlin/com/exchange/core/trading/OrderAdmissionState.kt#L237
[product-reject]: ../domain-trading/src/main/kotlin/com/exchange/core/trading/OrderAdmissionState.kt#L273
[product-replay]: ../domain-trading/src/main/kotlin/com/exchange/core/trading/OrderAdmissionState.kt#L178
[product-adapter]: ../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingAdapter.kt#L169
[runner-run]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerFundingReplicationTest.kt#L82
[runner-connect]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerFundingReplicationTest.kt#L176
[runner-ready]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerFundingReplicationTest.kt#L126
[runner-inputs]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerFundingReplicationTest.kt#L287
[runner-recheck]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerFundingReplicationTest.kt#L481
[runner-cleanup]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerFundingReplicationTest.kt#L738
[runner-secret-check]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerFundingReplicationTest.kt#L169
[readiness-comparison]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/FundingReplicationComparison.kt#L121
[coverage-comparison]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparison.kt#L7
[inspect-helper]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerInspectionReport.kt#L7
[leak-helper]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerInspectionReport.kt#L19
[docker-assert]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerOrderReplicationTest.kt#L20
[ready-ok]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/FundingReplicationComparisonTest.kt#L67
[ready-bad]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/FundingReplicationComparisonTest.kt#L106
[funding-diagnostic]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/FundingReplicationComparisonTest.kt#L201
[ready-list]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/FundingReplicationComparisonTest.kt#L134
[ready-fields]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/FundingReplicationComparisonTest.kt#L173
[order-assert-issue]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L828
[order-assert-mutations]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L815
[order-market]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L73
[order-balance]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L98
[order-context]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L104
[order-history]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L132
[order-rejection]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L158
[order-all]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L224
[order-names]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L756
[coverage-assert-issue]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationCoverageTest.kt#L143
[coverage-ok]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationCoverageTest.kt#L44
[coverage-extra]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationCoverageTest.kt#L55
[coverage-conflict]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationCoverageTest.kt#L67
[coverage-missing]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationCoverageTest.kt#L75
[coverage-checkpoint]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationCoverageTest.kt#L85
[coverage-malformed]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationCoverageTest.kt#L96
[coverage-unrelated]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationCoverageTest.kt#L110
[identity-market]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationStepIdentityTest.kt#L14
[identity-empty]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationStepIdentityTest.kt#L24
[identity-funding]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationStepIdentityTest.kt#L32
[inspection-fields]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerInspectionReportTest.kt#L21
[inspection-leaks]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerInspectionReportTest.kt#L45
[inspection-boundary]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerInspectionReportTest.kt#L56
[inspection-clean]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerInspectionReportTest.kt#L67
[database-lock-timeout]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MarketConfigurationDatabaseTest.kt#L22
[database-lock-cancel]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MarketConfigurationDatabaseTest.kt#L24
[database-empty]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MarketConfigurationDatabaseTest.kt#L34
[database-retry]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MarketConfigurationDatabaseTest.kt#L39
[database-cleanup]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MarketConfigurationDatabaseTest.kt#L41
[ready-number-max]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/FundingReplicationComparisonTest.kt#L34
[ready-number-range]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/FundingReplicationComparisonTest.kt#L46
[funding-number-range]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/FundingReplicationComparisonTest.kt#L19
[funding-position]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/FundingReplicationComparisonTest.kt#L58
[order-number-range]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L22
[order-response-number-range]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L30
[order-number-max]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L47
[order-position]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L61
[order-order]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L126
[order-history-all]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L145
[order-rejection-effects]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationComparisonTest.kt#L164
[coverage-count]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationCoverageTest.kt#L35
[coverage-number-range]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationCoverageTest.kt#L27
[coverage-no-position]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationCoverageTest.kt#L99
[coverage-error]: ../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/OrderReplicationCoverageTest.kt#L101
