-- Installs pgvector in the app database (POSTGRES_DB), so vector columns and
-- distance operators work without a manual CREATE EXTENSION.
-- The postgres image runs this only when the data volume is empty.
CREATE EXTENSION IF NOT EXISTS vector;
