package com.golf.course.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.golf.course.entity.Tournament;

import jakarta.persistence.LockModeType;

public interface TournamentRepository extends JpaRepository<Tournament, Long> {

    Optional<Tournament> findByTournamentNo(String tournamentNo);

    /**
     * 行级写锁读取（SELECT ... FOR UPDATE）：赛事收口（→已结束）与成绩录入都走它，
     * 把「推进赛事 / 补录成绩」串行化——要么成绩先入库被收口校验看见，
     * 要么收口先生效、迟到的成绩录入看到「已结束」被拒，保证快照的数据边界干净。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Tournament t where t.id = :id")
    Optional<Tournament> findByIdForUpdate(@Param("id") Long id);

    /** 缺球场难度参数（courseRating / slopeRating 任一缺失）的赛事——差点计算的明确缺项 */
    @Query("select t from Tournament t where t.courseRating is null or t.slopeRating is null "
            + "order by t.endDate, t.id")
    List<Tournament> findMissingDifficulty();
}
