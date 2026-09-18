-- 수도권 버스 artifact는 기존 서울·지하철 버전과 독립적으로 보관한다.
-- Spring 관리 적재만 쓰며 수집기/Spark는 이 테이블에 직접 쓰지 않는다.
CREATE TABLE metropolitan_bus_master (
    data_version VARCHAR(50) NOT NULL,
    artifact_sha256 CHAR(64) NOT NULL,
    artifact_gzip LONGBLOB NOT NULL,
    route_count INT NOT NULL,
    stop_count INT NOT NULL,
    loaded_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (data_version),
    CONSTRAINT CK_METRO_ROUTE_COUNT CHECK (route_count > 0),
    CONSTRAINT CK_METRO_STOP_COUNT CHECK (stop_count > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- singleton 행을 FOR UPDATE로 잠가 동시 적재의 활성 전환을 직렬화한다.
CREATE TABLE metropolitan_bus_master_active (
    singleton_id TINYINT NOT NULL,
    data_version VARCHAR(50) NULL,
    activated_at DATETIME(6) NULL,
    PRIMARY KEY (singleton_id),
    CONSTRAINT CK_METRO_SINGLETON CHECK (singleton_id = 1),
    CONSTRAINT FK_METRO_ACTIVE_VERSION FOREIGN KEY (data_version)
        REFERENCES metropolitan_bus_master(data_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
INSERT INTO metropolitan_bus_master_active(singleton_id) VALUES (1);

CREATE TABLE metropolitan_bus_mapping_cache (
    data_version VARCHAR(50) NOT NULL,
    policy_version VARCHAR(50) NOT NULL,
    provider_signature CHAR(64) NOT NULL,
    result_json JSON NOT NULL,
    review_status ENUM('UNREVIEWED','APPROVED','REJECTED') NOT NULL DEFAULT 'UNREVIEWED',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY(data_version,policy_version,provider_signature),
    CONSTRAINT FK_METRO_CACHE_VERSION FOREIGN KEY(data_version)
        REFERENCES metropolitan_bus_master(data_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
