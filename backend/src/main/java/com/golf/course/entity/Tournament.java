package com.golf.course.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;

/**
 * 赛事：球场组织的比赛，含报名、进行、结束三个阶段。
 *
 * <p>tournamentNo 唯一；状态只能逐级推进：报名中 → 进行中 → 已结束。</p>
 *
 * <p>courseRating / slopeRating 为该赛事所用球场的难度参数（差点差值计算的输入），
 * 需成对维护；旧赛事允许为 NULL，涉及轮次在差点计算中会被标记「缺球场难度参数」并排除，
 * 补齐参数后可对受影响球员做定向重算。</p>
 */
@Entity
@Table(name = "tournament")
@Getter
@Setter
public class Tournament {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 赛事编号，唯一 */
    @Column(name = "tournament_no", nullable = false, unique = true, length = 24)
    private String tournamentNo;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    /** 报名中 / 进行中 / 已结束 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "location", length = 48)
    private String location;

    /** 球场难度值（Course Rating），与 slopeRating 成对维护；旧数据可为 NULL */
    @Column(name = "course_rating", precision = 4, scale = 1)
    private BigDecimal courseRating;

    /** 坡度难度（Slope Rating，55 ~ 155），与 courseRating 成对维护；旧数据可为 NULL */
    @Column(name = "slope_rating")
    private Integer slopeRating;

    @Column(name = "note", length = 64)
    private String note;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
