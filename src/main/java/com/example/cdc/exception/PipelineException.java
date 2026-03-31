package com.example.cdc.exception;

/**
 * CDC 管道操作异常
 */
public class PipelineException extends RuntimeException {
    public PipelineException(String message) {
        super(message);
    }

    public PipelineException(String message, Throwable cause) {
        super(message, cause);
    }
}
