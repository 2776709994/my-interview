package com.edu.muc.app.modules.ragchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edu.muc.app.modules.ragchat.domain.ChatSession;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSession> {
}
