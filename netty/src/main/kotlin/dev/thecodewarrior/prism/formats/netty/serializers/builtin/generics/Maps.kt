package dev.thecodewarrior.librarianlib.features.saving.serializers.builtin.generics

import dev.thecodewarrior.librarianlib.features.autoregister.SerializerFactoryRegister
import dev.thecodewarrior.librarianlib.features.kotlin.*
import dev.thecodewarrior.librarianlib.features.methodhandles.MethodHandleHelper
import dev.thecodewarrior.librarianlib.features.saving.FieldType
import dev.thecodewarrior.librarianlib.features.saving.serializers.Serializer
import dev.thecodewarrior.librarianlib.features.saving.serializers.SerializerFactory
import dev.thecodewarrior.librarianlib.features.saving.serializers.SerializerFactoryMatch
import dev.thecodewarrior.librarianlib.features.saving.serializers.SerializerRegistry
import io.netty.buffer.ByteBuf
import net.minecraft.nbt.NBTBase
import net.minecraft.nbt.NBTTagCompound
import net.minecraft.nbt.NBTTagList
import net.minecraftforge.fml.relauncher.ReflectionHelper
import java.util.*

/**
 * Created by TheCodeWarrior
 */
@SerializerFactoryRegister
object SerializeMapFactory : SerializerFactory("Map") {
    override fun canApply(type: FieldType): SerializerFactoryMatch {
        return this.canApplySubclass(type, Map::class.java)
    }

    override fun create(type: FieldType): Serializer<*> {
        return SerializeMap(type, type.resolveGeneric(Map::class.java, 0), type.resolveGeneric(Map::class.java, 1))
    }

    class SerializeMap(type: FieldType, val keyType: FieldType, val valueType: FieldType) : Serializer<MutableMap<Any?, Any?>>(type) {
        override fun getDefault(): MutableMap<Any?, Any?> {
            return constructor()
        }

        val serKey: Serializer<Any> by SerializerRegistry.lazy(keyType)
        val serValue: Serializer<Any> by SerializerRegistry.lazy(valueType)

        val constructor = createConstructorMethodHandle()

        override fun readNBT(nbt: NBTBase, existing: MutableMap<Any?, Any?>?, syncing: Boolean): MutableMap<Any?, Any?> {
            val list = nbt.safeCast<NBTTagList>()
            val map = existing ?: getDefault()
            map.clear()

            list.forEach<NBTTagCompound> {
                val keyTag = it.getTag("k")
                val valTag = it.getTag("v")
                val k = serKey.read(keyTag, null, syncing)
                val v = serValue.read(valTag, existing?.get(k), syncing)
                map[k] = v
            }

            return map
        }

        override fun writeNBT(value: MutableMap<Any?, Any?>, syncing: Boolean): NBTBase {
            val list = NBTTagList()

            for (k in value.keys) {
                val container = NBTTagCompound()
                list.appendTag(container)
                val v = value[k]

                if (k != null) {
                    container.setTag("k", serKey.write(k, syncing))
                }
                if (v != null) {
                    container.setTag("v", serValue.write(v, syncing))
                }
            }

            return list
        }

        override fun readBytes(buf: ByteBuf, existing: MutableMap<Any?, Any?>?, syncing: Boolean): MutableMap<Any?, Any?> {
            val map = existing ?: getDefault()
            map.clear()

            val count = buf.readVarInt()
            val nullKey = buf.readVarInt()
            val valueNulls = buf.readBooleanArray()

            for (i in 0..count - 1) {
                val k = if (i == nullKey) null else serKey.read(buf, null, syncing)
                val v = if (valueNulls[i]) null else serValue.read(buf, existing?.get(k), syncing)
                map[k] = v
            }

            return map
        }

        override fun writeBytes(buf: ByteBuf, value: MutableMap<Any?, Any?>, syncing: Boolean) {

            val keys = value.keys.toMutableList()
            val values = value.values.toMutableList()

            val valueNulls = values.map { it == null }.toTypedArray().toBooleanArray()

            val count = value.size

            buf.writeVarInt(count)
            buf.writeVarInt(keys.indexOf(null))

            buf.writeBooleanArray(valueNulls)

            for (i in 0..count - 1) {
                val k = keys[i]
                val v = values[i]

                if (k != null) {
                    serKey.write(buf, k, syncing)
                }

                if (v != null) {
                    serValue.write(buf, v, syncing)
                }
            }
        }

        private fun createConstructorMethodHandle(): () -> MutableMap<Any?, Any?> {
            if (type.clazz == Map::class.java) {
                return { LinkedHashMap<Any?, Any?>() }
            } else if (type.clazz == EnumMap::class.java) {
                @Suppress("UNCHECKED_CAST")
                return { RawTypeConstructors.createEnumMap(keyType.clazz) as MutableMap<Any?, Any?> }
            } else {
                try {
                    val mh = MethodHandleHelper.wrapperForConstructor<MutableMap<Any?, Any?>>(type.clazz)
                    return { mh(arrayOf()) }
                } catch(e: ReflectionHelper.UnableToFindMethodException) {
                    return { throw UnsupportedOperationException("Could not find zero-argument constructor for " +
                            type.clazz.simpleName, e) }
                }
            }
        }
    }
}
