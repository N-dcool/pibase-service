CREATE TABLE refresh_tokens
(
    id         VARCHAR(21) PRIMARY KEY,
    user_id    VARCHAR(21) NOT NULL,
    token_hash VARCHAR(64) NOT NULL unique,
    expires_at TIMESTAMP   NOT NULL,
    revoked    BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_refresh_tokens_user foreign key (user_id) REFERENCES users (id)
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_tokens_hash ON refresh_tokens (token_hash);