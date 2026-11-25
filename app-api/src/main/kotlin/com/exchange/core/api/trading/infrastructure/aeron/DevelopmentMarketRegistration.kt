package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.market.application.MarketConfigurationError
import com.exchange.core.api.market.application.MarketConfigurationException
import com.exchange.core.api.market.application.MarketConfigurationUseCase
import com.exchange.core.api.market.infrastructure.persistence.MarketConfigurationDatabaseSettings
import com.exchange.core.api.market.infrastructure.persistence.PostgresMarketConfigurationStore
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.system.exitProcess

/** 파일은 등록 입력이다. 등록 뒤 노드를 시작하거나 실행 중인 설정을 교체하지 않는다. */
fun main(args: Array<String>) {
    try {
        val (version, path) = registrationArguments(args)
        val settings = MarketConfigurationDatabaseSettings.from(System.getenv())
        val document = registrationDocument(path)
        val useCase = MarketConfigurationUseCase(PostgresMarketConfigurationStore(settings))
        val result = useCase.register(version, document)
        println("CONFIRMED configVersion=${result.configVersion} sha256=${result.sha256}")
        if (System.out.checkError()) {
            throw MarketConfigurationException(MarketConfigurationError.RESULT_UNCONFIRMED, "response", version)
        }
    } catch (error: MarketConfigurationException) {
        System.err.println(error.message)
        exitProcess(if (error.code == MarketConfigurationError.RESULT_UNCONFIRMED) 3 else 2)
    }
}

private fun registrationArguments(args: Array<String>): Pair<Long, Path> {
    if (args.size != 4) invalidRegistrationInput("arguments")
    val options = args.toList().chunked(2)
    if (options.map { it[0] }.toSet() != setOf("--version", "--document")) invalidRegistrationInput("arguments")
    val values = options.associate { it[0] to it[1] }
    val version = values.getValue("--version").toLongOrNull()?.takeIf { it > 0 } ?: invalidRegistrationInput("version")
    val path =
        try {
            Path.of(values.getValue("--document"))
        } catch (_: InvalidPathException) {
            invalidRegistrationInput("document")
        }
    return version to path
}

private fun registrationDocument(path: Path): ByteArray =
    try {
        if (!Files.isRegularFile(path)) invalidRegistrationInput("document")
        Files.newInputStream(path).use { it.readNBytes(MarketConfigurationUseCase.MAX_DOCUMENT_BYTES + 1) }
    } catch (_: IOException) {
        invalidRegistrationInput("document")
    }

private fun invalidRegistrationInput(stage: String): Nothing =
    throw MarketConfigurationException(MarketConfigurationError.INVALID_INPUT, stage)
