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
        Tournament tournament = resolveTournament(input.getTournament());
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

    private Tournament resolveTournament(Tournament t) {
        if (t == null || t.getId() == null) {
            throw new BizException("必须指定赛事");
        }
        return tournamentRepository.findById(t.getId())
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
