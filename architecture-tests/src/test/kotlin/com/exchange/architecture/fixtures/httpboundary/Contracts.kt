package com.exchange.architecture.fixtures.httpboundary

/** 실제 업무 객체가 아니라 ARCH-03에 넣을 최소 입력·결과 예제다. */
data class HttpAmount(val value: Long)
data class HttpCommand(val amount: HttpAmount)
data class HttpEvent(val amount: HttpAmount)

interface SubmitEntry {
    fun submit(command: HttpCommand): HttpEvent
}

class SubmissionService {
    fun submit(command: HttpCommand) = HttpEvent(command.amount)
}

interface ExampleStore {
    fun save()
}

class ExampleStoreImpl : ExampleStore {
    override fun save() = Unit
}

class FundingTaskUseCase
class SettlementTask
class MatchingCoordinator
class ExampleEngine
class ExampleExecutor
class ExampleBook
interface ExampleRepository
class StoredEntity
class ExampleConfiguration

class WorkflowEntry(private val store: ExampleStore) {
    fun submit() = store.save()
}

@org.springframework.web.bind.annotation.RestController
class OutsidePackageController
