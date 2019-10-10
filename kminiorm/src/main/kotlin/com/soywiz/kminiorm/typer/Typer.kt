package com.soywiz.kminiorm.typer

import java.math.*
import kotlin.reflect.*
import kotlin.reflect.full.*
import kotlin.reflect.jvm.*

open class Typer private constructor(
    private val keepTypes: Set<KClass<*>> = setOf(),
    private val untypersByClass: Map<KClass<*>, (Any) -> Any> = mapOf(),
    private val typersByClass: Map<KClass<*>, (Any) -> Any> = mapOf()
) {
    constructor() : this(keepTypes = setOf())

    private fun copy(
        keepTypes: Set<KClass<*>> = this.keepTypes,
        untypersByClass: Map<KClass<*>, (Any) -> Any> = this.untypersByClass,
        typersByClass: Map<KClass<*>, (Any) -> Any> = this.typersByClass
    ) = Typer(keepTypes, untypersByClass, typersByClass)

    fun withKeepType(type: KClass<*>) = copy(keepTypes = keepTypes + type)
    inline fun <reified T> withKeepType() = withKeepType(T::class)


    fun <T : Any> withUntyper(clazz: KClass<T>, handler: (T) -> Any?) = copy(untypersByClass = untypersByClass + mapOf(clazz to (handler as (Any) -> Any)))
    fun <T : Any> withTyper(clazz: KClass<T>, handler: (Any) -> T) = copy(typersByClass = typersByClass + mapOf(clazz to (handler as (Any) -> Any)))

    inline fun <reified T : Any> withUntyper(noinline handler: (T) -> Any) = withUntyper(T::class, handler)
    inline fun <reified T : Any> withTyper(noinline handler: (Any) -> T) = withTyper(T::class, handler)

    inline fun <reified T : Any> withTyperUntyper(noinline typer: (Any) -> T, noinline untyper: (T) -> Any) = withTyper(T::class, typer).withUntyper(T::class, untyper)

    fun untype(instance: Any): Any {
        val clazz = instance::class
        if (clazz in keepTypes) return instance
        return when (instance) {
            is Number -> instance
            is String -> instance
            is Map<*, *> -> instance.entries.associate { (key, value) -> key?.let { untype(it) } to value?.let { untype(it) } }
            is Iterable<*> -> instance.map { it?.let { untype(it) } }
            else -> {
                val untyper = untypersByClass[clazz]
                if (untyper != null) {
                    untyper(instance)
                } else {
                    when (instance) {
                        is ByteArray -> instance
                        else -> LinkedHashMap<String, Any?>().also { out -> for (prop in clazz.memberProperties) out[prop.name] = (prop as KProperty1<Any?, Any?>).get(instance)?.let { untype(it) } }
                    }
                }
            }
        }
    }

    fun <T> type(instance: Any, targetType: KType): T = _type(instance, targetType) as T

    private fun _toIterable(instance: Any): Iterable<Any?> {
        if (instance is Iterable<*>) return instance as Iterable<Any?>
        TODO()
    }

    private fun _toMap(instance: Any): Map<Any?, Any?> {
        if (instance is Map<*, *>) return instance as Map<Any?, Any?>
        return instance::class.memberProperties.associate { it.name to (it as KProperty1<Any?, Any?>).get(instance) }
    }

    private fun _type(instance: Any, targetType: KType): Any? {
        val targetClass = targetType.jvmErasure

        return when (targetClass) {
            instance::class -> instance
            Boolean::class -> when (instance) {
                is Boolean -> instance
                is Number -> instance.toDouble() != 0.0
                is String -> instance != ""
                else -> true
            }
            String::class -> instance.toString()
            ByteArray::class -> (instance as? ByteArray) ?: _toIterable(instance).map { (it as Number).toByte() }.toTypedArray()
            IntArray::class -> (instance as? IntArray) ?: _toIterable(instance).map { (it as Number).toInt() }.toTypedArray()
            LongArray::class -> (instance as? LongArray) ?: _toIterable(instance).map { (it as Number).toLong() }.toTypedArray()
            FloatArray::class -> (instance as? FloatArray) ?: _toIterable(instance).map { (it as Number).toFloat() }.toTypedArray()
            DoubleArray::class -> (instance as? DoubleArray) ?: _toIterable(instance).map { (it as Number).toDouble() }.toTypedArray()
            Any::class -> instance
            else -> when {
                targetClass.isSubclassOf(Number::class) -> when (targetClass) {
                    Byte::class -> (instance as? Number)?.toByte() ?: instance.toString().toInt().toByte()
                    Short::class -> (instance as? Number)?.toShort() ?: instance.toString().toInt().toShort()
                    Char::class -> (instance as? Number)?.toChar() ?: instance.toString().toInt().toChar()
                    Int::class -> (instance as? Number)?.toInt() ?: instance.toString().toInt()
                    Long::class -> (instance as? Number)?.toLong() ?: instance.toString().toLong()
                    Float::class -> (instance as? Number)?.toFloat() ?: instance.toString().toFloat()
                    Double::class -> (instance as? Number)?.toDouble() ?: instance.toString().toDouble()
                    BigInteger::class -> (instance as? BigInteger) ?: instance.toString().toBigInteger()
                    BigDecimal::class -> (instance as? BigDecimal) ?: instance.toString().toBigDecimal()
                    else -> TODO()
                }
                targetClass.isSubclassOf(Map::class) -> {
                    val paramKey = targetType.arguments.first().type ?: Any::class.starProjectedType
                    val paramValue = targetType.arguments.last().type ?: Any::class.starProjectedType
                    _toMap(instance).entries.associate { (key, value) ->
                        key?.let { _type(it, paramKey) } to value?.let { _type(it, paramValue) }
                    }
                }
                targetClass.isSubclassOf(Iterable::class) -> {
                    val param = targetType.arguments.first().type ?: Any::class.starProjectedType
                    val info = _toIterable(instance).map { it?.let { type<Any>(it, param) } }
                    when {
                        targetClass.isSubclassOf(List::class) -> info.toMutableList()
                        targetClass.isSubclassOf(Set::class) -> info.toMutableSet()
                        else -> error("Don't know how to convert iterable into $targetClass")
                    }
                }
                else -> {
                    val typer = typersByClass[targetClass]
                    if (typer != null) {
                        typer(instance)
                    } else {
                        val data = _toMap(instance)
                        val constructor = targetClass.primaryConstructor
                                ?: targetClass.constructors.firstOrNull()
                        ?: error("Can't find constructor for $targetClass")
                        val params = constructor.parameters.map {
                            val value = data[it.name]
                            val type = it.type
                            value?.let { _type(value, type) }
                        }
                        val instance = kotlin.runCatching { constructor.call(*params.toTypedArray()) }.getOrNull()
                                ?: error("Can't instantiate object $targetClass")
                        for (prop in targetClass.memberProperties.filterIsInstance<KMutableProperty1<*, *>>()) {
                            kotlin.runCatching { (prop as KMutableProperty1<Any?, Any?>).set(instance, data[prop.name]) }
                        }
                        instance
                    }
                }
            }
        }
    }

    fun <T : Any> type(instance: Any, type: KClass<T>): T = type(instance, type.starProjectedType)

    @UseExperimental(ExperimentalStdlibApi::class)
    inline fun <reified T> type(instance: Any): T = type(instance, typeOf<T>())

    fun createDefault(type: KType): Any? {
        val clazz = type.jvmErasure
        return when (clazz) {
            Unit::class -> Unit
            Float::class -> 0f
            Double::class -> 0.0
            Byte::class -> 0.toByte()
            Short::class -> 0.toShort()
            Char::class -> 0.toChar()
            Int::class -> 0
            Long::class -> 0L
            String::class -> ""
            List::class, ArrayList::class -> arrayListOf<Any?>()
            Map::class, HashMap::class, MutableMap::class -> mutableMapOf<Any?, Any?>()
            else -> {
                val constructor = clazz.constructors.firstOrNull() ?: error("Class $clazz doesn't have constructors")
                constructor.call(*constructor.valueParameters.map { createDefault(it.type) }.toTypedArray())
            }
        }
    }
}