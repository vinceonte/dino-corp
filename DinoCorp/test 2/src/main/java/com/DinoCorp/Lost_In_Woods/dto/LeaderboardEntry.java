package com.DinoCorp.Lost_In_Woods.dto;

// One row on the leaderboard. Endless runs are ranked by finalScore
// (= currentScore + currentHealth + traitPoints); eventsSurvived shows how far they got.
public record LeaderboardEntry(
        String playerName,
        int eventsSurvived,
        int finalScore
) {}
