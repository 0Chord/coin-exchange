package com.exchange.core.api.trading.infrastructure.aeron

import org.agrona.concurrent.BackoffIdleStrategy

/** 공유 CPU에서 짧은 공백에는 반응하되, 유휴 상태에서는 CPU를 양보한다. */
internal object TradingAeronRuntime {
    // 느린 follower가 GC 뒤 따라잡을 로그 여유를 둔다. publication window는 별도로
    // 제한해 leader의 Archive·서비스 소비보다 로그가 과도하게 앞서 쌓이지 않게 한다.
    const val TERM_LENGTH_BYTES = 16 * 1024 * 1024
    const val LOG_CHANNEL = "aeron:udp?term-length=16m|pub-wnd=1m"

    // 외부 요청까지 큰 대기열을 만들지 않도록 ingress term은 기존 크기를 유지한다.
    const val UDP_CHANNEL = "aeron:udp?term-length=1m"

    // 접수마다 내부 실행 입력을 생성한다. 외부 입력과 내부 append를 함께
    // 묶어 처리하되 내부 append 한도(주기당 20건) 안에서 진행한다.
    const val INGRESS_FRAGMENT_LIMIT = 10

    // Docker Linux VM의 실제 Java socket 상한 212,992 B 안에서 사용할 수 있는 값이다.
    // 커널 상한을 바꾸거나 요청한 buffer가 전부 적용됐다고 가정하지 않는다.
    const val SOCKET_BUFFER_BYTES = 192 * 1024
    const val RECEIVER_WINDOW_BYTES = 192 * 1024

    // Agrona 기본값은 최대 1 ms를 park한다. 여러 agent를 지나는 주문의 대기를 줄이기 위해
    // 100 us로 제한한다. spin/yield를 무한 반복하지 않으며 agent마다 상태를 따로 소유한다.
    fun idleStrategy(): BackoffIdleStrategy = BackoffIdleStrategy(10, 5, 1_000, 100_000)
}
