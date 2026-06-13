#import <Foundation/Foundation.h>
#import <Capacitor/Capacitor.h>

// Capacitor 6 still uses Objective-C macro-based registration as the
// reliable cross-version path. The `@objc(...)` Swift annotation in
// VanceAccountWebViewPlugin.swift exposes the class to the ObjC
// runtime under the same name.
CAP_PLUGIN(VanceAccountWebViewPlugin, "VanceAccountWebView",
    CAP_PLUGIN_METHOD(present, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(dismiss, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(setBounds, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(reload, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(navigateHome, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(remove, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(addListener, CAPPluginReturnCallback);
    CAP_PLUGIN_METHOD(removeAllListeners, CAPPluginReturnPromise);
)
