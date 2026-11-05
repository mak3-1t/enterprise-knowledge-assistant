-- V1 remains unchanged so existing Flyway checksums remain valid.
-- Flyway runs PostgreSQL DDL and the data conversion in one transaction.
ALTER TABLE auth.accounts DROP CONSTRAINT accounts_role_valid;
UPDATE auth.accounts SET role = 'VIEWER' WHERE role = 'MEMBER';
ALTER TABLE auth.accounts ADD CONSTRAINT accounts_role_valid
    CHECK (role IN ('ADMIN', 'EDITOR', 'VIEWER'));
