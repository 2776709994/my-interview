package com.edu.muc.app.modules.llmprovider.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edu.muc.app.modules.llmprovider.model.LlmProviderEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface LlmProviderMapper extends BaseMapper<LlmProviderEntity> {

    @Select("SELECT * FROM llm_provider_config WHERE enabled = true ORDER BY id ASC")
    List<LlmProviderEntity> findEnabledOrderByIdAsc();
}
