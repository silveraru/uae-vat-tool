-- Opening a box on a large period (NFR-07). A page of lines is read from this index in tax point order
-- without sorting the box, and a count never touches the table once autovacuum has been through.
CREATE INDEX tax_line_box_page_idx ON tax_line (tenant_id, period_id, direction, treatment, tax_point, id)
  INCLUDE (emirate, blocked_category)
  WHERE superseded_at IS NULL AND memo = false AND line_kind = 'NORMAL';

CREATE INDEX tax_line_adjustment_box_idx ON tax_line (tenant_id, period_id, adj_box, tax_point, id)
  WHERE superseded_at IS NULL AND memo = false AND line_kind = 'ADJUSTMENT';
