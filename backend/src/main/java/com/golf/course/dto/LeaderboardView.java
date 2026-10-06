package com.golf.course.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 排行榜查询视图（GET 接口返回）：
 *
 * <ul>
 *   <li>{@code type=临时}：赛事报名中/进行中（或历史赛事未生成快照）时，
 *       按当前已录入成绩现算的临时排名，明确标注不能当正式成绩；
 *       若上次正式快照生成失败，{@code failReason} 带具体原因。</li>
 *   <li>{@code type=正式}：赛事收口时生成的唯一冻结快照，
 *       之后刷新页面或重读接口都只返回这份内容。</li>
 * </ul>
 */
public record LeaderboardView(
        Long tournamentId,
        String tournamentNo,
        String tournamentName,
        String tournamentStatus,
        String type,
        Long version,
        LocalDateTime generatedAt,
        Integer scoreRows,
        String failReason,
        List<LeaderboardRow> rows) {
}
