package org.matrix.vector.impl.utils

import io.github.libxposed.api.utils.DexParser
import io.github.libxposed.api.utils.DexParser.*
import java.io.IOException
import java.nio.ByteBuffer
import org.lsposed.lspd.nativebridge.DexParserBridge

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

    // Internal storage for parsed DEX structures.
    // We use private properties and explicit getter methods as requested.
    private val internalStrings: Array<StringId>
    private val internalTypeIds: Array<TypeId>
    private val internalProtoIds: Array<ProtoId>
    private val internalFieldIds: Array<FieldId>
    private val internalMethodIds: Array<MethodId>
    private val internalAnnotations: Array<DexParser.Annotation>
    private val internalArrays: Array<DexParser.Array>

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
            args[1] = if (includeAnnotations) 1 else 0

            // Call Native Bridge
            // Returns a raw Object[] containing headers and pools
            val out = DexParserBridge.openDex(data, args) as Array<Any?>
            cookie = args[0]

            // --- Parse Strings (Index 0) ---
            val rawStrings = out[0] as Array<String>
            internalStrings = Array(rawStrings.size) { i -> VectorStringId(i, rawStrings[i]) }

            // --- Parse Type IDs (Index 1) ---
            val rawTypeIds = out[1] as IntArray
            internalTypeIds = Array(rawTypeIds.size) { i -> VectorTypeId(i, rawTypeIds[i]) }

            // --- Parse Proto IDs (Index 2) ---
            val rawProtoIds = out[2] as Array<IntArray>
            internalProtoIds = Array(rawProtoIds.size) { i -> VectorProtoId(i, rawProtoIds[i]) }

            // --- Parse Field IDs (Index 3) ---
            val rawFieldIds = out[3] as IntArray
            // Each field is represented by 3 integers (class_idx, type_idx, name_idx)
            internalFieldIds =
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
            internalMethodIds =
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

            internalAnnotations =
                if (rawAnnotationMetadata != null && rawAnnotationValues != null) {
                    Array(rawAnnotationMetadata.size / 2) { i ->
                        // Metadata: [visibility, type_idx]
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
            internalArrays =
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
            cookie = 0
        }
    }

    override fun getStringId(): Array<StringId> = internalStrings

    override fun getTypeId(): Array<TypeId> = internalTypeIds

    override fun getFieldId(): Array<FieldId> = internalFieldIds

    override fun getMethodId(): Array<MethodId> = internalMethodIds

    override fun getProtoId(): Array<ProtoId> = internalProtoIds

    override fun getAnnotations(): Array<DexParser.Annotation> = internalAnnotations

    override fun getArrays(): Array<DexParser.Array> = internalArrays

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
    private open class VectorId<Self : Id<Self>>(private val id: Int) : Id<Self> {
        override fun getId(): Int = id

        override fun compareTo(other: Self): Int = id - other.id
    }

    private inner class VectorStringId(id: Int, private val string: String) :
        VectorId<StringId>(id), StringId {
        override fun getString(): String = string
    }

    private inner class VectorTypeId(id: Int, descriptorIdx: Int) : VectorId<TypeId>(id), TypeId {
        private val descriptor: StringId = internalStrings[descriptorIdx]

        override fun getDescriptor(): StringId = descriptor
    }

    private inner class VectorProtoId(id: Int, protoData: IntArray) :
        VectorId<ProtoId>(id), ProtoId {

        private val shorty: StringId = internalStrings[protoData[0]]
        private val returnType: TypeId = internalTypeIds[protoData[1]]
        private val parameters: Array<TypeId>?

        init {
            if (protoData.size > 2) {
                // protoData format: [shorty_idx, return_type_idx, param1_idx, param2_idx...]
                parameters = Array(protoData.size - 2) { i -> internalTypeIds[protoData[i + 2]] }
            } else {
                parameters = null
            }
        }

        override fun getShorty(): StringId = shorty

        override fun getReturnType(): TypeId = returnType

        override fun getParameters(): Array<TypeId>? = parameters
    }

    private inner class VectorFieldId(id: Int, classIdx: Int, typeIdx: Int, nameIdx: Int) :
        VectorId<FieldId>(id), FieldId {

        private val declaringClass: TypeId = internalTypeIds[classIdx]
        private val type: TypeId = internalTypeIds[typeIdx]
        private val name: StringId = internalStrings[nameIdx]

        override fun getType(): TypeId = type

        override fun getDeclaringClass(): TypeId = declaringClass

        override fun getName(): StringId = name
    }

    private inner class VectorMethodId(id: Int, classIdx: Int, protoIdx: Int, nameIdx: Int) :
        VectorId<MethodId>(id), MethodId {

        private val declaringClass: TypeId = internalTypeIds[classIdx]
        private val prototype: ProtoId = internalProtoIds[protoIdx]
        private val name: StringId = internalStrings[nameIdx]

        override fun getDeclaringClass(): TypeId = declaringClass

        override fun getPrototype(): ProtoId = prototype

        override fun getName(): StringId = name
    }

    private class VectorArray(elementsTypes: IntArray, valuesData: Array<Any?>) : DexParser.Array {

        private val values: Array<Value>

        init {
            values =
                Array(valuesData.size) { i ->
                    VectorValue(elementsTypes[i], valuesData[i] as? ByteBuffer)
                }
        }

        override fun getValues(): Array<Value> = values
    }

    private inner class VectorAnnotation(
        private val visibility: Int,
        typeIdx: Int,
        elementNameIndices: IntArray,
        elementValues: Array<Any?>,
    ) : DexParser.Annotation {

        private val type: TypeId = internalTypeIds[typeIdx]
        private val elements: Array<Element>

        init {
            elements =
                Array(elementValues.size) { i ->
                    // Flattened structure from JNI: names are at 2*i, types at 2*i+1
                    VectorElement(
                        elementNameIndices[i * 2],
                        elementNameIndices[i * 2 + 1], // valueType
                        elementValues[i] as? ByteBuffer,
                    )
                }
        }

        override fun getVisibility(): Int = visibility

        override fun getType(): TypeId = type

        override fun getElements(): Array<Element> = elements
    }

    private open class VectorValue(private val valueType: Int, buffer: ByteBuffer?) : Value {

        private val value: ByteArray?

        init {
            if (buffer != null) {
                value = ByteArray(buffer.remaining())
                buffer.get(value)
            } else {
                value = null
            }
        }

        override fun getValue(): ByteArray? = value

        override fun getValueType(): Int = valueType
    }

    private inner class VectorElement(nameIdx: Int, valueType: Int, value: ByteBuffer?) :
        VectorValue(valueType, value), Element {

        private val name: StringId = internalStrings[nameIdx]

        override fun getName(): StringId = name
    }
}
