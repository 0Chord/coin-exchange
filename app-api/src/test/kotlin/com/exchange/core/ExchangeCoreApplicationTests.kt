package com.exchange.core

import com.exchange.core.api.order.OrderSubmissionService
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean

/**
 * DB 자동 구성을 제외한 기본 Spring context가 로드되는지 확인한다.
 * 실제 주문 서비스 대신 테스트 대역을 사용하므로 DB 연결과 정산 검증은 주문 E2E가 담당한다.
 */
@SpringBootTest(
    properties = [
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
    ],
)
class ExchangeCoreApplicationTests {
    // DB 없는 context 검사에서는 주문 접수의 필수 의존성을 테스트 대역으로 제공한다.
    @MockitoBean
    private lateinit var orderSubmissionService: OrderSubmissionService

    @Test
    fun contextLoads() {
    }
}
