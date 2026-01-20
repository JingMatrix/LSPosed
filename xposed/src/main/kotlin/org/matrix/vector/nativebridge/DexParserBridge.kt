package org.lsposed.lspd.nativebridge
// TODO: refactor the JNI and thus change the package name

import dalvik.annotation.optimization.FastNative
import io.github.libxposed.api.utils.DexParser
import java.io.IOException
import java.lang.reflect.Method
import java.nio.ByteBuffer

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
