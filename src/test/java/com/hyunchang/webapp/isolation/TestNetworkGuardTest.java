package com.hyunchang.webapp.isolation;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@SuppressWarnings("removal")
@EnabledIfSystemProperty(named = "isolated.tests", matches = "true")
class TestNetworkGuardTest {
    @Test
    void guardIsInstalledBeforeTestsAndCannotBeRemoved() {
        assertInstanceOf(TestNetworkGuard.class, System.getSecurityManager());
        assertThrows(SecurityException.class, () -> System.setSecurityManager(null));
    }

    @Test
    void realSocketCannotReachEvenLoopback() throws Exception {
        try (Socket socket = new Socket()) {
            SecurityException error =
                    assertThrows(
                            SecurityException.class,
                            () -> socket.connect(new InetSocketAddress("127.0.0.1", 9), 100));
            assertTrue(error.getMessage().contains("TEST_ISOLATION"));
        }
    }

    @Test
    void listenersAndCredentialFilesAreRejected() {
        assertThrows(SecurityException.class, () -> new ServerSocket(0));
        assertThrows(SecurityException.class, () -> Files.readString(Path.of(".env")));
    }
}
