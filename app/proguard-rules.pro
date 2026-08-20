# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /sdk/tools/proguard/proguard-android.txt

# Keep data classes
-keep class com.noiselogger.RecordingInfo { *; }
-keep class com.noiselogger.LogEntry { *; }
