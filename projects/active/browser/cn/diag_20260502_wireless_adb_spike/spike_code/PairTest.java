package spike;

import android.os.IBinder;
import java.lang.reflect.Method;

public class PairTest {
    public static void main(String[] args) throws Exception {
        log("=== IAdbManager pair-flow probe ===");
        log("euid=" + android.os.Process.myUid() + " pid=" + android.os.Process.myPid());

        // 1. Get adb binder via ServiceManager (hidden API, use reflection)
        Class<?> svcMgr = Class.forName("android.os.ServiceManager");
        Method getService = svcMgr.getMethod("getService", String.class);
        IBinder binder = (IBinder) getService.invoke(null, "adb");
        if (binder == null) {
            log("ERROR: adb service not found in ServiceManager");
            return;
        }
        log("got adb binder: " + binder + " interface=" + binder.getInterfaceDescriptor());

        // 2. Get IAdbManager via Stub.asInterface
        Class<?> stub = Class.forName("android.debug.IAdbManager$Stub");
        Method asInterface = stub.getMethod("asInterface", IBinder.class);
        Object adb = asInterface.invoke(null, binder);
        log("got IAdbManager: " + adb);
        Class<?> iface = adb.getClass();

        // 3. Probe read-only methods first
        callAndPrint(adb, iface, "isAdbWifiSupported");
        callAndPrint(adb, iface, "isAdbWifiQrSupported");
        callAndPrint(adb, iface, "getAdbWirelessPort");
        callAndPrint(adb, iface, "getPairedDevices");

        // 4. Capture state BEFORE enabling pairing
        log("--- about to call enablePairingByPairingCode() ---");
        callAndPrint(adb, iface, "enablePairingByPairingCode");
        log("--- enablePairingByPairingCode returned, polling state ---");

        // 5. Poll for pair port and re-fetch wireless port
        for (int i = 0; i < 10; i++) {
            Thread.sleep(500);
            Object port = tryCall(adb, iface, "getAdbWirelessPort");
            log("T+" + (500 * (i + 1)) + "ms: getAdbWirelessPort=" + port);
        }

        // 6. Hold so we can read /proc/net/tcp from outside while pair port is up
        log("--- holding 60s so external observer can capture pair-port state ---");
        Thread.sleep(60_000);

        // 7. Cleanup: disablePairing
        callAndPrint(adb, iface, "disablePairing");
        log("=== done ===");
    }

    private static void callAndPrint(Object target, Class<?> iface, String name) {
        try {
            Method m = find(iface, name);
            Object r = m.invoke(target);
            log(name + "() = " + r);
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            log(name + "() FAILED: " + c.getClass().getSimpleName() + ": " + c.getMessage());
        }
    }

    private static Object tryCall(Object target, Class<?> iface, String name) {
        try {
            Method m = find(iface, name);
            return m.invoke(target);
        } catch (Throwable t) {
            return "ERR:" + (t.getCause() != null ? t.getCause().getMessage() : t.getMessage());
        }
    }

    private static Method find(Class<?> cls, String name) throws NoSuchMethodException {
        for (Method m : cls.getMethods()) if (m.getName().equals(name) && m.getParameterCount() == 0) return m;
        throw new NoSuchMethodException(name);
    }

    private static void log(String s) {
        System.out.println("[PairTest] " + s);
        System.err.println("[PairTest] " + s);
    }
}
