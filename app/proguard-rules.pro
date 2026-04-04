# ─── Règles Proguard — DeepFake Detector ─────────────────────────────────────

# Kotlin
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }

# Hilt / Dagger
-keep class dagger.hilt.** { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }

# Room
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }

# Retrofit + Gson
-keep class com.squareup.retrofit2.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.deepfakedetector.network.** { *; }
-keep class com.google.gson.** { *; }

# ML Kit
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.vision.** { *; }

# TensorFlow Lite
-keep class org.tensorflow.** { *; }

# Data models
-keep class com.deepfakedetector.data.** { *; }

# Coroutines
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class **$$serializer {
    kotlinx.serialization.descriptors.SerialDescriptor descriptor;
    <methods>;
}
-keep @kotlinx.serialization.Serializable class * { *; }

# Retrofit Kotlinx converter
-keep class retrofit2.converter.kotlinx.serialization.** { *; }

# OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# WorkManager
-keep class androidx.work.** { *; }
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker

# Coil
-dontwarn coil.**

# Analysis data classes used by reflection
-keep class com.deepfakedetector.analysis.** { *; }
-keep class com.deepfakedetector.repository.** { *; }
-keep class com.deepfakedetector.db.** { *; }
