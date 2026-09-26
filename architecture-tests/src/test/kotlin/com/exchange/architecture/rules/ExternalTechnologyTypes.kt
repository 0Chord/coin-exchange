package com.exchange.architecture.rules

/** ARCH-01의 기존 기술 판정 범위. 다른 규칙은 필요한 정책을 별도로 더한다. */
object ExternalTechnologyTypes {
    // URI 같은 값 타입까지 막지 않도록 외부 기술과 네트워크 I/O 타입을 명시한다.
    private val technologyPrefixes = listOf(
        "org.springframework.", "jakarta.persistence.", "javax.persistence.",
        "java.sql.", "javax.sql.", "java.net.http.", "javax.net.",
        "org.apache.kafka.clients.", "org.postgresql.", "org.hibernate.",
    )
    private val networkIoTypes = setOf(
        "java.net.Socket", "java.net.ServerSocket", "java.net.DatagramSocket", "java.net.MulticastSocket",
        "java.net.URL", "java.net.URLConnection", "java.net.HttpURLConnection", "java.net.JarURLConnection",
        "java.net.InetAddress", "java.net.Inet4Address", "java.net.Inet6Address",
        "java.nio.channels.SocketChannel", "java.nio.channels.ServerSocketChannel", "java.nio.channels.DatagramChannel",
    )

    fun contains(name: String): Boolean = technologyPrefixes.any { name.startsWith(it) } || name in networkIoTypes
}
