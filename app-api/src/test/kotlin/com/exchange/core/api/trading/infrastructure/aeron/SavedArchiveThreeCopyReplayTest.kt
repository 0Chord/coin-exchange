package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.test.assertTrue

/** 명시적으로 선택할 때만 실행한다. 검증한 작업 사본에 새 Archive/Driver를 시작하며 거래 서비스는 시작하지 않는다. */
class SavedArchiveThreeCopyReplayTest {
    @Test
    fun `explicit saved normal single-term prefix matches three full spools without opening originals for write`() {
        val supplied = System.getenv("SAVED_ARCHIVE_REPLAY_PLAN")
        assumeTrue(!supplied.isNullOrBlank(), "SAVED_ARCHIVE_REPLAY_PLAN must explicitly name a small saved-run plan")
        val file = Path.of(checkNotNull(supplied))
        requireSavedArchiveNoLinks(file)
        require(Files.isRegularFile(file, NOFOLLOW_LINKS) && Files.size(file) in 1..65_536)
        val json = Files.newInputStream(file, NOFOLLOW_LINKS).use { savedArchiveJson.readTree(it) }

        fun integer(name: String): Long {
            val value = json.path(name)
            require(value.isIntegralNumber && value.canConvertToLong()) { "explicit integer plan field required: $name" }
            return value.asLong()
        }
        val stream = integer("logStreamId")
        val leader = integer("leaderMemberId")
        require(stream in 1..Int.MAX_VALUE && leader in 0..2)
        val plan =
            SavedArchiveReplayPlan(
                sourceReports = Path.of(json.path("sourceReports").asString()),
                workDirectory = Path.of(json.path("workDirectory").asString()),
                runId = json.path("runId").asString(),
                startPosition = integer("startPosition"),
                limitPosition = integer("limitPosition"),
                leadershipTermId = integer("leadershipTermId"),
                logStreamId = stream.toInt(),
                leaderMemberId = leader.toInt(),
            )
        val result = readSavedArchiveThreeCopies(plan)
        println("SAVED_ARCHIVE_REPLAY_RESULT ${savedArchiveJson.writeValueAsString(result)}")
        assertTrue(result.verified, "saved replay prefix did not verify; inspect ${plan.workDirectory}/replay-report.json: $result")
    }
}
