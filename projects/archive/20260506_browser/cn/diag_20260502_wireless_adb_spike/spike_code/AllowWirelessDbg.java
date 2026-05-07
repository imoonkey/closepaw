package spike;

import android.os.IBinder;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

public class AllowWirelessDbg {
    public static void main(String[] args) {
        try { run(); } catch (Throwable t) { t.printStackTrace(System.out); }
        System.out.flush();
    }

    static void run() throws Exception {
        log("=== allowWirelessDebugging direct-call probe ===");
        log("euid=" + android.os.Process.myUid() + " pid=" + android.os.Process.myPid());

        Class<?> svcMgr = Class.forName("android.os.ServiceManager");
        IBinder binder = (IBinder) svcMgr.getMethod("getService", String.class).invoke(null, "adb");
        Class<?> stub = Class.forName("android.debug.IAdbManager$Stub");
        Object adb = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);

        // Get current BSSID via shell
        String bssid = sh("dumpsys wifi | grep -oE 'BSSID: [0-9a-f:]+' | head -1 | awk '{print $2}'").trim();
        log("current BSSID = '" + bssid + "'");

        // ACT: call allowWirelessDebugging(true, bssid) directly from shell uid
        log("calling allowWirelessDebugging(true, '" + bssid + "')");
        try {
            Method m = adb.getClass().getMethod("allowWirelessDebugging", boolean.class, String.class);
            m.invoke(adb, true, bssid);
            log("allowWirelessDebugging returned ok");
        } catch (Throwable t) {
            log("allowWirelessDebugging FAILED: " + (t.getCause() != null ? t.getCause() : t));
            t.printStackTrace(System.out);
        }

        Thread.sleep(1500);

        log("--- state after call ---");
        log("adb_wifi_enabled = " + sh("settings get global adb_wifi_enabled").trim());
        log("listeners (high ports):");
        log(sh("ss -tln 2>/dev/null | awk '/LISTEN/{print $4}' | grep -E ':[3-5][0-9]{4}$'"));
        log("WifiDebuggingActivity visible?:");
        log(sh("dumpsys activity activities | grep -E 'topResumed|WifiDebugging' | head -5"));

        log("--- relevant logcat ---");
        log(sh("logcat -d -t 100 | grep -iE 'AdbDebug|wireless|adbd|TlsServer|WifiDebug' | head -20"));

        log("=== done ===");
    }

    static String sh(String cmd) throws Exception {
        Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
        BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append("\n");
        return sb.toString();
    }

    static void log(String s) { System.out.println("[AWD] " + s); System.out.flush(); }
}
