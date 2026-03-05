package com.edu.muc.app.infrastructure.file;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

public interface FileStorageService {
    /**
     * 存储文件（默认使用 resumes 目录）
     * @param file 上传的文件
     * @return 文件的存储标识 (MinIO 为对象名, 本地为文件路径)
     */
    String store(MultipartFile file) throws Exception;
    
    /**
     * 存储文件到指定目录
     * @param file 上传的文件
     * @param pathPrefix 路径前缀（如 "knowledge-base"、"resumes"）
     * @return 文件的存储标识
     */
    String store(MultipartFile file, String pathPrefix) throws Exception;

    /**
     * 获取文件的访问URL
     * @param objectName 文件的存储标识
     * @return 文件的可访问URL
     */
    String getFileUrl(String objectName) throws Exception;
    
    /**
     * 下载文件为字节数组（参考 JavaGuide 实现）
     * @param objectName 文件的存储标识
     * @return 文件的字节数组
     */
    byte[] downloadAsBytes(String objectName) throws Exception;
    
    /**
     * 删除文件
     * @param objectName 文件的存储标识
     */
    void delete(String objectName) throws Exception;
}