package com.golf.course.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.golf.course.common.ApiResponse;
import com.golf.course.dto.LeaderboardView;
import com.golf.course.service.LeaderboardSnapshotService;

/**
 * 赛事排行榜快照接口（嵌在赛事资源下）。
 *
 * <ul>
 *   <li>{@code GET}：报名中/进行中返回当前已录入成绩现算的「临时」排名；
 *       已结束返回收口时生成的唯一「正式」冻结快照——之后无论成绩如何变化，
 *       刷新页面或重读接口都只返回这份快照；上次生成失败时带出具体原因。</li>
 *   <li>{@code POST /generate}：为历史已结束赛事（本功能上线前已收口、无快照）
 *       补生成正式快照；与赛事收口共用同一份校验与并发防护。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/tournaments/{tournamentId}/leaderboard")
public class LeaderboardSnapshotController {

    private final LeaderboardSnapshotService leaderboardSnapshotService;

    public LeaderboardSnapshotController(LeaderboardSnapshotService leaderboardSnapshotService) {
        this.leaderboardSnapshotService = leaderboardSnapshotService;
    }

    /** 排行榜视图：临时排名（现算）或正式快照（冻结），含生成失败原因 */
    @GetMapping
    public ApiResponse<LeaderboardView> view(@PathVariable Long tournamentId) {
        return ApiResponse.success(leaderboardSnapshotService.view(tournamentId));
    }

    /** 历史已结束赛事补生成正式快照（已有正式快照或未收口都会被拒） */
    @PostMapping("/generate")
    public ApiResponse<LeaderboardView> generate(@PathVariable Long tournamentId) {
        return ApiResponse.success("正式排行榜快照已生成并冻结",
                leaderboardSnapshotService.generateForEndedTournament(tournamentId));
    }
}
