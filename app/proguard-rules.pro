# R8 rules for the release build.

# Native code (JNI) looks classes, fields and methods up by name, so R8 must not rename or remove them.
# libmpv: the native side calls back into MPVLib by name (eventProperty / event / logMessage)
-keep class dev.jdtech.mpv.** { *; }
-keep class org.libtorrent4j.** { *; }
-keep class com.noapmat.tstream.cache.HolePuncher { *; }
-dontwarn dev.jdtech.mpv.**
-dontwarn org.libtorrent4j.**

# Keep names: crashes are saved to crash.txt and shown inside the app, so the stack traces must stay readable.
# Shrinking and optimisation still happen, only the renaming is off.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*
