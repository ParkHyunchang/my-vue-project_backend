package com.hyunchang.webapp.config;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 가계부(/expense) 기능 폐기에 따른 expenses 테이블 1회성 DROP 마이그레이션. - ddl-auto=update 는 미사용 테이블을 드롭하지 않으므로
 * 명시적으로 처리한다. - IF EXISTS 를 사용해 두 번째 부팅부터는 no-op. - 충분히 운영 적용된 후 이 클래스는 안전하게 제거 가능. // 추후 삭제 예정
 */
@Component
@Order(0)
public class ExpenseTableDropMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ExpenseTableDropMigration.class);

    @PersistenceContext private EntityManager entityManager;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        try {
            int result =
                    entityManager
                            .createNativeQuery("DROP TABLE IF EXISTS expenses")
                            .executeUpdate();
            log.info("[MIGRATION] DROP TABLE IF EXISTS expenses 실행 완료 (result={})", result);
        } catch (Exception e) {
            log.warn("[MIGRATION] expenses 테이블 드롭 중 오류 (무시 가능): {}", e.getMessage());
        }
    }
}
