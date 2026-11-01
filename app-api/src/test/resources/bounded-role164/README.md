# #164 변경 전 통합 복구 골든

기준 커밋은 `1b2d3bb6e7aad1e6c12dff8bfe7b861b027e373c`다. 이 커밋을 `git archive`로 분리한 뒤 기존 `BoundedTradingSnapshotContinuationTest`의 입력과 수기 기대값을 재사용했다. 원본 codec·continuation·run 검사와 생성기 총 10개 검사가 통과했다. 모든 원본 파일 625개(main 182개)가 기준 커밋과 같은 것도 대조했다.

`manifest-v1.bin`, `snapshot-v2.bin`, `recovery-identity.bin`은 변경 전 encoder가 만든 원자료다. 나머지 snapshot 두 개는 pending 실행 직후와 마지막 매수 체결 직후의 원자료다. 파일 지문·원본 실행물/스키마 지문은 `baseline.properties`에 기록했다. 새 encoder로 이 파일들을 다시 만들지 않는다.

초기 snapshot에는 잔고 13개, 활성 주문 4개, 주문장 항목 3개, pending 1개, 사용자 receipt 6개와 주문별 수수료 나머지 630000·440000이 있다. pending 매도는 FIFO로 먼저 대기한 매수 2개, 다음 매수 1개를 체결한다. first-bid의 USDT는 709, second-bid는 가용 798/예약 102, pending-seller 수취액은 298, 수수료 수익은 3이다. 마지막 매수 후 high-ask 수취 누계 329, final-taker USDT 779/BTC 2, 수수료 수익 5이며 second-bid 1개만 남는다. 테스트는 이 수치를 코드의 독립 기대값으로도 확인한다.

직렬화 형식과 실행물 지문 검사를 구분한다. identity 레이아웃 대조에서는 원본 identity 안의 유일한 실행물 지문 64자리만 현재 지문으로 명시 치환한다. 현재 실행물과 같은 설정의 재시작을 따로 검증하며, 다른 실행물 지문을 가진 manifest는 기존대로 거절한다. 과거 Archive를 새 build로 자동 재생하도록 허용하는 자료가 아니다. 검사 중 실제 노드·MediaDriver·Archive는 실행하지 않는다.

생성기·재생성 스크립트·독립 binary 검사·JUnit/XML·수기 산식은 작업 폴더의 `build/role164-golden-export`에 보존했다. 고정 원본 소스는 `build/role164-baseline-source`에 있고, 그 안의 재생성 가능한 build/cache만 검사 성공 후 정리했다. 자세한 삭제 범위는 export의 `cleanup.json`에 기록했다.
