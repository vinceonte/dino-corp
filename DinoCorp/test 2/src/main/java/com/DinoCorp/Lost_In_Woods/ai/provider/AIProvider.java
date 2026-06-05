package com.DinoCorp.Lost_In_Woods.ai.provider;

import java.util.List;
import java.util.function.Consumer;

// Adapter interface that hides the specific AI vendor behind neutral request/
// response records. Swapping providers (OpenRouter -> another) means adding a new
// implementation, with no changes to the calling service.
public interface AIProvider {

	// Send the assembled messages to the model and return its reply (blocking).
	ChatResponse ask(ChatRequest request);

	// Stream the reply token-by-token. onChunk is called with each text delta as
	// soon as it arrives; the returned ChatResponse contains the concatenated full
	// reply once generation finishes. Default fallback: do a normal blocking ask
	// and deliver the whole thing as one chunk (so non-streaming providers still work).
	default ChatResponse askStream(ChatRequest request, Consumer<String> onChunk) {
		ChatResponse r = ask(request);
		if (r != null && r.message() != null && onChunk != null) onChunk.accept(r.message());
		return r;
	}

	// Provider-agnostic request: an ordered list of role/content messages.
	record ChatRequest(List<ChatMessagePayload> messages) {
	}

	// One message: role is "system" / "user" / "assistant".
	record ChatMessagePayload(String role, String content) {
	}

	// Provider-agnostic reply: just the assistant's text.
	record ChatResponse(String message) {
	}

}
