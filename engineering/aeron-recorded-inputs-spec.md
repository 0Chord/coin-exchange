<!-- aeron-admission-split-20261017:archive -->
# 보낸 주문이 실제 Aeron 기록에 남았는지 확인하기

대상: [#99](https://github.com/0Chord/coin-exchange/issues/99) · 상위: #88 · 선행: #97·#98 · 명세 기준: 2026-10-20 통합 `865e14d0a2463f4f7009334360f6bc626c206eff` · 현재 구현·검증 상태: [구현 흐름과 테스트](aeron-recorded-inputs-review.md)

**이번에 만들 결과는 하나다. 보낸 자금 준비·주문 메시지를 실제 Archive에서 읽어, 내용·마켓/수수료 정책·순서가 같은지 확인한다.** Archive는 Aeron의 입력 기록을 저장하는 구성 요소다. 파일이 있다는 확인에서 끝내지 않고 파일에 기록된 메시지를 실제로 읽는다.

기존 개발 노드와 접수 기능을 사용한다. 읽어 낸 주문을 엔진에 다시 실행하거나, 서버를 재시작해 잔고를 복원하는 기능은 만들지 않는다.

## 로그에 두 번 남아도 돈은 한 번만 묶이는 이유

로그는 받은 입력을 남긴다. 엔진은 같은 요청을 이미 처리했는지 판단한다. 따라서 같은 R1을 두 번 보내면 로그에는 R1이 두 번 있지만, 엔진은 두 번째에 최초 결과만 돌려준다.

| 보내는 순서 | 실제 기록에서 찾을 입력 | 처리 후 사용 가능 USDT | 처리 후 예약 USDT |
| --- | --- | ---: | ---: |
| 1 | P1: U1에게 개발용 1,000 USDT 준비 | 1,000 | 0 |
| 2 | R1/O1: 100 USDT × BTC 3개 매수 | 700 | 300 |
| 3 | R2/O2: 100 USDT × BTC 8개 매수 → 부족 거절 | 700 | 300 |
| 4 | 같은 R1/O1 재전송 → 최초 승인 반환 | 700 | 300 |

수수료 0인 기본 설정의 예다. **비교할 업무 입력은 네 건이고, 예약은 한 번이다.** OPENING 한 거래와 RESERVE 한 거래, 대기 주문 한 건, 최초 주문 요청 결과 두 건(R1 승인·R2 거절), 승인 순번 1을 유지한다. 실제 로그에는 세션 시작·종료 같은 제어 메시지도 있으므로 전체 로그 메시지가 네 개라고 가정하지 않는다.

검사할 때는 네 번째 입력까지 처리한 끝 위치를 한 번 고정하고, 거기까지만 기록을 읽는다. 이 위치를 아래 상세에서 **L**이라고 부른다. 대조가 돈을 다시 지급하거나 예약하면 안 된다.

## 먼저 할 작은 작업

같은 실행에서 P1과 R1을 보내고, 실제 송신 바이트·처리 완료 위치와 Archive에서 꺼낸 바이트를 연결한다. 이것이 첫 정상 테스트다. 이어서 위 네 입력, 잘못된 비교 값, 읽기 실패와 범위 제한 사례를 보강해 한 PR을 마무리한다.

<details markdown="1">
<summary>1. 최신 코드에 이미 있는 것과 이번에 더할 것</summary>

[PR #103](https://github.com/0Chord/coin-exchange/pull/103)은 2026-10-20 01:57 KST에 병합됐다. 조회한 최신 통합 브랜치는 `feature/phase-2/integration`의 `865e14d`다. 그 트리와 PR head `a9fc74b`의 파일 내용 차이는 없다. 선행 CI 두 작업의 성공은 #98의 근거이며 #99 기록 대조의 실행 결과가 아니다.

| 현재 코드·확인한 사실 | #99에서 할 일 |
| --- | --- |
| [Node의 Archive 연결 설정](https://github.com/0Chord/coin-exchange/blob/865e14d0a2463f4f7009334360f6bc626c206eff/app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingNode.kt#L76)은 같은 MediaDriver의 IPC 제어 채널을 사용한다. | 이 연결을 재사용해 살아 있는 같은 노드의 기록을 읽는다. |
| [Node.recordings](https://github.com/0Chord/coin-exchange/blob/865e14d0a2463f4f7009334360f6bc626c206eff/app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingNode.kt#L235)는 실제 로그 stream의 archiveId·recordingId·logSessionId를 돌려준다. 한 번에 최대 100개이며 descriptor의 다른 필드는 버린다. | 목록의 첫 항목/가장 큰 ID를 바로 선택하지 않는다. 활성 counter·descriptor를 교차 확인한다. 조회 범위가 불완전하거나 후보가 모호하면 성공시키지 않는다. |
| [Adapter의 정상 관찰값](https://github.com/0Chord/coin-exchange/blob/865e14d0a2463f4f7009334360f6bc626c206eff/app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingAdapter.kt#L132)은 처리 위치·완성된 상태 사본·결과를 함께 담는다. 최신 한 개만 보관한다. | 검사 쪽이 요청을 하나씩 보내고 각 관찰값을 즉시 복사해 보관한다. 무한 이력 저장소나 도메인 상태 필드를 추가하지 않는다. |
| [자금 Client.send](https://github.com/0Chord/coin-exchange/blob/865e14d0a2463f4f7009334360f6bc626c206eff/app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingClient.kt#L96)와 [주문 Client.send](https://github.com/0Chord/coin-exchange/blob/865e14d0a2463f4f7009334360f6bc626c206eff/app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentOrderClient.kt#L89)는 실제 offer에 쓰는 bytes·양수 위치를 외부로 전달하지 않는다. | 실제 수용된 바이트의 독립 복사본과 전송 위치를 한 번 알려 주는 좁은 내부 관찰 지점을 추가할 수 있다. 재인코딩한 값을 실제 송신 바이트라고 대신 쓰지 않는다. |
| [기존 실제 노드 테스트](https://github.com/0Chord/coin-exchange/blob/865e14d0a2463f4f7009334360f6bc626c206eff/app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentOrderNodeTest.kt#L42)는 통신 후 메모리의 잔고·예약·원장을 비교한다. | 기존 성공을 재사용하되 실제 Archive 원문 대조의 새 근거를 추가한다. recording 존재·mock 위치·응답 바이트로 대체하지 않는다. |

**배치:** 실제 Archive reader·결과 비교 helper와 통합 테스트는 기존 `app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron` 안에 둔다. `AeronRecordedInputTest`는 명세 작성 당시 미존재했으며 이후 이 위치에 추가했다. Client·Node의 보완은 실제 바이트·식별 관찰에 필요한 좁은 내부 API만 허용한다. 새 모듈·범용 전송 계층·운영 로그 조회 API를 만들지 않는다.

`domain-trading`의 돈 계산·최초 결과·예약 규칙과 기존 Protobuf 헤더/schema 87/version 2·명령 종류 1/4는 유지한다. 기존 HTTP·SQL·기본 실행 진입점은 바꾸지 않는다.

</details>

<details markdown="1">
<summary>2. 보낸 값·처리 위치·최종 상태를 어떻게 연결하는가</summary>

1. 새 실행 폴더의 단일 개발 노드를 시작하고 기본 BTC-USDT·scale 8·SPOT/NORMAL/version 1·maker/taker 0ppm을 고정한다. 기존 폴더 삭제·재기동으로 검사 환경을 만들지 않는다.
2. P1 → R1 → R2 → R1을 한 요청씩 전송한다. 실제 offer에 사용한 애플리케이션 frame의 복사본과 원래 명령 값을 검사 쪽에 보관한다. 음수 역압력 재시도는 새 입력 건수가 아니며, 별도 호출한 R1 재전송은 새 전송 입력 한 건이다.
3. 각 응답과 같은 입력을 가리키는 정상 관찰값을 연결한다. `failure == null`, `blocked == false`, 정상 snapshot·결과가 있고, 처리 위치가 직전 입력보다 증가해야 한다. 같은 R1의 응답 내용·승인 순번은 같아도 처리 위치는 다르다. 응답 전후 같은 위치의 관찰값 교체는 별도 입력으로 세지 않는다.
4. 손상 입력·fatal 관찰값의 position만 보고 성공 위치로 채택하지 않는다. 시간 초과·노드 오류·대응 관찰값 누락이면 검증 불가다. 100ms 주기의 콘솔 출력으로 중간 입력 증거를 대신하지 않는다.
5. 네 번째 정상 처리 관찰값의 position을 **L로 한 번 고정**한다. 응답 수용이나 클라이언트 offer 위치를 L로 쓰지 않는다. 승인 순번도 로그의 바이트 위치가 아니다.
6. 대조 전후 엔진 상태의 잔고·예약·원장·대기·최초 결과·승인 순번 의미 필드가 같아야 한다. Archive 읽기가 `state.admit`·자금 준비 함수를 호출하거나 입력을 ingress에 재전송해서는 안 된다.

비교의 원본은 두 가지다. **바이트 기대값은 실제 송신 frame의 복사본**, **업무 값 기대값은 독립적으로 준비한 입력 값**이다. Archive에서 읽은 값을 다시 인코딩해 기대 바이트를 만들거나, 읽은 정책을 그대로 기대 정책으로 사용하지 않는다. 기존 코덱은 해석에 재사용한다.

기록의 원장 ID·업무 Instant의 초/나노초·주문 원본·마켓 ID/기준 자산/표시 자산/수량 scale·수수료 상품/등급/버전/maker/taker를 필드별로 대조한다. Cluster callback timestamp는 별도 관찰 값이며 본문 업무 시각을 덮어쓰지 않는다. 순서를 지키는 한글 식별자·나노초·비영점 수수료 입력도 별도 사례로 확인한다.

이 위치의 공식 근거는 [ClusteredServiceAgent가 위치를 설정한 뒤 callback을 호출하는 코드](https://github.com/aeron-io/aeron/blob/1.53.3/aeron-cluster/src/main/java/io/aeron/cluster/service/ClusteredServiceAgent.java#L485)와 [commit 상한 안에서 메시지를 전달하는 BoundedLogAdapter](https://github.com/aeron-io/aeron/blob/1.53.3/aeron-cluster/src/main/java/io/aeron/cluster/service/BoundedLogAdapter.java#L121)다. 이번 명세에서 동작을 새로 실행한 것은 아니다.

</details>

<details markdown="1">
<summary>3. 실제 Archive에서 어디까지 읽는가</summary>

**노드가 살아 있는 동안 읽는다.** 현재 Node 종료는 Archive·Driver도 닫는다. 별도 replay 구독으로 기록 바이트를 읽는 것은 기존 노드에 업무를 다시 적용하는 복구가 아니다.

1. **같은 기록을 식별한다.** 같은 실행의 Archive·MediaDriver counters와 실제 로그 session/stream을 사용한다. `RecordingPos.findCounterIdBySession(counters, logSessionId, archiveId)` 및 `getRecordingId`를 descriptor와 대조한다. 로그 publication의 int session ID와 클라이언트의 long clusterSessionId를 혼동하지 않는다. archiveId·recordingId·로그 session·stream·channel·startPosition을 확인하고 활성 기록을 하나로 확정한다.
2. **유효한 읽기 범위를 확인한다.** descriptor의 시작 위치를 S로 두고 `S < L`을 확인한다. 실제 recording 위치가 L에 도달할 때까지 상한 있는 대기를 사용한다. 대기 중 counter는 `RecordingPos.isActive(counters, counterId, recordingId)`로 재사용 여부도 확인한다. descriptor·`getStartPosition`·`getMaxRecordedPosition`의 정보가 일관돼야 한다. term/recording을 바꾸어야 하는 범위는 이번 새 단일 실행 시나리오의 지원 범위를 벗어나므로 조용히 추정하지 않는다.
3. **고정한 끝까지 유한 길이로 읽는다.** `startReplay(recordingId, S, L-S, replayChannel, replayStreamId)`를 사용한다. 읽기용 채널·stream은 업무 ingress/egress와 분리한다. 제안은 같은 Driver의 별도 IPC replay 구독이다. live follow·최신 recording 끝·`Long.MAX_VALUE`를 L 대신 사용하지 않는다. `startBoundedReplay`의 별도 limit counter와도 혼동하지 않는다.
4. **이번 replay의 Image만 받는다.** 반환된 replay session long 전체는 replay 정리에 사용하고, 하위 32비트는 Image session 식별에 사용한다. 원래 로그 session과 replay session은 다르다. 남의 Image나 재사용된 counter로 성공시키지 않는다.
5. **조각을 재조립하고 Cluster wrapper를 해석한다.** FragmentAssembler와 라이브러리가 제공하는 MessageHeader/SessionMessageHeader decoder·상수를 재사용한다. 현재 1.53.3의 Cluster schema 111·session template 1·session header 길이 32를 기준으로 wrapper를 제거한 **애플리케이션 frame**을 꺼낸다. 우리 Protobuf 본문뿐 아니라 그 앞의 8바이트 애플리케이션 헤더까지 원본과 비교한다.
6. **제어 이벤트와 업무 입력을 구분한다.** 알려진 SessionOpen/Close·Timer·ClusterAction·NewLeadershipTerm·Membership 이벤트는 별도 제어 메시지로 분류한다. 이를 업무 명령 수에 넣지 않으며, 알 수 없는 schema/template·잘린 wrapper·재조립 미완료를 조용히 건너뛰지 않는다. 코어 호출은 없다.
7. **실제 위치·내용·순서를 함께 비교한다.** 완성 메시지의 `Header.position()`과 수집한 처리 위치를 연결한다. position은 메시지 뒤의 aligned stream 위치이며 payload 길이를 더해 만들지 않는다. termBaseLogPosition을 임의로 다시 더하지 않는다. R1의 두 입력을 요청 ID 하나로 합치지 않고 순번별 발생으로 비교한다.
8. **끝에 도달했는지 확인하고 정리한다.** 이번 Image의 consumed position과 마지막 대상 완성 메시지의 끝 위치가 모두 L이어야 한다. 예상 업무 입력이 전부 맞아야 성공이다. `poll()==0`, 응답 완료, image close 또는 EOS만으로 성공시키지 않는다. L 초과·L 이전 종료·제한 시간 초과·중간 오류는 성공이 아니다. 자기가 시작한 replay·구독만 정리하고 기존 기록을 삭제하거나 원래 recording을 중단하지 않는다.

유한 replay도 요청 길이의 정확한 임의 바이트에서 강제로 자르는 장치는 아니다. **L이 실제 완성 메시지 끝이어야 한다.** 종료 기록의 짧아진 길이나 frame 단위 전송 때문에 조기 종료·초과를 놓치지 않도록 위의 끝 위치 확인을 둔다. Aeron 전송 session/stream 헤더는 replay에서 달라질 수 있어 원본 바이트 일치 범위를 애플리케이션 frame으로 한정한다.

공식 1.53.3 근거: [RecordingPos](https://github.com/aeron-io/aeron/blob/1.53.3/aeron-archive/src/main/java/io/aeron/archive/status/RecordingPos.java#L204), [descriptor](https://github.com/aeron-io/aeron/blob/1.53.3/aeron-archive/src/main/java/io/aeron/archive/client/RecordingDescriptorConsumer.java#L47), [startReplay 계약](https://github.com/aeron-io/aeron/blob/1.53.3/aeron-archive/src/main/java/io/aeron/archive/client/AeronArchive.java#L1026), [길이 처리](https://github.com/aeron-io/aeron/blob/1.53.3/aeron-archive/src/main/java/io/aeron/archive/ArchiveConductor.java#L831), [frame 단위 전송](https://github.com/aeron-io/aeron/blob/1.53.3/aeron-archive/src/main/java/io/aeron/archive/ReplaySession.java#L393), [Header.position](https://github.com/aeron-io/aeron/blob/1.53.3/aeron-client/src/main/java/io/aeron/logbuffer/Header.java#L91), [재조립 Header](https://github.com/aeron-io/aeron/blob/1.53.3/aeron-client/src/main/java/io/aeron/BufferBuilder.java#L247).

</details>

<details markdown="1">
<summary>4. 정상·불일치·읽기 실패의 수용 사례</summary>

결과는 **일치 / 불일치 / 검증 불가**를 구분한다. 바깥 HTTP 응답이나 운영 조회 제품을 추가한다는 뜻이 아니다. 정상 대조의 성공 결과는 ‘일치’다. 의도적 불일치·읽기 실패 테스트는 예상한 ‘불일치’ 또는 ‘검증 불가’를 정확히 검출했을 때 통과한다.

- **일치:** 올바른 기록·L과 완전한 읽기를 확인하고 바이트·필드·순서·건수·처리 위치가 모두 같다.
- **불일치:** 범위의 기록을 완전히 읽었지만 기대와 다른 바이트·필드·순서·건수·처리 위치가 확인된다. 같은 원인으로 여러 필드가 어긋나면 함께 설명한다.
- **검증 불가:** 기록/관찰 식별 실패, 읽기 오류·조기 종료·시간 초과·지원하지 않는 wrapper 등으로 필요한 증거를 온전히 얻지 못했다. 일부 일치한 행만으로 전체 성공을 만들지 않는다.

완전히 읽은 애플리케이션 본문을 해석하지 못해도, 이미 확인한 바이트·위치·건수·필드 차이는 버리지 않는다. 다른 입력도 계속 비교해 **확인된 차이가 있으면 불일치**, 차이가 확인되지 않고 해석만 실패했으면 검증 불가로 보고한다. 두 경우 모두 해석 실패 이유를 남긴다. Cluster wrapper를 읽지 못해 범위의 증거가 불완전한 경우와 구분한다.

| 준비·실행 | 기대 결과와 보존할 것 |
| --- | --- |
| 첫 정상 사례: P1 → R1을 실제 전송하고 Archive를 읽음 | 실제 송신 바이트·업무 값·정책·순서·callback 끝 위치가 모두 일치. 읽기 전후 상태 불변 |
| 대표 네 입력 P1 → R1 → 부족 R2 → R1 반복 | 대상 업무 입력 네 건. R1 입력 두 건의 끝 위치가 다르고 순서는 유지. available 700 USDT·hold 300 USDT, OPENING/RESERVE 각 1거래·대기 1건·최초 주문 요청 2건·승인 순번 1 |
| 같은 R1을 읽을 수 있는 다른 fee/원장 ID/시각으로 재전송하는 별도 사례 | 실제 기록은 그 재전송의 바이트·값을 보존. 엔진의 최초 결과·정책·예약은 처음 값 유지. 입력 기록을 최초 결과로 정규화하지 않음 |
| 한글 ID·나노초 Instant·비영점 maker/taker 정책의 정상 입력 | 실제 전송 원본과 업무 필드 일치. callback 시각·로컬 now가 업무 시각을 대체하지 않음. 금전 기대값은 기존 도메인 명세의 독립 숫자 사용 |
| 실제 기록에 대해 기대 바이트/정책 한 필드·원장 ID·입력 순서를 의도적으로 다르게 준비 | 불일치. 바이트와 의미 필드 비교의 실패를 각각 확인. Archive 원본 파일 자체를 손상시키지 않음 |
| 완전한 범위에 대해 기대 입력을 하나 빼거나 더 넣음 | 건수·순서 불일치. 요청 ID로 중복을 제거해 빈틈을 숨기지 않음 |
| 완전히 읽은 본문이 미지원 업무 종류·손상 Protobuf여서 해석 실패 | 앞뒤 입력의 차이와 원본 바이트 차이를 보존하고 해석 실패도 보고. 같은 바이트라 해석 실패만 있으면 검증 불가, 이후 입력에서 차이를 발견해도 불일치 |
| L 고정 뒤 새 정상 R3를 보내 기록이 L 뒤에도 존재 | 최초 L까지의 네 입력만 비교. R3는 보고서의 범위 밖. 읽기 전후 상태 불변은 R3 처리 후 상태를 기준으로 비교하며 L 시점 사본과 섞지 않음 |
| 잘못된/모호한 recording, 다른 session/stream, counter 재사용, 기록 위치가 시간 내 L 미도달 | 검증 불가. 현재 메모리 목록·응답 DTO로 보충해 일치시키지 않음 |
| 읽기가 L 전에 닫힘·EOS·기한까지 L 미도달·재조립 미완료·미지원 wrapper | 검증 불가. 일시적인 0 poll은 기한 안에서 계속 기다리며 즉시 실패로 단정하지 않는다. 읽지 못한 범위를 표시 |
| fragment·비영 offset·알려진 제어 이벤트가 섞임 | 완전한 업무 frame·실제 끝 위치를 추출. 실제 UDP/Archive 경로의 입력 하나는 MTU payload보다 크고 4096바이트 이하여서 fragmentation을 실제 확인. 조각/offset 단위 경계 검사와 실물 검사를 구분 |
| 코어 실패·blocked 또는 정상 관찰값을 입력과 연결할 수 없음 | 적용 완료 상한으로 채택하지 않고 검증 불가. 정상 자금 부족 거절은 유효한 처리 입력이므로 위의 대표 사례에 포함 |

실제 통합 사례는 진짜 노드·UDP·Archive reader를 거친다. reader의 조기 종료·모호한 식별·parser 경계 등 통제 사례는 그 수준을 분리해 기록한다. 단일 노드에서 실제 미확정 꼬리를 만들기 위해 합의 장애 실험을 추가하지 않는다. L 뒤의 정상 기록 제한 검사는 ‘미확정 꼬리 장애 시험’이 아니다.

빈 기대 목록·빈 추출 목록·대상 0건·검사 skip·오류 무시는 완료 근거가 아니다. 읽은 데이터가 모자라면 원인과 마지막 확인 위치를 남긴다.

</details>

<details markdown="1">
<summary>5. 한 PR의 구현 순서·검증·완료 기준</summary>

구현 브랜치는 `test/aeron-recorded-inputs/99`다. 명세를 작성했던 `docs/aeron-recorded-inputs/99`와 구분한다. 내부 helper 이름·파일 수·제어 이벤트 출력 형식은 구현자가 작은 형태로 조정할 수 있다.

1. **실물 연결 첫 테스트:** P1·R1의 수용 바이트와 성공 관찰 위치를 모으고 같은 Archive의 유한 범위를 읽어 두 입력을 대조한다.
2. **최소 연결 구현:** 필요한 송신 관찰 지점, 기록 식별/reader, 애플리케이션 frame 추출·비교만 추가한다. reader는 도메인 변경 함수를 호출하지 않는다.
3. **반복·거절·범위 보강:** 네 입력과 의도적 기대값 불일치, L 이후 제외, 읽기 실패·fragment/wrapper 사례를 보강한다. 실패를 일치로 바꾸거나 지원 범위를 몰래 줄이지 않는다.
4. **회귀와 설명:** 기존 자금·주문 통신/코덱·도메인, 구조 검사·필수 보고서·공통 lint를 확인한다. 각 새 테스트의 준비 → 실행 → 실제 assertion과 실제 실행/미실행을 자연어로 설명한다.

예상 검사는 기존 `:app-api:test`, `:domain-trading:test`, `:architecture-tests:test`, `:architecture-tests:verifyArchitectureReport`, `ktlintCheck`를 사용한다. 새로운 실행 task·CI workflow·언어를 만들지 않는다. `AeronRecordedInputTest`가 실제 추가된 뒤 선택 대상에 넣는다.

아래는 **명세 작성 당시 예상한 task·필터**다. 재실행할 때는 체크아웃한 저장소 루트에서 실행한다. 이후 실제 실행 범위·결과·재사용한 근거는 [구현 읽기](aeron-recorded-inputs-review.md)에 구분해 기록한다.

~~~~sh
./gradlew -p . :app-api:test --tests 'com.exchange.core.api.trading.infrastructure.aeron.*' --tests 'com.exchange.core.api.trading.infrastructure.codec.*'
./gradlew -p . :domain-trading:test :architecture-tests:test :architecture-tests:verifyArchitectureReport ktlintCheck
~~~~

HTML/XML 근거는 실행한 폴더의 각 모듈 `build/reports/tests/test/index.html`·`build/test-results/test/TEST-*.xml`와 실제 lint 보고서다. 대조 결과에는 기준 commit·단일 호스트/노드·실제 저장 설정 `[2,2,2]`(Archive data/catalog·Consensus metadata)·recording 식별·S/L·실제 읽은 끝·전송 수용 위치·업무 순번·응답 수신을 구분해 남긴다. 명령마다 원본 바이트·해석한 값·실제 끝 위치와 불일치/관측 실패 이유를 연결한다.

아래 체크는 로컬 기술 검증 상태이며 PR·원격 CI·병합·사용자의 이해 확인을 뜻하지 않는다.

- [x] 첫 실제 Archive 정상 대조와 위의 반복·정상 거절·불일치·누락·읽기 실패·상한·fragment 사례를 구분해 검증했다.
- [x] 완전한 읽기와 마지막 메시지 끝 L, 바이트·필드·순서·건수를 함께 확인했다.
- [x] 입력 네 건과 예약 한 번을 실제 상태에 연결하고, 읽기 전후 엔진 상태가 같다.
- [x] 관련 회귀·구조 P01~P09·lint를 확인하고 skip/미실행/환경 실패를 따로 기록했다.
- [x] 실제 구현 폴더·브랜치·존재하는 명령·보고서·테스트 설명을 남겼다.
- [x] 코드·테스트·설명을 독립 리뷰했고, 확인된 결함을 수정·재검증했다. PR·CI·병합과 로컬 실행 상태를 구분했다.

**명세 작성 당시의 기술 확인 수준:** 실제 프로젝트 소스와 Aeron 1.53.3의 공식 소스/캐시된 동일 버전 source JAR를 읽어 API·callback·위치 의미를 대조했으며 그 단계에서는 reader·테스트·노드 실행·Archive 읽기 실험을 하지 않았다. 이후 구현의 실제 reader 연결·실패 원인·수정·실행 결과는 [구현 읽기](aeron-recorded-inputs-review.md)에서 확인한다. 명세를 읽은 사실을 실행 근거로 쓰지 않는다.

</details>

<details markdown="1">
<summary>6. 이번에 하지 않는 것과 후속 범위</summary>

복구를 위한 코어 재적용·스냅샷 저장/복원·프로세스 재시작·주문장 복원·세 노드 복제·장애 전환·Kafka/DB 적재·HTTP 전환·성능 보장은 제외한다. `startReplay`로 바이트를 읽었다는 사실을 잔고 복구 성공이라고 표현하지 않는다. fsync 설정이나 파일 읽기 성공만으로 전원 장애·호스트 유실 내구성을 증명하지 않는다.

개발용 자금 지급 경로 제거·프로덕션 입금 연결도 제외한다. P1은 기존 개발 기능을 쓰는 검사 준비 단계다. 이 입력을 실제 기록에서 읽는 것이 이번 작업이며 자금 준비 기능을 새로 만드는 작업이 아니다.

#97 → #98 → #99의 연결 순서, 처리 완료 상한, 원본 바이트와 의미 필드 대조, 기존 HTTP/SQL 유지 및 복구 제외를 유지한다. #99 이후에는 #88의 전체 완료 기준을 확인하고 #89의 세 노드 정상 상태 대조로 이어간다. PR·원격 CI·병합은 로컬 구현과 구분한다.

</details>
