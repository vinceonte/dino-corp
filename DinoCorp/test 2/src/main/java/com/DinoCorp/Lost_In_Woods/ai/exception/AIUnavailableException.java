package com.DinoCorp.Lost_In_Woods.ai.exception;

// Thrown when the AI backend (OpenRouter) can't be reached or returns nothing usable.
public class AIUnavailableException extends RuntimeException {

	public AIUnavailableException(String message) {
		super(message);
	}

}
