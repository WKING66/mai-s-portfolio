CREATE TABLE user_account (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    type TINYINT UNSIGNED NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    status TINYINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_user_account_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE media_asset (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    asset_type TINYINT UNSIGNED NOT NULL,
    storage_key VARCHAR(512) NULL,
    source_type TINYINT UNSIGNED NOT NULL,
    source_host VARCHAR(255) NULL,
    original_filename VARCHAR(255) NULL,
    mime_type VARCHAR(100) NULL,
    byte_size BIGINT UNSIGNED NULL,
    sha256 BINARY(32) NULL,
    width INT UNSIGNED NULL,
    height INT UNSIGNED NULL,
    status TINYINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_media_asset_storage_key (storage_key),
    KEY idx_media_asset_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE site_config (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    display_name VARCHAR(100) NOT NULL,
    headline VARCHAR(200) NOT NULL,
    intro TEXT NOT NULL,
    github_url VARCHAR(2048) NULL,
    email VARCHAR(254) NULL,
    avatar_media_id BIGINT UNSIGNED NULL,
    resume_media_id BIGINT UNSIGNED NULL,
    seo_title VARCHAR(200) NULL,
    seo_description VARCHAR(500) NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_site_config_avatar FOREIGN KEY (avatar_media_id)
        REFERENCES media_asset (id) ON DELETE RESTRICT,
    CONSTRAINT fk_site_config_resume FOREIGN KEY (resume_media_id)
        REFERENCES media_asset (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tag (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    kind TINYINT UNSIGNED NOT NULL,
    name VARCHAR(100) NOT NULL,
    normalized_name VARCHAR(100) NOT NULL,
    slug VARCHAR(120) NOT NULL,
    group_code TINYINT UNSIGNED NULL,
    logo_key VARCHAR(160) NULL,
    is_featured TINYINT UNSIGNED NOT NULL,
    sort_order INT NOT NULL,
    UNIQUE KEY uk_tag_kind_name (kind, normalized_name),
    UNIQUE KEY uk_tag_slug (slug),
    KEY idx_tag_display (kind, group_code, is_featured, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE project (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    slug VARCHAR(160) NULL,
    title VARCHAR(200) NULL,
    summary TEXT NULL,
    contribution TEXT NULL,
    outcome TEXT NULL,
    time_label VARCHAR(100) NULL,
    status TINYINT UNSIGNED NOT NULL,
    is_featured TINYINT UNSIGNED NOT NULL,
    sort_order INT NOT NULL,
    version BIGINT UNSIGNED NOT NULL,
    published_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_project_slug (slug),
    KEY idx_project_featured (status, is_featured, sort_order, id),
    KEY idx_project_published (status, published_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE article (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    slug VARCHAR(160) NULL,
    title VARCHAR(200) NULL,
    summary TEXT NULL,
    body_markdown LONGTEXT NOT NULL,
    source_type TINYINT UNSIGNED NOT NULL,
    status TINYINT UNSIGNED NOT NULL,
    allow_visitor_download TINYINT UNSIGNED NOT NULL,
    reading_time_minutes SMALLINT UNSIGNED NOT NULL,
    version BIGINT UNSIGNED NOT NULL,
    published_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_article_slug (slug),
    KEY idx_article_published (status, published_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE project_link (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT UNSIGNED NOT NULL,
    link_type TINYINT UNSIGNED NOT NULL,
    label VARCHAR(100) NULL,
    url VARCHAR(2048) NOT NULL,
    is_visible TINYINT UNSIGNED NOT NULL,
    sort_order INT NOT NULL,
    KEY idx_project_link_order (project_id, sort_order, id),
    CONSTRAINT fk_project_link_project FOREIGN KEY (project_id)
        REFERENCES project (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE article_import_job (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    article_id BIGINT UNSIGNED NULL,
    source_type TINYINT UNSIGNED NOT NULL,
    original_filename VARCHAR(255) NULL,
    status TINYINT UNSIGNED NOT NULL,
    issues_json JSON NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    KEY idx_article_import_job_article (article_id, created_at),
    CONSTRAINT fk_article_import_job_article FOREIGN KEY (article_id)
        REFERENCES article (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE article_tag (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    article_id BIGINT UNSIGNED NOT NULL,
    tag_id BIGINT UNSIGNED NOT NULL,
    UNIQUE KEY uk_article_tag_pair (article_id, tag_id),
    KEY idx_article_tag_reverse (tag_id, article_id),
    CONSTRAINT fk_article_tag_article FOREIGN KEY (article_id)
        REFERENCES article (id) ON DELETE RESTRICT,
    CONSTRAINT fk_article_tag_tag FOREIGN KEY (tag_id)
        REFERENCES tag (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE project_tag (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT UNSIGNED NOT NULL,
    tag_id BIGINT UNSIGNED NOT NULL,
    UNIQUE KEY uk_project_tag_pair (project_id, tag_id),
    KEY idx_project_tag_reverse (tag_id, project_id),
    CONSTRAINT fk_project_tag_project FOREIGN KEY (project_id)
        REFERENCES project (id) ON DELETE RESTRICT,
    CONSTRAINT fk_project_tag_tag FOREIGN KEY (tag_id)
        REFERENCES tag (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE article_media (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    article_id BIGINT UNSIGNED NOT NULL,
    media_id BIGINT UNSIGNED NOT NULL,
    role TINYINT UNSIGNED NOT NULL,
    alt_text VARCHAR(500) NULL,
    source_reference VARCHAR(2048) NULL,
    sort_order INT NOT NULL,
    UNIQUE KEY uk_article_media_relation (article_id, media_id, role),
    KEY idx_article_media_reverse (media_id, article_id),
    CONSTRAINT fk_article_media_article FOREIGN KEY (article_id)
        REFERENCES article (id) ON DELETE RESTRICT,
    CONSTRAINT fk_article_media_media FOREIGN KEY (media_id)
        REFERENCES media_asset (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE project_media (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT UNSIGNED NOT NULL,
    media_id BIGINT UNSIGNED NOT NULL,
    role TINYINT UNSIGNED NOT NULL,
    alt_text VARCHAR(500) NULL,
    sort_order INT NOT NULL,
    UNIQUE KEY uk_project_media_relation (project_id, media_id, role),
    KEY idx_project_media_reverse (media_id, project_id),
    CONSTRAINT fk_project_media_project FOREIGN KEY (project_id)
        REFERENCES project (id) ON DELETE RESTRICT,
    CONSTRAINT fk_project_media_media FOREIGN KEY (media_id)
        REFERENCES media_asset (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
