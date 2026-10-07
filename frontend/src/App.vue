<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const mode = ref('login')
const currentView = ref('auth')
const username = ref('')
const password = ref('')
const email = ref('')
const bindPassword = ref('')
const activeUsername = ref('')
const hasEmail = ref(false)
const gender = ref('')
const birthday = ref('')
const loading = ref(false)
const message = ref('')
const error = ref('')
// 'prompt' = 登录后的可选提示；'manage' = 从用户中心主动进入修改
const emailPromptMode = ref('prompt')
const userPanelOpen = ref(false)
const userPanelRef = ref(null)
const userToggleRef = ref(null)

const EMAIL_PROMPT_KEY = 'september:emailPromptSkipped'
const GENDER_OPTIONS = ['男', '女', '武装直升机']
const WEEKDAYS = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六']

/** 取本地时区的今天，作为生日选择的上限（toISOString 是 UTC，会错一天）。 */
function toIsoDate(date) {
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${date.getFullYear()}-${month}-${day}`
}

/** ISO 周序号：以本周四所在的那一年、那一周为准。 */
function toIsoWeek(date) {
  const thursday = new Date(date.getFullYear(), date.getMonth(), date.getDate())
  // getDay() 里周日是 0，这里换算成 ISO 的周一为 1
  thursday.setDate(thursday.getDate() + 4 - (thursday.getDay() || 7))
  // 天数差必须用 UTC 毫秒来算：本地时间做差会被夏令时偏移 1 小时，
  // 当结果恰好落在周界上时 Math.ceil 会多算一周（南半球时区尤其明显）。
  const dayDiff = Math.round(
    (Date.UTC(thursday.getFullYear(), thursday.getMonth(), thursday.getDate())
      - Date.UTC(thursday.getFullYear(), 0, 1)) / 86400000
  )
  return { year: thursday.getFullYear(), week: Math.floor(dayDiff / 7) + 1 }
}

/**
 * 「今天」统一从这里取。
 *
 * businessToday 来自后端（东八区口径），登录或拉日历时会带回来；
 * 拿不到时才回退到设备本地日期。这样生日上限、日历高亮与后端的判断
 * 始终一致，不会因为访问者设备的时区而错开一天。
 */
const businessToday = ref('')
const localNow = ref(new Date())
const todayIso = computed(() => businessToday.value || toIsoDate(localNow.value))
// 用本地时间构造（不带 Z），这样 getDate()/getDay() 读出来就是日历上的那一天
const today = computed(() => new Date(`${todayIso.value}T00:00:00`))
const todayDate = computed(() => today.value.getDate())
const todayLabel = computed(() => `${today.value.getMonth() + 1} 月 ${today.value.getDate()} 日`)
const weekdayLabel = computed(() => WEEKDAYS[today.value.getDay()])
const weekLabel = computed(() => {
  const iso = toIsoWeek(today.value)
  return `${iso.year} 年第 ${iso.week} 周`
})

const isLogin = computed(() => mode.value === 'login')
const title = computed(() => (isLogin.value ? '欢迎回来' : '创建你的账户'))
const subtitle = computed(() => (
  isLogin.value ? '登录后继续使用 September' : '注册一个账户，马上开始使用'
))
const step = computed(() => (
  currentView.value === 'auth' ? (isLogin.value ? '1' : '2') : '3'
))
const isHome = computed(() => currentView.value === 'home')
const emailPromptKey = computed(() => `${EMAIL_PROMPT_KEY}:${activeUsername.value}`)
const avatarText = computed(() => (activeUsername.value.trim().charAt(0) || 'S').toUpperCase())
// 性别与生日未自定义时，统一显示“保密”
const genderLabel = computed(() => gender.value || '保密')
const birthdayLabel = computed(() => birthday.value || '保密')

function wasEmailPromptSkipped() {
  try {
    return localStorage.getItem(emailPromptKey.value) === 'skipped'
  } catch {
    return false
  }
}

function rememberEmailPromptSkipped() {
  try {
    localStorage.setItem(emailPromptKey.value, 'skipped')
  } catch {
    // 隐私模式下 localStorage 不可用，忽略即可
  }
}

function resetAccountState() {
  username.value = ''
  password.value = ''
  email.value = ''
  bindPassword.value = ''
  activeUsername.value = ''
  hasEmail.value = false
  gender.value = ''
  birthday.value = ''
  message.value = ''
  error.value = ''
}

function switchMode(nextMode) {
  currentView.value = 'auth'
  mode.value = nextMode
  resetAccountState()
}

function logout() {
  userPanelOpen.value = false
  emailPromptMode.value = 'prompt'
  currentView.value = 'auth'
  mode.value = 'login'
  resetAccountState()
}

async function submit() {
  message.value = ''
  error.value = ''

  if (!username.value.trim() || !password.value) {
    error.value = '请输入用户名和密码'
    return
  }

  loading.value = true
  try {
    const response = await fetch(`/api/auth/${isLogin.value ? 'login' : 'register'}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        username: username.value.trim(),
        password: password.value
      })
    })
    const result = await response.json()

    if (!response.ok || !result.success) {
      throw new Error(result.message || '请求失败，请稍后再试')
    }

    if (isLogin.value) {
      const data = result.data || {}
      activeUsername.value = data.username || username.value.trim()
      hasEmail.value = Boolean(data.hasEmail)
      email.value = hasEmail.value ? (data.email || '') : ''
      gender.value = data.gender || ''
      birthday.value = data.birthday || ''
      // 后端下发的业务今天（东八区），作为生日上限与日历高亮的统一口径
      businessToday.value = data.today || ''
      bindPassword.value = password.value
      message.value = ''
      // 已绑定过邮箱，或用户之前选过"暂不绑定"，都不再打扰
      currentView.value = hasEmail.value || wasEmailPromptSkipped() ? 'home' : 'bind-email'
    } else {
      switchMode('login')
      message.value = '注册成功，请使用新账号登录'
    }
  } catch (requestError) {
    error.value = requestError.message || '无法连接服务器，请确认后端已启动'
  } finally {
    loading.value = false
  }
}

async function bindEmail() {
  message.value = ''
  error.value = ''
  if (!email.value.trim()) {
    error.value = '请输入个人邮箱'
    return
  }
  if (!bindPassword.value) {
    error.value = '请输入登录密码以确认身份'
    return
  }

  loading.value = true
  try {
    const response = await fetch('/api/auth/bind-email', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        username: activeUsername.value,
        email: email.value.trim(),
        password: bindPassword.value
      })
    })
    const result = await response.json()
    if (!response.ok || !result.success) {
      throw new Error(result.message || '邮箱绑定失败，请稍后再试')
    }
    email.value = email.value.trim()
    hasEmail.value = true
    message.value = result.message
    currentView.value = 'home'
    // 回到工作台并展开用户中心，让用户看到最新的邮箱状态
    userPanelOpen.value = true
  } catch (requestError) {
    error.value = requestError.message || '无法连接服务器，请确认后端已启动'
  } finally {
    loading.value = false
  }
}

/** 保存性别与生日，两者都可以留空，留空即“保密”。 */
async function saveProfile() {
  message.value = ''
  error.value = ''
  if (!bindPassword.value) {
    error.value = '登录状态已失效，请重新登录后再保存'
    return
  }

  loading.value = true
  try {
    const response = await fetch('/api/auth/update-profile', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        username: activeUsername.value,
        password: bindPassword.value,
        gender: gender.value,
        birthday: birthday.value
      })
    })
    const result = await response.json()
    if (!response.ok || !result.success) {
      throw new Error(result.message || '资料保存失败，请稍后再试')
    }
    message.value = result.message
  } catch (requestError) {
    error.value = requestError.message || '无法连接服务器，请确认后端已启动'
  } finally {
    loading.value = false
  }
}

/** 一键把性别与生日恢复成“保密”，并立即保存。 */
async function resetProfileToSecret() {
  gender.value = ''
  birthday.value = ''
  await saveProfile()
}

function skipEmail() {
  rememberEmailPromptSkipped()
  message.value = ''
  error.value = ''
  userPanelOpen.value = false
  emailPromptMode.value = 'prompt'
  currentView.value = 'home'
}

/** 从用户中心进入邮箱表单，完成后可返回工作台。 */
function openEmailForm() {
  message.value = ''
  error.value = ''
  userPanelOpen.value = false
  emailPromptMode.value = 'manage'
  currentView.value = 'bind-email'
}

function backToHome() {
  message.value = ''
  error.value = ''
  currentView.value = 'home'
}

function toggleUserPanel() {
  userPanelOpen.value = !userPanelOpen.value
  if (userPanelOpen.value) {
    message.value = ''
    error.value = ''
    nextTick(() => userPanelRef.value?.focus())
  }
}

function closeUserPanel() {
  userPanelOpen.value = false
}

function isInsideUserCenter(target) {
  if (!target || typeof target.nodeType !== 'number') {
    return false
  }
  return Boolean(
    (userPanelRef.value && userPanelRef.value.contains(target))
    || (userToggleRef.value && userToggleRef.value.contains(target))
  )
}

function handleDocumentPointerDown(event) {
  if (userPanelOpen.value && !isInsideUserCenter(event.target)) {
    userPanelOpen.value = false
  }
}

function handleDocumentKeydown(event) {
  if (event.key === 'Escape' && userPanelOpen.value) {
    closeUserPanel()
  }
}

// ---------- 主功能区：日历 ----------
// 网格、农历、节气、节日、节假日/调休全部由后端装配好，
// 前端只负责把 days 铺进 7 列网格，不做任何日期运算。
const calendarYear = ref(0)
const calendarMonth = ref(0)
const calendarDays = ref([])
const calendarWeekdays = ref(WEEKDAYS)
const calendarLabel = ref('')
const calendarHolidayAvailable = ref(true)
const calendarError = ref('')
const calendarLoading = ref(false)

/** 取某个月的日历；不传年月时后端按业务当月返回。 */
async function loadCalendar(year, month) {
  const query = year && month ? `?year=${year}&month=${month}` : ''
  calendarLoading.value = true
  calendarError.value = ''
  try {
    const response = await fetch(`/api/calendar/month${query}`)
    const result = await response.json()
    if (!response.ok || !result.success) {
      throw new Error(result.message || '日历加载失败')
    }
    const data = result.data || {}
    calendarYear.value = data.year || 0
    calendarMonth.value = data.month || 0
    calendarDays.value = data.days || []
    calendarWeekdays.value = data.weekdays || WEEKDAYS
    calendarLabel.value = data.monthLabel || ''
    calendarHolidayAvailable.value = Boolean(data.holidayDataAvailable)
    // 顺手刷新业务今天，保证日历高亮与生日上限跟后端一致
    if (data.today) {
      businessToday.value = data.today
    }
  } catch (requestError) {
    calendarError.value = requestError.message || '无法连接服务器'
  } finally {
    calendarLoading.value = false
  }
}

/** 前后翻月。 */
function shiftMonth(step) {
  const base = new Date(calendarYear.value, calendarMonth.value - 1 + step, 1)
  loadCalendar(base.getFullYear(), base.getMonth() + 1)
}

watch(isHome, (value) => {
  if (value) {
    loadCalendar(null, null)
  }
})

/** 跨零点后刷新，避免页面长时间开着还显示昨天。 */
function refreshTodayIfNeeded() {
  const now = new Date()
  if (toIsoDate(now) === toIsoDate(localNow.value)) {
    return
  }
  localNow.value = now
  if (isHome.value) {
    // 重新拉一次当前显示的月份，同时把后端口径的今天同步过来
    loadCalendar(calendarYear.value || null, calendarMonth.value || null)
  }
}

let todayTimer = null

onMounted(() => {
  document.addEventListener('pointerdown', handleDocumentPointerDown)
  document.addEventListener('keydown', handleDocumentKeydown)
  todayTimer = window.setInterval(refreshTodayIfNeeded, 60000)
})

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', handleDocumentPointerDown)
  document.removeEventListener('keydown', handleDocumentKeydown)
  if (todayTimer !== null) {
    window.clearInterval(todayTimer)
  }
})
</script>

<template>
  <!-- ================= 登录 / 注册 / 邮箱表单 ================= -->
  <div v-if="!isHome" class="stage">
    <span class="ambient ambient--soft" aria-hidden="true"></span>
    <span class="ambient ambient--bloom" aria-hidden="true"></span>

    <main class="workspace">
      <section class="panel brand" aria-label="九月九">
        <p class="index-badge">#07 / 50</p>

        <header class="brand-head">
          <figure class="mascot-frame">
            <img
              class="mascot-photo"
              src="/september-mascot.jpg"
              alt="九月九 · 吉祥物"
              width="800"
              height="800"
              decoding="async"
            />
            <figcaption class="pop-badge">九月九</figcaption>
          </figure>

          <p class="brand-eyebrow">September</p>
          <h1 class="brand-title">九月九</h1>
          <p class="brand-latin">SEPTEMBER NINE</p>
        </header>

        <!-- 当日大日历：上面年月，中间大数字，下面星期 -->
        <div class="month-board">
          <p class="day-label">{{ today.getFullYear() }} 年 {{ today.getMonth() + 1 }} 月</p>
          <p class="day-number" aria-hidden="true">{{ todayDate }}</p>
          <p class="day-sub">{{ weekdayLabel }}</p>
        </div>

        <ul class="day-facts">
          <li>{{ todayLabel }}</li>
          <li>{{ weekLabel }}</li>
        </ul>

        <p class="brand-foot">{{ todayLabel }} · 祝你今天顺利</p>
      </section>

      <section class="panel auth">
        <p class="index-badge">#0{{ step }} / 03</p>

        <div v-if="currentView === 'auth'" class="auth-view">
          <header class="auth-head">
            <span class="brand-mark" aria-hidden="true">S</span>
            <div class="auth-head-text">
              <p class="eyebrow">九月 · 账户中心</p>
              <h2 class="auth-title">{{ title }}</h2>
            </div>
          </header>
          <p class="auth-desc">{{ subtitle }}</p>

          <div class="mode-switch" role="group" aria-label="账户操作">
            <button
              type="button"
              :class="{ active: isLogin }"
              :aria-pressed="isLogin"
              @click="switchMode('login')"
            >
              登录
            </button>
            <button
              type="button"
              :class="{ active: !isLogin }"
              :aria-pressed="!isLogin"
              @click="switchMode('register')"
            >
              注册
            </button>
          </div>

          <form @submit.prevent="submit">
            <div class="field">
              <label for="username">用户名</label>
              <input
                id="username"
                v-model="username"
                type="text"
                autocomplete="username"
                placeholder="请输入用户名"
              />
            </div>

            <div class="field">
              <label for="password">密码</label>
              <input
                id="password"
                v-model="password"
                type="password"
                :autocomplete="isLogin ? 'current-password' : 'new-password'"
                placeholder="请输入密码"
              />
            </div>

            <p v-if="error" class="notice notice--error" role="alert">{{ error }}</p>
            <p v-if="message" class="notice notice--success" role="status">{{ message }}</p>

            <button class="submit-button" type="submit" :disabled="loading">
              <span>{{ loading ? '处理中…' : (isLogin ? '进入账户' : '创建账户') }}</span>
              <span class="arrow" aria-hidden="true">→</span>
            </button>
          </form>

          <p class="form-footnote">
            {{ isLogin ? '还没有账户？' : '已经有账户？' }}
            <button type="button" @click="switchMode(isLogin ? 'register' : 'login')">
              {{ isLogin ? '立即注册' : '返回登录' }}
            </button>
          </p>
        </div>

        <div v-else-if="currentView === 'bind-email'" class="auth-view">
          <span class="bind-icon" aria-hidden="true">@</span>
          <p class="eyebrow">Account / Email</p>
          <h2 class="auth-title">绑定个人邮箱（可选）</h2>
          <p class="auth-desc">绑定后可接收重要通知，也可以稍后在账户中心补充。</p>

          <p class="account-pill">
            <span>当前账户</span>
            <strong>{{ activeUsername }}</strong>
          </p>

          <form @submit.prevent="bindEmail">
            <div class="field">
              <label for="email">邮箱地址</label>
              <input
                id="email"
                v-model="email"
                type="email"
                autocomplete="email"
                placeholder="name@example.com"
              />
            </div>

            <div class="field">
              <label for="bind-password">密码（确认身份）</label>
              <input
                id="bind-password"
                v-model="bindPassword"
                type="password"
                autocomplete="current-password"
                placeholder="请输入登录密码"
              />
            </div>

            <p v-if="error" class="notice notice--error" role="alert">{{ error }}</p>
            <p v-if="message" class="notice notice--success" role="status">{{ message }}</p>

            <button class="submit-button" type="submit" :disabled="loading">
              <span>{{ loading ? '保存中…' : '保存邮箱' }}</span>
              <span class="arrow" aria-hidden="true">→</span>
            </button>
          </form>

          <div class="ghost-row">
            <button
              v-if="emailPromptMode === 'prompt'"
              class="ghost-button"
              type="button"
              @click="skipEmail"
            >
              暂不绑定，稍后再说
            </button>
            <button v-else class="ghost-button" type="button" @click="backToHome">返回工作台</button>
            <button class="ghost-button" type="button" @click="logout">退出当前账户</button>
          </div>
        </div>

      </section>
    </main>
  </div>

  <!-- ================= 登录后的工作台 ================= -->
  <div v-else class="stage stage--home">
    <span class="ambient ambient--soft" aria-hidden="true"></span>
    <span class="ambient ambient--bloom" aria-hidden="true"></span>

    <section class="panel canvas" aria-label="主功能区">
      <header class="canvas-bar">
        <div class="canvas-brand">
          <img
            class="brand-mark brand-mark--sm brand-mark--photo"
            src="/september-mascot.jpg"
            alt="九月"
            width="800"
            height="800"
            decoding="async"
          />
          <div class="auth-head-text">
            <p class="eyebrow">九月 · 工作台</p>
            <h1 class="canvas-heading">工作台</h1>
          </div>
        </div>
        <p class="session-pill">
          <span class="session-dot" aria-hidden="true"></span>
          {{ activeUsername }} 已登录
        </p>
      </header>

      <!-- 主功能区域：日历。
           公历网格、农历、节气、节日、节假日/调休全部由后端装配，
           这里只负责把 days 铺进 7 列网格。 -->
      <div class="canvas-well canvas-well--calendar">
        <span class="tick tick--tl" aria-hidden="true"></span>
        <span class="tick tick--tr" aria-hidden="true"></span>
        <span class="tick tick--bl" aria-hidden="true"></span>
        <span class="tick tick--br" aria-hidden="true"></span>

        <div class="calendar" :class="{ 'is-loading': calendarLoading }">
          <header class="calendar-head">
            <button class="calendar-nav" type="button" aria-label="上个月" @click="shiftMonth(-1)">‹</button>
            <div class="calendar-title">
              <p class="calendar-month">{{ calendarLabel || '日历' }}</p>
              <p class="calendar-sub">
                {{ calendarHolidayAvailable ? '农历 · 节气 · 节假日' : '该年放假安排尚未发布' }}
              </p>
            </div>
            <button class="calendar-nav" type="button" aria-label="下个月" @click="shiftMonth(1)">›</button>
          </header>

          <div class="calendar-weekdays" aria-hidden="true">
            <span v-for="weekday in calendarWeekdays" :key="weekday">{{ weekday }}</span>
          </div>

          <div class="calendar-grid">
            <div
              v-for="day in calendarDays"
              :key="day.date"
              class="calendar-cell"
              :class="{
                'is-out': !day.inMonth,
                'is-today': day.today,
                'is-rest': day.holidayType === 1,
                'is-work': day.holidayType === 2
              }"
              :title="day.holidayName || day.date"
            >
              <span class="calendar-day">{{ day.day }}</span>
              <span class="calendar-label">{{ day.label }}</span>
              <span v-if="day.holidayType" class="calendar-badge">
                {{ day.holidayType === 1 ? '休' : '班' }}
              </span>
            </div>
          </div>

          <p v-if="calendarError" class="notice notice--error" role="alert">{{ calendarError }}</p>
        </div>
      </div>

      <footer class="canvas-foot">
        <p class="canvas-meta">
          <span class="canvas-meta__user">{{ activeUsername }}</span>
          <span class="canvas-meta__dot" aria-hidden="true"></span>
          {{ hasEmail ? '邮箱已绑定' : '邮箱未绑定' }}
        </p>
        <p class="canvas-note">九月 · 祝你今天顺利</p>
      </footer>
    </section>

    <!-- 角落的用户中心入口 -->
    <div class="corner-user">
      <button
        ref="userToggleRef"
        class="user-button"
        :class="{ 'is-open': userPanelOpen }"
        type="button"
        aria-label="用户中心"
        aria-controls="user-center-panel"
        :aria-expanded="userPanelOpen"
        @click="toggleUserPanel"
      >
        <svg
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="1.7"
          stroke-linecap="round"
          stroke-linejoin="round"
          aria-hidden="true"
        >
          <circle cx="12" cy="8.3" r="3.6" />
          <path d="M4.9 19.6c.9-3.6 3.8-5.5 7.1-5.5s6.2 1.9 7.1 5.5" />
        </svg>
      </button>

      <transition name="pop">
        <div v-if="userPanelOpen" class="user-panel-shell">
          <p class="index-badge">#03 / 03</p>

          <aside
            id="user-center-panel"
            ref="userPanelRef"
            class="panel user-panel"
            tabindex="-1"
            aria-label="用户中心"
          >
            <header class="auth-head">
              <span class="avatar" aria-hidden="true">{{ avatarText }}</span>
              <div class="auth-head-text">
                <p class="eyebrow">User Center</p>
                <h2 class="auth-title">用户中心</h2>
              </div>
              <button
                class="icon-button"
                type="button"
                aria-label="收起用户中心"
                @click="closeUserPanel"
              >
                ×
              </button>
            </header>
            <p class="auth-desc">已登录，可在此管理你的邮箱信息。</p>

            <div class="account-body">
              <p class="account-pill">
                <span>当前账户</span>
                <strong>{{ activeUsername }}</strong>
              </p>
              <p class="account-pill">
                <span>邮箱状态</span>
                <strong>{{ hasEmail && email ? email : '未绑定' }}</strong>
              </p>
              <p class="account-pill">
                <span>性别</span>
                <strong>{{ genderLabel }}</strong>
              </p>
              <p class="account-pill">
                <span>生日</span>
                <strong>{{ birthdayLabel }}</strong>
              </p>

              <button class="submit-button" type="button" @click="openEmailForm">
                <span>{{ hasEmail ? '修改邮箱' : '补充邮箱' }}</span>
                <span class="arrow" aria-hidden="true">→</span>
              </button>
            </div>

            <!-- 性别与生日：都留空即表示“保密” -->
            <div class="profile-body">
              <p class="profile-label">
                <span>个人资料</span>
                <button class="text-button" type="button" @click="resetProfileToSecret">恢复保密</button>
              </p>

              <div class="field">
                <p id="gender-label" class="field-label">性别</p>
                <div class="choice-group" role="group" aria-labelledby="gender-label">
                  <button
                    v-for="option in GENDER_OPTIONS"
                    :key="option"
                    type="button"
                    :class="{ active: gender === option }"
                    :aria-pressed="gender === option"
                    @click="gender = option"
                  >
                    {{ option }}
                  </button>
                </div>
              </div>

              <div class="field">
                <label for="birthday">生日</label>
                <input id="birthday" v-model="birthday" type="date" :max="todayIso" />
              </div>

              <button class="submit-button" type="button" :disabled="loading" @click="saveProfile">
                <span>{{ loading ? '保存中…' : '保存资料' }}</span>
                <span class="arrow" aria-hidden="true">→</span>
              </button>
            </div>

            <div v-if="error || message" class="user-status">
              <p v-if="error" class="notice notice--error" role="alert">{{ error }}</p>
              <p v-if="message" class="notice notice--success" role="status">{{ message }}</p>
            </div>

            <div class="ghost-row">
              <button class="ghost-button" type="button" @click="logout">退出当前账户</button>
            </div>
          </aside>
        </div>
      </transition>
    </div>
  </div>
</template>
