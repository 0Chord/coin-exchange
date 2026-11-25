package com.exchange.architecture.fixtures.applicationboundary.application;

import com.exchange.architecture.fixtures.applicationboundary.FundsPort;
import com.exchange.architecture.fixtures.applicationboundary.PostgresFundsStore;
import com.exchange.architecture.fixtures.applicationboundary.Reserved;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** JVM 메서드·생성자 참조의 실제 위치를 읽기 위한 예제다. 저장 메서드는 실행하지 않는다. */
public class JavaReferences {
    public Consumer<Reserved> port(FundsPort port) {
        return port::save;
    }
    public Consumer<Reserved> concrete(PostgresFundsStore store) {
        return store::save;
    }
    public Supplier<PostgresFundsStore> constructor() {
        return PostgresFundsStore::new;
    }
}
