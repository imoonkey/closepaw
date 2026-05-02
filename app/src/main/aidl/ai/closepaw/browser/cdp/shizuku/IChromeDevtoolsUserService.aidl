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
     * Idempotent: subsequent calls return the same port. Process death tears
     * the relay down via the Shizuku UserService lifecycle.
     */
    int startTcpRelay();

    /** Tear down the user service process. */
    void destroy();
}
