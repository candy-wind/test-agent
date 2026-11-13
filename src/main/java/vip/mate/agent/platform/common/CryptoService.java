package vip.mate.agent.platform.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 模型 API Key 等敏感字段的对称加密（AES-256-GCM）。
 * 密文格式：base64(iv) + ":" + base64(cipherText)
 */
@Slf4j
@Service
public class CryptoService {

    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public CryptoService(@Value("${platform.secret-key:}") String secret,
                         @Value("${platform.allow-dev-secret:false}") boolean allowDevSecret) {
        String effective = secret == null ? "" : secret.trim();
        if (effective.isEmpty() || "dev-only-change-me".equals(effective)) {
            if (!allowDevSecret) {
                throw new IllegalStateException(
                        "未配置 APP_SECRET_KEY：请设置环境变量 APP_SECRET_KEY（生成命令：openssl rand -base64 32）后重启。"
                                + "已有数据务必沿用原密钥，否则库中已加密的 API Key 将无法解密。"
                                + "仅本地开发可临时设置 PLATFORM_ALLOW_DEV_SECRET=true 降级。");
            }
            effective = "dev-only-change-me";
            log.warn("[crypto] 正在使用开发默认密钥，严禁用于生产环境");
        }
        if (effective.length() < 16) {
            throw new IllegalStateException("APP_SECRET_KEY 长度不足 16 位，请使用至少 32 位随机串");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(effective.getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(digest, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("初始化加密密钥失败", e);
        }
    }

    public String encrypt(String plainText) {
        if (plainText == null || plainText.isBlank()) {
            return null;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(iv) + ":"
                    + Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            throw new IllegalStateException("加密失败", e);
        }
    }

    public String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isBlank()) {
            return null;
        }
        try {
            String[] parts = cipherText.split(":", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("密文格式不正确");
            }
            byte[] iv = Base64.getDecoder().decode(parts[0]);
            byte[] encrypted = Base64.getDecoder().decode(parts[1]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("解密失败，请确认 APP_SECRET_KEY 未变更", e);
        }
    }

    /**
     * 脱敏展示，例如 sk-1234567890abcd -> sk-****abcd
     */
    public String mask(String plainText) {
        if (plainText == null || plainText.isBlank()) {
            return "";
        }
        if (plainText.length() <= 8) {
            return "****";
        }
        return plainText.substring(0, 3) + "****" + plainText.substring(plainText.length() - 4);
    }
}
