CREATE TABLE IF NOT EXISTS venues (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(100) NOT NULL, type VARCHAR(20) NOT NULL,
  capacity INT NOT NULL, expiry_minutes INT NOT NULL
);
CREATE TABLE IF NOT EXISTS check_ins (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, venue_id BIGINT NOT NULL, device_hash VARCHAR(100) NOT NULL,
  checked_in_at BIGINT NOT NULL, checked_out_at BIGINT, close_reason VARCHAR(20), duration_sec BIGINT,
  name VARCHAR(40), planned_minutes INT,
  FOREIGN KEY (venue_id) REFERENCES venues(id)
);
CREATE INDEX IF NOT EXISTS idx_open ON check_ins(venue_id, checked_out_at);
CREATE INDEX IF NOT EXISTS idx_time ON check_ins(venue_id, checked_in_at);
CREATE INDEX IF NOT EXISTS idx_dev ON check_ins(device_hash, checked_out_at);
