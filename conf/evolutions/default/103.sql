-- !Ups
ALTER TABLE challenges ADD COLUMN high_priority_bounds character varying NULL;
ALTER TABLE challenges ADD COLUMN medium_priority_bounds character varying NULL;
ALTER TABLE challenges ADD COLUMN low_priority_bounds character varying NULL;

-- !Downs
ALTER TABLE challenges DROP COLUMN high_priority_bounds;
ALTER TABLE challenges DROP COLUMN medium_priority_bounds;
ALTER TABLE challenges DROP COLUMN low_priority_bounds; 
