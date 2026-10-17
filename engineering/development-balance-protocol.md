# #96 · 자금 준비 요청을 바이트로 주고받기

**#95에서 만든 자금 준비 기능에 Protobuf 변환기를 붙였다.** “사용자 A에게 테스트용 1,000 USDT 준비”라는 요청을 바이트로 바꿨다가, 같은 값으로 읽어 실제 메모리 엔진을 호출할 수 있다.

**이번 흐름:** 요청 값 → 바이트 → 요청 값 → 기존 #95 엔진 → 결과 값 → 바이트 → 결과 값.

돈이 바뀌는 곳은 **기존 엔진 호출 한 번**이다. 변환기 자체는 돈을 바꾸지 않는다. 이 연결은 테스트에서 실행했으며, 실제 서버 간 통신은 다음 #97에서 만든다.

| 보내는 요청 | 확인한 결과 |
| --- | --- |
| P1: A에게 1,000 USDT 처음 준비 | 최초 준비 성공. 현재 사용 가능 1,000 USDT·예약 0 USDT |
| 주문에 300 USDT를 묶은 뒤 같은 P1 재전송 | 처음 지급한 1,000 USDT의 기록을 반환. 현재 사용 가능 700 USDT·예약 300 USDT 유지 |
| P1의 금액·사용자·자산 변경 | 요청 내용 충돌. 추가 지급 없음 |
| 새 P2로 이미 사용한 같은 계정에 지급 | 계정 사용 충돌. 추가 지급 없음 |
| 손상되거나 필수 값이 빠진 바이트 | 변환 오류. 엔진 호출 없음 |

**검증 상태:** 로컬 구현·필수 검사·독립 리뷰 완료. PR·CI·병합의 최신 상태는 GitHub의 연결 PR에서 확인한다. [합의한 #96 명세](https://github.com/0Chord/coin-exchange/issues/96) · [전체 작업 순서](aeron-admission-plan.md).

<details markdown="1">
<summary>코드에서 따라 읽을 순서와 실패 경계</summary>

빈 상태에서 처음 1,000 USDT를 준비하는 경우다. 아래 잔고는 같은 엔진의 현재 값이다.

| 실행 지점 | 하는 일 | 사용 가능 / 예약 |
| --- | --- | --- |
| 요청 `encode` → `decode` | “A에게 1,000 USDT 준비”를 바이트로 썼다가 읽음 | 0 USDT / 0 USDT |
| `prepareDevelopmentBalance` | 기존 엔진이 최초 요청을 확인하고 잔고·지급 원본·OPENING을 반영 | 1,000 USDT / 0 USDT |
| 결과 `encode` → `decode` | 이미 끝난 지급 결과를 바이트로 썼다가 읽음 | 1,000 USDT / 0 USDT |

이후 주문이 300 USDT를 예약하면 현재 값은 사용 가능 700 USDT·예약 300 USDT다. 같은 준비 요청의 변환을 여러 번 하거나 엔진에 재전송해도 1,000으로 다시 채우지 않는다. 첫 지급 기록만 확인한다. 변환만 성공했다고 지급 성공을 응답해서도 안 된다.

1. [DevelopmentBalanceCommandPayload.kt](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/codec/DevelopmentBalanceCommandPayload.kt)는 준비 원본과 호출자가 정한 원장 ID·시각을 담는다. 현재 잔고나 자동 생성한 ID는 넣지 않는다.
2. [DevelopmentBalanceCommandCodec.kt](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/codec/DevelopmentBalanceCommandCodec.kt)의 `encode`는 값을 바이트로, `decode`는 바이트를 값으로 바꾼다. `OpeningBalance`를 다시 만들어 기존 식별자·양수 금액 규칙을 적용한다.
3. 테스트는 복원한 값으로 기존 `OrderAdmissionState.prepareDevelopmentBalance`를 호출한다. 여기에서만 실제 지급·재전송·충돌을 판단한다. 이번 제품 코드에 범용 실행기나 서버 연결을 추가하지 않았다.
4. [DevelopmentBalanceResultPayload.kt](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/codec/DevelopmentBalanceResultPayload.kt)는 현재 요청과 실제 결과를 연결한다. 다른 요청의 성공을 섞거나 최초 성공의 ID·시각을 바꾸면 내부 오류다. 반복 성공은 과거의 첫 결과를 유지한다.
5. [DevelopmentBalanceResultCodec.kt](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/codec/DevelopmentBalanceResultCodec.kt)는 성공 또는 두 예상 충돌만 변환한다. 차단·내부 계산 오류를 업무 거절로 바꾸지 않는다.

**원장 ID를 요청과 응답에서 다르게 확인하는 이유:** 같은 P1 재전송은 새 원장 ID를 사용하지 않는다. 따라서 요청에 빈 ID가 명시되어 있어도 바이트로 표현 가능하면 기존 엔진까지 전달한다. 신규 P1이라면 엔진이 잘못된 ID를 거절한다. 반면 성공 응답의 ID는 실제 사용한 첫 원장 ID이므로 비어 있거나 손상된 ID를 허용하면 안 된다. 성공 응답은 엄격한 UTF-8 검사 뒤 기존 코어와 같은 비공백·NUL 금지·64 code point 이내 조건을 확인한다. 기존 주문의 검사 순서·접근 범위는 바꾸지 않았다.

**변환 실패와 반영 실패:** 잘못된 바이트는 엔진에 들어가지 않는다. 실제 반영 중 오류는 원래 오류를 전달하고 기존 상태가 차단된다. 신규·반복 요청 모두 차단을 우회하지 못한다. 이미 지급한 뒤 응답 변환에 실패한 경우 돈을 되돌리지는 않는다. 같은 준비 ID·원본으로 다시 확인하면 첫 결과를 돌려준다.

**관찰값과 복구:** 테스트의 `state.snapshot()`은 현재 상태를 읽는 불변 관찰값이다. 바이트나 관찰값을 이전에 받아 놓았으면 이후 입력 버퍼 덮어쓰기·다음 지급·주문이 그 값을 소급 변경하지 않는다. 디스크 Snapshot이나 재시작 복구를 만든 것은 아니다.

</details>

<details markdown="1">
<summary>전송 계약: 무엇을 유지하고 무엇을 추가했나</summary>

기존 [order_admission.proto](../app-api/src/main/proto/order_admission.proto)의 주문 메시지·enum 번호는 유지했다. 같은 generated package·Protobuf 라이브러리·Gradle 생성 설정을 사용하며 생성된 Java 파일은 build 폴더에만 둔다. 제품 코드 변경은 새 payload·codec 네 파일, 기존 proto의 메시지 추가, 기존 프레임의 종류 상수 추가다. 도메인 지급 코드·SQL·API·Bean 연결은 변경하지 않았다.

| 종류 번호 | 본문 |
| --- | --- |
| 기존 1 / 2 / 3 | 주문 명령 / 승인 / 거절. 기존 고정 바이트 그대로 |
| 새 4 | 준비 ID·사용자·자산·양수 금액·원장 ID·epoch second·nano |
| 새 5 | 첫 준비 원본·실제 첫 원장 ID·시각·`alreadyPrepared` |
| 새 6 | 지금 제출한 준비 원본·내용 충돌(1) 또는 계정 사용 충돌(2) |

기존 [AdmissionMessageFormat.kt](../app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/codec/AdmissionMessageFormat.kt)의 8바이트 헤더·schema 87/version 2·전체 4,096바이트 상한·문자열별 UTF-8 256바이트 상한·구간 읽기·변환 오류 처리를 재사용했다. 기존 코덱은 새 종류를 거절한다. 같은 버전 번호를 사용한다는 이유로 혼합 배포를 보장하지 않는다.

모든 필드는 `optional`로 presence를 확인한다. 명시한 false·epoch 0·nano 0과 누락을 구분한다. 미지 필드와 알려진 필드의 잘못된 wire type도 거절한다. 정상 타입의 단일 필드가 반복되면 Protobuf의 마지막 값 규칙을 유지한다. 나노초는 `Instant`가 보정하기 전에 범위를 확인하고 초·나노초를 손실 없이 보존한다.

금액은 최소 단위의 `Long`이다. 아래 통합 사례는 1,000 USDT를 `1_000_000_000`, 300 USDT를 `300_000_000`으로 표시한다. 작은 고정 바이트 예시의 금액 `1000`은 1,000 **최소 단위**다. 두 문맥을 혼동하지 않는다.

</details>

<details markdown="1">
<summary>테스트 1: 변환 계약을 독립된 바이트와 비교</summary>

[DevelopmentBalanceCodecTest.kt](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/DevelopmentBalanceCodecTest.kt)의 12개 테스트다. 고정 바이트는 제품 인코더로 정답을 만들지 않았다. 손상 입력은 생성된 Protobuf 빌더나 직접 바이트로 준비했다.

| 테스트 이름의 시작 | Given · 준비 | When · 실행 | Then · 실제 확인 |
| --- | --- | --- | --- |
| 자금 준비 명령은 독립적으로… | P1/u/Q/최소 단위 1000·L1·고정 시각과 독립 hex | 인코딩·디코딩 각각 실행 | 헤더와 7개 필드의 고정 바이트·원본 값 일치 |
| 최초와 반복 성공은… | false·true가 명시된 각각의 고정 바이트 | 두 성공을 각각 변환 | 바이트 일치·첫 원본과 ID·나노초·반복 표시 일치 |
| 두 충돌은 제출 원본과… | 두 사유의 고정 바이트와 정확한 두 충돌 예외 | 각각 변환·결과 연결 | 지금 제출한 원본과 사유 1/2만 일치. 과거 값·예외 설명 없음 |
| 명령 성공 충돌의 필수… | 7/8/5개 필드 각각 한 개씩 제거 | 디코딩 | 모든 누락·빈 본문은 변환 예외 |
| 명시한 epoch와 nano… | 빈 명령 ID·epoch/nano 0·성공 false가 명시됨 | 디코딩 | 누락으로 오판하지 않고 정확히 보존 |
| 금액 양끝과 Instant… | 금액 1/MAX와 시각 EPOCH/MIN/MAX/나노초 | 명령·성공·거절 변환 | 값·바이트 정확 일치. 실수 변환·절삭 없음 |
| 모든 자금 메시지는 미지… | 세 종류 각각 unknown과 모든 필드의 잘못된 타입 | 단독/정상 값 앞/뒤로 삽입 후 디코딩 | 모든 조합 변환 예외 |
| 충돌 사유의 최종 값이… | 사유 0·3·255 또는 정상 뒤 0/3 | 디코딩 | 최종 사유가 미지원이면 변환 예외 |
| 알려진 단일 필드가… | 정상 타입의 문자열·숫자·bool·enum 반복 | 디코딩 | 마지막 값 보존. 중복 자체를 금지하지 않음 |
| 기존 주문 golden과… | 기존 주문 1/2/3 및 새 4/5/6 고정 예시 | 네 코덱으로 교차 읽기 | 지원 종류만 허용·나머지 거절. 기존 주문 바이트도 양방향 유지 |
| 성공 결과의 원본이… | 다른 준비 ID/사용자/자산/금액의 결과 | 최초·반복 결과 연결 | 모두 내부 오류 |
| 최초 성공은 제출한… | 첫 성공 또는 ID/시각이 다른 결과 | 현재 요청과 연결 | 최초 불일치는 내부 오류. 반복은 최초 문맥 유지 |

</details>

<details markdown="1">
<summary>테스트 2: 손상·경계 입력과 버퍼 소유</summary>

[DevelopmentBalanceBoundaryTest.kt](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/DevelopmentBalanceBoundaryTest.kt)의 7개 테스트다. 변환 오류 뒤 코어 호출 횟수 0·미변경 상태를 확인하며, 실제 네트워크는 사용하지 않는다.

| 테스트 이름의 시작 | Given · 준비 | When · 실행 | Then · 실제 확인 |
| --- | --- | --- | --- |
| 읽을 수 없는 명령은… | 손상 헤더/본문/varint/tag·0/음수 금액·잘못된 ID/시각 | 변환 뒤에 코어 호출을 연결 | 변환 예외·코어 0회·빈 잔고/준비 유지·차단 없음 |
| 4096바이트의 정확한… | 알려진 필드 반복으로 정확한 상한·공유 배열 구간 | 디코딩 후 원본 배열 덮어쓰기 | 값 유지. 4097·배열 밖·음수/최대 offset·잘못된 length 거절 |
| 문자 수와 UTF8… | 120 ASCII 준비 ID·64개 4바이트 사용자/자산·조합형 원문 | 왕복 및 초과/손상 값 변환 | 경계 원문 유지. 257바이트·고립 서로게이트·손상 UTF-8·잘린 문자열 거절 |
| 명령의 미사용 원장… | 빈/공백/NUL/65자 새 ID, 유효 64 code point 성공 ID | 명령과 성공을 각각 변환 | 명령의 표현 가능한 문맥은 보존. 성공의 잘못된 실제 ID는 양방향 거절 |
| 바뀐 헤더는 거절… | 잘못된 schema/version/kind/길이와 공유 결과 버퍼 | 디코딩·원본 덮어쓰기·다음 인코딩 | 헤더 위반 거절. 이전 결과 값·인코딩 배열 유지 |
| 성공과 거절도 손상된… | 두 결과의 잘못된 원본/금액/UTF-8/구간, 성공의 잘못된 시각 | 직접 결과 디코딩 | 각 변환 예외. 명령만 엄격하고 결과는 우회하는 빈틈을 방지 |
| 성공과 거절도 UTF8… | UTF-8 허용 경계 및 알려진 필드로 채운 정확한 4096바이트 | 결과 변환 | 원문·최종 값 유지. 초과 문자열·4097 거절 |

</details>

<details markdown="1">
<summary>테스트 3: 기존 메모리 엔진과 연결</summary>

[DevelopmentBalanceProtocolFlowTest.kt](../app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/codec/DevelopmentBalanceProtocolFlowTest.kt)의 12개 테스트다. 실제 #95 상태·주문/수수료 계산기·도메인 객체를 호출한다. 변환 실패는 그 앞에서 끝난다. 예상 충돌은 정확한 두 예외만 연결하며 공통 `IllegalStateException`을 거절로 삼키지 않는다.

| 테스트 이름의 시작 | Given · 준비 | When · 실행 | Then · 실제 확인 |
| --- | --- | --- | --- |
| P09 빈 실제 상태는… | 빈 메모리와 1,000 USDT 요청 | 요청 왕복 → 실제 준비 → 응답 왕복 | 잔고 1,000·예약 0·OPENING 정확한 2분개/1건·첫 ID/시각/false, 주문/요청 없음·순번 0 |
| P09 같은 프레임을… | 독립된 실제 상태 둘·고정 입력 하나 | 최초·반복을 각 상태에 적용 | 결과·잔고·원장 등 의미 필드 일치. 객체 주소 비교 아님 |
| P10 같은 원본은… | 첫 지급 완료 | 새/빈/공백/65자 ID·다른 시각으로 반복, 무시한 ID로 다른 사용자 준비 | 첫 결과 true·무변경. 무시한 ID는 실제 신규 지급에 사용 가능 |
| P10 표현 불가능한… | 지급 완료 후 긴/손상 문맥 | 변환 뒤 코어 호출 연결 | 코어 0회·기존 기록 그대로·차단 없음 |
| P11 주문 프레임으로… | 지급 완료 | 기존 주문 코덱으로 300 예약 → 준비 재전송 | 현재 사용 가능 700·예약 300. 응답은 처음 1,000·ID/시각 유지·OPENING/RESERVE 각 1건 |
| P12 원본 변경과… | 지급 완료 | 동일 ID의 금액/사용자/자산 변경, 새 ID로 같은 계정 지급 | 각각 정확한 두 충돌 응답·지금 제출한 원본·추가 변경 없음 |
| P13 부족했던 R1은… | 빈 잔고의 R1 부족 거절 | 자금 준비 → R1 반복 → 새 R2 → R1 반복 | R1 첫 부족 결과 유지. R2만 승인·순번 1·현재 사용 가능 700·예약 300 |
| P14 반영 중 원래… | 첫 지급·이전 관찰 보관, 실제 준비 기록 쓰기에 오류 주입 | 신규 지급 후 신규/반복 요청 | 원래 Exception/Error 그대로·차단·조회/신규/반복 모두 거절·이전 관찰 유지 |
| 잘못된 신규 문맥과… | 빈 상태 또는 이미 사용한 원장 ID | 신규 지급 | 빈 ID 내부 검증·거래 ID 충돌을 예상 업무 거절로 바꾸지 않음. 상태 유지·차단 없음 |
| P15 다른 원본이나… | 실제 첫 지급 결과 | 다른 원본 또는 최초 ID/시각을 섞어 연결 | 내부 오류·기존 상태 무변경 |
| 성공 뒤 응답 변환… | 실제 지급 완료 | 손상된 응답 변환 → 같은 준비 재전송 | 변환 오류로 지급을 취소하지 않음. 첫 결과 true·현재 상태 유지 |
| P16 구간 버퍼 덮어쓰기… | 구간에서 읽은 요청/응답·배열·관찰값 보관 | 입력 덮어쓰기·다른 지급·주문 | 이전 값/바이트/관찰은 그대로. 현재 상태만 새 거래 반영 |

P14는 테스트 리플렉션으로 실제 상태의 collection 쓰기에 실패를 주입한다. 제품용 오류 주입 API를 만들지 않았고 #95의 모든 실패 위치를 다시 복제하지 않았다. 이 테스트의 `send`는 변환·직접 호출을 연결하는 테스트 함수이며 실제 Aeron 클라이언트가 아니다.

</details>

<details markdown="1">
<summary>검증 기록·문답·이번 범위의 끝</summary>

기준은 통합 `c6f962c96bb60d731377e5e3e75b08a1203eefc0`다. 작업 브랜치는 `feat/development-balance-protocol/96`이다.

동작 없는 Kotlin 입구에서 새 테스트 29개가 실제 `NotImplementedError`로 실패한 뒤 변환 기능을 연결했다. 처음 API가 없어서 생긴 컴파일 실패는 이 Red 근거와 구분한다. 테스트 의도 검토에서 결과 디코더의 손상·경계 검사가 부족해 직접 검사 2개를 보강했으며, 새 테스트는 총 31개다. 한 린트 줄 길이 오류도 수정했다.

| 실제 실행한 검사 | 결과 |
| --- | --- |
| app-api의 codec 테스트 | 63개 통과. 신규 31개 포함 |
| domain-trading 테스트 | 99개 통과 |
| 구조 테스트 | 375개 통과 |
| 필수 운영 구조 보고서 | P01~P09 실행·성공 확인 |
| clean 후 Protobuf 생성·컴파일 | 통과. 생성물을 저장소에 추가하지 않음 |
| 전체 ktlintCheck | 통과 |

테스트 실패·오류·skip은 모두 0이다. 작성 대화를 상속하지 않은 별도 리뷰어가 격리 checkout `review-snapshot-1`에서 같은 검사와 독립 반례 11개를 직접 실행했다. 손상 입력의 기존 상태 보존, NUL 미사용 문맥, 계정 충돌 우선순서, 구간 밖 바이트 차용 금지, 잘못된 varint, 차단 진단과 반복 차단을 추가 확인했다. 전체 코드·테스트에서 범위 안의 확인된 미해결 결함과 필수 수용 검증 누락은 발견하지 못했다. 향후 모든 결함이 없다는 보장은 아니다.

검토 대상은 위 기준 커밋에 변경 11개 파일을 덧씌운 고정 사본이다. 게시한 제품 코드·proto·테스트 9개 파일은 그 사본과 동일하다. 문서 두 개에는 실행 결과와 학습 설명·게시 안내를 후속 보완했다. 원격 검토자는 이 PR의 코드·테스트와 아래 명령으로 검증할 수 있다. 로컬 검증 기록을 원격 CI·병합 결과로 대신하지 않는다.

아래는 새 검토용 체크아웃에서 동일한 검사를 재현하는 예다. 검토 폴더가 이미 있으면 새 경로를 지정하며 기존 폴더를 지우거나 덮어쓰지 않는다. 이미 이 PR을 체크아웃했다면 해당 저장소 루트에서 같은 Gradle 작업만 실행하면 된다.

```sh
git clone --branch feat/development-balance-protocol/96 --single-branch https://github.com/0Chord/coin-exchange.git /tmp/coin-exchange-issue96-review
cd /tmp/coin-exchange-issue96-review
./gradlew :app-api:clean :app-api:generateProto :app-api:compileKotlin
./gradlew :app-api:test --tests '*codec.*' :domain-trading:test :architecture-tests:test :architecture-tests:verifyArchitectureReport ktlintCheck
```

학습 질문은 “바이트 변환과 돈이 바뀌는 시점 / 재전송의 첫 결과 / 손상 메시지와 업무 거절 중 어느 흐름을 더 자세히 보고 싶은가?”였다. 사용자는 **“바이트 변환과 실제 돈이 바뀌는 시점의 차이 (추천)”**를 선택했다. 이에 따라 코드 읽기 첫 부분에 변환 전후 0/0 → 실제 엔진 호출 뒤 1,000/0 → 결과 변환 뒤에도 1,000/0이라는 표를 추가했다. 별도 선택 이유나 이해 완료는 답하지 않았으므로 추측하지 않는다.

이번에는 Protobuf 변환과 실제 순수 코어 연계를 검증한다. HTTP·DB·Aeron 전송·Cluster 합의·Archive·재시작·영구 Snapshot·동시 요청·성능을 검증한 것이 아니다. 다음 #97에서 실제 노드에 이 요청을 보낸다.

</details>
