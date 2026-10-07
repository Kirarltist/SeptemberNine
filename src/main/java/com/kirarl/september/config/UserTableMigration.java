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
