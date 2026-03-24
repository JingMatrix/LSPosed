-keep class android.** { *; }
-keep class de.robv.android.xposed.** { *; }
-keep class org.matrix.vector.Startup { *; }

# Workaround to bypass verification of in-memory built class xposed.dummy.XResourcesSuperClass
-keep class org.matrix.vector.legacy.LegacyDelegateImpl$ResourceProxy { *; }
