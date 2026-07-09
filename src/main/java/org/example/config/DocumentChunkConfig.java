package org.example.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "document.chunk")
public class DocumentChunkConfig {
    private int maxSize = 1200;
    private int overlap = 200;
}
