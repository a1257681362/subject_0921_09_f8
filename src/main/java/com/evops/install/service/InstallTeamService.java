package com.evops.install.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.BusinessException;
import com.evops.install.InstallEnums;
import com.evops.install.dto.QualRenewRequest;
import com.evops.install.dto.TeamCreateRequest;
import com.evops.install.entity.InstallQualVersion;
import com.evops.install.entity.InstallTeam;
import com.evops.install.mapper.InstallQualVersionMapper;
import com.evops.install.mapper.InstallTeamMapper;
import com.evops.ledger.support.Operators;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 安装队档案与资质匹配版本：
 * 建档即记资质 v1（INITIAL 直接生效）；换证另起新版（RENEW），旧版置 SUPERSEDED 永久可翻。
 * 资质匹配状态 VALID/EXPIRED 按「有效期至 ≥ 比较日」判定，不固化。
 */
@Service
public class InstallTeamService {

    private final InstallTeamMapper teamMapper;
    private final InstallQualVersionMapper qualVersionMapper;

    public InstallTeamService(InstallTeamMapper teamMapper, InstallQualVersionMapper qualVersionMapper) {
        this.teamMapper = teamMapper;
        this.qualVersionMapper = qualVersionMapper;
    }

    /** 建档：队名唯一；填了证号必须给有效期；建档即记资质 v1（INITIAL 生效）。 */
    @Transactional(rollbackFor = Exception.class)
    public InstallTeam register(TeamCreateRequest request) {
        String teamName = request.getTeamName().trim();
        Long exists = teamMapper.selectCount(
                new LambdaQueryWrapper<InstallTeam>().eq(InstallTeam::getTeamName, teamName));
        if (exists != null && exists > 0) {
            throw new BusinessException("安装队名「" + teamName + "」已存在，重复队名不能建档");
        }
        String certNo = normalizeCert(request.getCertNo(), request.getCertValidUntil());
        String operator = Operators.resolve(request.getOperatorName());

        InstallTeam team = new InstallTeam();
        team.setTeamNo(generateTeamNo());
        team.setTeamName(teamName);
        team.setContactName(trimTo(request.getContactName(), 64));
        team.setContactPhone(trimTo(request.getContactPhone(), 64));
        team.setStatus(InstallEnums.TEAM_ACTIVE);
        team.setCertNo(certNo);
        team.setCertValidUntil(certNo != null ? request.getCertValidUntil() : null);
        team.setOperatorName(operator);
        team.setRemark(trimTo(request.getRemark(), 255));
        try {
            teamMapper.insert(team);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("安装队名「" + teamName + "」已存在，重复队名不能建档");
        }

        InstallQualVersion v1 = appendQualVersion(team, InstallEnums.QUAL_INITIAL, operator,
                "建档初始资质");
        team.setCurrentQualVersionId(v1.getId());
        team.setCurrentQualVersionNo(v1.getVersionNo());
        teamMapper.updateById(team);
        return fillQualStatus(team);
    }

    /** 换证：另起资质新版本（RENEW），旧版置 SUPERSEDED；队档案指向新生效版。 */
    @Transactional(rollbackFor = Exception.class)
    public InstallTeam renew(String teamNo, QualRenewRequest request) {
        InstallTeam team = requireByNo(teamNo);
        String certNo = normalizeCert(request.getCertNo(), request.getCertValidUntil());
        String operator = Operators.resolve(request.getOperatorName());

        // 旧生效版置 SUPERSEDED（留底不删）
        if (team.getCurrentQualVersionId() != null) {
            InstallQualVersion current = qualVersionMapper.selectById(team.getCurrentQualVersionId());
            if (current != null && InstallEnums.QUAL_VERSION_EFFECTIVE.equals(current.getStatus())) {
                current.setStatus(InstallEnums.QUAL_VERSION_SUPERSEDED);
                qualVersionMapper.updateById(current);
            }
        }
        team.setCertNo(certNo);
        team.setCertValidUntil(certNo != null ? request.getCertValidUntil() : null);
        team.setOperatorName(operator);
        teamMapper.updateById(team);

        InstallQualVersion version = appendQualVersion(team, InstallEnums.QUAL_RENEW, operator,
                trimTo(request.getRemark(), 255) != null ? request.getRemark().trim() : "换证");
        team.setCurrentQualVersionId(version.getId());
        team.setCurrentQualVersionNo(version.getVersionNo());
        teamMapper.updateById(team);
        return fillQualStatus(team);
    }

    /** 停用/启用：停用队不参与自动匹配，也不能手工派工。 */
    @Transactional(rollbackFor = Exception.class)
    public InstallTeam changeStatus(String teamNo, String status, String operatorName) {
        InstallTeam team = requireByNo(teamNo);
        String normalized = status.trim().toUpperCase();
        if (!InstallEnums.TEAM_ACTIVE.equals(normalized) && !InstallEnums.TEAM_PAUSED.equals(normalized)) {
            throw new BusinessException("不支持的状态: " + status + "（ACTIVE 在册 / PAUSED 停用）");
        }
        team.setStatus(normalized);
        team.setOperatorName(Operators.resolve(operatorName));
        teamMapper.updateById(team);
        return fillQualStatus(team);
    }

    /** 翻查：关键字（队号/队名）/ 状态过滤；每队带当天资质匹配状态。 */
    public List<InstallTeam> list(String keyword, String status) {
        LambdaQueryWrapper<InstallTeam> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.trim().isEmpty()) {
            wrapper.eq(InstallTeam::getStatus, status.trim().toUpperCase());
        }
        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(InstallTeam::getTeamNo, kw).or().like(InstallTeam::getTeamName, kw));
        }
        List<InstallTeam> teams = teamMapper.selectList(wrapper.orderByAsc(InstallTeam::getTeamNo));
        teams.forEach(this::fillQualStatus);
        return teams;
    }

    public List<InstallQualVersion> qualVersions(String teamNo) {
        InstallTeam team = requireByNo(teamNo);
        return qualVersionMapper.selectList(new LambdaQueryWrapper<InstallQualVersion>()
                .eq(InstallQualVersion::getTeamId, team.getId())
                .orderByAsc(InstallQualVersion::getVersionNo));
    }

    public InstallTeam requireByNo(String teamNo) {
        if (teamNo == null || teamNo.trim().isEmpty()) {
            throw new BusinessException("安装队号不能为空");
        }
        InstallTeam team = teamMapper.selectOne(new LambdaQueryWrapper<InstallTeam>()
                .eq(InstallTeam::getTeamNo, teamNo.trim()));
        if (team == null) {
            throw new BusinessException("安装队不存在: " + teamNo);
        }
        return fillQualStatus(team);
    }

    /** 资质匹配状态：有效期至 ≥ 比较日 = VALID 有效，否则 EXPIRED 已过期（无证按 EXPIRED 处理，接不了高空点位）。 */
    public String qualStatusOf(InstallTeam team, LocalDate compareDate) {
        if (team.getCertNo() == null || team.getCertValidUntil() == null) {
            return InstallEnums.QUAL_EXPIRED;
        }
        return team.getCertValidUntil().isBefore(compareDate)
                ? InstallEnums.QUAL_EXPIRED : InstallEnums.QUAL_VALID;
    }

    /** 高空点位校验：队的高空证必须在计划施工日仍有效；过期/无证直接拒（自动匹配与手工派工同一口径）。 */
    public void requireQualifiedForHighAltitude(InstallTeam team, LocalDate plannedDate) {
        if (team.getCertNo() == null || team.getCertValidUntil() == null) {
            throw new BusinessException("安装队「" + team.getTeamName() + "」没有高空证，接不了高空点位");
        }
        if (team.getCertValidUntil().isBefore(plannedDate)) {
            throw new BusinessException("安装队「" + team.getTeamName() + "」高空证已于 "
                    + team.getCertValidUntil() + " 过期，接不了计划施工日 " + plannedDate + " 的高空点位");
        }
    }

    private InstallTeam fillQualStatus(InstallTeam team) {
        team.setQualStatus(qualStatusOf(team, LocalDate.now()));
        return team;
    }

    private InstallQualVersion appendQualVersion(InstallTeam team, String changeType,
                                                 String operator, String remark) {
        Integer maxVersion = qualVersionMapper.selectList(new LambdaQueryWrapper<InstallQualVersion>()
                        .eq(InstallQualVersion::getTeamId, team.getId())
                        .orderByDesc(InstallQualVersion::getVersionNo).last("LIMIT 1"))
                .stream().findFirst().map(InstallQualVersion::getVersionNo).orElse(0);
        InstallQualVersion version = new InstallQualVersion();
        version.setTeamId(team.getId());
        version.setVersionNo(maxVersion + 1);
        version.setChangeType(changeType);
        version.setCertNo(team.getCertNo());
        version.setCertValidUntil(team.getCertValidUntil());
        version.setStatus(InstallEnums.QUAL_VERSION_EFFECTIVE);
        version.setOperatorName(operator);
        version.setRemark(remark);
        qualVersionMapper.insert(version);
        return version;
    }

    /** 证号与有效期成对校验：填了证号必须给有效期；只给有效期不给证号也拒。 */
    private String normalizeCert(String certNo, LocalDate validUntil) {
        String cert = certNo != null && !certNo.trim().isEmpty() ? certNo.trim() : null;
        if (cert != null && validUntil == null) {
            throw new BusinessException("填了高空证号必须给有效期至，安全员查的就是这个");
        }
        if (cert == null && validUntil != null) {
            throw new BusinessException("没填高空证号却给了有效期至，请把证号补上");
        }
        return cert;
    }

    private String generateTeamNo() {
        return String.format("TM%08d", teamMapper.nextTeamSeq());
    }

    private String trimTo(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
