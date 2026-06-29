package com.edu.muc.app.modules.resume.converter;

import com.edu.muc.app.modules.resume.domain.ResumeAnalyses;
import com.edu.muc.app.modules.resume.domain.Resumes;
import com.edu.muc.app.modules.resume.dto.ResumeDetailDTO;
import com.edu.muc.app.modules.resume.dto.ResumeListItemDTO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.AfterMapping;
import org.mapstruct.ReportingPolicy;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 简历模块对象映射（MapStruct 编译期生成实现）
 * <p>
 * 编译期生成 getter/setter 直调代码，相比运行时反射（BeanUtils/Jackson 转换）
 * 无装箱与反射开销；JSON 字段（strengths/suggestions）通过 @AfterMapping 填充。
 * </p>
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public abstract class ResumeConverter {

    /**
     * 仅用于反序列化 strengths/suggestions 简单列表，无特殊配置需求；
     * ObjectMapper 在配置完成后线程安全，可静态共享
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // ========== 列表项：简历 + 最近一次分析 → 列表 DTO ==========

    @Mapping(target = "id", source = "resume.id")
    @Mapping(target = "filename", source = "resume.originalFilename")
    @Mapping(target = "fileSize", source = "resume.fileSize", defaultValue = "0L")
    @Mapping(target = "uploadedAt", expression = "java(resume.getUploadedAt() != null ? resume.getUploadedAt() : java.time.LocalDateTime.now())")
    @Mapping(target = "accessCount", source = "resume.accessCount", defaultValue = "0")
    @Mapping(target = "latestScore", source = "latestAnalysis.overallScore")
    @Mapping(target = "lastAnalyzedAt", source = "latestAnalysis.analyzedAt")
    @Mapping(target = "interviewCount", constant = "0")
    @Mapping(target = "analyzeStatus", source = "resume.analyzeStatus", defaultValue = "PENDING")
    @Mapping(target = "analyzeError", source = "resume.analyzeError")
    public abstract ResumeListItemDTO toListItemDTO(Resumes resume, ResumeAnalyses latestAnalysis);

    // ========== 详情：简历 → 详情 DTO 基础字段（分析历史由调用方填充） ==========

    @Mapping(target = "filename", source = "originalFilename")
    @Mapping(target = "fileSize", source = "fileSize", defaultValue = "0L")
    @Mapping(target = "uploadedAt", expression = "java(original.getUploadedAt() != null ? original.getUploadedAt() : java.time.LocalDateTime.now())")
    @Mapping(target = "accessCount", source = "accessCount", defaultValue = "0")
    @Mapping(target = "analyzeStatus", source = "analyzeStatus", defaultValue = "PENDING")
    public abstract ResumeDetailDTO toDetailDTO(Resumes original);

    // ========== 分析记录 → 分析 DTO（标量字段编译期直拷） ==========

    @Mapping(target = "strengths", ignore = true)
    @Mapping(target = "suggestions", ignore = true)
    public abstract ResumeDetailDTO.AnalysisDTO toAnalysisDTO(ResumeAnalyses analysis);

    /**
     * JSON 字段填充：strengths/suggestions 以 JSON 字符串持久化，反序列化后写入 DTO
     */
    @AfterMapping
    protected void fillJsonLists(ResumeAnalyses analysis, @MappingTarget ResumeDetailDTO.AnalysisDTO dto) {
        dto.setStrengths(parseList(analysis.getStrengthsJson(), new TypeReference<List<String>>() {}));
        dto.setSuggestions(parseList(analysis.getSuggestionsJson(),
                new TypeReference<List<ResumeDetailDTO.SuggestionDTO>>() {}));
    }

    private <T> List<T> parseList(String json, TypeReference<List<T>> type) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, type);
        } catch (Exception e) {
            return List.of();
        }
    }
}
