package com.exchange.core.api.config

import org.apache.coyote.AbstractProtocol
import org.apache.coyote.Processor
import org.apache.coyote.RequestGroupInfo
import org.apache.coyote.http11.Http11NioProtocol
import org.apache.tomcat.util.modeler.NoDescriptorRegistry
import org.apache.tomcat.util.modeler.Registry
import org.apache.tomcat.util.net.NioChannel
import org.apache.tomcat.util.net.NioEndpoint

/** 진단 프로토콜과 기본 HTTP 파서·실행자·프로세서 생명주기를 공유한다. */
open class TradingGatewayHttpProtocol protected constructor(
    endpoint: NioEndpoint,
) : Http11NioProtocol(endpoint) {
    constructor() : this(NioEndpoint())

    init {
        val connectionHandler = GatewayConnectionHandler(this)
        endpoint.handler = connectionHandler
        handler = connectionHandler
    }

    protected open class GatewayConnectionHandler(
        protocol: AbstractProtocol<NioChannel>,
    ) : ConnectionHandler<NioChannel>(protocol) {
        // Boot는 커넥터를 시작하기 전에 JVM의 registry를 선택한다. 이 생성자에서 조회하면
        // Boot가 비활성화하기 전에 활성화된 registry가 먼저 만들어진다.
        private val registryDisabled by lazy(LazyThreadSafetyMode.PUBLICATION) {
            try {
                Registry.getRegistry(null).javaClass == NoDescriptorRegistry::class.java
            } catch (_: Exception) {
                // Registry 실패와 그때까지 등록한 자원의 정리는 기본 등록 경로에서 처리한다.
                false
            }
        }

        override fun register(processor: Processor) {
            if (protocol.domain == null || !registryDisabled) {
                super.register(processor)
                return
            }
            val request = processor.request
            if (request == null) {
                super.register(processor)
                return
            }
            // 여기서 새로 만든 각 프로세서는 Tomcat이 소유한다. 구성원 동기화는 RequestGroupInfo가
            // 직접 처리하며, 핸들러 모니터와 MBean 이름은 비활성화된 JMX 작업만 보호한다.
            request.requestProcessor.globalProcessor = global as RequestGroupInfo
        }

        override fun unregister(processor: Processor) {
            if (protocol.domain == null || !registryDisabled) {
                super.unregister(processor)
                return
            }
            val request = processor.request ?: return // 기본 upgrade 프로세서에는 request가 없다.
            val requestInfo = request.requestProcessor
            if (requestInfo.rpName != null) {
                // 이름이 있는 프로세서는 Tomcat의 MBean 정리 경로를 유지해야 한다.
                super.unregister(processor)
                return
            }
            // 종료된 프로세서의 카운터를 RequestGroupInfo의 종료 프로세서 누계에 합산한다.
            requestInfo.globalProcessor = null
        }
    }
}
