package com.exchange.architecture

import com.exchange.architecture.fixtures.beanassembly.AutoRegisteredStorage
import com.exchange.architecture.fixtures.beanassembly.AutoRegisteredUseCase
import com.exchange.architecture.fixtures.beanassembly.AutoRegisteredWorker
import com.exchange.architecture.fixtures.beanassembly.BusinessComponent
import com.exchange.architecture.fixtures.beanassembly.ComposedBusinessComponent
import com.exchange.architecture.fixtures.beanassembly.ComposedWorker
import com.exchange.architecture.fixtures.beanassembly.ExplicitAssemblyConfig
import com.exchange.architecture.fixtures.beanassembly.FixtureApplication
import com.exchange.architecture.fixtures.beanassembly.HtmlAdvice
import com.exchange.architecture.fixtures.beanassembly.HtmlController
import com.exchange.architecture.fixtures.beanassembly.HttpAdvice
import com.exchange.architecture.fixtures.beanassembly.HttpController
import com.exchange.architecture.fixtures.beanassembly.MixedConfig
import com.exchange.architecture.fixtures.beanassembly.MixedController
import com.exchange.architecture.fixtures.beanassembly.OutsideComposedFactory
import com.exchange.architecture.fixtures.beanassembly.OutsideConfig
import com.exchange.architecture.fixtures.beanassembly.PlainStorage
import com.exchange.architecture.fixtures.beanassembly.PlainUseCase
import com.exchange.architecture.fixtures.beanassembly.UnconfiguredFactory
import com.exchange.architecture.rules.BeanAssemblyRules
import com.exchange.architecture.support.AllowedFolder
import com.tngtech.archunit.core.importer.ClassFileImporter
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BeanAssemblyRuleTest {
    private val config =
        AllowedFolder(
            "config",
            "app-api",
            "src/main/kotlin",
            ExplicitAssemblyConfig::class.java.packageName.replace('.', '/'),
            "명시적 Bean 조립 예제",
        )

    private fun imports(vararg types: Class<*>) = ClassFileImporter().importClasses(types.toList())

    @Test fun `어노테이션 없는 업무 객체와 명시적 config 조립은 통과한다`() {
        val types = imports(PlainUseCase::class.java, PlainStorage::class.java, ExplicitAssemblyConfig::class.java)
        BeanAssemblyRules.noAutomaticBusinessRegistration().check(types)
        BeanAssemblyRules.configFactories(types.associate { it.name to "app-api" }, config).check(types)
    }

    @Test fun `Service Component Repository는 이름이나 위치와 관계없이 자동 등록 위반이다`() {
        for (type in listOf(AutoRegisteredUseCase::class.java, AutoRegisteredWorker::class.java, AutoRegisteredStorage::class.java)) {
            val error = assertFailsWith<AssertionError> { BeanAssemblyRules.noAutomaticBusinessRegistration().check(imports(type)) }
            assertTrue(error.message!!.contains(type.name), error.message)
            assertTrue(error.message!!.contains("ARCH-05/bean-registration"), error.message)
        }
    }

    @Test fun `사용자 정의 어노테이션을 겹쳐도 Component 자동 등록을 우회하지 못한다`() {
        val error =
            assertFailsWith<AssertionError> {
                BeanAssemblyRules.noAutomaticBusinessRegistration().check(
                    imports(ComposedWorker::class.java),
                )
            }
        assertTrue(error.message!!.contains(ComposedWorker::class.java.name), error.message)
        assertTrue(error.message!!.contains("ARCH-05/bean-registration"), error.message)
    }

    @Test fun `HTTP 어노테이션 Configuration Boot와 어노테이션 선언 자체는 허용한다`() {
        BeanAssemblyRules.noAutomaticBusinessRegistration().check(
            imports(
                HttpController::class.java,
                HttpAdvice::class.java,
                HtmlController::class.java,
                HtmlAdvice::class.java,
                ExplicitAssemblyConfig::class.java,
                FixtureApplication::class.java,
                BusinessComponent::class.java,
                ComposedBusinessComponent::class.java,
            ),
        )
    }

    @Test fun `HTTP나 config 어노테이션을 같이 붙여도 Service Repository 금지를 우회하지 못한다`() {
        for (type in listOf(MixedController::class.java, MixedConfig::class.java)) {
            val error = assertFailsWith<AssertionError> { BeanAssemblyRules.noAutomaticBusinessRegistration().check(imports(type)) }
            assertTrue(error.message!!.contains(type.name), error.message)
        }
    }

    @Test fun `Bean 메서드는 Configuration이 붙은 config 클래스에서만 선언한다`() {
        val types = imports(UnconfiguredFactory::class.java)
        val error =
            assertFailsWith<AssertionError> {
                BeanAssemblyRules
                    .configFactories(
                        types.associate { it.name to "app-api" },
                        config,
                    ).check(types)
            }
        assertTrue(error.message!!.contains("UnconfiguredFactory.workflow"), error.message)
        assertTrue(error.message!!.contains("ARCH-05/bean-factories"), error.message)
    }

    @Test fun `Configuration이어도 허용 config package가 아니면 Bean 조립 위반이다`() {
        val types = imports(OutsideConfig::class.java)
        val wrongArea = config.copy(folder = "com/exchange/other/config")
        val error =
            assertFailsWith<AssertionError> {
                BeanAssemblyRules
                    .configFactories(
                        types.associate { it.name to "app-api" },
                        wrongArea,
                    ).check(types)
            }
        assertTrue(error.message!!.contains("OutsideConfig.workflow"), error.message)
    }

    @Test fun `같은 config package라도 다른 모듈의 Bean 메서드는 허용하지 않는다`() {
        val types = imports(ExplicitAssemblyConfig::class.java)
        val error =
            assertFailsWith<AssertionError> {
                BeanAssemblyRules
                    .configFactories(
                        types.associate { it.name to "domain-order" },
                        config,
                    ).check(types)
            }
        assertTrue(error.message!!.contains("ExplicitAssemblyConfig.workflow"), error.message)
    }

    @Test fun `합성 Bean 어노테이션도 config 밖에서 팩토리를 만들지 못한다`() {
        val types = imports(OutsideComposedFactory::class.java)
        val error =
            assertFailsWith<AssertionError> {
                BeanAssemblyRules
                    .configFactories(
                        types.associate { it.name to "app-api" },
                        config,
                    ).check(types)
            }
        assertTrue(error.message!!.contains("OutsideComposedFactory.workflow"), error.message)
    }

    @Test fun `Bean 메서드가 없는 유효한 업무 클래스는 팩토리 위반이 아니다`() {
        val types = imports(PlainUseCase::class.java)
        BeanAssemblyRules.configFactories(types.associate { it.name to "app-api" }, config).check(types)
    }
}
