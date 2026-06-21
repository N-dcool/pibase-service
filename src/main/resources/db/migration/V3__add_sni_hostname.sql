-- V3: ADD SNI hostname for HAProxy SNI proxy

ALTER TABLE databases
    ADD COLUMN sni_hostname VARCHAR(20);
ALTER TABLE databases
    ADD COLUMN sni_uri VARCHAR(500);

CREATE UNIQUE INDEX idx_databases_sni_hostname ON databases (sni_hostname);