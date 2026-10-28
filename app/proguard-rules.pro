# Правила R8 для release-сборки FreeMAX.

# Отладочные логи с payload'ами кадров (могут содержать сообщения и токены)
# в release не компилируются вовсе.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
}

# Tink (androidx.security) ссылается на compile-only аннотации errorprone,
# которых нет в рантайме.
-dontwarn com.google.errorprone.annotations.**

# Понятные стектрейсы при падении (имена классов остаются obfuscated).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# kotlinx.serialization: сохраняем сгенерированные сериализаторы и companion'ы
# моделей, иначе decodeFromString падает на release.
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.freemax.**$$serializer { *; }
-keepclassmembers class com.freemax.** {
    *** Companion;
}
-keepclasseswithmembers class com.freemax.** {
    kotlinx.serialization.KSerializer serializer(...);
}
