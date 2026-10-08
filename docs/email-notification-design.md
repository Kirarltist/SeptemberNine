# SeptemberNine 邮件通知架构设计（v0.1 讨论稿）

> 状态：设计稿，未产生任何代码改动。
> 范围：向用户邮箱发送通知（生日/节日提前提醒、每日/每周摘要）。
> 读者：项目作者本人、后续接手的人。

---

## 1. 结论速览

1. **不存在「接入 QQ 邮箱 API」这件事。** QQ 邮箱不提供面向第三方系统的发信 REST API。实际做法是让后端充当 **SMTP 客户端**，把 QQ 邮箱当作发信中继服务器。协议是通用 SMTP，Java 侧只需 `spring-boot-starter-mail`（Jakarta Mail）+ 几行配置。**唯一麻烦的是 QQ 要求用「授权码」而非登录密码**。
2. **发信必须与请求链路解耦。** SMTP 单次往返是数百毫秒到数秒级，且会失败、会重试。因此设计核心是「**事务性发件箱**（outbox）」而不是在业务代码里直接 `mailSender.send()`。
3. **本期场景有一个硬依赖缺口**（见 §2.3）：**项目里没有任何用户事件/日程表**，「每日/每周日程摘要」暂时无内容可摘要。同时**没有联系人/好友模块**，「生日提醒」目前只能提醒用户自己的生日，产品价值有限。文档给出的对策是：先用现成的日历数据做出可交付的价值，把摘要层设计成可插拔的 section，等事件模块落地后零改动接入。
4. **必须先补的安全债**：`user.email` 从未被验证过，且没有唯一约束，`/api/auth/bind-email` 没有任何频率限制。一旦具备发信能力，这三件事组合起来就是一个可被利用的**发信跳板**。详见 §2.4 与 §9。

---

## 2. 现状盘点（基于当前代码，非假设）

### 2.1 已有的可复用资产

| 资产 | 位置 | 对邮件功能的意义 |
|---|---|---|
| `user.email` 字段 | [schema.sql](../src/main/resources/schema.sql) | 收件地址来源（但未验证，见 §2.4） |
| `user.birthday` 字段 | 同上 | 生日提醒的触发源，**唯一现成的个人化日期数据** |
| `holiday` 表 + 灌数器 | [HolidayDataInitializer.java](../src/main/java/com/kirarl/september/config/HolidayDataInitializer.java) | 法定节假日/调休，可直接驱动节日提醒 |
| `cn.6tail:lunar` 农历库 | [pom.xml](../pom.xml) | 农历节日、二十四节气，**邮件文案的差异化素材** |
| `businessClock`（东八区） | [AppTimeConfig.java](../src/main/java/com/kirarl/september/config/AppTimeConfig.java) | 所有「什么时候发」必须走它 |
| 统一响应 `Result<T>` | [Result.java](../src/main/java/com/kirarl/september/util/Result.java) | 新接口保持一致 |
| 启动期迁移约定 | [UserTableMigration.java](../src/main/java/com/kirarl/september/config/UserTableMigration.java) | 加列的迁移方式照抄它，不要引入 Flyway/Liquibase |

### 2.2 缺失的基础设施（全部需要从零加）

| 能力 | 现状 | 证据 |
|---|---|---|
| 邮件发送 | ❌ 无依赖、无配置 | `pom.xml` 中没有 mail 相关依赖 |
| 定时调度 | ❌ | `MainApplication` 无 `@EnableScheduling`，全仓无 `@Scheduled`/`TaskScheduler` |
| 异步执行 | ❌ | 全仓无 `@Async`/`@EnableAsync` |
| 会话/令牌 | ❌ | 见下 |
| 用户事件/日程 | ❌ | 无 `event` 表，`CalendarService` 只装配公历+农历+节假日 |

**没有会话或令牌机制**这一点比看起来更重要：现有所有接口（登录、绑定邮箱、改资料）都靠「重新提交用户名+密码」确认身份（见 [LoginController.java](../src/main/java/com/kirarl/september/controller/LoginController.java)）。

它对邮件功能有两个直接后果：

- 邮件里的**退订链接不能依赖登录态**，必须用自带签名的令牌（§9.4）。
- 用户每改一次「提醒开关」都要重输密码，体验很差。这是将来引入令牌机制的动机之一，但**不在本次范围**。

### 2.3 本期两个场景的可行度评估

| 场景 | 数据源是否具备 | 判断 |
|---|---|---|
| **节日/节气/调休提醒** | ✅ `holiday` 表 + 农历库 | **立刻可做，且是本项目最有价值的邮件场景**。特别是「明天是调休上班日」这类提醒，实用性和记忆点都强 |
| **每日/每周摘要** | ⚠️ 半具备 | 日程部分**无数据源**。可先做「日历摘要」（今天农历几号、什么节气、距下个节日几天、本周是否调休），日程 section 留空等事件模块 |
| **生日提醒** | ✅ 字段已有 | ⚠️ **产品价值存疑**：只能提醒用户自己的生日。真正有价值的是「提醒朋友的生日」，而项目没有联系人/好友模块，也没有对方同意收集生日的合规基础 |

**建议的取舍**：第一期以「**节日/节气/调休提前提醒** + **每日日历摘要**」为主线交付，生日提醒作为摘要里的一个 section（"距离你的生日还有 N 天"），而不是独立功能。等事件模块 + 联系人模块落地后，再补齐日程摘要和他人生日提醒。

### 2.4 必须先处理的安全债

| 问题 | 位置 | 风险 |
|---|---|---|
| 邮箱从未验证 | [LoginController#bindEmail](../src/main/java/com/kirarl/september/controller/LoginController.java#L101-L119) 只做正则格式校验 | 用户可把自己账号的收件地址填成任意他人邮箱，之后**系统会持续用你的发信身份向该地址发信**。这在收信方看来是你（或你的域名）在发垃圾邮件 |
| `email` 无唯一约束 | [schema.sql](../src/main/resources/schema.sql#L1-L12)，`username` 有 UNIQUE，`email` 没有 | 多个账号绑同一邮箱 → 退订、找回密码、去重逻辑全部歧义 |
| `bind-email` 无频率限制 | 同 Controller，无任何限流 | 加上发信能力后，该接口每被调用一次就可能触发一封邮件，成为**发信跳板** |
| 无退订机制 | — | 任何批量邮件缺少退订入口，既伤送达率也违反主流邮箱服务商政策 |

> **结论**：邮件功能的第一期不应只做「发送」，必须同时包含「邮箱验证 + 唯一约束 + 限流 + 退订」这四件配套。否则做得越快，被滥用的时间越早。

---

## 3. 发信通道选型

### 3.1 三条路线

| 维度 | A. 个人邮箱 + SMTP | B. 自有域名 + 云邮件推送 | C. 自建 MTA |
|---|---|---|---|
| 典型服务 | QQ 邮箱、163 邮箱 | 阿里云 DirectMail、腾讯云 SES、SendGrid、Resend | Postfix/Exim 自建 |
| 发信身份 | 只能是 `xxx@qq.com` | `no-reply@你的域名` | 你的域名 |
| 日发送量 | 个人免费邮箱有硬性日限额与频率限制，量级为**数十封/天**（腾讯不公开承诺具体数字，以邮箱设置页与实际测试为准），超限会**临时封禁 SMTP**，当天后续全部失败 | 阿里云 DirectMail：主账号共 2000 封免费、每天最多 200 封（见[官方文档](https://www.alibabacloud.com/help/zh/direct-mail/level)），超出按量付费 | 取决于带宽与信誉 |
| 送达率 | 一般。个人邮箱发信容易被大厂判为垃圾邮件，且无法配置 SPF/DKIM | 好，前提是配好 SPF/DKIM/DMARC | **差**。无信誉 IP 通常被直接拒收 |
| 前置条件 | 在邮箱设置里开启 SMTP 服务并生成**授权码** | 需域名 + 域名解析配置 + 控制台申请发信地址 | 独立 IP、反向解析、长期养信誉 |
| 适用 | 开发、内测、Demo、极小规模 | 生产 | 不推荐 |

### 3.2 决策

**开发与内测用 A，架构上按 B 设计，切换成本控制在「只改配置」。**

达成这一点的关键是：业务代码只依赖自建的 `MailSender` 接口，**只有 `SmtpMailSender` 这一个类知道 SMTP 的存在**（见附录 B）。届时若从 A 换成 B，改动仅限 `application-local.yml`（或环境变量）里的 host/port/凭据，以及 `mail.from` 的值。若将来改用提供商的原生 HTTP API（部分服务商的 API 比 SMTP 更好排障），只需新增一个 `MailSender` 实现类。

### 3.3 方案 A 的两个必知坑

1. **密码栏填「授权码」**。QQ 邮箱：设置 → 账户 → 开启 POP3/SMTP 服务 → 生成 16 位授权码。填登录密码会直接认证失败。授权码等同于密码，**泄露即可被用来以你的名义发信**，必须按密钥对待（§9.1）。
2. **Jakarta Mail 的默认超时是「无限」**。SMTP 服务器卡住时，发送线程会永久挂起。必须显式设置 `connectiontimeout`/`timeout`/`writetimeout`（见附录 A），否则一个网络抖动就能耗尽线程池。

---

## 4. 总体架构

```
                    ┌─────────────────────────────────────────┐
   触发器层          │  ReminderScheduler  (@Scheduled)        │
   Trigger          │  · 每 5 分钟：扫描到期提醒              │
                    │  · 每日 07:00：生成当日摘要             │
                    │  所有「今天/现在」取自 businessClock    │
                    └───────────────────┬─────────────────────┘
                                        │ 决定「谁、什么内容、什么时候」
                    ┌───────────────────▼─────────────────────┐
   生成层            │  NotificationService                    │
   Compose          │  · 查偏好 → 过滤收件人                  │
                    │  · 组装 Section（节日/生日/日程…）      │
                    │  · 计算 dedupe_key（幂等）              │
                    └───────────────────┬─────────────────────┘
                                        │ 渲染 + 写入（同一事务）
                    ┌───────────────────▼─────────────────────┐
   渲染层            │  EmailRenderer (Thymeleaf)              │
   Render           │  · HTML + 纯文本双版本                  │
                    │  · 模板内所有变量自动转义               │
                    └───────────────────┬─────────────────────┘
                                        │
                    ┌───────────────────▼─────────────────────┐
   出箱层            │  email_outbox 表                        │
   Outbox           │  · 业务事务只 INSERT，绝不调用 SMTP     │
                    │  · dedupe_key 唯一索引保证不重复发      │
                    └───────────────────┬─────────────────────┘
                                        │ 轮询 status=待发 且 到期
                    ┌───────────────────▼─────────────────────┐
   投递层            │  OutboxDispatcher                       │
   Deliver          │  · 全局/单用户限速                      │
                    │  · 失败分类：永久失败 vs 可重试         │
                    │  · 指数退避，超限标记放弃并告警         │
                    └───────────────────┬─────────────────────┘
                                        │
                    ┌───────────────────▼─────────────────────┐
                    │  MailSender（接口）                     │
                    │    └── SmtpMailSender（唯一碰 SMTP 的类）│
                    └─────────────────────────────────────────┘
```

**分层职责要点**

- **触发层只管「什么时候该算一次」**，不含任何业务判断。
- **生成层不含 SMTP 概念**，它只负责「产生一封待发的信」，产出物是数据库行。
- **出箱层是事务边界**：业务事务提交成功 ⇔ 邮件保证会被尝试发送。彻底消除「事务回滚了但邮件发出去」和「事务提交了但邮件丢了」两类问题。
- **投递层是唯一会失败的地方**，因此重试、限速、告警全部集中在此。

---

## 5. 数据模型

### 5.1 新增表 → 写入 `schema.sql`

沿用现有风格（`CREATE TABLE IF NOT EXISTS` + 中文注释 + CHECK 约束）。因为 [application.yml](../src/main/resources/application.yml) 里 `spring.sql.init.mode: always`，**这些语句每次启动都会执行，必须保持幂等**。

```sql
-- 邮件发件箱。业务事务只向这里 INSERT，由 OutboxDispatcher 异步投递。
CREATE TABLE IF NOT EXISTS `email_outbox` (
    `id`              BIGINT PRIMARY KEY AUTO_INCREMENT,
    `dedupe_key`      VARCHAR(128) NOT NULL,
    `recipient`       VARCHAR(255) NOT NULL,
    `subject`         VARCHAR(255) NOT NULL,
    `body_html`       MEDIUMTEXT   NOT NULL,
    `body_text`       MEDIUMTEXT   NOT NULL,
    -- 0=待发 1=发送中 2=已发送 3=永久失败（已放弃）
    `status`          TINYINT      NOT NULL DEFAULT 0,
    `retry_count`     INT          NOT NULL DEFAULT 0,
    `next_attempt_at` DATETIME     NOT NULL,
    `last_error`      VARCHAR(500),
    `created_at`      DATETIME     NOT NULL,
    `sent_at`         DATETIME,
    UNIQUE KEY `uk_outbox_dedupe` (`dedupe_key`),
    -- 投递轮询的驱动索引：只查待发且到期的行
    KEY `idx_outbox_due` (`status`, `next_attempt_at`),
    CONSTRAINT `chk_outbox_status` CHECK (`status` IN (0, 1, 2, 3))
);

-- 用户的通知偏好。一行 = 一个用户。
CREATE TABLE IF NOT EXISTS `notification_preference` (
    `user_id`             BIGINT PRIMARY KEY,
    `email_enabled`       TINYINT NOT NULL DEFAULT 1,  -- 总开关
    `birthday_remind`     TINYINT NOT NULL DEFAULT 1,  -- 生日是否出现在摘要里
    `festival_remind`     TINYINT NOT NULL DEFAULT 1,  -- 节日/调休提醒
    `digest_frequency`    VARCHAR(10) NOT NULL DEFAULT 'DAILY', -- OFF/DAILY/WEEKLY
    `birthday_advance_days` TINYINT NOT NULL DEFAULT 0, -- 生日提前几天提醒
    `updated_at`          DATETIME NOT NULL,
    CONSTRAINT `chk_pref_digest` CHECK (`digest_frequency` IN ('OFF', 'DAILY', 'WEEKLY'))
);

-- 邮箱验证码。为将来做「绑定邮箱验证」预留；本期场景不直接使用，
-- 但 §2.4 已说明它是发信能力上线的前置条件之一。
CREATE TABLE IF NOT EXISTS `email_verification` (
    `id`         BIGINT PRIMARY KEY AUTO_INCREMENT,
    `user_id`    BIGINT       NOT NULL,
    `email`      VARCHAR(255) NOT NULL,
    -- 只存哈希，不存明文验证码：库被读走也无法直接使用
    `code_hash`  VARCHAR(100) NOT NULL,
    `purpose`    VARCHAR(20)  NOT NULL,  -- BIND_EMAIL / RESET_PASSWORD
    `expires_at` DATETIME     NOT NULL,
    `attempts`   INT          NOT NULL DEFAULT 0,
    `consumed_at` DATETIME,
    `created_at` DATETIME     NOT NULL,
    KEY `idx_verification_lookup` (`user_id`, `purpose`, `created_at`)
);
```

### 5.2 对 `user` 表的增列 → 新建迁移组件，照抄既有约定

**不要**直接把 `ALTER TABLE` 写进 `schema.sql`：`CREATE TABLE IF NOT EXISTS` 对已存在的表不补列，而 MySQL 8 不支持 `ADD COLUMN IF NOT EXISTS`——这正是 [UserTableMigration.java](../src/main/java/com/kirarl/september/config/UserTableMigration.java#L10-L15) 存在的理由。按同样模式新增 `EmailNotificationMigration`：

| 新增列 | 类型 | 用途 |
|---|---|---|
| `email_verified` | `TINYINT NOT NULL DEFAULT 0` | 邮箱是否已验证。**只有 =1 才允许发信** |
| `birthday_calendar_type` | `VARCHAR(10) NOT NULL DEFAULT 'SOLAR'` | `SOLAR`/`LUNAR`，见 §6.1 关于农历生日的讨论 |
| `email_bounced_at` | `DATETIME NULL` | 收到硬退信后打标，停止继续发往该地址 |
| `unsubscribe_token` | `VARCHAR(64) NOT NULL` | 退订链接的签名基值，见 §9.4 |

同时补 `email` 的唯一索引。注意：**若库中已存在重复邮箱，加索引会失败**——迁移组件必须像 `addBirthdayLowerBoundIfMissing` 那样先检查重复、有则记警告并跳过，不能让启动被脏数据卡死。

### 5.3 未来 `event` 表的最小契约（本期不建，仅约定）

为了让摘要层将来能零改动接入，现在只需约定一个查询接口的形状：

```java
public interface DigestSectionProvider {
    /** 该 section 是否对该用户有内容可展示 */
    boolean supports(Long userId, LocalDate date);
    /** 展示顺序，数字小的在前 */
    int order();
    /** 渲染所需的数据，禁止返回已拼好的 HTML */
    DigestSection render(Long userId, LocalDate date);
}
```

节日/生日/日程分别是三个实现。事件模块落地后新增 `EventDigestSectionProvider` 即可，**摘要的组装、渲染、投递、重试、退订全部无需改动**。这是本设计里最重要的一处可扩展点。

---

## 6. 核心流程

### 6.1 生日提醒

**触发**：每日 07:00（业务时区）扫描。

```
对每个 满足以下全部条件的用户：
  1. preference.email_enabled = 1
  2. preference.birthday_remind = 1
  3. user.email 非空 且 email_verified = 1 且 email_bounced_at IS NULL
  4. 今天 + birthday_advance_days 恰好命中其生日
→ 生成摘要中的一个 section（不是独立邮件）
```

**四个必须明确的边界**：

1. **2 月 29 日出生的人**：非闰年不存在 2/29。必须显式决定策略（建议：非闰年落在 2 月 28 日提醒），并在代码里写注释说明这是产品决策而非疏漏。
2. **农历生日**：`user.birthday` 存的是 `LocalDate`（公历），**无法表达「农历五月初五」**。而农历生日在国内是刚需。这正是 §5.2 预留 `birthday_calendar_type` 的原因——将来若支持，需要一个 `(农历月, 农历日, 是否闰月)` 的三元组，且必须处理**闰月出生的人在无闰月年份怎么办**。这是一个独立的设计课题，本期不做，但字段先留好，避免日后改表。
3. **生日字段可为空**：`birthday IS NULL` 表示「保密」（见 [User.java](../src/main/java/com/kirarl/september/entity/User.java)），这类用户直接跳过。
4. **不要发「祝你生日快乐」给本人**。自祝生日的邮件价值接近零。建议的文案是**前瞻性信息**：「距离你的生日还有 7 天」并提供设置入口；真正的「祝某某生日快乐」应等联系人模块。

**幂等键**：`birthday-advance:{userId}:{targetBirthdayDate}`

### 6.2 节日 / 节气 / 调休提醒

这是本期价值最高的场景，且数据完全现成。

**触发**：每日 07:00 扫描，产出次日（或未来 N 天）的日历事件：

| 提醒类型 | 数据来源 | 示例文案 |
|---|---|---|
| 法定节假日开始 | `holiday` 表 `day_type=1` | 「明天起放假 3 天：国庆节」 |
| **调休上班日** | `holiday` 表 `day_type=2` | 「**明天是调休上班日，记得定闹钟**」 |
| 节气 | 农历库 `lunar.getJieQi()` | 「明天立秋」 |
| 农历节日 | 农历库 / `holiday` 表 | 「明天是除夕」 |

**实现要点**：

- 数据源优先级与 [CalendarService#resolveLabel](../src/main/java/com/kirarl/september/service/CalendarService.java#L126-L144) 保持一致——**这条规则已经在后端实现过一次了，复用它而不是重写**，否则同一天在日历页显示「立秋」而邮件说「七夕」，用户会立刻失去信任。建议把 `resolveLabel` 的逻辑提取为可复用的组件。
- `holiday` 表的「该年无记录 = 安排尚未发布」语义（见 [schema.sql](../src/main/resources/schema.sql#L17-L18)）意味着**跨年时提醒会静默失效**。这不是 bug，而是数据问题。建议在检测到「次年为整年空」时给管理员记一条日志/告警，而不是让用户悄悄收不到提醒。
- **调休提醒是一年只有几次的高价值通知**，也是这个日历项目相对手机自带日历的差异化点，值得单独做开关。

**幂等键**：`festival-advance:{holidayDate}:{userId}`

### 6.3 摘要生成

```
DigestComposer.compose(userId, date, frequency):
    sections = []
    for provider in DigestSectionProviders:      # 按 order() 排序
        if provider.supports(userId, date):
            sections.add(provider.render(userId, date))
    if sections.isEmpty():
        return null                              # 无内容则不发，避免「空摘要」骚扰
    return render(template = DIGEST, {sections, date, unsubscribeUrl})
```

**「无内容则不发」是一条重要产品原则。** 每天一封「今天没有安排」的邮件，用户三天内就会退订。

**幂等键**：`digest:daily:{userId}:{date}` / `digest:weekly:{userId}:{isoYear}-W{isoWeek}`

每周摘要的「周」必须用 **ISO-8601 周定义**（周一起始，跨年周编号可能属于上一年）。自己算极易出错，用 `java.time` 的 `WeekFields.ISO`。

### 6.4 出箱投递状态机

```
                    ┌──────────┐
      INSERT ──────▶│ 0 待发   │
                    └────┬─────┘
                         │ Dispatcher 抢占（条件更新为 1，避免多实例重复取）
                    ┌────▼─────┐
                    │ 1 发送中 │
                    └────┬─────┘
              ┌──────────┴───────────┐
        成功  │                      │ 失败
       ┌──────▼──────┐        ┌──────▼───────────────────┐
       │ 2 已发送    │        │ 可重试？                  │
       └─────────────┘        │  · 5xx / 550 无效地址 → 否 │
                              │  · 4xx / 超时 / 连接失败 → 是│
                              └──────┬───────────┬────────┘
                                    是│           │否
                        retry_count < 5│           │
                              ┌──────▼───┐  ┌────▼────────┐
                              │ 回到 0   │  │ 3 永久失败   │
                              │ 退避延后 │  │ + 告警       │
                              └──────────┘  └─────────────┘
```

**失败分类是这个设计里最容易被做错的地方**，必须区分：

- **永久失败（不重试，直接放弃）**：SMTP 5xx，尤其是 **550 无效收件地址**。重试只会浪费配额并损害发信域信誉。收件地址无效时还应同步置 `user.email_bounced_at`，停止后续发信。
- **可重试**：SMTP 4xx（含 421 服务不可用、450/451/452 临时拒绝）、连接超时、DNS 失败、认证失败（可能是授权码过期，重试有意义但应同时告警）。

**退避序列建议**：1 分钟 → 5 分钟 → 15 分钟 → 1 小时 → 6 小时，5 次后放弃。加**随机抖动（jitter）**，避免大量邮件在同一时刻集中重试。

**并发安全**：用条件更新抢占，而不是「先查后改」：

```sql
UPDATE email_outbox
SET status = 1, next_attempt_at = NOW()
WHERE id = ? AND status = 0;
-- 影响行数为 1 才说明本实例抢到了这一行
```

---

## 7. 幂等与去重

`dedupe_key` 的**唯一索引**是整套去重机制的落点：重复计算、任务重跑、重启后重复扫描，全部被数据库拦住，无需应用层加锁。

| 邮件类型 | dedupe_key 模板 |
|---|---|
| 生日提醒 | `birthday-advance:{userId}:{birthdayDate}` |
| 节日/调休提醒 | `festival-advance:{holidayDate}:{userId}` |
| 每日摘要 | `digest:daily:{userId}:{date}` |
| 每周摘要 | `digest:weekly:{userId}:{isoYear}-W{isoWeek}` |
| 邮箱验证码 | `verify:{userId}:{epochMillis}`（**故意不去重，允许用户重发**） |

**插入时用 `INSERT IGNORE` 或捕获唯一键冲突并静默忽略**——「已经发过了」是正常路径，不是异常，不该抛错更不该记 error 级日志。

`dedupe_key` 长度上限 128 已足够；`userId` 是数字，`holidayDate` 是 10 字符，余量充足。

---

## 8. 时间与调度

### 8.1 时区纪律

[AppTimeConfig.java](../src/main/java/com/kirarl/september/config/AppTimeConfig.java#L9-L23) 已经确立了「所有『今天』必须走注入的 `Clock`」这条纪律，调度器是同一个坑的**第二现场**：

- 「该不该现在给这个用户发摘要」必须用 `LocalDate.now(businessClock)`。
- 用无参数的 `LocalDate.now()`，在 UTC 云主机上，东八区用户会在**凌晨 0–8 点**收到「今天」的邮件——这正是该注释里描述的那类错判。
- **存储约定必须写死并注释**：`email_outbox.created_at`/`next_attempt_at`/`sent_at` 存的是**业务时区的本地时间**还是 UTC，两者绝不能混用。现有 `holiday.holiday_date` 是纯日期（无时区问题），但 `DATETIME` 时间戳有。建议统一存**业务时区本地时间**，与 `businessClock` 直接对齐，并在迁移/建表注释里写明。

### 8.2 调度设计

- `@EnableScheduling` 加在配置类上（**不要**加在 `MainApplication` 上，保持主类干净，与 `AppTimeConfig` 的现有风格一致）。
- **投递轮询**：每 30 秒 ~ 1 分钟。批量取（如 `LIMIT 50`），避免一次捞空全表。
- **摘要生成**：每日 07:00 一次；每周摘要按周一 07:00。
- **不要整点跑**。07:00 是用户可接受的时间，但所有任务都卡在 `:00` 会与将来的其他任务互相争抢。摘要生成用 07:00，投递轮询用固定间隔即可。
- **避开凌晨**：00:00–06:00 不应产生用户可见邮件（手机提示音会招致反感），生成与投递的窗口要显式限制。
- **单实例假设**：当前项目是单实例部署，`@Scheduled` 直接可用。**将来若多实例部署，每个实例都会跑一遍调度**——届时生成层靠 `dedupe_key` 天然免疫，但投递层必须靠 §6.4 的条件更新抢占 + 过期 `status=1` 行的回收（发送中超过 10 分钟视为实例崩溃，重置回待发）。**现在就把这个假设写成注释**，避免日后踩坑。

---

## 9. 安全与合规

### 9.1 凭据管理

- 授权码/API 密钥放 `application-local.yml`（已被 gitignore，与 `DB_PASSWORD` 同处）或环境变量 `MAIL_AUTH_CODE`。**绝不进 `application.yml`、绝不进 git、绝不进日志。**
- 需要在 [application-local.yml.example](../application-local.yml.example) 里补上占位符，让后来者知道要配什么。
- **异常日志必须脱敏**：JavaMail 的认证失败异常可能包含凭据片段，记日志前要过滤。同理，完整邮箱地址在日志里建议掩码（`u***@qq.com`）。
- 授权码有**有效期与轮换需求**：QQ 邮箱改密码会使其失效。`SmtpMailSender` 收到认证失败时应给出明确可诊断的日志，而不是一个泛化的 `MailException`。

### 9.2 频率限制（发信能力上线的前置条件）

三层，缺一不可：

| 层级 | 限制 | 防的是什么 |
|---|---|---|
| 接口层 | `/api/auth/bind-email` 按用户名 + IP 限流；验证码发送 60 秒冷却、每日上限 | 发信跳板（§2.4） |
| 用户层 | 每人每日上限（建议 ≤ 3 封） | 单个账号被滥用、用户被骚扰 |
| 全局层 | 每日总发送量上限（**方案 A 下必须显著低于邮箱的日限额**，否则当天后续全部失败并可能触发临时封禁） | 配额耗尽导致的全量失败 |

全局层限流的具体做法：`OutboxDispatcher` 每轮开始前统计「今日已发送数」，达到上限就**只跳过不失败**（邮件留在出箱里，次日继续），并记录告警。

### 9.3 内容安全

- **模板内所有变量自动转义**。`username`、`holiday.name`（来自外部导入的数据）都是不受信输入，拼进 HTML 会产生注入。让 `EmailRenderer` 成为唯一出口，禁止任何地方手拼 HTML。
- **必须同时生成纯文本版本**。只有 HTML 的邮件在部分客户端（尤其企业邮箱）会被显著降权。
- **邮件里的链接必须带签名令牌**。因为项目无会话机制，退订/确认链接只能靠签名自证。用 HMAC-SHA256：

  ```
  token = base64url( userId + "." + expiry + "." + HMAC_SHA256(userId + expiry, unsubscribeSecret) )
  ```

  校验时**先比签名再比过期时间**，且用**恒定时间比较**（`MessageDigest.isEqual`）防时序侧信道。`unsubscribeSecret` 是独立密钥，与邮箱授权码分开，同样不入库不入 git。

### 9.4 退订

- 每封非账号类邮件（摘要、提醒）底部都要有**一键退订**链接，指向 `/api/notification/unsubscribe?token=...`（GET 即可生效，不要要求登录或二次确认）。
- 对批量邮件补上 `List-Unsubscribe` 与 `List-Unsubscribe-Post` 头（RFC 8058），部分邮箱客户端会直接显示「退订」按钮。这能明显降低「标记为垃圾邮件」的概率。
- 退订必须写入 `notification_preference`，且**账号类邮件（验证码、找回密码）不受退订影响**——这是一个必须显式区分的事务性邮件 vs 营销类邮件边界。

### 9.5 个人信息

- 邮箱地址 + 生日都属个人信息。收集生日要有正当用途说明（这里就是提醒与摘要，属于合理用途），且用户能关闭（`notification_preference` 已提供）。
- 删除账号时要级联清理：`email_outbox` 中未发送的行、`notification_preference`、`email_verification`。**已发送的历史邮件不必也无法撤回**，但要停止后续发送。

---

## 10. 送达率工程

只有做到以下几条，「邮件真的进了收件箱」才可控：

| 措施 | 方案 A（QQ 邮箱） | 方案 B（自有域名） |
|---|---|---|
| SPF / DKIM | 由腾讯维护，无需自己配 | **必须自己配**，否则大概率进垃圾箱 |
| DMARC | 同上 | 建议配 `p=none` 起步，观察后再收紧 |
| From 显示名 | 建议设成「SeptemberNine 日历」而非裸地址 | 同 |
| Reply-To | 设一个真实可收信的地址（不要 `no-reply` 且不可回，用户回信失败会投诉） | 同 |
| 内容 | 避免纯图、避免短链、避免夸张标题与全大写、图文比例合理 | 同 |
| 预热 | 新邮箱/新域名从每天几封开始，逐步爬坡 | 同 |
| 退信处理 | 硬退信立即停发该地址（§6.4） | 同 |

**方案 A 的一个现实结论**：个人 QQ 邮箱的日限额不足以支撑真实用户量。它只适合内测；一旦超过几十个活跃用户，就必须切到 B。**这也是为什么 `MailSender` 的抽象必须在第一期就做对。**

---

## 11. 可观测性

没有度量就无法判断「邮件到底发出去没有」，尤其是异步之后用户不会立刻看到失败。

**核心指标**：

| 指标 | 说明 | 建议告警 |
|---|---|---|
| 出箱积压量 | `status=0` 的行数 | 持续 > 100 |
| 投递成功率 | 已发送 / (已发送 + 永久失败) | 低于 90% |
| 发送延迟 | `sent_at - created_at` 的 P95 | 超过 10 分钟 |
| 永久失败 Top 原因 | 按 `last_error` 聚合 | 出现新的 550 模式 |
| 每日发送量 | 对照配额 | 达到 80% 配额 |
| 认证失败次数 | 授权码过期/失效信号 | 任意一次 |

**实现**：第一期用日志 + 一条查询 SQL 即可（项目尚无 Micrometer/Prometheus 依赖，不急于引入）。建议同时提供一个给管理员用的排查接口或 SQL 片段，放进 `docs/`，例如：

```sql
SELECT status, COUNT(*) FROM email_outbox
WHERE created_at >= CURDATE() GROUP BY status;
```

---

## 12. 分期实施与验收标准

### Phase 0：清债（前置，不做完不应开发送功能）

| 任务 | 验收标准 |
|---|---|
| `user.email` 唯一索引 | 迁移组件能识别历史重复数据并给出可执行的清理 SQL |
| `email_verified` 列 + 验证流程 | 未验证的邮箱**在所有发信查询里被过滤掉** |
| 验证码表 + 发送接口限流 | 同 IP 60 秒内无法触发第二封 |
| `bind-email` 限流 | 超过阈值的请求被拒绝且不产生任何出箱记录 |

### Phase 1：通路打通（最小闭环）

| 任务 | 验收标准 |
|---|---|
| `spring-boot-starter-mail` + 配置 | 手工触发一封测试邮件，能在 QQ 邮箱收件箱看到，**且能看到纯文本版本** |
| `MailSender` / `SmtpMailSender` | 业务代码中 grep 不到 `JavaMailSender` |
| `email_outbox` + `OutboxDispatcher` | 断开 SMTP（改错端口）后邮件留在出箱并退避重试；恢复后自动发出 |
| 退订链路 | 退订链接 GET 一次即生效，无需登录 |
| 超时配置 | SMTP host 指向不可达地址时，请求线程**不被阻塞**（异步） |

### Phase 2：节日/调休提醒（本期主线价值）

| 任务 | 验收标准 |
|---|---|
| `businessClock` 驱动的每日扫描 | 把服务器 JVM 时区改为 UTC 后行为不变 |
| 与 `CalendarService.resolveLabel` 一致的展示口径 | 同一天日历页与邮件的名称完全一致 |
| 调休上班提醒 | 造一条 `day_type=2` 的测试数据，次日能收到提醒 |
| 幂等 | 同一任务连续执行 10 次，只产生 1 封邮件 |
| 「无内容不发」 | 无任何节日的平淡日子不产生邮件 |

### Phase 3：摘要 + 可扩展骨架

| 任务 | 验收标准 |
|---|---|
| `DigestSectionProvider` 抽象 | 新增一个 section 实现类，不改动投递/渲染/退订任何代码 |
| 每日 / 每周摘要 | ISO 周编号跨年正确（如 2026-12-31 属于 2026-W53） |
| 农历生日字段预留 | `birthday_calendar_type` 生效于摘要展示，公历行为不变 |

### Phase 4（依赖外部模块，暂不排期）

- 事件/日程模块落地 → 新增 `EventDigestSectionProvider`，摘要自动包含日程。
- 联系人模块落地 → 他人生日提醒（需同时解决个人信息合规）。
- 多实例部署 → 投递层条件更新抢占 + `status=1` 超时回收。
- 切换方案 B → 只改配置与 `mail.from`，新增/替换 `MailSender` 实现。

---

## 13. 未决问题（需要作者拍板）

1. **生日的历法口径**：只支持公历，还是必须支持农历？若支持，闰月出生的人如何提醒？（建议：先公历，字段预留）
2. **摘要频率的默认值**：默认每日、默认每周，还是默认关闭（要求用户主动订阅）？涉及「默认同意」的合规倾向，建议默认关闭或至少默认每周。
3. **发送时间**：固定 07:00，还是将来做用户时区/时间段偏好？（当前无用户时区字段）
4. **2 月 29 日的策略**：2/28 还是 3/1？
5. **是否需要「给朋友发邀请」类邮件**：这属于营销类邮件，规则与事务性邮件完全不同（退订、频控、信誉），建议明确排除在本期之外。
6. **`holiday` 表次年数据缺失时**：是静默跳过、还是给管理员告警？（建议后者）

---

## 附录 A：配置样例（方案 A）

放入 `application-local.yml`（已 gitignore），并在 `application-local.yml.example` 中补对应占位符：

```yaml
MAIL_USERNAME: 你的QQ号@qq.com
MAIL_AUTH_CODE: 邮箱设置里生成的16位授权码   # 不是QQ登录密码
MAIL_FROM: 你的QQ号@qq.com
MAIL_FROM_NAME: SeptemberNine 日历
MAIL_REPLY_TO: 你的QQ号@qq.com
UNSUBSCRIBE_SECRET: 一段随机字符串，与上两项分开保管
```

`application.yml` 中：

```yaml
spring:
  mail:
    host: smtp.qq.com
    port: 465
    username: ${MAIL_USERNAME}
    password: ${MAIL_AUTH_CODE}
    properties:
      mail.smtp.ssl.enable: true
      mail.smtp.auth: true
      # 必须显式设置：Jakarta Mail 默认超时为「无限」，
      # 服务器卡住会让发送线程永久挂起，进而拖垮线程池。
      mail.smtp.connectiontimeout: 5000
      mail.smtp.timeout: 5000
      mail.smtp.writetimeout: 5000

september:
  mail:
    enabled: ${MAIL_ENABLED:false}   # 默认关闭，开发环境不误发
    from: ${MAIL_FROM}
    from-name: ${MAIL_FROM_NAME}
    reply-to: ${MAIL_REPLY_TO}
    unsubscribe-secret: ${UNSUBSCRIBE_SECRET}
    # 全局日发送上限，必须显著低于邮箱服务商配额，否则会触发临时封禁
    daily-limit: 30
    per-user-daily-limit: 3
```

> `september.mail.enabled` 默认 `false` 是有意为之：**避免开发环境用错配置给真实用户发出测试邮件**。这类事故一旦发生无法撤回。

## 附录 B：分层骨架（签名级，非完整实现）

```java
// 唯一对外暴露的发信契约。业务代码只依赖它，不依赖 SMTP。
public interface MailSender {
    void send(EmailMessage message) throws MailDeliveryException;
}

// 只有这个类知道 SMTP 的存在；换供应商只改这一个实现或只改配置。
@Service
public class SmtpMailSender implements MailSender { /* JavaMailSender */ }

// 可重试 / 不可重试的区分，是重试策略的依据。
public class MailDeliveryException extends Exception {
    private final boolean retryable;
}

// 出箱：业务事务只调用它，它只写库。
@Service
public class OutboxService {
    /** 幂等：dedupe_key 冲突时返回 false，调用方无需特殊处理 */
    public boolean enqueue(EmailMessage message);
}

// 投递：唯一会失败、唯一需要重试的地方。
@Component
public class OutboxDispatcher { /* 轮询 + 限速 + 退避 + 失败分类 */ }

// 摘要的可扩展点：新增 section 无需改动投递/渲染/退订。
public interface DigestSectionProvider { /* 见 §5.3 */ }
```

## 附录 C：与现有代码的衔接点清单

| 衔接点 | 现有位置 | 本次需要做的事 |
|---|---|---|
| 造一个业务时钟 Bean | [AppTimeConfig.java](../src/main/java/com/kirarl/september/config/AppTimeConfig.java) | 复用，不新建时区常量 |
| 展示口径（节气/节日优先级） | [CalendarService#resolveLabel](../src/main/java/com/kirarl/september/service/CalendarService.java#L126-L144) | **提取复用**，避免邮件与日历页文案不一致 |
| 建表 | [schema.sql](../src/main/resources/schema.sql) | 追加三张新表，保持 `IF NOT EXISTS` 幂等 |
| 加列迁移 | [UserTableMigration.java](../src/main/java/com/kirarl/september/config/UserTableMigration.java) | 照抄模式新增 `EmailNotificationMigration`，含重复邮箱检查 |
| 私密配置 | [application-local.yml.example](../application-local.yml.example) | 补邮件相关占位符 |
| 接口风格 | [Result.java](../src/main/java/com/kirarl/september/util/Result.java) | 新接口统一用 `Result` |
| 无鉴权的现状 | [CalendarController 的注释](../src/main/java/com/kirarl/september/controller/CalendarController.java#L17-L19) | 该注释已预告「将来叠加个人数据需加鉴权」，通知偏好接口正是第一个这类接口 |
