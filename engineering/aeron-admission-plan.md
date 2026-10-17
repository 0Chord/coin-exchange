# Aeron 연결을 한 기능씩 이어 만들기

**#95는 PR #100으로 통합됐고, #96 자금 준비 메시지 변환은 로컬 구현·검증을 마쳤다.** #96의 PR·CI·병합 상태는 GitHub의 연결 PR에서 확인한다. 실제 통신은 #97부터 연결한다. [#96 구현 흐름·테스트](development-balance-protocol.md).

**왜 나누나:** 초기 자금 계산, 메시지 변환, 실제 통신, 주문 접수, 실제 기록 검증을 한 PR에 넣으면 무엇이 바뀌었는지 판단하기 어렵다. 각 이슈의 동작과 관련 테스트를 한 PR에 담고 순서대로 이어간다.

| 순서 | 이슈 | PR 하나가 끝났을 때 확인할 것 |
| --- | --- | --- |
| 1 · 통합 완료 | [#95 · 초기 자금](https://github.com/0Chord/coin-exchange/issues/95) | 메모리에 첫 자금을 준비하고 반복 지급을 막는다 |
| **2 · 로컬 완료** | [#96 · 자금 준비 메시지](https://github.com/0Chord/coin-exchange/issues/96) | 요청·결과가 Protobuf로 변환·복원된다 |
| 3 | [#97 · 실제 자금 준비 통신](https://github.com/0Chord/coin-exchange/issues/97) | Aeron 개발 노드에 보내 실제 준비 결과를 받는다 |
| 4 | [#98 · 실제 주문 접수](https://github.com/0Chord/coin-exchange/issues/98) | 준비한 돈으로 주문을 보내 승인·거절을 확인한다 |
| 5 | [#99 · 실제 기록 대조](https://github.com/0Chord/coin-exchange/issues/99) | 보낸 입력·정책·순서가 실제 Archive에 있는지 대조한다 |

**상태:** #95의 PR #100 통합 커밋은 `c6f962c96bb60d731377e5e3e75b08a1203eefc0`다. #96은 이 코드에서 변환·코어 연계 테스트를 구현하고 필수 검사·독립 리뷰를 마쳤다. #97~99는 명세만 작성한 미구현 상태다. 로컬 검증과 PR·CI·병합은 구분한다.

[#95 구현 결과·테스트 설명](engine-opening-balance.md)에서 로컬 결과를 읽고, [합의 명세](engine-opening-balance-spec.md)에서 원래 계약을 확인한다. 나머지 네 이슈의 상세 계약은 각 GitHub 본문이 기준이며, 웹 읽기 화면도 해당 본문을 사용한다.

<details markdown="1">
<summary>공식 설명과 비교해 보완한 최종 계약</summary>

Kotlin/JVM·Spring과 Aeron의 조합은 가능하다. 그 사실을 고정 TPS·지연·즉시 무중단 전환·노드의 매 순간 동일 메모리를 보장하는 말로 바꾸지 않는다. 아래 항목을 각 기능의 완료 기준에 연결했다. #95의 순수 상태와 #96의 변환·코어 연계 사례는 실행·검증했다. #97~99의 통신·기록 사례는 앞으로 구현할 계약이며 실행 근거가 아니다.

| 항목 | 현재 단계의 최종 계약 | 확인 위치 |
| --- | --- | --- |
| 상태 소유·결정론 | 한 실행자의 동기 상태 변경. 같은 초기 상태와 고정 입력 스트림을 두 실제 상태에 적용해 잔고·원장·최초 결과·순번을 의미 필드로 대조 | #95·#96·#98 |
| 시간·ID·순회 | 업무 ID·Instant는 확정 입력에서 읽는다. 로컬 시각·난수·보장되지 않는 순회 순서·코루틴 완료 순서를 업무 판단에 넣지 않는다 | #95~98 |
| callback과 응답 | 업무 적용은 역할/세션 유무와 분리. null 세션에 NPE를 내거나 업무 적용을 생략하지 않으며 유효한 리더 세션에만 egress | #97 |
| 실제 저장 설정 | Archive data/catalog·ConsensusModule의 Context에 각각 2를 명시하고 실제 getter 값을 대조. property 문자열만으로 적용을 주장하지 않음 | #97·#99·후속 #89 |
| 미지원 복구 | 새 기록 디렉터리만 사용. snapshotImage를 무시하거나 빈 onTakeSnapshot을 성공으로 반환하지 않고 명시 fatal 실행 경계로 중단 | #97 |
| 명시 거절과 반복 | 신규 설정 불일치는 사유 9로 접수 전에 거절. 기존 키의 최초 결과/내용 충돌은 코어가 먼저 판단 | #98 |
| 기록·복제 대조 | 기록 상한 L과 업무 순번·전송 수용을 구분. 사본은 같은 적용 위치 L에서 비교 | #99·#89 |
| 현재 도구 유지 | 기본 Spring MVC·기존 도메인 규칙·Protobuf를 유지. WebFlux 전환이나 Agrona 전면 교체를 필수 작업으로 넣지 않음 | 이번 다섯 PR |

**두 가지 snapshot:** `OrderAdmissionState.snapshot()`은 불변 관찰값이다. Aeron의 영구 Snapshot은 `onTakeSnapshot`으로 저장하고 `onStart(..., snapshotImage)`에서 복원한다. 1.53.3에 `onLoadSnapshot` 콜백은 없다. 후속 #97은 영구 Snapshot을 구현하지 않으므로 지원하는 것처럼 정상 완료하지 않는 경계만 확인할 예정이다. #95에는 Aeron Snapshot 콜백을 추가하지 않았다. framework의 실패 기록 흔적은 남을 수 있으며 파일 0건을 성공 기준으로 삼지 않는다. [실제 API](https://github.com/aeron-io/aeron/blob/1.53.3/aeron-cluster/src/main/java/io/aeron/cluster/service/ClusteredService.java), [실제 실행·Snapshot 처리](https://github.com/aeron-io/aeron/blob/1.53.3/aeron-cluster/src/main/java/io/aeron/cluster/service/ClusteredServiceAgent.java).

**시간과 ID:** 클러스터 callback timestamp는 클러스터 이벤트 시각이다. 현재 업무 시각은 명령 본문에 보존한 Instant다. timestamp만 허용하거나 ID를 무조건 Long으로 바꾸는 규칙을 만들지 않는다. 필요한 값이 모든 사본의 같은 입력에서 나와야 한다. 단위는 `Cluster.timeUnit()` 등 실제 설정과 대조하며 임의로 밀리초라고 가정하지 않는다.

**기록과 내구성:** 전송 수용 → 노드별 Archive 기록/설정된 force → 기록 위치의 과반 확정 → 서비스 적용 → 실제 업무 응답 수신을 구분한다. 기록 데이터·catalog·클러스터 메타데이터의 동기화 대상은 각각 다르다. 단일 노드의 설정 확인·로그 대조를 물리 전원 장애·호스트 유실 시험으로 표시하지 않는다. [RecordingWriter](https://github.com/aeron-io/aeron/blob/1.53.3/aeron-archive/src/main/java/io/aeron/archive/RecordingWriter.java), [Archive Context](https://github.com/aeron-io/aeron/blob/1.53.3/aeron-archive/src/main/java/io/aeron/archive/Archive.java), [ConsensusModule Context](https://github.com/aeron-io/aeron/blob/1.53.3/aeron-cluster/src/main/java/io/aeron/cluster/ConsensusModule.java).

결정론은 JVM 객체 주소·메모리 배치까지 같은 뜻이 아니다. 동일한 초기 코드/정책과 입력까지의 업무 의미가 같아야 한다. HashMap의 단순 조회를 모두 금지하지 않되 보장되지 않는 순회 순서로 거래 우선순위·ID를 정하지 않는다. 업무 I/O·상태 변경을 외부 스레드로 넘기지 않는 원칙과 로그·지표처럼 상태에 영향을 주지 않는 관찰을 구분한다. [업무 처리 원칙](https://aeron.io/docs/aeron-cluster/efficient-business-logic/), [Agrona 자료구조 주의](https://aeron.io/docs/agrona/data-structures/).

</details>

<details markdown="1">
<summary>#95 · 통신 없이 초기 자금을 한 번 준비</summary>

빈 상태 → 사용자 A에게 1,000 USDT 준비 → 기존 주문으로 300 USDT 예약 → 같은 준비 반복의 순서다. 사용 가능 700 USDT·예약 300 USDT를 유지하며, 최초 준비 원본·OPENING·원장 ID·시각을 보존한다.

같은 준비 ID·다른 사용자/자산/금액은 충돌이다. 새 ID로 이미 사용한 계정을 다시 개시하는 것도 충돌이다. 정상 거절과 반영 중 내부 오류 뒤 차단을 구분한다. 기존 OpeningBalance·Balance 규칙을 재사용하고, 두 독립 상태의 같은 입력 재현을 확인한다.

포함은 순수 상태 동작과 테스트다. Protobuf·Aeron·DB·새 서버·자동 지급은 없다. [#95 상세](https://github.com/0Chord/coin-exchange/issues/95).

</details>

<details markdown="1">
<summary>#96 · 초기 자금 요청·응답을 Protobuf로 변환</summary>

준비 ID·사용자·자산·금액·호출자가 정한 원장 ID·시각 → Protobuf 바이트 → 같은 값으로 복원한다. 실제 #95 코어의 최초 성공·반복 성공·두 종류 충돌을 작은 응답으로 연결한다.

기존 주문 종류 1/2/3·schema 87/version 2·헤더·바이트 계약을 유지하고 자금 준비 종류 4/5/6을 최종 전송 계약으로 정했다. unknown/손상·길이·UTF-8·필수 값 누락을 거절한다. 구 코덱이 새 종류를 읽는다고 보장하지 않는다.

이 단계는 변환과 순수 코어 연계 검사다. 실제 통신·Aeron 노드 실행은 없다. [#96 상세](https://github.com/0Chord/coin-exchange/issues/96).

</details>

<details markdown="1">
<summary>#97 · Aeron 노드에 자금 준비를 실제로 전달</summary>

같은 저장소의 별도 개발 실행으로 실제 노드를 띄운다. 요청을 보내는 코드 → 실제 Aeron 전송 → 확정 입력 콜백 → #95의 상태 → #96의 결과 응답을 확인한다. 자금 준비 없이 빈 노드만 실행하는 PR로 끝내지 않는다.

전송 수용과 업무 성공을 구분한다. 응답을 바로 보내지 못해도 자금 준비를 다시 실행하지 않는다. 허용 콜백 안에서 같은 응답을 상한 있게 재시도하고, 응답 대기가 끝나면 결과 미확인으로 남긴다. 같은 준비 ID로 재확인할 수 있다.

Archive 기록·실행별 위치 관측과 실제 저장 Context 값 검증은 이 단계부터 둔다. 복구/Snapshot 미지원 시 조용한 초기화·빈 성공을 막는다. 원본 기록 전체 대조는 #99다. 아직 주문 접수·전체 재시작·고가용성 검증은 없다. [#97 상세](https://github.com/0Chord/coin-exchange/issues/97).

</details>

<details markdown="1">
<summary>#98 · 같은 자금 상태로 주문을 실제 접수</summary>

#97에서 준비한 1,000 USDT → 300 USDT가 필요한 주문 → 승인 순번 1·사용 가능 700 USDT·예약 300 USDT·대기 주문 1건을 확인한다. 기존 주문 코덱·접수 코어를 같은 확정 입력 경로에 연결한다.

마켓·단위·수수료는 개발 실행 설정으로 고정한다. 새 요청의 설정이 다르면 예약 전에 거절한다. 기존 키가 있으면 코어가 최초 결과/내용 충돌을 먼저 판단한다. 부족 같은 최초 업무 거절과 잘못된 실행 문맥의 접수 전 거절을 구분한다. 후자는 돈·순번·최초 요청 이력을 만들지 않는다.

어댑터 거절 사유 9를 기존 작은 응답에 추가하되 순수 도메인 거절 enum은 바꾸지 않는다. 개발 마켓·단위·fee 예시와 기존 메시지 회귀·지원 버전 조합도 명시했다. 아직 매칭·체결·정산·HTTP 전환은 없다. [#98 상세](https://github.com/0Chord/coin-exchange/issues/98).

</details>

<details markdown="1">
<summary>#99 · 실제로 기록된 입력을 읽어서 대조</summary>

준비 P1 → 승인 주문 R1 → 부족 거절 R2 → R1 반복을 보낸다. 서비스가 처리 완료한 마지막 위치를 고정한 뒤 그 위치까지만 실제 Archive에서 읽는다.

recording ID·세션/스트림·시작/끝 위치를 대조하고 실제 입력 바이트·업무 값·정책·순서를 확인한다. 파일 존재, 메모리 요청 목록, 요약 응답이나 해시 하나로 대신하지 않는다. 다른 기록·누락·변조·순서 오류·관측 실패는 일치로 처리하지 않는다.

읽은 명령을 코어에 다시 적용하는 복구 기능이 아니다. 단일 노드 결과를 세 사본 복제나 장애 전환으로 확대하지 않는다. [#99 상세](https://github.com/0Chord/coin-exchange/issues/99).

</details>

<details markdown="1">
<summary>다섯 PR 이후의 복구·장애 전환·외부 전달·성능 계약</summary>

아래는 다음 설계에서 채워야 하는 준비 조건이다. 현재 #95~99의 완료 조건에 넣거나 이미 구현·검증된 것으로 표시하지 않는다. 기존 #52~59·#66~70은 DB 중심 이력이 있으므로 새 엔진 경계에 맞춰 상세화한 뒤 사용한다. 이번에 새 티켓·실행 기능을 추가하지 않았다.

| 후속 영역 | 다음 상세 설계에서 반드시 정할 것 | 완료를 인정할 실제 증거 |
| --- | --- | --- |
| 영구 Snapshot·재시작 | 사용자 잔고뿐 아니라 초기 준비 원본/결과/OPENING, 예약/RESERVE, 대기 순서, 승인 순번, 사용 ID·최초 승인/거절 이력, 정책/버전·적용 위치를 함께 복원. Snapshot과 이어 읽을 로그 위치를 연결 | 같은 저장 기록으로 재시작 후 필드별 대조·중복 재전송·후속 명령 처리. 버전/내용 불일치는 준비 완료 전에 차단 |
| 리더 종료·과반 상실·분할 | 확정되지 않은 새 명령은 승인하지 않음. 이미 확정·적용된 요청의 늦은 최초 결과 전달은 구분. 감지·선출·따라잡기·재연결·동일 ID 재확인 계약 | 실제 장애 주입과 복구 후 금액·원장·순번·최초 결과 대조. 정상 세 노드 대조만으로 대체하지 않음 |
| Kafka→조회 DB/알림 | 확정 결과의 재생 가능한 출처·이벤트 ID·순서·전달 cursor·ack, 수신 중복 처리와 DB 반영 경계, 보존/용량 한계에 닿기 전 제한 기준 | 발행/수신 중단 후 재전달·중복 수신·뒤늦은 이벤트·누락 검출. Aeron egress나 Kafka 사용 사실만으로 유실 없는 전달을 주장하지 않음 |
| 성능·운영 | 접수 승인 지연과 매칭·정산 지연을 구분. 하드웨어·네트워크·복제 수·실제 sync 설정·메시지 크기/주문·부하·warmup·GC/CPU·대기열·거절/오류율을 기록 | 같은 정합성/저장 설정의 p50·p95·p99·지속 처리량·오류/미확인 비율·전환 시간 실측. 설정을 낮춘 다른 숫자를 같은 보장으로 비교하지 않음 |

“수십만 TPS·수백 μs·수 ms 무중단 전환”은 현재 약속이 아니다. Aeron 도입만으로 정해지지 않는다. 우선 현재 다섯 단계의 정확한 입력/상태/응답을 검증하고, 측정한 병목이 있을 때 Agrona·버퍼 재사용·WebFlux 등 선택의 필요성을 판단한다. [성능 제한](https://aeron.io/docs/aeron-cluster/performance-limits/), [장애 감지·선출](https://aeron.io/docs/cluster-quickstart/raft-consensus/), [Spring MVC/WebFlux 선택](https://docs.spring.io/spring-framework/reference/web/webflux/new-framework.html).

</details>

<details markdown="1">
<summary>#88·#49·#50과의 관계</summary>

- [#50](https://github.com/0Chord/coin-exchange/issues/50)은 전체 접수 묶음이다. #95·#96은 그 직접 하위 선행이다. 다음 작업도 #95로 갱신했다.
- [#88](https://github.com/0Chord/coin-exchange/issues/88)은 #97·#98·#99를 추적하는 묶음이다. 이 셋이 완료되기 전 전체를 완료 처리하지 않는다.
- [#49](https://github.com/0Chord/coin-exchange/issues/49)는 기존 DB 지급과 중복 구현하지 않고 새 엔진의 시연 절차를 재사용하도록 바꿨다. 시연 자금 준비라는 목적은 유지하며 현재 선행은 #97·#98이다. 이전 DB 중심 본문·선행은 이력으로 보존했다.
- [#89](https://github.com/0Chord/coin-exchange/issues/89)는 #88 이후의 세 사본 정상 상태 대조다. 같은 적용 위치·초기 설정·의미 필드 비교와 실제 저장 Context 검증을 보완했으며 장애 전환·복구 구현은 넣지 않았다.

이 분할을 위해 다른 완료 티켓을 일괄 닫거나 기존 우선순위를 바꾸지 않았다. 제품 코드·테스트·PR은 만들지 않았다.

</details>

<details markdown="1">
<summary>실제 문답과 결정 · 2026-10-17</summary>

**범위:** 사용자는 “이거는 #88은 아닌거 같은데 #88 크기가 너무 큰거 아니야?”라고 지적했다. AI가 5개 PR(코어 자금·Protobuf·실제 준비·실제 접수·기록 대조)로 나누고 #88을 뒤 세 작업의 묶음으로 두는 안을 제안했다. 사용자 답변: **“ㅇㅇ … 그렇게 다 쪼개봐 그래야 하나하나 이어서 할 수 있지”**. 이에 따라 #95~99를 발급하고 관계를 연결했다.

**Ask 질문:** “실제 주문을 연결하는 개발 단계에서는 BTC/USDT 마켓·수량 단위·수수료 정책을 노드 실행 설정으로 고정하고, 주문에 담긴 값이 다르면 돈을 예약하기 전에 거절하는 방식으로 갈까? 이 방식을 추천해. 정책을 실행 중 바꾸는 관리 기능은 후속으로 두고, 이번에는 같은 설정으로 재현 가능한 접수 흐름에 집중하려는 거야.”

- AI 추천은 고정된 개발 설정과 신규 불일치 거절이다. 재현 가능한 작은 단위에 집중하기 위해서다.
- 대안은 정책 준비·변경 기능까지 별도 선행 이슈로 설계하는 것이었다.
- 사용자 선택 원문: **“개발 실행 설정으로 고정하고 불일치 거절 (추천)”**. 별도 이유는 답하지 않아 추측해 적지 않는다.
- #98에 설정 검사·최초 결과 재사용의 순서와 수용 사례를 반영했다. 동적 정책 관리·운영 배포 보장은 추가하지 않았다.

이전 초기 자금 반복 Ask의 사용자 선택은 “같은 요청은 최초 결과 재사용, 내용 변경은 충돌 (추천)”이며 #95에 보존했다. 기술적 클래스명·구체 내부 자료구조는 계약을 지키는 구현 제안이다.

**공식 자료 비교 후 요청:** 사용자는 다른 AI의 Aeron 설명을 검토한 뒤 “너가 비교해서 추가해야하는 부분은 추가해서 최종 상세 명세 작성해봐”라고 요청했다. 이미 합의한 자금 반복·설정·분할은 유지하고, 실제 1.53.3 API와 누락된 실패 경계에 근거한 기술 계약·수용 사례를 보완했다. 전송 종류 4/5/6, 설정 불일치 사유 9, 실제 Context 수준 2는 이 문서와 관련 이슈의 현재 구현 기준이다. 스냅샷/HA/성능을 이번 PR로 확대하는 새 합의로 해석하지 않는다.

</details>
