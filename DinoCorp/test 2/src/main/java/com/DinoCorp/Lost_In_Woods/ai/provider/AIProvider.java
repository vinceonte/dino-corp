package com.DinoCorp.Lost_In_Woods.ai.provider;

import java.util.List;

// Adapter interface that hides the specific AI vendor behind neutral request/
// response records. Swapping providers (OpenRouter -> another) means adding a new
// implementation, with no changes to the calling service.
public interface AIProvider {

	// Send the assembled messages to the model and return its reply.
	ChatResponse ask(ChatRequest request);

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
