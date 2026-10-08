# September 前端

这是基于 Vue 3 + Vite 的登录注册前端，配套 Spring Boot 后端使用。

## 访问地址

启动前端后访问：

```text
cd 
```

启动后端后访问：

```text
http://localhost:8080
```

当前后端没有网页首页，提供以下接口：

```text
POST http://localhost:8080/api/auth/register
POST http://localhost:8080/api/auth/login
POST http://localhost:8080/api/auth/bind-email
POST http://localhost:8080/api/auth/update-profile
```

## 登录后的可选邮箱流程

1. 第 1、2 步（登录 / 注册）：只填用户名和密码，不要求邮箱。
2. 登录成功后，后端在 `data` 中回传 `hasEmail`：
   - `hasEmail = true`：直接进入账户中心，不再询问。
   - `hasEmail = false`：进入第 3 步「绑定个人邮箱（可选）」。
3. 第 3 步可跳过：点「暂不绑定，稍后再说」立即进入账户中心，并在 `localStorage`
   记录 `september:emailPromptSkipped:<用户名>`，同一浏览器不再重复询问。
4. 账户中心始终保留「补充邮箱 / 修改邮箱」入口，随时可以补绑。

## 启动方式

先启动后端 Spring Boot，确保运行在 `8080` 端口，并确认 MySQL 数据库 `septembernine` 可用。

> 数据库账号密码：`src/main/resources/application.yml` 里**不再保存明文**，
> 而是启动时自动加载项目根目录的 `application-local.yml`（已被 `.gitignore` 忽略）。
> 仓库里只提供模板 `application-local.yml.example`，复制成 `application-local.yml`
> 并填入自己的账号密码即可；也可以改用环境变量 `DB_USERNAME` / `DB_PASSWORD` 覆盖。
> 注意：Spring Boot 运行配置的工作目录需要是项目根目录（IntelliJ 默认如此）。

> 旧库升级：如果 `user` 表是早期版本创建的（只有 id / username / password），
> 启动时会由 `UserTableMigration` 自动补上缺失的列（`email`、`gender`、`birthday`），
> 无需手工执行 SQL。

然后在当前目录执行：

```powershell
cd frontend
npm install
npm run dev
```

浏览器打开：

```text
http://localhost:5173
```

## 接口请求示例

注册：

```json
{
  "username": "testuser",
  "password": "123456"
}
```

登录：

```json
{
  "username": "testuser",
  "password": "123456"
}
```

登录成功时 `data` 会带回账号状态，前端据此决定是否弹出可选的邮箱绑定步骤：

```json
{
  "success": true,
  "message": "登录成功，可绑定邮箱",
  "data": {
    "username": "testuser",
    "hasEmail": false,
    "email": null,
    "gender": null,
    "birthday": null
  }
}
```

绑定邮箱（可选操作，需要再次输入密码确认身份）：

```json
{
  "username": "testuser",
  "email": "testuser@example.com",
  "password": "123456"
}
```

## 用户中心：性别与生日

用户中心（工作台右上角的人形图标）里可以直接填写性别与生日，两者都可以留空，
**留空时统一显示为「保密」**，点「恢复保密」可以清空并立即保存。

保存资料（同样需要密码确认身份）：

```json
{
  "username": "testuser",
  "password": "123456",
  "gender": "武装直升机",
  "birthday": "1998-09-01"
}
```

- `gender` 只接受 `男`、`女`、`武装直升机`，传空字符串或 `null` 表示保密。
- `birthday` 使用 `yyyy-MM-dd`，允许范围是 `1900-01-01` 到今天，留空表示保密。
- 登录接口会在 `data` 中带回 `gender` / `birthday`（未设置为 `null`），
  前端据此在工作台打开时就把用户中心显示补全。

## 工作台核心区：日历交互

数据全部来自一个接口：

```text
GET /api/calendar/month?year=2026&month=10
```

省略参数时按业务当月返回。农历、节气、节日、节假日/调休都由后端解析，
前端只把 `days` 铺进 7 列网格，不做日期运算。围绕这个网格，工作台支持：

1. **点击日期 = 选中**：选中格是「凹陷 + 淡蓝底」，右侧详情面板同步显示
   大号日号、`2026 年 10 月 · 星期二`、相对今天（今天 / 明天 / N 天前后）、
   农历、节气、节日，以及该日的 `休 / 班`。点「回到今天」以外的日期都不会改变月份。
2. **点邻月灰格自动翻月**：网格首尾补位的邻月日期被点中时，会直接加载并切到那个月，
   同时把这一天设为选中。
3. **回到今天**：日历工具行左侧的按钮，回到业务当月并选中今天；
   已经在今天时置灰（`disabled`），避免重复点击。
4. **键盘导航**：`Tab` 进入网格后，`← →` 移动一天、`↑ ↓` 移动一周、
   `Home / End` 跳到本周首尾、`PageUp / PageDown` 翻月（保留「几号」，月末不足取当月最后一天）。
   跨月移动时会自动取回目标月份并把焦点落到新的选中格上。
   网格用 roving tabindex：只有选中格是 `tabindex="0"`，其余为 `-1`。
5. **悬停气泡**：鼠标停留显示「公历 + 星期 · 农历 · 节日/节气」与 `休 / 班` 标签；
   第一行格子改为向下展开，不会顶出画布；`(hover: none)` 的触屏设备不显示气泡，
   窗口尺寸变化时自动收起。
6. **年月快速跳转**：点日历标题（`2026 年 10 月 ▾`）打开年月选择器，
   年份 `‹ ›` 步进 + 12 个月按钮，范围 1900–2100（到边界时按钮置灰），
   底部「回到 X 年」可快速回到今年；点面板外部或按 `Esc` 关闭。
   当前查看的月份是按下态，业务今天所在的月份带一个小圆点。

实现上的三个细节：

- 连点翻月或快速跳转会同时发出多个请求，前端按请求序号**丢弃过期响应**，不会错页；
- 页面长时间打开时，跨零点会自动重新拉取当前月份，保持「今天」高亮与后端一致；
- 网格固定按 **6 行**排版（`.calendar-grid` 显式 `repeat(6, minmax(var(--calendar-row), 1fr))`），
  行高与列间距由 `.calendar` 上的 `--calendar-row`（`clamp(46px, 6vh, 62px)`）和
  `--calendar-gap` 决定。一个月是 4 周、5 周还是 6 周，核心区盒子的高度都一样，
  快速翻月时整张卡片不会忽高忽低；不足 6 行的月份在网格底部留白，
  行的位置也不会随月份上下移动。行高跟窗口高度挂钩，矮窗口下不会把卡片顶得过高。

窄屏（≤900px）下详情面板移到网格下方，`≤560px` 时把 `--calendar-row / --calendar-gap`
降到 `46px / 4px`，网格同样恒定 6 行高。

## 前后端代理

开发环境中，前端的 `/api` 请求会由 Vite 转发到：

```text
http://localhost:8080
```

因此前端页面不需要直接写后端完整地址。

## 首页左侧「九月九」品牌卡

第一屏左侧是**站点品牌卡**（不是设计风格说明），登录、注册、绑定邮箱三个界面共用，
组成如下：

- **吉祥物**：`public/september-mascot.jpg` 圆形头像（凹陷内圆 + 浮雕外圈），
  右下角浮起小胶囊；替换只需覆盖这个文件。
- **名称**：眉标 `SEPTEMBER`、主标题 `九月九`、副标题 `SEPTEMBER NINE`。
- **当日大日历**：下沉底板，上面一行 `2026 年 10 月`，中间是大号当日数字，
  下面一行是星期几。年 / 月 / 日 / 星期全部由 `new Date()` 实时推算，跨天自动更新。
- **当天信息胶囊**：`10 月 7 日`、`2026 年第 41 周`（ISO 周序号，由 `toIsoWeek()` 计算）。
- **页脚**：`M 月 D 日 · 祝你今天顺利`。

登录后的工作台同样用吉祥物做站标（`.brand-mark--photo`），页脚显示当前账户与邮箱状态。

视觉上沿用原有的卡片语言：卡片与背景同为 `--panel`，靠 `--lift*`（对向 box-shadow）
做浮雕、`--sink*`（inset）做凹陷按下态，圆角统一 20px / 28px。
