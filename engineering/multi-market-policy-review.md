# BTC·SUI 주문이 같은 USDT를 사용하도록 연결하기

**#114는 주문을 받는 입구가 BTC뿐 아니라 등록된 SUI도 받아, 기존 자금 상태 하나에 넘기도록 바꾼다.** 돈 계산·원장 생성 규칙은 기존 코어를 그대로 쓴다.

USDT 1,000에서 BTC 주문이 600을 예약하면 SUI는 남은 400으로 판단한다. SUI가 500을 요구하면 거절하고, 새 요청으로 400을 요구하면 승인한다. 이번 접수는 자금 예약과 처리 대기까지이며 체결은 하지 않는다.

## 마켓 설정은 둘인데 잔고는 왜 하나일까

설정은 **주문을 해석할 규칙**, 상태는 **지금 가진 돈과 처리 결과**다.

| 보관하는 것 | 이번 예시 | 용도 |
| --- | --- | --- |
| BTC 마켓 설정 | BTC/USDT, BTC 소수 8자리, 정책 버전 1 | BTC 주문의 수량과 정책 검사 |
| SUI 마켓 설정 | SUI/USDT, SUI 소수 9자리, 정책 버전 2 | SUI 주문의 수량과 정책 검사 |
| U1의 USDT 잔고 | 사용 가능 1,000, 예약 0 | 두 BUY 주문이 함께 쓰는 돈 |
| 주문별 예약 | BTC 주문 600, SUI 주문 400 | 어느 주문이 얼마를 묶었는지 구분 |

위 스케일·정책은 개발 검증값이다. 실제 서비스의 운영 정책을 확정한 값은 아니다.

```text
BTC 주문 → BTC 설정 검사 ─┐
                         ├→ 같은 OrderAdmissionState → (U1, USDT) 잔고
SUI 주문 → SUI 설정 검사 ─┘

BTC 600 승인: 사용 가능 400 · 예약 600
SUI 500 거절: 사용 가능 400 · 예약 600 유지
SUI 400 승인: 사용 가능   0 · 예약 1,000
```

`DevelopmentMarketRegistry`에는 잔고가 없다. 노드가 만든 `OrderAdmissionState` 하나에 BTC와 SUI 요청을 모두 전달한다. 사용자와 자산이 같아서 돈을 공유하며, U2의 USDT나 U1의 BTC·SUI까지 합치는 것은 아니다. 서버나 클러스터를 새로 분리한 변경도 아니다.

## 요청 한 건이 지나가는 길

1. **준비:** `DevelopmentFundingSettings.markets`에 허용 마켓과 정책을 등록한다. 입력 목록을 복사하고 빈 목록·중복 ID·잘못된 문맥은 시작 전에 거절한다.
2. **전송:** 테스트가 기존 `DevelopmentOrderClient.request(command)`로 실제 Protobuf 메시지를 보낸다. HTTP API는 거치지 않는다.
3. **이력 확인:** `DevelopmentFundingAdapter.admit()`가 기존 사용자·요청 ID인지 먼저 확인한다.
4. **신규 요청만 설정 검사:** 주문의 마켓 ID로 문맥을 찾는다. 미등록이거나 자산·수량 단위·정책이 다르면 자금과 요청 이력 모두 그대로 두고 거절한다.
5. **같은 코어 호출:** 최초 요청이면 잔고를 보고 승인 또는 거절을 기록한다. 기존 요청이면 최초 결과 또는 내용 충돌을 판단한다.
6. **응답과 관찰:** 응답은 작은 접수 결과다. 테스트는 별도로 해당 요청 뒤의 잔고·예약·원장·대기 목록·최초 결과를 확인한다.

| 분기 | 남는 결과 |
| --- | --- |
| 허용된 새 주문, 잔고 충분 | 예약·RESERVE 원장·접수 대기·최초 승인·승인 순번 |
| 허용된 새 주문, 잔고 부족 | 돈은 그대로, 최초 거절 기록만 추가 |
| 새 요청의 설정 오류 | 돈과 최초 요청 기록 모두 그대로. 같은 요청 ID로 설정을 고쳐 재시도 가능 |
| 기존 요청, 같은 주문 원본 | 최초 결과 재사용. 전달용 정책·시각·원장 ID로 과거 결과를 덮어쓰지 않음 |
| 기존 요청, 유효한 주문 원본 변경 | 내용 충돌. 기존 돈·기록 보존 |
| 내부 오류 | 기존 규칙대로 개발 노드 중단. 부분 반영을 자동 롤백하거나 재개하지 않음 |

읽을 수 없는 바이트는 코어에 전달하지 않는다. 기존 식별자 검사의 우선순위와 차단된 상태의 재전송 거절도 유지한다. 응답이 사라졌다고 예약을 취소하지 않는다.

<details>
<summary>핵심 코드와 변경 파일의 역할</summary>

- [DevelopmentMarketRegistry.kt](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentMarketRegistry.kt): 개발 설정 값과 등록 목록. 기존 식별자·현물 정책 검증을 옮겨 재사용한다. Map을 외부로 내주지 않으며 `get(marketId)`는 해당 문맥만 반환한다.
- [DevelopmentFundingNode.kt](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingNode.kt): Settings가 등록 목록을 보관하고 Node가 기존 상태 객체 하나와 함께 어댑터에 전달한다. Driver·Archive·합의 설정은 바꾸지 않았다.
- [DevelopmentFundingAdapter.kt](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingAdapter.kt): `hasRequest` 이후 신규 요청에만 마켓 조회를 추가했다. `state.admit()`와 응답 전송은 기존 경로다.
- [DevelopmentFundingSettingsTest.kt](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingSettingsTest.kt): 등록 목록의 고정·중복·호환·검증 사례.
- [DevelopmentMultiMarketOrderNodeTest.kt](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentMultiMarketOrderNodeTest.kt): 실제 단일 노드의 두 마켓 흐름과 거절·실패 사례. 포트 준비·관찰 대기는 test 안에만 있다.
- 기존 `DevelopmentOrderNodeTest`, `DevelopmentOrderClientTest`, `AeronRecordedInputTest`: 비영점 정책·버전 2·한글 단일 마켓을 만드는 설정을 새 등록 타입으로 이행했다. 기존 기대값은 유지했다.

개발 실행 코드는 main에, 테스트와 관찰 도우미는 test에 있다. 이번에 새 프로덕션 HTTP 연결이나 테스트용 gate를 만들지 않았다.

기존 `market`·`feePolicySnapshot` 접근은 등록 한 항목에서 파생한다. 다중 등록이면 오류를 내므로 기존 단일 READY가 첫 BTC만 표시하며 성공할 수 없다. 다중 마켓 CLI·전체 설정 출력은 #115다.

</details>

<details>
<summary>새 테스트 7개가 실제로 확인하는 것</summary>

공통 준비는 한 JVM 안의 실제 Aeron 노드·MediaDriver·Archive와 실제 코어다. 개발 클라이언트는 기존 전송 코드를 사용한다. 새 테스트에는 mock·reflection·운영 관찰 훅을 추가하지 않았다. 각 사례는 새 실행 경로와 포트를 사용한다.

| 테스트 행동 | 준비 → 실행 | 실제 확인 |
| --- | --- | --- |
| 두 마켓이 같은 돈을 쓴다 | USDT 1,000 → BTC 600 → SUI 500 → SUI 400 | 부족 시 400·500·100, 승인 순번 1·2, 최종 사용 가능 0·예약 1,000, OPENING 1건·RESERVE 2건, 각 정책·분개·시각·요청 기록. 최초 SUI 거절을 다시 보내도 당시 숫자 반환 |
| 잘못된 SUI 설정을 고친다 | 아래 10개 필드 중 하나를 변경 → 같은 요청 ID의 정상 입력 | 오류 때 전체 업무 상태·요청 기록 불변. 수정 뒤 순번·예약 증가, SUI 정책 사용 |
| 최초 결과가 현재 전달 문맥보다 먼저다 | SUI 승인 및 부족 기록 → 같은 원본의 전달 마켓·정책·원장 ID·시각 변경 | 최초 응답과 기록 유지. 요청 이력과 원장 추가 없음 |
| 내용 충돌과 주문 중복을 구분한다 | 기존 BTC 요청을 SUI로 변경 / 새 요청 ID로 기존 BTC 주문 재전송 | 기존 키는 내용 충돌. 새 키는 설정 오류 우선, 설정을 고치면 주문 중복 기록. 이후 같은 요청은 최초 중복 결과 |
| 다른 마켓에서 같은 주문 ID를 쓴다 | BTC·SUI에 각각 100 예약, 주문 ID는 동일 | 사용 가능 800·예약 200, 마켓별 대기 한 건, 전역 순번 1·2, 서로 다른 원장 사건과 거래 ID |
| 다른 사용자·자산 돈을 쓰지 않는다 | U1 USDT 1,000·BTC 3개 → U2 SUI BUY / U1 SUI SELL | 각각 USDT 0·SUI 0 기준 부족. U1 자금 불변, 거절 기록만 추가 |
| 다른 마켓의 발행 원장 ID 충돌 | BTC 600 예약 → 그 원장 ID로 SUI 500 / 새 SUI 400 | 먼저 부족하면 정상 거절·노드 유지. 충분한 후보의 ID 충돌이면 응답 미확인·노드 종료·오류 관찰. 종료 뒤 정상 snapshot은 반환하지 않음 |

설정 오류 10개는 **미등록 주문 마켓, 전달 마켓 ID, base 자산, quote 자산, 수량 스케일, 상품, 수수료 등급, 정책 버전, maker 요율, taker 요율**이다. SUI 정상 버전은 2라서 오류값은 1을 사용한다. 각 변형이 정상값과 다르다는 것도 검사한다.

`request()`는 전송 전 관찰 위치를 기억한다. 실제 응답을 받은 뒤 그 위치보다 뒤의 관찰을 유한 기한 안에 기다리고, 응답과 관찰의 결과가 일치하는지 확인한다. 이전 요청의 snapshot으로 통과하지 않는다. `snapshotValues()`는 업무 값을 비교하며 전송 위치 자체가 같아야 한다고 요구하지 않는다.

원장 기대 금액은 명세의 600·400을 직접 쓰고, 사건 ID는 고정값으로 대조한다. 제품 자금 계산기를 정답 생성에 쓰지 않는다. 마지막 실패 사례의 종료 후 무변경은 새 노드 테스트가 직접 조회한 보장이 아니다. 반영 전 불변·반영 중 차단은 기존 도메인/어댑터 검사와 함께 읽는다.

</details>

<details>
<summary>설정 테스트와 기존 회귀 검사</summary>

새 설정 검사 7개는 다음을 확인한다.

| 준비·실행 | 기대 결과 |
| --- | --- |
| BTC·SUI의 등록 순서를 바꿔 조회 | 같은 ID는 같은 문맥, 미등록 ETH는 없음 |
| 등록 뒤 원본 목록을 지우고 조회값의 복사본 수정 | 내부 BTC·SUI 설정 보존 |
| 빈 목록, 같은 ID·같은 내용, 같은 ID·다른 정책 | 모두 등록 거절 |
| 기본 BTC와 사용자 지정 SUI 한 항목 | 단일 호환 접근에 해당 등록값 반환 |
| 두 항목에서 단일 전용 접근 | 첫 항목을 선택하지 않고 거절 |
| 두 번째 문맥의 긴 식별자·NUL·잘못된 UTF-8·자산 콜론·비현물 정책 | 기존 제약대로 거절 |
| UTF-8 256바이트인 64개 코드포인트, 스케일 0/18 | 기존 유효 경계 허용 |

`MarketDefinition`의 서로 다른 자산·스케일 범위, 수수료 값의 버전·요율 검사는 기존 생성자를 그대로 사용한다. 새로운 별도 도메인 검증기는 없다.

기존 검사에서 BTC BUY/SELL·비영점 수수료, 요청/주문 식별자 우선순위, 정상 거절과 내부 오류, 응답 시간 초과, 코덱, 사용자 지정 한글 마켓의 실제 Archive 기록, 단일 READY를 다시 확인한다. 마켓 설정 변경을 이유로 이 기대값을 약화하지 않았다.

</details>

<details>
<summary>실행 근거·검토 상태·남은 범위</summary>

기준은 통합 `168a378bba3a8ef3a362fe4ce8812c176d3a5e0b`다. 브랜치는 `feat/multi-market-admission/114`이며, 아래 검사는 동일한 구현·설정·테스트 파일을 대상으로 실행했다.

- 설정 API 작성 전: 미구현 타입에 의한 컴파일 실패. 행동 수준의 Red로 부르지 않는다.
- 다중 설정 연결 전: 대표 실제 노드 테스트가 기존 `config.market`의 단일 접근에서 실패했다. 노드까지 등록 목록을 전달하도록 바꾼 뒤 통과했다.
- 기존 관련 app-api 테스트 133개, domain-trading 100개 통과. 실패·skip 0.
- 신규 실제 노드 7개 + 설정 검사 10개(기존 3개 포함) 통과. 실패·skip 0. 위 133개에 설정 10개가 포함되므로 두 결과를 단순 합산하지 않는다.
- 공통 린트와 아키텍처 테스트 375개 통과. 필수 구조 규칙 P01~P09의 실행·성공 보고서도 확인했다. 실패·오류·skip 0.
- 작성 대화를 받지 않은 별도 리뷰어가 기준 `168a378`에서 변경 9개와 관련 호출 경로를 검토했다. 현재 구현·설정·테스트 파일 8개는 검사를 실행한 사본과 바이트 단위로 같다.
- 리뷰어가 app-api 140개·domain-trading 100개·아키텍처 375개와 린트를 직접 실행했다. 실패·오류·skip 0이며, 검토한 범위에서 확인된 미해결 결함이나 필수 검증 누락은 없다. 구현·테스트 파일은 검토 사본과 같고, 이후 이 문서의 실행·리뷰 상태만 갱신했다.

```sh
cd /path/to/exchange-core # feat/multi-market-admission/114 체크아웃 경로
./gradlew :app-api:test --tests '*DevelopmentMultiMarketOrderNodeTest' --tests '*DevelopmentFundingSettingsTest' --tests '*DevelopmentOrderNodeTest' --tests '*DevelopmentFundingAdapterTest' --tests '*DevelopmentFundingNodeTest' --tests '*DevelopmentOrderClientTest' --tests '*DevelopmentFundingClientTest' --tests '*DevelopmentFundingEventsTest' --tests '*FundingReplicationComparisonTest' --tests '*AeronRecordedInputTest' --tests '*Admission*Test' :domain-trading:test --console=plain
./gradlew ktlintCheck :architecture-tests:test :architecture-tests:verifyArchitectureReport --console=plain
```

이번 실행은 실제 단일 노드의 접수 연결을 검증한다. 세 노드 다중 마켓 복제, 여러 클라이언트의 동시 접수, 복구, 마켓별 서버 분리, HTTP API, 매칭·정산·취소, Kafka/DB 연결, 성능 목표는 검증한 범위가 아니다. 새 Docker 시나리오나 CI 설정 변경은 없다.

후속은 [#115 외부 설정·마켓 선택](https://github.com/0Chord/coin-exchange/issues/115), [#116 세 노드 비교](https://github.com/0Chord/coin-exchange/issues/116), [#117 동시 접수](https://github.com/0Chord/coin-exchange/issues/117)다. [#118 RDB 설정 관리](https://github.com/0Chord/coin-exchange/issues/118)는 #115의 설정 읽기를 재사용하는 별도 작업이다.

</details>
