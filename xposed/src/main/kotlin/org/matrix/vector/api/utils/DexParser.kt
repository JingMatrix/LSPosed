package org.matrix.vector.api.utils

import java.io.Closeable

/** Xposed interface for parsing dex files. */
@Suppress("unused")
interface DexParser : Closeable {

    companion object {
        /** The constant NO_INDEX. */
        const val NO_INDEX: Int = -1 // 0xffffffff
    }

    /** The interface Array. */
    interface Array {
        /**
         * Get values value [ ].
         *
         * @return the value [ ]
         */
        val values: kotlin.Array<Value>
    }

    /** The interface Annotation. */
    interface Annotation {
        /**
         * Gets visibility.
         *
         * @return the visibility
         */
        val visibility: Int

        /**
         * Gets type.
         *
         * @return the type
         */
        val type: TypeId

        /**
         * Get elements element [ ].
         *
         * @return the element [ ]
         */
        val elements: kotlin.Array<Element>
    }

    /** The interface Value. */
    interface Value {
        /**
         * Get value byte [ ].
         *
         * @return the byte [ ]
         */
        val value: ByteArray?

        /**
         * Gets value type.
         *
         * @return the value type
         */
        val valueType: Int
    }

    /** The interface Element. */
    interface Element : Value {
        /**
         * Gets name.
         *
         * @return the name
         */
        val name: StringId
    }

    /** The interface Id. */
    interface Id<Self> : Comparable<Self> {
        /**
         * Gets id.
         *
         * @return the id
         */
        val id: Int
    }

    /** The interface Type id. */
    interface TypeId : Id<TypeId> {
        /**
         * Gets descriptor.
         *
         * @return the descriptor
         */
        val descriptor: StringId
    }

    /** The interface String id. */
    interface StringId : Id<StringId> {
        /**
         * Gets string.
         *
         * @return the string
         */
        val string: String
    }

    /** The interface Field id. */
    interface FieldId : Id<FieldId> {
        /**
         * Gets type.
         *
         * @return the type
         */
        val type: TypeId

        /**
         * Gets declaring class.
         *
         * @return the declaring class
         */
        val declaringClass: TypeId

        /**
         * Gets name.
         *
         * @return the name
         */
        val name: StringId
    }

    /** The interface Method id. */
    interface MethodId : Id<MethodId> {
        /**
         * Gets declaring class.
         *
         * @return the declaring class
         */
        val declaringClass: TypeId

        /**
         * Gets prototype.
         *
         * @return the prototype
         */
        val prototype: ProtoId

        /**
         * Gets name.
         *
         * @return the name
         */
        val name: StringId
    }

    /** The interface Proto id. */
    interface ProtoId : Id<ProtoId> {
        /**
         * Gets shorty.
         *
         * @return the shorty
         */
        val shorty: StringId

        /**
         * Gets return type.
         *
         * @return the return type
         */
        val returnType: TypeId

        /**
         * Get parameters type id [ ].
         *
         * @return the type id [ ]
         */
        val parameters: kotlin.Array<TypeId>?
    }

    /**
     * Get string id string id [ ].
     *
     * @return the string id [ ]
     */
    val stringId: kotlin.Array<StringId>

    /**
     * Get type id type id [ ].
     *
     * @return the type id[ ]
     */
    val typeId: kotlin.Array<TypeId>

    /**
     * Get field id field id [ ].
     *
     * @return the field id [ ]
     */
    val fieldId: kotlin.Array<FieldId>

    /**
     * Get method id method id [ ].
     *
     * @return the method id [ ]
     */
    val methodId: kotlin.Array<MethodId>

    /**
     * Get proto id proto id [ ].
     *
     * @return the proto id [ ]
     */
    val protoId: kotlin.Array<ProtoId>

    /**
     * Get annotations annotation [ ].
     *
     * @return the annotation [ ]
     */
    val annotations: kotlin.Array<Annotation>

    /**
     * Get arrays array [ ].
     *
     * @return the array [ ]
     */
    val arrays: kotlin.Array<Array>

    /** The interface Early stop visitor. */
    interface EarlyStopVisitor {
        /**
         * Stop boolean.
         *
         * @return the boolean
         */
        fun stop(): Boolean
    }

    /** The interface Member visitor. */
    interface MemberVisitor : EarlyStopVisitor

    /** The interface Class visitor. */
    interface ClassVisitor : EarlyStopVisitor {
        /**
         * Visit member visitor.
         *
         * @param clazz the clazz
         * @param accessFlags the access flags
         * @param superClass the super class
         * @param interfaces the interfaces
         * @param sourceFile the source file
         * @param staticFields the static fields
         * @param staticFieldsAccessFlags the static fields access flags
         * @param instanceFields the instance fields
         * @param instanceFieldsAccessFlags the instance fields access flags
         * @param directMethods the direct methods
         * @param directMethodsAccessFlags the direct methods access flags
         * @param virtualMethods the virtual methods
         * @param virtualMethodsAccessFlags the virtual methods access flags
         * @param annotations the annotations
         * @return the member visitor
         */
        fun visit(
            clazz: Int,
            accessFlags: Int,
            superClass: Int,
            interfaces: IntArray,
            sourceFile: Int,
            staticFields: IntArray,
            staticFieldsAccessFlags: IntArray,
            instanceFields: IntArray,
            instanceFieldsAccessFlags: IntArray,
            directMethods: IntArray,
            directMethodsAccessFlags: IntArray,
            virtualMethods: IntArray,
            virtualMethodsAccessFlags: IntArray,
            annotations: IntArray,
        ): MemberVisitor?
    }

    /** The interface Field visitor. */
    interface FieldVisitor : MemberVisitor {
        /**
         * Visit.
         *
         * @param field the field
         * @param accessFlags the access flags
         * @param annotations the annotations
         */
        fun visit(field: Int, accessFlags: Int, annotations: IntArray)
    }

    /** The interface Method visitor. */
    interface MethodVisitor : MemberVisitor {
        /**
         * Visit method body visitor.
         *
         * @param method the method
         * @param accessFlags the access flags
         * @param hasBody the has body
         * @param annotations the annotations
         * @param parameterAnnotations the parameter annotations
         * @return the method body visitor
         */
        fun visit(
            method: Int,
            accessFlags: Int,
            hasBody: Boolean,
            annotations: IntArray,
            parameterAnnotations: IntArray,
        ): MethodBodyVisitor?
    }

    /** The interface Method body visitor. */
    interface MethodBodyVisitor {
        /**
         * Visit.
         *
         * @param method the method
         * @param accessFlags the access flags
         * @param referredStrings the referred strings
         * @param invokedMethods the invoked methods
         * @param accessedFields the accessed fields
         * @param assignedFields the assigned fields
         * @param opcodes the opcodes
         */
        fun visit(
            method: Int,
            accessFlags: Int,
            referredStrings: IntArray,
            invokedMethods: IntArray,
            accessedFields: IntArray,
            assignedFields: IntArray,
            opcodes: ByteArray,
        )
    }

    /**
     * Visit defined classes.
     *
     * @param visitor the visitor
     * @throws IllegalStateException the illegal state exception
     */
    @Throws(IllegalStateException::class) fun visitDefinedClasses(visitor: ClassVisitor)
}
