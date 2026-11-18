package com.hyunchang.webapp.isolation;

import java.net.InetAddress;
import java.security.Permission;

/** Java 21 test-fork guard. Never packaged with the application. */
@SuppressWarnings("removal")
public final class TestNetworkGuard extends SecurityManager {
    public TestNetworkGuard() {
        if (Runtime.version().feature() != 21) {
            throw new IllegalStateException("Isolated tests require JDK 21");
        }
    }

    @Override
    public void checkPermission(Permission permission) {
        if (permission instanceof RuntimePermission
                && "setSecurityManager".equals(permission.getName())) {
            throw new SecurityException("TEST_ISOLATION: network guard cannot be removed");
        }
    }

    @Override
    public void checkPermission(Permission permission, Object context) {
        checkPermission(permission);
    }

    @Override
    public void checkConnect(String host, int port) {
        throw blocked();
    }

    @Override
    public void checkConnect(String host, int port, Object context) {
        throw blocked();
    }

    @Override
    public void checkListen(int port) {
        throw blocked();
    }

    @Override
    public void checkAccept(String host, int port) {
        throw blocked();
    }

    @Override
    public void checkMulticast(InetAddress address) {
        throw blocked();
    }

    @Override
    public void checkExec(String command) {
        throw new SecurityException("TEST_ISOLATION: child processes are disabled");
    }

    @Override
    public void checkRead(String file) {
        // Prevent accidental loading of local credentials by a future test.
        String name = file.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        if (name.equals(".env") || name.startsWith(".env.")) {
            throw new SecurityException("TEST_ISOLATION: dotenv files are disabled");
        }
    }

    private SecurityException blocked() {
        return new SecurityException(
                "TEST_ISOLATION: real network disabled; use an in-memory HTTP/WS test double");
    }
}
