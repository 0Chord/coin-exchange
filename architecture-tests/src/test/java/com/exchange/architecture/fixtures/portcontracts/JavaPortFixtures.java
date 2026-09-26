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
    // 바깥 타입의 인자도 공개 반환/인자/필드/상한에 남아 있는 계약이다.
    public static class Owner<T> { public class Member<U> {} }
    public interface OwnerReturnPort { Owner<Connection>.Member<String> load(); }
    public interface OwnerArgumentPort { void save(List<? extends Owner<Connection>.Member<String>[]> value); }
    public interface OwnerFieldPort { Owner<Connection>.Member<String> VALUE = null; }
    public interface OwnerBoundPort<T extends Owner<Connection>.Member<String>> { T load(); }
    public interface OwnerMethodBoundPort { <T extends Owner<Connection>.Member<String>> T load(); }
    public interface SafeOwnerPort { Owner<String>.Member<Integer> load(); }
    public interface ExternalNestedPort extends com.exchange.architecture.fixtures.externalports.ExternalPortContracts.Parent { String load(); }
    public interface SafeExternalNestedPort extends com.exchange.architecture.fixtures.externalports.ExternalPortContracts.SafeParent { String load(); }
}
