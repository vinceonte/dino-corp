package com.DinoCorp.Lost_In_Woods.repository;
import com.DinoCorp.Lost_In_Woods.model.GameSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface GameSessionRepository extends JpaRepository<GameSession, Long> {

    // Leaderboard: finished runs, highest progress score first.
    List<GameSession> findTop10ByGameOverTrueOrderByFinalScoreDesc();
}
