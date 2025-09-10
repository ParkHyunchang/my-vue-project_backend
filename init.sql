-- 데이터베이스 생성
CREATE DATABASE IF NOT EXISTS vue_personal_project_db;

-- 기존 권한 제거
REVOKE ALL PRIVILEGES ON *.* FROM 'admin'@'%';
DROP USER IF EXISTS 'admin'@'%';
REVOKE ALL PRIVILEGES ON *.* FROM 'hyunchang88'@'%';
DROP USER IF EXISTS 'hyunchang88'@'%';

-- admin 사용자 생성 및 권한 부여
CREATE USER 'admin'@'%' IDENTIFIED BY 'admin';
GRANT ALL PRIVILEGES ON vue_personal_project_db.* TO 'admin'@'%';
FLUSH PRIVILEGES;

-- hyunchang88 사용자 생성 및 권한 부여
CREATE USER 'hyunchang88'@'%' IDENTIFIED BY 'hyunchang88';
GRANT ALL PRIVILEGES ON vue_personal_project_db.* TO 'hyunchang88'@'%';
FLUSH PRIVILEGES;
