package com.edu.muc.app.modules.resume.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edu.muc.app.modules.resume.domain.ResumeAnalyses;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
* @author LINJH
* @description 针对表【resume_analyses】的数据库操作Mapper
* @createDate 2026-04-27 22:39:34
* @Entity generator.domain.ResumeAnalyses
*/
public interface ResumeAnalysesMapper extends BaseMapper<ResumeAnalyses> {
    @Select("SELECT * FROM resume_analyses WHERE resume_id = #{resumeId} ORDER BY analyzed_at DESC")
    List<ResumeAnalyses> findByResumeId(@Param("resumeId") Long resumeId);

    /**
     * 批量查询多个简历的最新分析记录
     * 用于解决 N+1 查询问题
     */
    @Select("<script>" +
            "SELECT ra.* FROM resume_analyses ra " +
            "INNER JOIN (" +
            "  SELECT resume_id, MAX(analyzed_at) as max_analyzed_at " +
            "  FROM resume_analyses " +
            "  WHERE resume_id IN " +
            "  <foreach item='id' collection='resumeIds' open='(' separator=',' close=')'>" +
            "    #{id}" +
            "  </foreach>" +
            "  GROUP BY resume_id" +
            ") latest ON ra.resume_id = latest.resume_id AND ra.analyzed_at = latest.max_analyzed_at" +
            "</script>")
    List<ResumeAnalyses> findLatestByResumeIds(@Param("resumeIds") List<Long> resumeIds);

    @Update("UPDATE resume_analyses SET skill_match_score=#{skillMatchScore}, " +
            "structure_score=#{structureScore}, expression_score=#{expressionScore}, " +
            "project_score=#{projectScore}, content_score=#{contentScore}, " +
            "overall_score=#{overallScore}, summary_text=#{summaryText}, " +
            "strengths_json=#{strengthsJson}, suggestions_json=#{suggestionsJson}, " +
            "analyzed_at=now() WHERE id=#{id}")
    int updateAnalysisResult(@Param("id") Long id,
                             @Param("skillMatchScore") Integer skillMatchScore,
                             @Param("structureScore") Integer structureScore,
                             @Param("expressionScore") Integer expressionScore,
                             @Param("projectScore") Integer projectScore,
                             @Param("contentScore") Integer contentScore,
                             @Param("overallScore") Integer overallScore,
                             @Param("summaryText") String summaryText,
                             @Param("strengthsJson") String strengthsJson,
                             @Param("suggestionsJson") String suggestionsJson);

}




