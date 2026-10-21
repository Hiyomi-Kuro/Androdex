-dontobfuscate
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Gson persists these model properties in SharedPreferences by their field names.
-keep class com.kaori.androdex.data.AuthorizedRoot { *; }
-keep class com.kaori.androdex.data.ActivityLogEntry { *; }
-keep enum com.kaori.androdex.data.RootKind { *; }

# Optional JPEG 2000 image decoding is not used by text extraction.
-dontwarn com.gemalto.jp2.JP2Decoder
