# #58 분개 보존과 재시작 후 원장 대사

초기 지급·예약·체결 정산·취소 반환을 확정하는 분개를 PostgreSQL에 남긴다. 복구 후 자금과 매칭이 서로 같은 업무 순서에 도달했고, 그 시점의 원장·잔고·활성 예약·주문 잔량이 일치할 때 `READY`를 반환한다. 현재 수용 범위는 정산 분개 저장이 설정된 자금 클러스터와 단일 마켓이다. [#58](https://github.com/0Chord/coin-exchange/issues/58)

## 저장부터 재개 판정까지

1. 자금 역할은 금융 변경과 함께 적용한 분개에 연속 순번과 Aeron 로그 위치를 부여한다. 데이터와 누적 검증값을 스냅샷·후속 로그로 복원한다. 사용자가 같은 요청을 다시 보내거나 결과가 재전송될 때 최초 상태 변경에만 분개를 남긴다.
2. 기존 WAS의 조회 반영 작업이 분개를 읽고 PostgreSQL에 커밋한다. 메모리에서 아직 회수하지 않은 구간은 기존 DB 행도 원본과 한 건씩 확인한다. DB의 마지막 번호 앞에 공백이 있으면 원본을 회수하지 않고 차단한다.
3. 재시작 시 주문·취소 전달과 매칭 결과 반영을 이어간다. 자금에서 보낸 순번, 매칭에서 적용한 순번, 자금에서 정산한 결과 순번이 맞으면 잔고·예약과 매칭 주문 잔량을 대조한다. 상태는 512행 단위로 읽어 모든 페이지에 같은 업무 순번과 원장 검증값이 확인된 경우만 사용한다. 비교 중 상태나 리더 임기가 바뀌면 다시 읽는다.
4. PostgreSQL의 원장을 해당 자금 위치까지 순서대로 읽고 각 분개의 JSON 값과 타입, 연속 순번과 원본 검증값을 확인한다. 기존 #74 계산으로 사용자·자산별 사용 가능과 예약 잔고를 따로 비교하고, 활성 예약 합계 및 매칭 주문 ID·소유자·잔량도 비교한다.

| 관찰 | 판정 |
| --- | --- |
| 미확인 주문·정산이 남거나 비교할 DB 순번이 아직 도착하지 않음 | `RECOVERING` |
| 같은 원장 범위에서 원장·잔고·예약·매칭 잔량이 일치 | `READY` |
| 필수 순번이 이미 회수됐는데 DB에 없음, 내용 변조, 잔고·예약 또는 주문 잔량 불일치, 읽기 실패 | `BLOCKED`와 해당 원인 |

테스트는 새로 만든 개발용 Archive와 PostgreSQL로 시작해 동일한 디스크와 DB에서 자금·매칭 각 한 노드를 다시 시작한다. 기존 개발 데이터의 분개 이관은 범위 밖이다. 확정 위치까지 분개가 보존됐다는 근거가 없으면 대사할 수 없다. 판정은 확인한 자금 로그 위치를 함께 반환하고 새로 연결하거나 리더 임기가 바뀌면 다시 계산한다. `READY`를 실제 주문·취소 HTTP 접수에 반영하는 일은 [#59](https://github.com/0Chord/coin-exchange/issues/59)에서 진행한다.

## 실제 재시작 수용 사례

수수료 0으로 매수자에게 1,000을 지급하고 3개 주문에 300을 예약한다. 같은 마켓에서 한 개를 100에 체결하고 나머지 두 개를 취소한다. 처음 지급과 예약이 담긴 스냅샷 뒤의 체결·반환·저장 ACK를 재생한다. 저장된 모든 분개는 재시작 전후에 같고, 매수자 잔고는 사용 가능 900·예약 0이며 활성 예약은 없다. 저장된 반환의 차변·대변을 200에서 199로 바꾸면 분개 자체는 균형이어도 재시작 판정은 차단된다. 이와 별개로 잔고 배분 차이, 고아 예약, 회수된 기록의 누락, 원장 읽기 실패를 확인한다.

- 매칭 결과 전송이 남은 상태로 Archive를 재시작한다. 실제 PostgreSQL에 지급과 예약이 기록된 뒤에도 `RECOVERING`을 확인하고, 결과가 자금에 반영된 뒤 `READY`와 활성 예약 300을 확인한다.
- PostgreSQL의 첫 행을 지워도 ACK 전인 자금 원본이 남는다. 반환 200 두 항목을 200.125로 바꾸거나 필수 필드 없는 JSON을 넣어도 읽기 시 값이 보정되지 않고 차단된다.
- 잔고 513개를 가진 자금 노드에서 WAS가 두 번째 페이지까지 읽어 전체 원장 대사를 통과하는지 확인한다.

## 전체 구현 검증 기록

2026-11-18 로컬에서 앱·통합 시험 148건, 거래 도메인 456건, 원장 도메인 59건, 매칭 도메인 167건, 아키텍처 386건이 실패·건너뜀 없이 통과했다. 대상 모듈의 ktlint와 필수 아키텍처 결과 검증도 통과했다. 기존 HTTP 회귀에서 OS 임시 UDP 포트와 충돌한 시험은 고정 포트 후보를 임시 포트 범위 밖에서 골라 재검증했다.

구현 대화를 전달하지 않은 리뷰어가 기준 `6961894`와 고정본을 대조했다. 소수 금액 강제 변환, ACK 전 누락 원장 회수, 복구 응답 초과, 스냅샷 기본 예산을 보완했다. 이전 회수 범위의 내부 공백도 새 원장 ACK 전에 확인하도록 수정했다. 재검토 고정본의 범위 해시는 `e5d7dcc28afe9dd88c12facfc5b095b20e8d620ab6634b124a8c2735ede19fc3`이며, 구현·시험 파일은 현재 변경과 일치한다.

저장소 루트에서 아래 명령으로 실제 Archive·PostgreSQL을 사용하는 복구 테스트를 실행한다.

```sh
./gradlew --no-daemon --max-workers=2 :app-api:test \
  --tests '*SplitFundsTradingHttpTest' --tests '*SplitTradingRoleStartupTest' \
  --tests '*OrderQueryRoleSnapshotCodecTest' --tests '*PostgresOrderQueryFeedStoreTest' \
  --console=plain
```

## 첫 검증에서 확보한 근거

**1,000 USDT를 준비하고 300 USDT를 예약한 주문 하나를 복원한다.** 자금 클러스터 Archive의 승인 위치까지 독립적으로 읽고, 재시작한 자금 역할이 그 위치를 적용한 직후의 **사용 가능 700 · 예약 잔고 300 · 해당 주문의 활성 예약 300**을 각각 비교한다. 전체 구현에 앞서 추가한 기준 시험이다. 통합 기준은 `6961894`다. [#58](https://github.com/0Chord/coin-exchange/issues/58) · [통합 커밋](https://github.com/0Chord/coin-exchange/commit/6961894fdabab567be1d23eb71721b73441366a8)

## 한 건의 흐름

```text
초기 자금 1,000 준비 확정 → 자금 스냅샷 저장
                       ↓
매수 3개 × 단가 100 · 수수료 0 → 주문 승인 위치 P 확정
                       ├─ 기존 Archive를 P까지 읽음
                       │  초기 지급 1,000과 승인된 주문 입력으로 700 / 300을 계산
                       └─ 같은 기록으로 자금 노드 재시작
                          스냅샷 이후 주문 P 재생 직후 잔고와 활성 예약을 캡처
                       ↓
같은 자금 계보·같은 위치인지 확인 → 사용 가능·예약 잔고·활성 예약을 각각 대조
```

사용 가능은 700, 예약 잔고는 300이다. 예약 잔고 300이 실제 그 주문의 활성 예약 300과도 같아야 한다. 두 잔고를 더한 값이 1,000이어도 `800 / 200`이면 불일치다. 수수료 0과 미체결은 이 사례의 시험 조건이다. [기존 복구·대사 계약](gtc-recovery-contract.md#L157) · [#74 대사 기준](ledger-reconciliation-spec.md#L33)

## 첫 검증 당시 원본을 읽은 위치

| 위치 | 초기 자금 1,000 | 주문 예약 300 | 이 사례에 쓸 수 있는 범위 |
| --- | --- | --- | --- |
| **자금 클러스터 Archive** | 지급 준비 명령 원본에 준비 ID·사용자·자산·금액이 있다. 최초 `PREPARED` 응답으로 적용을 확인한다. | 주문 명령 원본에 요청 식별자·마켓 설정 버전·가격·수량이 있다. 최초 `ACCEPTED` 응답이 적용 위치 P, 주문 ID, 접수 순번을 준다. | 원본 프레임을 승인 위치까지 읽을 수 있다. 로그에는 요청도 남으므로 프레임과 실제 성공 응답을 연결한다. [자금·주문 명령 적용](https://github.com/0Chord/coin-exchange/blob/6961894fdabab567be1d23eb71721b73441366a8/app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitTradingClusterAdapter.kt#L637) · [기존 Archive 읽기](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/RecordedArchiveReader.kt#L51) |
| **자금 스냅샷과 복원 상태** | 최초 지급 정보와 잔고가 보존된다. | 잔고의 예약 금액, 주문별 활성 예약, 접수 순번과 최초 응답이 보존된다. | 실제 복원된 값을 읽는 쪽이다. 현재 복원 과정에서도 활성 예약 합계와 예약 잔고, 자산 총량을 검사한다. [스냅샷 저장](https://github.com/0Chord/coin-exchange/blob/6961894fdabab567be1d23eb71721b73441366a8/app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitTradingClusterAdapter.kt#L847) · [보존된 자금 상태](https://github.com/0Chord/coin-exchange/blob/6961894fdabab567be1d23eb71721b73441366a8/domain-trading/src/main/kotlin/com/exchange/core/trading/BoundedFundingState.kt#L1403) · [복구 검사](https://github.com/0Chord/coin-exchange/blob/6961894fdabab567be1d23eb71721b73441366a8/domain-trading/src/main/kotlin/com/exchange/core/trading/BoundedFundingState.kt#L1558) |
| **기존 PostgreSQL 주문 조회** | 자금 클러스터의 개시 지급은 이 조회 경로에 적재되지 않는다. | 조회 원본을 켠 경우 접수 원본에 최초 예약과 정책이 보존된다. 접수 순번을 쓰며 자금 Archive 위치 P는 이 자료에 직접 연결되지 않는다. | 주문·정산 조회 근거로 쓸 수 있다. 이 저장 경로만으로 개시부터 예약까지 같은 위치의 전체 분개를 읽을 수 없다. [접수 원본 적재](https://github.com/0Chord/coin-exchange/blob/6961894fdabab567be1d23eb71721b73441366a8/app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/persistence/PostgresOrderQueryFeedStore.kt#L167) · [예약 원본](../domain-trading/src/main/kotlin/com/exchange/core/trading/FundsDispatch.kt#L227) · [체결 정산 적재](https://github.com/0Chord/coin-exchange/blob/6961894fdabab567be1d23eb71721b73441366a8/app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/persistence/PostgresOrderQueryFeedStore.kt#L250) |

자금 역할은 개시 분개의 균형을 검사하고 최초 준비 결과를 보관한다. 예약 승인 때는 잔고·예약·최초 응답을 확정 로그 적용 안에서 함께 반영한다. 기존 #74 PostgreSQL 대사는 자체 원장·잔고·예약 테이블을 한 DB 읽기 시점으로 묶는 경로다. 통합 기준 `6961894`에는 이 읽기 시점과 Aeron 로그 위치 P를 연결하는 경로가 없었다. [개시 적용](https://github.com/0Chord/coin-exchange/blob/6961894fdabab567be1d23eb71721b73441366a8/domain-trading/src/main/kotlin/com/exchange/core/trading/BoundedFundingState.kt#L305) · [예약 적용](https://github.com/0Chord/coin-exchange/blob/6961894fdabab567be1d23eb71721b73441366a8/domain-trading/src/main/kotlin/com/exchange/core/trading/BoundedFundingState.kt#L158) · [#74 DB 읽기 경계](../app-api/src/main/kotlin/com/exchange/core/api/ledger/infrastructure/persistence/PostgresLedgerReconciliationStore.kt#L35)

## 기존 시험에서 가져올 것

- 도메인 시험은 **1,000 → 300 예약 → 복원 → 700/300**과 같은 활성 주문·접수 이력을 이미 확인한다. 실제 Archive 시험은 역할을 같은 디스크로 재시작해 스냅샷 이미지와 이후 로그, 복원된 잔고·접수 순번·확인 순번을 검증한다. 이 두 경계를 그대로 사용한다. [정확한 금액 사례](../domain-trading/src/test/kotlin/com/exchange/core/trading/BoundedFundingStateTest.kt#L200) · [기존 실제 Archive 복원 시험](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitFundsTradingHttpTest.kt#L1267)
- 기존 시험용 Archive 읽기는 실행 중인 기록에서 지정한 끝 위치까지 원본 프레임을 반환한다. 프레임을 해석할 때 현재 자금 역할의 메시지 코덱을 사용한다. 재시작 뒤 상태 관찰은 이미 있는 시험용 콜백을 사용해 자금 상태와 적용 위치를 함께 캡처한다. [유한 Archive 재생](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/RecordedArchiveReader.kt#L62) · [원본 프레임 해석](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/RecordedClusterFrameParser.kt#L39) · [분리 명령 코덱](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/codec/SplitOrderDeliveryCodec.kt#L176) · [상태 관찰 접점](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitFundsTradingHttpTest.kt#L2937)
- 통합 기준 커밋에서 도메인·Archive·역할별 복구 검사가 통과했다. [통합 CI](https://github.com/0Chord/coin-exchange/actions/runs/44301316140) · [일반 시험 산출물](https://github.com/0Chord/coin-exchange/actions/runs/44301316140/artifacts/14426607492) · [복구 시험 산출물](https://github.com/0Chord/coin-exchange/actions/runs/44301316140/artifacts/14427997828)

## 기준 시험의 수용 범위

기존 한 노드씩의 실제 Archive 복구 준비를 재사용해 **승인 위치 한 곳에서 원본과 복원 상태를 대조하는 시험**을 붙인다. 비교는 테스트에 둔다. 기대값은 읽어 온 지급 금액과 주문 가격·수량, 시험에 고정한 수수료 0으로 산출한다. 복원된 잔고나 엔진의 예약 계산 결과를 기대값에 사용하지 않는다.

1. 자금 준비의 최초 `PREPARED`를 확인하고, 이후에 찍은 스냅샷이 완료됐음을 확인한다. 그 뒤 매수 3개를 한 번 승인하고 최초 `ACCEPTED` 응답에서 자금 계보·토큰에 해당하는 주문 ID·접수 순번·적용 위치 P를 기록한다. 이 주문은 체결시키지 않는다.
2. 원래 노드가 살아 있는 동안 동일 자금 클러스터의 처음부터 P까지 읽는다. 이 시험에서 원본 기록을 하나로 식별하고 끝까지 읽었다는 근거를 요구한다. 초기 지급은 준비 ID·사용자·자산·금액과 최초 성공 응답의 위치로, 주문은 요청 토큰·내용과 P로 연결한다. 개시 원장 ID는 자금 엔진이 발급한 최초 응답에서 확인한다. 대상 사용자·자산에 예상한 두 입력 외 다른 자금 효과가 있거나 범위를 충분히 확인할 수 없으면 대조를 성공으로 표시하지 않는다.
3. 원래 기록으로 자금 노드를 재시작한다. 실제 스냅샷 이미지로 시작한 뒤, 시험 관찰자가 **주문 P를 적용하는 콜백 직후**의 잔고·활성 예약·접수 순번을 한 번에 담는다. 복구 후 선출 메시지 등으로 로그가 더 진행돼도 이 관찰값은 덮어쓰지 않는다.
4. 같은 자금 계보와 P가 확인된 뒤 사용자·자산·주문 ID를 맞춰 세 금액을 비교한다. `P`는 자금 역할의 로그 위치다. 접수 순번, 매칭 역할 위치, PostgreSQL 반영 순번, 벽시계 시각은 각각 값의 의미가 다르다.

| 검증할 사례 | 기대 결과 |
| --- | --- |
| 같은 계보·P, 지급 1,000과 승인된 3개 매수, 실제 스냅샷 이후 재생 | Archive 계산 `700 / 300`과 복원 잔고가 일치한다. 승인된 ID의 활성 예약은 300이고 접수 순번은 원래 값과 같다. |
| 동일 P의 비교 입력 사본에서 사용 가능/예약만 `800 / 200` | 사용 가능과 예약이 각각 불일치다. 활성 예약 300과도 어긋난다. 실제 기록이나 잔고를 수정하지 않는다. |
| 지급 또는 주문 프레임 누락, 읽기 종료 위치 부족, 계보나 P 불일치 | 필요한 근거를 확보하지 못한 시험 실패로 기록한다. 일부 값이 우연히 같아도 일치 판정을 반환할 수 없다. |

시험은 새 기록의 단일 자금 노드에 대해 읽는다. 보존 자료에서 처음 구간을 이미 회수했거나 여러 임기로 나뉜 로그를 일반적으로 읽는 기능은 별도 범위다. 기존 리더의 기록을 재시작 전에 읽으므로, 동일 장치의 전체 유실과 원장 독립 보존을 검증하는 시험에는 해당하지 않는다.

## 구현·검증 기록

- **준비** 자금 노드에서 매수자 초기 지급을 성공시키고 실제 스냅샷을 완료한다. 이후 수수료 0으로 100 USDT × 3개 주문을 승인한다. [준비와 승인 위치](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitFundsTradingHttpTest.kt#L1130)
- **실행** Archive가 P까지 돌려준 입력을 성공 응답의 위치와 연결해 개시·예약 분개를 시험에서 재구성한다. 기존 디스크로 자금 노드를 다시 시작해 P를 재생한 직후의 상태를 담는다. [원본 검증](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitFundsTradingHttpTest.kt#L1161) · [동일 위치 복원](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitFundsTradingHttpTest.kt#L1193)
- **확인** 기존 대사 계산기가 실제 700/300과 활성 예약 300을 일치로 판정한다. 800/200 사본은 세 항목에서 불일치한다. 지급·승인 프레임 누락과 P까지 읽지 않은 원본은 비교 전 실패한다. [원본 누락·위치 반례](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitFundsTradingHttpTest.kt#L1172) · [금액 일치와 불일치](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitFundsTradingHttpTest.kt#L1252)
- **독립 리뷰 반영** 복원 상태와 함께 실제 적용 위치를 전달한다. 금액을 그대로 둔 사본에서도 복원 위치가 P보다 뒤거나 자금 계보가 다르면 대사 전에 실패하는 반례를 추가했다. 최초 응답의 접수 순번은 복원된 활성 주문의 순번과 직접 맞춘다. 변경한 두 테스트 파일의 고정본으로 독립 재검토를 마쳤다. [복원 위치·계보 반례](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitFundsTradingHttpTest.kt#L1209)

2026-11-18 로컬에서 새 대사와 기존 Archive·HTTP 시험 32건, 자금 복원 5건, 원장 대사 17건이 실패·건너뜀 없이 통과했다. ktlint와 아키텍처 시험 386건, 필수 운영 검사 P01~P09의 결과 검증도 통과했다.

검증 명령은 기존 통합 시험 준비를 사용한다.

```sh
./gradlew --no-daemon --max-workers=2 :app-api:test \
  --tests '*SplitFundsTradingHttpTest' --tests '*RecordedArchiveReaderTest' --tests '*AeronRecordedInputTest' \
  :domain-trading:test --tests '*BoundedFundingStateTest' \
  :domain-ledger:test --tests '*LedgerReconciliationTest' \
  :app-api:ktlintCheck :architecture-tests:test :architecture-tests:verifyArchitectureReport \
  --continue --console=plain
```
