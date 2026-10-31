package com.exchange.core.ledger

/**
 * 검증을 마친 불변 원장 항목 스냅샷을 정해진 한도 안에서 캐시한다.
 *
 * 호출자는 거래 검증을 모두 마친 뒤 방어적으로 복사한 수정 불가 스냅샷을 전달해야 한다.
 * 키는 List의 순서와 모든 LedgerPosting 필드를 비교하므로 해시가 충돌해도 서로 다른 값을 합치지 않는다.
 * 퇴출 시 캐시의 참조만 해제한다. 기존 거래는 각자의 불변 스냅샷을 유지한다.
 * 조회 적중에 따른 접근 순서 변경을 포함해, 가변 상태는 모두 이 인스턴스의 모니터로 보호한다.
 *
 * 문자열과 항목 수의 한도는 캐시가 붙잡는 객체 범위를 제한하며, 원장에서 허용하는 값은 제한하지 않는다.
 * 캐시 대상이 아닌 스냅샷은 그대로 반환한다. 거래 식별자나 업무 상태는 캐시하지 않는다.
 */
internal class LedgerPostingListPool(
    private val maxEntries: Int = 256,
    private val maxPostings: Int = 4096,
    private val maxTextLength: Int = 256,
) {
    private val entries = LinkedHashMap<List<LedgerPosting>, List<LedgerPosting>>(16, 0.75f, true)
    private var retainedPostings = 0

    init {
        require(maxEntries > 0 && maxPostings > 0 && maxTextLength > 0)
    }

    @Synchronized
    fun shareValidated(snapshot: List<LedgerPosting>): List<LedgerPosting> {
        if (
            snapshot.size > maxPostings ||
            snapshot.any {
                it.accountId.length > maxTextLength || it.assetId.value.length > maxTextLength
            }
        ) {
            return snapshot
        }
        entries[snapshot]?.let { return it }

        // 테스트에서 큰 양수 한도를 설정해도 넘치지 않도록 뺄셈으로 비교한다.
        while (entries.size >= maxEntries || retainedPostings > maxPostings - snapshot.size) {
            val oldest = entries.entries.iterator()
            retainedPostings -= oldest.next().key.size
            oldest.remove()
        }
        entries[snapshot] = snapshot
        retainedPostings += snapshot.size
        return snapshot
    }
}
