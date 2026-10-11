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

# The bundled TikTok SDK contains an OPTIONAL legacy IAP tracker. TTConfig
# defaults autoIapTrack to false and MAGuardianApp does not opt in. These four
# old Billing classes were removed from Billing 9; the app uses ProductDetails.
# Do not suppress warnings for Billing itself or downgrade its version.
# Before enabling TikTok auto-IAP, replace this optional adapter with a version
# compatible with the installed Billing SDK, and remove these exceptions.
-dontwarn com.android.billingclient.api.SkuDetails
-dontwarn com.android.billingclient.api.SkuDetailsParams
-dontwarn com.android.billingclient.api.SkuDetailsParams$Builder
-dontwarn com.android.billingclient.api.SkuDetailsResponseListener
