package com.exchange.core

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
	fromApplication<ExchangeCoreApplication>().with(TestcontainersConfiguration::class).run(*args)
}
