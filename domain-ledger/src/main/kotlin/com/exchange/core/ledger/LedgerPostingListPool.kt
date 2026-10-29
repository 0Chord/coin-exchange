package com.exchange.core.ledger

/**
 * A bounded cache of already validated, immutable posting snapshots.
 *
 * The caller must finish every transaction validation and pass a defensive, unmodifiable snapshot.
 * Keys use ordered List equality and every LedgerPosting field; a hash collision cannot merge values.
 * Eviction only drops this cache's reference. Existing transactions keep their immutable snapshots.
 * All mutable state is guarded by this instance's monitor, including access-order changes on hits.
 *
 * Text and posting limits bound the graph retained by the cache, not the values accepted by a ledger.
 * Ineligible snapshots pass through unchanged. No transaction identity or business state is cached.
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

        // Subtraction avoids overflow even when a test configures a large positive limit.
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
