package com.DinoCorp.Lost_In_Woods.ai.dto;

import java.util.List;

// Begin the AI story for a session, with the player's chosen appearance (Epic 1).
// Appearance fields are optional — null/blank means "unspecified".
// startingItems: the character's randomly-rolled opening inventory (may be empty = start with nothing).
public record StoryStartRequest(
        Long sessionId,
        String gender,
        String hairColor,
        String skinColor,
        String clothingColor,
        String character,
        List<String> startingItems
) {}
