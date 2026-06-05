package com.DinoCorp.Lost_In_Woods.model;

import jakarta.persistence.*;
import lombok.*;

// A single endless playthrough. The AI generates the story and reports the
// player's health, score, traits, and inventory each beat; this record holds the
// latest values plus the final score, for the leaderboard.
@Entity
@Table(name = "game_sessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GameSession {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Name entered on the splash ("Guest" for guests).
    private String playerName;

    // Latest health/score reported by the AI for this run.
    @Builder.Default
    private int currentHealth = 100;
    @Builder.Default
    private int currentScore = 0;

    // How many events (beats) the player has survived this run.
    @Builder.Default
    private int eventsSurvived = 0;

    // True once the run ends (death at 0 HP).
    @Builder.Default
    private boolean gameOver = false;

    // The death title reached (null until the run ends).
    private String ending;

    // finalScore = currentScore + currentHealth + traitPoints, computed at death.
    @Builder.Default
    private int finalScore = 0;
}
