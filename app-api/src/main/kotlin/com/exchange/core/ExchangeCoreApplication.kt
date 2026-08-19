package com.exchange.core

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Exchange Core HTTP 애플리케이션의 Spring Boot 구성 진입점.
 *
 * 이 클래스가 있는 `com.exchange.core` 하위 패키지를 component scan하여 controller,
 * service, persistence configuration을 등록한다.
 */
@SpringBootApplication
class ExchangeCoreApplication

/**
 * Spring Boot 애플리케이션을 시작한다.
 *
 * @param args JVM 프로세스에서 전달된 command-line 인자
 */
fun main(args: Array<String>) {
    runApplication<ExchangeCoreApplication>(*args)
}
