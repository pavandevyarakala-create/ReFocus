# Credential Manager
-keep class androidx.credentials.** { *; }
-keep class androidx.credentials.playservices.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }

# Firebase Auth
-keep class com.google.firebase.auth.** { *; }

# Firestore - Data Objects (DTOs)
# Prevent R8 from renaming or stripping fields in classes used with Firestore .toObject() or .set()
-keepclassmembers class com.reeltrak.app.data.remote.dto.** {
    private <fields>;
    public <fields>;
    public <methods>;
}
-keep class com.reeltrak.app.data.remote.dto.** { *; }

# Room - Generated Classes and Converters
-keep class * extends androidx.room.RoomDatabase
-keep class com.reeltrak.app.data.local.entity.** { *; }
-keep class com.reeltrak.app.data.local.dao.** { *; }
-keep class com.reeltrak.app.data.local.database.** { *; }

# Gson - Serialization for Room TypeConverters
-keep class com.google.gson.** { *; }
-keepclassmembers class com.reeltrak.app.data.local.entity.SessionData {
    <fields>;
}

# Keep the generated Google Services resources
-keepclassmembers class **.R$string {
    public static <fields>;
}

# Razorpay Standard Checkout SDK ProGuard Rules
-keepattributes *Annotation*
-dontwarn com.razorpay.**
-keep class com.razorpay.** {*;}
-optimizations !class/merging/vertical*,!class/merging/horizontal*
-keepclasseswithmembers class * {
    public void onPaymentSuccess(java.lang.String);
    public void onPaymentError(int, java.lang.String);
    public void onPaymentSuccess(java.lang.String, com.razorpay.PaymentData);
    public void onPaymentError(int, java.lang.String, com.razorpay.PaymentData);
}
-keep class com.google.android.gms.auth.api.phone.** {*;}

# Security Hardening (P4-02): Strip verbose, debug, and info logs from release builds
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Dagger / Hilt (Issue #4323): Prevent R8 from merging or stripping ViewModel classes.
# Hilt LazyClassKeyMap uses @IdentifierNameString for ViewModel multibinding.
# If R8 horizontally merges ViewModels, it rewrites different keys to the same obfuscated name,
# causing Guava ImmutableMap to throw IllegalArgumentException: Multiple entries with same key.
-keep,allowobfuscation class * extends androidx.lifecycle.ViewModel

