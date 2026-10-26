package com.ruoyi.web.controller.webui;

import com.ruoyi.webui.file.FileAnalysisProperties;
import java.util.HashMap;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.*;
import org.springframework.core.io.ClassPathResource;
import static org.junit.Assert.*;

public class FileAnalysisEnvironmentTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void localProfileLoadsMinioDefaultsWhenStartedWithoutShellEnvironment() throws Exception {
        Path dotenv = temporary.newFile("backend.env").toPath();
        Files.write(dotenv, ("FILE_ANALYSIS_STORAGE_TYPE=minio\nMINIO_ENDPOINT=http://configured-minio:9000\n"
                + "MINIO_ACCESS_KEY=local-access\nMINIO_SECRET_KEY=local-secret\nMINIO_BUCKET=local-files\n")
                .getBytes(StandardCharsets.UTF_8));
        StandardEnvironment environment = localEnvironment(dotenv);
        ConfigDataEnvironmentPostProcessor.applyTo(environment);
        FileAnalysisProperties properties = bind(environment);
        assertEquals("minio", properties.getStorageType());
        assertEquals("http://configured-minio:9000", properties.getMinio().getEndpoint());
        assertEquals("local-access", properties.getMinio().getAccessKey());
        assertEquals("local-secret", properties.getMinio().getSecretKey());
        assertEquals("local-files", properties.getMinio().getBucket());

        Map<String, Object> overrides = new HashMap<>();
        overrides.put("MINIO_ENDPOINT", "https://override.example.test");
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("process-overrides", overrides));
        assertEquals("https://override.example.test", bind(environment).getMinio().getEndpoint());
    }

    @Test public void missingOptionalLocalEnvironmentFilePreservesExplicitLocalStorage() {
        StandardEnvironment environment = localEnvironment(temporary.getRoot().toPath().resolve("missing.env"));
        ConfigDataEnvironmentPostProcessor.applyTo(environment);
        assertEquals("local", bind(environment).getStorageType());
    }

    private StandardEnvironment localEnvironment(Path dotenv) {
        StandardEnvironment environment = new StandardEnvironment();
        Map<String, Object> settings = new HashMap<>();
        settings.put("spring.config.location", "classpath:/");
        settings.put("spring.profiles.active", "local");
        settings.put("RUOYI_LOCAL_ENV_FILE", dotenv.toAbsolutePath().toString());
        environment.getPropertySources().addFirst(new MapPropertySource("test-startup", settings));
        return environment;
    }

    private FileAnalysisProperties bind(StandardEnvironment environment) {
        return Binder.get(environment).bind("webui.file-analysis", Bindable.of(FileAnalysisProperties.class)).get();
    }

    @Test public void deploymentEnvironmentConfiguresMinioWithoutPrivateJsonFiles() throws Exception {
        Map<String, Object> values = new HashMap<>();
        values.put("FILE_ANALYSIS_STORAGE_TYPE", "minio");
        values.put("MINIO_ENDPOINT", "http://internal-minio:9000");
        values.put("MINIO_PUBLIC_ENDPOINT", "https://files.example.test");
        values.put("MINIO_ACCESS_KEY", "environment-access"); values.put("MINIO_SECRET_KEY", "environment-secret");
        values.put("MINIO_BUCKET", "department-files");
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("test-env", values));
        for (PropertySource<?> source : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        FileAnalysisProperties properties = Binder.get(environment).bind("webui.file-analysis", Bindable.of(FileAnalysisProperties.class)).get();
        assertEquals("minio", properties.getStorageType());
        assertEquals("http://internal-minio:9000", properties.getMinio().getEndpoint());
        assertEquals("https://files.example.test", properties.getMinio().getPublicEndpoint());
        assertEquals("department-files", properties.getMinio().getBucket());
        assertEquals("environment-access", properties.getMinio().getAccessKey());
        assertEquals("environment-secret", properties.getMinio().getSecretKey());
        assertEquals("", properties.getMinio().getConfigFile());
    }
}
