-- Additive migration. Existing text results remain readable.
CREATE TABLE IF NOT EXISTS file_analysis_result_file (
  id VARCHAR(36) NOT NULL,
  task_id BIGINT NOT NULL,
  storage_user_id BIGINT NOT NULL,
  file_name VARCHAR(200) NOT NULL,
  mime_type VARCHAR(100) NOT NULL,
  file_size BIGINT NOT NULL,
  published TINYINT NOT NULL DEFAULT 0,
  create_by VARCHAR(64) NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_analysis_result_task (task_id,published),
  CONSTRAINT fk_analysis_result_task FOREIGN KEY (task_id) REFERENCES file_analysis_task(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件分析结果附件，保存记录后发布';
