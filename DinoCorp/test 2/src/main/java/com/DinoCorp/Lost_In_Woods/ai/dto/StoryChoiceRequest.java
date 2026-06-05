package com.DinoCorp.Lost_In_Woods.ai.dto;

// The player's pick for the current beat, plus the session it belongs to.
public record StoryChoiceRequest(Long sessionId, String choice) {}
