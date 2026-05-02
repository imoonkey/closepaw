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

    /** Tear down the user service process. */
    void destroy();
}
