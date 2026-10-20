# 보낸 주문을 기록에서 꺼내 확인하기

<!-- local-verification: complete -->

**주문을 보내 돈을 예약한 뒤, 실제 Archive 파일에서 같은 입력을 읽어 원본과 비교한다. 읽기는 돈을 다시 움직이지 않는다.**

기준은 통합 `865e14d`와 #99의 로컬 변경이다. 브랜치는 `test/aeron-recorded-inputs/99`다. 로컬 구현·검증·독립 리뷰를 마쳤다. 원격 CI와 사람의 PR 리뷰 상태는 GitHub에서 확인한다. [합의한 명세](aeron-recorded-inputs-spec.md)를 유지한다.

## 먼저 볼 흐름

1. 기존 클라이언트로 자금 준비 P1과 주문 R1을 보낸다. 실제 전송 바이트의 복사본을 보관한다.
2. 기존 엔진이 처리한 끝 위치를 고정하고, 같은 실행의 Archive를 그 위치까지만 읽는다.
3. 보낸 바이트·주문 값·정책·순서를 대조하고, 읽기 전후 잔고·예약·원장·최초 결과가 그대로인지 확인한다.

수수료 0인 대표 사례다. **기록에서 찾을 업무 입력은 네 건, 돈을 새로 예약한 것은 한 번이다.**

| 보낸 입력 | 엔진의 판단 | 사용 가능한 USDT | 예약된 USDT |
| --- | --- | ---: | ---: |
| P1: 시작 자금 1,000 준비 | OPENING 한 거래 | 1,000 | 0 |
| R1: 가격 100 × BTC 3개 매수 | 승인·RESERVE 한 거래 | 700 | 300 |
| R2: 가격 100 × BTC 8개 매수 | 100 부족해서 거절 | 700 | 300 |
| 같은 R1 재전송 | 첫 승인 결과 반환 | 700 | 300 |

**이번에 추가한 것은 이 입력 기록을 읽고 비교하는 검사다.** 실제 주문장 매칭·정산·재시작 복구·세 노드 장애 전환은 이 검사로 증명하지 않는다. DB·Kafka·기존 HTTP 경로는 바꾸지 않는다.

<details markdown="1">
<summary>1. 기록을 읽어도 왜 돈이 다시 움직이지 않을까</summary>

실행과 검사의 목적지를 나눴다.

~~~~text
기존 주문 실행
클라이언트 → Protobuf → Aeron → Adapter → OrderAdmissionState.admit
                                            └─ 잔고·예약·원장 변경

이번 기록 검사
Archive → 읽기 전용 replay 구독 → 조각 합치기 → 명령 값 해석 → 비교 결과
                                                   └─ 엔진 함수 호출 없음
~~~~

Archive의 replay는 저장한 바이트를 읽기용 구독에 보내는 기능이다. 이번 reader는 그 바이트를 비교기에 전달한다. 자금 준비 함수·`state.admit`·업무 ingress로 다시 보내는 코드는 없다.

검사 직전 현재 상태를 의미 필드로 복사하고, 읽기 뒤의 상태와 비교한다. 잔고만 확인하는 것이 아니라 승인 순번·대기 주문·예약·OPENING/RESERVE의 거래 ID/사건 ID/종류/시각/분개·최초 승인/거절 결과를 포함한다. L 뒤에 R3를 보낸 사례는 **R3 처리 뒤 상태**가 비교 기준이다.

같은 R1이 기록에서 두 번 발견되어도 reader는 이를 두 번의 입력으로 보존한다. 엔진이 최초 결과를 재사용한 것과 입력이 두 번 기록된 것을 섞지 않는다. 읽은 입력을 요청 ID로 합쳐 버리면 누락을 놓칠 수 있다.

관찰값의 메모리 상태 사본은 검사용이다. 영구 스냅샷 저장·복원 기능을 추가한 것이 아니다.

</details>

<details markdown="1">
<summary>2. 실제 노드·UDP·Archive를 거치는 테스트 읽기</summary>

아래는 [AeronRecordedInputTest.kt](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/AeronRecordedInputTest.kt)의 8개 테스트다. 실제 노드·통신·Archive는 대역으로 바꾸지 않는다. 실패 관찰값을 통제하는 마지막 사례의 범위는 따로 표시한다.

| 사례 | Given — 준비 | When — 실행 | Then — 실제 확인 |
| --- | --- | --- | --- |
| 자금 준비와 주문의 실제 원문 | 새 실행 폴더·단일 노드·빈 잔고 | P1 → R1 전송 후 그 끝까지 읽음 | 두 입력의 원본 바이트·업무 값·정책·처리 위치 일치, 제어 이벤트 분리, 자금 700/300 및 읽기 전후 전체 의미 상태 동일 |
| 반복·거절 입력 네 건 | P1으로 1,000 준비 | R1 300 승인 → R2 800 부족 거절 → R1 반복 → 기록 읽기 | 네 입력 순서·각 처리 위치가 일치, 반복 R1의 바이트는 같고 위치는 다름. OPENING/RESERVE 각 한 거래·대기 한 건·최초 주문 결과 R1/R2·승인 순번 1 |
| 기대값을 틀리게 준 검사 | P1/R1의 실제 기록을 완전히 읽음 | 기대 바이트만 변경, 업무 필드별 기대값만 변경, 위치·순서·건수 변경 | 각각 불일치와 해당 이유. 의미 값만 틀린 사례에는 바이트 불일치 이유가 없어야 함. 빈 기대 목록은 검증 불가 |
| 고정 범위 뒤의 새 주문 | P1/R1/R2/R1의 끝을 L로 고정 | 새 R3를 실제 처리한 다음 L까지 읽음 | 처음 네 입력만 대조하고 R3 위치 제외. 현재 자금 600/400·승인 순번 2를 읽기가 바꾸지 않음 |
| 재전송의 달라진 문맥 | R1을 처음 정책으로 승인 | 같은 주문 원본의 R1을 다른 정책·원장 ID·나노초 시각으로 전송 | 실제 기록은 두 번째 입력을 그대로 보존. 엔진의 첫 정책·원장·시각·결과와 돈은 처음 값 유지 |
| 큰 한글 주문과 실제 조각 | 마켓·사용자·주문 등의 한글 ID, maker 1000/taker 2000ppm, 나노초 시각 | 실제 UDP payload보다 큰 정상 주문 전송 후 기록 읽기 | 실제 조각 둘 이상을 합쳐 원본과 같은 명령·끝 위치. 사용 가능 699.4 USDT·예약 300.6 USDT·최초 수수료 예약 0.6 USDT |
| 다른 기록과 기록 미도달 | 정상 P1/R1 기록 | 다른 Archive/recording/session/stream, 잘못되거나 빠진 채널 설정 8종, 아직 도달하지 않은 큰 L로 읽기 요청 | 일치로 보충하지 않고 검증 불가. 원래 노드는 오류 없이 유지 |
| 완료 상한으로 쓸 수 없는 관찰값 | 실제 P1의 정상 관찰값을 확보 | 이전 위치·관찰 누락·실패·blocked·상태/결과 누락을 통제 | 이전 값은 새 완료로 채택하지 않고 실패/누락 값은 거절. 실제 코어 장애·복구 실험은 아님 |

R1 재전송은 응답만 같다고 새 처리가 끝난 것으로 보지 않는다. 직전보다 큰 서비스 로그 위치와 정상 결과·상태를 함께 확인한다. 반면 서로 다른 클라이언트의 전송 위치는 전역 순번이 아니므로 양수 수용만 확인한다.

실제 송신 바이트와 원래 명령 값을 기대값으로 따로 보관한다. Archive에서 읽은 값을 다시 인코딩해 정답을 만들지 않는다. 각 정상 비교는 기록 식별·S/L·소비 위치·전송 수용 위치·원문 hex·업무 값을 테스트 출력에 남긴다.

</details>

<details markdown="1">
<summary>3. 파서·기록 식별의 경계를 확인하는 15개 테스트</summary>

이 표는 파일을 읽는 통합 검사와 구분한 단위/제어 검사다. 실제 Aeron 생성 decoder·FragmentAssembler·Agrona counter 구현은 사용하지만, 실제 서버 장애·불완전 디스크를 만들었다고 해석하지 않는다. 여섯 제어 종류나 여러 반례를 한 테스트 안에서 반복하는 경우도 있어 테스트 정의 수와 입력 조합 수는 다르다.

| 테스트 | Given → When | Then |
| --- | --- | --- |
| 비영 offset의 원문 | 버퍼 앞뒤에 다른 바이트를 두고 해당 메시지 구간만 해석 | 우리 헤더 포함 원본과 실제 끝 위치 보존, 반환 원문은 원본 버퍼와 분리 |
| 여섯 제어 template | SessionOpen/Close·Timer·ClusterAction·NewLeadershipTerm·Membership을 해석 | 업무 입력으로 세지 않고 제어 이벤트로 분류 |
| 미지원/잘린 wrapper | schema/version/template·blockLength·범위를 바꾼 입력 해석 | 조용히 건너뛰지 않고 거절 |
| 제어 메시지 변수 길이 | 길이 필드가 실제 callback 범위를 넘는 제어 입력 | 버퍼 용량이 더 커도 해당 메시지 밖으로 읽지 않음 |
| 두 조각 합치기 | 유효한 BEGIN/END 두 조각과 비영 offset 입력 | 원문 한 건·실제 aligned 끝 위치·조각 수 2 |
| 덜 끝난 조각 | BEGIN만 받은 채 소비 위치가 L인 상황 | 소비 위치만으로 성공하지 않고 재조립 미완료 |
| orphan·누락·중첩·조기 종료/초과 | 조각 순서와 종료 위치를 통제 | 완전한 읽기로 인정하지 않음 |
| 업무 입력 0건·제어 이벤트 끝 | 빈 범위 또는 제어 이벤트만 전달 | 대상 0건·업무 입력 끝이 아닌 L은 성공하지 않음 |
| 활성 기록 하나 | 활성 기록 하나와 일치하는 식별 정보 | 해당 기록 선택 |
| 기록 없음·모호함 | 후보 0개·2개, 기대 ID도 함께 제공 | 첫 항목·최대 ID·기대 ID로 모호함을 숨기지 않음 |
| 다른 기록 식별 | Archive/recording/session 중 하나가 다른 기대값 | 잘못된 기록을 선택하지 않음 |
| counter 해제·재사용 | 실제 Agrona RecordingPos 메타데이터를 준비한 뒤 해제·다른 기록/Archive/source로 재사용 | 예전 counter를 유효한 증거로 채택하지 않음 |
| 실제 publication 채널 연결 | 공식 PublisherPos 생성기로 92바이트 key보다 긴 전체 채널 메타데이터 준비 | 기록 태그·session·stream을 실제 publication에 연결하고 전체 채널 설정을 확인. 다른/누락된 routing·spy·tag·session은 거절 |
| publication 메타데이터 누락 | 잘린 label·key/label 불일치·다른 registration을 준비 | 부분 채널이나 추측 값으로 검사를 통과시키지 않음 |
| publication 없음·모호함·재사용 | 실제 counter 해제·두 후보·다른 registration으로 재사용 | 유효한 송신 채널을 하나로 확정할 수 없으면 거절 |

근거 파일: [RecordedClusterFrameParserTest.kt](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/RecordedClusterFrameParserTest.kt), [RecordedArchiveReaderTest.kt](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/RecordedArchiveReaderTest.kt).

</details>

<details markdown="1">
<summary>4. 어떤 파일이 바뀌었고 어디부터 읽을까</summary>

| 성격 | 파일과 책임 |
| --- | --- |
| 기존 개발 실행 코드의 작은 보완 | [자금 Client](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingClient.kt)·[주문 Client](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentOrderClient.kt): 양수 offer 때 원본 바이트 복사본·전송 위치를 선택적으로 관찰. 관찰자가 없으면 복사하지 않음 |
| 기존 개발 노드의 작은 보완 | [Node](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingNode.kt): 실제 로그 stream과 Aeron이 적용하는 기본 channel을 내부 getter로 제공. 실행 설정을 바꾸지 않음 |
| 테스트 도구 | [RecordedArchiveReader](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/RecordedArchiveReader.kt): 활성 기록과 descriptor/counter 대조 → 유한 구간 읽기 → L 완료 확인 → 자신의 구독/replay 정리 |
| 테스트 도구 | [RecordedClusterFrameParser](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/RecordedClusterFrameParser.kt): 조각 합치기, Cluster wrapper/제어 이벤트 구분, 원본 frame 추출 |
| 테스트 도구 | [RecordedInputComparison](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/RecordedInputComparison.kt): 완전한 읽기와 기대 입력을 대조해 일치·불일치·검증 불가로 구분 |
| 테스트 | 위의 실물 8개와 경계 15개 테스트. 새 돈 계산기·업무 상태·원장 생성 규칙은 추가하지 않음 |
| 문서 | 합의한 상세 명세와 이 구현/테스트 설명 |

Cluster는 기록 채널에 실행 중 publication의 tag/session 참조를 남긴다. 설정의 endpoint 문자열과 똑같을 것이라고 가정하지 않고 실제 기록의 채널 형태·매체·alias/tag/session 및 활성 counter를 함께 확인한다. 이어 그 태그와 session/stream의 실제 PublisherPos 메타데이터에서 전체 송신 채널을 확인한다. 이 counter의 위치를 L로 사용하지는 않는다.

Aeron 1.53.3은 MDC 로그에서 control-mode를 manual로 적용하고, 기본 control 바인딩이 켜져 있으면 현재 멤버 log endpoint의 포트를 0으로 바꾼다. Node의 새 getter는 공개 ChannelUri·ClusterMember API로 이 실제 기본 채널을 관찰할 뿐, 기존 launch 설정이나 플래그를 바꾸지 않는다. reader는 이 값과 실제 publication의 routing·나머지 기대 매개변수를 대조한다. key의 채널은 92바이트로 잘릴 수 있어 전체 label이 온전하고 key/registration과 맞는지도 확인하며, label이 잘렸으면 검증 불가다.

새 실행의 단일 활성 로그만 지원하며 여러 기록·leadership term을 묵시적으로 이어 붙이지 않는다.

처음 읽을 코드는 실물 테스트의 첫 두 사례, reader의 `read`, 비교기의 `compareRecordedInputs` 순서다. 세부 파서·제어 반례는 그다음에 펼쳐 읽어도 된다.

</details>

<details markdown="1">
<summary>5. 실제 검사·독립 리뷰와 남은 범위</summary>

아래 task·필터로 게시 대상과 같은 코드의 검사를 실행했다. 재실행할 때는 체크아웃한 저장소 루트에서 실행한다.

~~~~sh
./gradlew -p . :app-api:test --tests 'com.exchange.core.api.trading.infrastructure.aeron.*' --tests 'com.exchange.core.api.trading.infrastructure.codec.*' :domain-trading:test :architecture-tests:test :architecture-tests:verifyArchitectureReport ktlintCheck --console=plain
~~~~

실행 근거는 각 모듈의 `build/test-results/test/TEST-*.xml`, `build/reports/tests/test/index.html`, 구조 보고서 및 ktlint 보고서다. skip/실패/미실행을 통과 수에 합치지 않는다.

| 검사 | 확인 결과 | 실행 구분 |
| --- | --- | --- |
| 실제 통신·Protobuf 코덱 | 141개, 실패·오류·skip 0 | 채널 수정 후 실제 재실행. 신규 23개 포함 |
| 순수 도메인 | 100개, 실패·오류·skip 0 | 이번 구현에서 실제 실행했고 이후 도메인 변경이 없어 최종 명령은 UP-TO-DATE로 재사용 |
| 구조 검사 | 375개, 실패·오류·skip 0 | Node 관찰 getter 수정 후 실제 재실행 |
| 필수 구조 보고서 | P01~P09 실행·성공 | `verifyArchitectureReport` 확인 |
| 공통 린트 | 통과 | `ktlintCheck`. 자동 포맷 후 남은 긴 줄도 정리 |

최종 전체 명령은 BUILD SUCCESSFUL(2분 48초)이었다. 실제 큰 주문은 1,617바이트, ingress maxPayload는 1,376바이트였고 Archive에서 실제 두 조각을 재조립했다. 이 숫자는 해당 로컬 실행의 관찰값이며 지연 시간·처리량 벤치마크가 아니다.

독립 리뷰는 작성 대화를 상속하지 않는 별도 에이전트가 고정 사본과 파일 해시 manifest를 기준으로 수행했다. `review-v1`에서는 잘못된 기대 control 채널도 일치시키는 P2 한 건을 실제 노드 반례로 찾았다. 이는 기록 태그만 보고 실제 publication 설정까지 연결하지 않았던 원인이었다.

그 반례가 실패하는 회귀 테스트를 먼저 확인한 뒤, 실제 PublisherPos 메타데이터와 기대 채널을 연결했다. 라이브러리가 실행 시 적용하는 기본 채널은 Node의 관찰 getter에서 공개 API로 계산하며, 실행 플래그는 바꾸지 않았다. 정상 대조와 잘못된 control/mode/term/tag/session/endpoint·설정 누락·spy 사례, 메타데이터 잘림·모호함·counter 재사용을 보강했다.

수정된 `review-v2`는 manifest SHA-256 `97586b173fcba1d0d486fe56a52f74d898132b74380ebde9a22d8a198e7d2f1d`의 별도 고정 사본이다. v2 코드 9개 파일은 최종 전달 코드와 해시가 같다. 독립 리뷰어는 같은 검사 명령을 그 사본에서 전부 새로 실행해 616개·실패/오류/skip 0, P01~P09·린트 통과를 확인했다(BUILD SUCCESSFUL, 4분 19초). 실제 별도 노드에서 정상 채널·매개변수 순서만 바꾼 채널은 일치하고, 원래 잘못된 control 반례와 인접 잘못된 설정은 검증 불가임을 확인했다.

최종 판단은 **검토한 범위에서 확인된 미해결 결함 없음**이다. v2 이후에는 이 문서와 명세의 실행 결과·완료 표시·원격 문서 링크만 정리했으며, 코드·테스트는 바꾸지 않았다. 이후 코드가 바뀌면 그 영향 범위의 검사·재리뷰를 갱신한다. 이 판단이 모든 장애·미래 결함이 없다는 보장은 아니다.

복구·스냅샷 저장/복원·프로세스 재시작·세 노드 복제·장애 전환·Kafka/DB 적재·HTTP 전환·성능 수치 보장은 제외한다. 실제 `[2,2,2]` 저장 설정과 기록 읽기 성공으로 전원 장애·호스트 유실 내구성까지 보장하지 않는다. 관련 통신/코덱·도메인·구조·린트를 확인하며, 범위 밖 전체 DB/HTTP 제품 검사를 실행한 것으로 표현하지 않는다.

로컬 구현·검증과 PR·CI·병합은 별도다. 게시 전 사용자의 코드 이해 확인을 자동 완료로 표시하지 않는다.

</details>
