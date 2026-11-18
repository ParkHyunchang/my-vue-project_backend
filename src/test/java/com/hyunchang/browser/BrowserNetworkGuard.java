package com.hyunchang.browser;

import java.net.InetAddress;
import java.security.Permission;

/** Windows/JDK 21 browser harness: inbound loopback only, no outbound or dotenv access. */
@SuppressWarnings("removal")
public final class BrowserNetworkGuard extends SecurityManager {
    public BrowserNetworkGuard() {
        if (Runtime.version().feature() != 21) {
            throw new IllegalStateException("Browser harness requires JDK 21");
        }
    }

    @Override
    public void checkPermission(Permission permission) {
        if (permission instanceof RuntimePermission
                && "setSecurityManager".equals(permission.getName())) {
            throw blocked("guard removal");
        }
    }

    @Override
    public void checkPermission(Permission permission, Object context) {
        checkPermission(permission);
    }

    @Override
    public void checkConnect(String host, int port) {
        // Name resolution for binding loopback is allowed; every outgoing socket is denied.
        if (port == -1 && isLoopbackLiteral(host)) return;
        throw blocked("outbound connection or DNS");
    }

    @Override
    public void checkConnect(String host, int port, Object context) {
        checkConnect(host, port);
    }

    @Override
    public void checkListen(int port) {
        if (port != 13200) throw blocked("listener port");
    }

    @Override
    public void checkAccept(String host, int port) {
        if (!isLoopbackLiteral(host)) throw blocked("remote client");
    }

    @Override
    public void checkMulticast(InetAddress address) {
        throw blocked("multicast");
    }

    @Override
    public void checkExec(String command) {
        throw blocked("child process");
    }

    @Override
    public void checkRead(String file) {
        String name = file.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        if (name.equals(".env") || name.startsWith(".env.")) throw blocked("dotenv");
    }

    private boolean isLoopbackLiteral(String host) {
        return "127.0.0.1".equals(host)
                || "localhost".equals(host)
                || "::1".equals(host)
                || "0:0:0:0:0:0:0:1".equals(host);
    }

    private SecurityException blocked(String boundary) {
        return new SecurityException("BROWSER_ISOLATION: blocked " + boundary);
    }
}
