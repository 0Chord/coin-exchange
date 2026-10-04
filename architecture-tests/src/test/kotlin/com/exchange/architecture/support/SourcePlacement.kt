package com.exchange.architecture.support

import java.nio.file.Path

/** Gradle의 실제 main 입력. 파일 목록은 역할/허용 경로 목록에서 만들지 않는다. */
data class MainSourceRoot(
    val module: String,
    val moduleDirectory: Path,
    val relativeRoot: String,
    val files: Set<Path>,
    val producer: String? = null,
)

data class ParsedSource(
    val module: String,
    val path: Path,
    val relativeRoot: String,
    val parentFolder: String,
    val packageSegments: List<String>,
)

data class SourcePlacementResult(
    val result: PlacementResult,
    val sources: List<ParsedSource> = emptyList(),
)

object SourcePlacement {
    /** 파일·정책 준비가 실패하면 부분 위반 목록도 통과 증거로 노출하지 않는다. */
    fun inspect(
        roots: List<MainSourceRoot>,
        policy: LayoutPolicy,
        registeredModules: Set<String>,
    ): SourcePlacementResult {
        val errors =
            com.exchange.architecture.policy.LayoutPolicyValidation
                .inspect(policy, registeredModules)
                .toMutableList()
        val violations = mutableListOf<PlacementViolation>()
        val sources = mutableListOf<ParsedSource>()

        fun invalid(
            subject: String,
            detail: String,
        ) {
            errors += ScopeProblem(ScopeProblemCode.INVALID_TARGET_INPUT, subject, detail)
        }
        if (roots.isEmpty() || roots.all { it.files.isEmpty() }) errors += ScopeProblem(ScopeProblemCode.EMPTY_SCOPE, "main-sources")
        roots
            .groupBy { it.module to it.relativeRoot }
            .filterValues { it.size > 1 }
            .keys
            .forEach { invalid(it.toString(), "루트 중복") }
        roots
            .groupBy { it.module }
            .filterValues { group ->
                group.map { it.moduleDirectory.toAbsolutePath().normalize() }.distinct().size !=
                    1
            }.keys
            .forEach { invalid(it, "모듈 디렉터리 충돌") }
        val seenFiles = mutableSetOf<Path>()
        SourcePackageParser().use { parser ->
            for (input in roots) {
                if (input.module !in registeredModules ||
                    !com.exchange.architecture.policy.LayoutPolicyValidation
                        .relativePath(input.relativeRoot)
                ) {
                    invalid(input.relativeRoot, "알 수 없는 모듈 또는 루트 형식")
                    continue
                }
                val moduleDir = input.moduleDirectory.toAbsolutePath().normalize()
                val root = moduleDir.resolve(input.relativeRoot)
                val allowedRoot = policy.roots.singleOrNull { it.module == input.module && it.path == input.relativeRoot }
                val inactiveJava = input.relativeRoot == "src/main/java" && input.files.isEmpty() && input.producer == null
                if (!inactiveJava && (allowedRoot == null || allowedRoot.producer != input.producer)) {
                    violations +=
                        PlacementViolation(
                            root.toString(),
                            "sourceRoot",
                            input.relativeRoot,
                            policy.roots.filter { it.module == input.module }.joinToString {
                                it.path + (
                                    it.producer?.let { p ->
                                        " ($p)"
                                    } ?: ""
                                )
                            },
                            input.module,
                        )
                }
                for (candidate in input.files.sorted()) {
                    val file = candidate.toAbsolutePath().normalize()
                    try {
                        require(file.startsWith(root)) { "원본이 전달된 루트 밖에 있습니다" }
                        val owners =
                            roots.filter {
                                it.moduleDirectory
                                    .toAbsolutePath()
                                    .normalize()
                                    .resolve(
                                        it.relativeRoot,
                                    ).let(file::startsWith)
                            }
                        require(owners.size == 1) { "원본의 모듈·루트 소속이 중복됩니다" }
                        require(
                            java.nio.file.Files
                                .isRegularFile(file),
                        ) { "원본 파일이 없거나 읽을 수 없습니다" }
                        require(
                            file.toRealPath().startsWith(root.toRealPath()) && root.toRealPath().startsWith(moduleDir.toRealPath()),
                        ) { "심볼릭 링크가 소속 밖으로 나갑니다" }
                        require(
                            file.toRealPath() == moduleDir.toRealPath().resolve(moduleDir.relativize(file)) &&
                                root.toRealPath() == moduleDir.toRealPath().resolve(input.relativeRoot),
                        ) { "별칭 경로는 허용하지 않습니다" }
                        require(seenFiles.add(file.toRealPath())) { "같은 원본이 여러 입력에 있습니다" }
                        val segments = parser.read(file)
                        val parent = root.relativize(file.parent).joinToString("/")
                        val expected = segments.joinToString("/")
                        sources += ParsedSource(input.module, file, input.relativeRoot, parent, segments)
                        val locations = policy.folders.filter { it.module == input.module && it.sourceRoot == input.relativeRoot }
                        if (locations.none { it.folder == parent }) {
                            violations +=
                                PlacementViolation(
                                    file.toString(),
                                    "allowedFolder",
                                    parent,
                                    locations.map { it.folder }.sorted().joinToString(),
                                    input.module,
                                    sourceFile = file.toString(),
                                )
                        }
                        if (parent != expected) {
                            violations +=
                                PlacementViolation(
                                    file.toString(),
                                    "sourceFolder",
                                    parent,
                                    expected,
                                    input.module,
                                    sourceFile = file.toString(),
                                )
                        }
                    } catch (error: Exception) {
                        errors += ScopeProblem(ScopeProblemCode.READ_FAILURE, file.toString(), error.message)
                    }
                }
            }
        }
        if (errors.isNotEmpty()) {
            return SourcePlacementResult(
                PlacementResult(
                    problems =
                        errors.distinct().sortedWith(
                            compareBy({
                                it.code.name
                            }, { it.subject }, { it.detail }),
                        ),
                ),
            )
        }
        return SourcePlacementResult(
            PlacementResult(
                violations = violations.distinct().sortedWith(compareBy({ it.subject }, { it.item })),
                evaluatedFiles = sources.map { it.path.toString() }.toSortedSet(),
            ),
            sources,
        )
    }
}
