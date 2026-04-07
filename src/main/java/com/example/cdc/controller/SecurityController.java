package com.example.cdc.controller;

import com.example.cdc.service.PasswordCryptoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/security")
@RequiredArgsConstructor
public class SecurityController {

    private final PasswordCryptoService passwordCryptoService;

    @GetMapping("/password-public-key")
    public ResponseEntity<Map<String, String>> getPasswordPublicKey() {
        return ResponseEntity.ok(Map.of(
                "algorithm", "RSA-OAEP-256",
                "prefix", passwordCryptoService.getTransportPrefix(),
                "publicKeyPem", passwordCryptoService.getPublicKeyPem()
        ));
    }
}

