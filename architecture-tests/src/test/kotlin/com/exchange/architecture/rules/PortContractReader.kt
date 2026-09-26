package com.exchange.architecture.rules

import com.tngtech.archunit.core.domain.JavaAnnotation
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaModifier.PUBLIC
import com.tngtech.archunit.core.domain.JavaModifier.STATIC
import com.tngtech.archunit.core.domain.JavaType
import com.tngtech.archunit.core.importer.ClassFileImporter
import java.io.IOException

/** 메서드 본문이 아닌 선언에서 읽은 노출 근거. 루트 포트와 실제 선언 소유자는 다를 수 있다. */
internal data class ContractReference(
    val owner: JavaClass,
    val declaration: String,
    val exposure: String,
    val target: String,
    val line: Int?,
)

/** 한 포트의 공개 계약을 읽는다. 순환 상속·반복 탐색은 방문 집합으로 제한한다. */
internal class PortContractReader(
    private val types: Map<String, JavaClass>,
    private val projectPrefixes: Set<String>,
    private val forbidden: (String) -> Boolean,
) {
    val references = mutableListOf<ContractReference>()
    val problems = mutableListOf<PortContractProblem>()
    private val visited = mutableSetOf<Pair<String, Boolean>>()
    private val contracts = mutableSetOf<String>()
    val contractCount: Int get() = contracts.size

    fun read(type: JavaClass, inherited: Boolean = false) {
        // 상위로 읽었던 타입도 공개 중첩 계약으로 다시 노출되면 자신의 static을 검사한다.
        if (!visited.add(type.name to inherited)) return
        // ArchUnit이 클래스패스에서 해석했어도 프로젝트 내부 정의는 운영 출력에 실제 있어야 한다.
        if (!type.isFullyImported || (projectPrefixes.any { type.name.startsWith(it) } && type.name !in types)) {
            problems += PortContractProblem("UNRESOLVED_PORT_CONTRACT", type.name)
            return
        }
        val bytecode = PortContractBytecode(type)
        supplement(type, type.name, bytecode.classReferences())
        annotations(type, type.name, "annotation", type.annotations)
        type.typeParameters.forEach { reference(type, type.name, "typeParameter[${it.name}]", it) }
        val parents = type.interfaces + listOfNotNull(type.superclass.orElse(null)).filter { it.toErasure().name != "java.lang.Object" }
        parents.forEach { parent ->
            contracts += "${type.name}:supertype:${parent.name}"
            reference(type, type.name, "supertype", parent)
            val raw = parent.toErasure()
            // 금지된 상위 타입은 이름만으로 위반 근거가 충분하므로 그 라이브러리 전체를 펼치지 않는다.
            if (!forbidden(raw.name)) read(types[raw.name] ?: raw, inherited = true)
        }
        // 인터페이스 static은 상속되지 않는다. 루트·공개 중첩 타입 자신의 선언은 포함한다.
        type.methods.filter { PUBLIC in it.modifiers && !(inherited && type.isInterface && STATIC in it.modifiers) }.forEach { method ->
            contracts += method.fullName
            val line = method.sourceCodeLocation.lineNumber.takeIf { it > 0 }
            supplement(type, method.fullName, bytecode.methodReferences(method), line)
            reference(type, method.fullName, "return", method.returnType, line)
            method.parameters.forEach { parameter ->
                reference(type, method.fullName, "parameter[${parameter.index}]", parameter.type, line)
                annotations(type, method.fullName, "parameter[${parameter.index}].annotation", parameter.annotations, line)
            }
            method.typeParameters.forEach { reference(type, method.fullName, "typeParameter[${it.name}]", it, line) }
            method.throwsClause.types.forEach { reference(type, method.fullName, "throws", it, line) }
            annotations(type, method.fullName, "annotation", method.annotations, line)
        }
        type.fields.filter { PUBLIC in it.modifiers }.forEach { field ->
            contracts += field.fullName
            val line = field.sourceCodeLocation.lineNumber.takeIf { it > 0 }
            supplement(type, field.fullName, bytecode.fieldReferences(field), line)
            reference(type, field.fullName, "field", field.type, line)
            annotations(type, field.fullName, "annotation", field.annotations, line)
        }
        // 운영 출력에 없어도 원본의 public 포함 관계를 읽는다. 내부 누락은 자동 수집으로 대체하지 않는다.
        bytecode.publicNestedNames().forEach { name ->
            val nested = types[name] ?: if (projectPrefixes.any { name.startsWith(it) }) null else loadNested(bytecode, name)
            if (nested == null) problems += PortContractProblem("UNRESOLVED_PORT_CONTRACT", name) else read(nested)
        }
    }

    private fun loadNested(bytecode: PortContractBytecode, name: String): JavaClass? = try {
        val url = bytecode.nestedUrl(name)
        // importer가 파일 누락을 빈 목록으로 처리하기 전에 실제 원본의 존재를 확인한다.
        url.openStream().use { }
        ClassFileImporter().importUrl(url).firstOrNull { it.name == name }
    } catch (_: IOException) { null }

    private fun supplement(owner: JavaClass, declaration: String, exposedTypes: Map<String, Set<String>>, line: Int? = null) {
        exposedTypes.forEach { (exposure, targets) -> targets.forEach { target ->
            references += ContractReference(owner, declaration, exposure, target, line)
        } }
    }

    private fun reference(owner: JavaClass, declaration: String, exposure: String, type: JavaType, line: Int? = null) {
        // 타입 인자·배열 원소·상하한은 읽지만, 임의 DTO의 필드는 펼치지 않는다.
        type.allInvolvedRawTypes.forEach { raw ->
            references += ContractReference(owner, declaration, exposure, raw.baseComponentType.name, line)
        }
    }

    private fun annotations(owner: JavaClass, declaration: String, exposure: String,
        annotations: Collection<JavaAnnotation<*>>, line: Int? = null) {
        annotations.forEach { reference(owner, declaration, exposure, it.rawType, line) }
    }
}
