-keep class com.pocketeverything.app.ipc.** { *; }
-keep class com.pocketeverything.app.fs.** { *; }
-keep class com.pocketeverything.app.search.** { *; }

# Keep libsu RootService & Shizuku UserService/Provider entrypoints invoked via reflection
-keep class com.pocketeverything.app.fs.RootFsService { *; }
-keep class com.pocketeverything.app.fs.ShizukuFsService { *; }
-keep class rikka.shizuku.ShizukuProvider { *; }
-keep class * extends com.topjohnwu.superuser.ipc.RootService { *; }
-keepattributes InnerClasses,Signature

# androidx.sqlite.bundled JNI
-keep class androidx.sqlite.driver.bundled.** { *; }

# PDFBox-Android
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-dontwarn org.bouncycastle.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**
