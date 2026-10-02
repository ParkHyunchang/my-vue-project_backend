package com.hyunchang.webapp.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KiwoomUsReferenceStoreTest {
    @TempDir Path directory;

    record Snapshot(Instant capturedAt, double value) {}

    private KiwoomUsReferenceStore store() {
        return new KiwoomUsReferenceStore(
                new ObjectMapper().findAndRegisterModules(), directory.toString());
    }

    @Test
    void successfulSnapshotSurvivesNewInstanceAndReplacement() {
        var first = new Snapshot(Instant.parse("2026-10-01T00:00:00Z"), 25);
        store().write("fundamental-TEST", first);
        assertEquals(first, store().read("fundamental-TEST", Snapshot.class).orElseThrow());
        var updated = new Snapshot(first.capturedAt().plusSeconds(1), 30);
        store().write("fundamental-TEST", updated);
        assertEquals(updated, store().read("fundamental-TEST", Snapshot.class).orElseThrow());
    }

    @Test
    void missingOrCorruptSnapshotIsNotUsable() throws Exception {
        assertTrue(store().read("missing", Snapshot.class).isEmpty());
        Files.writeString(directory.resolve("broken.json"), "{truncated");
        assertTrue(store().read("broken", Snapshot.class).isEmpty());
        assertThrows(
                IllegalArgumentException.class, () -> store().read("../outside", Snapshot.class));
    }
}
