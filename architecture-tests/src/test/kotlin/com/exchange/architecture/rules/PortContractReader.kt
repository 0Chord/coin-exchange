package com.exchange.architecture.rules

import com.tngtech.archunit.core.domain.JavaAnnotation
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaModifier.PUBLIC
import com.tngtech.archunit.core.domain.JavaType

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
    private val visited = mutableSetOf<String>()
    private val contracts = mutableSetOf<String>()
    val contractCount: Int get() = contracts.size

    fun read(type: JavaClass) {
        if (!visited.add(type.name)) return
        // ArchUnit이 클래스패스에서 해석했어도 프로젝트 내부 정의는 운영 출력에 실제 있어야 한다.
        if (!type.isFullyImported || (projectPrefixes.any { type.name.startsWith(it) } && type.name !in types)) {
            problems += PortContractProblem("UNRESOLVED_PORT_CONTRACT", type.name)
            return
        }
        annotations(type, type.name, "annotation", type.annotations)
        type.typeParameters.forEach { reference(type, type.name, "typeParameter[${it.name}]", it) }
        val parents = type.interfaces + listOfNotNull(type.superclass.orElse(null)).filter { it.toErasure().name != "java.lang.Object" }
        parents.forEach { parent ->
            contracts += "${type.name}:supertype:${parent.name}"
            reference(type, type.name, "supertype", parent)
            val raw = parent.toErasure()
            // 금지된 상위 타입은 이름만으로 위반 근거가 충분하므로 그 라이브러리 전체를 펼치지 않는다.
            if (!forbidden(raw.name)) read(types[raw.name] ?: raw)
        }
        type.methods.filter { PUBLIC in it.modifiers }.forEach { method ->
            contracts += method.fullName
            val line = method.sourceCodeLocation.lineNumber.takeIf { it > 0 }
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
            reference(type, field.fullName, "field", field.type, line)
            annotations(type, field.fullName, "annotation", field.annotations, line)
        }
        // public 포함 관계만 따라가며 private 보조 타입이나 이름 패턴으로 범위를 넓히지 않는다.
        types.values.filter { it.enclosingClass.orElse(null)?.name == type.name && PUBLIC in it.modifiers }
            .sortedBy { it.name }.forEach(::read)
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
