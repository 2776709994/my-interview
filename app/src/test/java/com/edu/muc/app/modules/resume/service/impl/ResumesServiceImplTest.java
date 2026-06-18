package com.edu.muc.app.modules.resume.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edu.muc.app.infrastructure.file.FileStorageService;
import com.edu.muc.app.modules.resume.domain.Resumes;
import com.edu.muc.app.modules.resume.dto.ResumeListItemDTO;
import com.edu.muc.app.modules.resume.listener.AnalyzeStreamProducer;
import com.edu.muc.app.modules.resume.mapper.ResumeAnalysesMapper;
import com.edu.muc.app.modules.resume.mapper.ResumesMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResumesServiceImplTest {

    @Mock
    private ResumesMapper resumesMapper;

    @Mock
    private ResumeAnalysesMapper analysesMapper;

    @Mock
    private FileStorageService fileStorageService;

    @Mock
    private ChatClient chatClient;

    @Mock
    private AnalyzeStreamProducer streamProducer;

    @InjectMocks
    private ResumesServiceImpl resumesService;

    private MultipartFile mockFile;
    private Resumes testResume;

    @BeforeEach
    void setUp() {
        // 准备模拟文件（lenient：部分测试用例不会触发全部 stub）
        mockFile = mock(MultipartFile.class);
        lenient().when(mockFile.getOriginalFilename()).thenReturn("test_resume.pdf");
        lenient().when(mockFile.getContentType()).thenReturn("application/pdf");
        lenient().when(mockFile.getSize()).thenReturn(1024L);

        // 准备模拟简历对象
        testResume = new Resumes();
        testResume.setId(1L);
        testResume.setOriginalFilename("test_resume.pdf");
        testResume.setStorageKey("resumes/test_resume.pdf");
    }

    @Test
    void testGetList_Success() {
        // 1. 准备数据
        when(resumesMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(testResume));
        when(analysesMapper.findLatestByResumeIds(any())).thenReturn(Collections.emptyList());

        // 2. 执行测试
        List<ResumeListItemDTO> result = resumesService.getList();

        // 3. 验证结果
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("test_resume.pdf", result.get(0).getFilename());

        // 验证 Mapper 是否被调用
        verify(resumesMapper, times(1)).selectList(any(LambdaQueryWrapper.class));
    }

    @Test
    void testDelete_Success() throws Exception {
        // 1. 准备数据
        when(resumesMapper.selectById(1L)).thenReturn(testResume);
        when(resumesMapper.deleteById(1L)).thenReturn(1);

        // 2. 执行测试
        boolean result = resumesService.delete(1L);

        // 3. 验证结果
        assertTrue(result);

        // 验证是否调用了 MinIO 删除
        verify(fileStorageService, times(1)).delete("resumes/test_resume.pdf");
        verify(resumesMapper, times(1)).deleteById(1L);
    }

    @Test
    void testDelete_FileNotFound() throws Exception {
        // 1. 准备数据：数据库中找不到该简历
        when(resumesMapper.selectById(999L)).thenReturn(null);

        // 2. 执行测试
        boolean result = resumesService.delete(999L);

        // 3. 验证结果
        assertFalse(result);
        verify(fileStorageService, never()).delete(anyString());
    }

    @Test
    void testUpload_FileTooLarge() {
        // 1. 准备数据：模拟一个超大文件
        MultipartFile largeFile = mock(MultipartFile.class);
        when(largeFile.getSize()).thenReturn(11 * 1024 * 1024L); // 11MB

        // 2. 执行测试并捕获异常
        Exception exception = assertThrows(RuntimeException.class, () -> {
            resumesService.upload(largeFile);
        });

        // 3. 验证异常信息
        assertTrue(exception.getMessage().contains("文件大小超过限制"));
    }
}
