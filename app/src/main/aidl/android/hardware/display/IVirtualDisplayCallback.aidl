package android.hardware.display;

/** @hide */
oneway interface IVirtualDisplayCallback {
    void onPaused();
    void onResumed();
    void onStopped();
}
