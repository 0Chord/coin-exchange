# #106 · 한 번 보낸 자금이 세 노드에 반영됐는지 확인하기

상태: **로컬 구현·검증 완료 / 독립 리뷰 완료**. 기준 통합은 `1a351aa`다. 이 문서의 실행 기록은 게시 전 로컬 검증이며, 최신 원격 CI 결과는 PR의 Checks에서 확인한다.

이번에 읽을 것은 새 자금 계산이 아니라 **기존 개발 노드를 세 Docker 컨테이너로 연결하고, 실제 복제 결과를 확인하는 흐름**이다. 실행 폴더는 `/Users/0chord/Desktop/exchange-core-aeron-106`, 브랜치는 `test/aeron-container-funding/106`이다.

## 먼저 이 흐름 하나를 읽는다

1. 같은 Java 실행 이미지로 node0·node1·node2를 띄운다. 각 컨테이너의 JVM·메모리·Driver·Archive·기록 볼륨은 따로 있다.
2. 세 멤버가 같은 목록을 사용해 리더 하나·팔로워 둘을 선출한다. 실제 준비와 설정을 확인한 뒤 자금을 보낸다.
3. 별도 Driver를 가진 개발 클라이언트가 P1 한 건을 전송한다. 입력은 U1에게 USDT 1,000 준비이며 실제 은행 입금 기능은 아니다.
4. 각 노드가 확정 입력의 callback에서 기존 `OrderAdmissionState.prepareDevelopmentBalance`를 호출한다. 잔고와 준비 이력·OPENING은 기존 규칙으로 만들어진다.
5. 클라이언트 응답과 각 노드가 만든 불변 관찰을 따로 모은다. 세 관찰은 같은 새 로그 위치 L이어야 하고, 사용 가능 USDT 1,000·예약 0·준비 이력 1·OPENING 1이어야 한다.
6. 노드가 기록 파일을 닫은 사실을 확인하고 Archive·cluster를 보고서에 복사한다. 이번 컨테이너·네트워크를 정리하며 기록 볼륨은 지우지 않는다.

세 개의 USDT 1,000은 총 3,000을 지급했다는 뜻이 아니다. **같은 자금 상태를 세 프로세스가 각각 보관하는 복제 사본**이다.

## 개발 클라이언트가 별도 컨테이너인 이유

이번 검사는 HTTP 서버를 실행하지 않는다. 같은 `app-api` 모듈 안의 `DevelopmentFundingClientKt` main을 별도 컨테이너에서 실행해 외부 요청자의 역할을 맡긴다. 이 JVM 안에서 MediaDriver를 함께 실행하므로 클라이언트와 Driver가 컨테이너 두 개로 나뉘는 것은 아니다. 노드의 메모리·Driver 파일을 공유하지 않고 실제 주소·UDP 송수신·응답 경로로 연결되는지 확인한다.

Aeron client는 클러스터에 요청을 보내는 라이브러리 코드다. 이후 HTTP API를 연결할 때는 Controller → UseCase → Aeron client → MediaDriver를 같은 API 서버 JVM에 둘 수 있다. 이번 개발 클라이언트 컨테이너가 별도의 제품 서비스 배치를 확정하는 것은 아니다. 현재 세 노드의 리더 선출은 Kafka KRaft가 아닌 Aeron Cluster의 Raft 기반 ConsensusModule이 담당한다.

## 응답을 받았는데 왜 세 노드를 또 확인할까

클라이언트의 성공 응답은 해당 요청의 준비 결과를 받았다는 뜻이다. 각 팔로워의 업무 상태와 관찰 출력까지 모두 확인했다는 뜻은 아니다. 팔로워의 처리나 외부 관찰 출력은 늦을 수 있다.

| 확인한 것 | 그 사실만으로 알 수 없는 것 |
| --- | --- |
| `offer`가 양수 → 전송 수용 | 실제 지급 결과를 받았는지 |
| `RESULT_RECEIVED` → 준비 결과 수신 | 세 노드의 같은 L에서 잔고·원장이 모두 맞는지 |
| 각 노드의 `APPLIED` 관찰 | 다른 노드도 같은 지점을 처리했는지 |
| 세 사본의 같은 L·독립 기대값 + 응답 | 장애 전환·재시작 복구·성능까지 되는지 |

그래서 이 테스트는 **응답 + 세 사본의 같은 L + 각각의 돈·원장 기대값**을 함께 확인한다. 응답이 없어도 돈이 이미 반영됐을 수 있으므로 자동으로 새 요청을 보내거나 미지급으로 표시하지 않는다.

이 문서는 응답 수신과 세 사본의 적용 완료를 구분하는 부분부터 읽도록 구성했다.

<details markdown="1">
<summary>파일별 역할과 정상·실패 경로</summary>

## 코드에서 응답과 관찰이 갈라지는 곳

`DevelopmentFundingAdapter.kt`의 실제 발췌다. 같은 callback에서 업무를 반영한 뒤 불변 관찰을 게시하고, 결과 전달을 시도한다. `sendReply`는 팔로워에서는 업무를 다시 실행하지 않고 NOT_LEADER 진단으로 끝낸다. 노드 main은 이 관찰을 외부에 출력하고, JUnit 실행기는 세 프로세스 출력을 대조한다.

```kotlin
            val applied =
                FundingObservation(
                    cluster.logPosition(),
                    state.snapshot(),
                    fundingResult,
                    malformedMessages = malformedMessages,
                    replyStatus = "PENDING",
                    admissionResult = admissionResult,
                    appliedRole = cluster.role().name,
                )
            observation = applied
            observation = sendReply(session, reply, applied)
```

서비스 적용 위치와 준비 결과·Snapshot을 묶는 부분을 먼저 보고, 그다음 `DockerFundingVerification`의 `compareFundingReplication` 호출로 내려가면 된다. Snapshot은 이 설명에서 메모리의 읽기 전용 관찰 값이며, 영구 Aeron Snapshot 저장·복구 기능을 뜻하지 않는다.

## 어느 파일을 읽어야 할까

| 역할 | 실제 파일 | 읽을 판단 |
| --- | --- | --- |
| 개발 실행 | `DevelopmentFundingNode.kt` | 공통 멤버 목록과 자기 주소를 구분하고, 각자의 자원을 시작·종료한다. 세 노드에서는 리더를 강제 지정하지 않는다. |
| 개발 클라이언트 | `DevelopmentFundingClient.kt` | 기존 로컬 실행은 유지하고, 컨테이너에서는 자기 Driver로 연결한다. 전송 수용·결과 수신·미확인을 구분한다. |
| 기존 업무 연결 | `DevelopmentFundingAdapter.kt` | 확정 입력에 기존 State를 호출한다. 반영 순간의 역할을 관찰에 함께 남긴다. 업무 계산은 변경하지 않는다. |
| 개발 출력 | `DevelopmentFundingEvents.kt` | main의 환경 설정과 구조화 출력을 담당한다. 서비스가 완성한 불변 관찰을 읽으며 State를 외부 스레드에서 직접 조회하지 않는다. |
| 실제 외부 검증 | `DockerFundingReplicationTest.kt` | 이미지 준비부터 P1, 대조, 기록 수집·정리까지 한 실제 Docker 사례를 실행한다. |
| 대조와 반례 | `FundingReplicationComparison.kt`·`FundingReplicationComparisonTest.kt` | 읽은 결과를 독립 기대값과 비교한다. 계정·금액·원장·시각 중 하나라도 다르면 실패한다. |
| 설정 회귀 | `DevelopmentFundingSettingsTest.kt` | 기존 loopback 설정, 세 멤버의 주소 분리, 잘못된 설정의 거절을 확인한다. |
| 실행·CI 지원 | `app-api/build.gradle.kts`·`src/test/docker/aeron-funding/`·기존 workflow | 실행 산출물과 이미지 하나를 재사용하고 기존 PR 전체 테스트에 연결한다. |

위 Kotlin 파일은 `app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/` 또는 같은 패키지의 `src/test/kotlin/` 아래에 있다. 제품 HTTP·SQL·Kafka 흐름과 새로운 도메인 모듈은 추가하지 않는다.

## 오류가 나면 무엇이 남나

| 상황 | 실제 처리 |
| --- | --- |
| Docker·이미지·주소·기록 폴더 준비 실패 | 준비 실패. 자동 skip이나 다른 포트·빈 상태로 대체하지 않는다. P1 전송 시도 여부도 기록한다. |
| 응답 없음·새 L 관찰 누락·출력 해석 실패 | 검증 불가. 이미 반영됐는지 확인하지 못했다고 남긴다. 미반영·롤백으로 추정하지 않는다. |
| 금액·계정·원장·업무 시각 불일치 | 업무 불일치. 뒤의 수집 오류가 발생해도 앞서 확인한 차이를 보고서에서 지우지 않는다. |
| 종료·기록 복사·정리 실패 | 추가 실패. 강제 종료나 STOPPED 근거가 없는 기록을 정상 종료한 사본이라고 부르지 않는다. 가능한 진단·부분 기록을 보존한다. |

`APPLIED` 출력은 업무 callback 밖에서 만든다. 업무 callback 안에는 새 stdout·파일·네트워크 출력이나 테스트 장벽 대기를 넣지 않는다. 같은 위치의 PENDING/OFFERED 관찰 두 개는 자금 지급 두 번으로 세지 않는다.

</details>

<details markdown="1">
<summary>새 테스트를 준비·실행·확인 순서로 읽기</summary>

## 실제 Docker 사례 하나

**빈 세 사본에 자금 준비를 한 번 보내면 세 사본의 잔고와 원장이 같아야 한다.**

- 준비: 별도 볼륨을 가진 실제 컨테이너 세 개, 동일 이미지·업무 설정, 별도 Driver의 클라이언트. mock이나 DB를 사용하지 않는다.
- 실행: 준비 확인 → P1 전송 한 번 → 응답과 각 노드의 적용 관찰 수집 → 정상 종료 → 기록 복사·정리.
- 확인: 한 리더·두 팔로워와 격리, 새 동일 L, USDT 사용 가능 1,000·예약 0, 준비 이력 1, 접수/대기 주문 0, 승인 순번 0, 지정한 OPENING ID·사건 ID·시각·두 분개·최초 원본/결과. 마지막에 실패 목록이 비어야 assertion이 통과한다.
- 한계: Docker 세 개는 한 호스트 위에 있다. 물리 호스트 장애·리더 교체·재시작 복구·TPS를 증명하지 않는다.

## 비교기·수집 사례 열한 개

실제 엔진을 다시 실행하지 않고 **읽은 관찰 예제의 일부를 바꿔**, 대조 코드가 거짓 통과를 막는지 확인한다. 아래 예제는 제품 업무 테스트와 구분한다.

| 준비와 실행 | 실제 확인 |
| --- | --- |
| 정상 세 관찰과 같은 L의 응답 시도 관찰 하나 추가 | 정상으로 통과하며 중복 지급으로 세지 않는다. |
| node2 관찰 제거 또는 다른 실행 ID로 변경 | 검증 불가와 누락 노드를 남긴다. |
| 오래된 L·다른 L·같은 노드의 모순된 두 L | 새 동일 위치로 인정하지 않는다. |
| 금액·계정·업무 시각을 각각 변경 | 해당 필드의 업무 불일치를 잡는다. |
| 세 사본의 금액을 모두 같은 잘못된 값으로 변경 | 서로 같아도 독립 기대값과 다르므로 실패한다. |
| 현재 응답은 정상, 보존한 최초 준비 금액만 변경 | 보존한 준비 원본의 차이를 잡는다. |
| 잔고 불일치 + 다른 노드 누락 + 수집 오류 | 세 종류의 오류를 모두 보존한다. |
| 전송 수용 이벤트만 남기고 응답 제거 | 반영 관찰이 맞아도 성공으로 보지 않는다. |
| 일반 로그와 손상된 JSON을 읽음 | 빈 성공이 아니라 해석 실패를 남긴다. |
| node0 식별 실패, node1·node2는 존재 | 첫 실패를 기록하고 나머지 두 컨테이너의 식별을 계속한다. |
| 잘못된 금액을 읽은 뒤 다음 조회가 READY까지만 반환 | 앞서 읽은 APPLIED와 불일치를 보존하고 수집 오류·누락을 함께 남긴다. |

## 설정 사례 세 개

| 준비와 실행 | 실제 확인 |
| --- | --- |
| 기존 단일 노드 기본 설정 생성 | loopback·단일 ingress 연결이 유지된다. |
| node1의 환경 설정과 세 멤버 목록 입력 | 자기 주소/Driver 경로와 세 ingress 목적지가 구분된다. |
| 목록에 없는 member·wildcard 광고 주소·멤버 목록 누락 입력 | 잘못된 설정을 거절한다. |

</details>

<details markdown="1">
<summary>실행·검토 기록과 로컬 재실행</summary>

## 실행 기록

최종 실제 Docker 실행 `funding1792498431880d66c3f36`은 통과했다. 로그 위치 352에서 세 노드의 사용 가능 USDT 1,000·예약 0·최초 준비 원본과 OPENING을 대조했고 정상 종료·기록 복사·정리를 확인했다. 마지막 실행의 보고서는 아래 경로다.

`/Users/0chord/Desktop/exchange-core-aeron-106/app-api/build/reports/aeron-funding/funding1792498431880d66c3f36/`

| 검증 | 실제 결과 | 보장 범위 |
| --- | --- | --- |
| 최종 신규 검사 | 비교기·수집 11 + 설정 3 + 실제 Docker 1, 총 15건 통과, skip 0 | 이번 자금 준비 복제와 실패 판정 |
| 기존 Aeron 회귀 | 60건 통과, skip 0 | 기존 Adapter·단일 노드·클라이언트·주문·기록 비교 |
| 구조 검사 | 375건 통과, skip 0 | 프로젝트의 현재 구조 규칙·검사 계약 |
| Kotlin 린트 | 전체 ktlintCheck 통과, 수정 후 app-api ktlintCheck 재통과 | 형식 검사 |
| 독립 재리뷰 | 순수 검사 33건 통과와 실행기 명령 경계 반례 4개 확인 | 지적 두 건 해소 및 인접 수집 경로 |
| 원격 PR CI | 게시 전 로컬 검증에 미포함 | 자동 실행·업로드 연결 코드를 포함했으며, 실제 상태는 PR Checks에서 확인 |

로컬 검증 완료와 #106 전체 완료를 구분한다. PR 게시 후 기존 CI에서 실제 실행·보고서 업로드가 확인돼야 해당 CI 수용 조건까지 충족한다. 데이터베이스 제품 전체 테스트·복구·장애 전환·성능 검증으로 확대하지 않는다.

독립 리뷰는 작성 대화를 전달하지 않은 에이전트가 분리된 고정 checkout에서 수행했다. 첫 대상은 `c82b8e8`, 수정 코드의 검토 대상은 `a9b25891b856610ca0013edff39bdaf681f9aefb`다. 구현에 참여한 에이전트의 자기 검토로 대체하지 않았다. 지적과 수정은 다음과 같다.

| 지적 | 원인과 수정 | 확인 상태 |
| --- | --- | --- |
| 부분 기동 실패 때 다른 노드의 진단 누락 | 노드 발견·수집을 하나의 예외로 중단했다. 각 노드를 계속 발견·수집하고 개별 실패를 보존하도록 수정했다. | 새 반례 테스트 통과, 독립 실행기 반례에서 node1·2 로그·기록 수집 확인 |
| 뒤의 부분 조회가 앞서 읽은 업무 불일치를 제거 | 전체 관찰을 마지막 로그로 교체했다. 이번 P1의 이벤트를 누적·중복 제거하고 확인한 MISMATCH를 즉시 보존하도록 수정했다. | 새 반례 테스트 통과, 독립 실행기 반례에서 MISMATCH·수집 오류·누락의 동시 보존 확인 |

재리뷰에서는 첫 노드 예상 밖 종료 후에도 다른 노드의 관찰 수집이 계속되고, 정상 대조는 오류 없이 통과함도 확인했다. 검토한 변경 범위에서 확인된 미해결 결함은 없다. 앞으로 발생할 모든 버그가 없다는 보장은 아니다.

대기 중 일시적으로 관찰이 아직 없는 상태는 확정 불일치와 구분한다. 기다리는 동안의 누락을 무조건 영구 실패로 누적하지 않는다.

```sh
cd /Users/0chord/Desktop/exchange-core-aeron-106
./gradlew :app-api:test --tests '*DockerFundingReplicationTest' --console=plain --no-daemon
```

위 명령은 실제 Docker 검사 하나를 실행하며 Docker·Compose가 필요하다. 기본 `:app-api:test`에도 포함되어 있다. 테스트 안이나 Dockerfile에서 Gradle을 다시 돌리지 않는다. 보고서는 `app-api/build/reports/aeron-funding/<run-id>/`다. 기존 PR CI는 이 폴더를 기존 테스트 보고서와 함께 7일 보관하도록 연결한다.

이번 로컬 구현 뒤 남은 주문 기능의 검증은 #107 → #108 → #109다. #106에서 주문·동시 부하·여러 마켓을 미리 구현하지 않는다. 이 기록은 사람의 코드 이해나 PR 게시 동의를 대신하지 않는다.

</details>
