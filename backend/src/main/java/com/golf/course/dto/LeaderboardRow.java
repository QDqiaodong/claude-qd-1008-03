package com.golf.course.dto;

import java.util.List;

/**
 * 排行榜排名行：一名球员在一个轮次的汇总成绩。
 * 临时排名（现算）与正式快照（冻结）共用同一结构，保证收口前后口径一致。
 */
public record LeaderboardRow(
        Integer rank,
        Long playerId,
        String playerName,
        Integer roundNo,
        Integer holes,
        Integer totalStrokes,
        Integer totalPar,
        Integer toPar,
        List<HoleScore> scores) {

    /** 逐洞成绩明细（快照自包含，便于事后审计「基于哪些成绩定榜」） */
    public record HoleScore(
            Integer holeNo,
            Integer par,
            Integer strokes) {
    }
}
