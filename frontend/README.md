# September 前端

这是基于 Vue 3 + Vite 的登录注册前端，配套 Spring Boot 后端使用。

## 访问地址

启动前端后访问：

```text
http://localhost:5173
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
