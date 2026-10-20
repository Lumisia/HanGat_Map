-- Only bounded diagnostic codes; no prompt, raw response, message, URL, headers or credentials.
CREATE TABLE course_ai_diagnostics (
    id CHAR(36) NOT NULL PRIMARY KEY,
    trace_id CHAR(36) NOT NULL,
    job_id CHAR(36) DEFAULT NULL,
    occurred_at DATETIME(6) NOT NULL,
    call_kind VARCHAR(20) NOT NULL,
    failure_type VARCHAR(40) NOT NULL,
    validation_code VARCHAR(80) DEFAULT NULL,
    phase VARCHAR(40) NOT NULL,
    http_status INT DEFAULT NULL,
    google_status VARCHAR(60) DEFAULT NULL,
    google_reason VARCHAR(60) DEFAULT NULL,
    network_type VARCHAR(30) DEFAULT NULL,
    model VARCHAR(80) NOT NULL,
    attempts INT NOT NULL,
    elapsed_ms BIGINT NOT NULL,
    outcome VARCHAR(30) NOT NULL
);
CREATE INDEX idx_ai_diagnostic_trace ON course_ai_diagnostics (trace_id);
CREATE INDEX idx_ai_diagnostic_job ON course_ai_diagnostics (job_id);
CREATE INDEX idx_ai_diagnostic_time ON course_ai_diagnostics (occurred_at);
