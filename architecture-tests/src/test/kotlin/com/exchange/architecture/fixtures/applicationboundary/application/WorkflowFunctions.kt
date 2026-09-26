package com.exchange.architecture.fixtures.applicationboundary.application

import com.exchange.architecture.fixtures.applicationboundary.PostgresFundsStore
import com.exchange.architecture.fixtures.applicationboundary.Reserved

fun Reserved.storeDirectly() {
    PostgresFundsStore().save(this)
}
