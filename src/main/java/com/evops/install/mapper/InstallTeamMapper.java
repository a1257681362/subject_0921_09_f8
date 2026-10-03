package com.evops.install.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.install.entity.InstallTeam;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface InstallTeamMapper extends BaseMapper<InstallTeam> {
    @Select("SELECT NEXT VALUE FOR seq_install_team_no")
    long nextTeamSeq();
}
