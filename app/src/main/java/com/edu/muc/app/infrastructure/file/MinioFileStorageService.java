package com.edu.muc.app.infrastructure.file;

import io.minio.*;
import io.minio.http.Method;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class MinioFileStorageService implements FileStorageService {

    private final MinioClient minioClient;
    private final String bucketName;

    public MinioFileStorageService(@Value("${minio.endpoint}") String endpoint,
                                   @Value("${minio.access-key}") String accessKey,
                                   @Value("${minio.secret-key}") String secretKey,
                                   @Value("${minio.bucket-name}") String bucketName,
                                   @Value("${minio.secure}") boolean secure) {
        this.minioClient = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
        this.bucketName = bucketName;
        ensureBucketExists();
    }

    private void ensureBucketExists() {
        try {
            boolean found = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
            if (!found) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
            }
        } catch (Exception e) {
            log.error("MinIO 存储桶初始化失败: {}", bucketName, e);
            throw new IllegalStateException("MinIO 存储桶初始化失败", e);
        }
    }

    @Override
    public String store(MultipartFile file) throws Exception {
        return store(file, "resumes");
    }
    
    @Override
    public String store(MultipartFile file, String pathPrefix) throws Exception {
        String originalFilename = file.getOriginalFilename();
        log.info("开始上传文件: {}, 路径前缀: {}", originalFilename, pathPrefix);
        
        LocalDate now = LocalDate.now();
        String datePath = String.format("%s/%d/%02d/%02d", pathPrefix, now.getYear(), now.getMonthValue(), now.getDayOfMonth());
        
        // 生成 UUID 前 8 位
        String uuid = UUID.randomUUID().toString().substring(0, 8);
        
        // 提取文件名（不含扩展名）和扩展名
        String nameWithoutExt = "";
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            nameWithoutExt = originalFilename.substring(0, originalFilename.lastIndexOf("."));
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        
        // 格式：UUID前8位_原始文件名.扩展名
        String objectName = datePath + "/" + uuid + "_" + nameWithoutExt + extension;
        
        log.info("MinIO 存储路径: {}", objectName);
        
        // 使用 MultipartFile 的 InputStream 直接上传，避免整文件加载到内存
        try (InputStream inputStream = file.getInputStream()) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .stream(inputStream, file.getSize(), -1)
                            .contentType(file.getContentType())
                            .build()
            );
        }

        log.info("✅ 文件上传成功: {}, 大小: {} bytes", objectName, file.getSize());
        return objectName;
    }

    @Override
    public String getFileUrl(String objectName) throws Exception {
        return minioClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                        .method(Method.GET)
                        .bucket(bucketName)
                        .object(objectName)
                        .expiry(7, TimeUnit.DAYS)
                        .build()
        );
    }


    /**
     * 删除文件
     * @param objectName 文件存储键
     */
    public void delete(String objectName) throws Exception {
        minioClient.removeObject(
                RemoveObjectArgs.builder()
                        .bucket(bucketName)
                        .object(objectName)
                        .build()
        );
    }

    /**
     * 下载文件并转换为字节数组（参考 JavaGuide 实现）
     * @param objectName 文件存储键
     * @return 文件字节数组
     */
    public byte[] downloadAsBytes(String objectName) throws Exception {
        log.info(" 开始下载 MinIO 文件: {}", objectName);
        
        // 获取文件信息
        StatObjectResponse stat = minioClient.statObject(
                StatObjectArgs.builder()
                        .bucket(bucketName)
                        .object(objectName)
                        .build()
        );
        log.info(" 文件大小: {} bytes, Content-Type: {}", stat.size(), stat.contentType());
        
        // 下载为字节数组
        byte[] fileBytes = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(bucketName)
                        .object(objectName)
                        .build()
        ).readAllBytes();
        
        log.info("✅ 文件下载成功，字节数组长度: {} bytes", fileBytes.length);
        return fileBytes;
    }
}