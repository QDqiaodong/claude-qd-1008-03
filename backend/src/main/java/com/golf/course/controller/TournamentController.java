package com.golf.course.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.golf.course.common.ApiResponse;
import com.golf.course.entity.Tournament;
import com.golf.course.service.TournamentService;

@RestController
@RequestMapping("/api/tournaments")
public class TournamentController {

    private final TournamentService tournamentService;

    public TournamentController(TournamentService tournamentService) {
        this.tournamentService = tournamentService;
    }

    @GetMapping
    public ApiResponse<List<Tournament>> list() {
        return ApiResponse.success(tournamentService.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<Tournament> get(@PathVariable Long id) {
        return ApiResponse.success(tournamentService.get(id));
    }

    @PostMapping
    public ApiResponse<Tournament> create(@RequestBody Tournament input) {
        return ApiResponse.success("赛事已创建", tournamentService.create(input));
    }

    /** 维护基础信息与球场难度参数（状态仍只能走 advance 逐级推进） */
    @PutMapping("/{id}")
    public ApiResponse<Tournament> update(@PathVariable Long id, @RequestBody Tournament input) {
        return ApiResponse.success("赛事已更新", tournamentService.update(id, input));
    }

    /** 状态推进：报名中 → 进行中 → 已结束 */
    @PostMapping("/{id}/advance")
    public ApiResponse<Tournament> advance(@PathVariable Long id, @RequestParam String target) {
        return ApiResponse.success("赛事状态已推进", tournamentService.advance(id, target));
    }
}
