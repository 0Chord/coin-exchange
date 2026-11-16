# #135 · 여러 매칭 담당과 공통 자금 연결

**상태: A 로컬 구현·검증 완료 · B 미구현.** 2026-11-15 기준 통합 커밋은 `e0cb119bb672f024fd121d632c1109773f0927a6`이다. #134의 PR #183이 병합된 기준이며, 이전 작업 HEAD `fea5067`과 파일 tree가 같다. A는 이 기준 위의 미커밋 변경으로 검증했다. 1~10절은 합의한 계약, 11~12절은 실제 구현·실행 근거다. 실제3+3+3노드는 다음 PR 범위이므로 #135 전체 완료를 뜻하지 않는다.

상위 #105 · 시스템 계약 #130 · 선행 #134 완료 · 기존 마켓 설정 #114~#118 재사용. 코드 근거는 위 커밋 기준이다.

## 1. 사용자가 보게 될 한 결과

**매칭 담당이 달라도 내 잔고는 하나이며, 같은 돈으로 두 주문을 승인하지 않는다.** 승인한 주문은 자기 담당에게만 전달되고, 각 담당의 체결 결과는 공통 자금에서 한 번 정산한다.

```text
사용자의 주문
      │
      ▼
공통 자금 클러스터 ── BTC 주문 ──▶ BTC 담당 매칭 클러스터
      │                 ◀──────── 체결·대기·취소 전체 결과
      │
      └──────────── SUI 주문 ──▶ SUI 담당 매칭 클러스터
                        ◀──────── 체결·대기·취소 전체 결과
```

각 박스는 별도 클러스터다. 실제 배치 검증 단계에서는 각 박스가 3 JVM, 전체 9 JVM이다. 자금은 사용자·자산별 잔고와 예약을 소유하고, 매칭은 담당 마켓의 주문장과 체결 순서를 소유한다. 매칭 담당에 사용자 USDT 잔고 사본을 따로 만들어 승인하지 않는다. 한 매칭 담당이 여러 마켓을 맡는 배치도 허용한다.

### 누가·언제·왜 판단하나

| 시점 | 판단하는 담당 | 하는 일과 바뀌는 상태 | 완료로 인정하는 근거 |
| --- | --- | --- | --- |
| 주문 접수 | 공통 자금 | 확정 입력 순서대로 가용 잔고를 검사하고 예약·주문 원본·전달 의무를 기록 | 자금 로그를 반영한 최초 접수 결과. 아직 매칭 완료가 아님 |
| 주문 전달 | 자금 리더 JVM의 전송 작업 | 고정 배치표에서 주문의 매칭 담당을 고르고 같은 원본을 전달 | 전송 성공만으로 원본을 지우지 않음 |
| 주문 적용 | 해당 매칭 클러스터 | 자기 마켓인지 확인하고 순서대로 주문장·전체 결과를 함께 반영 | 매칭 적용 확인. 자금 로그에 그 확인이 반영돼야 전달 자료 회수 |
| 결과 정산 | 해당 매칭 리더의 전송 작업 → 공통 자금 | 마켓별 앞 결과 다음 순서로 잔고·예약·수수료를 반영 | 자금에 정산 결과가 확정됨 |
| 결과 회수 | 해당 매칭 클러스터 | 자기 결과의 정산 확인을 자기 로그에 기록 | 그 확인 구간의 결과 자료만 회수 |

전체가 하나의 트랜잭션은 아니다. 각 클러스터가 자기 확정 기록을 바탕으로 다음 단계로 이어간다. 접수 응답·매칭 적용 확인·정산 확인을 서로 대신 쓰지 않는다.

## 2. 잔고 1,000에서 두 주문이 겹치는 흐름

설명을 위해 수수료 0인 시험 설정에서 예약 요구액을 정확히 600·500으로 둔다. 실제 정책을 무시한 금액 비교나 수수료를 빠뜨린 승인을 의미하지 않는다.

```text
BTC 600 주문 ──┐
               ├──▶ 공통 자금이 자기 로그의 순서로 판단
SUI 500 주문 ──┘          │
                         ├─ BTC 먼저: 600 예약 → 가용 400 → SUI 부족 거절
                         └─ SUI 먼저: 500 예약 → 가용 500 → BTC 부족 거절

승인 주문만 해당 매칭 담당으로 전달
같은 요청 재전송 → 최초 응답 반환, 추가 예약·추가 주문 없음
```

- 두 매칭 담당의 실행 속도·응답 도착 순서가 자금 승인 순서를 정하지 않는다.
- 자금 단일 소유자가 예약을 순서대로 판단하고, Raft는 그 순서를 각 자금 사본에 복제한다. 요청 중복 방지는 이미 처리한 요청의 금융 효과를 다시 만들지 않는 역할이다.
- 전달/응답이 늦어도 예약을 임의로 풀거나 다른 매칭 담당으로 우회하지 않는다.
- 한 담당의 일시 단절·BUSY는 그 담당의 재시도로 제한한다. 다른 담당은 진행할 수 있다. 다만 공통 가용 자금·공통 저장 한도까지 소진되면 다른 마켓도 접수가 제한될 수 있다.
- 잘못된 설정·다른 담당의 ACK·손상된 결과는 정상 지연과 다르다. 기존 실패 차단을 유지하며 결과를 다른 담당으로 추측해 적용하지 않는다. 새 인증·인가·원격 펜싱은 이번 범위가 아니다.

## 3. 두 PR로 나눈 완료 기준

| 단위 | 이번에 끝낼 결과 | 남기는 한계 |
| --- | --- | --- |
| **A · 이번 상세 명세** | 실제 실행 설정·노드·전송 작업이 서로 다른 두 매칭 담당을 연결한다. 실제 Adapter와 유한 가상 전송을 잇는 경량 검사로 승인·라우팅·정산·확인 격리를 검증 | 실제 9 JVM·Aeron 전송·Archive 복제 성공 근거는 아직 없음. #135는 Open 유지 |
| **B · A 이후 별도 PR** | 같은 구현을 자금3+BTC3+SUI3 JVM으로 실행해 실제 대상·각 3사본·공유 자금·자동 정산을 대조 | 리더 전환/과반 상실·물리 장애 영역·TPS 보장은 별도 |

A가 설정 객체만 만드는 PR이 되지 않도록 노드의 실제 전송 작업 조립까지 포함한다. B를 위해 범용 실행 프레임워크나 제품 관찰 API를 A에 만들지 않는다. 두 PR이 끝나야 #135를 완료한다. 명세 작성 당시에는 구현 전이었으며, 이어진 사용자 요청으로 A 구현·검증만 허용됐다. 실제9 JVM 검증은 다음 PR에 남기고 커밋·PR 게시·이슈 종료는 이번 구현과 구분한다.

## 4. 이번에 바꾸는 것과 유지하는 것

- **포함:** 고정 배치와 담당별 연결 설정, 자기 담당 선택, 공통 구성 지문, 담당별 주문 전송/확인·자기 결과 반환 연결, 유한 송신 예산, 기본 저장·복원 및 단일 담당 회귀.
- **유지:** 공통 자금 한 소유자, 기존 매칭·정산 계산기, 요청/입력/결과 identity, 마켓별 순서, Protobuf 주문/결과 계약 v1/v2, 기존 snapshot 형식과 엄격한 복구 identity 검사, 리더에서만 송신·팔로워 동일 상태 적용.
- **제외:** HTTP/API·인증/인가·보안 기능, Kafka/RDB/RocksDB 원장 적재, 전체 과거 원장 항목 직접 대조, 자금 샤딩, 담당 자동 이동·동적 설정 변경, 새 매칭 알고리즘, 성능 측정·장기 부하, 리더 선출/과반 장애 시험, SOL/ETH 반복 사례, 별도 전달 JVM.
- **마켓 정보와 배치 정보는 구분:** 가격/수량 단위·수수료는 기존 RDB 지정 버전·원문 지문에서 가져온 고정 manifest를 재사용한다. 어느 담당에게 보낼지는 별도 실행 배치다. 주문마다 RDB를 읽거나 마켓 목록을 코드에 하드코딩하지 않는다. 새 마켓 설정 JSON 저장소·관리 API·토폴로지 RDB CRUD는 만들지 않는다.

<details>
<summary>5. 구현자용 · 현재 코드의 사실과 변경 경계</summary>

아래 파일은 기준 커밋에 존재한다. 신규 클래스명·세부 분할은 구현자가 조정할 수 있으며, 다른 책임까지 한꺼번에 정리하지 않는다.

| 현재 코드 | 확인한 사실 | A의 변경 책임 |
| --- | --- | --- |
| [TradingRoleAssembly.kt](../domain-trading/src/main/kotlin/com/exchange/core/trading/TradingRoleAssembly.kt) | `TradingRoleTopology`는 마켓별 lineage/epoch, matching 선언은 자기 마켓만 검증 | 기존 도메인 배치·상태 소유 재사용. 새 자금 엔진을 만들지 않음 |
| [FundsDispatchAdmissionTest.kt](../domain-trading/src/test/kotlin/com/exchange/core/trading/FundsDispatchAdmissionTest.kt) | 138줄 부근에 공유1,000/예약600·500의 두 순서가 이미 있음 | 단순 도메인 사례만 다시 실행해 복수 실제 연결을 검증했다고 하지 않음 |
| [SplitTradingConfiguration.kt](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitTradingConfiguration.kt) | 98~103줄은 한 matching lineage만 허용. 115줄 이후 지문은 단일 matching 구성 포함 | 전체 담당 구성과 노드 자기 담당을 분리. 전체 구성을 공통 지문으로 결속 |
| [DevelopmentSplitTradingMain.kt](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentSplitTradingMain.kt) | 22~39줄은 모든 마켓을 한 담당에 배치 | 명시적 고정 배치·연결 설정을 읽고 자기 선언 선택 |
| [DevelopmentSplitTradingNode.kt](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentSplitTradingNode.kt) | 38~116줄은 두 ingress, 고정 cluster ID, boolean 상대 선택·두 응답 포트 | 담당→endpoint/cluster ID와 자기 owner를 명시. 충돌 검사·run identity·자원 정리 연결 |
| [FundsOrderDeliveryAgent.kt](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/FundsOrderDeliveryAgent.kt) | 140줄 부근은 모든 stream을 한 matching peer로 송신. reconnect는 모든 stream 초기화 | 담당별 stream/연결/재시도 격리. 기존 송신·ACK 알고리즘을 복제해 별도 엔진으로 만들지 않음 |
| [MatchingResultDeliveryAgent.kt](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MatchingResultDeliveryAgent.kt) | 189줄 부근에 자기 matching 구성의 결과 stream을 사용 | 자기 담당의 결과만 공통 자금에 반환하고 자기 클러스터에 ACK 기록 |
| [SplitTradingClusterAdapter.kt](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitTradingClusterAdapter.kt) | 역할별 상태 소유·committed callback·지문/lineage 응답·제한 조회 있음 | funds는 한 상태 유지, matching만 자기 담당 조립. 서비스 스레드 소유 유지 |
| [SplitTradingRoleStartupTest.kt](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitTradingRoleStartupTest.kt) | 251줄 부근에 고정 입력의 기존 두 구성 지문 golden 있음 | 단일 담당 바이트 보존과 복수 담당 지문 일치/변조 거절을 각각 검사 |

[기존 주문 전달 명세](funds-order-delivery-spec.md)·[결과 전달 명세](matching-result-delivery-spec.md)·[취소 명세](split-order-cancellation-spec.md)·[#134 검증 기록](split-delivery-failure-verification-spec.md)을 재사용한다. 문서의 이전 ‘아직 구현 전’ 표시는 당시 기록이며 현재 코드 사실은 위 커밋에서 대조했다.

</details>

<details>
<summary>6. 구현자용 · 고정 설정·배치·호환성 계약</summary>

### 6.1 공급하는 정보

실행 경계에서 다음을 한 번 읽고 불변으로 고정한다. 외부 입력의 세부 표기법은 기존 환경 설정 관례에 맞춰 구현자가 정한다. 파싱을 미루거나 테스트 코드만으로 배치를 만들어 실제 main 경로를 비워 두지 않는다.

- 기존 고정 manifest: RDB 설정 버전·내용 지문·자산/정밀도/수수료 context와 원래 market ordinal. 부분집합을 다시 번호 매기지 않는다.
- 전체 배치: 등록된 각 market ID → matching lineage·ownershipEpoch. 누락·중복·미등록 마켓을 거절한다.
- 담당별 연결: lineage → cluster ID·member ID별 ingress. 자기 matching 노드는 명시적 lineage로 선택하며 담당 밖 마켓/한도를 조립하지 않는다.
- 자기 노드: role·member ID·run root·driver/Archive/consensus 경로, 충돌 없는 local egress 포트. funds는 자기 funds lineage를 사용한다.

최초 지원은 **1~2 matching 담당**, 마켓 수는 기존 manifest 한도 안이다. 각 담당은 하나 이상의 마켓을 맡을 수 있다. 미지정 담당으로 fallback하거나 ticker 분기문을 넣지 않는다. 새 복수 담당 설정과 기존 단일 담당 환경 설정이 함께 들어오면 모호하게 한쪽을 고르지 않고 거절한다.

기존 RDB 로더가 만들어 공급한 고정 manifest를 사용하는 개발 진입점이라는 현재 구조를 유지한다. 모든 노드가 별도로 latest를 읽게 바꾸지 않는다. B에서는 RDB 지정 버전/내용→manifest provenance와 실제 9노드의 동일 manifest를 확인한다. 이번에 노드별 JDBC 조회를 새로 넣지 않는다.

### 6.2 자원 생성 전 검사

모든 담당 선언·배치와 manifest가 일치하고 funds/matching lineage 및 cluster ID가 구분돼야 한다. 서로 다른 그룹의 ingress, 노드의 서비스/전송 응답 포트, 저장 경로가 겹치지 않아야 한다. 자기 member set과 자기 ingress는 정확히 일치해야 한다. 연결 수·세션 수·응답 포트 범위·조회/송신/단일 메시지/결과 한도는 전체 담당 수를 고려해 검사한다. 하나라도 잘못되면 가능한 한 Driver/Archive/기록 폴더를 열기 전에 실패한다.

### 6.3 공통 지문과 복구

**구현 제안으로 고정할 계약:** 신규 복수 담당 실행은 전체 구성의 공통 지문을 쓴다. 기존 지문 필드를 재사용하되 신규 구성 인코딩을 별도 형식 표지로 구분한다. funds 구성 + 전체 matching 선언/한도 + 프로토콜/전달 한도를 결정적 순서로 결속한다. matching 목록의 입력 나열 순서가 달라도 같은 배치면 같은 지문이어야 한다. manifest의 기존 market ordinal은 바꾸지 않는다.

모든 역할이 같은 전체 구성으로 같은 주문/결과 지문을 계산한다. 자기 matching 선언만 지문에 넣어 노드마다 값이 달라지는 구현은 금지한다. 실제 응답의 role·lineage와 기대 담당도 함께 대조한다. endpoint/cluster ID·자기 선택은 run identity에 결속하고, 연결 위치가 다르다고 업무 lineage/epoch를 재발급하지 않는다. 이 지문은 구성 일치 검사이며 상대 인증을 의미하지 않는다.

단일 담당 모드는 **동일한 고정 입력**에서 기존 지문 인코딩·wire·snapshot bytes 계약을 유지한다. 제품 class 변경으로 artifact 지문이 바뀌는 것은 정상이며, 이를 ‘이전 실행물의 기록을 새 실행물로 복구할 수 있다’는 보장으로 바꾸지 않는다. 복수 담당은 새 run root로 시작하고 다른 설정/실행물의 root·snapshot 복구는 기존대로 거절한다. 같은 실행물·같은 고정 구성의 새 owner에 각 역할 snapshot+tail을 복원하는 경량 검사는 포함한다. 이전 1담당 기록을 2담당으로 자동 이관하는 기능은 제외한다.

기존 stream/wire와 역할 snapshot은 이미 lineage·market·epoch·전체 assignment를 표현한다. 이를 확인 없이 새 schema/포맷으로 교체하지 않는다. 역할 snapshot은 자기 matching 선언도 저장하므로 BTC/SUI의 snapshot bytes는 서로 다른 것이 정상이다. 같은 것은 전체 구성 지문이며, 서로 다른 역할의 snapshot bytes까지 같게 만들지 않는다. 예상과 다른 제약을 발견하면 호환성을 완화하지 말고 해당 차이만 명세에 기록한다.

</details>

<details>
<summary>7. 구현자용 · 전송·정산·장애 경계</summary>

1. **주문 경로:** funds가 발급한 stream의 matching lineage로 목적지를 고른다. 승인 당시의 market/context/epoch를 그대로 전달한다. 상대 응답은 해당 연결의 기대 role·lineage·공통 지문과 먼저 대조한다.
2. **결과 경로:** 각 matching 리더는 자기 담당 결과만 공통 funds에 보낸다. funds는 기존 stream 전체 identity와 마켓별 연속 prefix로 결과를 검증·정산한다. 서로 다른 마켓 사이에 불필요한 전역 결과 순서를 추가하지 않는다.
3. **두 ACK:** matching 적용 ACK는 정확한 주문 stream의 전달 자료만 회수한다. funds 정산 ACK는 정확한 결과 stream의 결과만 회수한다. BTC through2가 SUI through2를 대신할 수 없다. 틀린 대상 ACK는 prefix·payload·돈을 변경하지 않는다.
4. **일시 장애 격리:** 한 matching의 connect/offer/timeout/BUSY는 그 담당의 연결·미확인 송신·cursor만 재시도한다. 다른 담당의 확인 경계와 이미 검증한 연결을 매번 초기화하지 않는다. 공통 funds 연결 또는 자금 리더 세대 변경은 공통 경계라 전체 작업 재조회가 가능하다. 통신 단절만으로 노드를 치명 오류 처리하지 않는다.
5. **의미 오류:** 잘못된 role/lineage/지문, 불가능한 ACK/손상 결과, 금융 반영 실패는 기존 치명 오류 차단을 유지한다. 이번에는 이를 담당별로 자동 수리·우회하는 새 정책을 만들지 않는다. 잘못된 결과로 회수/반환하지 않는 것을 우선 검증한다.
6. **유한 예산과 진행:** 담당별 독립 전송 작업을 재사용하는 안을 우선 검토한다. 각 작업은 담당 stream만 읽고 자신의 client·응답 포트·재시도를 소유한다. 다른 구현도 가능하지만 도메인 알고리즘을 복제하지 않고 서비스 스레드에서 네트워크 대기하지 않아야 한다. 담당 수가 제한되며 전체 조회·in-flight 건수/바이트는 공통 상한 안에서 담당별로 배분한다. 느린 담당이 창 전체를 점유해 건강한 담당의 제어/ACK/최소 한 건 진행 공간까지 소비하지 않도록 시작 때 예산을 검증한다. 한도 합계를 기존 한도인 것처럼 보고하지 않는다.
7. **기존 취소 경로:** wire v2의 취소도 동일 담당으로 전달하고 그 결과만 순차 정산한다. 취소를 새로 구현하지 않으며 다른 마켓 예약을 반환하지 않는다. 늦은 체결 결과보다 취소 반환을 먼저 적용하지 않는다.
8. **리더/종료:** 복원·tail 완료 후 현재 리더만 송신한다. 세대 변경·종료 시 로컬 읽기/future를 무효화하고 모든 소유 연결/작업을 제한 시간 안에 닫는다. 아직 미확인 원본과 결과는 기존 replicated 상태에 남긴다. 이 검사는 실제 리더 failover 시험의 대체가 아니다.

</details>

<details>
<summary>8. A의 수용 사례 · 실제 코드와 독립 기대값</summary>

수용 번호는 앞으로 구현할 사례이며 이미 실행한 테스트 개수가 아니다. 전송·상태 사례는 유효하게 초기화된 공통 funds Adapter 하나, 서로 다른 lineage의 matching Adapter 둘을 사용한다. 실제 codec→Adapter committed callback→응답 codec 경로로 운반한다. 가상 전송은 바이트와 시간/응답 보류만 제어하고 승인·체결·정산 ACK를 자체 생성하지 않는다. source 조회도 실제 Adapter 경로로 한다. 설정·기동 전 거절 사례에는 Adapter를 먼저 만들지 않아도 된다.

| 번호 | 초기 상태·입력 | 독립 기대값·금지할 변화 |
| --- | --- | --- |
| A01 · 공유 자금 경쟁 | 수수료0·수량 scale0·단위1. U1 USDT1,000. BTC BUY6@100, SUI BUY5@100. BTC/SUI가 먼저인 callback 순서를 각각 구성 | BTC 먼저면 가용400/예약600, 반대면500/500. 각각 승인1·잔고부족 거절1. 승인된 대상 주문장에만 주문1, 반대 주문장/dispatch/result/체결 변화0 |
| A02 · 같은 요청 재전송 | A01의 두 요청을 그대로 반복. 같은 token의 다른 내용도 별도 반례 | 최초 응답·order ID·수량·잔고·순번·각 prefix 유지. 내용 충돌은 기존 오류, 새로운 주문을 만들지 않음 |
| A03 · 두 담당 실제 정산 | 별도 fresh fixture. maker/taker1%, scale0·단위1. 구매자 USDT1,000, 판매자 BTC1/SUI1·USDT0. 구매자 BTC BUY2@100·SUI BUY3@100 예약 후 판매자 각 SELL1@100 | 예약 직후 구매자 가용495/예약505. 두 체결 뒤 가용495/예약303·BTC1/SUI1, 판매자 USDT198·base0, 수수료4. quote총량1,000과 각 base총량1 보존. 마켓별 결과1→2는 유지하되 SUI2가 BTC2보다 먼저 도착해도 같은 최종 값 |
| A04 · 기존 취소의 대상 격리 | A03에서 BTC 잔량1·SUI 잔량2를 취소. 순서별·중복 결과/확인 재전송 | BTC 반환101 후 가용596/예약202, SUI 반환202 후 가용798/예약0. 판매자198·수수료4 유지. 두 주문장 비고 각 stream through3·체결 합2, 미전달/미정산 payload0. 서로의 자료를 회수하지 않음 |
| A05 · 한쪽 지연 | BTC 응답만 유실시킨 뒤 가상 시계를 attempt timeout 너머로 진행해 실제 재연결을 유발. 별도 사례로 BTC BUSY의 기존 stream backoff도 검사. 공통 여유·SUI 담당 창은 충분히 둠 | BTC 의무·예약은 유지, SUI 주문 전달·정산·ACK가 실제 진행. BTC 재연결 중 **SUI 연결 ID·검증 세대 유지, 확정 prefix는 낮아지지 않음**을 관찰. SUI까지 매번 재연결한 뒤 잠깐 진행한 것으로 성공 처리하지 않음. BTC 재개 후 같은 원본만 한 번 적용. BUSY 자체에 불필요한 재연결을 요구하지 않음 |
| A06 · 다른 담당의 입력/확인/결과 | BTC 원문 frame을 바꾸지 않고 SUI Adapter에 보내는 사례, 올바른 공통 지문을 가진 다른 lineage 응답, 바뀐 market/epoch/context, 상한 밖 through 또는 동일 stream의 변조된 결과를 별도 fixture에 주입 | 잘못된 수신 Adapter는 INVALID_STREAM·성공 ACK 없음·자기 주문장/결과/prefix 불변. 정상 BTC Adapter는 올바른 원문을 적용. 전송 작업의 잘못된 peer/ACK는 기존 차단, 금액·관련 prefix·payload 무변경. 차단 뒤 정상 진행을 요구하는 시험과 섞지 않음 |
| A07 · 설정/포트/예산 | 담당 누락/중복, 자기 선언 불일치, ingress/cluster ID/egress 충돌, 모호한 신·구 설정, 단일 원본+제어 공간 부족 | 기동/구성 검증 실패. 잘못된 설정으로 정상 노드·기록을 생성했다고 보고하지 않음. 정상 한 담당/두 담당과 한 담당이 두 마켓을 맡는 배치도 검사 |
| A08 · 공통 지문 | 전체 구성의 owner 나열 순서만 변경, 이어 담당 배치/epoch/정책/한도 각각 변경 | 순서만 바뀐 같은 구성은 동일 지문. 의미 변경은 다른 지문·잘못된 peer 거절. 기존 단일 담당 golden도 그대로 통과 |
| A09 · 기본 복원 | A03 중 미전달/미정산 자료가 있는 시점에서 같은 실행물/구성의 funds·각 matching snapshot을 새 실제 Adapter로 복원하고 기존 worker가 재조회 | 대상·stream identity·예약/금액·최초 응답 보존, 자동 전달/정산/ACK 뒤 A03/A04와 수렴. 반대 owner snapshot·변경 배치 복구는 거절. 실제 Archive/과반 복구 주장 금지 |
| A10 · 검증 자체의 누락 | Adapter/관찰 하나 누락, owner끼리 결과를 바꿔 끼움, 잘못된 잔고/추가 반환/중복 결과, 후속 진행 미완료 | 검사 실패. 빈 사본 목록·ACK 수신만으로 성공하지 않음. 모든 Adapter·양쪽 경로의 실제 입력/반영 횟수와 ID를 확인 |
| A11 · 마켓별 순서 | 서로 다른 마켓의 전역 승인 순번은 역순으로 도착. 같은 stream은 2→1로 도착시킨 뒤 1→2로 재시도 | 다른 마켓 간 전역 순서 강제 없음. 같은 stream의 선행 누락은 GAP/expected1이며 적용 없음. 연속 처리 후 각각 한 번 반영. 결과/누적 ACK도 같은 stream의 연속 구간만 사용 |
| A12 · 실제 실행 조립의 연결 | 실제 환경 설정 파서→settings→Node가 공용으로 사용하는 작업 조립 경로에 두 담당 배치를 공급. 바이트 전송 factory만 유한 가상 전송으로 대체 | funds의 두 담당 작업은 **동일한 funds Adapter 인스턴스**를 source로 쓰며 각 올바른 endpoint/stream을 사용. 각 matching은 자기 Adapter만 source로 하는 결과 작업 하나를 조립. 실제 frame이 두 목적지에 도착. 배치의 담당을 서로 바꾸면 목적지도 그 설정대로 바뀜. 테스트가 별도 worker 목록을 만들어 main/Node의 미연결을 숨기면 불충족 |

동시 요청은 같은 사용자의 서로 다른 유효 channel(예:0·1)의 epoch1/sequence1을 사용해 요청 GAP를 잔고 부족으로 오인하지 않는다. A01은 두 callback 순서를 결정적으로 각각 검사한다. 후속 요청은 각 channel의 다음 유효 sequence, 중복은 정확히 같은 token을 사용한다. 기존 유한 inbox/receipt 계약은 바꾸지 않는다.

A03/A04의 기대값은 위 수기 계산에서 정한다. 새 계산기를 호출해 얻은 값을 기대값으로 복사하지 않는다. 실제 결과 ID·대상 주문·수량·체결/취소 이벤트·금융 효과도 비교한다. 전체 평생 원장 목록의 저장/직접 대조로 확대하지 않는다.

가상 전송 큐의 건수/실제 encoded bytes 상한은 고정 구성의 전체 송신·제어 예산으로 정한다. 무한 큐나 sleep 재시도에 의존하지 않는다. 각 경량 사례의 안전 한도로 실제 경과30초·가상 poll100,000회를 제안하며, 초과하면 관측 상태와 원인을 남겨 실패한다. 이는 제품 지연 SLO가 아니다. A12에 필요한 Node의 순수 조립 분리는 허용하지만 새 범용 런처나 제품 테스트 gate를 만들지 않는다. 실제 main/start가 사용하는 동일 경로인지 호출 관계도 정적 검토한다.

</details>

<details>
<summary>9. 검증 방법·첫 구현 단위·B로 넘길 조건</summary>

### 첫 구현 순서

1. A01/A07/A08의 작은 검사를 시작점으로 잡는다. 기존 고정 manifest의 복수 선언·배치를 실행 경계에 연결하고, 단일 모드 golden과 신규 공통 지문을 검사한다. 이 단계만으로 A 완료라고 하지 않는다.
2. 실제 노드 조립과 담당별 송신/결과 반환을 연결한다. 기존 agent/Adapter의 책임 안에서 변경하고 불필요한 큰 파일 이동·범용 추상화를 피한다. A05/A06의 격리와 A12의 실제 조립 경로를 함께 확인한다.
3. 실제 Adapter와 가상 전송으로 A01~A04·A09/A10/A11을 완주한다. 기존 단일 담당의 주문/결과/취소·snapshot 회귀와 구조/스타일 검사를 수행한다.

### A의 관련 검사 명령

아래 선택자를 실제로 사용했다. 신규 `SplitMultiMatchingFlowTest`, `SplitMultiMatchingSettingsTest`는 실제 transport를 대신한 검사이며, 기존 native 회귀와 구분한다. 실행 결과와 별도의 native 명령은 12절에 기록한다.

```bash
cd /Users/0chord/.codex/worktrees/issue148-http-aeron/exchange-core
JAVA_HOME=/Users/0chord/Library/Java/JavaVirtualMachines/corretto-25.0.2/Contents/Home \
./gradlew :domain-trading:test --rerun :app-api:test \
  --tests '*SplitMultiMatchingFlowTest' --tests '*SplitMultiMatchingSettingsTest' \
  --tests '*SplitTradingRoleStartupTest' --tests '*FundsOrderDeliveryAgentTest' \
  --tests '*MatchingResultDeliveryAgentTest' --tests '*SplitCancellationFlowTest' \
  --tests '*SplitTradingClusterAdapterTest' \
  --tests '*FundsRoleSnapshotCodecTest' --tests '*MatchingRoleSnapshotCodecTest' \
  :architecture-tests:test --rerun :architecture-tests:verifyArchitectureReport ktlintCheck \
  --no-daemon --max-workers=1 --console=plain
```

기존/신규 선택자는 구현 때 실제 파일과 대조한다. 테스트0건·skip·예외 무시를 성공으로 세지 않는다. A에서 바꾼 단일 담당 기동을 실제 Aeron으로 회귀 확인하기 위해 기존 `DockerFundsOrderDeliveryTest`·`DockerMatchingResultDeliveryTest`·`DockerSplitCancellationTest`를 기존 예산 안에서 순차 실행한다. 이전 실행 근거를 제품 코드 변경 뒤의 회귀 결과로 재사용하지 않는다. 기존6 JVM 회귀는 신규9 JVM 배치의 검증이 아니다.

### B의 시작 조건과 남은 범위

A의 코드·관련 회귀·독립 리뷰가 준비되면 기존 native fixture를 최소 확장해 **funds3/BTC3/SUI3**을 실제로 나누어 실행한다. A 구현 없이9 JVM 배치만 만들어 성공으로 세지 않는다. B에서는 고정 RDB 설정의 출처, 각 PID/boot·members·lineage·담당 market·세 cluster ID·동일 구성 지문과 sync2/2/2를 확인한다. 올바른 두 목적지 라우팅, 동시600/500의 한 건 승인, 양쪽 마켓 실제 체결·정산과 각3사본 일치, 같은 요청의 재전송을 핵심으로 둔다.

각 run의 총시간·디스크/진단 상한과 자기 자원의 finally 정리는 #134를 재사용한다. 9 JVM의 메모리 조건을6 JVM의 ‘6GiB에서 가능’으로 추측하지 않는다. 기존 노드 cap768MiB를 유지하면9개 합계만6.75GiB이다. B 상세화에서 실제 배분 합계+client/Driver/DB·호스트 여유의 사전 조건을 정하고, 부족하면 실행 전에 명시 실패로 보고한다. skip/단일 노드 대체를 성공으로 세지 않는다. 기존 ‘호스트8GiB 여유’ 검사는 RAM이 아닌 디스크 사전 검사다. 장기 부하·물리 장애 영역 분리 보장은 포함하지 않는다.

### A 완료 기록

A01~A12와 관련 회귀의 대응표, 대상 commit/source hash·고정 구성·실행 명령/시각·실제 건수/실패/skip, 독립 검토의 미해결점, B 미검증을 기록한다. 설정/의미 책임의 리뷰를 테스트 수나 architecture 통과로 대신하지 않는다. A의 PR 본문으로 #135 전체를 close하지 않는다.

</details>

## 10. 결정과 상태

- **기존 합의 재사용:** #130의 공통 자금 소유·예약 뒤 매칭 확정·결과의 별도 정산, 마켓별 유한 대기, timeout만으로 반환하지 않는 고정 배치를 유지한다.
- **실제 질문:** “#135는 실제 전송이 아직 상대 하나로 고정돼 있어 연결 수정도 필요해. 추천은 ① 여러 매칭 담당으로 주문·결과를 보내는 기능과 경량 검증, ② 실제 3+3+3노드에서 공유 자금 검증을 두 PR로 나누는 거야. #135는 둘 다 끝날 때 완료로 두고, 이번 상세 명세는 ①을 바로 구현할 수 있게 정리할까?” AI 추천 이유는 변경과 실행 판정 근거를 분리해 리뷰 크기를 줄이는 것이다.
- **실제 답변:** “두 PR로 나누고, 첫 연결 기능부터 상세화 (추천)”. 별도 이유는 제출하지 않았다. **채택됨.** 3절과 A 수용 사례/B 잔여 범위에 반영했다.
- **구현 제안:** 신규 복수 담당의 전체 공통 구성 지문, 기존 단일 담당 형식 유지, 담당별 유한 연결·송신 창. 내부 클래스 분할·외부 설정의 세부 표기는 구현자가 조정할 수 있다. 바뀌는 보장이 발견되면 해당 차이만 다시 확인한다.
- **이후 구현 요청:** “$spec-implementer #135 A 상세 명세 기반으로 구현 진행해봐. 테스트랑 검증까지 하고, 실제 3+3+3노드 검증은 다음 PR로 남겨.” A 구현·검증을 진행하며 B는 미실행으로 유지한다. 실행 결과는 아래에 기록한다.
- **명세 정적 검토:** 작성 대화와 분리한 검토자가 현재 코드·금액·호환성·검사 연결을 대조했다. 실제 Node 조립을 우회한 테스트의 거짓 통과 가능성(A12), 다른 담당의 불필요한 재연결을 놓칠 가능성(A05), BUSY와 timeout 재연결의 구분을 보완했다. 이는 제품 구현·시험 통과나 사용자의 코드 이해 확인을 뜻하지 않는다.

## 11. A의 로컬 구현과 읽을 테스트

구현·검사는 `/Users/0chord/.codex/worktrees/issue148-http-aeron/exchange-core`, 브랜치 `feat/multi-matching-delivery/135`의 검증 당시 미커밋 파일을 대상으로 수행했다. 게시할 때 코드·테스트 내용이 검증본과 같은지 다시 대조한다. 다음 경로는 코드로 구현했으며 최종 실행·독립 리뷰 상태는 12절에서 구분한다.

- 실행 설정은 전체 담당을 보존하고 자기 담당만 선택한다. 단일 담당의 기존 구성 지문은 유지하고, 두 담당은 동일한 전체 구성 지문을 계산한다.
- 실제 Node가 쓰는 `splitTradingDeliveryTasks` 조립에서 자금 전송 작업 둘은 **같은 자금 Adapter**를 source로 사용한다. 각 작업은 자기 담당의 stream·연결·재시도를 소유한다. 각 매칭 Node는 자기 결과 작업 하나를 조립한다.
- 전체 주문 조회/송신 예산을 담당 수로 나누고, 복수 담당 작업에는 ACK 제어 공간을 남긴다. 종료 때 모든 작업을 정리하며 일부 작업의 시작 실패도 정리 경로를 거친다.
- 도메인 계산기·HTTP 경로·Protobuf·snapshot 포맷·인증/인가는 바꾸지 않았다. 실제9 JVM 실행도 추가하지 않았다.

<details>
<summary>구현·테스트 근거와 실행 설정</summary>

구현 입구는 [실행 설정](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentSplitTradingMain.kt), [공통 구성/지문](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitTradingConfiguration.kt), [Node와 공용 작업 조립](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentSplitTradingNode.kt), [담당별 주문 전송](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/FundsOrderDeliveryAgent.kt)이다.

새 실행 배치는 환경 설정 `SPLIT_MATCHING_OWNER_COUNT=1|2`와 각 인덱스 `SPLIT_MATCHING_0_*`/`SPLIT_MATCHING_1_*`의 `LINEAGE_HIGH`, `LINEAGE_LOW`, `OWNERSHIP_EPOCH`, `MARKETS`, `CLUSTER_ID`, `INGRESS`로 공급한다. `MARKETS`는 등록된 ID의 쉼표 목록이다. MATCHING 역할은 `SPLIT_SELF_MATCHING_LINEAGE_HIGH/LOW`로 자기 담당을 지정한다. FUNDS는 정렬된 첫 담당을 설정 뷰로 사용하지만 자금 소유자와 전체 배치는 하나다. 기존 단일 담당 환경과 혼용하면 거절한다. 이는 마켓 정책을 저장하는 새 JSON 파일이 아니라 고정 실행 배치 입력이다.

### 설정 검사 — 준비·실행·실제 확인

[SplitMultiMatchingSettingsTest](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitMultiMatchingSettingsTest.kt)의13개 검사다.

| 준비·실행 | 확인하는 결과 |
| --- | --- |
| 두 담당의 설정 뷰 생성, 목록 순서/자기 선택 변경 | 자기 마켓·원래 ordinal, 공통 지문 동일, 외부 목록 수정 불가 |
| 다른 담당의 한도·배치·epoch·정책을 각각 변경 | 공통 지문이 의미 변경에 결속됨 |
| 담당/한도 누락·중복·자기 불일치와 잘못된 환경 입력 | 시작 전에 거절, 기존/신규 환경 혼용 거절 |
| 한 담당에 두 마켓을 배치 | 기존 단일 설정과 같은 구성 지문/전송 한도 |
| 전체 송신 예산 배분 및 너무 작은 예산 입력 | 담당별 합계가 공통 상한 안, 정상 원본+ACK 공간 부족은 거절 |
| 실제 manifest/환경에서 Node 설정 생성, cluster/ingress/응답 포트 충돌 입력 | 정확한 두 목적지와 자기 역할 선택, 잘못된 배치의 기록 폴더 미생성 |
| 같은 그룹의 다른 멤버를+9/+10 또는 낮은 겹친 범위에 배치, 자기 ingress와 실제 기본 포트를 다르게 선언 | 자기 멤버만 충돌 대상에서 제외하고 모든 그룹을 검사. 정상 간격과 기존 광고 주소 별칭은 허용, 잘못된 포트는 기록 생성 전에 거절 |

### 업무 흐름 검사 — 실제 응답을 운반하는 가상 전송

[SplitMultiMatchingFlowTest](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitMultiMatchingFlowTest.kt)의12개 검사다. 공통 준비는 실제 환경 파서·Node 공용 조립·자금 Adapter 하나·매칭 Adapter 둘과 실제 codec이다. 가상 전송은 목적지 ingress와 실제 frame을 관찰하고, 확정 callback이 만든 응답만 운반한다. 실제 Aeron·Archive·Raft 복제·HTTP 검사는 아니다.

| 사례/준비 | 실행 | 실제 assertion |
| --- | --- | --- |
| A01/A02: 잔고1,000·수수료0 | BTC600/SUI500을 두 callback 순서로 접수하고 같은 요청/내용 충돌 재전송 | 승인1·부족 거절1,400/600 또는500/500, 정확한 주문장 하나와 최초 receipt/자금 bytes 유지 |
| A03/A04: 잔고1,000·양쪽1% 수수료 | 두 마켓 예약→각1개 체결→잔량 취소를 두 취소 순서로 실행 | 가용495/예약505→495/303→798/0, 판매자198·수수료4, 대상 주문/체결 ID·총량·각 stream 확인 |
| A05: BTC만 주문 응답과 결과 전달 보류 | 다른 작업은 계속 pump하며 BTC timeout/재연결, 별도 BUSY 후 재개 | BTC 의무 유지·동일 원본1회 효과, SUI 연결/검증 세대 유지와 정산 완료. BUSY에는 불필요 재연결 없음 |
| A06 세 검사: 잘못된 목적지·identity·미래 확인·변조 결과 | 실제 codec/Adapter 또는 작업 응답 경계로 반례 전달 | 잘못된 stream/결과 거절·성공 ACK 없음·금액/prefix/payload 무변경. 치명 오류는 시간 초과와 구분 |
| A09 두 검사: 미전달/미정산 상태의 세 snapshot과 tail | 새 Adapter와 새 작업으로 복원, 기존 요청 재전송·정상 처리 재개. 반대 담당/변경 배치 복원도 시도 | 구매자/판매자 최초 receipt와 원본/예약 보존, 정산·취소까지 수렴. 잘못된 복원은 거절 |
| A10: 정상 체결 관찰과8개 손상 변형 | 담당/결과 누락·교환, 잘못된 금액·중복·미완료 관찰로 판정 실행 | 정상은 통과, 각 변형은 assertion 실패. 빈 관찰의 거짓 성공 차단 |
| A11: 마켓 간 역순과 같은 stream의2번 선도착 | GAP 뒤1→2 재처리, 결과도 같은 조건으로 전달 | 다른 마켓의 독립 진행, 같은 stream 연속 처리·누적 ACK·금융 효과1회 |
| A12: 실제 실행 환경과 담당을 바꾼 배치 | Node와 같은 공용 조립으로 양방향 frame 전송 | 자금 source 동일 인스턴스, 각 matching 자기 source, 정확한 ingress·lineage·목적지와 결과 source |

[FundsOrderDeliveryAgentTest](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/FundsOrderDeliveryAgentTest.kt)에 응답 callback이 연결을 닫은 뒤 같은 poll에서 keepalive를 보내지 않는 회귀를 추가했다. 상대 미초기화 응답과 keepalive 기한을 함께 준비한 뒤, 닫힌 연결 호출0과 재연결 후 정상 전달을 검사한다. 시간 진행을 포함한 반례에서 이전 guard는 닫힌 연결 호출1로 실패했다(`/tmp/issue135-closed-connection-red-v2.log`). 임시 재현 후 수정 소스는 복원했으며, 수정 상태의 최종 결과는 아래 실행 기록으로 확인한다.

</details>

## 12. 실행 결과와 독립 리뷰

**A의 합의된 로컬 수용 범위를 충족했다.** 관련949개와 기존 단일 매칭 담당6 JVM 회귀8개가 최종 제품 코드에서 통과했다. 신규 두 담당의 업무 검사는 실제 Adapter·codec·Node 공용 조립과 유한 가상 전송을 사용하며, 실제9 JVM 성공으로 확대하지 않는다. 이 절은 로컬 검증 완료 시점의 기록이며 PR·CI·병합·이슈 종료 상태는 포함하지 않는다. 최신 게시·CI 상태는 해당 PR에서 별도로 확인한다.

<details>
<summary>수용 조건·실행 명령·실패 수정·검토 대상</summary>

### 수용 조건과 근거

| 수용 조건 | 확인한 동작 | 최종 근거와 판정 |
| --- | --- | --- |
| A01/A02 | 두 마켓의 공유 자금 승인, 최초 receipt 재사용과 내용 충돌 | Flow 검사에서 두 접수 순서 모두 충족 |
| A03/A04 | 두 담당의 체결·수수료·잔량 취소를 공통 자금에 한 번 반영 | Flow 검사에서 두 취소 순서·자산 총량·각 stream 확인 충족 |
| A05/A06 | 담당별 timeout/BUSY 격리, 잘못된 대상·응답·확인·결과의 무변경 거절 | Flow 검사와 기존 두 agent/Adapter 회귀에서 충족 |
| A07/A08 | 자원 생성 전 설정 거절, 전체 공통 지문과 단일 담당 호환성 | Settings13개·기존 startup17개 및 독립 반례에서 충족 |
| A09 | 세 Adapter의 snapshot+tail 복원과 잘못된 배치 복원 거절 | Flow 검사와 두 snapshot codec 회귀에서 충족. 신규9 JVM Archive 복구는 미검증 |
| A10/A11/A12 | 잘못된 관찰의 실패, 마켓 간 독립 순서·같은 stream 연속 순서, 실제 Node 공용 조립 | Flow 검사에서 충족. 가상 전송의 한계는11절에 명시 |
| 기존 단일 담당 회귀 | 실제6 JVM 주문 전달·취소·정산·손실 재시도·복원·결과 크기/수용량 한도 | native8개 모두 충족 |
| B | 실제 자금3+매칭3+매칭3, RDB 출처와 각3사본 직접 대조 | 미실행. 다음 PR로 유지 |

### 최종 실행 기록

2026-11-15 KST23:33~23:45에 최종 수정 코드로 실행했다. 명령의 JVM은 Corretto25.0.2, Gradle 동시 작업은1이다. Docker 검사는 직렬 실행했으며 각 run의 노드 cap768MiB·heap256MiB, Archive sync2와 제한/관찰은 기존 fixture 그대로다. 신규9 JVM·HTTP·성능 시험은 실행하지 않았다.

| 실행 | 실제 건수 | 실패/오류/skip | 근거 |
| --- | ---: | --- | --- |
| 앱 관련 선택 검사 |119|0 /0 /0|`/tmp/issue135-related-results-v2/app-api/` XML |
| 자금·정산 도메인 전체 |444|0 /0 /0|`/tmp/issue135-related-results-v2/domain-trading/` XML |
| 구조 검사 |386|0 /0 /0|`/tmp/issue135-related-results-v2/architecture-tests/` XML |
| 구조 report·전체 ktlint |명령 성공|해당 없음|`/tmp/issue135-related-verification-v2.log`, `BUILD SUCCESSFUL in 2m 28s` |
| 실제6 JVM 주문 전달·취소 |2|0 /0 /0|`/tmp/issue135-native-regression-v2.log`,3분3초 |
| 실제6 JVM 결과 전달 |6|0 /0 /0|`/tmp/issue135-native-results-regression-v2.log`,3분55초 |

관련949개는9절의 명령으로 실행했다. 결과 보존본 집계는 `/tmp/issue135-related-results-v2/summary.json`이다. 앱119개에는 신규 Settings13개·Flow12개, 기존 주문 agent19개·결과 agent23개·startup17개·Adapter15개·취소3개·snapshot7+10개가 들어 있다.

native 명령은 다음 두 번으로 나눠 같은 수정 코드를 사용했다.

```bash
cd /Users/0chord/.codex/worktrees/issue148-http-aeron/exchange-core
JAVA_HOME=/Users/0chord/Library/Java/JavaVirtualMachines/corretto-25.0.2/Contents/Home \
./gradlew :app-api:test \
  --tests '*DockerFundsOrderDeliveryTest' --tests '*DockerSplitCancellationTest' \
  --no-daemon --max-workers=1 --console=plain
JAVA_HOME=/Users/0chord/Library/Java/JavaVirtualMachines/corretto-25.0.2/Contents/Home \
./gradlew :app-api:test --tests '*DockerMatchingResultDeliveryTest' \
  --no-daemon --max-workers=1 --console=plain
```

native8개는 주문 전달1·취소1·결과 전달6개다. 결과 전달에는 응답/ACK 손실 후 log/snapshot-tail 복원, 다중 maker 부분 체결, 수수료, 결과 공간 BUSY, 활성 주문 한도, 약1MiB 전체 결과가 포함된다. 보존한 XML·각 result.json·집계는 `/tmp/issue135-native-results-v2/`에 있다. 8개 모두 `success=true`, `issues=[]`, `recordVolumesDeleted=true`, `runResourcesRemoved=true`다. 보고서의 제품 소스 hash는 현재 파일과 일치했고, 실행 후 컨테이너가 남지 않았으며 호스트 디스크 여유는 약70GiB였다. 이전 v1 실행8개나 수정 후 중간 실행을 최종 건수에 중복 합산하지 않았다.

### 실제 실패와 수정

- 새 Flow fixture가 초기 자금 한도와 snapshot 상한을 함께 충족하지 못해12개가 시작 단계에서 실패했다. fixture의 자금 보관 바이트 상한을 명시했고 제품 한도 검증은 유지했다. 이후 A05는 BTC 정산이 주문 적용 증거가 되어 전달 자료를 이미 회수하는데 기대값이 pending1이었던 시험 준비 오류였다. BTC 주문 응답과 결과를 함께 보류하고 SUI 작업은 계속 진행하도록 고쳤다. 최종12개 모두 통과했다.
- 주문 전송의 poll callback이 연결을 닫은 뒤 같은 회차 keepalive를 시도하는 오류를 수정했다. 기한이 지난 상황을 준비한 반례에서 이전 guard는 닫힌 연결 호출1로 실패했고(`/tmp/issue135-closed-connection-red-v2.log`), 수정 후0과 정상 재연결을 확인했다. 금융 원본·ACK 알고리즘은 바꾸지 않았다.
- 독립 리뷰 v1은 같은 자금 그룹의 다른 멤버와 새 응답 포트가 겹쳐도 초기 설정 검사가 수락하는 P2 한 건을 재현했다. 회귀2개가 이전 구현에서 모두 실패함을 확인했다(`/tmp/issue135-port-red.log`). 자기 ingress 기본 포트를 검사하고 **자기 멤버만 제외한 모든 그룹의 멤버**에 대해 상·하방 포트 중첩을 검사하도록 수정했다. 정상 주소 별칭과 인접 정상 포트도 함께 유지했다. 수정 뒤 관련949개와 native8개를 모두 다시 실행했다.

### 독립 검토와 소스 고정

작성 대화를 전달하지 않은 별도 검토자가 v1 고정 소스에서 실제 호출 경로와117개 관련 검사를 직접 확인하고 P2를 발견했다. 수정 뒤 v2 고정 사본 `/tmp/issue135-review-v2`를 이어 재검토했다. manifest SHA256은 `dcdc425c1b052fcae76e1588230cbbd7db6367a4d9dfb3e0523f8182464a7548`이다. base는 문서 첫머리의 통합 커밋이며 실제 변경9개 파일의 hash를 manifest에 담았다.

v2 검토자는 잘못된 설정 거절37개·정상 수락20개를 최대 힙256MiB로 설정한 별도 probe로 직접 실행했다. funds/member0·1, 두 matching 역할, 상·하방 포트 경계, 자기 포트 불일치, 알려진 주소 별칭, 기존 단일 담당 간격·상위 포트를 포함하며 실행 폴더는 생성되지 않았다. 원자료는 `/tmp/issue135-review-v2-probe.log`, 집계·class hash는 `/tmp/issue135-review-v2-probe-summary.json`이다. 검토자가 보관 XML119/444/386과 구조/스타일 성공 로그도 직접 대조했다. v2 Gradle/Docker를 독립적으로 다시 실행한 것으로 표시하지 않는다.

**v1 P2 해소, v2 검토 범위의 확인된 미해결 결함0건**이다. 이는 모든 미래 결함이나 B의 실제 복제·고가용성을 보장하는 판정이 아니다. 실행 코드·테스트7개는 v2 이후 변경하지 않았고, 마지막 문서2개 갱신은 최종 사본 `/tmp/issue135-review-final-v3`과 `/tmp/issue135-review-final-v3.manifest.json`으로 분리해 대조한다. 기술 검사 통과는 사용자의 코드 이해·검토 완료나 게시 승인과 구분한다.

</details>

게시 준비에서는 위 고정 검토본의 코드·테스트7개와 실제 게시할 파일의 hash를 다시 대조한다. 문서의 미커밋·미게시 표현은 검증 당시의 기록으로 명확히 했으며, 실행 코드·테스트는 바꾸지 않는다. A 게시로 #135를 자동 종료하지 않고 실제9 JVM 검증은 B로 유지한다.
