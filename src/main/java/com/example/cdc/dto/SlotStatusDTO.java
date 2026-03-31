package com.example.cdc.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class SlotStatusDTO {
    private List<OrphanResource> orphanSlots;
    private List<OrphanResource> orphanPublications;
    private List<ConfigResourceStatus> configResources;

    @Data
    @Builder
    public static class OrphanResource {
        private String name;
        private String dbUrl;
        private String hostname;
        private Integer port;
        private String dbName;
        private String dbUser;
        private String dbPassword;
    }

    @Data
    @Builder
    public static class ConfigResourceStatus {
        private Long configId;
        private String configName;
        private boolean isActive;
        private String slotName;
        private Long retainedWalBytes;
        private Long daysInactive;
        private String alertLevel; // NORMAL, WARN, ERROR
    }
}
