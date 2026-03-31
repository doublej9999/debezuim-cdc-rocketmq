package com.example.cdc.exception;

/**
 * 实体不存在异常
 */
public class EntityNotFoundException extends RuntimeException {
    public EntityNotFoundException(String message) {
        super(message);
    }
}
