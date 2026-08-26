package com.optel.qxinspection.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "app.sync")
public class SyncConfig {

    private List<String> essential = new ArrayList<>();
    private int batchSize = 5000;
    private List<String> exclude = new ArrayList<>();
}
