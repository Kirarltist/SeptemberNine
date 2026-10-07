CREATE TABLE IF NOT EXISTS `user` (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(100) NOT NULL,
    email VARCHAR(255),
    gender VARCHAR(20),
    birthday DATE,
    -- 生日下界可以下沉到数据库，因为它是一个常量。
    -- 上界「不晚于今天」必须留在应用层：MySQL 的 CHECK 只允许确定性表达式，
    -- NOW() / CURDATE() 这类非确定性函数被明确禁止。
    CONSTRAINT chk_user_birthday_lower CHECK (birthday IS NULL OR birthday >= '1900-01-01')
);

-- 日历第三层数据：节假日与调休。
-- 它不可由公历推导（只能跟着国务院办公厅的公告走），因此是唯一需要入库的日历数据。
-- 约定：休息日 day_type = 1，调休上班日 day_type = 2。
-- 没有某天的记录 = 那天既不是节假日也不是调休上班日；
-- 整个年份没有记录 = 该年安排尚未发布（例如公告发布前的次年）。
CREATE TABLE IF NOT EXISTS `holiday` (
    `holiday_date` DATE NOT NULL,
    `name` VARCHAR(50) NOT NULL,
    `day_type` TINYINT NOT NULL,
    PRIMARY KEY (`holiday_date`),
    CONSTRAINT chk_holiday_day_type CHECK (`day_type` IN (1, 2))
);
