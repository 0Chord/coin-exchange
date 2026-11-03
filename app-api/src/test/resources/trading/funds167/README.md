# #167의 기존 snapshot 바이트

제품 코드의 저장 파일이 아니라, 이전 형식을 유지하는지 검사하는 작은 테스트 fixture다.

- 기준: 통합 커밋 `05e28a91a77a1772451bbf6ee8a6e1d308e17054`.
- `funds-role-v1-initial.bin`: 기존 `FundsRoleSnapshotCodec` writer의 v1 출력. quote1,000 준비 후 BUY3@100을 예약해 가용700/예약300, PENDING1·미확인 전달1인 상태다. 기록 위치4096.
- `matching-role-v1-multiple-fills.bin`: 기존 `MatchingRoleSnapshotCodec` writer의 v1 출력. SELL1@90·SELL1@95 뒤 BUY3@100을 적용해 체결2·잔량1과 전체 결과3건을 가진 상태다. 기록 위치4096.
- 각 `.sha256`은 원본 fixture의 지문이다. 새 writer가 만든 자료의 버전 번호를 바꿔 과거 형식으로 만들지 않았다.
- 생성 시 기준 커밋의 writer 원문을 가져와 클래스 이름만 임시 변경하고, 원래 테스트 준비 값에 적용했다. 두 생성 테스트의 실제 실행을 확인한 뒤 임시 writer·생성 테스트를 제거했다.
- 기존 자금 writer 원문 SHA-256: `b43d032a3372c16eaab789b131941d6983347f852c895d699c531b751ce83845`.
- 기존 매칭 writer 원문 SHA-256: `8f44f4fbbd5ad9caf70deb24b9e045e89dd1539ea03a665f746b5ff9f66ea53a`.

자금 fixture는 새 reader의 제한적 v1 읽기와 v2 저장을 확인한다. 매칭 fixture는 공통 결과 바이트 helper 추출 후에도 기존 v1의 여러 사건·효과를 byte 단위로 그대로 기록하는지 확인한다.
