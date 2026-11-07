package com.eka.ingestion.internal.chunking;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ChunkSettings.class)
class ChunkConfiguration {}
