package com.example.cdc.repository;

import com.example.cdc.model.DataSourceConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;

@Repository
public interface DataSourceConfigRepository extends JpaRepository<DataSourceConfig, Long> {

    List<DataSourceConfig> findByIsActive(Boolean isActive);

    List<DataSourceConfig> findAllByOrderByCreatedAtDesc();

    List<DataSourceConfig> findByIdIn(Set<Long> ids);
}
