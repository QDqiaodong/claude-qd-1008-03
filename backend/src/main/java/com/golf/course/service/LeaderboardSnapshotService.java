package com.golf.course.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.golf.course.common.BizException;
import com.golf.course.common.ConflictException;
import com.golf.course.common.Dict;
import com.golf.course.dto.LeaderboardPayload;
import com.golf.course.dto.LeaderboardRow;
import com.golf.course.dto.LeaderboardView;
import com.golf.course.entity.Hole;
import com.golf.course.entity.LeaderboardSnapshot;
import com.golf.course.entity.Player;
import com.golf.course.entity.Tournament;
import com.golf.course.entity.TournamentEntry;
import com.golf.course.repository.HoleRepository;
import com.golf.course.repository.LeaderboardSnapshotRepository;
import com.golf.course.repository.PlayerRepository;
import com.golf.course.repository.ScoreRepository;
import com.golf.course.repository.ScoreRepository.ScoreRow;
import com.golf.course.repository.TournamentEntryRepository;
import com.golf.course.repository.TournamentRepository;

/**
 * 赛事排行榜快照一致性业务。
 *
 * <p>数据边界：
 * <ul>
 *   <li><b>报名中 / 进行中</b>：排行榜按当前已录入成绩（{@code score} 表）现算，
 *       一律标注「临时」，不得当作正式成绩；</li>
 *   <li><b>推进到已结束</b>：在同一事务内先做收口校验（缺成绩 / 成绩属于不存在的球员 /
 *       杆数不合法，逐条指出赛事、球员、洞号），通过后基于收口前确认的全部参赛成绩
 *       生成唯一正式快照；校验不过则整体回滚——赛事状态不退、快照行不写，
 *       失败原因经独立事务落库，临时排名仍可查询；</li>
 *   <li><b>已结束</b>：读取接口只返回冻结快照，之后的成绩补录/修改/删除不改变名次。</li>
 * </ul>
 *
 * <p>并发口径：赛事行悲观写锁串行化「推进赛事 vs 录入成绩」；快照行
 * {@code tournament_id} 唯一约束 + {@code @Version} 保证多人同时收口/补生成时
 * 只允许一个版本成为正式快照，后到者收到 409「数据已变化」。</p>
 */
@Service
public class LeaderboardSnapshotService {

    private final LeaderboardSnapshotRepository snapshotRepository;
    private final TournamentRepository tournamentRepository;
    private final ScoreRepository scoreRepository;
    private final TournamentEntryRepository entryRepository;
    private final PlayerRepository playerRepository;
    private final HoleRepository holeRepository;
    private final LeaderboardSnapshotFailureRecorder failureRecorder;
    private final ObjectMapper objectMapper;

    public LeaderboardSnapshotService(LeaderboardSnapshotRepository snapshotRepository,
                                      TournamentRepository tournamentRepository,
                                      ScoreRepository scoreRepository,
                                      TournamentEntryRepository entryRepository,
                                      PlayerRepository playerRepository,
                                      HoleRepository holeRepository,
                                      LeaderboardSnapshotFailureRecorder failureRecorder,
                                      ObjectMapper objectMapper) {
        this.snapshotRepository = snapshotRepository;
        this.tournamentRepository = tournamentRepository;
        this.scoreRepository = scoreRepository;
        this.entryRepository = entryRepository;
        this.playerRepository = playerRepository;
        this.holeRepository = holeRepository;
        this.failureRecorder = failureRecorder;
        this.objectMapper = objectMapper;
    }

    // ================================================================
    // 查询：有正式快照只读快照，否则现算临时排名
    // ================================================================

    /**
     * 排行榜视图。正式快照一旦存在，永远只返回冻结内容（刷新、重读都一样）；
     * 否则按当前成绩现算临时排名并标注「临时」，同时带出最近一次生成失败原因。
     */
    @Transactional(readOnly = true)
    public LeaderboardView view(Long tournamentId) {
        Tournament tournament = tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new BizException("赛事不存在：" + tournamentId));
        Optional<LeaderboardSnapshot> existing = snapshotRepository.findByTournamentId(tournamentId);
        if (existing.isPresent() && Dict.SNAPSHOT_OFFICIAL.equals(existing.get().getStatus())) {
            LeaderboardSnapshot snap = existing.get();
            LeaderboardPayload payload = readPayload(snap);
            return new LeaderboardView(tournament.getId(), tournament.getTournamentNo(),
                    tournament.getName(), tournament.getStatus(),
                    Dict.SNAPSHOT_OFFICIAL, snap.getVersion(), snap.getGeneratedAt(),
                    snap.getScoreRows(), null, payload.rows());
        }
        List<ScoreRow> scores = scoreRepository.findRowsByTournamentId(tournamentId);
        Map<Long, Player> players = loadPlayersOf(scores);
        Map<Long, Hole> holes = loadHolesOf(scores);
        List<LeaderboardRow> rows = buildRows(scores, players, holes);
        String failReason = existing.map(LeaderboardSnapshot::getFailReason).orElse(null);
        return new LeaderboardView(tournament.getId(), tournament.getTournamentNo(),
                tournament.getName(), tournament.getStatus(),
                Dict.LEADERBOARD_PROVISIONAL, null, null, scores.size(), failReason, rows);
    }

    // ================================================================
    // 收口生成：随「推进到已结束」同事务执行；失败整体回滚
    // ================================================================

    /**
     * 生成正式快照（加入调用方事务——赛事收口时与状态推进同生共死）。
     * 校验失败：失败原因经独立事务落库后抛 {@link BizException}，主事务回滚，
     * 不留下半份正式结果。
     */
    @Transactional
    public LeaderboardSnapshot generateOfficialSnapshot(Tournament tournament) {
        Long tournamentId = tournament.getId();
        Optional<LeaderboardSnapshot> existing = snapshotRepository.findByTournamentId(tournamentId);
        if (existing.isPresent() && Dict.SNAPSHOT_OFFICIAL.equals(existing.get().getStatus())) {
            throw new ConflictException("赛事「" + tournament.getName() + "」的正式排行榜快照已存在（v"
                    + existing.get().getVersion() + "），不能重复生成；请刷新查看当前状态");
        }

        List<ScoreRow> scores = scoreRepository.findRowsByTournamentId(tournamentId);
        List<TournamentEntry> entries = entryRepository.findByTournamentIdOrderByIdAsc(tournamentId);
        Map<Long, Player> players = loadPlayersOf(scores);
        Map<Long, Hole> holes = loadHolesOf(scores);
        List<Hole> openHoles = openHoles();

        List<String> errors = validate(tournament, scores, entries, players, holes, openHoles);
        if (!errors.isEmpty()) {
            String reason = "赛事「" + tournament.getName() + "」正式排行榜快照生成失败："
                    + String.join("；", errors);
            failureRecorder.record(tournamentId, reason);
            throw new BizException(reason);
        }

        List<LeaderboardRow> rows = buildRows(scores, players, holes);
        LeaderboardPayload payload = new LeaderboardPayload(tournamentId,
                tournament.getTournamentNo(), tournament.getName(), scores.size(), rows);
        LeaderboardSnapshot snap = existing.orElseGet(LeaderboardSnapshot::new);
        snap.setTournamentId(tournamentId);
        snap.setStatus(Dict.SNAPSHOT_OFFICIAL);
        snap.setPayloadJson(toJson(payload));
        snap.setScoreRows(scores.size());
        snap.setFailReason(null);
        snap.setGeneratedAt(LocalDateTime.now());
        try {
            // saveAndFlush 让唯一约束冲突在此暴露：并发收口时只允许一个版本成为正式快照
            return snapshotRepository.saveAndFlush(snap);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("赛事「" + tournament.getName()
                    + "」的正式排行榜快照已由他人先生成，本次操作未生效；请刷新查看当前状态");
        }
    }

    /**
     * 历史已结束赛事补生成正式快照（这些赛事在本功能上线前就已结束，没有快照）。
     * 与收口同一份校验与生成逻辑；已结束赛事的成绩录入已被阻断，快照生成后即冻结。
     */
    @Transactional
    public LeaderboardView generateForEndedTournament(Long tournamentId) {
        Tournament tournament = tournamentRepository.findByIdForUpdate(tournamentId)
                .orElseThrow(() -> new BizException("赛事不存在：" + tournamentId));
        if (!"已结束".equals(tournament.getStatus())) {
            throw new BizException("赛事「" + tournament.getName() + "」尚未收口（当前："
                    + tournament.getStatus() + "），正式快照只能在赛事结束时生成；当前排行榜为临时结果");
        }
        generateOfficialSnapshot(tournament);
        return view(tournamentId);
    }

    // ================================================================
    // 收口校验：缺成绩 / 成绩属于不存在的球员 / 杆数不合法，逐条指出赛事+球员+洞号
    // ================================================================

    private List<String> validate(Tournament tournament, List<ScoreRow> scores,
                                  List<TournamentEntry> entries,
                                  Map<Long, Player> players, Map<Long, Hole> holes,
                                  List<Hole> openHoles) {
        List<String> errors = new ArrayList<>();
        if (entries.isEmpty()) {
            errors.add("赛事「" + tournament.getName() + "」没有任何参赛球员，无法生成正式排行榜");
        }
        if (openHoles.isEmpty()) {
            errors.add("当前没有任何「开放」球洞，无法界定应打成绩范围");
        }

        // ① 逐行校验成绩本身：球员是否存在、球洞是否存在、杆数是否合法、是否重复
        Map<String, Integer> rowCount = new LinkedHashMap<>();
        for (ScoreRow s : scores) {
            Player player = players.get(s.getPlayerId());
            Hole hole = holes.get(s.getHoleId());
            String who = player == null ? "不存在的球员（ID=" + s.getPlayerId() + "）"
                    : "球员「" + player.getName() + "」";
            String where = hole == null ? "不存在的球洞（ID=" + s.getHoleId() + "）"
                    : "第 " + hole.getHoleNo() + " 洞";
            if (player == null) {
                errors.add("成绩记录 #" + s.getId() + " 属于不存在的球员（ID=" + s.getPlayerId()
                        + "），第 " + s.getRoundNo() + " 轮，" + where);
            }
            if (hole == null) {
                errors.add(who + "第 " + s.getRoundNo() + " 轮成绩记录 #" + s.getId()
                        + " 指向不存在的球洞（ID=" + s.getHoleId() + "）");
            }
            if (s.getStrokes() == null || s.getStrokes() < Dict.MIN_STROKES) {
                errors.add(who + "第 " + s.getRoundNo() + " 轮" + where + "杆数不合法（"
                        + s.getStrokes() + "，须 ≥ " + Dict.MIN_STROKES + "）");
            }
            rowCount.merge(groupKey(s.getPlayerId(), s.getRoundNo(), s.getHoleId()), 1, Integer::sum);
        }
        rowCount.forEach((key, count) -> {
            if (count > 1) {
                String[] parts = key.split("\\|");
                errors.add(describePlayer(players, Long.valueOf(parts[0])) + "第 " + parts[1] + " 轮"
                        + describeHole(holes, Long.valueOf(parts[2])) + "存在 " + count
                        + " 条重复成绩，须先去重");
            }
        });

        // ② 参赛完整性：每个参赛名额 × 每个应打轮次 × 每个开放洞都要有合法成绩
        Set<String> valid = scores.stream()
                .filter(s -> players.containsKey(s.getPlayerId()) && holes.containsKey(s.getHoleId())
                        && s.getStrokes() != null && s.getStrokes() >= Dict.MIN_STROKES)
                .map(s -> groupKey(s.getPlayerId(), s.getRoundNo(), s.getHoleId()))
                .collect(Collectors.toSet());
        for (TournamentEntry entry : entries) {
            Player player = entry.getPlayer();
            if (player == null) {
                errors.add("参赛名额 #" + entry.getId() + " 指向不存在的球员，无法核对应打成绩");
                continue;
            }
            for (int round = 1; round <= entry.getRoundCount(); round++) {
                List<String> missing = new ArrayList<>();
                for (Hole open : openHoles) {
                    if (!valid.contains(groupKey(player.getId(), round, open.getId()))) {
                        missing.add("第 " + open.getHoleNo() + " 洞");
                    }
                }
                if (!missing.isEmpty()) {
                    errors.add("球员「" + player.getName() + "」第 " + round + " 轮缺少成绩："
                            + String.join("、", missing));
                }
            }
        }
        return errors;
    }

    // ================================================================
    // 排名计算：临时与正式共用同一口径，保证快照就是收口那一刻的临时排名
    // ================================================================

    private List<LeaderboardRow> buildRows(List<ScoreRow> scores,
                                           Map<Long, Player> players, Map<Long, Hole> holes) {
        Map<String, List<ScoreRow>> groups = new LinkedHashMap<>();
        for (ScoreRow s : scores) {
            groups.computeIfAbsent(s.getPlayerId() + "|" + s.getRoundNo(), k -> new ArrayList<>()).add(s);
        }
        List<LeaderboardRow> rows = new ArrayList<>();
        for (Map.Entry<String, List<ScoreRow>> group : groups.entrySet()) {
            List<ScoreRow> lines = group.getValue();
            Long playerId = lines.get(0).getPlayerId();
            Integer roundNo = lines.get(0).getRoundNo();
            Player player = players.get(playerId);
            List<LeaderboardRow.HoleScore> holeScores = lines.stream()
                    .map(s -> {
                        Hole hole = holes.get(s.getHoleId());
                        return new LeaderboardRow.HoleScore(hole == null ? null : hole.getHoleNo(),
                                hole == null ? null : hole.getPar(), s.getStrokes());
                    })
                    .sorted(Comparator.comparing(LeaderboardRow.HoleScore::holeNo,
                            Comparator.nullsLast(Integer::compareTo)))
                    .toList();
            int totalStrokes = lines.stream()
                    .map(s -> s.getStrokes() == null ? 0 : s.getStrokes())
                    .mapToInt(Integer::intValue).sum();
            boolean parComplete = holeScores.stream().allMatch(h -> h.par() != null);
            int totalPar = holeScores.stream().map(h -> h.par() == null ? 0 : h.par())
                    .mapToInt(Integer::intValue).sum();
            rows.add(new LeaderboardRow(null, playerId,
                    player == null ? null : player.getName(), roundNo, holeScores.size(),
                    totalStrokes, totalPar, parComplete ? totalStrokes - totalPar : null,
                    holeScores));
        }
        // 轮次 → 总杆 → 球员ID：确定性排序，同杆按球员ID破平，保证快照内容稳定
        rows.sort(Comparator.comparing(LeaderboardSnapshotService::roundNoOf)
                .thenComparing(LeaderboardRow::totalStrokes)
                .thenComparing(LeaderboardRow::playerId));
        Map<Integer, Integer> roundRank = new HashMap<>();
        List<LeaderboardRow> ranked = new ArrayList<>(rows.size());
        for (LeaderboardRow row : rows) {
            int rank = roundRank.merge(row.roundNo(), 1, Integer::sum);
            ranked.add(new LeaderboardRow(rank, row.playerId(), row.playerName(), row.roundNo(),
                    row.holes(), row.totalStrokes(), row.totalPar(), row.toPar(), row.scores()));
        }
        return ranked;
    }

    // ================================================================
    // 内部辅助
    // ================================================================

    private String groupKey(Long playerId, Integer roundNo, Long holeId) {
        return playerId + "|" + roundNo + "|" + holeId;
    }

    private String describePlayer(Map<Long, Player> players, Long playerId) {
        Player player = players.get(playerId);
        return player == null ? "不存在的球员（ID=" + playerId + "）" : "球员「" + player.getName() + "」";
    }

    private String describeHole(Map<Long, Hole> holes, Long holeId) {
        Hole hole = holes.get(holeId);
        return hole == null ? "不存在的球洞（ID=" + holeId + "）" : "第 " + hole.getHoleNo() + " 洞";
    }

    private Map<Long, Player> loadPlayersOf(List<ScoreRow> scores) {
        Set<Long> ids = scores.stream().map(ScoreRow::getPlayerId).collect(Collectors.toSet());
        return playerRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Player::getId, Function.identity()));
    }

    private Map<Long, Hole> loadHolesOf(List<ScoreRow> scores) {
        Set<Long> ids = scores.stream().map(ScoreRow::getHoleId).collect(Collectors.toSet());
        return holeRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Hole::getId, Function.identity()));
    }

    private List<Hole> openHoles() {
        return holeRepository.findByStatus("开放").stream()
                .sorted(Comparator.comparing(Hole::getHoleNo))
                .toList();
    }

    private String toJson(LeaderboardPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new BizException("排行榜快照序列化失败：" + e.getMessage());
        }
    }

    private LeaderboardPayload readPayload(LeaderboardSnapshot snap) {
        try {
            return objectMapper.readValue(snap.getPayloadJson(), LeaderboardPayload.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new BizException("赛事 " + snap.getTournamentId() + " 的正式排行榜快照无法解析");
        }
    }

    /** 排序兜底：轮次为空时排最后（数据异常防御，正常不会为空） */
    private static Integer roundNoOf(LeaderboardRow row) {
        return Objects.requireNonNullElse(row.roundNo(), Integer.MAX_VALUE);
    }
}
