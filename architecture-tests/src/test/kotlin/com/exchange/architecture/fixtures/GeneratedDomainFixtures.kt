package com.exchange.architecture.fixtures

import java.net.URI
import java.net.Socket
import java.net.http.HttpClient

class PortTypedDomain(val port: DomainBalancePort)
class SocketDomain(val socket: Socket)
class UriValueDomain(val uri: URI)

class LambdaHttpDomain {
    fun supplier(): () -> HttpClient = { HttpClient.newHttpClient() }
}

fun fixtureHttpClient(): HttpClient = HttpClient.newHttpClient()
