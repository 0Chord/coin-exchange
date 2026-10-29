package com.exchange.core.api.config

import org.apache.coyote.AbstractProtocol
import org.apache.coyote.Processor
import org.apache.coyote.RequestGroupInfo
import org.apache.coyote.http11.Http11NioProtocol
import org.apache.tomcat.util.modeler.NoDescriptorRegistry
import org.apache.tomcat.util.modeler.Registry
import org.apache.tomcat.util.net.NioChannel
import org.apache.tomcat.util.net.NioEndpoint

/** Shares the native HTTP parser, executor and processor lifecycle with the diagnostic protocol. */
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
        // Boot selects the JVM registry before starting the connector. Looking it up in this
        // constructor would create an enabled registry before Boot can disable it.
        private val registryDisabled by lazy(LazyThreadSafetyMode.PUBLICATION) {
            try {
                Registry.getRegistry(null).javaClass == NoDescriptorRegistry::class.java
            } catch (_: Exception) {
                // The native registration path owns Registry failures and its partial cleanup.
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
            // Tomcat owns each newly-created processor here. RequestGroupInfo synchronizes its
            // own membership; the handler monitor and MBean name protect only disabled JMX work.
            request.requestProcessor.globalProcessor = global as RequestGroupInfo
        }

        override fun unregister(processor: Processor) {
            if (protocol.domain == null || !registryDisabled) {
                super.unregister(processor)
                return
            }
            val request = processor.request ?: return // Native upgrade processors have no request.
            val requestInfo = request.requestProcessor
            if (requestInfo.rpName != null) {
                // A named processor must retain Tomcat's MBean cleanup path.
                super.unregister(processor)
                return
            }
            // Transfers completed counters into RequestGroupInfo's dead-processor totals.
            requestInfo.globalProcessor = null
        }
    }
}
