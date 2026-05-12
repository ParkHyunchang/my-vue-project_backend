-- ============================================================
-- /portfolio 메뉴의 CRUD 권한 보정 스크립트
-- ------------------------------------------------------------
-- 사유: /portfolio 는 본질적으로 "내 잔고" — 사용자별로 자기 데이터를
--       관리해야 하는 메뉴인데, USER/PREMIUM 기본값이 READ-only 로
--       잡혀 있어서 PREMIUM 계정에서 종목 추가/수정/삭제 시 403 이
--       반환됨. 서비스 레이어가 이미 userId 단위로 격리하므로 모든
--       로그인 사용자에게 CRUD 를 허용해도 안전함.
--
-- 실행 방법 (호스트 OS):
--   mysql -u <user> -p vue_personal_project_db < fix_portfolio_permissions.sql
-- 또는 컨테이너 내부에서:
--   mysql -u root -p vue_personal_project_db < /docker-entrypoint-initdb.d/...
-- ============================================================

UPDATE vue_personal_project_db.menu_crud_permissions
SET can_create = TRUE,
    can_update = TRUE,
    can_delete = TRUE,
    updated_at = NOW()
WHERE menu_path = '/portfolio'
  AND role_name IN ('USER', 'PREMIUM');

-- 행이 아예 없는 경우(권한이 한 번도 초기화되지 않은 신규 환경 등)를
-- 대비해 누락 행 보강. 이미 존재하면 영향 없음.
INSERT INTO vue_personal_project_db.menu_crud_permissions
    (role_name, menu_path, can_create, can_read, can_update, can_delete, created_at, updated_at)
SELECT 'USER', '/portfolio', TRUE, TRUE, TRUE, TRUE, NOW(), NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM vue_personal_project_db.menu_crud_permissions
    WHERE role_name = 'USER' AND menu_path = '/portfolio'
);

INSERT INTO vue_personal_project_db.menu_crud_permissions
    (role_name, menu_path, can_create, can_read, can_update, can_delete, created_at, updated_at)
SELECT 'PREMIUM', '/portfolio', TRUE, TRUE, TRUE, TRUE, NOW(), NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM vue_personal_project_db.menu_crud_permissions
    WHERE role_name = 'PREMIUM' AND menu_path = '/portfolio'
);

-- 결과 확인용
SELECT role_name, menu_path, can_create, can_read, can_update, can_delete
FROM vue_personal_project_db.menu_crud_permissions
WHERE menu_path = '/portfolio'
ORDER BY role_name;
