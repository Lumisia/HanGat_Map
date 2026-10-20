-- token은 접근 증표다. 대소문자를 구분하고 코스당 활성 링크는 최대 하나다.
CREATE TABLE course_shares (
    course_id BIGINT NOT NULL PRIMARY KEY,
    token VARCHAR(43) CHARACTER SET ascii COLLATE ascii_bin NULL,
    active BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_course_share_token UNIQUE (token),
    CONSTRAINT fk_course_share_course FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE CASCADE
);
