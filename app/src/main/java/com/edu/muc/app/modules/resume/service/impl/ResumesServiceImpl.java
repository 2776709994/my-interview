package com.edu.muc.app.modules.resume.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.edu.muc.app.infrastructure.file.FileStorageService;
import com.edu.muc.app.infrastructure.redis.RedisStreamProducer;
import com.edu.muc.app.modules.resume.domain.Resumes;
import com.edu.muc.app.modules.resume.domain.ResumeAnalyses;
import com.edu.muc.app.modules.resume.dto.ResumeDetailDTO;
import com.edu.muc.app.modules.resume.dto.ResumeListItemDTO;
import com.edu.muc.app.modules.resume.mapper.ResumeAnalysesMapper;
import com.edu.muc.app.modules.resume.service.ResumesService;
import com.edu.muc.app.modules.resume.mapper.ResumesMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.fasterxml.jackson.core.type.TypeReference;

/**
* @author LINJH
* @description 针对表【resumes】的数据库操作Service实现
* @createDate 2026-04-27 15:35:33
*/

@Slf4j
@Service
@RequiredArgsConstructor
public class ResumesServiceImpl extends ServiceImpl<ResumesMapper, Resumes>
    implements ResumesService{


    private final RedisStreamProducer streamProducer;
    private final ResumesMapper resumesMapper;
    private final ResumeAnalysesMapper analysesMapper;
    private final FileStorageService fileStorageService;
    private final ChatClient chatClient;
    
    // 复用 ObjectMapper 实例，提升性能
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Resumes upload(MultipartFile file) throws Exception {
        String storageKey = null;
        try {
            // 1. 计算文件的 MD5 哈希值
            String hash = calculateMD5(file);

            // 2. 检查数据库中是否已有相同哈希的简历（即内容相同的文件）
            Resumes existing = resumesMapper.selectOne(
                    new LambdaQueryWrapper<Resumes>().eq(Resumes::getFileHash, hash));

            if (existing != null) {
                // 已存在，直接对这条旧记录触发一次新的 AI 分析，然后返回它
                streamProducer.sendResumeAnalysisTask(String.valueOf(existing.getId()));
                return existing;
            }

            // 3. 全新文件：存 MinIO → 插入数据库 → 发送分析任务
            storageKey = fileStorageService.store(file);
            String storageUrl = fileStorageService.getFileUrl(storageKey);

            log.info("🔍 [URL长度检查] storageKey: {}", storageKey);
            log.info("🔍 [URL长度检查] storageUrl: {} (长度: {})", storageUrl, storageUrl.length());

            Resumes resume = new Resumes();
            resume.setOriginalFilename(file.getOriginalFilename());
            resume.setStorageKey(storageKey);
            resume.setStorageUrl(storageUrl);
            resume.setFileHash(hash);
            resume.setFileSize(file.getSize());
            resume.setContentType(file.getContentType());
            resume.setAnalyzeStatus("PENDING");
            resume.setAccessCount(0);  // 初始化访问次数为 0
            resume.setUploadedAt(LocalDateTime.now());
            resumesMapper.insert(resume);

            streamProducer.sendResumeAnalysisTask(String.valueOf(resume.getId()));

            return resume;
        } catch (Exception e) {
            // 发生异常时，回滚数据库操作，并清理已上传的 MinIO 文件
            if (storageKey != null) {
                try {
                    fileStorageService.delete(storageKey);
                    log.warn("⚠️ 上传失败，已清理 MinIO 孤儿文件: {}", storageKey);
                } catch (Exception cleanupEx) {
                    log.error("❌ 清理 MinIO 孤儿文件失败: {}", storageKey, cleanupEx);
                }
            }
            throw e;
        }
    }

    @Override
    public Resumes getResume(Long id) {
        return resumesMapper.selectById(id);
    }


    @Override
    public List<ResumeListItemDTO> getList() {
        // 1. 查询所有简历
        List<Resumes> resumesList = resumesMapper.selectList(new LambdaQueryWrapper<>());
        if (resumesList.isEmpty()) {
            return List.of();
        }

        // 2. 批量查询所有简历的最新分析记录（解决 N+1 问题）
        List<Long> resumeIds = resumesList.stream()
                .map(Resumes::getId)
                .collect(Collectors.toList());

        List<ResumeAnalyses> latestAnalyses = analysesMapper.findLatestByResumeIds(resumeIds);

        // 3. 将分析记录转为 Map，方便快速查找
        Map<Long, ResumeAnalyses> analysesMap = latestAnalyses.stream()
                .collect(Collectors.toMap(
                        ResumeAnalyses::getResumeId,
                        analysis -> analysis,
                        (existing, replacement) -> existing  // 如果有重复，保留第一个
                ));

        // 4. 组装 DTO
        return resumesList.stream()
                .map(resume -> toListItemDTO(resume, analysesMap.get(resume.getId())))
                .toList();
    }


    /**
     * 获取简历列表（带分页）
     * @param page 页码（从 1 开始）
     * @param size 每页大小
     * @return
     */
    @Override
    public Map<String, Object> getListWithPagination(int page, int size) {
        // 参数校验
        if (page < 1) page = 1;

        // 1. 查询总数
        long total = resumesMapper.selectCount(new LambdaQueryWrapper<>());

        // 2. 分页查询简历
        List<Resumes> resumesList = resumesMapper.selectList(
                new LambdaQueryWrapper<Resumes>()
                        .orderByDesc(Resumes::getUploadedAt)
                        .last("LIMIT " + size + " OFFSET " + ((page - 1) * size))
        );

        if (resumesList.isEmpty()) {
            Map<String, Object> result = new HashMap<>();
            result.put("list", List.of());
            result.put("total", 0);
            result.put("page", page);
            result.put("size", size);
            result.put("totalPages", 0);
            return result;
        }

        // 3. 批量查询最新分析记录
        List<Long> resumeIds = resumesList.stream()
                .map(Resumes::getId)
                .collect(Collectors.toList());

        List<ResumeAnalyses> latestAnalyses = analysesMapper.findLatestByResumeIds(resumeIds);

        Map<Long, ResumeAnalyses> analysesMap = latestAnalyses.stream()
                .collect(Collectors.toMap(
                        ResumeAnalyses::getResumeId,
                        analysis -> analysis,
                        (existing, replacement) -> existing
                ));

        // 4. 组装 DTO
        List<ResumeListItemDTO> dtoList = resumesList.stream()
                .map(resume -> toListItemDTO(resume, analysesMap.get(resume.getId())))
                .toList();

        // 5. 返回分页结果
        Map<String, Object> result = new HashMap<>();
        result.put("list", dtoList);
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        result.put("totalPages", (int) Math.ceil((double) total / size));

        return result;
    }

    @Override
    @Transactional
    public boolean delete(Long id) {
        // 1. 检查简历是否存在
        Resumes resume = resumesMapper.selectById(id);
        if (resume == null) {
            return false;
        }

        // 2. 删除关联的分析记录（先删子表）
        LambdaQueryWrapper<ResumeAnalyses> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ResumeAnalyses::getResumeId, id);
        analysesMapper.delete(wrapper);

        // 3. 删除简历主记录
        resumesMapper.deleteById(id);

        // 4. 删除 MinIO 上的文件
        try {
            if (resume.getStorageKey() != null) {
                fileStorageService.delete(resume.getStorageKey());
                log.info("✅ 已删除 MinIO 文件: {}", resume.getStorageKey());
            }
        } catch (Exception e) {
            // 记录日志，不影响主流程
            log.error("❌ 删除 MinIO 文件失败: {}", resume.getStorageKey(), e);
        }

        return true;
    }

    private ResumeListItemDTO toListItemDTO(Resumes resume, ResumeAnalyses latestAnalysis) {
        Integer latestScore = null;
        LocalDateTime lastAnalyzedAt = null;

        if (latestAnalysis != null) {
            latestScore = latestAnalysis.getOverallScore();
            lastAnalyzedAt = latestAnalysis.getAnalyzedAt();
        }

        return new ResumeListItemDTO(
                resume.getId(),
                resume.getOriginalFilename(),
                resume.getFileSize() != null ? resume.getFileSize() : 0L,
                resume.getUploadedAt() != null ? resume.getUploadedAt() : LocalDateTime.now(),
                resume.getAccessCount() != null ? resume.getAccessCount() : 0,
                latestScore,
                lastAnalyzedAt,
                0,
                resume.getAnalyzeStatus() != null ? resume.getAnalyzeStatus() : "PENDING",
                null
        );
    }


    @Override
    public ResumeDetailDTO getDetail(Long id) {
        // 1. 查简历信息
        Resumes resume = resumesMapper.selectById(id);
        if (resume == null) {
            throw new RuntimeException("简历不存在");
        }

        // 2. 查所有分析结果（按时间倒序）
        List<ResumeAnalyses> analyses = analysesMapper.findByResumeId(id);

        // 3. 组装 DTO
        ResumeDetailDTO dto = new ResumeDetailDTO();
        dto.setId(resume.getId());
        dto.setFilename(resume.getOriginalFilename());
        dto.setFileSize(resume.getFileSize() != null ? resume.getFileSize() : 0L);
        dto.setContentType(resume.getContentType());
        dto.setStorageUrl(resume.getStorageUrl());
        dto.setUploadedAt(resume.getUploadedAt() != null ? resume.getUploadedAt() : LocalDateTime.now());
        dto.setAccessCount(resume.getAccessCount() != null ? resume.getAccessCount() : 0);
        dto.setResumeText(resume.getResumeText());
        dto.setAnalyzeStatus(resume.getAnalyzeStatus() != null ? resume.getAnalyzeStatus() : "PENDING");
        dto.setAnalyzeError(resume.getAnalyzeError());

        // 4. 转换分析记录列表
        List<ResumeDetailDTO.AnalysisDTO> analysisDTOs = analyses.stream()
                .map(analysis -> {
                    ResumeDetailDTO.AnalysisDTO analysisDTO = new ResumeDetailDTO.AnalysisDTO();
                    analysisDTO.setId(analysis.getId());
                    analysisDTO.setOverallScore(analysis.getOverallScore());
                    analysisDTO.setSkillMatchScore(analysis.getSkillMatchScore());
                    analysisDTO.setStructureScore(analysis.getStructureScore());
                    analysisDTO.setExpressionScore(analysis.getExpressionScore());
                    analysisDTO.setProjectScore(analysis.getProjectScore());
                    analysisDTO.setContentScore(analysis.getContentScore());
                    analysisDTO.setSummary(analysis.getSummary());
                    analysisDTO.setAnalyzedAt(analysis.getAnalyzedAt());

                    // 解析 strengths JSON
                    try {
                        analysisDTO.setStrengths(OBJECT_MAPPER.readValue(analysis.getStrengthsJson(),
                                new TypeReference<List<String>>() {}));
                    } catch (Exception e) {
                        log.warn("解析 strengths JSON 失败: {}", e.getMessage());
                        analysisDTO.setStrengths(List.of());
                    }

                    // 解析 suggestions JSON
                    try {
                        analysisDTO.setSuggestions(OBJECT_MAPPER.readValue(analysis.getSuggestionsJson(),
                                new TypeReference<List<ResumeDetailDTO.SuggestionDTO>>() {}));
                    } catch (Exception e) {
                        log.warn("解析 suggestions JSON 失败: {}", e.getMessage());
                        analysisDTO.setSuggestions(List.of());
                    }

                    return analysisDTO;
                })
                .toList();

        dto.setAnalyses(analysisDTOs);

        return dto;
    }

    // 计算文件的 MD5 哈希值
    private String calculateMD5(MultipartFile file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(file.getBytes());
        BigInteger bigInt = new BigInteger(1, digest);
        return String.format("%032x", bigInt);
    }
}




