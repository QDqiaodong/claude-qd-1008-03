package com.golf.course.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import lombok.Getter;
import lombok.Setter;

/**
 * 赛事排行榜正式快照：一场赛事至多一份（{@code tournament_id} 唯一），
 * 在赛事推进到「已结束」的同一事务内，基于收口前确认的全部参赛成绩一次性生成。
 *
 * <p>两种状态：
 * <ul>
 *   <li>{@code 正式}：{@code payloadJson} 冻结完整排名（名次/球员/轮次/逐洞/总杆/相对标准杆）。
 *       之后排行榜读取接口只返回这份快照——成绩事后的补录、修改、删除都不会再改变名次。</li>
 *   <li>{@code 生成失败}：收口校验未过（缺成绩 / 成绩属于不存在的球员 / 杆数不合法）。
 *       {@code payloadJson} 必为 NULL（绝不留下半份正式结果），{@code failReason}
 *       记录具体赛事、球员和洞号；临时排名仍可查询，补齐数据后重试会覆盖本行。</li>
 * </ul>
 *
 * <p>{@code version} 为 JPA {@link Version} 乐观锁，叠加 {@code tournament_id} 唯一约束：
 * 多人同时推进赛事 / 补生成快照时，只允许一个版本成为正式快照，后到者失败并提示重新读取。</p>
 */
@Entity
@Table(name = "leaderboard_snapshot")
@Getter
@Setter
public class LeaderboardSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属赛事（DB 唯一约束：一场赛事只有一份快照行） */
    @Column(name = "tournament_id", nullable = false, unique = true)
    private Long tournamentId;

    /** 正式 / 生成失败 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 乐观锁版本号：并发生成/重试时只有基于最新版本的一次写成功 */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** 正式快照内容（排名行完整 JSON）；生成失败时必为 NULL */
    @Column(name = "payload_json")
    private String payloadJson;

    /** 纳入快照的成绩行数 */
    @Column(name = "score_rows")
    private Integer scoreRows;

    /** 最近一次生成失败原因（含具体赛事、球员、洞号） */
    @Column(name = "fail_reason", length = 1024)
    private String failReason;

    @Column(name = "generated_at")
    private LocalDateTime generatedAt;
}
