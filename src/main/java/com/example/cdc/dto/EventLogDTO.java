package com.example.cdc.dto;

import com.example.cdc.model.EventLog;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 事件日志 DTO
 * 用于前端展示，包含配置名称
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventLogDTO {
    private Long id;
    private Long configId;
    private String configName;  // 配置名称
    private String topic;
    private String tag;
    private String messageKey;
    private EventLog.EventStatus status;
    private Integer retryCount;
    private Integer maxRetry;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime sentAt;
}
