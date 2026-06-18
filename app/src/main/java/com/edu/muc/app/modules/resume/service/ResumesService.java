package com.edu.muc.app.modules.resume.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.edu.muc.app.modules.resume.domain.Resumes;
import com.edu.muc.app.modules.resume.domain.ResumeAnalyses;
import com.edu.muc.app.modules.resume.dto.ResumeDetailDTO;
import com.edu.muc.app.modules.resume.dto.ResumeDetailDTO;
import com.edu.muc.app.modules.resume.dto.ResumeListItemDTO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
* @author LINJH
* @description 针对表【resumes】的数据库操作Service
* @createDate 2026-04-27 15:35:33
*/
public interface ResumesService extends IService<Resumes>{

    Resumes upload(MultipartFile file) throws Exception;

    /**
     * 重新分析简历：重置状态为 PENDING 后重新入队（已完成/失败的简历也可重新触发）
     */
    void reanalyze(Long id);

    Resumes getResume(Long id);

    List<ResumeListItemDTO> getList();

    boolean delete(Long id);

    ResumeDetailDTO getDetail(Long id);

    /**
     * 分页获取简历列表
     * @param page 页码（从 1 开始）
     * @param size 每页大小
     * @return 分页结果
     */
    IPage<ResumeListItemDTO> getListWithPagination(int page, int size);
}
