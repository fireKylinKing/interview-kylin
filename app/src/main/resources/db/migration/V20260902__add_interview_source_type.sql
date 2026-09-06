-- 为既有面试会话表补充会话来源字段。
-- V1 已包含该字段；本迁移用于已基线的旧数据库。
ALTER TABLE interview_sessions
  ADD COLUMN IF NOT EXISTS source_type VARCHAR(32);
