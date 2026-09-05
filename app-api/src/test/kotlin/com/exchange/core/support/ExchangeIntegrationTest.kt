package com.exchange.core.support

import com.exchange.core.ExchangeCoreApplication
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext

/**
 * 실제 주문·매칭·원장 서비스와 PostgreSQL을 사용하는 통합 테스트의 공통 실행 환경.
 *
 * 각 테스트 뒤 context를 닫아 DB뿐 아니라 메모리 주문장·중복 주문 ID·마켓 장애 상태도
 * 초기화한다. 테스트 트랜잭션의 롤백은 별도 마켓 스레드에 전파되지 않으므로 사용하지 않는다.
 * context와 컨테이너를 다시 만드는 비용이 있어 순수 도메인 단위 테스트에는 적용하지 않는다.
 */
@SpringBootTest(
    classes = [ExchangeCoreApplication::class],
    properties = [
        "exchange.matching.persistence.enabled=true",
        "exchange.ledger.persistence.enabled=true",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
    ],
)
@Import(ExchangeIntegrationTestConfiguration::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
abstract class ExchangeIntegrationTest
