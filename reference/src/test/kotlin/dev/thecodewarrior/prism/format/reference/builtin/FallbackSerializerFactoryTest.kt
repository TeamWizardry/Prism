package dev.thecodewarrior.prism.format.reference.builtin

import dev.thecodewarrior.mirror.Mirror
import dev.thecodewarrior.prism.DeserializationException
import dev.thecodewarrior.prism.Prism
import dev.thecodewarrior.prism.SerializerNotFoundException
import dev.thecodewarrior.prism.format.reference.ReferencePrism
import dev.thecodewarrior.prism.format.reference.ReferenceSerializer
import dev.thecodewarrior.prism.format.reference.format.LeafNode
import dev.thecodewarrior.prism.format.reference.format.ObjectNode
import dev.thecodewarrior.prism.format.reference.testsupport.PrismTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class FallbackSerializerFactoryTest: PrismTest() {
    override fun createPrism(): ReferencePrism = Prism<ReferenceSerializer<*>>().also { prism ->
        prism.register(FallbackSerializerFactory(prism))
    }

    @Test
    fun getSerializer_withObject_shouldReturnFallback() {
        assertEquals(FallbackSerializerFactory.FallbackSerializer::class.java, prism[Mirror.types.any].value.javaClass)
    }

    @Test
    fun getSerializer_withPrimitive_shouldThrow() {
        assertThrows<SerializerNotFoundException> {
            prism[Mirror.types.int]
        }
    }

    @Test
    fun serialize_withObject_shouldReturnLeaf() {
        val theObject = Any()
        val leaf = prism[Mirror.types.any].value.write(theObject)
        assertEquals(LeafNode(FallbackValue(theObject)), leaf)
    }

    @Test
    fun deserialize_withLeaf_shouldReturnObject() {
        val theObject = Any()
        val theLeaf = LeafNode(FallbackValue(theObject))
        val value = prism[Mirror.types.any].value.read(theLeaf, null)
        assertEquals(theObject, value)
    }

    @Test
    fun deserialize_withWrongNodeType_shouldThrow() {
        val theObject = Any()
        val theLeaf = ObjectNode()
        assertThrows<DeserializationException> {
            prism[Mirror.types.any].value.read(theLeaf, null)
        }
    }
}