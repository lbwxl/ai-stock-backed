-- Additive migration: preserves all existing data, menus and permissions.
CREATE TABLE IF NOT EXISTS file_analysis_task (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  file_id VARCHAR(36) NOT NULL,
  file_name VARCHAR(200) NOT NULL,
  mime_type VARCHAR(100) NOT NULL,
  file_size BIGINT NOT NULL,
  file_url VARCHAR(2048) NOT NULL,
  processing_requirement TEXT NOT NULL,
  analysis_requirement TEXT NOT NULL,
  analysis_text MEDIUMTEXT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'NOT_STARTED',
  processing_mode VARCHAR(16) NOT NULL DEFAULT 'AUTO',
  model_name VARCHAR(100) NULL,
  error_code VARCHAR(100) NULL,
  error_message VARCHAR(1000) NULL,
  request_id VARCHAR(36) NOT NULL,
  version INT NOT NULL DEFAULT 0,
  worker_id VARCHAR(100) NULL,
  started_at DATETIME NULL,
  completed_at DATETIME NULL,
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  create_by VARCHAR(64) NULL,
  update_by VARCHAR(64) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_analysis_task_file (user_id,file_id),
  KEY idx_analysis_task_user (user_id,id),
  KEY idx_analysis_task_status (status,processing_mode,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件分析任务及人工维护结果';

SET @analysis_parent = COALESCE((SELECT menu_id FROM sys_menu WHERE parent_id=0 AND path='webui' LIMIT 1),0);
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '文件分析记录',@analysis_parent,5,'file-analysis','webui/file-analysis/index',1,0,'C','0','0','webui:fileAnalysis:list','documentation','admin',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='webui:fileAnalysis:list');
SET @analysis_menu = (SELECT menu_id FROM sys_menu WHERE perms='webui:fileAnalysis:list' LIMIT 1);
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,create_by,create_time)
SELECT '分析记录查询',@analysis_menu,1,'#',1,0,'F','0','0','webui:fileAnalysis:query','admin',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='webui:fileAnalysis:query');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,create_by,create_time)
SELECT '分析记录维护',@analysis_menu,2,'#',1,0,'F','0','0','webui:fileAnalysis:edit','admin',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='webui:fileAnalysis:edit');
