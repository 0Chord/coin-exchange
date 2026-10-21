package com.exchange.core.api.trading.infrastructure.aeron

import io.aeron.exceptions.AeronException
import java.nio.file.Path

/** Docker 동시 전송 검증 전용 진입점. 주문 송수신은 일반 개발 클라이언트에 위임한다. */
fun main(args: Array<String>) {
    runConcurrentOrderClient(args)
}

internal fun runConcurrentOrderClient(
    args: Array<String>,
    environment: Map<String, String> = System.getenv(),
) {
    val (config, command, structured) = parseDevelopmentOrderInvocation(args, environment)
    require(structured) { "공통 출발 신호는 --cluster-env 개발 실행에서만 사용합니다" }
    val gate =
        DevelopmentOrderStartGate(
            Path.of(requireNotNull(environment["ORDER_START_GATE"]) { "ORDER_START_GATE가 필요합니다" }),
            requireNotNull(config.runId),
            requireNotNull(environment["ORDER_CLIENT_ID"]) { "ORDER_CLIENT_ID가 필요합니다" },
            config.startupTimeoutMs,
        )
    launchDevelopmentOrderDriver(config).use {
        val client =
            try {
                DevelopmentOrderClient(config)
            } catch (error: AeronException) {
                orderClientEvent(
                    config,
                    command,
                    "SETUP_FAILED",
                    mapOf("transportAccepted" to false, "reason" to "연결 문제: ${error.message}", "clientId" to gate.clientId),
                )
                throw IllegalStateException("공통 출발 전 연결이 실패해 주문을 보내지 않았습니다", error)
            }
        client.use {
            val identity =
                mapOf(
                    "clientId" to gate.clientId,
                    "sessionId" to it.connection.clusterSessionId(),
                    "pid" to ProcessHandle.current().pid(),
                    "advertisedHost" to config.advertisedHost,
                    "egressChannel" to config.udp(7),
                    "driverDirectory" to config.driverDirectory,
                    "runRoot" to config.runRoot.toString(),
                    "gatePath" to gate.path.toString(),
                    "gateTimeoutMs" to config.startupTimeoutMs,
                )
            try {
                gate.await(
                    pollConnection = it::pollConnection,
                    onReady = { orderClientEvent(config, command, "READY", identity) },
                    onGo = { orderClientEvent(config, command, "GO_OBSERVED", identity) },
                )
            } catch (error: Exception) {
                orderClientEvent(
                    config,
                    command,
                    "SETUP_FAILED",
                    identity + mapOf("reason" to error.message, "transportAccepted" to false),
                )
                throw IllegalStateException("공통 출발 전 준비가 실패해 주문을 보내지 않았습니다", error)
            }
            orderClientEvent(config, command, "SEND_STARTED", identity)
            when (val outcome = it.request(command) { orderClientEvent(config, command, "TRANSPORT_ACCEPTED", identity) }) {
                is OrderRequestOutcome.Confirmed -> {
                    orderClientEvent(
                        config,
                        command,
                        "RESULT_RECEIVED",
                        identity + mapOf("result" to admissionResultFields(outcome.result)),
                    )
                }

                is OrderRequestOutcome.Unconfirmed -> {
                    orderClientEvent(
                        config,
                        command,
                        "UNCONFIRMED",
                        identity + mapOf("transportAccepted" to outcome.transportAccepted, "reason" to outcome.reason),
                    )
                    throw IllegalStateException("미접수로 단정하지 마세요. 같은 사용자·요청 ID와 주문 원본으로 재확인해야 합니다")
                }
            }
        }
    }
}
