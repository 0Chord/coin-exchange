package com.exchange.architecture.rules

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaField
import com.tngtech.archunit.core.domain.JavaMethod
import java.io.IOException
import java.net.URI
import java.net.URL
import java.lang.reflect.AccessFlag
import java.lang.classfile.Attributes
import java.lang.classfile.ClassFile
import java.lang.classfile.Signature

/**
 * ArchUnit이 생략한 소유 타입 인자를 원본 Signature에서 보완한다.
 * 수집한 클래스의 원본 파일만 읽으며 클래스를 로드·초기화하거나 메서드를 호출하지 않는다.
 * 읽기 실패는 호출부의 준비 실패로 전파한다.
 */
internal class PortContractBytecode(private val owner: JavaClass) {
    private val model = try {
        val source = requireNotNull(owner.source.orElse(null)) { "클래스 원본 없음: ${owner.name}" }
        source.uri.toURL().openStream().use { ClassFile.of().parse(it.readAllBytes()) }
    } catch (error: IOException) {
        throw IllegalArgumentException("클래스 원본 읽기 실패: ${owner.name}", error)
    }
    private val classSignature = model.findAttribute(Attributes.signature()).orElse(null)?.asClassSignature()
    private val classBounds = bounds(classSignature?.typeParameters().orEmpty())
    // 클래스의 U extends T는 메서드가 T를 다시 선언해도 클래스의 T를 가리킨다.
    private val classTypes = classBounds.keys.associateWith { types(Signature.TypeVarSig.of(it), classBounds) }

    init {
        require(model.thisClass().asInternalName().replace('/', '.') == owner.name) { "클래스 원본 이름 불일치: ${owner.name}" }
    }

    fun classReferences(): Map<String, Set<String>> = buildMap {
        classTypes.forEach { (name, references) -> put("typeParameter[$name]", references) }
        classSignature?.let { signature ->
            put("supertype", (signature.superinterfaceSignatures() + signature.superclassSignature())
                .flatMap { types(it, classBounds) }.toSet())
        }
    }

    fun methodReferences(method: JavaMethod): Map<String, Set<String>> {
        val declaration = model.methods().single { it.methodName().stringValue() == method.name && it.methodType().stringValue() == method.descriptor }
        val signature = declaration.findAttribute(Attributes.signature()).orElse(null)?.asMethodSignature() ?: return emptyMap()
        val methodBounds = bounds(signature.typeParameters())
        return buildMap {
            put("return", types(signature.result(), methodBounds, classTypes))
            signature.arguments().forEachIndexed { index, type -> put("parameter[$index]", types(type, methodBounds, classTypes)) }
            signature.typeParameters().forEach { put("typeParameter[${it.identifier()}]", types(Signature.TypeVarSig.of(it.identifier()), methodBounds, classTypes)) }
            put("throws", signature.throwableSignatures().flatMap { types(it, methodBounds, classTypes) }.toSet())
        }
    }

    fun fieldReferences(field: JavaField): Map<String, Set<String>> {
        val declaration = model.fields().single { it.fieldName().stringValue() == field.name && it.fieldType().stringValue() == field.descriptor }
        val signature = declaration.findAttribute(Attributes.signature()).orElse(null)?.asTypeSignature() ?: return emptyMap()
        return mapOf("field" to types(signature, classBounds))
    }

    fun publicNestedNames(): Set<String> = model.findAttribute(Attributes.innerClasses()).orElse(null)?.classes().orEmpty()
        .filter { it.has(AccessFlag.PUBLIC) && it.outerClass().orElse(null)?.asInternalName()?.replace('/', '.') == owner.name }
        .map { it.innerClass().asInternalName().replace('/', '.') }.toSortedSet()

    /** 같은 클래스 원본 디렉터리 또는 JAR에서 읽어 클래스패스의 다른 복사본으로 누락을 숨기지 않는다. */
    fun nestedUrl(name: String): URL {
        val source = requireNotNull(owner.source.orElse(null)).uri.toString()
        return URI.create(source.substringBeforeLast('/') + "/" + name.substringAfterLast('.') + ".class").toURL()
    }

    private fun bounds(parameters: List<Signature.TypeParam>) = parameters.associate {
        it.identifier() to (listOfNotNull(it.classBound().orElse(null)) + it.interfaceBounds())
    }

    private fun types(
        signature: Signature,
        bounds: Map<String, List<Signature.RefTypeSig>>,
        enclosingTypes: Map<String, Set<String>> = emptyMap(),
        visiting: Set<String> = emptySet(),
    ): Set<String> = when (signature) {
        is Signature.ClassTypeSig -> buildSet {
            add(signature.classDesc().descriptorString().removePrefix("L").removeSuffix(";").replace('/', '.'))
            signature.outerType().ifPresent { addAll(types(it, bounds, enclosingTypes, visiting)) }
            signature.typeArgs().filterIsInstance<Signature.TypeArg.Bounded>().forEach { addAll(types(it.boundType(), bounds, enclosingTypes, visiting)) }
        }
        is Signature.ArrayTypeSig -> types(signature.componentSignature(), bounds, enclosingTypes, visiting)
        is Signature.TypeVarSig -> when (val name = signature.identifier()) {
            !in bounds -> enclosingTypes[name].orEmpty()
            in visiting -> emptySet()
            else -> bounds.getValue(name).flatMap { types(it, bounds, enclosingTypes, visiting + name) }.toSet()
        }
        else -> emptySet()
    }
}
