package com.DinoCorp.Lost_In_Woods.ai.dto;

import java.util.List;

// One AI-driven story beat sent to the frontend, plus the run's live stats.
public record StoryResponse(
        Long sessionId,
        String location,
        String npc,
        String narrative,
        List<ChoiceView> choices,
        boolean gameOver,
        String ending,
        String endingType,
        int hp,
        int score,
        List<TraitView> traits,
        List<String> items,
        int eventsSurvived,
        int finalScore
) {}
