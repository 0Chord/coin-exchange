package com.exchange.architecture.support

import com.tngtech.archunit.core.domain.JavaClass
import org.jetbrains.kotlin.metadata.ProtoBuf
import org.jetbrains.kotlin.metadata.deserialization.Flags
import org.jetbrains.kotlin.metadata.jvm.deserialization.JvmProtoBufUtil
import java.lang.classfile.Attributes
import java.lang.classfile.ClassFile
import java.lang.classfile.ClassModel
import java.lang.reflect.AccessFlag

/** 원본 바이트코드와 Kotlin 메타데이터를 읽는다. 검사할 제품 클래스를 로드하거나 초기화하지 않는다. */
internal class NamingTypeFacts(private val input: NamingPlacementInput, policy: LayoutPolicy) {
    data class Facts(
        val type: JavaClass, val model: ClassModel, val kind: Int?, val data: Boolean, val value: Boolean,
        val generated: Boolean, val owner: String?, val parts: Set<String>, val facade: String?,
    ) {
        val isInterface = model.flags().has(AccessFlag.INTERFACE)
        val isAnnotation = model.flags().has(AccessFlag.ANNOTATION)
        val isEnum = model.flags().has(AccessFlag.ENUM)
        val isRecord = model.findAttribute(Attributes.record()).isPresent
        val isData = data || value || isRecord || isEnum
        val concrete = !isInterface && !isAnnotation && !isData && !model.flags().has(AccessFlag.ABSTRACT)
        val sourceFile: String? = model.findAttribute(Attributes.sourceFile()).orElse(null)?.sourceFile()?.stringValue()
    }
    private val namespaces = (policy.folders.map { it.packageName } + input.classes.values.map { it.packageName })
        .filter { it.isNotBlank() }.map { it.split('.').take(3).joinToString(".") + "." }.toSet()
    private val external = mutableMapOf<String, ClassModel>()
    val facts: Map<String, Facts> = input.classes.mapValues { (_, type) -> read(type) }
    private fun className(model: ClassModel) = model.thisClass().asInternalName().replace('/', '.')
    private fun read(type: JavaClass): Facts {
        val uri = requireNotNull(type.source.orElse(null)) { "클래스 원본 없음: ${type.name}" }.uri
        val model = uri.toURL().openStream().use { ClassFile.of().parse(it.readAllBytes()) }
        require(className(model) == type.name) { "클래스 원본 이름 불일치: ${type.name}" }
        var kind: Int? = null; var data = false; var value = false; var companion = false
        var parts = emptySet<String>(); var facade: String? = null
        if (type.isAnnotatedWith(Metadata::class.java)) {
            val metadata = type.getAnnotationOfType(Metadata::class.java)
            kind = metadata.kind
            require(metadata.metadataVersion.isNotEmpty() && metadata.metadataVersion[0] in 1..2) { "지원하지 않는 Kotlin 메타데이터: ${type.name}" }
            when (kind) {
                1 -> {
                    require(metadata.data1.isNotEmpty()) { "Kotlin 클래스 메타데이터 누락: ${type.name}" }
                    val (names, proto) = JvmProtoBufUtil.readClassDataFrom(metadata.data1, metadata.data2)
                    require(type.isAnonymousClass || type.isLocalClass || names.getQualifiedClassName(proto.fqName).replace('/', '.')
                        .replace('$', '.') == type.name.replace('$', '.')) { "Kotlin 메타데이터 소유 타입 불일치: ${type.name}" }
                    data = Flags.IS_DATA.get(proto.flags); value = Flags.IS_VALUE_CLASS.get(proto.flags)
                    companion = Flags.CLASS_KIND.get(proto.flags) == ProtoBuf.Class.Kind.COMPANION_OBJECT
                }
                2, 5 -> {
                    require(metadata.data1.isNotEmpty()) { "Kotlin 파일 메타데이터 누락: ${type.name}" }
                    JvmProtoBufUtil.readPackageDataFrom(metadata.data1, metadata.data2)
                    if (kind == 5) {
                        facade = metadata.extraString.replace('/', '.')
                        require(facade.isNotBlank()) { "다중 파일 facade 연결 누락: ${type.name}" }
                    }
                }
                3 -> Unit
                4 -> {
                    parts = metadata.data1.map { it.replace('/', '.') }.toSortedSet()
                    require(parts.isNotEmpty() && parts.size == metadata.data1.size) { "다중 파일 part 목록 누락·중복: ${type.name}" }
                }
                else -> error("알 수 없는 Kotlin 메타데이터 종류: ${type.name}: $kind")
            }
        }
        val inner = model.findAttribute(Attributes.innerClasses()).orElse(null)?.classes().orEmpty()
            .singleOrNull { it.innerClass().asInternalName() == model.thisClass().asInternalName() }
        val owner = model.findAttribute(Attributes.enclosingMethod()).orElse(null)?.enclosingClass()?.asInternalName()?.replace('/', '.')
            ?: inner?.outerClass()?.orElse(null)?.asInternalName()?.replace('/', '.')
        val anonymous = inner != null && inner.innerName().isEmpty
        return Facts(type, model, kind, data, value, companion || anonymous || kind == 3, owner, parts, facade)
    }
    private fun model(name: String): ClassModel {
        facts[name]?.let { return it.model }
        require(namespaces.none { name.startsWith(it) }) { "내부 상위 정의가 수집 결과에 없습니다: $name" }
        return external.getOrPut(name) {
            val path = name.replace('.', '/') + ".class"
            val resource = requireNotNull(javaClass.classLoader.getResource(path)) { "상위 정의를 읽을 수 없습니다: $name" }
            resource.openStream().use { ClassFile.of().parse(it.readAllBytes()) }.also {
                require(className(it) == name) { "상위 정의 이름 불일치: $name" }
            }
        }
    }
    /** 인터페이스를 포함한 전체 상위 계층. 실제 정의를 못 읽으면 추측하지 않는다. */
    fun ancestors(name: String): Set<String> {
        val visited = sortedSetOf<String>()
        fun visit(current: String, stack: Set<String>) {
            require(current !in stack) { "상위 계층 순환: $current" }
            if (current == "java.lang.Object" || !visited.add(current)) return
            val m = model(current)
            (m.interfaces().map { it.asInternalName().replace('/', '.') } +
                listOfNotNull(m.superclass().orElse(null)?.asInternalName()?.replace('/', '.')))
                .sorted().forEach { visit(it, stack + current) }
        }
        visit(name, emptySet()); visited.remove(name)
        return visited
    }
    private fun annotations(model: ClassModel) = (
        model.findAttribute(Attributes.runtimeVisibleAnnotations()).orElse(null)?.annotations().orEmpty() +
            model.findAttribute(Attributes.runtimeInvisibleAnnotations()).orElse(null)?.annotations().orEmpty()
        ).map { it.classSymbol().descriptorString().removePrefix("L").removeSuffix(";").replace('/', '.') }

    /** 명시한 기술 FQN과 프로젝트 내부 합성 어노테이션만 해석한다. 외부 전체를 수집 대상으로 늘리지 않는다. */
    fun annotations(name: String): Set<String> {
        val result = sortedSetOf<String>()
        fun visit(annotation: String) {
            if (!result.add(annotation)) return
            if (namespaces.any { annotation.startsWith(it) }) {
                val definition = requireNotNull(facts[annotation]) { "내부 합성 어노테이션 정의 누락: $annotation" }
                require(definition.isAnnotation) { "어노테이션 정의 불일치: $annotation" }
                annotations(definition.model).sorted().forEach(::visit)
            }
        }
        annotations(facts.getValue(name).model).sorted().forEach(::visit)
        return result
    }
}
