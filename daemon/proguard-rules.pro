-keepclasseswithmembers,includedescriptorclasses class * {
    native <methods>;
}
-keepclasseswithmembers class org.matrix.vector.daemon.VectorDaemon {
    public static void main(java.lang.String[]);
}
-keepclasseswithmembers class org.matrix.vector.daemon.Cli {
    public static void main(java.lang.String[]);
}
-keepclasseswithmembers class org.matrix.vector.daemon.env.LogcatMonitor {
    private int refreshFd(boolean);
}
-keepclassmembers class ** implements android.content.ContextWrapper {
    public int getUserId();
    public android.os.UserHandle getUser();
}
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
}
-repackageclasses
-allowaccessmodification
