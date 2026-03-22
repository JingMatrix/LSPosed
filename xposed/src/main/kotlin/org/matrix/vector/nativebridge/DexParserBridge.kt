package org.matrix.vector.nativebridge

import dalvik.annotation.optimization.FastNative
import java.io.IOException
import java.lang.reflect.Method
import java.nio.ByteBuffer
import org.matrix.vector.api.utils.DexParser

object DexParserBridge {
    @JvmStatic
    @FastNative
    @Throws(IOException::class)
    external fun openDex(data: ByteBuffer, args: LongArray): Any

    @JvmStatic @FastNative external fun closeDex(cookie: Long)

    @JvmStatic
    @FastNative
    external fun visitClass(
        cookie: Long,
        visitor: Any,
        fieldVisitorClass: Class<DexParser.FieldVisitor>,
        methodVisitorClass: Class<DexParser.MethodVisitor>,
        classVisitMethod: Method,
        fieldVisitMethod: Method,
        methodVisitMethod: Method,
        methodBodyVisitMethod: Method,
        stopMethod: Method,
    )
}
