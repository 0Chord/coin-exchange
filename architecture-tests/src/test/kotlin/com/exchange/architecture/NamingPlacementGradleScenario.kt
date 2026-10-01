package com.exchange.architecture

import com.exchange.architecture.rules.NamingPlacement
import com.exchange.architecture.support.*
import java.nio.file.Files
import java.nio.file.Path

/** TestKit의 실제 Test 프로세스에서 동일한 검사기를 호출한다. 대상 클래스 목록을 주입하지 않는다. */
object NamingPlacementGradleScenario {
    @JvmStatic fun verify(project: Path, manifest: String) {
        val policyDir = project.resolve("src/test/kotlin/com/exchange/architecture/policy")
        // 이 두 상수는 입력 변경을 재현하는 예제 정책이다. 운영 정책을 해석하는 별도 설정 언어가 아니다.
        fun constant(file: String) = Regex("\"([^\"]+)\"").find(Files.readString(policyDir.resolve(file)))!!.groupValues[1]
        val folder = constant("ProjectLayoutPolicy.kt")
        val reason = constant("PortPlacementPolicy.kt")
        val policy = LayoutPolicy(listOf(AllowedSourceRoot("app-api", "src/main/java")), listOf(
            AllowedFolder("order-application", "app-api", "src/main/java", folder, reason)))
        val scope = ProductionScopeImporter().load(listOf(ModuleOutput("app-api", listOf(project.resolve("app-api/build/classes/java/main")))),
            ScopeExpectations(mapOf("app-api" to setOf("com.example.order.SubmitOrderUseCase"))))
        val result = NamingPlacement.inspect(scope, policy, MainSourceSnapshot.read(manifest, setOf("app-api")), setOf("app-api"))
        val report = buildString {
            appendLine("evaluated=${result.evaluated}")
            result.evaluatedTypes.forEach { appendLine("type=$it") }
            result.rules.forEach { appendLine("rule=${it.id}:targets=${it.targets.joinToString()}") }
            appendLine("policyReason=$reason")
            result.violations.forEach { appendLine("violation=${it.subject}:${it.item}:${it.expected}") }
            result.problems.forEach { appendLine("problem=$it") }
        }
        Files.writeString(project.resolve("build/naming-result.txt"), report)
        check(result.evaluated && result.violations.isEmpty()) { report }
    }
}
