package com.DinoCorp.Lost_In_Woods.service;

import com.DinoCorp.Lost_In_Woods.dto.LeaderboardEntry;
import com.DinoCorp.Lost_In_Woods.model.GameSession;
import com.DinoCorp.Lost_In_Woods.repository.GameSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

// Player/run bookkeeping for the AI-driven game: create a session and serve the
// leaderboard. The story itself lives in the ai package (StoryService).
@Service
@RequiredArgsConstructor
public class GameService {

    private final GameSessionRepository sessionRepository;



    // Create and persist a fresh run; the ID is generated automatically on save.
    public GameSession createSession(String playerName) {
        return sessionRepository.save(GameSession.builder().playerName(playerName).build());
    }

    // Top 10 finished runs, ranked by final score.
    public List<LeaderboardEntry> getLeaderboard() {
        return sessionRepository.findTop10ByGameOverTrueOrderByFinalScoreDesc().stream()
                .map(s -> new LeaderboardEntry(
                        (s.getPlayerName() == null || s.getPlayerName().isBlank()) ? "Guest" : s.getPlayerName(),
                        s.getEventsSurvived(),
                        s.getFinalScore()))
                .toList();
    }
}
