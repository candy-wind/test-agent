package vip.mate.agent.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vip.mate.agent.config.ImageUploadGuard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * 图片上传接口：只按文件头魔数识别类型，不信任客户端的 Content-Type 与文件名。
 * 返回 base64 data URL，可直接用于聊天请求的 images 字段；不再回显服务端绝对路径。
 */
@Slf4j
@RestController
@RequestMapping("/api/upload")
public class UploadController {

    @Value("${upload.dir:./uploads}")
    private String uploadDir;

    @PostMapping("/image")
    public Map<String, String> uploadImage(@RequestParam("file") MultipartFile file) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("读取上传文件失败: " + e.getMessage(), e);
        }
        ImageUploadGuard.ImageType type = ImageUploadGuard.requireValid(bytes);

        String filename = UUID.randomUUID().toString().replace("-", "") + type.extension();
        Path dir = Paths.get(uploadDir).toAbsolutePath().normalize();
        Path target = dir.resolve(filename).normalize();
        if (!dir.equals(target.getParent())) {
            throw new IllegalArgumentException("非法的上传路径");
        }
        try {
            Files.createDirectories(dir);
            Files.write(target, bytes);
        } catch (IOException e) {
            throw new IllegalStateException("保存上传文件失败: " + e.getMessage(), e);
        }

        log.info("[upload/image] 已保存: name={} size={} type={}", filename, bytes.length, type.contentType());
        String dataUrl = "data:" + type.contentType() + ";base64,"
                + Base64.getEncoder().encodeToString(bytes);
        return Map.of(
                "dataUrl", dataUrl,
                "filename", filename,
                "contentType", type.contentType(),
                "size", String.valueOf(bytes.length)
        );
    }
}
