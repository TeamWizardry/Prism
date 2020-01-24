package dev.thecodewarrior.librarianlib.features.saving.serializers.builtin.core

import dev.thecodewarrior.librarianlib.core.LibrarianLog
import dev.thecodewarrior.librarianlib.features.autoregister.SerializerFactoryRegister
import dev.thecodewarrior.librarianlib.features.kotlin.readString
import dev.thecodewarrior.librarianlib.features.kotlin.safeCast
import dev.thecodewarrior.librarianlib.features.kotlin.withRealDefault
import dev.thecodewarrior.librarianlib.features.kotlin.writeString
import dev.thecodewarrior.librarianlib.features.saving.Dyn
import dev.thecodewarrior.librarianlib.features.saving.FieldType
import dev.thecodewarrior.librarianlib.features.saving.FieldTypeGeneric
import dev.thecodewarrior.librarianlib.features.saving.serializers.Serializer
import dev.thecodewarrior.librarianlib.features.saving.serializers.SerializerFactory
import dev.thecodewarrior.librarianlib.features.saving.serializers.SerializerFactoryMatch
import dev.thecodewarrior.librarianlib.features.saving.serializers.SerializerRegistry
import io.netty.buffer.ByteBuf
import net.minecraft.nbt.NBTBase
import net.minecraft.nbt.NBTTagCompound

/**
 * TODO: Document file Dynamic
 *
 * Created by TheCodeWarrior
 */

@SerializerFactoryRegister
object SerializeDynamicFactory : SerializerFactory("Dynamic") {
    override fun canApply(type: FieldType): SerializerFactoryMatch {
        if (type.annotations.any { it is Dyn }) {
            if (type is FieldTypeGeneric) {
                throw RuntimeException("Generic types cannot be annotated with @Dyn, as the generic type information is lost at runtime")
            }
            return SerializerFactoryMatch.WRAPPER
        }
        return SerializerFactoryMatch.NONE
    }

    override fun create(type: FieldType): Serializer<*> {
        return SerializeDynamic(type)
    }

    class SerializeDynamic(type: FieldType) : Serializer<Any>(type) {

        private val alreadyWarnedTypes = mutableSetOf<String>()

        val defaultSerializer: Serializer<Any> by SerializerRegistry.lazy(type, SerializeDynamicFactory)
        val serializers = mutableMapOf<String, Serializer<Any>>().withRealDefault {
            try {
                val clazz = Class.forName(it)
                if (!type.clazz.isAssignableFrom(clazz))
                    throw RuntimeException("Specified type $it is not assignable to field type $type")
                if (clazz.isArray)
                    throw RuntimeException("Arrays cannot be annotated with @Dyn, only their component types.")
                return@withRealDefault SerializerRegistry.getOrCreate(FieldType.create(clazz))
            } catch (e: ClassNotFoundException) {
                if (it !in alreadyWarnedTypes) {
                    alreadyWarnedTypes.add(it)
                    LibrarianLog.warn("Attempt to find the class $it for dynamically serialized type $type failed." +
                            " Attempting to use default serializer. This is a major problem, and likely will cause strange" +
                            " bugs and crashes.")
                }
                return@withRealDefault defaultSerializer
            }
        }

        override fun getDefault(): Any {
            return defaultSerializer.getDefault()
        }

        private fun serializerFor(className: String): Serializer<Any> {
            if (className == "")
                return defaultSerializer
            else
                return serializers[className]
        }

        override fun readNBT(nbt: NBTBase, existing: Any?, syncing: Boolean): Any {
            val wrapper = nbt.safeCast(NBTTagCompound::class.java)

            val className = wrapper.getString("class")

            val ser = serializerFor(className)

            if (wrapper.hasKey("data"))
                return ser.read(wrapper.getTag("data"), existing, syncing)
            else
                return ser.read(nbt, existing, syncing) // to facilitate transitions to @Dyn
        }

        override fun writeNBT(value: Any, syncing: Boolean): NBTBase {
            val wrapper = NBTTagCompound()
            val className = value.javaClass.canonicalName
            wrapper.setString("class", className)

            wrapper.setTag("data", serializerFor(className).write(value, syncing))

            return wrapper
        }

        override fun readBytes(buf: ByteBuf, existing: Any?, syncing: Boolean): Any {
            val className = buf.readString()

            return serializerFor(className).read(buf, existing, syncing)
        }

        override fun writeBytes(buf: ByteBuf, value: Any, syncing: Boolean) {
            val className = value.javaClass.canonicalName
            buf.writeString(className)

            serializerFor(className).write(buf, value, syncing)
        }
    }
}
