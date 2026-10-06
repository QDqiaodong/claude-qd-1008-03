package com.golf.course.dto;

import java.util.List;

/**
 * 正式排行榜快照的冻结内容：序列化为 JSON 存入
 * {@code leaderboard_snapshot.payload_json}，生成后永不修改。
 * 之后无论成绩如何补录/修改/删除，读取接口都只返回这份内容。
 */
public record LeaderboardPayload(
        Long tournamentId,
        String tournamentNo,
        String tournamentName,
        Integer scoreRows,
        List<LeaderboardRow> rows) {
}
