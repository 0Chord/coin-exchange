package com.exchange.architecture.fixtures.httpboundary.web;

import com.exchange.architecture.fixtures.httpboundary.ExampleStore;
import com.exchange.architecture.fixtures.httpboundary.ExampleStoreImpl;
import org.springframework.web.bind.annotation.RestController;
import java.util.function.Supplier;

/** Kotlin이 별도 클래스로 바꾸는 경우와 구분해 JVM 메서드 참조 자체를 검증한다. */
@RestController
public class JavaReferenceController {
    public Runnable port(ExampleStore store) {
        return store::save;
    }

    public Runnable implementation(ExampleStoreImpl store) {
        return store::save;
    }

    public Supplier<ExampleStoreImpl> constructor() {
        return ExampleStoreImpl::new;
    }
}
