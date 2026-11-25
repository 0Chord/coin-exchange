package com.exchange.architecture.fixtures.externalports;

/** 운영 입력 밖의 라이브러리를 흉내 낸다. 테스트는 자식만 명시적으로 수집한다. */
public final class ExternalPortContracts {
    public interface Parent {
        interface Exposed { java.sql.Connection load(); }
        interface Safe { String load(); }
    }
    public interface SafeParent {
        interface Exposed { String load(); }
    }
}
