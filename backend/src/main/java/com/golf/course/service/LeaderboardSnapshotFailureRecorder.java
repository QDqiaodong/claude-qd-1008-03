package com.golf.course.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.golf.course.common.Dict;
import com.golf.course.entity.LeaderboardSnapshot;
import com.golf.course.repository.LeaderboardSnapshotRepository;

/**
 * 排行榜正式快照「生成失败」记录器。
 *
 * <p>必须独立于调用方事务（{@code REQUIRES_NEW}）：收口校验失败后，推进赛事的主事务
 * 会整体回滚（赛事状态不退、快照行不写），但失败原因要留下来给页面展示——
 * 否则刷新后「生成失败原因」就丢了。单独成 Bean 是为了让 Spring 代理生效
 * （同类自调用不会开启新事务）。</p>
 *
 * <p>失败记录本身不是正式结果：status=生成失败、payload 恒为 NULL，
 * 排行榜读取仍回退到临时排名，因此「不留半份正式结果、临时排名仍可查」。</p>
 */
@Component
public class LeaderboardSnapshotFailureRecorder {

    /** fail_reason 列宽 1024，超长截断（完整原因随本次接口报错返回，不落库） */
    private static final int MAX_REASON_LENGTH = 1000;

    private final LeaderboardSnapshotRepository snapshotRepository;

    public LeaderboardSnapshotFailureRecorder(LeaderboardSnapshotRepository snapshotRepository) {
        this.snapshotRepository = snapshotRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long tournamentId, String reason) {
        LeaderboardSnapshot snap = snapshotRepository.findByTournamentId(tournamentId)
                .orElseGet(LeaderboardSnapshot::new);
        // 防御：正式快照已存在的赛事不会再走到这里（状态机保证），但绝不覆盖正式结果
        if (Dict.SNAPSHOT_OFFICIAL.equals(snap.getStatus())) {
            return;
        }
        snap.setTournamentId(tournamentId);
        snap.setStatus(Dict.SNAPSHOT_FAILED);
        snap.setPayloadJson(null);
        snap.setScoreRows(null);
        snap.setFailReason(reason == null || reason.length() <= MAX_REASON_LENGTH
                ? reason : reason.substring(0, MAX_REASON_LENGTH) + "…");
        snap.setGeneratedAt(LocalDateTime.now());
        snapshotRepository.save(snap);
    }
}
