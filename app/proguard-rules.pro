-keepattributes Signature,InnerClasses,EnclosingMethod,Annotation
-keep class io.obtainmond.rust.ObtainmondNative { *; }
-keep,includedescriptorclasses class io.obtainmond.rust.** { *; }
-keep,includedescriptorclasses class io.obtainmond.data.model.** { *; }
-keep class io.obtainmond.privilege.ObtainmondSheveryProvider { *; }
-keep class com.hamondev.shevery.api.** { *; }
-keep class moe.shizuku.api.** { *; }
-keep class rikka.shizuku.** { *; }

-keepclassmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class **$$serializer { *; }

-keep class * extends androidx.room.RoomDatabase {
    <init>();
}
-keep class androidx.work.impl.WorkDatabase_Impl { *; }
-keep class androidx.work.** { *; }
-keep class androidx.startup.** { *; }

-dontwarn rikka.shizuku.**
-dontwarn com.hamondev.shevery.api.**
-dontwarn okhttp3.**
-dontwarn okio.**
