package com.example.cdc.service;

import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.util.Base64;

@Service
public class PasswordCryptoService {

    private static final String TRANSPORT_PREFIX = "ENC_RSA:";
    private static final String RSA_TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
    private static final OAEPParameterSpec OAEP_SHA256_PARAMS = new OAEPParameterSpec(
            "SHA-256",
            "MGF1",
            MGF1ParameterSpec.SHA256,
            PSource.PSpecified.DEFAULT
    );

    private final KeyPair keyPair = generateKeyPair();

    public String decodeIfEncrypted(String password) {
        if (password == null || password.isBlank()) {
            return password;
        }
        if (!password.startsWith(TRANSPORT_PREFIX)) {
            return password;
        }

        String encryptedText = password.substring(TRANSPORT_PREFIX.length());
        if (encryptedText.isBlank()) {
            throw new IllegalArgumentException("密码密文为空");
        }

        try {
            Cipher cipher = Cipher.getInstance(RSA_TRANSFORMATION);
            // Align with WebCrypto RSA-OAEP(SHA-256): both main digest and MGF1 digest use SHA-256.
            cipher.init(Cipher.DECRYPT_MODE, privateKey(), OAEP_SHA256_PARAMS);
            byte[] decrypted = cipher.doFinal(Base64.getDecoder().decode(encryptedText));
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalArgumentException("密码解密失败", e);
        }
    }

    public String getTransportPrefix() {
        return TRANSPORT_PREFIX;
    }

    public String getPublicKeyPem() {
        String base64 = Base64.getEncoder().encodeToString(publicKey().getEncoded());
        StringBuilder builder = new StringBuilder();
        builder.append("-----BEGIN PUBLIC KEY-----\n");
        for (int i = 0; i < base64.length(); i += 64) {
            int end = Math.min(i + 64, base64.length());
            builder.append(base64, i, end).append('\n');
        }
        builder.append("-----END PUBLIC KEY-----");
        return builder.toString();
    }

    private PublicKey publicKey() {
        return keyPair.getPublic();
    }

    private PrivateKey privateKey() {
        return keyPair.getPrivate();
    }

    private KeyPair generateKeyPair() {
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(2048);
            return keyPairGenerator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("初始化密码传输密钥失败", e);
        }
    }
}
