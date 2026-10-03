package com.evops.install.controller;

import com.evops.common.ApiResponse;
import com.evops.install.dto.QualRenewRequest;
import com.evops.install.dto.TeamCreateRequest;
import com.evops.install.dto.TeamStatusRequest;
import com.evops.install.entity.InstallQualVersion;
import com.evops.install.entity.InstallTeam;
import com.evops.install.service.InstallTeamService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/** 安装队档案与资质：建档、换证（资质版本链）、停用启用、翻查。 */
@RestController
@RequestMapping("/api/install/teams")
public class InstallTeamController {

    private final InstallTeamService teamService;

    public InstallTeamController(InstallTeamService teamService) {
        this.teamService = teamService;
    }

    /** 建档：队名唯一；填了高空证号必须给有效期；建档即记资质 v1。 */
    @PostMapping
    public ApiResponse<InstallTeam> register(@Valid @RequestBody TeamCreateRequest request) {
        return ApiResponse.ok(teamService.register(request));
    }

    /** 翻查：?keyword=&status=（每队带当天资质匹配状态 VALID/EXPIRED）。 */
    @GetMapping
    public ApiResponse<List<InstallTeam>> list(@RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) String status) {
        return ApiResponse.ok(teamService.list(keyword, status));
    }

    @GetMapping("/{teamNo}")
    public ApiResponse<InstallTeam> detail(@PathVariable String teamNo) {
        return ApiResponse.ok(teamService.requireByNo(teamNo));
    }

    /** 资质版本链：建档 INITIAL / 换证 RENEW，旧版 SUPERSEDED 可翻。 */
    @GetMapping("/{teamNo}/quals")
    public ApiResponse<List<InstallQualVersion>> qualVersions(@PathVariable String teamNo) {
        return ApiResponse.ok(teamService.qualVersions(teamNo));
    }

    /** 换证：另起资质新版本，旧版置 SUPERSEDED。 */
    @PostMapping("/{teamNo}/quals")
    public ApiResponse<InstallTeam> renew(@PathVariable String teamNo,
                                          @RequestBody QualRenewRequest request) {
        return ApiResponse.ok(teamService.renew(teamNo, request));
    }

    /** 停用/启用：停用队不参与匹配也不能派工。 */
    @PostMapping("/{teamNo}/status")
    public ApiResponse<InstallTeam> changeStatus(@PathVariable String teamNo,
                                                 @Valid @RequestBody TeamStatusRequest request) {
        return ApiResponse.ok(teamService.changeStatus(teamNo, request.getStatus(), request.getOperatorName()));
    }
}
