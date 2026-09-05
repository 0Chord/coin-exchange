package com.exchange.core

import com.exchange.core.api.order.OrderCancellationService
import com.exchange.core.api.order.OrderSubmissionService
import com.exchange.core.support.ExchangeIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * 테스트용 마켓·수수료 정책과 실제 PostgreSQL로 전체 Spring context가 로드되는지 확인한다.
 * 주문 서비스를 대역으로 교체하지 않으므로 실제 예약·반환·정산 의존성 연결도 필요하다.
 * 운영 마켓 설정이나 배포 환경 자체를 검증하는 테스트는 아니다.
 */
class ExchangeCoreApplicationTests : ExchangeIntegrationTest() {
    @Autowired
    private lateinit var orderSubmissionService: OrderSubmissionService

    @Autowired
    private lateinit var orderCancellationService: OrderCancellationService

    @Autowired
    private lateinit var dataSource: DataSource

    @Test
    fun contextLoads() {
        assertNotNull(orderSubmissionService)
        assertNotNull(orderCancellationService)
        dataSource.connection.use { connection ->
            assertEquals("PostgreSQL", connection.metaData.databaseProductName)
        }
    }
}
