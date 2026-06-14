-- V1: Initial schema for PiBase metadata

CREATE TABLE users
(
    id            VARCHAR(21) PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    display_name  VARCHAR(255),
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    max_databases INT          NOT NULL DEFAULT 1,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE databases
(
    id               VARCHAR(21) PRIMARY KEY,
    user_id          VARCHAR(21)  NOT NULL,
    engine           VARCHAR(20)  NOT NULL DEFAULT 'postgresql',
    engine_version   VARCHAR(10)           DEFAULT '15',
    status           VARCHAR(30)  NOT NULL DEFAULT 'PROVISIONING',
    container_id     VARCHAR(64),
    container_name   VARCHAR(100),
    volume_name      VARCHAR(100),
    db_name          VARCHAR(100) NOT NULL,
    db_user          VARCHAR(50)           DEFAULT 'dbuser',
    db_password      VARCHAR(500) NOT NULL,
    host_port        INT,
    pooler_enabled   BOOLEAN               DEFAULT TRUE,
    direct_uri       VARCHAR(500),
    pooled_uri       VARCHAR(500),
    memory_limit_mb  INT                   DEFAULT 128,
    storage_limit_mb INT                   DEFAULT 100,
    max_connections  INT                   DEFAULT 10,
    ttl_hours        INT                   DEFAULT 24,
    expires_at       TIMESTAMP    NOT NULL,
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at       TIMESTAMP,

    CONSTRAINT  fk_databases_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE INDEX idx_databases_user_id  ON databases(user_id);
CREATE INDEX idx_databases_status   ON databases(status);
CREATE INDEX idx_databases_expires_at ON databases(expires_at);