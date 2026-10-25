package com.exchange.core.trading

import java.lang.reflect.Modifier
import java.time.Instant

/** 차단 후 공개 snapshot 대신 실제 소유 필드를 값으로 복사하는 테스트 전용 관찰기. */
internal object TradingStateFailureTestSupport {
    fun field(
        owner: Any,
        name: String,
    ) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }

    fun read(
        owner: Any,
        name: String,
    ): Any = checkNotNull(field(owner, name).get(owner))

    fun write(
        owner: Any,
        name: String,
        value: Any,
    ) = field(owner, name).set(owner, value)

    /** 가변 book의 잔량까지 새 값으로 만들므로, 이후 내부 쓰기가 관측값을 함께 바꾸지 않는다. */
    fun actualValues(owner: Any): Any? = freeze(owner)

    @Suppress("UNCHECKED_CAST")
    fun map(
        owner: Any,
        name: String,
    ): MutableMap<Any, Any> = read(owner, name) as MutableMap<Any, Any>

    @Suppress("UNCHECKED_CAST")
    fun set(
        owner: Any,
        name: String,
    ): MutableSet<Any> = read(owner, name) as MutableSet<Any>

    class WriteProbe {
        var count: Int = 0
            private set

        var writtenKey: Any? = null
            private set

        fun wrote(key: Any) {
            count += 1
            writtenKey = key
        }
    }

    /** 위임 대상의 put이 끝난 뒤 표지를 남기고 동일한 예외 객체를 전달한다. */
    fun failAfterPut(
        owner: Any,
        name: String,
        failure: Throwable,
    ): WriteProbe {
        val original = map(owner, name)
        val probe = WriteProbe()
        val failing =
            object : MutableMap<Any, Any> by original {
                override fun put(
                    key: Any,
                    value: Any,
                ): Any? {
                    original[key] = value
                    probe.wrote(key)
                    throw failure
                }
            }
        write(owner, name, failing)
        return probe
    }

    /** 이미 있는 마켓의 실제 seen-ID set을 감싸 첫 add 뒤 실패시킨다. */
    @Suppress("UNCHECKED_CAST")
    fun failAfterNestedSetAdd(
        owner: Any,
        name: String,
        key: Any,
        failure: Throwable,
    ): WriteProbe {
        val parent = map(owner, name)
        val original = checkNotNull(parent[key]) as MutableSet<Any>
        val probe = WriteProbe()
        parent[key] =
            object : MutableSet<Any> by original {
                override fun add(element: Any): Boolean {
                    check(original.add(element)) { "실패 표지는 신규 실제 쓰기에만 남깁니다" }
                    probe.wrote(element)
                    throw failure
                }
            }
        return probe
    }

    private data class FrozenObject(
        val type: String,
        val fields: Map<String, Any?>,
    )

    private fun freeze(value: Any?): Any? =
        when (value) {
            null, is String, is Number, is Boolean, is Char, is Enum<*>, is Instant -> {
                value
            }

            is Map<*, *> -> {
                value.entries.map { freeze(it.key) to freeze(it.value) }
            }

            is Set<*> -> {
                value.map(::freeze).toSet()
            }

            is Collection<*> -> {
                value.map(::freeze)
            }

            else -> {
                check(value.javaClass.name.startsWith("com.exchange.")) { "관측하지 못한 타입: ${value.javaClass.name}" }
                val fields =
                    value.javaClass.declaredFields
                        .filterNot {
                            Modifier.isStatic(it.modifiers) || it.isSynthetic ||
                                it.name in setOf("isBlocked", "calculator", "admissionCalculator", "settlementCalculator", "planToken")
                        }.associate {
                            it.isAccessible = true
                            it.name to freeze(it.get(value))
                        }
                FrozenObject(value.javaClass.name, fields)
            }
        }
}
