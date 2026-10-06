package com.golf.course.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.golf.course.common.BizException;
import com.golf.course.common.Dict;
import com.golf.course.entity.Hole;
import com.golf.course.entity.Player;
import com.golf.course.entity.Score;
import com.golf.course.entity.Tournament;
import com.golf.course.repository.HoleRepository;
import com.golf.course.repository.PlayerRepository;
import com.golf.course.repository.ScoreRepository;
import com.golf.course.repository.TournamentRepository;

/**
 * 成绩业务：按洞录入杆数并与标准杆对比。
 *
 * <p>核心规则：① 球员 / 赛事 / 球洞必须存在；② 单洞杆数须 ≥ 1；③ 录入后可通过
 * {@code toPar}（杆数 - par）查看相对标准杆的成绩。</p>
 *
 * <p>收口边界：赛事「已结束」后正式排行榜快照已冻结，成绩一律不得再补录
 * （读取接口只认快照，但这里仍显式拒绝，避免库里出现收口后的游离成绩）。
 * 录入与「推进到已结束」共用赛事行悲观写锁：要么本录入先入库、被收口校验看见，
 * 要么收口先生效、本录入看到「已结束」被拒——不会漏进快照生成后的空窗。</p>
 */
@Service
public class ScoreService {

    private final ScoreRepository scoreRepository;
    private final PlayerRepository playerRepository;
    private final TournamentRepository tournamentRepository;
    private final HoleRepository holeRepository;

    public ScoreService(ScoreRepository scoreRepository, PlayerRepository playerRepository,
                        TournamentRepository tournamentRepository, HoleRepository holeRepository) {
        this.scoreRepository = scoreRepository;
        this.playerRepository = playerRepository;
        this.tournamentRepository = tournamentRepository;
        this.holeRepository = holeRepository;
    }

    public List<Score> list(Long tournamentId, Long playerId) {
        if (tournamentId != null && playerId != null) {
            return scoreRepository.findByTournamentIdAndPlayerId(tournamentId, playerId);
        }
        if (tournamentId != null) {
            return scoreRepository.findByTournamentId(tournamentId);
        }
        return scoreRepository.findAll();
    }

    @Transactional
    public Score record(Score input) {
        Player player = resolvePlayer(input.getPlayer());
        Tournament tournament = resolveTournamentForUpdate(input.getTournament());
        if ("已结束".equals(tournament.getStatus())) {
            throw new BizException("赛事「" + tournament.getName()
                    + "」已结束，正式排行榜快照已生成，成绩不能再补录或修改");
        }
        Hole hole = resolveHole(input.getHole());
        if (input.getRoundNo() == null || input.getRoundNo() < 1) {
            throw new BizException("轮次必须大于 0");
        }
        if (input.getStrokes() == null || input.getStrokes() < Dict.MIN_STROKES) {
            throw new BizException("单洞杆数须 ≥ " + Dict.MIN_STROKES);
        }
        Score score = new Score();
        score.setPlayer(player);
        score.setTournament(tournament);
        score.setHole(hole);
        score.setRoundNo(input.getRoundNo());
        score.setStrokes(input.getStrokes());
        score.setCreatedAt(LocalDateTime.now());
        return scoreRepository.save(score);
    }

    private Player resolvePlayer(Player p) {
        if (p == null || p.getId() == null) {
            throw new BizException("必须指定球员");
        }
        return playerRepository.findById(p.getId())
                .orElseThrow(() -> new BizException("球员不存在：" + p.getId()));
    }

    /**
     * 以悲观写锁读取赛事：与「推进到已结束」串行化——要么本事务先录入（被收口校验看见），
     * 要么收口先提交（本事务读到「已结束」被拒），保证正式快照的数据边界。
     */
    private Tournament resolveTournamentForUpdate(Tournament t) {
        if (t == null || t.getId() == null) {
            throw new BizException("必须指定赛事");
        }
        return tournamentRepository.findByIdForUpdate(t.getId())
                .orElseThrow(() -> new BizException("赛事不存在：" + t.getId()));
    }

    private Hole resolveHole(Hole h) {
        if (h == null || h.getId() == null) {
            throw new BizException("必须指定球洞");
        }
        return holeRepository.findById(h.getId())
                .orElseThrow(() -> new BizException("球洞不存在：" + h.getId()));
    }
}
