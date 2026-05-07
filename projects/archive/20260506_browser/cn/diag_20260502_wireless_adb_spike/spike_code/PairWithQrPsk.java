package spike;

import android.os.IBinder;
import java.lang.reflect.Method;

public class PairWithQrPsk {
    public static void main(String[] args) {
        try {
            log("=== Pair via enablePairingByQrCode (we supply the PSK) ===");
            log("euid=" + android.os.Process.myUid() + " pid=" + android.os.Process.myPid());

            Class<?> svcMgr = Class.forName("android.os.ServiceManager");
            IBinder binder = (IBinder) svcMgr.getMethod("getService", String.class).invoke(null, "adb");
            Class<?> stub = Class.forName("android.debug.IAdbManager$Stub");
            Object adb = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
            Class<?> iface = adb.getClass().getInterfaces()[0];

            // First: ensure clean state
            try {
                Method m = iface.getMethod("disablePairing");
                m.invoke(adb);
                log("disablePairing ok");
            } catch (Throwable t) { log("disablePairing: " + t.getCause()); }

            Thread.sleep(500);

            // Pick a deterministic PSK — adb's TLS-PSK pairing accepts any 6+ char string
            String name = "ClosePawSpike";
            String psk = "SPIKE_PSK_12345";
            log("calling enablePairingByQrCode(name='" + name + "', psk='" + psk + "')");
            Method qr = iface.getMethod("enablePairingByQrCode", String.class, String.class);
            qr.invoke(adb, name, psk);
            log("enablePairingByQrCode invoked");

            // Poll for pair port
            for (int i = 0; i < 6; i++) {
                Thread.sleep(500);
                Method gp = iface.getMethod("getAdbWirelessPort");
                int wirelessPort = (int) gp.invoke(adb);
                Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", "ss -tln | awk '/LISTEN/{print $4}' | grep -E ':[0-9]+$' | sed 's/.*://' | sort -u | tr '\\n' ',' "});
                java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
                String ports = br.readLine();
                log("T+" + (500 * (i + 1)) + "ms: getAdbWirelessPort=" + wirelessPort + " all-listen-ports=" + ports);
            }

            log("--- now hold; outside, run: adb pair <ip>:<pairport> " + psk + " ---");
            log("--- (PSK is the same shape as a 6-digit code; adb pair will accept any string client-side) ---");
            Thread.sleep(120_000);

            log("disablePairing");
            iface.getMethod("disablePairing").invoke(adb);
            log("=== done ===");
        } catch (Throwable t) {
            log("FATAL " + t);
            t.printStackTrace(System.out);
        }
        System.out.flush();
    }

    static void log(String s) { System.out.println("[QR] " + s); System.out.flush(); }
}
