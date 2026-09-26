package com.exchange.architecture.fixtures.portcontracts;

import java.io.Serializable;
import java.net.http.HttpClient;
import java.sql.Connection;
import java.util.List;

/** Kotlin에서 직접 만들기 어려운 와일드카드와 다중 상한의 컴파일 결과를 검사한다. */
public final class JavaPortFixtures {
    public interface FieldPort { HttpClient CLIENT = null; }
    public interface UpperPort { List<? extends Connection> read(); }
    public interface LowerPort { List<? super Connection> read(); }
    public interface BoundPort<T extends Serializable & Connection> { T read(); }
    public interface RecursivePort<T extends Comparable<T>> { T read(); }
    public interface GenericArrayPort<T extends Connection> { T[] read(); }
    public interface MissingExternalParentPort extends java.util.function.Supplier<Connection> {}
    public interface RawPort { List read(); }
    public interface DiamondLeft extends ConnectionContract {}
    public interface DiamondRight extends ConnectionContract {}
    public interface ConnectionContract { Connection read(); }
    public interface DiamondPort extends DiamondLeft, DiamondRight {}
    public interface BridgeParent<T> { T map(Connection connection); }
    public interface BridgePort extends BridgeParent<String> {
        @Override public default String map(Connection connection) { return "fixture"; }
    }
    public interface ResolvedExternalPort extends java.util.function.Supplier<String> {}
}
