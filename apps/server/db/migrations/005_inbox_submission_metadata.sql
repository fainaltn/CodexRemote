ALTER TABLE inbox_items ADD COLUMN submission_id TEXT;
ALTER TABLE inbox_items ADD COLUMN staging_dir TEXT;

UPDATE inbox_items
SET submission_id = id
WHERE submission_id IS NULL;
