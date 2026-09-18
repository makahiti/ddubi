package com.ssafy.ddubi.domain.pipeline.repository;

import com.ssafy.ddubi.domain.pipeline.entity.PipelineConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PipelineConfigRepository extends JpaRepository<PipelineConfig, String> {
}
