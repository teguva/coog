# Add project specific ProGuard rules here.

# btleplug / droidplug (JNI-only; R8 would strip without keeps)
-keep class com.nonpolynomial.** { *; }
-keep class io.github.gedgygedgy.** { *; }
