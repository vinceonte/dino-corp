package com.DinoCorp.Lost_In_Woods.ai.provider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.DinoCorp.Lost_In_Woods.ai.config.OpenRouterProperties;
import com.DinoCorp.Lost_In_Woods.ai.exception.AIUnavailableException;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

// Concrete AIProvider that talks to the OpenRouter HTTP API. Translates our
// neutral ChatRequest into OpenRouter's payload and maps any failure to a clean
// AIUnavailableException, so the rest of the app never sees HTTP details.
@Component
public class OpenRouterAdapter implements AIProvider {

	// Cap generation length so the model stops sooner (lower latency). Big enough
	// for a tight narrative plus the JSON envelope (choices, traits, etc.).
	// A ceiling, not a target — the model stops when its JSON is done. Keep it high
	// enough that a beat is NEVER truncated mid-JSON (truncation = invalid JSON).
	private static final int MAX_TOKENS = 1600;

	// The free tier is flaky (timeouts, rate limits, empty replies) — retry a few
	// times before giving up so a single hiccup doesn't end the player's turn.
	private static final int MAX_ATTEMPTS = 3;

	// Automatic fallback model: if the primary (e.g. a rate-limited free model) is
	// unavailable, OpenRouter routes to this reliable one instead.
	private static final String FALLBACK_MODEL = "z-ai/glm-4.5-air:free";

	private final OpenRouterProperties properties;
	private final RestClient restClient;

	public OpenRouterAdapter(OpenRouterProperties properties, @Lazy RestClient restClient) {
		this.properties = properties;
		this.restClient = restClient;
	}

	@Override
	public ChatResponse ask(ChatRequest request) {
		if (properties.apiKey() == null || properties.apiKey().isBlank()) {
			throw new AIUnavailableException("OpenRouter API key is not configured");
		}

		String primary = properties.model();
		List<String> models = primary.equals(FALLBACK_MODEL) ? List.of(primary) : List.of(primary, FALLBACK_MODEL);
		var body = new OpenRouterRequest(
				primary,
				models,
				request.messages().stream()
						.map(m -> new OpenRouterMessage(m.role(), m.content()))
						.toList(),
				MAX_TOKENS
		);

		RestClientException lastError = null;
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try {
				OpenRouterResponse response = restClient.post()
						.uri("/v1/chat/completions")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
						.contentType(MediaType.APPLICATION_JSON)
						.body(body)
						.retrieve()
						.body(OpenRouterResponse.class);

				if (response != null && response.choices() != null && !response.choices().isEmpty()) {
					String content = response.choices().getFirst().message().content();
					if (content != null && !content.isBlank()) {
						return new ChatResponse(content.trim());
					}
				}
				// Empty/blank reply (common on the free tier) — fall through and retry.
			}
			catch (RestClientException ex) {
				lastError = ex; // transient network / rate-limit error — retry.
			}
			if (attempt < MAX_ATTEMPTS) {
				try {
					Thread.sleep(500L * attempt); // brief backoff before retrying
				}
				catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		}
		throw new AIUnavailableException("OpenRouter did not return a usable response after "
				+ MAX_ATTEMPTS + " attempts" + (lastError != null ? ": " + lastError.getMessage() : ""));
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record OpenRouterRequest(String model, List<String> models, List<OpenRouterMessage> messages, int max_tokens) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record OpenRouterMessage(String role, String content) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record OpenRouterResponse(List<Choice> choices) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Choice(OpenRouterMessage message) {
	}

}
