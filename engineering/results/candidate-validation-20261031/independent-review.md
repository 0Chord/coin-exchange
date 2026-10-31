# 독립 검토 기록

작성 대화를 상속하지 않은 별도 검토를 진행했다. 실행·빌드·부하는 루트 한 곳에서 순서대로 진행했고 검토자는 읽기 전용으로 검토했다.

- candidate_domain_handoff_review: A 원본 명령·불변 이벤트 소유권·전체 결과 검증·실패 격리, B volatile 발행/대기 순서 검토. 확인된 결함 0. 최종 A 소스 동일·B/C 다섯 파일 원상복원·595파일 manifest 일치 확인. timed park 테스트만으로 missed wakeup 부재를 증명하지는 못함.
- candidate_client_review: C decoder/type/epoch/floor/index/duplicate/drain/Legacy와 재사용된 ByteArray 검토. 확인된 결함 0. formatter의 중괄호·줄바꿈 차이가 행동을 바꾸지 않음 확인. C는 별도 성능 판단에 따라 제외.
- candidate_runtime_wiring_audit: 후보별 클래스 소유 관계와 두 domain jar, JVM default method 경로 확인. 28회 실행 이미지·runtime manifest·84 최종 노드·168checkpoint·원본 이벤트·정리 원문 등 5,055조건 대조 불일치0. per-run percentile 원표본 미보존으로 자체 재산출 한계 명시. CPU/할당은 3개 서비스스레드 평균이며 client cost 아님. 초기 호스트 swap과 기준 변동 때문에 큰 중앙값 차이를 개선율로 주장하지 않도록 확인.
- 최종 재빌드 runtime 13파일 차이: main2는 LineNumberTable/SMAP 정규화 후 모든 나머지 바이트 동일. test9도debug-only, test본체2는 기존 검증강화 차이. 전체 binary/실행식별자 동일이라고 주장할 수 없음. 원문은 final-runtime-bytecode-audit.json 및 bytecode-audit/에 보존.
- 회귀887중 matching/trading432는 동일입력 UP-TO-DATE 재사용. architecture/API455와 Python13, HA2는 최종 새실행. XML시각과 원문 대조.

원본 검토 메시지는 현재 대화에 있으며 이 파일은 루트가 작성한 요약이다. 최종 장애시험 원문과 보고서의 추가 대조는 해당 산출물에 기록한다.

최종 추가 대조: HA10+remote6 조건 모두 true, 새JUnit2 실패·오류·skip0. 상태22파일/55node의 금융식·복구지문·장애경계285조건 불일치0. 실제HTTP응답경로 검증/별도물리사이트RPO0보장으로 확대 해석하지 않음. 원문요약은 final-verification-audit.json. 보고서의 CPU범위·중앙값변화율·소스동일성·회귀재사용 표현을 보완했다.
