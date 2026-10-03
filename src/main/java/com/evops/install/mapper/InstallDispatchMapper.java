package com.evops.install.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.install.entity.InstallDispatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface InstallDispatchMapper extends BaseMapper<InstallDispatch> {
    @Select("SELECT NEXT VALUE FOR seq_install_dispatch_no")
    long nextDispatchSeq();
}
