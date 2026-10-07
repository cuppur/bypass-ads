# http://developer.android.com/guide/developing/tools/proguard.html

-dontwarn **

# ML Kit discovers these registrars from manifest metadata and constructs
# them with reflection. The dependency's class-only rule leaves R8 free to
# remove their no-argument constructors, disabling the bundled text model.
-keep class com.google.mlkit.** implements com.google.firebase.components.ComponentRegistrar {
    public <init>();
}

# P1-5: the live a11y instance slot must stay a single Kotlin object across
# R8. If the registry is split/inlined, onCreate can stamp one copy while
# the UI reads another and the home screen stays on "正在恢复".
-keep class li.songe.gkd.service.A11yInstanceRegistry { *; }
-keepclassmembers class li.songe.gkd.service.A11yService {
    public static *;
}
