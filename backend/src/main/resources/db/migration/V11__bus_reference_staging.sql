-- 날씨 독립 참고자료. 운영 baseline_serving과 활성 버전을 공유하지 않는다.
CREATE TABLE bus_reference_chunk (
    data_version VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    chunk_id INT NOT NULL,
    payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (data_version, chunk_id)
);
CREATE TABLE bus_reference_row (
    data_version VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    chunk_id INT NOT NULL,
    row_index INT NOT NULL,
    master_version VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    namespace VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    route_id VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    from_stop_id VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    to_stop_id VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    from_order INT NOT NULL,
    to_order INT NOT NULL,
    dow INT NOT NULL,
    hour_slot INT NOT NULL,
    sample_kind VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload JSON NOT NULL,
    PRIMARY KEY (data_version, chunk_id, row_index),
    KEY ix_bus_reference_lookup (data_version, master_version, namespace, route_id, from_stop_id, to_stop_id, from_order, to_order, dow, hour_slot),
    CONSTRAINT fk_bus_reference_chunk FOREIGN KEY (data_version, chunk_id)
        REFERENCES bus_reference_chunk(data_version, chunk_id)
);
