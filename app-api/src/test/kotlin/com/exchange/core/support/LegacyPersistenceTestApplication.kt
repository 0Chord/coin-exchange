package com.exchange.core.support

import org.springframework.boot.autoconfigure.AutoConfigurationPackage
import org.springframework.boot.test.context.TestComponent
import org.springframework.context.annotation.Configuration

/** 이전 JPA·JDBC 저장소 검증은 제품 HTTP 조립을 가져오지 않는다. */
@TestComponent
@Configuration(proxyBeanMethods = false)
@AutoConfigurationPackage(basePackages = ["com.exchange.core"])
class LegacyPersistenceTestApplication
