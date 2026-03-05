package com.edu.muc.app.infrastructure.file;

import io.minio.*;
import io.minio.http.Method;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service   // 只保留 @Service，保证容器一定会创建这个 Bean
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
            throw new RuntimeException("MinIO bucket init failed", e);
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
        
        // 先读取完整文件到 byte[]，确保数据完整性
        byte[] fileBytes = file.getBytes();
        String originalMd5 = calculateMD5(fileBytes);
        log.info("文件大小: {} bytes, MD5: {}", fileBytes.length, originalMd5);
        
        // 使用 ByteArrayInputStream 上传
        try (java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(fileBytes)) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .stream(bais, fileBytes.length, -1)
                            .contentType(file.getContentType())
                            .build()
            );
        }
        
        // 下载刚上传的文件，验证 MD5
        byte[] downloadedBytes = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(bucketName)
                        .object(objectName)
                        .build()
        ).readAllBytes();
        
        String uploadedMd5 = calculateMD5(downloadedBytes);
        log.info("✅ 文件上传成功: {}, MinIO 中大小: {} bytes, MD5: {}", objectName, downloadedBytes.length, uploadedMd5);
        
        if (!originalMd5.equals(uploadedMd5)) {
            log.error("❌ MD5 不匹配！原始: {}, MinIO: {}", originalMd5, uploadedMd5);
            throw new RuntimeException("文件上传后内容被篡改");
        }
        
        log.info("✅ MD5 验证通过，文件完整性确认");
        return objectName;
    }
    
    private String calculateMD5(byte[] bytes) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(bytes);
        BigInteger bigInt = new BigInteger(1, digest);
        return String.format("%032x", bigInt);
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