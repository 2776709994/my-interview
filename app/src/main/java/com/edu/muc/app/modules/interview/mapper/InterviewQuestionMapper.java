package com.edu.muc.app.modules.interview.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edu.muc.app.modules.interview.domain.InterviewQuestion;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface InterviewQuestionMapper extends BaseMapper<InterviewQuestion> {
}
