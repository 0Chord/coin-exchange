확인된 신규 결함은 0건이다. 이번 성능 변경에 대해 수정이 필요한 회귀를 찾지 못했다. 이는 테스트 통과나 성능 개선의 실행 확인을 뜻하지 않는다.

## 고정한 대상과 자료

- 저장소: `/Users/0chord/.codex/worktrees/issue148-http-aeron/exchange-core`
- 조회 당시 브랜치: `test/http-trading-performance/142`. 리뷰 근거는 변경 중인 checkout 대신 아래 스냅샷이다.
- base: `build/regression-fix-20261030/source-v2`
- 최초 target: `build/service-progress-fix-20261031/review-source`
- 최종 target: `build/service-progress-fix-20261031/review-source-final`
- 최종 manifest: `build/service-progress-fix-20261031/review-source-final-manifest.json`, SHA-256 `2838b80bbeb40bbe442b5cbcbd0c4f3298ee92b33062a90a8b1ea0e17055787f`
- 최종 manifest의 521개 파일을 직접 해시 대조했고 불일치는 0개였다. base 대비 제품 2개·테스트 2개 변경과 `AGENTS.md` 추가를 확인했다.
- 최초 target → 최종 target의 실제 diff는 `TradingNodeTelemetry.kt`의 빈 줄 1개와 신규 첫 성공 테스트의 동일한 decode 결과를 지역 변수에 담는 변경뿐이었다. 두 차이를 추가 검토했으며 판단에 영향이 없다.
- 적용한 지침: 저장소 `AGENTS.md`, `pr-reviewer/SKILL.md`와 실행·지적 검증 참고 문서. 전달받은 경로·검토 범위 외에 작성 대화, 작성자 해설, 이전 리뷰 보고서는 읽지 않았다.

## 확인한 흐름

1. **내부 실행 전송**: 신규 승인과 예약 적용 → 동일 Execute 인코딩 → 양수 offer/claim에서만 완료 → 이후 외부 응답 순서를 유지한다. bounded 경로의 인코딩된 바이트·버퍼와 legacy 경로의 sequence·길이·claim은 재시도 사이에 바뀌지 않는다. 이번 변경은 세 대기 지점의 호출을 `idleForRetry(attempt == 1)`로 바꾸고, 첫 재시도에서만 reset한 뒤 원래 cluster idle을 호출한다. 금융 상태 호출과 재전송 판단은 변경되지 않았다. 근거: [DevelopmentFundingAdapter.kt:411](/Users/0chord/.codex/worktrees/issue148-http-aeron/exchange-core/build/service-progress-fix-20261031/review-source-final/app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingAdapter.kt:411), [동일 bounded Execute:597](/Users/0chord/.codex/worktrees/issue148-http-aeron/exchange-core/build/service-progress-fix-20261031/review-source-final/app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingAdapter.kt:597), [legacy claim:788](/Users/0chord/.codex/worktrees/issue148-http-aeron/exchange-core/build/service-progress-fix-20261031/review-source-final/app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingAdapter.kt:788).

2. **전송 실패와 응답 실패**: BP/ADMIN만 재시도하고 0·다른 음수·예외는 기존 분기로 진행한다. 실행 제출 실패는 `fail`로 노드를 중단하고 예약을 임의 해제하지 않는다. 응답 실패는 적용된 결과를 보존하며 역할·세션 종료·횟수·시간 판단에 따라 반환한다. 최초 전송 성공이나 재시도 불가능한 반환에서는 reset/idle에 진입하지 않는다. 근거: [응답:851](/Users/0chord/.codex/worktrees/issue148-http-aeron/exchange-core/build/service-progress-fix-20261031/review-source-final/app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingAdapter.kt:851), [공통 idle:891](/Users/0chord/.codex/worktrees/issue148-http-aeron/exchange-core/build/service-progress-fix-20261031/review-source-final/app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingAdapter.kt:891), [fail:904](/Users/0chord/.codex/worktrees/issue148-http-aeron/exchange-core/build/service-progress-fix-20261031/review-source-final/app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingAdapter.kt:904).

3. **cluster 진행 보존**: 실제 의존 버전 Aeron 1.53.3의 로컬 sources에서 `ClusteredServiceAgent.idleStrategy()`는 agent 자신을 반환하며 `reset()`은 내부 idle만 reset하고 `idle()`은 내부 idle 후 interrupt·clock tick·서비스 background 작업을 계속 수행함을 확인했다. Agrona 2.6.1의 `BackoffIdleStrategy.reset()`은 SPINNING 이전 상태로 돌아간다. 따라서 helper가 cluster wrapper를 우회하거나 매 재시도마다 backoff를 지우는 변경은 아니다. 두 파일은 Gradle 캐시의 해당 버전 `-sources.jar`에서 직접 읽었다.

4. **SPSC 발행과 슬롯 재사용**: 단일 producer 소유와 재진입 방지는 유지된다. 로컬 producer 위치·슬롯은 수락한 offer에서만 전진하며 full·closed·종료·다른 producer 거절에서는 그대로다. 모든 primitive를 슬롯에 복사한 뒤 `published.lazySet`으로 공개하고, consumer는 record가 반환한 뒤 `consumed.lazySet`을 갱신한다. 생산자는 이 소비 prefix를 읽어 아직 기록 중인 슬롯을 재사용하지 않는다. 새 일반 필드는 producer만, 새 consumer 커서는 worker만 접근한다. capacity 1 및 3에서 경계 직전·직후, full 거절 뒤 재시도, 여러 바퀴의 인덱스 전이를 코드로 대조했다. 근거: [TradingNodeTelemetry.kt:363](/Users/0chord/.codex/worktrees/issue148-http-aeron/exchange-core/build/service-progress-fix-20261031/review-source-final/app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/TradingNodeTelemetry.kt:363), [offer:382](/Users/0chord/.codex/worktrees/issue148-http-aeron/exchange-core/build/service-progress-fix-20261031/review-source-final/app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/TradingNodeTelemetry.kt:382), [consume:430](/Users/0chord/.codex/worktrees/issue148-http-aeron/exchange-core/build/service-progress-fix-20261031/review-source-final/app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/TradingNodeTelemetry.kt:430).

5. **포화·닫기·fatal**: 큐의 false 반환 뒤 동기 fallback은 기존 수락 prefix의 drain을 먼저 기다린다. 선택적 bounded 모드의 drop/degraded 정책도 변경되지 않았다. close가 발행과 겹쳐도 `closing`과 `producerActive` 확인 뒤 prefix를 다시 읽으므로 수락한 마지막 발행을 비어 있다고 오판하는 경로가 추가되지 않았다. record Error는 소비 완료로 전진하지 않고 failure/fatal 통지와 종료로 이어지며, recorder의 pending fatal은 await/close에서 재전파된다. 근거: [fallback:267](/Users/0chord/.codex/worktrees/issue148-http-aeron/exchange-core/build/service-progress-fix-20261031/review-source-final/app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/TradingNodeTelemetry.kt:267), [worker 종료:443](/Users/0chord/.codex/worktrees/issue148-http-aeron/exchange-core/build/service-progress-fix-20261031/review-source-final/app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/TradingNodeTelemetry.kt:443), [close:463](/Users/0chord/.codex/worktrees/issue148-http-aeron/exchange-core/build/service-progress-fix-20261031/review-source-final/app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/TradingNodeTelemetry.kt:463). 실제 node의 recorder 구성 및 `onFatal = ::fail` 연결도 추적했다.

## 테스트의 기대값과 남은 검증 범위

- 변경된 `BoundedAdapterTelemetryTest`는 실행·응답 각각의 첫 대기 전 reset 1회, 다음 두 idle 순서, 첫 전송 성공 시 reset/idle 0회, 같은 바이트 재시도, 동일 토큰 재전송 시 추가 Execute 없음, 예약 잔고 보존을 확인한다. mock의 reset 횟수 검증을 실제 환경의 지연 개선으로 해석하지 않았다.
- 추가된 `TradingNodeMetricHandoffTest`는 capacity 3의 여러 회전, 원본 배열 변경 후 값 보존, full 거절 뒤 슬롯 재사용, 수락한 값의 누락·중복·순서 뒤바뀜을 실제 목록 동등성으로 확인한다. 기존 테스트에는 0ns/meter counter, full fallback prefix, drain timeout, Error 즉시 통지, 발행/close 경쟁 검증이 있다.
- 관련 기존 `AutomaticOrderExecutionTest`와 `DevelopmentFundingAdapterTest`에서 legacy claim의 동일 명령·header·commit 순서, 시간/횟수 초과, 종단 0, 응답 대기 중 follower/세션 종료, 응답 deadline 전 재시도 중단의 기대값을 읽었다. 이 리뷰에서 해당 테스트들을 실행하지는 않았다.
- 신규 reset 순서 검증은 bounded offer와 공통 reply를 직접 대상으로 하며 legacy claim의 reset 호출 순서는 별도 신규 assertion이 없다. 새 큐 회전 테스트는 여러 샘플의 모든 phase·kind·attempt 필드를 서로 다른 값으로 채우는 전수 검증은 아니다. 이는 테스트 범위의 한계이며 확인된 제품 결함은 아니다.
- JVM 동시성 스케줄 전수, 실제 클러스터의 진행/지연, 성능·CPU·긴 부하, 라이브러리 내부 전수, 금융 도메인 전체 재검증은 수행하지 않았다. root가 실행을 독점하므로 빌드·테스트·부하·별도 재현 실행을 시작하지 않았다. 실행을 요청해야 할 신규 실패 반례는 발견하지 못했다.

## 기존 경계: bounded 실행 전송의 timeout 의미

이번 변경 전부터 bounded 실행 전송은 실패한 offer 뒤에만 deadline을 확인하며, 다음 반복의 offer 전에는 시간을 다시 검사하지 않는다([DevelopmentFundingAdapter.kt:600](/Users/0chord/.codex/worktrees/issue148-http-aeron/exchange-core/build/service-progress-fix-20261031/review-source-final/app-api/src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentFundingAdapter.kt:600)). 첫 offer가 BP이고 deadline 검사 뒤 idle/background 작업이 기한을 넘겼으며 다음 offer가 양수인 코드 경로에서는 기한 이후에도 제출이 완료된다. legacy claim/reply에는 반복 앞 시간 검사가 있다. base와 target에 같은 분기 위치가 있으므로 이번 변경의 신규 결함으로 세지 않았다. 따라서 이번 리뷰는 bounded 경로의 엄격한 wall-clock cut-off를 보장하거나 검증했다고 주장하지 않는다. 이 경계의 실행 재현은 하지 않았다.

## 직접 수행한 확인

`diff -qr` 및 네 파일의 `diff -u`, manifest 전체 SHA-256 대조, `nl`/`rg`를 이용한 변경·호출·테스트 경로 읽기, 의존성 sources.jar의 `ClusteredServiceAgent`/`BackoffIdleStrategy` 읽기만 수행했다. CI·작성자 실행 결과는 조회하거나 독립 증거로 사용하지 않았다. 소스·테스트·설정·브랜치·원격 상태를 수정하지 않았으며 이 보고서만 작성했다.
