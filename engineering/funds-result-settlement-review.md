# #167 · 매칭 결과를 자금에 한 번 반영하는 흐름

기준 통합 커밋은 `05e28a91a77a1772451bbf6ee8a6e1d308e17054`(#172 병합)이며, 작업 브랜치는 `feat/funds-result-settlement/167`이다. 기록일은 2026-11-02다. 계약 원본은 [상세 명세](funds-result-settlement-spec.md)와 [#167](https://github.com/0Chord/coin-exchange/issues/167)이다.

**명세 범위의 로컬 구현·필수 검사·독립 리뷰를 완료했다.** 제품 코드는 자금 상태와 snapshot 경계까지이며 실제 서버 간 전송은 아직 없다. 커밋·PR·CI·병합·이슈/보드 완료와는 별개다.

## 어떤 연결이 생겼는가

#165에서 자금을 예약하고 원본·전달 의무를 기억했다. #166에서 그 주문을 매칭해 주문장과 전체 결과를 함께 남겼다. 이번에는 **그 전체 결과를 자금 역할에 넣어 잔고·예약·수수료를 정산하는 메모리 입구**를 추가했다.

```text
현재 테스트 호출자
  → funds.applyMatchingResult(전체 결과, 적용 시각, 기존 정산 계산기)
      → 고정 출처·순번·중복 확인
      → 전체 사건·주문 효과와 로컬 승인/현재 예약 대조
      → 모든 체결·수수료·잔고·분개 후보 계산
      → 금융 효과·정산 순번·전달 확인 함께 반영
  ← APPLIED와 누적 정산 확인
```

이 입구는 실제 Aeron `onSessionMessage`나 HTTP에서 아직 호출하지 않는다. 새 역할의 통신·Archive 재생·리더 교체·6노드 연결은 #168~169에서 잇는다. 기존 INTEGRATED 실행과 새 역할의 단독 서버 기동 차단은 유지한다.

## 두 확인을 실제 돈과 연결하기

수수료0, 구매자 quote1,000, 판매자 base1, 가격100×1 거래의 예다.

| 시점·확인 | 구매자 quote 가용/예약 | 무엇을 정리하는가 |
| --- | --- | --- |
| 자금이 주문 승인·예약 | 900 / 100 | 원본·전달 의무·활성 예약을 보관 |
| 매칭 적용 ACK | 900 / 100 | 매칭이 적용한 주문 전달 payload만 회수. 예약은 유지 |
| 전체 체결 결과를 자금에 반영 | 900 / 0, base1 지급 | 주문 종료·활성 예산 회수·정산 prefix 진행. 정산 확인 생성 가능 |
| 미래 연결에서 매칭이 정산 확인을 자기 로그에 반영 | 위 금융 값 유지 | 매칭의 확인된 전체 결과 payload 회수 |

결과가 별도 적용 ACK보다 먼저 도착하면, **검증·정산한 결과 자체를 적용 증거로 함께 사용**한다. `matchingAppliedThrough`는 기존 값보다 낮추지 않고 해당 입력까지 전진할 수 있다. 검증 실패 시에는 이 전달 확인도 반영하지 않는다. 매칭 ACK를 먼저 받은 뒤 결과가 와도 현재 예약에서 정산할 수 있다.

사용자의 응답 ACK·채널 세대 변경은 별개다. 사용자 응답 기록을 회수한다고 활성 예약이나 매칭 전달·정산 순번을 지우지 않는다.

## 제품 코드의 책임과 읽는 순서

| 코드 | 이번 책임 |
| --- | --- |
| [BoundedFundingState](../domain-trading/src/main/kotlin/com/exchange/core/trading/BoundedFundingState.kt) | `applyMatchingResult`가 검사→후보→반영을 조율. `matchingSettlementAck`는 비소비 누적 확인 조회. 통합 실행 전용 검사는 유지 |
| [FundsResultSettlement](../domain-trading/src/main/kotlin/com/exchange/core/trading/FundsResultSettlement.kt) | 결과 수신 한도·응답·불변 receipt·유한 FIFO·정산/회수 경계와 복원 검사. 최종 거절의 균형 반환 분개 |
| [MatchingInputValidation](../domain-trading/src/main/kotlin/com/exchange/core/trading/MatchingInputValidation.kt) | 매칭·자금이 공유하는 순수 전체 결과 검사. 자금에서 주문장을 생성하지 않음 |
| [FundsDispatch](../domain-trading/src/main/kotlin/com/exchange/core/trading/FundsDispatch.kt) | 정산 종료의 활성 건수/bytes 회수, PENDING·RESTING과 전달 prefix의 복원 관계 |
| [기존 정산 계산기](../domain-trading/src/main/kotlin/com/exchange/core/trading/OrderSettlementCalculator.kt) | 현재 예약·잔고에서 복수 체결을 이어 계산. 이번에 정산 규칙을 다시 구현하지 않음 |
| [자금 snapshot codec](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/history/FundsRoleSnapshotCodec.kt) | v2의 금융·전달·정산 경계·receipt와 제한적 v1 읽기, 길이/체크섬·빈 owner 의미 검증 |
| [공통 결과 snapshot codec](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/history/MatchingResultSnapshotCodec.kt) | 매칭 결과 바이트 표현을 재사용. 기존 매칭 v1 필드 순서는 유지 |

조립·복구 값의 연결은 [TradingRoleAssembly](../domain-trading/src/main/kotlin/com/exchange/core/trading/TradingRoleAssembly.kt)와 [FundsRoleRecoveryState](../domain-trading/src/main/kotlin/com/exchange/core/trading/FundsRoleRecoveryState.kt)에 있다. 도메인에는 Aeron·Spring·Protobuf·파일 쓰기를 넣지 않았다.

## 정상 적용·중복·실패가 나뉘는 곳

`applyMatchingResult`는 고정 stream과 정책, 양수 순번·입력 identity·한도를 먼저 검사한다. 이미 처리한 결과는 활성 주문을 다시 찾기 전에 비교한다. 종료된 주문이 제거됐어도 정상 재전송을 확인할 수 있어야 하기 때문이다.

- 보존 중 같은 원문: `DUPLICATE`와 누적 ACK. 첫 적용 시각·돈·receipt는 불변.
- 보존 중 다른 원문: `CONTENT_CONFLICT`, 성공 ACK 없음.
- 회수한 과거: `PRUNED`와 유효 stream의 누적 ACK. 원문 일치 확인이나 과거 상세 조회를 뜻하지 않음.
- 앞선 결과가 빠짐: `GAP`과 expectedSequence. 돈·prefix·전달 자료를 바꾸지 않음.
- 정확한 다음 결과: taker는 첫 PENDING, maker는 이미 RESTING인 로컬 승인과 대조하고 현재 예약으로 계산.

각 체결은 maker 효과 하나를 가지며 taker 효과 하나가 별도로 있다. 전체 사건에는 잔량 진입도 포함되므로 사건 수와 효과 수가 항상 같지는 않다. 결과 순번과 engine 사건 순번도 다르다. 무체결 대기는 engine 사건1개를, 실행 전 최종 거절은 사건0개를 가진다.

모든 후보가 준비되면 잔고·예약·수수료 수익, pending 제거/RESTING·종료 처리, 정산 prefix·receipt와 매칭 적용 확인을 같은 소유자 변경에서 반영한다. 단일 소유 스레드 전제이며 다른 스레드의 임의 동시 호출을 안전하게 만드는 lock을 추가한 것은 아니다.

**계산 중 실패:** 실제 값은 그대로이며 성공 ACK를 내지 않는다. 테스트는 두 번째 수취 잔고를 의도적으로 손상시켜 첫 체결까지 실제 반영되지 않는지 확인한다. 정상 선예약·공급 총량 아래의 정상 체결을 잔고 부족/overflow로 재거절하는 정책을 추가한 것이 아니다.

**실제 쓰기 중 실패:** 이미 일부 값이 바뀌었을 수 있으므로 owner를 차단한다. 테스트 전용 Map 실패 주입으로 부분 변화를 직접 관측하고, 후속 적용·정상 ACK·조회·snapshot·재초기화를 막는지 확인한다. 자동 롤백 성공을 보장하지 않는다. 제품 gate나 실패 주입 인터페이스는 추가하지 않았다.

## 최초 예약과 현재 예약을 구분하기

매칭 결과의 `effect.origin.reservation`은 최초 승인 원본이다. 부분 체결된 주문의 정산에는 자금에 남은 현재 `remainingQuantity`, `remainingAmount`, `feeRemainder`를 사용한다. 원본은 승인/정책을 대조하는 근거이며 현재 예약을 되돌리는 값이 아니다.

같은 판매자의 SELL1@90·SELL1@95에 BUY3@100이 체결되면 구매자 quote는 **가용715·예약100**, base2이고 판매자 quote는185다. 구매 주문의 잔량1은 남는다. 여러 체결의 중간 후보를 실제 상태에 하나씩 공개하지 않는다.

별도 예로 가격51·양측 수수료1%에서 BUY2가 부분 체결되면 현재 수수료 나머지는510,000, quote 가용896/예약53이다. 이 상태를 복원한 뒤 BUY가 다음 체결의 maker가 되어도 나머지를 이어 써서 최종 구매자 quote897/base2, 판매자 quote102, 수수료 수익1이 된다. 같은 사용자 잔고는 합치지만 서로 다른 주문의 수수료 나머지는 합치지 않는다.

## 메모리와 복원 자료의 수명

비교 receipt는 전역 FIFO와 마켓별 연속 구간으로 제한한다. 기본 논리 예산은 공통4MiB·마켓2MiB, 단일 결과1MiB다. 논리 charge를 실제 JVM heap이나 네트워크 바이트와 같다고 말하지 않는다. 건수와 bytes를 모두 지키고, 오래된 정산 완료 receipt를 자동 회수해 다음 정산과 ACK가 막히지 않게 한다. 적용 prefix와 회수 직전 engine 경계는 남긴다.

자금 snapshot-v2에는 그 경계·원문·최초 시각·회수 순서를 보관한다. 결과 하나의 frame 길이는 할당 전에 제한하며, 잘린 frame·범위 밖 나노초·과대한 건수를 거절한다. checksum이 맞아도 금융/전달 prefix·receipt 구멍·수량 효과 연결·hold·공급 총량·카운터가 틀리면 빈 임시 owner에서 복원을 거절한다.

기존 자금 v1은 초기 PENDING 자료에 한해 읽는다. 기존 writer가 만든 **2,337-byte 실제 v1 자료**로 읽기와 v2 업그레이드를 검증한다. 매칭 v1은 기존 writer의 **5,407-byte 복수 체결 자료**를 새 codec이 동일 바이트로 다시 쓰는지 확인한다. 자료·SHA·생성 근거는 [test resources 기록](../app-api/src/test/resources/trading/funds167/README.md)에 있다. 임시 과거 writer/생성 테스트는 최종 소스에서 제거했다.

## 새 테스트를 상황·실행·확인으로 읽기

[공통 fixture](../domain-trading/src/test/kotlin/com/exchange/core/trading/FundsResultTestFixture.kt)는 실제 자금 승인에서 얻은 전달 원본을 실제 `ReservedMatchingState`에 넣어 결과를 만든다. 잔고 기대값은 각 테스트의 수기 값이다. 네트워크·DB 대역이나 실제 원격 클러스터는 사용하지 않는다.

| 테스트 묶음 | Given → When → Then |
| --- | --- |
| 무체결·단일/복수 체결 | 준비 자금과 승인 주문 → 전체 결과 적용 → 잔고/hold·RESTING/종료·prefix·수수료·공급 보존 대조 |
| 현재 예약·수수료 | 부분 체결 예약 → 복원 후 그 주문이 maker인 다음 결과 적용 → 초기 예약으로 되돌지 않고 나머지·잔량을 이어 계산 |
| 중복·충돌·누락 | 적용한 원문/다른 원문/미래 결과 → 재전송 → DUPLICATE/CONFLICT/GAP와 금융·전달 무변경을 함께 확인 |
| 두 ACK 순서 | 적용 ACK 선도착 또는 정산 결과 선도착 → 후속 결과/늦은 ACK → 같은 금융 상태·전달 회수·정상 복원 |
| 최종 거절 | 1,000 중300 예약 → 사건0 최종 거절·복원·재전송 →1,000/0, 추가 반환0·최초 ACCEPTED 응답 유지 |
| 결과 회수·공통 자금 | 작은 건수/byte 창·두 마켓 → 결과 반복·복원·옛 결과 재전송 → 한도/FIFO·마켓별 연속 prefix·공유 quote 보존 |
| 입력·복구 손상 | 잘못된 stream/정책·사건/효과·수량·다시 서명한 복구 자료 → 적용/읽기 → 무변경 또는 빈 owner 차단, 정상 ACK 없음 |
| 계산·쓰기 실패 | 두 번째 체결 계산 오류 또는 실제 Map 쓰기 오류 → 적용 → 계산 실패 무변경과 쓰기 실패 차단을 구분 |
| 사용자 수명·포화·MAX | 사용자 ACK/세대 회전·신규 접수 포화·마지막 순번 → 정산/재전송 → 금융 수명 유지·공간 회수·wraparound 금지 |
| 바이트 호환·v2 | 실제 옛 자료·v2 자료 → 읽기/쓰기/변조 → 제한적 v1 업그레이드·매칭 v1 정확한 바이트·길이/의미 검사 |

새 정산 테스트는 [FundsResultSettlementTest](../domain-trading/src/test/kotlin/com/exchange/core/trading/FundsResultSettlementTest.kt), 복원은 [FundsResultRecoveryTest](../domain-trading/src/test/kotlin/com/exchange/core/trading/FundsResultRecoveryTest.kt), bytes는 [FundsResultSnapshotCodecTest](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/history/FundsResultSnapshotCodecTest.kt)에서 읽는다. 기존 [FundsRoleSnapshotCodecTest](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/history/FundsRoleSnapshotCodecTest.kt)·[MatchingRoleSnapshotCodecTest](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/history/MatchingRoleSnapshotCodecTest.kt)에 옛 자료 검사를 추가했다. 독립 리뷰의 두 지적은 [FundsResultValidationRegressionTest](../domain-trading/src/test/kotlin/com/exchange/core/trading/FundsResultValidationRegressionTest.kt)에 정상 대조와 함께 남겼다. F1~F30과 실제 실행 결과의 대응은 아래 최종 검증 표에 연결한다.

## 실행·독립 리뷰와 남은 한계

Java25에서 다음 최종 검증을 실행했다. 명령은 실제 최종 검사 범위이며, 변경 없는 일부 모듈은 같은 입력에 대한 UP-TO-DATE 근거를 재사용했다. 저장소 전체 app-api suite나 전체 build를 실행했다고 주장하지 않는다.

```bash
cd /Users/0chord/.codex/worktrees/issue148-http-aeron/exchange-core
JAVA_HOME=/Users/0chord/.sdkman/candidates/java/25.0.2-amzn ./gradlew :domain-matching:test :domain-trading:test :domain-order:test :domain-fee:test :domain-ledger:test :app-api:test --tests '*FundsRoleSnapshotCodecTest' --tests '*FundsResultSnapshotCodecTest' --tests '*MatchingRoleSnapshotCodecTest' --tests '*BoundedTradingRoleGoldenTest' --tests '*BoundedTradingStateCodecTest' --tests '*BoundedTradingSnapshotContinuationTest' --tests '*TradingRoleStartupTest' --tests '*BoundedHttpTradingClusterTest' :architecture-tests:test :architecture-tests:verifyArchitectureReport ktlintCheck --no-daemon --max-workers=1 --console=plain
```

| 범위 | 테스트 수 | 결과 |
| --- | --- | --- |
| domain-matching | 163 | 실패·오류·skip0 |
| domain-trading | 402 | 실패·오류·skip0 |
| domain-order / domain-fee / domain-ledger | 78 / 39 / 59 | 실패·오류·skip0 |
| 지정 app-api8개 클래스 | 37 | 실패·오류·skip0 |
| architecture-tests | 386 | 실패·오류·skip0 |
| 합계 | **1,164** | P01~P09 각각 실행·성공, 형식 검사 통과 |

신규 테스트는 **40개**다. 정산17·복원11·결함 회귀2의 도메인30개, 새 result codec8개와 기존 funds/matching codec에 추가한 호환2개의 앱10개다. 별도 리뷰어의 독립 반례/재검사14개는 이 수에 합산하지 않는다.

최종 실제 기존 HTTP 실행은 `http-bounded-60732a14`다. 범위는 HTTP v3·LOCAL_QUORUM·세 Docker JVM·sync2/2/2이고 success=true, cleanupFailures=[], recordVolumesDeleted=true다. 해당 컨테이너·볼륨이 남지 않았음을 실행 후 다시 확인했다. 새 자금3+매칭3 클러스터 시험은 아니다. 진단물은 기존 시험의16MiB 상한/자원 정리 규약을 따른다.

실행 근거는 작업 폴더의 `build/funds167-final-verification.log`, `build/funds167-verification/verification.json`, 같은 폴더의 보존 XML·`http-result.json`이다. 다음 수용 대응 표와 실제 assertion을 함께 읽는다.

| 명세 사례 | 직접 확인한 근거 |
| --- | --- |
| F1~F6 | 무체결/단일·복수 체결, 자기 거래·무관 hold, 현재 부분 예약·수수료 이월/복원, 독립 잔고 수기 값 |
| F7~F10 | 두 최종 거절 reason·정상 반환·최초 ACCEPTED 유지, 중복 시각 불변·내용 충돌 |
| F11~F15 | 건수/byte 회수·PRUNED, GAP, 두 ACK 역도착, 과거 결과의 잘못된 정책/순번/stream에도 ACK 금지 |
| F16~F19 | 사건/효과/승인/잔량 모순, 보이는 maker 건너뛰기 회귀, 계산 실패 무변경·실제 쓰기 실패 차단 |
| F20~F24 | 부분·종료·정상 거절·회수 후 복원, 사용자 ACK/epoch 독립, 포화 중 정산으로 공간 회수 |
| F25~F27 | 잘린/과대한 frame·시각·서명 재작성 관계 손상, 실제 v1 읽기·v2 저장, 바이트 보존·누적 읽기 예산 |
| F28~F30 | 마지막 Long 순번·과거 ACK, 두 마켓 공유 자금/FIFO, 기존 통합 golden·시작 차단·실제 기존 HTTP 회귀 |

### 독립 리뷰의 지적과 수정

작성 대화를 상속하지 않은 하위 리뷰어(`fork_turns=none`)가 별도 base/head 사본에서 전체 변경을 읽고 직접 검사했다. 첫 사본은 `build/funds167-review-v1`이며 P2 두 건을 재현했다. 다른 독립 컨텍스트의 지적 검증자도 코드·명세·입력의 도달 가능성을 대조해 두 건을 확인했다. 그 검증은 읽기 판단이며 별도 실행을 했다고 세지 않는다.

1. **복원한 활성 maker의 승인 순번 대조 누락.** 이전 receipt가 회수된 상태에서 origin의 dispatch/marketAdmission만 바꾸면 복원이 수용됐고 정당한 원문을 CONTENT_CONFLICT로 만들 수 있었다. 실시간·복원이 같은 `requireFundsOriginal`로 로컬 승인 순번·원본·초기 예약 관계를 검사하도록 수정했다. 정상 복원·동일 결과 재전송은 유지한다.
2. **보이는 maker를 소진하지 않고 다음 maker를 체결하는 묶음 수용.** SELL2@90·SELL1@95·BUY2@100에서 잘못된1@90+1@95 결과가 구매대금185를 반영할 수 있었다. 공유 전체 결과 검증에 `min(takerRemaining, makerBefore)` 체결량을 검사한다. 보이지 않는 전체 주문장을 재매칭하는 검사가 아니라 결과 안의 수량 모순 검사다. taker가 먼저 소진돼 마지막 maker 잔량이 남는 정상 부분 체결은 허용한다.

두 위반의 정식 회귀 테스트가 기존 코드에서 **2개 모두 의도한 실패**를 보이는 것을 확인한 뒤 수정했다(`build/funds167-review-regressions-red.log`). 이 실패는 컴파일 실패가 아니라 실제 assertion 실패다. 이후 관련 정산30개·codec25개를 통과했고 최종 전체 관련 검사를 다시 실행했다.

추가로, 자금 v2 reader는 선언 상한뿐 아니라 **누적 실제·마켓별·선언 잔여 예산**을 다음 frame 할당 전에 대조한다. 세 receipt에 두 receipt 예산만 선언한 자료를 읽을 때 셋째 본문 전에 중단되는지 CountingInputStream으로 직접 확인한다. 정상 자료의 읽기는 유지한다.

최종 고정 사본은 `build/funds167-review-v2`다. 전체24개 변경 파일 목록의 SHA-256은 `f223735b95bdef9563036402c5354c77a10ca07cf4dfe85f31a97e8fec3a2bcc`다. 리뷰어는 최초 전체 리뷰 이후 수정분·두 지적의 해소·인접 경계를 재검토했다. 정식 회귀2·독립 도메인2·result codec8·독립 codec2, 합계14개의 직접 검사를 통과했다. SELL 방향 정상/변조 결과, 손상 복원 후 차단과 정상 복원 후 후속 체결, 첫 frame 전 중단과 양수 잔여 byte 초과를 확인했다. 검토 범위의 미해결 결함은0건이다.

최종 문서 보완 이후에도 제품·테스트·복구 fixture의 내용 SHA가 이 사본과 같음을 확인한다. 문서의 후속 설명 보완을 새 제품 검증 실행으로 세지 않는다. 리뷰 보고서는 각 사본의 `review-report.md`이며, 독립 실행 로그·추가 반례 원본은 각 `review-evidence`에 보존한다.

초기 MAX 재전송 테스트의 자체 순번 불일치는 정상 과거 입력으로 고쳤고 제품 검사를 약화하지 않았다. 첫 앱 컴파일의 domain-internal 접근은 공개 manifest 조회로 고쳤으며 도메인의 공개 범위를 넓히지 않았다. 형식 자동 수정 후 필요한 검사를 다시 확인했다.

**남은 범위:** 실제 원격 출처 검증·Aeron 송수신·ACK의 양쪽 로그 반영·새 역할 Archive replay·리더 승계는 #168~169와 후속 장애 검증에서 연결한다. 리더 자금 노드 JVM의 송신 작업은 배치 후보이며 이번에 구현하지 않았다. `FundsResultLimits.requireCompatible()`는 실제 producer 상한을 후속 연결 조립에서 대조할 입구다. 여기서 함수가 반환한 ACK나 snapshot bytes를 원격 quorum/Archive 확정 증거로 확대하지 않는다. 전체 원장 이력의 외부 전달·인증/인가·새 성능 측정도 하지 않았다.
