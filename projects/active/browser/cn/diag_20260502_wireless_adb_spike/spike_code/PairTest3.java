package spike;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class PairTest3 {
    static final String ACTION = "com.android.server.adb.WIRELESS_DEBUG_PAIRING_RESULT";

    public static void main(String[] args) {
        try { doWork(); }
        catch (Throwable t) {
            log("FATAL: " + t);
            t.printStackTrace(System.out);
            System.out.flush();
        }
        log("=== exit ===");
        System.out.flush();
    }

    static void doWork() throws Exception {
        log("euid=" + android.os.Process.myUid() + " pid=" + android.os.Process.myPid());

        Looper.prepareMainLooper();
        log("Looper.prepareMainLooper ok");

        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        log("ActivityThread.systemMain ok: " + thread);

        Context ctx = (Context) at.getMethod("getSystemContext").invoke(thread);
        log("getSystemContext ok: " + ctx);

        // Try registerReceiver — this is where SecurityException might fire
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] capturedCode = new String[]{null};
        final int[] capturedPort = new int[]{0};
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                log("BROADCAST: action=" + intent.getAction());
                Bundle ex = intent.getExtras();
                if (ex != null) for (String k : ex.keySet()) log("  extra[" + k + "] = " + ex.get(k));
                latch.countDown();
            }
        };
        IntentFilter f = new IntentFilter(ACTION);

        // Try the 5-arg signature with RECEIVER_EXPORTED=2
        try {
            Method reg = Context.class.getMethod("registerReceiver", BroadcastReceiver.class, IntentFilter.class, String.class, Handler.class, int.class);
            reg.invoke(ctx, r, f, null, null, 0x2);
            log("registerReceiver(5arg, EXPORTED) ok");
        } catch (Throwable t) {
            log("5arg registerReceiver failed: " + t.getCause());
            try {
                ctx.registerReceiver(r, f);
                log("registerReceiver(2arg) ok");
            } catch (Throwable t2) {
                log("2arg registerReceiver also failed: " + t2);
                t2.printStackTrace(System.out);
            }
        }
        System.out.flush();

        // Get IAdbManager
        Class<?> svcMgr = Class.forName("android.os.ServiceManager");
        IBinder binder = (IBinder) svcMgr.getMethod("getService", String.class).invoke(null, "adb");
        Class<?> stub = Class.forName("android.debug.IAdbManager$Stub");
        Object adb = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
        log("IAdbManager ready");

        // Disable + re-enable to force a fresh broadcast
        try {
            for (Method m : adb.getClass().getMethods()) {
                if (m.getName().equals("disablePairing") && m.getParameterCount() == 0) {
                    m.invoke(adb);
                    log("disablePairing ok");
                    break;
                }
            }
        } catch (Throwable t) { log("disablePairing error: " + t); }

        Thread.sleep(500);

        for (Method m : adb.getClass().getMethods()) {
            if (m.getName().equals("enablePairingByPairingCode") && m.getParameterCount() == 0) {
                m.invoke(adb);
                log("enablePairingByPairingCode invoked");
                break;
            }
        }

        log("waiting up to 30s for broadcast...");
        boolean got = latch.await(30, TimeUnit.SECONDS);
        log("broadcast received = " + got);
        if (got) {
            log("=== CODE WIN: " + capturedCode[0] + " PORT: " + capturedPort[0]);
        }

        log("done — holding 60s for manual cleanup");
        Thread.sleep(60_000);
    }

    private static void log(String s) {
        System.out.println("[PT3] " + s);
        System.out.flush();
    }
}
