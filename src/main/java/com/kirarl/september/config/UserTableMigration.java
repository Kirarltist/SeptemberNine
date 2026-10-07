package com.kirarl.september.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * schema.sql 使用 CREATE TABLE IF NOT EXISTS，对已经存在的旧表不会补列，
 * 而 MySQL 8 又不支持 ALTER TABLE ... ADD COLUMN IF NOT EXISTS，
 * 因此启动时逐个检查并按需补齐缺失的列，
 * 保证邮箱绑定、性别生日等资料功能在旧库上同样可用。
 */
@Component
public class UserTableMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UserTableMigration.class);

    private final JdbcTemplate jdbcTemplate;

    public UserTableMigration(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        addColumnIfMissing("email", "VARCHAR(255) NULL");
        addColumnIfMissing("gender", "VARCHAR(20) NULL");
        addColumnIfMissing("birthday", "DATE NULL");
        addBirthdayLowerBoundIfMissing();
    }

    /**
     * 把「生日不早于 1900-01-01」这条下界下沉到数据库。
     *
     * <p>schema.sql 用的是 {@code CREATE TABLE IF NOT EXISTS}，对已存在的老表不会补约束，
     * 所以这里按需补上。只补下界：上界「不晚于今天」无法用 CHECK 表达，
     * 因为 MySQL 只允许确定性表达式，CURDATE()/NOW() 被明确禁止。
     *
     * <p>若库里已有越界的历史数据，ALTER 会失败。这种情况下只记警告并跳过，
     * 不让一行脏数据把整个应用卡在启动阶段——请先用检查 SQL 清理后再重启。
     */
    private void addBirthdayLowerBoundIfMissing() {
        String constraintName = "chk_user_birthday_lower";

        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE constraint_schema = DATABASE() AND table_name = 'user' "
                        + "AND constraint_type = 'CHECK' AND constraint_name = ?",
                Integer.class, constraintName);

        if (existing != null && existing > 0) {
            return;
        }

        Integer outOfRange = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM `user` WHERE birthday IS NOT NULL AND birthday < '1900-01-01'",
                Integer.class);

        if (outOfRange != null && outOfRange > 0) {
            log.warn("user 表存在 {} 条早于 1900-01-01 的生日，已跳过 {} 约束；"
                            + "请先清理这些数据再重启：SELECT id, username, birthday FROM `user` "
                            + "WHERE birthday IS NOT NULL AND birthday < '1900-01-01';",
                    outOfRange, constraintName);
            return;
        }

        try {
            jdbcTemplate.execute("ALTER TABLE `user` ADD CONSTRAINT " + constraintName
                    + " CHECK (birthday IS NULL OR birthday >= '1900-01-01')");
            log.info("user 表缺少生日下界约束，已自动补充 {}", constraintName);
        } catch (Exception ex) {
            // 例如 MySQL 低于 8.0.16（CHECK 只解析不生效）或权限不足。
            // 约束没加上不影响功能，后端仍会校验，所以只记警告。
            log.warn("补充 {} 约束失败：{}", constraintName, ex.getMessage());
        }
    }

    /**
     * 列名与类型都是代码里的常量，不存在外部输入拼接。
     */
    private void addColumnIfMissing(String columnName, String definition) {
        Integer columnCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'user' AND column_name = ?",
                Integer.class, columnName);

        if (columnCount != null && columnCount > 0) {
            return;
        }

        jdbcTemplate.execute("ALTER TABLE `user` ADD COLUMN " + columnName + " " + definition);
        log.info("user 表缺少 {} 列，已自动补充", columnName);
    }
}
