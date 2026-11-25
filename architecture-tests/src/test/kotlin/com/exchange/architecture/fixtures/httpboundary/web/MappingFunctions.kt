package com.exchange.architecture.fixtures.httpboundary.web

import com.exchange.architecture.fixtures.httpboundary.ExampleStoreImpl

/** 저장 호출을 변환 함수에 숨긴 고의 위반 예제. 이 함수 자체를 실행하지 않는다. */
fun InputDto.toStored(): Long {
    ExampleStoreImpl().save()
    return amount
}
