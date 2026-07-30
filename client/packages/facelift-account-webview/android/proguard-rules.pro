# Capacitor discovers and instantiates the plugin by reflection, so its
# class + @CapacitorPlugin/@PluginMethod members must survive R8/ProGuard
# in release builds.
-keep class de.mhus.vance.facelift.accountwebview.** { *; }
