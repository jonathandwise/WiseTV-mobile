# Keep class and line names readable in crash logs; this is a personal app, nothing to hide.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# Optional TLS providers OkHttp looks for but doesn't need.
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Named only in the manifest (as a string), so R8 can't see it's used.
-keep class com.wisetv.mobile.cast.CastOptionsProvider { *; }
