# Strip all android.util.Log calls from the release build
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}

# Keep the reflection methods used in BleManager
# Since these are system classes, we just need to ensure the reflective calls are protected
-keepclassmembernames class com.pksin.intervalometer.BleManager {
    *** unpairDevice(...);
    *** refreshDeviceCache(...);
}

# Keep our internal callback interfaces and service
-keep class com.pksin.intervalometer.IntervalService { *; }
-keep class com.pksin.intervalometer.BleManager$BleCallback { *; }
-keep class com.pksin.intervalometer.IntervalService$ServiceCallback { *; }

# General Android protection
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
