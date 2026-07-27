package de.robv.android.xposed;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class XposedHelpers {
    public static Class<?> findClass(String className, ClassLoader classLoader) throws ClassNotFoundException {
        return classLoader.loadClass(className);
    }

    public static XC_MethodHook.Unhook findAndHookMethod(Class<?> clazz, String methodName, Object... parameterTypesAndCallback) {
        return null;
    }

    public static Object getStaticObjectField(Class<?> clazz, String fieldName) {
        return null;
    }

    public static Object getObjectField(Object obj, String fieldName) {
        return null;
    }

    public static void setStaticObjectField(Class<?> clazz, String fieldName, Object value) {
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        return null;
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        return null;
    }

    public static XC_LoadPackage.LoadPackageParam getCurrentPackageParam() {
        return null;
    }
}