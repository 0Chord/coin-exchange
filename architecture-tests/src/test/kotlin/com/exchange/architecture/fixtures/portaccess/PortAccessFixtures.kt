package com.exchange.architecture.fixtures.portaccess

import com.exchange.architecture.fixtures.DomainBalance
import com.exchange.architecture.fixtures.DomainBalancePort

/** 포트 구현 관계를 읽기 위한 예제다. 실제 저장이나 DB 연결은 하지 않는다. */
class BalancePortImplementation : DomainBalancePort {
    override fun save(balance: DomainBalance) = Unit
}

class ImplementationCallingDomain {
    fun persist(port: BalancePortImplementation, balance: DomainBalance) = port.save(balance)
}

class PortReferencingDomain {
    fun saveAction(port: DomainBalancePort): (DomainBalance) -> Unit = port::save
}

class ImplementationReferencingDomain {
    fun saveAction(port: BalancePortImplementation): (DomainBalance) -> Unit = port::save
}
