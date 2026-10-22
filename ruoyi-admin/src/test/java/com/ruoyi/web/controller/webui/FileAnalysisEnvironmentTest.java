package com.ruoyi.web.controller.webui;

import com.ruoyi.webui.file.FileAnalysisProperties;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.*;
import org.springframework.core.io.ClassPathResource;
import static org.junit.Assert.*;

public class FileAnalysisEnvironmentTest {
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
