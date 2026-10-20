-- UTC. 미사용 최초 관찰 후 최소 24시간 경과해야 삭제 후보가 된다.
CREATE TABLE media_cleanup_candidates (
    storage_key VARCHAR(200) NOT NULL PRIMARY KEY,
    first_seen_at DATETIME(6) NOT NULL,
    object_modified_at DATETIME(6) NOT NULL,
    object_etag VARCHAR(200) NOT NULL
);
