package spike;

import android.os.IBinder;
import java.lang.reflect.Method;

public class ListMethods {
    public static void main(String[] args) {
        try {
            Class<?> svcMgr = Class.forName("android.os.ServiceManager");
            IBinder binder = (IBinder) svcMgr.getMethod("getService", String.class).invoke(null, "adb");
            Class<?> stub = Class.forName("android.debug.IAdbManager$Stub");
            Object adb = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
            log("=== IAdbManager methods (interface " + adb.getClass().getInterfaces()[0].getName() + ") ===");
            Class<?> iface = adb.getClass().getInterfaces()[0];
            for (Method m : iface.getMethods()) {
                StringBuilder sb = new StringBuilder("  ");
                sb.append(m.getReturnType().getSimpleName()).append(" ").append(m.getName()).append("(");
                Class<?>[] ps = m.getParameterTypes();
                for (int i = 0; i < ps.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(ps[i].getSimpleName());
                }
                sb.append(")");
                log(sb.toString());
            }
            log("=== AdbManager class (if exists) ===");
            try {
                Class<?> mgr = Class.forName("android.debug.AdbManager");
                for (java.lang.reflect.Field f : mgr.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) && java.lang.reflect.Modifier.isFinal(f.getModifiers())) {
                        try {
                            f.setAccessible(true);
                            log("  " + f.getName() + " = " + f.get(null));
                        } catch (Throwable t) {}
                    }
                }
            } catch (Throwable t) { log("AdbManager class not found: " + t); }

            // Probe FingerprintAndPairDevice too
            log("=== FingerprintAndPairDevice fields ===");
            try {
                Class<?> fp = Class.forName("android.debug.FingerprintAndPairDevice");
                for (java.lang.reflect.Field f : fp.getDeclaredFields()) {
                    log("  " + f.getType().getSimpleName() + " " + f.getName());
                }
            } catch (Throwable t) { log("FingerprintAndPairDevice not found: " + t); }

            // Look for WirelessDebugging classes
            log("=== Other adb-related classes ===");
            for (String name : new String[]{
                    "android.debug.AdbManager$WirelessDebuggingManager",
                    "android.debug.AdbManagerInternal",
                    "android.debug.IAdbCallback",
                    "android.debug.IAdbCallback$Stub",
                    "android.debug.PairDevice",
                    "android.debug.AdbProtoEnums"
            }) {
                try {
                    Class<?> c = Class.forName(name);
                    log("FOUND: " + c.getName());
                } catch (Throwable t) {}
            }
        } catch (Throwable t) {
            t.printStackTrace(System.out);
        }
        System.out.flush();
    }

    static void log(String s) {
        System.out.println("[LM] " + s);
        System.out.flush();
    }
}
