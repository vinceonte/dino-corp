package com.DinoCorp.Lost_In_Woods.ai.dto;

// One choice offered to the player. "trait" is its category for display
// (Brave / Curious / Risky / Friendly / Careful) — never reveals if it's safe.
public record ChoiceView(String text, String trait) {}
