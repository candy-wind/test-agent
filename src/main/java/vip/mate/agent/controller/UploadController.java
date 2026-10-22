package vip.mate.agent.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * 图片上传接口
 * 返回 base64 data URL，可直接用于聊天请求的 images 字段
 */
@Slf4j
@RestController
@RequestMapping("/api/upload")
public class UploadController {

    @Value("${upload.dir:./uploads}")
    private String uploadDir;

    /**
     * 上传图片，返回 base64 data URL
     */
    @PostMapping("/image")
    public Map<String, String> uploadImage(@RequestParam("file") MultipartFile file) throws IOException {
        log.info("[upload/image] name={} size={} type={}",
                file.getOriginalFilename(), file.getSize(), file.getContentType());

        if (file.isEmpty()) {
            log.warn("[upload/image] 文件为空");
            throw new IllegalArgumentException("文件不能为空");
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            log.warn("[upload/image] 非图片类型: {}", contentType);
            throw new IllegalArgumentException("仅支持图片文件");
        }

        // 保存到本地
        String suffix = "";
        if (file.getOriginalFilename() != null && file.getOriginalFilename().contains(".")) {
            suffix = file.getOriginalFilename()
                    .substring(file.getOriginalFilename().lastIndexOf("."));
        }
        String filename = UUID.randomUUID() + suffix;
        Path dirPath = Paths.get(uploadDir);
        Files.createDirectories(dirPath);
        Path filePath = dirPath.resolve(filename);
        Files.write(filePath, file.getBytes());
        log.info("[upload/image] 已保存: {}", filePath);

        // 生成 base64 data URL（兼容 DeepSeek/Qwen 视觉模型）
        String base64 = Base64.getEncoder().encodeToString(file.getBytes());
        String dataUrl = "data:" + contentType + ";base64," + base64;

        return Map.of(
                "dataUrl", dataUrl,
                "filename", filename,
                "path", filePath.toString()
        );
    }
}