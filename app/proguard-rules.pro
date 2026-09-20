# Xiaomi Vela Simulator proguard 规则
# kotlinx-serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.vela.simulator.**$$serializer { *; }
-keepclassmembers class com.vela.simulator.** { *** Companion; }
-keepclasseswithmembers class com.vela.simulator.** { kotlinx.serialization.KSerializer serializer(...); }
