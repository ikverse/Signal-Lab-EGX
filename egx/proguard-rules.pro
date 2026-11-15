# Release builds are minified; names are kept so a stack trace can be read without a mapping file.
-dontobfuscate

# Google's QR scanner and Play Services come already processed by Google. 0.2.1's shrinker copied their start-up code
# into the app's scan and added not-null checks to it, and one of those failed: "Scan ClaudeIn's QR code" stopped with
# "getClass() on a null object reference" before the camera opened. Their code is kept exactly as shipped.
-keep class com.google.android.gms.** { *; }
-keep class com.google.mlkit.** { *; }
