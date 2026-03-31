package com.example.cdc.config;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * JPA 属性转换器 — 对数据库密码字段进行 AES-256-GCM 透明加解密
 *
 * <p>密钥来源：环境变量 {@code CDC_ENCRYPTION_KEY}。
 * 如果未设置，将使用内置默认密钥（仅适用于开发环境）。</p>
 *
 * <p>存储格式：Base64(iv + ciphertext + tag)</p>
 */
@Slf4j
@Converter
public class AesEncryptor implements AttributeConverter<String, String> {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final String ENV_KEY = "CDC_ENCRYPTION_KEY";
    private static final String DEFAULT_KEY = "debezium-cdc-default-dev-key-32!"; // 32 bytes for AES-256

    private final SecretKeySpec secretKey;

    public AesEncryptor() {
        String keyStr = System.getenv(ENV_KEY);
        if (keyStr == null || keyStr.isBlank()) {
            log.warn("环境变量 {} 未设置，使用默认开发密钥（请勿在生产环境使用）", ENV_KEY);
            keyStr = DEFAULT_KEY;
        }
        try {
            byte[] keyBytes = MessageDigest.getInstance("SHA-256")
                    .digest(keyStr.getBytes(StandardCharsets.UTF_8));
            this.secretKey = new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("初始化加密密钥失败", e);
        }
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return attribute;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] encrypted = cipher.doFinal(attribute.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            log.error("加密失败: {}", e.getMessage());
            throw new IllegalStateException("密码加密失败", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isEmpty()) {
            return dbData;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(dbData);
            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] encrypted = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            System.arraycopy(combined, GCM_IV_LENGTH, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] decrypted = cipher.doFinal(encrypted);

            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // 向后兼容：如果不是有效的 Base64（即明文密码），直接返回原值
            log.debug("数据不是加密格式，作为明文返回（可能是旧数据）");
            return dbData;
        } catch (Exception e) {
            // 向后兼容：解密异常也可能是旧的明文数据
            log.warn("解密失败，作为明文返回（可能是旧数据）: {}", e.getMessage());
            return dbData;
        }
    }
}
