package com.DinoCorp.Lost_In_Woods.ai.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(OpenRouterProperties.class)
public class OpenRouterConfig {

	@Bean
	@Lazy
	RestClient openRouterRestClient(OpenRouterProperties properties) {
		return RestClient.builder()
				.baseUrl(properties.baseUrl())
				.build();
	}

}
