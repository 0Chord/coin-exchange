package com.exchange.core.isolationfixture

import com.tngtech.archunit.core.domain.JavaClass
import org.junit.jupiter.api.Tag
import org.openjdk.jmh.annotations.Benchmark
import org.springframework.stereotype.Component
import org.springframework.test.context.ContextConfiguration
import org.testcontainers.containers.GenericContainer
import kotlin.test.assertTrue

// 모두 위반 여부를 확인하기 위한 예제다. 각 테스트가 임시 main/test 출력에 나누어 배치한다.
open class Helper { fun value(): String = "helper" }
class TestValue(val value: String)
class Normal(val value: TestValue)
class UsesHelper(val helper: Helper) { fun use(): String = helper.value() }
class ExtendsHelper : Helper()
class Signatures(val values: List<Helper>) { fun echo(input: Array<Helper>): Array<Helper> = input }
@Tag("example") class JunitAnnotated
class KotlinAssertion { fun check(value: Boolean) = assertTrue(value) }
class ContainerField(val container: GenericContainer<*>)
class BenchmarkMethod { @Benchmark fun sample(): Int = 1 }
class ArchitectureField(val type: JavaClass)
@ContextConfiguration class SpringTestAnnotated
@Component class SpringProduction
fun helperReference(helper: Helper): () -> String = helper::value
fun helperLambda(helper: Helper): () -> String = { helper.value() }
