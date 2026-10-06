package com.golf.course.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.golf.course.entity.Score;

public interface ScoreRepository extends JpaRepository<Score, Long> {

    List<Score> findByTournamentId(Long tournamentId);

    List<Score> findByTournamentIdAndPlayerId(Long tournamentId, Long playerId);

    List<Score> findByTournamentIdAndHoleId(Long tournamentId, Long holeId);
}
