package com.golf.course.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.golf.course.entity.Score;

public interface ScoreRepository extends JpaRepository<Score, Long> {

    List<Score> findByTournamentId(Long tournamentId);

    List<Score> findByTournamentIdAndPlayerId(Long tournamentId, Long playerId);

    List<Score> findByTournamentIdAndHoleId(Long tournamentId, Long holeId);

    /**
     * 排行榜收口校验/汇总用的原始行投影：只取外键列本身（player_id / hole_id），
     * 不关联加载 Player / Hole 实体——这样即使历史脏数据指向不存在的球员或球洞，
     * 查询也不会在 JPA 层炸掉，而能由收口校验逐条报出「成绩属于不存在的球员 /
     * 球洞杆数不合法」的具体赛事、球员和洞号。
     */
    @Query("select s.id as id, s.player.id as playerId, s.hole.id as holeId, "
            + "s.roundNo as roundNo, s.strokes as strokes "
            + "from Score s where s.tournament.id = :tournamentId order by s.id")
    List<ScoreRow> findRowsByTournamentId(@Param("tournamentId") Long tournamentId);

    /** 成绩原始行（排行榜快照计算与收口校验的工作单元） */
    interface ScoreRow {
        Long getId();

        Long getPlayerId();

        Long getHoleId();

        Integer getRoundNo();

        Integer getStrokes();
    }
}
