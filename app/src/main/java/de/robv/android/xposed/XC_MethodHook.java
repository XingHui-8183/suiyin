package de.robv.android.xposed;

public abstract class XC_MethodHook implements IXposedHookCallback {
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    public interface Unhook {
        void unhook();
    }

    public static class MethodHookParam {
        public Object thisObject;
        public Object[] args;
        public Object result;
        public Throwable throwable;
        public boolean returnEarly = false;
    }
}