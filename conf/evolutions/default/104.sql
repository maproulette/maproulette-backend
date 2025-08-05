-- Add mr_tag_metrics column to challenges table
-- !Ups
ALTER TABLE challenges ADD mr_tag_metrics JSONB;

-- !Downs
ALTER TABLE challenges DROP COLUMN mr_tag_metrics; 
