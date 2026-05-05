// Shizuku UserService binder for Chrome DevTools socket access.
// Runs in a Shizuku-spawned process with shell UID and is hard-wired to
// Chrome's `chrome_devtools_remote` abstract socket. The socket name is
// intentionally NOT a parameter: any caller-controlled abstract socket
// name would let app-UID code use the shell-UID proxy to reach arbitrary
// privileged sockets, defeating the whole point of the isolation boundary.
package ai.closepaw.browser.cdp.shizuku;

interface IChromeDevtoolsUserService {
    /**
     * Connect to the hard-coded `chrome_devtools_remote` abstract socket,
     * write the request bytes, read until EOF or the deadline elapses,
     * and return the response bytes. Throws if the socket is inaccessible.
     */
    byte[] exchange(in byte[] request, int timeoutMs);

    /**
     * Start (idempotently) a TCP loopback relay that forwards 127.0.0.1:<port>
     * to the `chrome_devtools_remote` abstract socket. Returns the bound port.
     * Lets the app-UID OkHttp WebSocket client tunnel CDP through the shell-UID
     * UserService — needed because WebSocket is full-duplex stream-based and
     * cannot be fitted into the request/response `exchange` shape, and because
     * the WS URL Chrome returns has no port (defaults to 80, unreachable).
     *
     * Token-gated: every accepted client connection must include the matching
     * `X-ClosePaw-Token` header in the WS Upgrade request — see
     * `ai.closepaw.browser.cdp.RelayAuthToken`. The relay binds 127.0.0.1, so
     * any local app can dial the port; the token is the only thing keeping it
     * out of Chrome's CDP. Token must be non-empty.
     *
     * Idempotent: subsequent calls with the same token return the same port.
     * Calls with a different token throw SecurityException to signal a
     * configuration bug rather than silently shadowing the original token.
     * Process death tears the relay down via the Shizuku UserService lifecycle.
     */
    int startTcpRelay(String authToken);

    /** Tear down the user service process. */
    void destroy();

    // ── Wireless ADB management (IAdbManager via reflection from shell UID) ─────
    // The shell UID holds MANAGE_DEBUGGING, so these binder calls succeed without
    // root. App UID cannot call IAdbManager directly: SecurityException.

    /** Current Wi-Fi BSSID (lowercased hex like "aa:bb:cc:11:22:33") or null. */
    String getCurrentBssid();

    /**
     * IAdbManager.allowWirelessDebugging(true, bssid). Returns true on success.
     * Idempotent — safe to call when wireless ADB is already enabled for the same BSSID.
     */
    boolean enableWirelessDebugging(String bssid);

    /** IAdbManager.getAdbWirelessPort(). Returns -1 when wireless ADB is not listening. */
    int getAdbWirelessPort();

    /**
     * IAdbManager.enablePairingByQrCode(name, psk). Returns the pair port (discovered by
     * diffing /proc/net/tcp before/after the call), or -1 if no new listening port appeared
     * within 5s. Caller is expected to follow up with disablePairing() once paired.
     */
    int enablePairingByQrCode(String name, String psk);

    /** IAdbManager.disablePairing(). Best-effort — errors are swallowed and logged. */
    void disablePairing();

    /**
     * Returns the contents of `/data/misc/adb/adb_keys` (the file adbd consults to decide
     * which pubkeys are pre-authorized) or null if the file is unreadable / missing. Each line
     * is `<base64-pubkey> <name>` — caller substring-matches its own pubkey base64 to decide
     * whether the device already trusts us and pairing can be skipped.
     */
    String readAdbKeys();

    /**
     * Atomically replaces `/data/misc/adb/adb_keys` with [content] (write tmp + fsync →
     * best-effort metadata restore → rename). adbd reads the file at every auth handshake
     * (libadbd_auth iterates lines from disk per A_AUTH SIGNATURE), so the new key set takes
     * effect on the next adb connection without an adbd restart.
     *
     * Returns true on success, false on any IO failure (including non-root environments
     * where shell uid cannot reach the path at all). Caller is responsible for ensuring
     * [content] preserves any non-ClosePaw entries that should remain trusted.
     */
    boolean writeAdbKeys(String content);
}
