package com.edu.muc.app.modules.resume.controller;


import com.baomidou.mybatisplus.core.metadata.IPage;
import com.edu.muc.app.common.Result;
import com.edu.muc.app.common.exception.BusinessException;
import com.edu.muc.app.infrastructure.export.PdfExportService;
import com.edu.muc.app.modules.resume.domain.Resumes;
import com.edu.muc.app.modules.resume.dto.ResumeDetailDTO;
import com.edu.muc.app.modules.resume.dto.ResumeListItemDTO;
import com.edu.muc.app.modules.resume.service.ResumesService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;


@Slf4j
@RestController
@RequestMapping("/api/resumes")
@RequiredArgsConstructor
public class ResumeController {

    private final ResumesService resumesService;
    private final PdfExportService pdfExportService;


    /**
     * 获取简历列表（分页查询，默认每页 10 个）
     * @param page 页码（从 1 开始）
     * @param size 每页大小
     * @return
     */
    @GetMapping()
    public Result<IPage<ResumeListItemDTO>> getList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.success(resumesService.getListWithPagination(page, size));
    }


    /**
     * 上传简历
     * @param file
     * @return
     * @throws Exception
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) throws Exception {
        Resumes resume = resumesService.upload(file);

        Map<String, Object> result = new HashMap<>();
        result.put("duplicate", false);
        result.put("storage", Map.of("resumeId", resume.getId()));

        return Result.success(result);
    }

    /**
     * 获取简历详情
     * @param id
     * @return
     */
    @GetMapping("/{id}")
    public Result<Resumes> getResume(@PathVariable Long id) {
        Resumes resume = resumesService.getResume(id);
        if (resume == null) return Result.error(404, "简历不存在");
        return Result.success(resume);
    }

    /**
     * 获取简历分析
     * @param id
     * @return
     */
    @GetMapping("/{id}/detail")
    public Result<ResumeDetailDTO> getDetail(@PathVariable Long id) {
        return Result.success(resumesService.getDetail(id));
    }

    /**
     * 删除简历
     * @param id
     * @return
     */
    @DeleteMapping("/{id}")
    public Result<String> delete(@PathVariable Long id) {
        boolean success = resumesService.delete(id);
        if (success) {
            return Result.success("简历删除成功");
        } else {
            return Result.error(404, "简历不存在");
        }
    }


    /**
     * 导出最新简历分析报告 PDF（iText 8）
     */
    @GetMapping("/{id}/report/export")
    public ResponseEntity<byte[]> exportAnalysisReport(@PathVariable Long id) {
        ResumeDetailDTO detail = resumesService.getDetail(id);
        ResumeDetailDTO.AnalysisDTO latest = detail.getAnalyses().stream()
                .max(Comparator.comparing(ResumeDetailDTO.AnalysisDTO::getAnalyzedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElseThrow(() -> new BusinessException("RESUME_ANALYSIS_NOT_FOUND", "暂无分析结果，请先完成分析"));
        byte[] pdf = pdfExportService.exportResumeAnalysis(detail.getFilename(), latest);
        String filename = URLEncoder.encode("简历分析报告.pdf", StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + filename)
                .body(pdf);
    }

    /**
     * 重新分析简历
     * @param id
     * @return
     */
    @PostMapping("/{id}/reanalyze")
    public Result<Void> reanalyze(@PathVariable Long id) {
        resumesService.reanalyze(id);
        return Result.success(null);
}


}



