package com.hyunchang.webapp.isolation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Architectural boundary: adding a real-account dependency must fail before any app is started. */
class UsPaperArchitectureIsolationTest {
    private static final String ROOT = "com/hyunchang/webapp/";
    private static final List<String> PAPER_PACKAGES =
            List.of(
                    "controller/paper/",
                    "dto/paper/",
                    "entity/paper/",
                    "repository/paper/",
                    "service/paper/");
    private static final Pattern PROJECT_TYPE =
            Pattern.compile("com/hyunchang/webapp/[A-Za-z0-9_/$]+");

    @Test
    void paperBytecodeCannotReferenceLiveAccountOrOrderComponents() throws Exception {
        Path classes = Path.of("target/classes");
        List<Path> paperClasses = new ArrayList<>();
        for (String packageName : PAPER_PACKAGES) {
            Path directory = classes.resolve(ROOT + packageName);
            assertTrue(
                    Files.isDirectory(directory), "Missing compiled paper package: " + packageName);
            try (var paths = Files.walk(directory)) {
                paperClasses.addAll(
                        paths.filter(path -> path.toString().endsWith(".class")).toList());
            }
        }
        paperClasses.add(classes.resolve(ROOT + "config/UsPaperConfig.class"));
        assertTrue(paperClasses.size() >= 10, "The complete paper module must be checked");
        for (Path path : paperClasses) {
            for (String constant : utf8Constants(Files.readAllBytes(path))) {
                assertFalse(
                        constant.contains("org/springframework/context/ApplicationContext")
                                || constant.contains(
                                        "org/springframework/beans/factory/BeanFactory"),
                        () -> path.getFileName() + " must not dynamically resolve live beans");
                var matcher = PROJECT_TYPE.matcher(constant.replace('.', '/'));
                while (matcher.find()) {
                    String dependency = matcher.group();
                    assertTrue(
                            isAllowedProjectDependency(dependency),
                            () ->
                                    path.getFileName()
                                            + " references non-paper application type "
                                            + dependency);
                }
            }
        }
    }

    @Test
    void standaloneWiringScansOnlyPaperPersistenceAndNoProductionComponents() throws Exception {
        Class<?> app = Class.forName("com.hyunchang.paperlocal.PaperLocalApplication");
        assertFalse(
                app.isAnnotationPresent(ComponentScan.class), "No application-wide component scan");
        EntityScan entities = app.getAnnotation(EntityScan.class);
        EnableJpaRepositories repositories = app.getAnnotation(EnableJpaRepositories.class);
        assertNotNull(entities);
        assertNotNull(repositories);
        var entityPackages = new ArrayList<>(Arrays.asList(entities.value()));
        entityPackages.addAll(Arrays.asList(entities.basePackages()));
        var repositoryPackages = new ArrayList<>(Arrays.asList(repositories.value()));
        repositoryPackages.addAll(Arrays.asList(repositories.basePackages()));
        assertEquals(List.of("com.hyunchang.webapp.entity.paper"), entityPackages);
        assertEquals(List.of("com.hyunchang.webapp.repository.paper"), repositoryPackages);
        assertEquals(0, entities.basePackageClasses().length);
        assertEquals(0, repositories.basePackageClasses().length);
        Import imports = app.getAnnotation(Import.class);
        assertNotNull(imports);
        for (Class<?> imported : imports.value()) {
            String name = imported.getName().replace('.', '/');
            assertTrue(
                    isAllowedProjectDependency(name)
                            || name.startsWith("com/hyunchang/paperlocal/"),
                    () -> "Unexpected standalone component import: " + imported.getName());
        }
    }

    @Test
    void paperRuntimeAndSchedulerRequireExplicitOptIn() throws Exception {
        for (String name :
                List.of(
                        "config.UsPaperConfig",
                        "controller.paper.UsPaperController",
                        "service.paper.UsPaperService",
                        "service.paper.UsPaperEngineService",
                        "service.paper.UsPaperMarketService",
                        "service.paper.UsPaperScheduler")) {
            var condition =
                    Class.forName("com.hyunchang.webapp." + name)
                            .getAnnotation(ConditionalOnProperty.class);
            assertNotNull(condition, name + " must be disabled unless explicitly enabled");
            assertFalse(condition.matchIfMissing(), name + " cannot default to enabled");
            assertEquals("true", condition.havingValue());
            var properties = new ArrayList<>(Arrays.asList(condition.name()));
            properties.addAll(Arrays.asList(condition.value()));
            assertTrue(properties.contains("us-paper.enabled"), name);
            if (name.endsWith("UsPaperScheduler")) {
                assertTrue(properties.contains("us-paper.scheduler-enabled"), name);
            }
        }
    }

    private static boolean isAllowedProjectDependency(String dependency) {
        return PAPER_PACKAGES.stream().anyMatch(path -> dependency.startsWith(ROOT + path))
                || dependency.equals(ROOT + "config/UsPaperConfig")
                || dependency.startsWith(ROOT + "config/UsPaperConfig$")
                || dependency.equals(ROOT + "util/KiwoomUsMarketHours")
                || dependency.startsWith(ROOT + "util/KiwoomUsMarketHours$");
    }

    private static List<String> utf8Constants(byte[] bytecode) throws IOException {
        List<String> values = new ArrayList<>();
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytecode))) {
            assertEquals(0xCAFEBABE, input.readInt());
            input.readUnsignedShort();
            input.readUnsignedShort();
            int constantCount = input.readUnsignedShort();
            for (int index = 1; index < constantCount; index++) {
                int tag = input.readUnsignedByte();
                switch (tag) {
                    case 1 -> values.add(input.readUTF());
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> input.skipNBytes(4);
                    case 5, 6 -> {
                        input.skipNBytes(8);
                        index++;
                    }
                    case 7, 8, 16, 19, 20 -> input.skipNBytes(2);
                    case 15 -> input.skipNBytes(3);
                    default -> throw new IOException("Unsupported constant-pool tag " + tag);
                }
            }
        }
        return values;
    }
}
