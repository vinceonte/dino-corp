package com.DinoCorp.Lost_In_Woods.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai.openrouter")
public record OpenRouterProperties(String apiKey, String baseUrl, String model) {
}
