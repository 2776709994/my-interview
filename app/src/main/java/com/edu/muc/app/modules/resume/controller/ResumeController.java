package com.edu.muc.app.modules.resume.controller;


import com.edu.muc.app.common.Result;
import com.edu.muc.app.infrastructure.redis.RedisStreamProducer;
import com.edu.muc.app.modules.resume.domain.Resumes;
import com.edu.muc.app.modules.resume.dto.ResumeDetailDTO;
import com.edu.muc.app.modules.resume.service.ResumesService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;


@Slf4j
@RestController
@RequestMapping("/api/resumes")
@RequiredArgsConstructor
public class ResumeController {

    private final ResumesService resumesService;
    private final RedisStreamProducer streamProducer;



    /**
     * 获取简历列表（分页查询，默认每页 10 个）
     * @param page 页码（从 1 开始）
     * @return
     */
    @GetMapping()
    public Result<Map<String, Object>> getList(
            @RequestParam(defaultValue = "1") int page) {
        Map<String, Object> result = resumesService.getListWithPagination(page, 10);
        return Result.success(result);
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
     * 重新分析简历
     * @param id
     * @return
     */
    @PostMapping("/{id}/reanalyze")
    public Result<Void> reanalyze(@PathVariable Long id) {
        streamProducer.sendResumeAnalysisTask(String.valueOf(id));
        return Result.success(null);
}


}



