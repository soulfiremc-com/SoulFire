CREATE TABLE server_metadata (
  id BIGINT NOT NULL PRIMARY KEY CHECK (id = 1),
  installation_id VARCHAR(36) NOT NULL
);
