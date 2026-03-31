package com.example.cdc.controller;

import com.example.cdc.dto.SlotStatusDTO;
import com.example.cdc.service.SlotLifecycleManager;
import com.example.cdc.service.PgReplicationService;
import com.example.cdc.model.DataSourceConfig;
import com.example.cdc.service.DataSourceConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/lifecycle")
@RequiredArgsConstructor
public class SlotLifecycleController {

    private final SlotLifecycleManager slotLifecycleManager;
    private final PgReplicationService pgReplicationService;
    private final DataSourceConfigService configService;

    @GetMapping("/status")
    public SlotStatusDTO getStatus() {
        return slotLifecycleManager.getGlobalStatus();
    }

    @PostMapping("/cleanup/orphan-slot")
    public void cleanupOrphanSlot(@RequestBody SlotStatusDTO.OrphanResource resource) {
        DataSourceConfig config = new DataSourceConfig();
        config.setDbHostname(resource.getHostname());
        config.setDbPort(resource.getPort());
        config.setDbName(resource.getDbName());
        config.setDbUser(resource.getDbUser());
        config.setDbPassword(resource.getDbPassword());
        
        pgReplicationService.dropReplicationSlot(config, resource.getName());
    }

    @PostMapping("/cleanup/orphan-publication")
    public void cleanupOrphanPublication(@RequestBody SlotStatusDTO.OrphanResource resource) {
        DataSourceConfig config = new DataSourceConfig();
        config.setDbHostname(resource.getHostname());
        config.setDbPort(resource.getPort());
        config.setDbName(resource.getDbName());
        config.setDbUser(resource.getDbUser());
        config.setDbPassword(resource.getDbPassword());
        
        pgReplicationService.dropPublication(config, resource.getName());
    }
}
