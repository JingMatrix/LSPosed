package org.matrix.vector.impl.utils

import java.io.IOException
import java.nio.ByteBuffer
import org.matrix.vector.api.utils.DexParser
import org.matrix.vector.api.utils.DexParser.*
import org.matrix.vector.nativebridge.DexParserBridge

/**
 * Kotlin implementation of [DexParser] for Vector.
 *
 * This class acts as a high-level wrapper around the native C++ DexParser. It maps raw JNI data
 * structures (integer arrays, flat buffers) into usable object graphs (StringId, TypeId, MethodId,
 * etc.).
 */
@Suppress("UNCHECKED_CAST")
class VectorDexParser(buffer: ByteBuffer, includeAnnotations: Boolean) : DexParser {

    private var cookie: Long = 0
    private val data: ByteBuffer

    // Properties directly override the interface properties.
    // Kotlin automatically generates private backing fields for these.
    override val stringId: Array<StringId>
    override val typeId: Array<TypeId>
    override val protoId: Array<ProtoId>
    override val fieldId: Array<FieldId>
    override val methodId: Array<MethodId>
    override val annotations: Array<DexParser.Annotation>
    override val arrays: Array<DexParser.Array>

    init {
        // Ensure the buffer is Direct and accessible by native code
        data =
            if (!buffer.isDirect || !buffer.asReadOnlyBuffer().hasArray()) {
                ByteBuffer.allocateDirect(buffer.capacity()).apply {
                    put(buffer)
                    // Ensure position is reset for reading if needed,
                    // though native uses address
                    flip()
                }
            } else {
                buffer
            }

        try {
            val args = LongArray(2)
            args[1] = if (includeAnnotations) 1L else 0L

            // Call Native Bridge
            // Returns a raw Object[] containing headers and pools
            val out = DexParserBridge.openDex(data, args) as Array<Any?>
            cookie = args[0]

            // --- Parse Strings (Index 0) ---
            val rawStrings = out[0] as Array<String>
            stringId = Array(rawStrings.size) { i -> VectorStringId(i, rawStrings[i]) }

            // --- Parse Type IDs (Index 1) ---
            val rawTypeIds = out[1] as IntArray
            typeId = Array(rawTypeIds.size) { i -> VectorTypeId(i, rawTypeIds[i]) }

            // --- Parse Proto IDs (Index 2) ---
            val rawProtoIds = out[2] as Array<IntArray>
            protoId = Array(rawProtoIds.size) { i -> VectorProtoId(i, rawProtoIds[i]) }

            // --- Parse Field IDs (Index 3) ---
            val rawFieldIds = out[3] as IntArray
            // Each field is represented by 3 integers (class_idx, type_idx, name_idx)
            fieldId =
                Array(rawFieldIds.size / 3) { i ->
                    VectorFieldId(
                        i,
                        rawFieldIds[3 * i],
                        rawFieldIds[3 * i + 1],
                        rawFieldIds[3 * i + 2],
                    )
                }

            // --- Parse Method IDs (Index 4) ---
            val rawMethodIds = out[4] as IntArray
            // Each method is represented by 3 integers (class_idx, proto_idx, name_idx)
            methodId =
                Array(rawMethodIds.size / 3) { i ->
                    VectorMethodId(
                        i,
                        rawMethodIds[3 * i],
                        rawMethodIds[3 * i + 1],
                        rawMethodIds[3 * i + 2],
                    )
                }

            // --- Parse Annotations (Index 5 & 6) ---
            val rawAnnotationMetadata = out[5] as? IntArray
            val rawAnnotationValues = out[6] as? Array<Any?>

            annotations =
                if (rawAnnotationMetadata != null && rawAnnotationValues != null) {
                    Array(rawAnnotationMetadata.size / 2) { i ->
                        // Metadata:[visibility, type_idx]
                        // Values: [name_indices[], values[]]
                        val elementsMeta = rawAnnotationValues[2 * i] as IntArray
                        val elementsData = rawAnnotationValues[2 * i + 1] as Array<Any?>
                        VectorAnnotation(
                            rawAnnotationMetadata[2 * i],
                            rawAnnotationMetadata[2 * i + 1],
                            elementsMeta,
                            elementsData,
                        )
                    }
                } else {
                    emptyArray()
                }

            // --- Parse Arrays (Index 7) ---
            val rawArrays = out[7] as? Array<Any?>
            arrays =
                if (rawArrays != null) {
                    Array(rawArrays.size / 2) { i ->
                        val types = rawArrays[2 * i] as IntArray
                        val values = rawArrays[2 * i + 1] as Array<Any?>
                        VectorArray(types, values)
                    }
                } else {
                    emptyArray()
                }
        } catch (e: Throwable) {
            throw IOException("Invalid dex file", e)
        }
    }

    @Synchronized
    override fun close() {
        if (cookie != 0L) {
            DexParserBridge.closeDex(cookie)
            cookie = 0L
        }
    }

    override fun visitDefinedClasses(visitor: ClassVisitor) {
        if (cookie == 0L) {
            throw IllegalStateException("Closed")
        }

        // Accessing [0] is fragile
        val classVisitMethod = ClassVisitor::class.java.declaredMethods[0]
        val fieldVisitMethod = FieldVisitor::class.java.declaredMethods[0]
        val methodVisitMethod = MethodVisitor::class.java.declaredMethods[0]
        val methodBodyVisitMethod = MethodBodyVisitor::class.java.declaredMethods[0]
        val stopMethod = EarlyStopVisitor::class.java.declaredMethods[0]

        DexParserBridge.visitClass(
            cookie,
            visitor,
            FieldVisitor::class.java,
            MethodVisitor::class.java,
            classVisitMethod,
            fieldVisitMethod,
            methodVisitMethod,
            methodBodyVisitMethod,
            stopMethod,
        )
    }

    /** Base implementation for all Dex IDs. */
    private open class VectorId<Self : Id<Self>>(override val id: Int) : Id<Self> {
        override fun compareTo(other: Self): Int = id - other.id
    }

    private inner class VectorStringId(id: Int, override val string: String) :
        VectorId<StringId>(id), StringId

    private inner class VectorTypeId(id: Int, descriptorIdx: Int) : VectorId<TypeId>(id), TypeId {
        override val descriptor: StringId = stringId[descriptorIdx]
    }

    private inner class VectorProtoId(id: Int, protoData: IntArray) :
        VectorId<ProtoId>(id), ProtoId {

        override val shorty: StringId = stringId[protoData[0]]
        override val returnType: TypeId = typeId[protoData[1]]
        override val parameters: Array<TypeId>? =
            if (protoData.size > 2) {
                // protoData format:[shorty_idx, return_type_idx, param1_idx, param2_idx...]
                Array(protoData.size - 2) { i -> typeId[protoData[i + 2]] }
            } else {
                null
            }
    }

    private inner class VectorFieldId(id: Int, classIdx: Int, typeIdx: Int, nameIdx: Int) :
        VectorId<FieldId>(id), FieldId {

        override val declaringClass: TypeId = typeId[classIdx]
        override val type: TypeId = typeId[typeIdx]
        override val name: StringId = stringId[nameIdx]
    }

    private inner class VectorMethodId(id: Int, classIdx: Int, protoIdx: Int, nameIdx: Int) :
        VectorId<MethodId>(id), MethodId {

        override val declaringClass: TypeId = typeId[classIdx]
        override val prototype: ProtoId = protoId[protoIdx]
        override val name: StringId = stringId[nameIdx]
    }

    private class VectorArray(elementsTypes: IntArray, valuesData: Array<Any?>) : DexParser.Array {
        override val values: Array<Value> =
            Array(valuesData.size) { i ->
                VectorValue(elementsTypes[i], valuesData[i] as? ByteBuffer)
            }
    }

    private inner class VectorAnnotation(
        override val visibility: Int,
        typeIdx: Int,
        elementNameIndices: IntArray,
        elementValues: Array<Any?>,
    ) : DexParser.Annotation {

        override val type: TypeId = typeId[typeIdx]
        override val elements: Array<Element> =
            Array(elementValues.size) { i ->
                // Flattened structure from JNI: names are at 2*i, types at 2*i+1
                VectorElement(
                    elementNameIndices[i * 2],
                    elementNameIndices[i * 2 + 1], // valueType
                    elementValues[i] as? ByteBuffer,
                )
            }
    }

    private open class VectorValue(override val valueType: Int, buffer: ByteBuffer?) : Value {

        override val value: ByteArray? =
            buffer?.let {
                val bytes = ByteArray(it.remaining())
                it.get(bytes)
                bytes
            }
    }

    private inner class VectorElement(nameIdx: Int, valueType: Int, buffer: ByteBuffer?) :
        VectorValue(valueType, buffer), Element {

        override val name: StringId = stringId[nameIdx]
    }
}
