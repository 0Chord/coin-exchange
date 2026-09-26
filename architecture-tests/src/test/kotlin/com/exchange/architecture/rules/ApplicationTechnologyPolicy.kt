package com.exchange.architecture.rules

import com.exchange.architecture.support.ScopeProblem
import com.exchange.architecture.support.ScopeProblemCode
import com.tngtech.archunit.core.domain.JavaClass

/** Spring 전체를 금지하지 않는다. DB·HTTP 직접 사용과 컨테이너 조립 우회만 분류한다. */
internal class ApplicationTechnologyPolicy {
    val problems = mutableListOf<ScopeProblem>()
    private val containers = mutableMapOf<String, Boolean>()

    fun forbidden(type: JavaClass): Boolean = forbiddenPackages.any { type.name.startsWith(it) } ||
        type.name in assemblyAnnotations || isContainer(type, mutableSetOf())

    private fun isContainer(type: JavaClass, visiting: MutableSet<String>): Boolean {
        if (type.name in containerContracts) return true
        if (type.isPrimitive) return false
        containers[type.name]?.let { return it }
        if (!visiting.add(type.name)) return false
        if (!type.isFullyImported) {
            problems += ScopeProblem(ScopeProblemCode.UNRESOLVED_TYPE_HIERARCHY, type.name,
                "컨테이너 하위 타입인지 판정할 상속 정의를 읽지 못했습니다")
            return false
        }
        val parents = type.rawInterfaces + listOfNotNull(type.rawSuperclass.orElse(null))
        // 모든 부모를 읽어 한쪽 분기의 누락도 통과로 덮지 않는다.
        val inherited = parents.map { isContainer(it, visiting) }.any { it }
        visiting.remove(type.name)
        containers[type.name] = inherited
        return inherited
    }

    private val forbiddenPackages = setOf("java.sql.", "javax.sql.", "org.springframework.jdbc.",
        "jakarta.persistence.", "javax.persistence.", "org.springframework.data.repository.",
        "org.springframework.data.jpa.repository.", "org.hibernate.", "org.postgresql.",
        "org.springframework.web.", "org.springframework.http.", "jakarta.servlet.", "javax.servlet.", "java.net.http.")
    private val assemblyAnnotations = setOf("org.springframework.context.annotation.Bean", "org.springframework.context.annotation.Configuration")
    private val containerContracts = setOf("org.springframework.beans.factory.BeanFactory", "org.springframework.beans.factory.ListableBeanFactory",
        "org.springframework.beans.factory.HierarchicalBeanFactory", "org.springframework.beans.factory.config.AutowireCapableBeanFactory",
        "org.springframework.beans.factory.config.ConfigurableBeanFactory", "org.springframework.beans.factory.config.ConfigurableListableBeanFactory",
        "org.springframework.context.ApplicationContext", "org.springframework.context.ConfigurableApplicationContext")
}
