-keepclasseswithmembers,includedescriptorclasses class * {
    native <methods>;
}
-keepattributes *Annotation*, Signature, Exception

-keep class picocli.CommandLine { *; }
-keep class picocli.CommandLine$* { *; }
-keep class org.lsposed.lspd.cli.* {*;}

-keepclassmembers class * extends java.util.concurrent.Callable {
    public java.lang.Integer call();
}

-keepclasseswithmembers class org.lsposed.lspd.cli.Main {
    public static void main(java.lang.String[]);
}
-keepclasseswithmembers class org.lsposed.lspd.Main {
    public static void main(java.lang.String[]);
}
-keepclasseswithmembers class org.lsposed.lspd.service.Dex2OatService {
    private java.lang.String devTmpDir;
    private java.lang.String magiskPath;
    private java.lang.String fakeBin32;
    private java.lang.String fakeBin64;
    private java.lang.String[] dex2oatBinaries;
}
-keepclasseswithmembers class org.lsposed.lspd.service.LogcatService {
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
