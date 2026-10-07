-- ============================================================================
--  查看 September 项目的 user 表（结构 + 数据）
--  全部是只读语句（SHOW / DESCRIBE / SELECT），不会修改或删除任何数据。
--
--  相关代码出处：
--    src/main/resources/schema.sql                          建表语句
--    src/main/java/com/kirarl/september/mapper/UserMapper.java       实际执行的 SQL
--    src/main/java/com/kirarl/september/config/UserTableMigration.java  启动时补列
--    src/main/resources/application.yml                     库名与连接参数
--
--  ⚠ 表名 user 在 MySQL 里是关键字，且与系统库 mysql.user 同名，
--    所以下面所有表名都写成 `user`（反引号）——这不是可选项，去掉会报错或查错表。
-- ============================================================================


-- ---------------------------------------------------------------------------
-- 【0】先切换到项目使用的库
--     库名 septembernine 来自 application.yml 的 JDBC URL
--     （jdbc:mysql://${DB_HOST:localhost}:${DB_PORT:3306}/septembernine? ...）
-- ---------------------------------------------------------------------------
USE `septembernine`;


-- ---------------------------------------------------------------------------
-- 【1】建表语句原文：最直观，一眼看全所有列、类型、索引、引擎、字符集
-- ---------------------------------------------------------------------------
SHOW CREATE TABLE `user`;


-- ---------------------------------------------------------------------------
-- 【2】列清单（竖排，比 SHOW CREATE TABLE 更容易读列类型）
-- ---------------------------------------------------------------------------
DESCRIBE `user`;
-- 等价写法： SHOW COLUMNS FROM `user`;


-- ---------------------------------------------------------------------------
-- 【3】用 information_schema 精确核对每一列
--     这条路就是 UserTableMigration 启动时用来判断"列是否存在"的同一张视图，
--     所以它能直接告诉你 email / gender / birthday 三列有没有被自动补上。
-- ---------------------------------------------------------------------------
SELECT column_name,
       column_type,
       is_nullable,
       column_default,
       ordinal_position,
       column_comment
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name   = 'user'
ORDER BY ordinal_position;


-- ---------------------------------------------------------------------------
-- 【4】看全部数据 —— 推荐先跑这条：密码只显示前 7 位
--     BCrypt 哈希形如 $2a$10$....，前 7 位足以确认"确实是哈希而非明文"，
--     又不会把完整哈希贴到聊天记录/截图里。
-- ---------------------------------------------------------------------------
SELECT id,
       username,
       email,
       CASE WHEN gender   IS NULL THEN '(保密)' ELSE gender END   AS 性别,
       CASE WHEN birthday IS NULL THEN '(保密)'
            ELSE DATE_FORMAT(birthday, '%Y-%m-%d') END            AS 生日,
       LEFT(password, 7)                                          AS 密码前缀,
       CHAR_LENGTH(password)                                      AS 密码长度
FROM `user`
ORDER BY id;


-- ---------------------------------------------------------------------------
-- 【5】只查某个用户（完全对应 UserMapper.selectByUsername 里那条 SQL）
--     把 'testuser' 换成你想看的用户名。这条会带出完整哈希，注意别外传。
-- ---------------------------------------------------------------------------
SELECT id, username, password, email, gender, birthday
FROM `user`
WHERE username = 'testuser'
LIMIT 1;


-- ---------------------------------------------------------------------------
-- 【6】总览统计：多少条、多少"保密"、有没有越界或异常的脏数据
-- ---------------------------------------------------------------------------
SELECT COUNT(*)                          AS 用户总数,
       SUM(gender   IS NULL)             AS 性别为保密,
       SUM(birthday IS NULL)             AS 生日为保密,
       SUM(gender   IS NOT NULL)         AS 性别已填写,
       SUM(birthday IS NOT NULL)         AS 生日已填写,
       MIN(birthday)                     AS 最早生日,
       MAX(birthday)                     AS 最晚生日,
       SUM(birthday > CURDATE())         AS 未来日期异常条数,
       SUM(password NOT LIKE '$2%')      AS 密码疑似非BCrypt条数
FROM `user`;


-- ---------------------------------------------------------------------------
-- 【7】性别分布（顺便确认中文没有乱码，"武装直升机"应当正常显示）
-- ---------------------------------------------------------------------------
SELECT IFNULL(gender, '(保密)') AS 性别,
       COUNT(*)                 AS 人数
FROM `user`
GROUP BY gender
ORDER BY 人数 DESC;


-- ---------------------------------------------------------------------------
-- 【8】最近注册的用户（id 是 AUTO_INCREMENT，越大越新）
-- ---------------------------------------------------------------------------
SELECT id, username, email, gender, birthday
FROM `user`
ORDER BY id DESC
LIMIT 10;


-- ---------------------------------------------------------------------------
-- 【9】核对后端的范围校验是否真的生效
--     规则见 LoginController：1900-01-01 <= birthday <= 今天
--     正常情况这条应当返回 0 行；有行说明数据被绕过接口改过。
-- ---------------------------------------------------------------------------
SELECT id, username, birthday
FROM `user`
WHERE birthday IS NOT NULL
  AND (birthday < '1900-01-01' OR birthday > CURDATE());


-- ---------------------------------------------------------------------------
-- 【10】表属性：引擎、字符集/排序规则、大致行数
--      中文能正常存的前提是字符集为 utf8mb4；
--      若这里不是 utf8mb4，性别字段可能存成乱码或写入失败。
-- ---------------------------------------------------------------------------
SELECT table_name,
       engine,
       table_collation,
       table_rows,
       create_time,
       update_time
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name   = 'user';


-- ---------------------------------------------------------------------------
-- 【11】索引：确认 username 上有唯一索引
--      注册流程是"先 select 再 insert"，真正的防重名靠的就是这个 UNIQUE 约束。
-- ---------------------------------------------------------------------------
SHOW INDEX FROM `user`;
