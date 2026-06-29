package com.edu.muc.app.modules.resume.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.edu.muc.app.common.exception.BusinessException;
import com.edu.muc.app.common.model.AsyncTaskStatus;
import com.edu.muc.app.infrastructure.file.FileHashService;
import com.edu.muc.app.infrastructure.file.FileStorageService;
import com.edu.muc.app.modules.resume.listener.AnalyzeStreamProducer;
import com.edu.muc.app.modules.resume.domain.Resumes;
import com.edu.muc.app.modules.resume.domain.ResumeAnalyses;
import com.edu.muc.app.modules.resume.dto.ResumeDetailDTO;
import com.edu.muc.app.modules.resume.dto.ResumeListItemDTO;
import com.edu.muc.app.modules.resume.mapper.ResumeAnalysesMapper;
import com.edu.muc.app.modules.resume.converter.ResumeConverter;
import com.edu.muc.app.modules.resume.service.ResumesService;
import com.edu.muc.app.modules.resume.mapper.ResumesMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import java.time.LocalDateTime;
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


    private final AnalyzeStreamProducer streamProducer;
    private final ResumesMapper resumesMapper;
    private final ResumeAnalysesMapper analysesMapper;
    private final FileStorageService fileStorageService;
    private final FileHashService fileHashService;
    private final ResumeConverter resumeConverter;
    private final ChatClient chatClient;
    
    // 复用 ObjectMapper 实例，提升性能
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Resumes upload(MultipartFile file) throws Exception {
        String storageKey = null;
        try {
            // 0. 文件大小校验（防止超大文件打爆 MinIO 与 Tika 解析）
            if (file.getSize() > 10 * 1024 * 1024L) {
                throw new BusinessException("FILE_TOO_LARGE", "文件大小超过限制（最大10MB）");
            }

            // 1. 计算文件的 SHA-256 哈希值（内容寻址去重）
            String hash = fileHashService.calculateHash(file);

            // 2. 检查数据库中是否已有相同哈希的简历（即内容相同的文件）
            Resumes existing = resumesMapper.selectOne(
                    new LambdaQueryWrapper<Resumes>().eq(Resumes::getFileHash, hash));

            if (existing != null) {
                // 已存在：重置状态后重新触发一次 AI 分析，然后返回旧记录
                // （消费端状态守卫仅放行 PENDING/PROCESSING，需先回到 PENDING）
                existing.setAnalyzeStatus(AsyncTaskStatus.PENDING.name());
                existing.setAnalyzeError(null);
                resumesMapper.updateById(existing);
                streamProducer.send(existing.getId());
                return existing;
            }

            // 3. 全新文件：存 MinIO → 插入数据库 → 发送分析任务
            storageKey = fileStorageService.store(file);
            String storageUrl = fileStorageService.getFileUrl(storageKey);

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

            // 关键修复：必须在事务提交（afterCommit）后再发送分析任务。
            // 若在提交前 XADD，消费端会立刻读到未提交的行（PostgreSQL READ COMMITTED
            // 下不可见），导致消费端误判"简历不存在"并 ACK 丢弃消息，简历永远卡在 PENDING。
            final Long newResumeId = resume.getId();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        streamProducer.send(newResumeId);
                    } catch (Exception e) {
                        log.error("❌ 事务提交后发送简历分析任务失败（可在前端手动重新分析）: {}", newResumeId, e);
                    }
                }
            });

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
    public void reanalyze(Long id) {
        Resumes resume = resumesMapper.selectById(id);
        if (resume == null) {
            throw new BusinessException("RESUME_NOT_FOUND", "简历不存在");
        }
        // 消费端状态守卫仅放行 PENDING/PROCESSING，
        // 已完成/失败的简历必须先重置回 PENDING 才能被重新领取
        resume.setAnalyzeStatus(AsyncTaskStatus.PENDING.name());
        resume.setAnalyzeError(null);
        resumesMapper.updateById(resume);
        streamProducer.send(id);
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


    @Override
    public IPage<ResumeListItemDTO> getListWithPagination(int page, int size) {
        Page<Resumes> resumePage = resumesMapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<Resumes>()
                        .orderByDesc(Resumes::getUploadedAt)
        );

        List<ResumeListItemDTO> dtoList = buildDtoList(resumePage.getRecords());

        Page<ResumeListItemDTO> result = new Page<>(resumePage.getCurrent(), resumePage.getSize(), resumePage.getTotal());
        result.setRecords(dtoList);
        return result;
    }

    private List<ResumeListItemDTO> buildDtoList(List<Resumes> resumesList) {
        if (resumesList.isEmpty()) {
            return List.of();
        }

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

        return resumesList.stream()
                .map(resume -> toListItemDTO(resume, analysesMap.get(resume.getId())))
                .toList();
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
        return resumeConverter.toListItemDTO(resume, latestAnalysis);
    }


    @Override
    public ResumeDetailDTO getDetail(Long id) {
        // 1. 查简历信息
        Resumes resume = resumesMapper.selectById(id);
        if (resume == null) {
            throw new BusinessException("RESUME_NOT_FOUND", "简历不存在");
        }

        // 2. 查所有分析结果（按时间倒序）
        List<ResumeAnalyses> analyses = analysesMapper.findByResumeId(id);

        // 3. 组装 DTO（MapStruct 编译期映射，null 值兜底由映射器处理）
        ResumeDetailDTO dto = resumeConverter.toDetailDTO(resume);

        // 4. 转换分析记录列表（MapStruct 编译期映射 + @AfterMapping 填充 JSON 字段）
        List<ResumeDetailDTO.AnalysisDTO> analysisDTOs = analyses.stream()
                .map(resumeConverter::toAnalysisDTO)
                .toList();

        dto.setAnalyses(analysisDTOs);

        return dto;
    }

    // 计算文件的 MD5 哈希值
}




