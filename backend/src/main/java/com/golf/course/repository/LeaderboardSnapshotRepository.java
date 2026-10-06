package com.golf.course.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.golf.course.entity.LeaderboardSnapshot;

public interface LeaderboardSnapshotRepository extends JpaRepository<LeaderboardSnapshot, Long> {

    /** 一场赛事至多一份快照行（DB 唯一约束兜底） */
    Optional<LeaderboardSnapshot> findByTournamentId(Long tournamentId);
}
