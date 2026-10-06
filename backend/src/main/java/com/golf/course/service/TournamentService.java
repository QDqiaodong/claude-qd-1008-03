package com.golf.course.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.golf.course.common.BizException;
import com.golf.course.common.Dict;
import com.golf.course.dto.SignoffCheck;
import com.golf.course.entity.Tournament;
import com.golf.course.repository.TournamentRepository;

/**
 * 赛事业务。核心规则：状态只能逐级推进：报名中 → 进行中 → 已结束。
 *
 * <p>推进到「已结束」前必须通过记分卡签核检查：全部参赛球员的每一轮记分卡均已签核；
 * 任何草稿 / 待确认 / 争议中 / 缺卡都会阻止收口，并把逐条原因返回给页面。</p>
 */
@Service
public class TournamentService {

    private final TournamentRepository tournamentRepository;
    private final ScorecardService scorecardService;

    public TournamentService(TournamentRepository tournamentRepository,
                             ScorecardService scorecardService) {
        this.tournamentRepository = tournamentRepository;
        this.scorecardService = scorecardService;
    }

    public List<Tournament> list() {
        return tournamentRepository.findAll();
    }

    public Tournament get(Long id) {
        return tournamentRepository.findById(id)
                .orElseThrow(() -> new BizException("赛事不存在：" + id));
    }

    @Transactional
    public Tournament create(Tournament input) {
        if (input.getTournamentNo() == null || input.getTournamentNo().isBlank()) {
            throw new BizException("赛事编号不能为空");
        }
        tournamentRepository.findByTournamentNo(input.getTournamentNo().trim()).ifPresent(t -> {
            throw new BizException("赛事编号已存在：" + input.getTournamentNo());
        });
        if (input.getName() == null || input.getName().isBlank()) {
            throw new BizException("赛事名称不能为空");
        }
        if (!Dict.isValidTournamentStatus(input.getStatus())) {
            input.setStatus("报名中");
        }
        validateDifficulty(input.getCourseRating(), input.getSlopeRating());
        Tournament t = new Tournament();
        t.setTournamentNo(input.getTournamentNo().trim());
        t.setName(input.getName().trim());
        t.setStartDate(input.getStartDate());
        t.setEndDate(input.getEndDate());
        t.setStatus(input.getStatus());
        t.setLocation(input.getLocation());
        t.setCourseRating(input.getCourseRating());
        t.setSlopeRating(input.getSlopeRating());
        t.setNote(input.getNote());
        t.setCreatedAt(LocalDateTime.now());
        return tournamentRepository.save(t);
    }

    /**
     * 维护赛事基础信息与球场难度参数（状态只能走 advance 逐级推进，不在此处改）。
     * 难度参数成对写：courseRating 与 slopeRating 要同时填写或同时留空——
     * 旧赛事补齐参数后，受影响球员可走差点定向重算。
     */
    @Transactional
    public Tournament update(Long id, Tournament input) {
        Tournament t = get(id);
        if (input.getName() != null && !input.getName().isBlank()) {
            t.setName(input.getName().trim());
        }
        if (input.getStartDate() != null) {
            t.setStartDate(input.getStartDate());
        }
        if (input.getEndDate() != null) {
            t.setEndDate(input.getEndDate());
        }
        if (input.getLocation() != null) {
            t.setLocation(input.getLocation());
        }
        if (input.getNote() != null) {
            t.setNote(input.getNote());
        }
        validateDifficulty(input.getCourseRating(), input.getSlopeRating());
        t.setCourseRating(input.getCourseRating());
        t.setSlopeRating(input.getSlopeRating());
        if (t.getStartDate() != null && t.getEndDate() != null
                && t.getStartDate().isAfter(t.getEndDate())) {
            throw new BizException("开始日期不能晚于结束日期");
        }
        return tournamentRepository.save(t);
    }

    /** 球场难度参数需成对维护，且落在合法区间（缺参是允许的——旧数据明确按缺项处理）。 */
    private void validateDifficulty(java.math.BigDecimal courseRating, Integer slopeRating) {
        if ((courseRating == null) != (slopeRating == null)) {
            throw new BizException("球场难度参数需成对维护：courseRating 与 slopeRating 要同时填写或同时留空");
        }
        if (courseRating != null && !Dict.isValidCourseRating(courseRating)) {
            throw new BizException("球场难度值 courseRating 须在 " + Dict.RATING_MIN + " ~ "
                    + Dict.RATING_MAX + " 之间，当前：" + courseRating);
        }
        if (slopeRating != null && !Dict.isValidSlope(slopeRating)) {
            throw new BizException("坡度难度 slopeRating 须为 " + Dict.SLOPE_MIN + " ~ "
                    + Dict.SLOPE_MAX + " 的整数，当前：" + slopeRating);
        }
    }

    @Transactional
    public Tournament advance(Long id, String target) {
        Tournament t = get(id);
        if (!Dict.isValidTournamentStatus(target)) {
            throw new BizException("赛事状态不合法：" + target);
        }
        if (!Dict.canProgressTournament(t.getStatus(), target)) {
            throw new BizException("不允许从「" + t.getStatus() + "」直接转为「" + target + "」");
        }
        if ("已结束".equals(target)) {
            SignoffCheck check = scorecardService.signoffCheck(id);
            if (!check.canClose()) {
                throw new BizException("赛事不能收口：仍有 " + check.blockingReasons().size()
                        + " 条未签核记录——" + String.join("；", check.blockingReasons()));
            }
        }
        t.setStatus(target);
        return tournamentRepository.save(t);
    }
}
