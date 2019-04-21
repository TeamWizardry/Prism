package com.teamwizardry.librarianlib.features.saving.serializers.builtin.special

import com.teamwizardry.librarianlib.features.autoregister.SerializerFactoryRegister
import com.teamwizardry.librarianlib.features.kotlin.readBooleanArray
import com.teamwizardry.librarianlib.features.kotlin.safeCast
import com.teamwizardry.librarianlib.features.kotlin.writeBooleanArray
import com.teamwizardry.librarianlib.features.methodhandles.MethodHandleHelper
import com.teamwizardry.librarianlib.features.saving.*
import com.teamwizardry.librarianlib.features.saving.serializers.*
import io.netty.buffer.ByteBuf
import net.minecraft.nbt.NBTBase
import net.minecraft.nbt.NBTTagCompound
import java.lang.reflect.Constructor
import java.util.*
import kotlin.reflect.jvm.kotlinFunction

/**
 * Created by TheCodeWarrior
 */
@SerializerFactoryRegister
object SerializeObjectFactory : SerializerFactory("Object") {
    override fun canApply(type: FieldType): SerializerFactoryMatch {
        val savable = type.clazz.isAnnotationPresent(Savable::class.java)
        val inplace = inPlaceCheck(type.clazz)
        if (savable || inplace)
            return SerializerFactoryMatch.GENERAL
        else
            return SerializerFactoryMatch.NONE
    }

    fun inPlaceCheck(clazz: Class<*>): Boolean {
        if (clazz.isAnnotationPresent(SaveInPlace::class.java))
            return true
        return clazz.superclass?.let { inPlaceCheck(it) } ?: false
    }

    override fun create(type: FieldType): Serializer<*> {
        return SerializeObject(type, SerializerAnalysis(type))
    }

    class SerializeObject(type: FieldType, val analysis: SerializerAnalysis) : Serializer<Any>(type) {
        override fun getDefault(): Any {
            return analysis.constructorMH(analysis.constructorArgOrder.map {
                analysis.serializers[it]!!.value.getDefault()
            }.toTypedArray())
        }

        override fun readNBT(nbt: NBTBase, existing: Any?, syncing: Boolean): Any {
            val tag = nbt.safeCast(NBTTagCompound::class.java)

            if (analysis.mutable && (existing != null || analysis.constructor.parameters.isEmpty())) {
                val instance = existing ?: analysis.constructorMH(arrayOf())
                readFields(analysis.alwaysFields, tag, instance, syncing)

                if (!syncing) {
                    readFields(analysis.noSyncFields, tag, instance, syncing)
                } else {
                    readFields(analysis.nonPersistentFields, tag, instance, syncing)
                }
                return instance
            } else {
                val args = analysis.constructorArgOrder.map {
                    if (tag.hasKey(it)) {
                        try {
                            analysis.serializers[it]!!.value.read(tag.getTag(it), null, syncing)
                        } catch (e: Throwable) {
                            throw SerializerException("Error reading value for field $it from NBT", e)
                        }
                    } else {
                        null
                    }
                }.toTypedArray()
                try {
                    return analysis.constructorMH(args)
                } catch (e: Throwable) {
                    throw SerializerException("Error creating instance of type $type", e)
                }
            }
        }

        fun readFields(map: Map<String, FieldCache>, tag: NBTTagCompound, instance: Any, sync: Boolean) {
            map.forEach {
                try {
                    val oldValue = it.value.getter(instance)
                    val value = if (tag.hasKey(it.key)) {
                        analysis.serializers[it.key]!!.value.read(tag.getTag(it.key), oldValue, sync)
                    } else {
                        if (it.value.meta.hasFlag(SavingFieldFlag.FINAL)) oldValue else null
                    }
                    if (it.value.meta.hasFlag(SavingFieldFlag.FINAL)) {
                        if (oldValue !== value) {
                            throw SerializerException("Cannot set final field to new value. Either make the field " +
                                    "mutable or modify the serializer to change the existing object instead of " +
                                    "creating a new one.")
                        }
                    } else {
                        it.value.setter(instance, value)
                    }
                } catch (e: Throwable) {
                    throw SerializerException("Error reading field ${it.value.name} from NBT", e)
                }
            }
        }

        override fun writeNBT(value: Any, syncing: Boolean): NBTBase {
            val tag = NBTTagCompound()

            writeFields(analysis.alwaysFields, value, tag, syncing)

            if (!syncing) {
                writeFields(analysis.noSyncFields, value, tag, syncing)
            } else {
                writeFields(analysis.nonPersistentFields, value, tag, syncing)
            }

            return tag
        }

        fun writeFields(map: Map<String, FieldCache>, value: Any, tag: NBTTagCompound, sync: Boolean) {
            map.forEach {
                try {
                    val fieldValue = it.value.getter(value)
                    if (fieldValue != null)
                        tag.setTag(it.key, analysis.serializers[it.key]!!.value.write(fieldValue, sync))
                } catch (e: Throwable) {
                    throw SerializerException("Error writing field ${it.value.name} to NBT", e)
                }
            }
        }

        override fun readBytes(buf: ByteBuf, existing: Any?, syncing: Boolean): Any {
            val nullsig = buf.readBooleanArray()
            val nulliter = nullsig.iterator()
            if (analysis.mutable && (existing != null || analysis.constructor.parameters.isEmpty())) {
                val instance = existing ?: analysis.constructorMH(arrayOf())
                readFields(analysis.alwaysFields, buf, instance, nulliter, syncing)
                if (!syncing) {
                    readFields(analysis.noSyncFields, buf, instance, nulliter, syncing)
                } else {
                    readFields(analysis.nonPersistentFields, buf, instance, nulliter, syncing)
                }
                return instance
            } else {
                val map = mutableMapOf<String, Any?>()

                analysis.alwaysFields.forEach {
                    try {
                        if (!nulliter.next()) {
                            map[it.key] = analysis.serializers[it.key]!!.value.read(buf, null, syncing)
                        }
                    } catch (e: Throwable) {
                        throw SerializerException("Error reading field ${it.value.name} from bytes", e)
                    }
                }
                if (!syncing) {
                    analysis.noSyncFields.forEach {
                        try {
                            if (!nulliter.next()) {
                                map[it.key] = analysis.serializers[it.key]!!.value.read(buf, null, syncing)
                            }
                        } catch (e: Throwable) {
                            throw SerializerException("Error reading field ${it.value.name} from bytes", e)
                        }
                    }
                } else {
                    analysis.nonPersistentFields.forEach {
                        try {
                            if (!nulliter.next()) {
                                map[it.key] = analysis.serializers[it.key]!!.value.read(buf, null, syncing)
                            }
                        } catch (e: Throwable) {
                            throw SerializerException("Error reading field ${it.value.name} from bytes", e)
                        }
                    }
                }
                try {
                    return analysis.constructorMH(analysis.constructorArgOrder.map {
                        map[it]
                    }.toTypedArray())
                } catch (e: Throwable) {
                    throw SerializerException("Error creating instance of type $type", e)
                }
            }
        }

        private fun readFields(map: Map<String, FieldCache>, buf: ByteBuf, instance: Any, nullsig: BooleanIterator, sync: Boolean) {
            map.forEach {
                try {
                    val oldValue = it.value.getter(instance)
                    val value = if (nullsig.next()) {
                        null
                    } else {
                        analysis.serializers[it.key]!!.value.read(buf, oldValue, sync)
                    }
                    if (it.value.meta.hasFlag(SavingFieldFlag.FINAL)) {
                        if (oldValue !== value) {
                            throw SerializerException("Cannot set final field to new value. Either make the field " +
                                    "mutable or modify the serializer to change the existing object instead of " +
                                    "creating a new one.")
                        }
                    } else {
                        it.value.setter(instance, value)
                    }
                } catch (e: Throwable) {
                    throw SerializerException("Error reading field ${it.value.name} from bytes", e)
                }
            }

        }

        override fun writeBytes(buf: ByteBuf, value: Any, syncing: Boolean) {
            var nullsig = mutableListOf<Boolean>()
            analysis.alwaysFields.forEach {
                try {
                    nullsig.add(it.value.getter(value) == null)
                } catch (e: Throwable) {
                    throw SerializerException("Error getting field ${it.value.name} for nullsig", e)
                }
            }
            if (!syncing) {
                analysis.noSyncFields.forEach {
                    try {
                        nullsig.add(it.value.getter(value) == null)
                    } catch (e: Throwable) {
                        throw SerializerException("Error getting field ${it.value.name} for nullsig", e)
                    }
                }
            } else {
                analysis.nonPersistentFields.forEach {
                    try {
                        nullsig.add(it.value.getter(value) == null)
                    } catch (e: Throwable) {
                        throw SerializerException("Error getting field ${it.value.name} for nullsig", e)
                    }
                }
            }

            buf.writeBooleanArray(nullsig.toTypedArray().toBooleanArray())

            analysis.alwaysFields.forEach {
                try {
                    val fieldValue = it.value.getter(value)
                    if (fieldValue != null)
                        analysis.serializers[it.key]!!.value.write(buf, fieldValue, syncing)
                } catch (e: Throwable) {
                    throw SerializerException("Error writing field ${it.value.name} to bytes", e)
                }
            }

            if (!syncing) {
                analysis.noSyncFields.forEach {
                    try {
                        val fieldValue = it.value.getter(value)
                        if (fieldValue != null)
                            analysis.serializers[it.key]!!.value.write(buf, fieldValue, syncing)
                    } catch (e: Throwable) {
                        throw SerializerException("Error writing field ${it.value.name} to bytes", e)
                    }
                }
            } else {
                analysis.nonPersistentFields.forEach {
                    try {
                        val fieldValue = it.value.getter(value)
                        if (fieldValue != null)
                            analysis.serializers[it.key]!!.value.write(buf, fieldValue, syncing)
                    } catch (e: Throwable) {
                        throw SerializerException("Error writing field ${it.value.name} to bytes", e)
                    }
                }
            }
        }
    }
}


class SerializerAnalysis(val type: FieldType) {
    val alwaysFields: Map<String, FieldCache>
    val noSyncFields: Map<String, FieldCache>
    val nonPersistentFields: Map<String, FieldCache>
    val allFieldsOrdered: Map<String, FieldCache>
    val fields: Map<String, FieldCache>

    val mutable: Boolean

    val inPlaceSavable: Boolean

    val constructor: Constructor<*>
    val constructorArgOrder: List<String>
    val constructorMH: (Array<Any?>) -> Any
    val serializers: Map<String, Lazy<Serializer<Any>>>

    init {
        val allFields = mutableMapOf<String, FieldCache>()
        addFieldsRecursive(allFields, type)
        this.fields =
                if (allFields.any { it.value.meta.hasFlag(SavingFieldFlag.ANNOTATED) }) {
                    allFields.filter {
                        it.value.meta.hasFlag(SavingFieldFlag.ANNOTATED)
                    }
                } else if (type.clazz.isAnnotationPresent(Savable::class.java)) {
                    allFields.filter {
                        (it.value.meta.hasFlag(SavingFieldFlag.FIELD) || it.value.meta.hasFlag(SavingFieldFlag.PROPERTY))
                                && !it.value.meta.hasFlag(SavingFieldFlag.TRANSIENT)
                    }
                } else {
                    mapOf<String, FieldCache>()
                }
        inPlaceSavable = SerializeObjectFactory.inPlaceCheck(type.clazz)
        this.mutable = inPlaceSavable || !fields.any { it.value.meta.hasFlag(SavingFieldFlag.FINAL) }
        if (!mutable && fields.any { it.value.meta.hasFlag(SavingFieldFlag.NO_SYNC) })
            throw SerializerException("Immutable type ${type.clazz.canonicalName} cannot have non-syncing fields")

        alwaysFields = fields.filter { !it.value.meta.hasFlag(SavingFieldFlag.NO_SYNC) && !it.value.meta.hasFlag(SavingFieldFlag.NON_PERSISTENT) }
        noSyncFields = fields.filter { it.value.meta.hasFlag(SavingFieldFlag.NO_SYNC) && !it.value.meta.hasFlag(SavingFieldFlag.NON_PERSISTENT) }
        nonPersistentFields = fields.filter { it.value.meta.hasFlag(SavingFieldFlag.NON_PERSISTENT) && !it.value.meta.hasFlag(SavingFieldFlag.NO_SYNC) }
        allFieldsOrdered = alwaysFields + noSyncFields + nonPersistentFields
        constructor =
                if (inPlaceSavable) {
                    nullConstructor
                } else {
                    type.clazz.declaredConstructors.find {
                        val paramsToFind = HashMap(fields)
                        val customParamNames = it.getDeclaredAnnotation(SavableConstructorOrder::class.java)?.params ?:
                        it.kotlinFunction?.parameters?.map { it.name }?.toTypedArray()
                        var i = 0
                        it.parameters.all {
                            val ret =
                                    if (customParamNames != null && i < customParamNames.size)
                                        paramsToFind.remove(customParamNames[i])?.meta?.type?.equals(FieldType.create(it.parameterizedType, it.annotatedType)) ?: false
                                    else
                                        paramsToFind.remove(it.name)?.meta?.type?.equals(FieldType.create(it.parameterizedType, it.annotatedType)) ?: false
                            i++
                            ret
                        }
                    } ?:
                    type.clazz.declaredConstructors.find { it.parameterCount == 0 } ?:
                    throw SerializerException("Couldn't find zero-argument constructor or constructor with parameters (${fields.map { it.value.meta.type.toString() + " " + it.key }.joinToString(", ")}) for immutable type ${type.clazz.canonicalName}")
                }
        constructorArgOrder = constructor.getDeclaredAnnotation(SavableConstructorOrder::class.java)?.params?.asList() ?:
                constructor.kotlinFunction?.parameters?.let { if (it.any { it.name == null }) null else it.map { it.name!! } } ?:
                constructor.parameters.map { it.name }
        constructorMH = if (inPlaceSavable) {
            { _ -> throw SerializerException("Cannot create instance of class marked with @SaveInPlace") }
        } else {
            MethodHandleHelper.wrapperForConstructor(constructor)
        }

        serializers = fields.mapValues {
            val fieldType = it.value.meta.type
            SerializerRegistry.lazy(fieldType)
        }
    }

    private tailrec fun addFieldsRecursive(map: MutableMap<String, FieldCache>, type: FieldType) {
        map.putAll(SavingFieldCache.getClassFields(type))
        if (type.clazz != Any::class.java)
            addFieldsRecursive(map, type.genericSuperclass(type.clazz.superclass))
    }

    companion object {
        val nullConstructor: Constructor<*> = Any::class.java.constructors.first()
    }
}
