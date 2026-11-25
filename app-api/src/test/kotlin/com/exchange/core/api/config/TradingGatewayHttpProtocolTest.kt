package com.exchange.core.api.config

import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.apache.catalina.connector.Connector
import org.apache.catalina.startup.Tomcat
import org.apache.coyote.AbstractProtocol
import org.apache.coyote.Processor
import org.apache.coyote.RequestGroupInfo
import org.apache.coyote.http11.Http11NioProtocol
import org.apache.tomcat.util.modeler.NoDescriptorRegistry
import org.apache.tomcat.util.modeler.Registry
import org.apache.tomcat.util.net.NioChannel
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.management.MBeanServerFactory
import javax.management.ObjectName
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@Timeout(30)
class TradingGatewayHttpProtocolTest {
    @Test
    fun `비활성 JMX의 새 processor는 handler monitor를 기다리지 않고 기존 통계에 연결한다`() {
        runProbe("disabled-monitor")
    }

    @Test
    fun `실제 비동기 HTTP 성공과 오류의 통계는 processor 회수와 서버 종료 뒤에도 보존된다`() {
        runProbe("disabled-http")
    }

    @Test
    fun `활성 JMX는 native MBean 등록과 해제를 유지한다`() {
        runProbe("enabled")
    }

    @Test
    fun `보호된 Registry 조회 실패는 native 예외 처리와 부분 등록 통계 해제를 유지한다`() {
        runProbe("guarded")
    }

    @Test
    fun `생성자에서 Registry를 먼저 만들지 않고 domain 없는 processor와 upgrade 해제를 native처럼 처리한다`() {
        runProbe("disabled-lifecycle")
    }

    @Test
    fun `Gateway 기본 protocol은 제품 구현을 사용하고 명시한 protocol은 덮어쓰지 않는다`() {
        val customizer = TradingGatewayConfig().tradingHttpProtocolCustomizer()
        val default = TomcatServletWebServerFactory()
        assertEquals(Http11NioProtocol::class.java.name, default.protocol)
        customizer.customize(default)
        assertEquals(TradingGatewayHttpProtocol::class.java.name, default.protocol)
        customizer.customize(default)
        assertEquals(TradingGatewayHttpProtocol::class.java.name, default.protocol)

        val custom = TomcatServletWebServerFactory().apply { setProtocol("explicit.application.Protocol") }
        customizer.customize(custom)
        assertEquals("explicit.application.Protocol", custom.protocol)
    }

    private fun runProbe(mode: String) {
        val output = Files.createTempFile("gateway-http-protocol-", ".log")
        val process =
            ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-XX:ActiveProcessorCount=2",
                "-cp",
                checkNotNull(System.getProperty("marketConfigurationHttpTestClasspath")),
                TradingGatewayHttpProtocolProbe::class.java.name,
                mode,
            ).redirectErrorStream(true).redirectOutput(output.toFile()).start()
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "isolated Tomcat probe timed out: $mode")
            assertEquals(0, process.exitValue(), Files.readString(output))
        } finally {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
            Files.deleteIfExists(output)
        }
    }
}

/** Registry는 JVM 싱글턴이므로 각 사례는 새 프로세스에서 실제 레지스트리를 선택한다. */
object TradingGatewayHttpProtocolProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        when (args.single()) {
            "disabled-monitor" -> disabledMonitor()
            "disabled-http" -> disabledHttp()
            "enabled" -> enabledRegistry()
            "guarded" -> guardedRegistry()
            "disabled-lifecycle" -> disabledLifecycle()
            else -> error("Unknown probe")
        }
    }

    private fun registeredProtocol(): ProbeProtocol =
        ProbeProtocol().also {
            it.preRegister(Registry.getRegistry(null).mBeanServer, ObjectName("gatewayProtocolProbe:type=ProtocolHandler"))
        }

    private fun disabledMonitor() {
        Registry.disableRegistry()
        val registry = Registry.getRegistry(null)
        assertIs<NoDescriptorRegistry>(registry)
        val protocol = registeredProtocol()
        val processor = protocol.newProcessor()
        val handler = protocol.connectionHandler
        val started = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val completed =
                synchronized(handler) {
                    val future =
                        executor.submit {
                            started.countDown()
                            handler.attach(processor)
                            returned.countDown()
                        }
                    assertTrue(started.await(5, TimeUnit.SECONDS))
                    assertTrue(returned.await(1, TimeUnit.SECONDS), "disabled JMX registration waited for handler monitor")
                    future
                }
            completed.get(5, TimeUnit.SECONDS)
            val requestInfo = processor.request.requestProcessor
            assertSame(handler.global, requestInfo.globalProcessor)
            assertNull(requestInfo.rpName, "disabled JMX must not construct unused MBean names")
            requestInfo.requestCount = 2
            requestInfo.errorCount = 1
            requestInfo.bytesReceived = 11
            requestInfo.bytesSent = 17
            requestInfo.processingTime = 9
            requestInfo.maxTime = 7
            val global = handler.global as RequestGroupInfo
            val totals = counters(global)
            handler.detach(processor)
            assertNull(requestInfo.globalProcessor)
            assertEquals(totals, counters(global), "unregister must transfer live processor totals")
            handler.detach(processor)
            assertEquals(totals, counters(global), "a repeated unregister must not count totals twice")
            global.resetCounters()
            assertEquals(List(6) { 0L }, counters(global))
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun disabledLifecycle() {
        // 제품 코드와 마찬가지로 프레임워크가 레지스트리를 선택하기 전에 생성한다.
        val protocol = ProbeProtocol()
        Registry.disableRegistry()
        assertEquals<Class<*>>(NoDescriptorRegistry::class.java, Registry.getRegistry(null).javaClass)
        val processor = protocol.newProcessor()
        val info = processor.request.requestProcessor
        protocol.connectionHandler.attach(processor)
        assertNull(info.globalProcessor, "the native null-domain gate must remain in place")
        protocol.connectionHandler.detach(processor)
        assertNull(info.rpName)

        protocol.preRegister(Registry.getRegistry(null).mBeanServer, ObjectName("gatewayProtocolProbe:type=ProtocolHandler"))
        val upgrade =
            Proxy.newProxyInstance(Processor::class.java.classLoader, arrayOf(Processor::class.java)) { _, method, _ ->
                check(method.name == "getRequest") { "Unexpected upgrade access: ${method.name}" }
                null
            } as Processor
        protocol.connectionHandler.detach(upgrade)
        assertEquals(List(6) { 0L }, counters(protocol.connectionHandler.global as RequestGroupInfo))

        protocol.connectionHandler.attach(processor)
        info.rpName = ObjectName("gatewayProtocolProbe:type=RequestProcessor,name=named")
        protocol.connectionHandler.detach(processor)
        assertNull(info.rpName, "named registrations must retain native unregister cleanup")
        assertNull(info.globalProcessor)
    }

    private fun enabledRegistry() {
        val registry = Registry.getRegistry(null)
        assertFalse(registry is NoDescriptorRegistry)
        val protocol = registeredProtocol()
        val processor = protocol.newProcessor()
        val info = processor.request.requestProcessor
        protocol.connectionHandler.attach(processor)
        try {
            val name = assertNotNull(info.rpName)
            assertTrue(registry.mBeanServer.isRegistered(name), "enabled JMX must use actual native registration")
            assertSame(protocol.connectionHandler.global, info.globalProcessor)
            info.requestCount = 3
            info.errorCount = 1
            info.bytesReceived = 19
            info.bytesSent = 23
            val global = protocol.connectionHandler.global as RequestGroupInfo
            val totals = counters(global)
            protocol.connectionHandler.detach(processor)
            assertFalse(registry.mBeanServer.isRegistered(name))
            assertNull(info.rpName)
            assertNull(info.globalProcessor)
            assertEquals(totals, counters(global))
        } finally {
            protocol.connectionHandler.detach(processor)
        }
    }

    private fun guardedRegistry() {
        val guard = Any()
        val registry = Registry.getRegistry(guard)
        assertFalse(registry is NoDescriptorRegistry)
        val protocol = ProbeProtocol()
        protocol.preRegister(MBeanServerFactory.newMBeanServer(), ObjectName("gatewayProtocolProbe:type=ProtocolHandler"))
        val processor = protocol.newProcessor()
        val info = processor.request.requestProcessor
        // 네이티브 register는 보호된 Registry 접근이 실패하기 전에 전역 통계를 연결한다.
        // 자체 catch가 이 실패를 처리해 연결이 닫히지 않아야 한다.
        protocol.connectionHandler.attach(processor)
        assertSame(protocol.connectionHandler.global, info.globalProcessor)
        assertNull(info.rpName)
        info.requestCount = 3
        info.errorCount = 1
        info.bytesReceived = 19
        info.bytesSent = 23
        info.processingTime = 11
        info.maxTime = 7
        val global = protocol.connectionHandler.global as RequestGroupInfo
        val totals = counters(global)
        protocol.connectionHandler.detach(processor)
        assertNull(info.globalProcessor)
        assertEquals(totals, counters(global))
        protocol.connectionHandler.detach(processor)
        assertEquals(totals, counters(global))
        assertSame(registry, Registry.getRegistry(guard))
    }

    private fun disabledHttp() {
        Registry.disableRegistry()
        assertEquals<Class<*>>(NoDescriptorRegistry::class.java, Registry.getRegistry(null).javaClass)
        val base = Files.createTempDirectory("gateway-protocol-http-")
        val tomcat = Tomcat().apply { setBaseDir(base.toString()) }
        val protocol = CountingProtocol()
        val connector = Connector(protocol).apply { port = 0 }
        protocol.maxThreads = 4
        protocol.minSpareThreads = 4
        protocol.processorCache = 8
        tomcat.connector = connector
        val context = tomcat.addContext("", base.toString())
        val callbacks = Executors.newSingleThreadExecutor()
        val servlet =
            object : HttpServlet() {
                override fun doPost(
                    request: HttpServletRequest,
                    response: HttpServletResponse,
                ) {
                    val input = request.inputStream.readAllBytes()
                    val error = request.requestURI.endsWith("/error")
                    val async = request.startAsync()
                    callbacks.execute {
                        try {
                            response.status = if (error) 503 else 202
                            val body = "echo:".toByteArray(StandardCharsets.UTF_8) + input
                            response.setContentLength(body.size)
                            response.outputStream.write(body)
                        } finally {
                            async.complete()
                        }
                    }
                }
            }
        Tomcat.addServlet(context, "asyncProbe", servlet).setAsyncSupported(true)
        context.addServletMappingDecoded("/probe/*", "asyncProbe")
        var started = false
        try {
            tomcat.start()
            started = true
            val global = protocol.handler.global as RequestGroupInfo
            val success = post(connector.localPort, "/probe/ok", "alpha")
            val failure = post(connector.localPort, "/probe/error", "beta")
            assertTrue(success.startsWith("HTTP/1.1 202 "), success)
            assertTrue(failure.startsWith("HTTP/1.1 503 "), failure)
            assertTrue(success.endsWith("\r\n\r\necho:alpha"), success)
            assertTrue(failure.endsWith("\r\n\r\necho:beta"), failure)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (global.requestCount != 2 && System.nanoTime() < deadline) Thread.sleep(1)
            assertEquals(2, global.requestCount)
            assertEquals(1, global.errorCount)
            assertEquals(9L, global.bytesReceived)
            assertEquals(19L, global.bytesSent)
            assertEquals(1, protocol.createdProcessors.get(), "both async HTTP requests must reuse one cached processor")
            val totals = counters(global)
            tomcat.stop()
            started = false
            assertEquals(totals, counters(global), "native shutdown must preserve totals after cache processors detach")
            global.resetCounters()
            assertEquals(List(6) { 0L }, counters(global))
        } finally {
            try {
                if (started) tomcat.stop()
                tomcat.destroy()
            } finally {
                callbacks.shutdownNow()
                assertTrue(callbacks.awaitTermination(5, TimeUnit.SECONDS))
                Files.walk(base).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
            }
        }
    }

    private fun post(
        port: Int,
        path: String,
        body: String,
    ): String =
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            socket.soTimeout = 5000
            val request =
                "POST $path HTTP/1.1\r\nHost: localhost\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
            socket.getOutputStream().write(request.toByteArray(StandardCharsets.US_ASCII))
            socket.getOutputStream().flush()
            String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII)
        }

    private fun counters(global: RequestGroupInfo): List<Long> =
        listOf(
            global.requestCount.toLong(),
            global.errorCount.toLong(),
            global.bytesReceived,
            global.bytesSent,
            global.processingTime,
            global.maxTime,
        )

    private class CountingProtocol : TradingGatewayHttpProtocol() {
        val createdProcessors = AtomicInteger()

        override fun createProcessor(): Processor {
            createdProcessors.incrementAndGet()
            return super.createProcessor()
        }
    }

    private class ProbeProtocol : TradingGatewayHttpProtocol() {
        val connectionHandler = ProbeHandler(this)

        init {
            endpoint.handler = connectionHandler
            handler = connectionHandler
        }

        fun newProcessor(): Processor = createProcessor()

        class ProbeHandler(
            protocol: AbstractProtocol<NioChannel>,
        ) : GatewayConnectionHandler(protocol) {
            fun attach(processor: Processor) = register(processor)

            fun detach(processor: Processor) = unregister(processor)
        }
    }
}
