# SDK Anthropic: usa Jackson e reflection per (de)serializzare le richieste.
-keep class com.anthropic.** { *; }
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, KotlinMetadata
-keep class com.fasterxml.jackson.** { *; }
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.** { *; }
-dontwarn com.fasterxml.jackson.**
-dontwarn com.anthropic.**
-dontwarn java.beans.**
-dontwarn org.slf4j.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn kotlin.reflect.jvm.internal.**
-dontwarn com.github.victools.**
-dontwarn io.swagger.**


-dontwarn com.google.auto.value.**
-dontwarn io.opentelemetry.**

# sherpa-onnx: il codice nativo legge i campi delle classi di configurazione per nome.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** { *; }
