package com.DinoCorp.Lost_In_Woods.ai.provider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.DinoCorp.Lost_In_Woods.ai.config.OpenRouterProperties;
import com.DinoCorp.Lost_In_Woods.ai.exception.AIUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

// Concrete AIProvider that talks to the OpenRouter HTTP API. Translates our
// neutral ChatRequest into OpenRouter's payload and maps any failure to a clean
// AIUnavailableException, so the rest of the app never sees HTTP details.
@Component
public class OpenRouterAdapter implements AIProvider {

	// Cap generation length. A typical beat outputs ~250-400 tokens (one paragraph
	// of narrative + the JSON envelope). 600 gives comfortable headroom for the
	// rare 2-paragraph ending beat while forcing slow models to wrap up sooner —
	// saves ~3-6s per beat on slow tiers vs the previous 800/1600 caps.
	private static final int MAX_TOKENS = 600;

	// On free models we still want a brief retry on transient empty replies, but
	// NEVER on rate-limit (429) errors — retrying a 429 just burns more of the
	// daily quota and adds latency. So: up to 2 attempts total, none on 429.
	private static final int MAX_ATTEMPTS = 2;

	// Automatic fallback model: if the primary (e.g. a rate-limited free model) is
	// unavailable, OpenRouter routes to this reliable one instead.
	private static final String FALLBACK_MODEL = "z-ai/glm-4.5-air:free";

	// Shared HTTP/2 client for streaming. HTTP/1.1 keep-alive also fine, this is
	// just lower-overhead and reusable across threads.
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_2)
			.connectTimeout(Duration.ofSeconds(15))
			.build();

	private final ObjectMapper jsonMapper = new ObjectMapper();
	private final OpenRouterProperties properties;
	private final RestClient restClient;

	// OpenRouter API key — injected from the externalized "ai.openrouter.api-key"
	// property (which itself resolves OPENROUTER_API_KEY at runtime, see
	// application.properties). The key is NEVER hardcoded in source.
	// Default ":" leaves the field blank if the env var is unset, so the startup
	// doesn't fail; requireKey() then throws a clear AIUnavailableException at the
	// point of use instead.
	@Value("${ai.openrouter.api-key:}")
	private String openRouterApiKey;

	public OpenRouterAdapter(OpenRouterProperties properties, @Lazy RestClient restClient) {
		this.properties = properties;
		this.restClient = restClient;
	}

	@Override
	public ChatResponse ask(ChatRequest request) {
		requireKey();

		String primary = properties.model();
		List<String> models = primary.equals(FALLBACK_MODEL) ? List.of(primary) : List.of(primary, FALLBACK_MODEL);
		var body = new OpenRouterRequest(
				primary,
				models,
				request.messages().stream()
						.map(m -> new OpenRouterMessage(m.role(), m.content()))
						.toList(),
				MAX_TOKENS,
				false   // stream=false on the blocking path
		);

		RestClientException lastError = null;
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try {
				OpenRouterResponse response = restClient.post()
						.uri("/v1/chat/completions")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + openRouterApiKey)
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
			catch (HttpClientErrorException ex) {
				// Hard 4xx error. 429 (rate-limit) is NEVER worth retrying — the next
				// attempt burns another request against the same daily cap and the
				// server will return 429 again. Bail out immediately.
				if (ex.getStatusCode().value() == 429) {
					throw new AIUnavailableException("OpenRouter rate-limited (429). Try a different model or top up credit.");
				}
				lastError = ex;
			}
			catch (RestClientException ex) {
				lastError = ex; // transient network error — retry once
			}
			if (attempt < MAX_ATTEMPTS) {
				try {
					Thread.sleep(300L * attempt); // brief backoff before single retry
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

	// Streaming path: requests stream=true from OpenRouter, parses SSE chunks, and
	// invokes onChunk with each text delta as it arrives. Returns the FULL message
	// when generation finishes (or throws AIUnavailableException on hard failure).
	// First-token latency is dramatically lower than ask() since the client can start
	// rendering as soon as bytes arrive, instead of waiting for the whole response.
	@Override
	public ChatResponse askStream(ChatRequest request, Consumer<String> onChunk) {
		requireKey();

		String primary = properties.model();
		List<String> models = primary.equals(FALLBACK_MODEL) ? List.of(primary) : List.of(primary, FALLBACK_MODEL);
		Map<String, Object> body = Map.of(
				"model", primary,
				"models", models,
				"messages", request.messages().stream()
						.map(m -> Map.of("role", m.role(), "content", m.content()))
						.toList(),
				"max_tokens", MAX_TOKENS,
				"stream", true
		);

		String bodyJson;
		try { bodyJson = jsonMapper.writeValueAsString(body); }
		catch (Exception e) { throw new AIUnavailableException("Could not encode request: " + e.getMessage()); }

		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(properties.baseUrl() + "/v1/chat/completions"))
				.timeout(Duration.ofSeconds(90))
				.header("Authorization", "Bearer " + openRouterApiKey)
				.header("Content-Type", "application/json")
				.header("Accept", "text/event-stream")
				.POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
				.build();

		try {
			HttpResponse<java.io.InputStream> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
			int code = resp.statusCode();
			if (code == 429) {
				throw new AIUnavailableException("OpenRouter rate-limited (429). Try a different model or top up credit.");
			}
			if (code >= 400) {
				String snippet;
				try (var is = resp.body()) { snippet = new String(is.readNBytes(512), StandardCharsets.UTF_8); }
				catch (Exception e) { snippet = ""; }
				throw new AIUnavailableException("OpenRouter " + code + ": " + snippet);
			}

			// Some models / providers ignore "stream":true and return a regular JSON
			// response (Content-Type: application/json) instead of SSE. Detect that and
			// parse it as a blocking response — emit the whole content as ONE chunk so
			// the client still sees text without burning a second request. This is the
			// graceful fallback for streaming-incompatible models.
			String ctype = resp.headers().firstValue("content-type").orElse("").toLowerCase();
			boolean isStream = ctype.contains("text/event-stream") || ctype.contains("text/stream");
			if (!isStream) {
				String bodyText;
				try (var is = resp.body()) { bodyText = new String(is.readAllBytes(), StandardCharsets.UTF_8); }
				try {
					JsonNode root = jsonMapper.readTree(bodyText);
					String content = root.path("choices").path(0).path("message").path("content").asText("").trim();
					if (content.isEmpty()) throw new AIUnavailableException("OpenRouter returned an empty (non-streaming) response.");
					if (onChunk != null) onChunk.accept(content); // single chunk so the UI still updates
					return new ChatResponse(content);
				} catch (AIUnavailableException e) { throw e; }
				catch (Exception e) {
					throw new AIUnavailableException("OpenRouter returned unexpected " + ctype + ": "
							+ bodyText.substring(0, Math.min(200, bodyText.length())));
				}
			}

			// Parse OpenAI-compatible SSE: lines of "data: <json>", terminated by "data: [DONE]".
			StringBuilder full = new StringBuilder();
			try (BufferedReader br = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = br.readLine()) != null) {
					if (line.isEmpty() || !line.startsWith("data:")) continue;
					String payload = line.substring(5).trim();
					if (payload.isEmpty() || "[DONE]".equals(payload)) {
						if ("[DONE]".equals(payload)) break;
						continue;
					}
					try {
						JsonNode node = jsonMapper.readTree(payload);
						JsonNode delta = node.path("choices").path(0).path("delta").path("content");
						if (delta.isTextual()) {
							String text = delta.asText();
							if (!text.isEmpty()) {
								full.append(text);
								if (onChunk != null) onChunk.accept(text);
							}
						}
					}
					catch (Exception ignore) { /* ignore malformed SSE frame; keep streaming */ }
				}
			}
			String content = full.toString().trim();
			if (content.isEmpty()) throw new AIUnavailableException("OpenRouter returned an empty stream.");
			return new ChatResponse(content);
		}
		catch (AIUnavailableException e) { throw e; }
		catch (InterruptedException ie) {
			// Restore the interrupt flag (required for any caller still watching it),
			// then surface a player-friendly message instead of the raw class name.
			Thread.currentThread().interrupt();
			throw new AIUnavailableException("The storyteller took too long — try once more.");
		}
		catch (Exception e) {
			String msg = e.getMessage();
			if (msg == null) msg = e.getClass().getSimpleName();
			throw new AIUnavailableException("OpenRouter streaming failed: " + msg);
		}
	}

	private void requireKey() {
		if (openRouterApiKey == null || openRouterApiKey.isBlank()) {
			throw new AIUnavailableException("OpenRouter API key is not configured");
		}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record OpenRouterRequest(String model, List<String> models, List<OpenRouterMessage> messages, int max_tokens, boolean stream) {
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
