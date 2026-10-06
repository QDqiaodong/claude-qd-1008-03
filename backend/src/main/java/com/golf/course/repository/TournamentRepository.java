package com.golf.course.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.golf.course.entity.Tournament;

public interface TournamentRepository extends JpaRepository<Tournament, Long> {

    Optional<Tournament> findByTournamentNo(String tournamentNo);

    /** 缺球场难度参数（courseRating / slopeRating 任一缺失）的赛事——差点计算的明确缺项 */
    @Query("select t from Tournament t where t.courseRating is null or t.slopeRating is null "
            + "order by t.endDate, t.id")
    List<Tournament> findMissingDifficulty();
}
