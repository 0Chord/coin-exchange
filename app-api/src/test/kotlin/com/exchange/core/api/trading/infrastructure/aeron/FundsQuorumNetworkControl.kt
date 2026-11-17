package com.exchange.core.api.trading.infrastructure.aeron

import java.security.MessageDigest

internal data class FundsPeerPair(
    val first: String,
    val second: String,
)

internal enum class FundsQuorumNetworkDirection { INPUT, OUTPUT }

internal data class FundsQuorumNetworkRule(
    val service: String,
    val direction: FundsQuorumNetworkDirection,
    val peerAddress: String,
) {
    fun savedRule(plan: FundsQuorumNetworkPlan): String {
        val chain = if (direction == FundsQuorumNetworkDirection.INPUT) plan.inputChain else plan.outputChain
        val addressOption = if (direction == FundsQuorumNetworkDirection.INPUT) "-s" else "-d"
        return "-A $chain $addressOption $peerAddress/32 -m comment --comment ${plan.comment} -j DROP"
    }
}

internal data class FundsQuorumRuleCounter(
    val rule: FundsQuorumNetworkRule,
    val packets: Long,
    val bytes: Long,
)

internal data class FundsQuorumRuleChanges(
    val additions: List<FundsQuorumNetworkRule>,
    val removals: List<FundsQuorumNetworkRule>,
)

internal data class FundsQuorumReachability(
    val fromService: String,
    val toService: String,
    val destinationAddress: String,
    val reachable: Boolean,
    val observedAtMillis: Long,
)

/** 순수 계획과 실제 namespace에서 읽은 규칙·counter·도달 결과를 별도로 대조한다. */
internal class FundsQuorumNetworkPlan(
    runId: String,
    addresses: Map<String, String>,
    blockedPairs: Set<FundsPeerPair>,
    scopes: Map<String, Set<String>>,
) {
    val addresses = addresses.toMap()
    val scopes = scopes.mapValues { it.value.toSet() }
    private val funds = setOf("funds0", "funds1", "funds2")
    private val digest =
        MessageDigest
            .getInstance("SHA-256")
            .digest(runId.toByteArray())
            .take(6)
            .joinToString("") { "%02x".format(it) }
    val inputChain = "FQ138_${digest}_I"
    val outputChain = "FQ138_${digest}_O"
    val comment = "fq138-$digest"
    val blockedPairs =
        blockedPairs
            .map { pair ->
                val members = listOf(pair.first, pair.second).sorted()
                FundsPeerPair(members[0], members[1])
            }.toSet()
    val services: Set<String> = funds + scopes.keys
    val rules: List<FundsQuorumNetworkRule>

    init {
        require(runId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))) { "잘못된 run 식별자" }
        require(addresses.keys.containsAll(funds)) { "자금 세 노드 주소가 모두 필요함" }
        require(addresses.keys.all { it.matches(Regex("[a-z][a-z0-9-]{0,31}")) }) { "잘못된 서비스 식별자" }
        require(addresses.values.toSet().size == addresses.size) { "namespace 주소 중복" }
        addresses.values.forEach { address ->
            val parts = address.split('.')
            require(
                parts.size == 4 &&
                    parts.all { part ->
                        part.toIntOrNull()?.let { number -> number in 0..255 && part == number.toString() } == true
                    },
            ) {
                "IPv4 주소가 아님: $address"
            }
            val numbers = parts.map(String::toInt)
            require(
                numbers[0] == 10 || (numbers[0] == 172 && numbers[1] in 16..31) ||
                    (numbers[0] == 192 && numbers[1] == 168),
            ) { "전용 bridge의 사설 주소만 허용: $address" }
            require(numbers[3] in 1..254) { "호스트 주소가 아님: $address" }
        }
        require(
            addresses.values
                .map { it.substringBeforeLast('.') }
                .toSet()
                .size == 1,
        ) { "같은 run /24만 허용" }
        require(this.blockedPairs.all { it.first in funds && it.second in funds && it.first != it.second }) {
            "peer 차단은 서로 다른 funds 쌍에만 허용"
        }
        require(
            scopes.all { (client, allowed) ->
                client == "client" && client in addresses && allowed.isNotEmpty() && funds.containsAll(allowed)
            },
        ) { "scope는 client 자신의 namespace와 비어 있지 않은 funds 집합만 허용" }
        rules =
            buildList {
                for (pair in this@FundsQuorumNetworkPlan.blockedPairs) {
                    for ((service, peer) in listOf(pair.first to pair.second, pair.second to pair.first)) {
                        // 양끝 수신에서 버려 모든 peer 경로를 끊고 로컬 UDP send 오류는 섞지 않는다.
                        add(FundsQuorumNetworkRule(service, FundsQuorumNetworkDirection.INPUT, addresses.getValue(peer)))
                    }
                }
                for ((client, allowed) in scopes) {
                    for (peer in funds - allowed) {
                        for (direction in FundsQuorumNetworkDirection.entries) {
                            add(FundsQuorumNetworkRule(client, direction, addresses.getValue(peer)))
                        }
                    }
                }
            }.sortedWith(compareBy({ it.service }, { it.direction.name }, { it.peerAddress }))
    }

    fun expectedReachable(
        from: String,
        to: String,
    ): Boolean {
        require(from in addresses && to in addresses && from != to)
        val peer = listOf(from, to).sorted()
        if (FundsPeerPair(peer[0], peer[1]) in blockedPairs) return false
        if (from in scopes && to in funds && to !in scopes.getValue(from)) return false
        if (to in scopes && from in funds && from !in scopes.getValue(to)) return false
        return true
    }

    fun changesFrom(
        previous: FundsQuorumNetworkPlan,
        service: String,
    ): FundsQuorumRuleChanges {
        require(addresses == previous.addresses && inputChain == previous.inputChain && outputChain == previous.outputChain)
        val before = previous.rules.filter { it.service == service }
        val after = rules.filter { it.service == service }
        return FundsQuorumRuleChanges(after - before.toSet(), before - after.toSet())
    }

    fun probePairs(activeServices: Set<String> = addresses.keys): List<Pair<String, String>> =
        buildList {
            requireActiveServices(activeServices)
            for (from in (services intersect activeServices).sorted()) {
                for (to in activeServices.sorted()) {
                    // 존재 여부를 추정하지 않고 실행기가 확인한 live 집합을 그대로 사용한다.
                    if (to != from) add(from to to)
                }
            }
        }

    fun verifyInstalled(
        snapshots: Map<String, String>,
        activeServices: Set<String> = addresses.keys,
    ): List<FundsQuorumRuleCounter> {
        requireActiveServices(activeServices)
        check(snapshots.keys == (services intersect activeServices)) { "실제 규칙 namespace 누락/추가: ${snapshots.keys}" }
        return snapshots.flatMap { (service, raw) ->
            val parsed = parseSnapshot(raw)
            check(
                parsed.chains ==
                    mapOf(
                        "INPUT" to "ACCEPT",
                        "FORWARD" to "ACCEPT",
                        "OUTPUT" to "ACCEPT",
                        inputChain to "-",
                        outputChain to "-",
                    ),
            ) { "$service: 예상 밖 chain 또는 기본 정책" }
            val serviceRules = rules.filter { it.service == service }
            val expected =
                serviceRules.map { it.savedRule(this) } +
                    listOf("-A INPUT -j $inputChain", "-A OUTPUT -j $outputChain")
            check(parsed.rules.keys == expected.toSet() && parsed.ruleCount == expected.size) {
                "$service: 전체 IP 양방향 규칙 누락/추가/중복. expected=$expected actual=${parsed.rules.keys}"
            }
            serviceRules.map { rule ->
                val counters = parsed.rules.getValue(rule.savedRule(this))
                FundsQuorumRuleCounter(rule, counters.first, counters.second)
            }
        }
    }

    fun verifyDropCounters(
        counters: List<FundsQuorumRuleCounter>,
        activeServices: Set<String> = addresses.keys,
    ) {
        requireActiveServices(activeServices)
        val activeRules = rules.filter { it.service in activeServices }
        check(counters.map { it.rule }.toSet() == activeRules.toSet() && counters.size == activeRules.size) {
            "counter의 실제 규칙 결속 누락/중복"
        }
        val activeAddresses = activeServices.map { addresses.getValue(it) }.toSet()
        check(
            counters
                .filter {
                    it.rule.peerAddress in activeAddresses &&
                        (it.rule.service in funds || it.rule.direction == FundsQuorumNetworkDirection.OUTPUT)
                }.all { it.packets > 0 },
        ) {
            "peer 양끝 INPUT 또는 client OUTPUT의 실제 DROP counter가 증가하지 않음"
        }
    }

    fun verifyReachability(
        reachability: List<FundsQuorumReachability>,
        activeServices: Set<String> = addresses.keys,
    ) {
        requireActiveServices(activeServices)
        val actualPairs = reachability.map { it.fromService to it.toService }
        check(actualPairs.toSet() == probePairs(activeServices).toSet() && actualPairs.size == probePairs(activeServices).size) {
            "도달 행렬 누락/추가/중복"
        }
        for (entry in reachability) {
            check(entry.destinationAddress == addresses.getValue(entry.toService) && entry.observedAtMillis >= 0) {
                "도달 관측 주소/시간 불일치: $entry"
            }
            check(entry.reachable == expectedReachable(entry.fromService, entry.toService)) {
                "차단 또는 허용 경로 불일치: $entry"
            }
        }
    }

    fun requireActiveServices(activeServices: Set<String>) {
        require(activeServices.isNotEmpty() && addresses.keys.containsAll(activeServices)) { "미식별 live 집합" }
        require(activeServices.containsAll(addresses.keys - funds - "client")) { "매칭 노드 누락은 허용하지 않음" }
        require((funds intersect activeServices).isNotEmpty()) { "자금 live 노드가 없음" }
        require(activeServices.containsAll(scopes.keys)) { "scope client는 살아 있어야 함" }
    }

    companion object {
        internal fun requireCleanSnapshot(raw: String) {
            val parsed = parseSnapshot(raw)
            check(
                parsed.chains == mapOf("INPUT" to "ACCEPT", "FORWARD" to "ACCEPT", "OUTPUT" to "ACCEPT") &&
                    parsed.ruleCount == 0,
            ) { "namespace에 기존 firewall 규칙/정책이 있음" }
        }

        private fun parseSnapshot(raw: String): ParsedSnapshot {
            check(raw.toByteArray().size <= 65_536) { "규칙 관측 상한 초과" }
            val lines =
                raw
                    .lineSequence()
                    .map(String::trim)
                    .filter { it.isNotEmpty() && !it.startsWith('#') }
                    .toList()
            check(lines.firstOrNull() == "*filter" && lines.lastOrNull() == "COMMIT") { "불완전 filter 관측" }
            val chains = linkedMapOf<String, String>()
            val rules = linkedMapOf<String, Pair<Long, Long>>()
            var ruleCount = 0
            for (line in lines.drop(1).dropLast(1)) {
                val chain = Regex(":(\\S+) (\\S+) \\[\\d+:\\d+]").matchEntire(line)
                if (chain != null) {
                    check(chains.put(chain.groupValues[1], chain.groupValues[2]) == null) { "중복 chain" }
                    continue
                }
                val rule = checkNotNull(Regex("\\[(\\d+):(\\d+)] (-A .+)").matchEntire(line)) { "미식별 규칙: $line" }
                val packets = checkNotNull(rule.groupValues[1].toLongOrNull()) { "packet counter 범위 초과" }
                val bytes = checkNotNull(rule.groupValues[2].toLongOrNull()) { "byte counter 범위 초과" }
                rules[rule.groupValues[3]] = packets to bytes
                ruleCount++
            }
            return ParsedSnapshot(chains, rules, ruleCount)
        }
    }

    private data class ParsedSnapshot(
        val chains: Map<String, String>,
        val rules: Map<String, Pair<Long, Long>>,
        val ruleCount: Int,
    )
}

internal data class FundsQuorumNetworkEvidence(
    val runId: String,
    val generation: Int,
    val installedAtMillis: Long,
    val observedAtMillis: Long,
    val backend: Map<String, String>,
    val peerDropMode: String,
    val addresses: Map<String, String>,
    val activeServices: Set<String>,
    val excludedServices: Set<String>,
    val blockedPairs: Set<FundsPeerPair>,
    val scopes: Map<String, Set<String>>,
    val snapshots: Map<String, String>,
    val counters: List<FundsQuorumRuleCounter>,
    val reachability: List<FundsQuorumReachability>,
)

internal data class FundsQuorumNetworkRemoval(
    val service: String,
    val removedAtMillis: Long,
    val snapshot: String?,
    val error: String?,
)

/** Docker exec의 service 선택은 실행기가 소유하며, host firewall을 호출하는 경로는 없다. */
internal class FundsQuorumNetworkControl(
    private val runId: String,
    addresses: Map<String, String>,
    private val execute: (service: String, arguments: List<String>) -> String,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val addresses = addresses.toMap()
    private var blockedPairs = emptySet<FundsPeerPair>()
    private var scopes = emptyMap<String, Set<String>>()
    private val installed = linkedSetOf<String>()
    private val backends = linkedMapOf<String, String>()
    private val removalEvidence = mutableListOf<FundsQuorumNetworkRemoval>()
    private var generation = 0
    private var installedAtMillis = 0L
    private var closed = false

    init {
        plan() // 외부 명령을 실행하기 전에 주소/대상 경계를 확인한다.
    }

    fun partition(
        pairs: Set<FundsPeerPair>,
        activeServices: Set<String> = addresses.keys,
    ): FundsQuorumNetworkEvidence {
        check(!closed) { "이미 회수한 network control" }
        val next = FundsQuorumNetworkPlan(runId, addresses, pairs, scopes)
        next.requireActiveServices(activeServices)
        require(activeServices.containsAll(setOf("funds0", "funds1", "funds2"))) { "peer 분할은 자금 세 프로세스 생존이 필요함" }
        initialize(next, activeServices)
        apply(next, setOf("funds0", "funds1", "funds2"))
        blockedPairs = next.blockedPairs
        changed()
        return inspect(activeServices)
    }

    fun scope(
        clientService: String,
        allowedFunds: Set<String>,
        activeServices: Set<String> = addresses.keys,
    ): FundsQuorumNetworkEvidence {
        check(!closed) { "이미 회수한 network control" }
        val next = FundsQuorumNetworkPlan(runId, addresses, blockedPairs, scopes + (clientService to allowedFunds))
        next.requireActiveServices(activeServices)
        require(activeServices.containsAll(allowedFunds)) { "scope 허용 funds가 live 집합에 없음" }
        initialize(next, activeServices)
        apply(next, setOf(clientService))
        scopes = next.scopes
        changed()
        return inspect(activeServices)
    }

    fun releaseScope(
        clientService: String = "client",
        activeServices: Set<String> = addresses.keys,
    ): FundsQuorumNetworkEvidence {
        check(!closed && clientService in scopes) { "회수할 client scope가 없음" }
        remove(clientService)
        scopes = scopes - clientService
        changed()
        return inspect(activeServices)
    }

    fun rejoined(
        service: String,
        activeServices: Set<String> = addresses.keys,
    ): FundsQuorumNetworkEvidence {
        check(!closed && service in setOf("funds0", "funds1", "funds2") && service in activeServices)
        check(blockedPairs.isEmpty()) { "분할은 재시작으로 복구할 수 없음" }
        installed -= service
        backends -= service
        initialize(plan(), setOf(service))
        changed()
        return inspect(activeServices)
    }

    fun inspect(activeServices: Set<String> = addresses.keys): FundsQuorumNetworkEvidence {
        check(!closed) { "이미 회수한 network control" }
        val plan = plan()
        plan.requireActiveServices(activeServices)
        check(installed.containsAll(plan.services intersect activeServices)) { "먼저 partition 또는 scope를 설치해야 함" }
        plan.verifyInstalled(snapshots(plan, activeServices), activeServices)
        val reachability =
            plan.probePairs(activeServices).groupBy { it.first }.flatMap { (from, pairs) ->
                val script =
                    pairs.joinToString("\n") { (_, to) ->
                        val ip = addresses.getValue(to)
                        "if ping -n -c 1 -W 1 $ip >/dev/null 2>&1; then echo '$to|$ip|1'; else echo '$to|$ip|0'; fi"
                    }
                val output = execute(from, listOf("sh", "-eu", "-c", script))
                check(output.toByteArray().size <= 8192) { "$from: 도달 관측 상한 초과" }
                output
                    .lineSequence()
                    .filter(String::isNotBlank)
                    .map { line ->
                        val fields = line.trim().split('|')
                        check(fields.size == 3 && fields[2] in setOf("0", "1")) { "$from: 미식별 도달 관측 $line" }
                        FundsQuorumReachability(from, fields[0], fields[1], fields[2] == "1", nowMillis())
                    }.toList()
            }
        plan.verifyReachability(reachability, activeServices)
        val actual = snapshots(plan, activeServices)
        val counters = plan.verifyInstalled(actual, activeServices)
        plan.verifyDropCounters(counters, activeServices)
        return FundsQuorumNetworkEvidence(
            runId,
            generation,
            installedAtMillis,
            nowMillis(),
            backends.filterKeys { it in activeServices },
            PEER_DROP_MODE,
            addresses.toMap(),
            activeServices.toSet(),
            addresses.keys - activeServices,
            plan.blockedPairs,
            scopes.toMap(),
            actual,
            counters,
            reachability,
        )
    }

    fun removals(): List<FundsQuorumNetworkRemoval> = removalEvidence.toList()

    override fun close() {
        if (closed) return
        val failures = mutableListOf<Throwable>()
        for (service in installed.toList().asReversed()) {
            runCatching { remove(service) }.exceptionOrNull()?.let(failures::add)
        }
        closed = true
        if (failures.isNotEmpty()) {
            val failure = IllegalStateException("run 소유 firewall 회수 실패: ${failures.size}")
            failures.forEach(failure::addSuppressed)
            throw failure
        }
    }

    private fun plan() = FundsQuorumNetworkPlan(runId, addresses, blockedPairs, scopes)

    private fun initialize(
        plan: FundsQuorumNetworkPlan,
        activeServices: Set<String>,
    ) {
        for (service in (plan.services intersect activeServices).sorted() - installed) {
            val backend = execute(service, listOf(IPTABLES, "--version")).trim()
            check(backend == BACKEND) { "$service: 동결한 backend가 아님: $backend" }
            command(service, "-S")
            FundsQuorumNetworkPlan.requireCleanSnapshot(snapshot(service))
            // 중간 설치 실패도 finally에서 이 namespace의 run 전용 chain만 회수한다.
            installed += service
            backends[service] = backend
            execute(
                service,
                listOf(
                    "sh",
                    "-eu",
                    "-c",
                    listOf(
                        "$IPTABLES -w 2 -N ${plan.inputChain}",
                        "$IPTABLES -w 2 -N ${plan.outputChain}",
                        "$IPTABLES -w 2 -I INPUT 1 -j ${plan.inputChain}",
                        "$IPTABLES -w 2 -I OUTPUT 1 -j ${plan.outputChain}",
                    ).joinToString("\n"),
                ),
            )
        }
    }

    private fun apply(
        plan: FundsQuorumNetworkPlan,
        services: Set<String>,
    ) {
        val previous = this.plan()
        for (service in services.sorted()) {
            val changes = plan.changesFrom(previous, service)
            val commands =
                buildList {
                    for (rule in changes.additions) {
                        // IP 전체를 대상으로 하므로 consensus/log/Archive의 다른 포트도 우회하지 못한다.
                        add("$IPTABLES -w 2 ${rule.savedRule(plan)}")
                    }
                    for (rule in changes.removals) {
                        // 부분 회복 중 기존 구 리더의 격리를 잠깐 해제하는 chain flush를 하지 않는다.
                        add("$IPTABLES -w 2 ${rule.savedRule(plan).replaceFirst("-A ", "-D ")}")
                    }
                }
            if (commands.isNotEmpty()) execute(service, listOf("sh", "-eu", "-c", commands.joinToString("\n")))
        }
    }

    private fun remove(service: String) {
        val plan = plan()
        try {
            val script =
                listOf("INPUT" to plan.inputChain, "OUTPUT" to plan.outputChain).joinToString("\n") { (hook, chain) ->
                    "if $IPTABLES -w 2 -C $hook -j $chain 2>/dev/null; then $IPTABLES -w 2 -D $hook -j $chain; fi\n" +
                        "if $IPTABLES -w 2 -S $chain >/dev/null 2>&1; then $IPTABLES -w 2 -F $chain; $IPTABLES -w 2 -X $chain; fi"
                }
            execute(service, listOf("sh", "-eu", "-c", script))
            val after = snapshot(service)
            FundsQuorumNetworkPlan.requireCleanSnapshot(after)
            removalEvidence += FundsQuorumNetworkRemoval(service, nowMillis(), after, null)
            installed -= service
            backends -= service
        } catch (failure: Exception) {
            removalEvidence += FundsQuorumNetworkRemoval(service, nowMillis(), null, failure.message ?: failure.javaClass.name)
            throw failure
        }
    }

    private fun changed() {
        generation++
        installedAtMillis = nowMillis()
    }

    private fun snapshots(
        plan: FundsQuorumNetworkPlan,
        activeServices: Set<String>,
    ) = (plan.services intersect activeServices).sorted().associateWith(::snapshot)

    private fun snapshot(service: String) = execute(service, listOf("/usr/sbin/iptables-legacy-save", "-c", "-t", "filter"))

    private fun command(
        service: String,
        vararg arguments: String,
    ) = execute(service, listOf(IPTABLES, "-w", "2") + arguments)

    companion object {
        const val BACKEND = "iptables v1.8.8 (legacy)"
        const val PEER_DROP_MODE = "RECEIVER_INPUT_BOTH_ENDS"
        private const val IPTABLES = "/usr/sbin/iptables-legacy"
    }
}
