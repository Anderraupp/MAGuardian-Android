# Framework entry points (activities, services, receivers, XML handlers) are
# preserved by the Android plugin's generated rules. Do not keep the whole app:
# that disables shrinking, optimization and obfuscation.
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# The locally bundled TikTok AAR does not supply consumer ProGuard rules.
-keep class com.tiktok.** { *; }
# Preserve the Billing public SDK contract and callback interfaces.
-keep class com.android.billingclient.api.** { *; }
# AndroidX and Meta ship their own consumer rules, including lifecycle observers.
