CREATE TABLE IF NOT EXISTS isa (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  market VARCHAR(2) NOT NULL,
  name VARCHAR(255) NOT NULL,
  symbol VARCHAR(255) NOT NULL,
  quantity BIGINT NOT NULL,
  avg_price DOUBLE DEFAULT NULL,
  is_core BIT(1) NOT NULL DEFAULT b'0',
  asset_type VARCHAR(10) NOT NULL DEFAULT 'STOCK',
  created_at DATETIME(6) DEFAULT NULL,
  updated_at DATETIME(6) DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_isa_user_id (user_id),
  CONSTRAINT fk_isa_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS irp (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  market VARCHAR(2) NOT NULL,
  name VARCHAR(255) NOT NULL,
  symbol VARCHAR(255) NOT NULL,
  quantity BIGINT NOT NULL,
  avg_price DOUBLE DEFAULT NULL,
  is_core BIT(1) NOT NULL DEFAULT b'0',
  asset_type VARCHAR(10) NOT NULL DEFAULT 'STOCK',
  created_at DATETIME(6) DEFAULT NULL,
  updated_at DATETIME(6) DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_irp_user_id (user_id),
  CONSTRAINT fk_irp_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
