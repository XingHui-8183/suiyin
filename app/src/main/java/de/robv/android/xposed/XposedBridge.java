package de.robv.android.xposed;

public final class XposedBridge {
    public static final int XPOSED_BRIDGE_VERSION = 82;

    public static Object invokeOriginalMethod(java.lang.reflect.Member method, Object thisObject, Object[] args) throws Throwable {
        return null;
    }

    public static void hookAllMethods(Class<?> hookClass, String methodName, XC_MethodHook callback) {
    }

    public static void handleHookedMethod(XC_MethodHook.MethodHookParam param) throws Throwable {
    }

    public static void log(String text) {
    }

    public static void log(Throwable t) {
    }
}