package com.exchange.architecture

import com.exchange.architecture.fixtures.portcontracts.*
import com.exchange.architecture.rules.PortContractIndependence
import com.exchange.architecture.rules.PortInspection
import com.exchange.architecture.support.*
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.ArchConfiguration
import com.tngtech.archunit.base.DescribedPredicate
import kotlin.test.*

class PortContractRuleTest {
    @Test
    fun `PORT-01 도메인 값과 일반 컨테이너를 주고받으면 통과한다`() {
        val result = inspect(ValuePort::class.java)
        assertTrue(result.evaluated, result.problems.toString())
        assertEquals(setOf(ValuePort::class.java.name), result.ports)
        assertTrue(result.contractCount >= 4)
        assertEquals(emptyList(), result.violations)
    }

    @Test
    fun `PORT-02 JDBC 반환과 인자 및 Spring 반환을 각각 정확히 보고한다`() {
        val cases = listOf(
            Triple(ConnectionResultPort::class.java, "return", "java.sql.Connection"),
            Triple(ConnectionArgumentPort::class.java, "parameter[0]", "java.sql.Connection"),
            Triple(SpringResultPort::class.java, "return", "org.springframework.core.env.Environment"),
        )
        cases.forEach { (port, exposure, target) ->
            val classes = ClassFileImporter().importClasses(port)
            assertTrue(classes.get(port).directDependenciesFromSelf.any { it.targetClass.name == target }, "예제 전제: $target")
            assertSingleViolation(inspect(port), port, exposure, target, "TECHNOLOGY")
        }
    }

    @Test
    fun `PORT-03 명시한 비 JPA 영속 모델을 반환하면 위반이다`() {
        assertSingleViolation(inspect(ManualRowPort::class.java, setOf(ManualRow::class.java.name)),
            ManualRowPort::class.java, "return", ManualRow::class.java.name, "PERSISTENCE")
    }

    @Test
    fun `PORT-04 등록 목록 밖의 JPA 표식도 발견한다`() {
        listOf(JpaRowPort::class.java to JpaRow::class.java,
            EmbeddedRowPort::class.java to EmbeddedRow::class.java,
            BaseRowPort::class.java to BaseRow::class.java).forEach { (port, row) ->
            val type = ClassFileImporter().importClasses(row).get(row)
            assertTrue(type.annotations.any { it.rawType.name.startsWith("jakarta.persistence.") })
            assertSingleViolation(inspect(port), port, "return", row.name, "PERSISTENCE")
        }
    }

    @Test
    fun `PORT-10 구현체의 JDBC 사용은 정상 포트의 계약 위반이 아니다`() {
        val result = inspect(ValuePort::class.java)
        val adapter = fixtureScope().classesByModule.getValue("fixture-module").get(JdbcValueAdapter::class.java)
        assertTrue(adapter.methodCallsFromSelf.any { it.target.owner.name == "java.sql.Connection" })
        assertTrue(result.evaluated)
        assertEquals(emptyList(), result.violations)
    }

    @Test
    fun `PORT-12 비어 있거나 사라진 포트와 영속 모델은 미평가한다`() {
        val scope = fixtureScope()
        listOf(
            PortContractIndependence.inspect(scope, emptySet()) to "EMPTY_PORTS",
            PortContractIndependence.inspect(scope, setOf("missing.Port")) to "MISSING_PORT",
            inspect(ValuePort::class.java, setOf("missing.Row")) to "MISSING_PERSISTENCE_TYPE",
            inspect(NotAnInterface::class.java) to "INVALID_PORT_TYPE",
            inspect(EmptyPort::class.java) to "EMPTY_PORT_CONTRACT",
        ).forEach { (result, code) ->
            assertFalse(result.evaluated)
            assertTrue(result.problems.any { it.code == code }, result.problems.toString())
            assertEquals(emptyList(), result.violations)
        }
    }

    @Test
    fun `PORT-13 기존 수집 문제가 있으면 일부 포트로 통과하지 않는다`() {
        val partial = fixtureScope().copy(problems = listOf(ScopeProblem(ScopeProblemCode.INCOMPLETE_IMPORT, "lost.Type")))
        val result = PortContractIndependence.inspect(partial, setOf(ValuePort::class.java.name))
        assertFalse(result.evaluated)
        assertTrue(result.problems.any { it.code == "INCOMPLETE_IMPORT" && it.subject.contains("lost.Type") })
        assertEquals(emptyList(), result.violations)
    }

    @Test
    fun `PORT-02 공개 필드의 HTTP 기술 타입을 보고한다`() {
        val result = inspect(JavaPortFixtures.FieldPort::class.java)
        assertTrue(result.evaluated, result.problems.toString())
        assertEquals(listOf("field" to "java.net.http.HttpClient"), result.violations.map { it.exposure to it.targetType })
    }

    @Test
    fun `PORT-05 목록 배열 비동기 컨테이너 안의 기술과 영속 타입도 검사한다`() {
        listOf(NestedListPort::class.java to JpaRow::class.java.name,
            ArrayPort::class.java to "java.sql.Connection", FutureRowPort::class.java to JpaRow::class.java.name).forEach { (port, target) ->
            val method = fixtureScope().classesByModule.getValue("fixture-module").get(port).methods.single()
            assertTrue(method.returnType.allInvolvedRawTypes.any { it.baseComponentType.name == target })
            assertSingleViolation(inspect(port), port, "return", target, if (target == "java.sql.Connection") "TECHNOLOGY" else "PERSISTENCE")
        }
    }

    @Test
    fun `PORT-06 Kotlin 프로퍼티의 getter와 setter 노출을 모두 보고한다`() {
        val result = inspect(PropertyPort::class.java)
        assertTrue(result.evaluated, result.problems.toString())
        assertEquals(setOf(
            Triple("getConnection", "return", "java.sql.Connection"),
            Triple("getEntity", "return", JpaRow::class.java.name),
            Triple("setEntity", "parameter[0]", JpaRow::class.java.name),
        ), result.violations.map { Triple(it.declaration.substringBefore('(').substringAfterLast('.'), it.exposure, it.targetType) }.toSet())
        assertEquals(3, result.violations.size)
    }

    @Test
    fun `PORT-07 상속한 반환 계약도 자식 포트의 위반으로 보고한다`() {
        val result = inspect(ChildPort::class.java)
        assertTrue(result.evaluated, result.problems.toString())
        val violation = result.violations.single()
        assertEquals(ChildPort::class.java.name, violation.originType)
        assertEquals(ParentPort::class.java.name + ".inherited()", violation.declaration)
        assertEquals("return", violation.exposure)
        assertEquals("java.sql.Connection", violation.targetType)
    }

    @Test
    fun `PORT-07 상위 제네릭의 실제 인자와 기술 인터페이스 자체를 검사한다`() {
        val generic = inspect(GenericChildPort::class.java)
        assertTrue(generic.evaluated, generic.problems.toString())
        assertEquals(listOf("supertype" to JpaRow::class.java.name), generic.violations.map { it.exposure to it.targetType })
        val technology = inspect(TechnologyParentPort::class.java)
        assertTrue(technology.evaluated, technology.problems.toString())
        assertEquals(listOf("supertype" to "org.springframework.core.env.Environment"), technology.violations.map { it.exposure to it.targetType })
    }

    @Test
    fun `PORT-08 상한 하한 다중 상한과 제네릭 배열에 있는 JDBC 타입을 놓치지 않는다`() {
        val cases = listOf(
            JavaPortFixtures.UpperPort::class.java to setOf("return"),
            JavaPortFixtures.LowerPort::class.java to setOf("return"),
            JavaPortFixtures.BoundPort::class.java to setOf("typeParameter[T]", "return"),
            JavaPortFixtures.GenericArrayPort::class.java to setOf("typeParameter[T]", "return"),
            MethodBoundPort::class.java to setOf("typeParameter[T]", "return"),
        )
        cases.forEach { (port, exposures) ->
            val result = inspect(port)
            assertTrue(result.evaluated, result.problems.toString())
            assertEquals(exposures, result.violations.map { it.exposure }.toSet(), port.name)
            assertEquals(exposures.size, result.violations.size)
            assertTrue(result.violations.all { it.targetType == "java.sql.Connection" && it.ruleId == "ARCH-06" })
        }
        val recursive = inspect(JavaPortFixtures.RecursivePort::class.java)
        assertTrue(recursive.evaluated, recursive.problems.toString())
        assertEquals(emptyList(), recursive.violations)
    }

    @Test
    fun `PORT-09 직접 기술 어노테이션과 선언 예외 및 ObjectMapper를 보고한다`() {
        val annotated = inspect(AnnotatedPort::class.java)
        assertTrue(annotated.evaluated, annotated.problems.toString())
        assertEquals(listOf("annotation" to "jakarta.transaction.Transactional"), annotated.violations.map { it.exposure to it.targetType })
        val member = inspect(AnnotationMemberPort::class.java)
        assertTrue(member.evaluated, member.problems.toString())
        assertEquals(setOf("annotation" to "jakarta.transaction.Transactional",
            "parameter[0].annotation" to "org.springframework.beans.factory.annotation.Qualifier"),
            member.violations.map { it.exposure to it.targetType }.toSet())
        assertEquals(2, member.violations.size)
        assertSingleViolation(inspect(ThrowsPort::class.java), ThrowsPort::class.java, "throws", "java.sql.SQLException", "TECHNOLOGY")
        assertSingleViolation(inspect(MapperPort::class.java), MapperPort::class.java, "return", "tools.jackson.databind.ObjectMapper", "TECHNOLOGY")
    }

    @Test
    fun `PORT-11 공개 중첩 계약과 companion을 검사하고 본문과 private는 제외한다`() {
        val nested = inspect(NestedContractPort::class.java)
        assertTrue(nested.evaluated, nested.problems.toString())
        assertEquals(setOf(NestedContractPort.Exposed::class.java.name + ".connection()",
            NestedContractPort.Companion::class.java.name + ".connection()"), nested.violations.map { it.declaration }.toSet())
        assertEquals(2, nested.violations.size)
        assertTrue(nested.violations.all { it.originType == NestedContractPort::class.java.name && it.targetType == "java.sql.Connection" })
        val body = fixtureScope().classesByModule.getValue("fixture-module").get(DefaultBodyPort::class.java)
        assertTrue(body.directDependenciesFromSelf.any { it.targetClass.name == "java.net.URL" })
        val result = inspect(DefaultBodyPort::class.java)
        assertTrue(result.evaluated, result.problems.toString())
        assertEquals(emptyList(), result.violations)
    }

    @Test
    fun `PORT-13 필요한 내부 상위 정의가 출력에 없으면 자동 해석으로 대체하지 않는다`() {
        val scope = fixtureScope()
        val reduced = scope.copy(classesByModule = scope.classesByModule.mapValues { (_, classes) ->
            classes.that(DescribedPredicate.describe("상위 정의 누락") { it.name != ParentPort::class.java.name })
        })
        val result = PortContractIndependence.inspect(reduced, setOf(ChildPort::class.java.name),
            projectPackagePrefixes = setOf("com.exchange.architecture."))
        assertFalse(result.evaluated)
        assertTrue(result.problems.any { it.code == "UNRESOLVED_PORT_CONTRACT" && it.subject.contains(ParentPort::class.java.name) })
    }

    @Test
    fun `PORT-13 외부 상위 계약을 해석할 수 없으면 빈 계약으로 통과하지 않는다`() {
        // 전역 설정은 finally로 복원한다. 읽은 바이트코드 자체는 실제 Supplier 상속 예제다.
        val configuration = ArchConfiguration.get()
        val previous = configuration.resolveMissingDependenciesFromClassPath()
        val classes = try {
            configuration.setResolveMissingDependenciesFromClassPath(false)
            ClassFileImporter().importClasses(JavaPortFixtures.MissingExternalParentPort::class.java)
        } finally { configuration.setResolveMissingDependenciesFromClassPath(previous) }
        assertFalse(classes.get(JavaPortFixtures.MissingExternalParentPort::class.java).rawInterfaces.single().isFullyImported)
        val result = PortContractIndependence.inspect(ScopeImportResult(mapOf("fixture-module" to classes)),
            setOf(JavaPortFixtures.MissingExternalParentPort::class.java.name))
        assertFalse(result.evaluated)
        assertTrue(result.problems.any { it.code == "UNRESOLVED_PORT_CONTRACT" && it.subject.contains("java.util.function.Supplier") })
    }

    @Test
    fun `PORT-14 서로 다른 위치를 보존하고 입력 순서에 관계없이 같은 결과를 낸다`() {
        val ports = listOf(ValuePort::class.java.name, ConnectionResultPort::class.java.name, ConnectionArgumentPort::class.java.name)
        val scope = fixtureScope()
        val first = PortContractIndependence.inspect(scope, ports.toSet())
        val second = PortContractIndependence.inspect(scope, ports.reversed().toSet())
        assertTrue(first.evaluated && second.evaluated)
        assertEquals(first, second)
        assertEquals(setOf(ConnectionResultPort::class.java.name to "return", ConnectionArgumentPort::class.java.name to "parameter[0]"),
            first.violations.map { it.originType to it.exposure }.toSet())
        assertEquals(2, first.violations.size)
        assertTrue(first.violations.all { it.report().contains("ARCH-06") && it.report().contains("행 정보 없음") })
    }

    @Test
    fun `검사 한계 Any raw 목록과 임의 DTO의 내부 필드는 펼치지 않는다`() {
        listOf(OpaquePort::class.java, JavaPortFixtures.RawPort::class.java).forEach {
            val result = inspect(it)
            assertTrue(result.evaluated, result.problems.toString())
            assertEquals(emptyList(), result.violations)
        }
    }

    @Test
    fun `PORT-14 중복 상속과 JVM 브리지에서도 동일 노출을 한 번만 보고한다`() {
        val diamond = inspect(JavaPortFixtures.DiamondPort::class.java)
        assertTrue(diamond.evaluated, diamond.problems.toString())
        assertEquals(listOf(JavaPortFixtures.ConnectionContract::class.java.name + ".read()"), diamond.violations.map { it.declaration })
        val type = fixtureScope().classesByModule.getValue("fixture-module").get(JavaPortFixtures.BridgePort::class.java)
        assertEquals(2, type.methods.count { it.name == "map" }, "실제 JVM 브리지가 있는 예제여야 한다")
        val bridge = inspect(JavaPortFixtures.BridgePort::class.java)
        assertTrue(bridge.evaluated, bridge.problems.toString())
        assertEquals(setOf(
            JavaPortFixtures.BridgePort::class.java.name + ".map(java.sql.Connection)",
            JavaPortFixtures.BridgeParent::class.java.name + ".map(java.sql.Connection)",
        ), bridge.violations.map { it.declaration }.toSet())
        assertEquals(2, bridge.violations.size, "브리지의 같은 인자 노출은 중복 보고하지 않는다")
        assertTrue(bridge.violations.all { it.exposure == "parameter[0]" && it.targetType == "java.sql.Connection" })
    }

    @Test
    fun `PORT-13 해석 가능한 외부 상위 계약은 허용한다`() {
        val result = inspect(JavaPortFixtures.ResolvedExternalPort::class.java)
        assertTrue(result.evaluated, result.problems.toString())
        assertTrue(result.contractCount >= 2)
        assertEquals(emptyList(), result.violations)
    }

    @Test
    fun `PORT-08 중첩 타입 소유자의 반환 인자와 필드에 있는 JDBC 인자를 보고한다`() {
        val owner = JavaPortFixtures.OwnerReturnPort::class.java.getMethod("load").genericReturnType as java.lang.reflect.ParameterizedType
        assertEquals(java.sql.Connection::class.java,
            (owner.ownerType as java.lang.reflect.ParameterizedType).actualTypeArguments.single(), "컴파일한 선언의 실제 소유 타입 인자")
        listOf(
            JavaPortFixtures.OwnerReturnPort::class.java to "return",
            JavaPortFixtures.OwnerArgumentPort::class.java to "parameter[0]",
            JavaPortFixtures.OwnerFieldPort::class.java to "field",
        ).forEach { (port, exposure) ->
            val result = inspect(port)
            assertTrue(result.evaluated, result.problems.toString())
            assertEquals(1, result.violations.size, port.name)
            val violation = result.violations.single()
            assertEquals(port.name, violation.originType)
            assertTrue(violation.declaration.startsWith(port.name + "."))
            assertEquals(exposure, violation.exposure)
            assertEquals("java.sql.Connection", violation.targetType)
            assertEquals("TECHNOLOGY", violation.reason)
        }
    }

    @Test
    fun `PORT-08 타입 변수 상한 안의 소유 타입 인자도 선언과 사용 자리에서 보고한다`() {
        listOf(JavaPortFixtures.OwnerBoundPort::class.java, JavaPortFixtures.OwnerMethodBoundPort::class.java).forEach { port ->
            val result = inspect(port)
            assertTrue(result.evaluated, result.problems.toString())
            assertEquals(setOf("typeParameter[T]" to "java.sql.Connection", "return" to "java.sql.Connection"),
                result.violations.map { it.exposure to it.targetType }.toSet())
            assertEquals(2, result.violations.size)
        }
    }

    @Test
    fun `PORT-08 메서드 변수 이름이 겹쳐도 클래스 상한과 반환 노출은 같다`() {
        val port = JavaPortFixtures.ShadowedClassBoundPort::class.java
        val classT = port.typeParameters[0]
        val classU = port.typeParameters[1]
        val method = port.getMethod("load")
        assertEquals(classT, classU.bounds.single(), "U의 상한은 클래스가 선언한 T다")
        assertEquals(classU, method.genericReturnType)
        assertNotEquals<java.lang.reflect.Type>(classT, method.typeParameters.single(), "같은 철자여도 서로 다른 선언이다")
        listOf(port, JavaPortFixtures.RenamedMethodVariablePort::class.java).forEach { type ->
            val result = inspect(type)
            assertTrue(result.evaluated, result.problems.toString())
            assertEquals(setOf(
                type.name to "typeParameter[T]",
                type.name to "typeParameter[U]",
                type.name + ".load()" to "return",
            ), result.violations.map { it.declaration to it.exposure }.toSet())
            assertEquals(3, result.violations.size)
            assertTrue(result.violations.all { it.targetType == "java.sql.Connection" && it.reason == "TECHNOLOGY" })
        }
    }

    @Test
    fun `PORT-08 클래스 상한의 연결은 메서드 인자와 새 변수의 상한에도 유지된다`() {
        val port = JavaPortFixtures.ShadowedParameterBoundPort::class.java
        val method = port.name + ".exchange(" + JavaPortFixtures.Owner.Member::class.java.name + ")"
        val result = inspect(port)
        assertTrue(result.evaluated, result.problems.toString())
        assertEquals(setOf(
            port.name to "typeParameter[T]",
            port.name to "typeParameter[U]",
            method to "typeParameter[V]",
            method to "parameter[0]",
            method to "return",
        ), result.violations.map { it.declaration to it.exposure }.toSet())
        assertEquals(5, result.violations.size)
        assertTrue(result.violations.all { it.targetType == "java.sql.Connection" && it.reason == "TECHNOLOGY" })
    }

    @Test
    fun `PORT-08 메서드가 선언한 변수에는 같은 이름의 클래스 상한을 적용하지 않는다`() {
        listOf(JavaPortFixtures.MethodShadowPort::class.java, JavaPortFixtures.RecursiveMethodShadowPort::class.java).forEach { port ->
            val result = inspect(port)
            assertTrue(result.evaluated, result.problems.toString())
            assertEquals(setOf(port.name to "typeParameter[T]", port.name to "typeParameter[U]"),
                result.violations.map { it.declaration to it.exposure }.toSet())
            assertEquals(2, result.violations.size, "메서드 반환과 재귀 상한은 JDBC를 노출하지 않는다")
            assertTrue(result.violations.all { it.targetType == "java.sql.Connection" && it.reason == "TECHNOLOGY" })
        }
    }

    @Test
    fun `PORT-08 메서드의 기술 상한이 클래스의 정상 반환값에 섞이지 않는다`() {
        val port = JavaPortFixtures.SafeClassBoundShadowPort::class.java
        val result = inspect(port)
        assertTrue(result.evaluated, result.problems.toString())
        assertEquals(listOf(port.name + ".load()" to "typeParameter[T]"),
            result.violations.map { it.declaration to it.exposure })
        assertEquals("java.sql.Connection", result.violations.single().targetType)
        assertEquals("TECHNOLOGY", result.violations.single().reason)
    }

    @Test
    fun `PORT-01 중첩 소유 타입이 일반 값이면 허용한다`() {
        val result = inspect(JavaPortFixtures.SafeOwnerPort::class.java)
        assertTrue(result.evaluated, result.problems.toString())
        assertEquals(1, result.contractCount)
        assertEquals(emptyList(), result.violations)
    }

    @Test
    fun `PORT-11 운영 입력 밖 외부 상위의 공개 중첩 계약도 검사한다`() {
        val port = JavaPortFixtures.ExternalNestedPort::class.java
        val classes = ClassFileImporter().importClasses(port)
        assertEquals(setOf(port.name), classes.map { it.name }.toSet(), "외부 상위와 중첩 타입은 운영 입력에 넣지 않는다")
        val result = PortContractIndependence.inspect(ScopeImportResult(mapOf("fixture-module" to classes)), setOf(port.name))
        assertTrue(result.evaluated, result.problems.toString())
        assertEquals(1, result.violations.size)
        val violation = result.violations.single()
        assertEquals(port.name, violation.originType)
        assertEquals("com.exchange.architecture.fixtures.externalports.ExternalPortContracts$" + "Parent$" + "Exposed.load()", violation.declaration)
        assertEquals("return", violation.exposure)
        assertEquals("java.sql.Connection", violation.targetType)
        assertEquals("TECHNOLOGY", violation.reason)
    }

    @Test
    fun `PORT-11 외부 중첩 계약이 정상 값만 노출하면 허용한다`() {
        val port = JavaPortFixtures.SafeExternalNestedPort::class.java
        val result = PortContractIndependence.inspect(ScopeImportResult(mapOf("fixture-module" to ClassFileImporter().importClasses(port))), setOf(port.name))
        assertTrue(result.evaluated, result.problems.toString())
        assertEquals(3, result.contractCount, "자식 메서드와 상속 선언 및 중첩 메서드")
        assertEquals(emptyList(), result.violations)
    }

    @Test
    fun `PORT-13 내부 중첩 계약 누락을 외부 파일 수집으로 숨기지 않는다`() {
        val port = NestedContractPort::class.java
        val result = PortContractIndependence.inspect(ScopeImportResult(mapOf("fixture-module" to ClassFileImporter().importClasses(port))),
            setOf(port.name), projectPackagePrefixes = setOf("com.exchange.architecture."))
        assertFalse(result.evaluated)
        assertTrue(result.problems.any { it.code == "UNRESOLVED_PORT_CONTRACT" && it.subject == NestedContractPort.Exposed::class.java.name }, result.problems.toString())
        assertEquals(emptyList(), result.violations)
    }

    @Test
    fun `PORT-13 외부 중첩 클래스 파일이 없으면 일부 계약으로 통과하지 않는다`(@org.junit.jupiter.api.io.TempDir directory: java.nio.file.Path) {
        val port = JavaPortFixtures.ExternalNestedPort::class.java
        // 원본에 중첩 선언 정보는 남기고 해당 파일만 없는 라이브러리 입력을 만든다.
        listOf(port, com.exchange.architecture.fixtures.externalports.ExternalPortContracts.Parent::class.java).forEach { type ->
            val resource = type.name.replace('.', '/') + ".class"
            val destination = directory.resolve(resource)
            java.nio.file.Files.createDirectories(destination.parent)
            type.classLoader.getResourceAsStream(resource)!!.use { java.nio.file.Files.copy(it, destination) }
        }
        val classes = ClassFileImporter().importPath(directory)
        val result = PortContractIndependence.inspect(ScopeImportResult(mapOf("fixture-module" to classes)), setOf(port.name))
        assertFalse(result.evaluated)
        assertTrue(result.problems.any { it.code == "UNRESOLVED_PORT_CONTRACT" && it.subject.endsWith("Parent$" + "Exposed") }, result.problems.toString())
        assertEquals(emptyList(), result.violations)
    }

    @Test
    fun `PORT-11 JAR의 외부 중첩 계약도 원본 위치에서 읽는다`(@org.junit.jupiter.api.io.TempDir directory: java.nio.file.Path) {
        val port = JavaPortFixtures.ExternalNestedPort::class.java
        val parent = com.exchange.architecture.fixtures.externalports.ExternalPortContracts.Parent::class.java
        val jar = directory.resolve("external-contracts.jar")
        java.util.jar.JarOutputStream(java.nio.file.Files.newOutputStream(jar)).use { output ->
            (listOf(parent) + parent.declaredClasses).forEach { type ->
                val resource = type.name.replace('.', '/') + ".class"
                output.putNextEntry(java.util.jar.JarEntry(resource))
                type.classLoader.getResourceAsStream(resource)!!.use { it.copyTo(output) }
                output.closeEntry()
            }
        }
        val parentUrl = java.net.URI.create("jar:" + jar.toUri() + "!/" + parent.name.replace('.', '/') + ".class").toURL()
        val scope = ScopeImportResult(mapOf(
            "fixture-module" to ClassFileImporter().importClasses(port),
            "external-fixture" to ClassFileImporter().importUrl(parentUrl),
        ))
        val result = PortContractIndependence.inspect(scope, setOf(port.name))
        assertTrue(result.evaluated, result.problems.toString())
        assertEquals(listOf("return" to "java.sql.Connection"), result.violations.map { it.exposure to it.targetType })
        assertTrue(result.violations.single().declaration.endsWith("Parent$" + "Exposed.load()"))
    }

    @Test
    fun `PORT-13 원본 클래스가 사라지거나 손상되면 준비 실패로 처리한다`(@org.junit.jupiter.api.io.TempDir directory: java.nio.file.Path) {
        val port = JavaPortFixtures.SafeOwnerPort::class.java
        val resource = port.name.replace('.', '/') + ".class"
        listOf("missing", "broken").forEach { scenario ->
            val target = directory.resolve(scenario).resolve(resource)
            java.nio.file.Files.createDirectories(target.parent)
            port.classLoader.getResourceAsStream(resource)!!.use { java.nio.file.Files.copy(it, target) }
            val scope = ScopeImportResult(mapOf("fixture-module" to ClassFileImporter().importPath(target)))
            assertTrue(scope.classesByModule.getValue("fixture-module").get(port).isFullyImported)
            if (scenario == "missing") java.nio.file.Files.delete(target)
            else java.nio.file.Files.write(target, byteArrayOf(0, 1, 2))
            val result = PortContractIndependence.inspect(scope, setOf(port.name))
            assertFalse(result.evaluated, scenario)
            assertTrue(result.problems.any { it.code == "CONTRACT_READ_FAILURE" && it.subject.startsWith(port.name) }, result.problems.toString())
            assertEquals(emptyList(), result.violations)
        }
    }

    @Test
    fun `PORT-11 여러 단계 상위 인터페이스의 static은 자식의 계약에서 제외한다`() {
        val port = JavaPortFixtures.StaticChildPort::class.java
        val publicMethods = port.methods.map { it.name }.toSet()
        assertTrue(publicMethods.containsAll(setOf("load", "label")), "일반 메서드와 default 메서드는 상속된다")
        assertFalse("open" in publicMethods || "save" in publicMethods, "인터페이스 static은 상속되지 않는다")
        val result = inspect(port)
        assertTrue(result.evaluated, result.problems.toString())
        assertEquals(emptyList(), result.violations)
        assertEquals(4, result.contractCount, "상속 선언 2개와 일반/default 메서드 2개만 센다")
    }

    @Test
    fun `PORT-11 부모도 등록하면 그 부모의 static은 부모 위반으로 보고한다`() {
        val parent = JavaPortFixtures.StaticParentPort::class.java
        val child = JavaPortFixtures.StaticChildPort::class.java
        val result = PortContractIndependence.inspect(fixtureScope(), setOf(parent.name, child.name))
        assertTrue(result.evaluated, result.problems.toString())
        assertEquals(setOf(parent.name), result.violations.map { it.originType }.toSet())
        assertEquals(setOf(parent.name + ".open()" to "return", parent.name + ".save(java.sql.Connection)" to "parameter[0]"),
            result.violations.map { it.declaration to it.exposure }.toSet())
        assertEquals(2, result.violations.size)
        assertTrue(result.violations.all { it.targetType == "java.sql.Connection" && it.reason == "TECHNOLOGY" })
    }

    @Test
    fun `PORT-11 자식 자신이 선언한 static은 계속 검사한다`() {
        val port = JavaPortFixtures.OwnStaticChildPort::class.java
        val result = inspect(port)
        assertTrue(result.evaluated, result.problems.toString())
        assertEquals(listOf(port.name + ".own()" to "return"), result.violations.map { it.declaration to it.exposure })
        assertEquals("java.sql.Connection", result.violations.single().targetType)
        assertEquals("TECHNOLOGY", result.violations.single().reason)
    }

    @Test
    fun `PORT-11 상속한 일반 메서드와 default 메서드의 위반은 남긴다`() {
        val parent = JavaPortFixtures.InstanceParentPort::class.java
        val port = JavaPortFixtures.InstanceChildPort::class.java
        val result = inspect(port)
        assertTrue(result.evaluated, result.problems.toString())
        assertEquals(setOf(parent.name + ".load()", parent.name + ".read()"), result.violations.map { it.declaration }.toSet())
        assertEquals(2, result.violations.size)
        assertTrue(result.violations.all { it.originType == port.name && it.exposure == "return" && it.targetType == "java.sql.Connection" })
    }

    @Test
    fun `PORT-11 상위로 먼저 읽은 타입도 공개 중첩 계약이면 자신의 static을 검사한다`() {
        val port = JavaPortFixtures.InheritedAndNestedPort::class.java
        val nested = JavaPortFixtures.NestedStaticParentPort.Exposed::class.java
        val result = inspect(port)
        assertTrue(result.evaluated, result.problems.toString())
        assertEquals(listOf(nested.name + ".open()" to "return"), result.violations.map { it.declaration to it.exposure })
        assertEquals(port.name, result.violations.single().originType)
        assertEquals("java.sql.Connection", result.violations.single().targetType)
    }

    private fun fixtureScope() = ScopeImportResult(mapOf("fixture-module" to
        ClassFileImporter().importPackages("com.exchange.architecture.fixtures.portcontracts")))

    private fun inspect(port: Class<*>, persistenceTypes: Set<String> = emptySet()) =
        PortContractIndependence.inspect(fixtureScope(), setOf(port.name), persistenceTypes)

    private fun assertSingleViolation(result: PortInspection, port: Class<*>, exposure: String, target: String, reason: String) {
        assertTrue(result.evaluated, result.problems.toString())
        assertEquals(1, result.violations.size, "정확히 한 노출을 보고해야 한다: ${result.violations}")
        val violation = result.violations.single()
        assertEquals("ARCH-06", violation.ruleId)
        assertEquals("fixture-module", violation.originModule)
        assertEquals(port.name, violation.originType)
        assertTrue(violation.declaration.startsWith(port.name + "."))
        assertEquals(exposure, violation.exposure)
        assertEquals(target, violation.targetType)
        assertEquals(reason, violation.reason)
        assertEquals("PortFixtures.kt", violation.sourceFile)
        assertEquals(null, violation.lineNumber, "추상 계약에 실행 행을 지어내지 않는다")
        assertEquals("engineering/architecture-check-spec.md", violation.specification)
    }
}
