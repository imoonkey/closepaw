package spike;

import android.os.IBinder;

public class DisablePairing {
    public static void main(String[] args) throws Exception {
        IBinder b = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "adb");
        Object adb = Class.forName("android.debug.IAdbManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, b);
        adb.getClass().getMethod("disablePairing").invoke(adb);
        System.out.println("disablePairing ok");
    }
}
