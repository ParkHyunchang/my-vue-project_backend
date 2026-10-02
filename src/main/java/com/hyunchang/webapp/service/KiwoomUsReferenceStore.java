package com.hyunchang.webapp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Last successful reference data survives process restarts; callers enforce data age. */
@Service
public class KiwoomUsReferenceStore {
    private static final Logger log = LoggerFactory.getLogger(KiwoomUsReferenceStore.class);
    private final ObjectMapper mapper;
    private final Path directory;

    public KiwoomUsReferenceStore(
            ObjectMapper mapper,
            @Value("${kiwoom.us.reference-cache-directory:data/kiwoom-us-reference}")
                    String directory) {
        this.mapper = mapper;
        this.directory = Path.of(directory).toAbsolutePath().normalize();
    }

    public <T> Optional<T> read(String key, Class<T> type) {
        Path path = path(key);
        if (!Files.isRegularFile(path)) return Optional.empty();
        try {
            return Optional.ofNullable(mapper.readValue(path.toFile(), type));
        } catch (IOException | RuntimeException error) {
            log.warn("미국 참고 데이터 캐시 읽기 실패 [{}]: {}", key, error.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    public synchronized void write(String key, Object value) {
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            Path target = path(key);
            temporary = Files.createTempFile(directory, "reference-", ".tmp");
            mapper.writeValue(temporary.toFile(), value);
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException error) {
            log.warn("미국 참고 데이터 캐시 저장 실패 [{}]: {}", key, error.getClass().getSimpleName());
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    /* best effort */
                }
            }
        }
    }

    private Path path(String key) {
        if (!key.matches("[A-Za-z0-9_.-]{1,64}") || key.contains(".."))
            throw new IllegalArgumentException("잘못된 캐시 키");
        return directory.resolve(key + ".json");
    }
}
