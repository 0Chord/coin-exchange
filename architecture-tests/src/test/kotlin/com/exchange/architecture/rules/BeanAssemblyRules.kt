package com.exchange.architecture.rules

import com.exchange.architecture.support.AllowedFolder
import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaAnnotation
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

/** config의 명시적 Bean 조립과 HTTP 자동 등록 예외를 검사한다. */
object BeanAssemblyRules {
    private const val COMPONENT = "org.springframework.stereotype.Component"
    private const val CONFIGURATION = "org.springframework.context.annotation.Configuration"
    private const val BEAN = "org.springframework.context.annotation.Bean"
    private val frameworkAnnotations =
        setOf(
            CONFIGURATION,
            "org.springframework.boot.autoconfigure.SpringBootApplication",
            "org.springframework.stereotype.Controller",
            "org.springframework.web.bind.annotation.RestController",
            "org.springframework.web.bind.annotation.ControllerAdvice",
            "org.springframework.web.bind.annotation.RestControllerAdvice",
        )

    // 허용 어노테이션 하나가 다른 금지 어노테이션을 가리지 않도록 각각 판정한다.
    fun noAutomaticBusinessRegistration() =
        noClasses()
            .that()
            .areNotAnnotations()
            .should()
            .beAnnotatedWith(
                object : DescribedPredicate<JavaAnnotation<*>>("업무 객체를 자동 등록하는 Component 계열 어노테이션") {
                    override fun test(input: JavaAnnotation<*>) =
                        input.rawType.name !in frameworkAnnotations &&
                            (input.rawType.name == COMPONENT || input.rawType.isMetaAnnotatedWith(COMPONENT))
                },
            ).`as`("ARCH-05/bean-registration: 업무 객체는 자동 등록하지 않고 config의 Bean으로 조립한다")

    /** 팩토리의 반환 타입이 아니라 메서드를 선언한 클래스의 모듈·package·Configuration을 검사한다. */
    fun configFactories(
        modules: Map<String, String>,
        config: AllowedFolder,
    ) = methods()
        .that()
        .areAnnotatedWith(BEAN)
        .or()
        .areMetaAnnotatedWith(BEAN)
        .should()
        .beDeclaredInClassesThat(
            object : DescribedPredicate<JavaClass>("${config.module} ${config.packageName}의 Configuration") {
                override fun test(input: JavaClass) =
                    modules[input.name] == config.module &&
                        input.packageName == config.packageName && input.isAnnotatedWith(CONFIGURATION)
            },
        ).`as`("ARCH-05/bean-factories: Bean 메서드는 허용 config에서만 선언한다")
        .allowEmptyShould(true)
}
