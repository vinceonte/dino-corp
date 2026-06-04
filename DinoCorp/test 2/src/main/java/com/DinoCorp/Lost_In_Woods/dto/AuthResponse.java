package com.DinoCorp.Lost_In_Woods.dto;

// Returned when a player joins (guest or named). The session ID is then used to
// start and advance the AI story.
public record AuthResponse(Long sessionId, String playerName) {}
