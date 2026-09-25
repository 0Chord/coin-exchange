package com.exchange.architecture.rules

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.JavaClass

data class ArchitectureRoles(
    val pureDomain: Set<String>,
    val externalPorts: Set<String> = emptySet(),
    val executors: Set<String> = emptySet(),
)

data class ArchitectureViolation(
    val ruleId: String,
    val originType: String,
    val targetType: String,
    val description: String,
    val sourceFile: String?,
    val lineNumber: Int?,
    val specification: String,
)

object DomainTechnologyIndependence {
    // Explicit technology families; URI/value types and the entire JDK are not blanket-banned.
    private val technologyPrefixes = listOf(
        "org.springframework.", "jakarta.persistence.", "javax.persistence.",
        "java.sql.", "javax.sql.", "java.net.http.", "javax.net.",
        "org.apache.kafka.clients.", "org.postgresql.", "org.hibernate.",
    )
    private val networkIoTypes = setOf(
        "java.net.Socket", "java.net.ServerSocket", "java.net.DatagramSocket", "java.net.MulticastSocket",
        "java.net.URL", "java.net.URLConnection", "java.net.HttpURLConnection", "java.net.JarURLConnection",
        "java.net.InetAddress", "java.net.Inet4Address", "java.net.Inet6Address",
        "java.nio.channels.SocketChannel", "java.nio.channels.ServerSocketChannel", "java.nio.channels.DatagramChannel",
    )

    fun evaluate(classes: JavaClasses, roles: ArchitectureRoles): List<ArchitectureViolation> {
        val violations = mutableListOf<ArchitectureViolation>()
        classes.filter { belongsToRole(it, roles.pureDomain) }.forEach { origin ->
            origin.directDependenciesFromSelf.forEach { dependency ->
                val target = dependency.targetClass.baseComponentType.name
                if (technologyPrefixes.any { target.startsWith(it) } || target in networkIoTypes) {
                    violations += diagnostic(origin, target, dependency.description, dependency.sourceCodeLocation.lineNumber)
                }
            }
            // Referencing a port's type is not itself an external call. Invocations/references are.
            (origin.methodCallsFromSelf + origin.methodReferencesFromSelf).forEach { access ->
                val target = access.target.owner
                if (target.name in roles.externalPorts || target.allRawInterfaces.any { it.name in roles.externalPorts }) {
                    violations += diagnostic(origin, target.name, access.description, access.lineNumber)
                }
            }
        }
        return violations.distinct().sortedWith(compareBy(
            { it.ruleId }, { it.originType }, { it.targetType }, { it.sourceFile }, { it.lineNumber }, { it.description },
        ))
    }

    private fun diagnostic(origin: JavaClass, target: String, description: String, line: Int) = ArchitectureViolation(
        ruleId = "ARCH-01", originType = origin.name, targetType = target, description = description,
        sourceFile = origin.source.flatMap { it.fileName }.orElse(null),
        lineNumber = line.takeIf { it > 0 }, specification = "engineering/architecture-check-spec.md",
    )
}

/** Use the bytecode's enclosing relationship, not a blanket exclusion for names containing '$'. */
fun belongsToRole(type: JavaClass, roots: Set<String>): Boolean =
    generateSequence(type) { it.enclosingClass.orElse(null) }.any { it.name in roots }
