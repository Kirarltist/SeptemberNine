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

function isInsideMonthPicker(target) {
  if (!target || typeof target.nodeType !== 'number') {
    return false
  }
  return Boolean(pickerRef.value && pickerRef.value.contains(target))
}

function handleDocumentPointerDown(event) {
  if (userPanelOpen.value && !isInsideUserCenter(event.target)) {
    userPanelOpen.value = false
  }
  if (monthPickerOpen.value && !isInsideMonthPicker(event.target)) {
    monthPickerOpen.value = false
  }
}

function handleDocumentKeydown(event) {
  if (event.key !== 'Escape') {
    return
  }
  if (monthPickerOpen.value) {
    monthPickerOpen.value = false
    return
  }
  if (userPanelOpen.value) {
    closeUserPanel()
  }
}

// ---------- 主功能区：日历 ----------
// 网格、农历、节气、节日、节假日/调休全部由后端装配好，
// 前端只负责把 days 铺进 7 列网格，不做任何日期运算。
//
// 交互部分共有五件事：
//   1. 点击某天 → 选中并显示右侧详情（点到邻月灰格时顺带翻到那个月）；
//   2. 「回到今天」→ 回到业务当月并选中今天；
//   3. 方向键 / Home / End / PageUp / PageDown → 在网格内移动选中日；
//   4. 点年月标题 → 弹出年月选择器，直接跳到 1900–2100 的任意月份；
//   5. 鼠标悬停 → 自定义气泡显示当天农历与放假信息（触屏不出气泡）。
const CALENDAR_MIN_YEAR = 1900
const CALENDAR_MAX_YEAR = 2100
const MONTH_NAMES = Array.from({ length: 12 }, (unused, index) => `${index + 1} 月`)

const calendarYear = ref(0)
const calendarMonth = ref(0)
const calendarDays = ref([])
const calendarWeekdays = ref(WEEKDAYS)
const calendarLabel = ref('')
const calendarHolidayAvailable = ref(true)
const calendarError = ref('')
const calendarLoading = ref(false)

// 选中日期（ISO）是这一组交互的中心：详情面板、键盘导航、回到今天都以它为准
const selectedDate = ref('')
const hoverDate = ref('')
const hoverPosition = ref({ left: 0, top: 0, below: false })
const monthPickerOpen = ref(false)
const pickerYear = ref(0)
const pickerRef = ref(null)
const calendarMainRef = ref(null)
const calendarGridRef = ref(null)
// 触屏设备没有「悬停」，此时不显示跟随指针的气泡，避免点一下就残留一个浮层
const hoverCapable = window.matchMedia ? window.matchMedia('(hover: hover)').matches : true

/** 用本地时间解析 ISO 日期，避免 new Date('yyyy-MM-dd') 被当成 UTC 而错一天。 */
function parseIsoDate(iso) {
  return new Date(`${iso}T00:00:00`)
}

function addDays(iso, delta) {
  const date = parseIsoDate(iso)
  date.setDate(date.getDate() + delta)
  return toIsoDate(date)
}

function formatIsoDate(iso) {
  const date = parseIsoDate(iso)
  return `${date.getFullYear()} 年 ${date.getMonth() + 1} 月 ${date.getDate()} 日`
}

/** 相对业务今天的口语化描述（今天 / 明天 / N 天后 / N 天前）。 */
function relativeDayLabel(iso) {
  if (!iso || !todayIso.value) {
    return ''
  }
  const target = iso.split('-').map(Number)
  const base = todayIso.value.split('-').map(Number)
  // 同样用 UTC 毫秒做差，绕开夏令时导致的 23/25 小时日
  const diff = Math.round(
    (Date.UTC(target[0], target[1] - 1, target[2]) - Date.UTC(base[0], base[1] - 1, base[2])) / 86400000
  )
  if (diff === 0) {
    return '今天'
  }
  if (diff === 1) {
    return '明天'
  }
  if (diff === -1) {
    return '昨天'
  }
  return diff > 0 ? `${diff} 天后` : `${-diff} 天前`
}

/** 翻月时尽量保留「几号」：选中 7 号就跳到下月 7 号，月末不足时取当月最后一天。 */
function preferredDateInMonth(year, month) {
  const anchorDay = selectedDay.value ? selectedDay.value.day : today.value.getDate()
  const lastDay = new Date(year, month, 0).getDate()
  const day = Math.min(anchorDay, lastDay)
  return `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`
}

const selectedDay = computed(() => (
  calendarDays.value.find((day) => day.date === selectedDate.value) || null
))
const hoverDay = computed(() => (
  hoverDate.value
    ? calendarDays.value.find((day) => day.date === hoverDate.value) || null
    : null
))
const viewingToday = computed(() => {
  const iso = todayIso.value
  return calendarYear.value === Number(iso.slice(0, 4))
    && calendarMonth.value === Number(iso.slice(5, 7))
})
const isTodaySelected = computed(() => viewingToday.value && selectedDate.value === todayIso.value)
const selectedWeekday = computed(() => (
  selectedDay.value ? WEEKDAYS[parseIsoDate(selectedDay.value.date).getDay()] : ''
))
const selectedRelative = computed(() => (
  selectedDay.value ? relativeDayLabel(selectedDay.value.date) : ''
))

/** 详情面板的字段：只展示后端已经算好的信息，不在前端重算农历。 */
const selectedRows = computed(() => {
  const day = selectedDay.value
  if (!day) {
    return []
  }
  const rows = [{ key: 'lunar', label: '农历', value: day.lunar }]
  if (day.jieQi) {
    rows.push({ key: 'jieqi', label: '节气', value: day.jieQi })
  }
  if (day.label && day.label !== day.lunar && day.label !== day.jieQi) {
    rows.push({ key: 'festival', label: '节日', value: day.label })
  }
  if (day.holidayType) {
    rows.push({
      key: 'holiday',
      label: '假期',
      value: `${day.holidayName || '调休'} · ${day.holidayType === 1 ? '放假' : '调休上班'}`
    })
  }
  if (!day.inMonth) {
    rows.push({ key: 'out', label: '位置', value: '邻月日期，点击可跳到该月' })
  }
  return rows
})

/** 单元格的无障碍名称：读屏用户拿到的信息与气泡一致。 */
function cellLabel(day) {
  const parts = [`${formatIsoDate(day.date)} ${WEEKDAYS[parseIsoDate(day.date).getDay()]}`]
  if (day.today) {
    parts.push('今天')
  }
  parts.push(`农历${day.lunar}`)
  if (day.label && day.label !== day.lunar) {
    parts.push(day.label)
  }
  if (day.holidayType === 1) {
    parts.push(`${day.holidayName || '假期'}放假`)
  } else if (day.holidayType === 2) {
    parts.push(`${day.holidayName || '调休'}上班`)
  }
  return parts.join('，')
}

function hoverMeta(day) {
  const parts = [WEEKDAYS[parseIsoDate(day.date).getDay()], `农历${day.lunar}`]
  if (day.label && day.label !== day.lunar) {
    parts.push(day.label)
  }
  return parts.join(' · ')
}

/** 递增的请求序号，用来丢弃过期响应。 */
let calendarRequestId = 0

/** 取某个月的日历；不传年月时后端按业务当月返回。 */
async function loadCalendar(year, month, options = {}) {
  const query = year && month ? `?year=${year}&month=${month}` : ''
  // 连点翻月或快速跳转时会有多个请求同时在飞，只认最后发出的那个
  calendarRequestId += 1
  const requestId = calendarRequestId
  calendarLoading.value = true
  calendarError.value = ''
  try {
    const response = await fetch(`/api/calendar/month${query}`)
    const result = await response.json()
    if (!response.ok || !result.success) {
      throw new Error(result.message || '日历加载失败')
    }
    if (requestId !== calendarRequestId) {
      return
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
    // 选中日期必须落在新网格里：优先用调用方指定的那一天，
    // 否则退回本月今天，再退回当月第一天。
    const wanted = options.selectDate || selectedDate.value
    if (calendarDays.value.some((day) => day.date === wanted)) {
      selectedDate.value = wanted
    } else {
      const anchor = calendarDays.value.find((day) => day.today)
        || calendarDays.value.find((day) => day.inMonth)
      selectedDate.value = anchor ? anchor.date : ''
    }
    hoverDate.value = ''
    monthPickerOpen.value = false
    if (options.focus) {
      await nextTick()
      focusSelectedCell()
    }
  } catch (requestError) {
    if (requestId === calendarRequestId) {
      calendarError.value = requestError.message || '无法连接服务器'
    }
  } finally {
    if (requestId === calendarRequestId) {
      calendarLoading.value = false
    }
  }
}

function canShiftMonth(step) {
  if (!calendarYear.value || !calendarMonth.value) {
    return false
  }
  const year = new Date(calendarYear.value, calendarMonth.value - 1 + step, 1).getFullYear()
  return year >= CALENDAR_MIN_YEAR && year <= CALENDAR_MAX_YEAR
}

/** 前后翻月，尽量保留原来的「几号」。 */
function shiftMonth(step, options = {}) {
  const base = new Date(calendarYear.value, calendarMonth.value - 1 + step, 1)
  const year = base.getFullYear()
  const month = base.getMonth() + 1
  if (year < CALENDAR_MIN_YEAR || year > CALENDAR_MAX_YEAR) {
    return
  }
  loadCalendar(year, month, {
    selectDate: preferredDateInMonth(year, month),
    focus: options.focus
  })
}

/** 把选中日对应的格子真正聚焦，键盘操作后焦点才不会丢在旧格子上。 */
function focusSelectedCell() {
  const grid = calendarGridRef.value
  if (!grid || !selectedDate.value) {
    return
  }
  const cell = grid.querySelector(`[data-date="${selectedDate.value}"]`)
  if (cell) {
    cell.focus()
  }
}

/** 选中某天；点到邻月的灰格时顺带翻到那个月。 */
function selectDay(day) {
  if (!day) {
    return
  }
  selectedDate.value = day.date
  hoverDate.value = ''
  if (!day.inMonth) {
    loadCalendar(Number(day.date.slice(0, 4)), Number(day.date.slice(5, 7)), { selectDate: day.date })
  }
}

/** 回到业务当月并选中今天；已经在当月时只是把选中移回今天。 */
function goToToday() {
  const iso = todayIso.value
  if (!iso) {
    return
  }
  const year = Number(iso.slice(0, 4))
  const month = Number(iso.slice(5, 7))
  monthPickerOpen.value = false
  if (calendarYear.value === year && calendarMonth.value === month) {
    selectedDate.value = iso
    nextTick(focusSelectedCell)
    return
  }
  loadCalendar(year, month, { selectDate: iso, focus: true })
}

/** 键盘导航：目标日期不在当前网格里（跨月）就先取回那个月再聚焦。 */
function moveSelection(iso) {
  if (!iso) {
    return
  }
  const year = Number(iso.slice(0, 4))
  if (year < CALENDAR_MIN_YEAR || year > CALENDAR_MAX_YEAR) {
    return
  }
  if (calendarDays.value.some((day) => day.date === iso)) {
    selectedDate.value = iso
    nextTick(focusSelectedCell)
    return
  }
  loadCalendar(year, Number(iso.slice(5, 7)), { selectDate: iso, focus: true })
}

function handleGridKeydown(event) {
  const day = selectedDay.value
  if (!day || event.altKey || event.ctrlKey || event.metaKey) {
    return
  }
  if (event.key === 'PageUp' || event.key === 'PageDown') {
    event.preventDefault()
    shiftMonth(event.key === 'PageUp' ? -1 : 1, { focus: true })
    return
  }
  let target = ''
  if (event.key === 'ArrowLeft') {
    target = addDays(day.date, -1)
  } else if (event.key === 'ArrowRight') {
    target = addDays(day.date, 1)
  } else if (event.key === 'ArrowUp') {
    target = addDays(day.date, -7)
  } else if (event.key === 'ArrowDown') {
    target = addDays(day.date, 7)
  } else if (event.key === 'Home') {
    target = addDays(day.date, -parseIsoDate(day.date).getDay())
  } else if (event.key === 'End') {
    target = addDays(day.date, 6 - parseIsoDate(day.date).getDay())
  } else {
    return
  }
  event.preventDefault()
  moveSelection(target)
}

function toggleMonthPicker() {
  monthPickerOpen.value = !monthPickerOpen.value
  if (monthPickerOpen.value) {
    pickerYear.value = calendarYear.value || Number(todayIso.value.slice(0, 4))
    hoverDate.value = ''
  }
}

function shiftPickerYear(step) {
  const next = pickerYear.value + step
  if (next < CALENDAR_MIN_YEAR || next > CALENDAR_MAX_YEAR) {
    return
  }
  pickerYear.value = next
}

function pickMonth(month) {
  const year = pickerYear.value
  loadCalendar(year, month, { selectDate: preferredDateInMonth(year, month) })
}

function pickCurrentYear() {
  pickerYear.value = Number(todayIso.value.slice(0, 4))
}

/** 悬停气泡：跟随指针所在格子，第一行改成向下展开，避免顶出画布。 */
function showHover(day, event) {
  if (!hoverCapable || monthPickerOpen.value) {
    return
  }
  const main = calendarMainRef.value
  const cell = event.currentTarget
  if (!main || !cell) {
    return
  }
  const mainRect = main.getBoundingClientRect()
  const cellRect = cell.getBoundingClientRect()
  const index = calendarDays.value.indexOf(day)
  const below = index >= 0 && index < 7
  hoverPosition.value = {
    left: cellRect.left - mainRect.left + cellRect.width / 2,
    top: (below ? cellRect.bottom : cellRect.top) - mainRect.top,
    below
  }
  hoverDate.value = day.date
}

function hideHover() {
  hoverDate.value = ''
}

watch(isHome, (value) => {
  if (value) {
    loadCalendar(null, null)
  } else {
    monthPickerOpen.value = false
    hoverDate.value = ''
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
  // 视口变化后格子的位置就变了，气泡按旧坐标会飘走，直接收起
  window.addEventListener('resize', hideHover)
  todayTimer = window.setInterval(refreshTodayIfNeeded, 60000)
})

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', handleDocumentPointerDown)
  document.removeEventListener('keydown', handleDocumentKeydown)
  window.removeEventListener('resize', hideHover)
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
            <button
              class="calendar-nav"
              type="button"
              aria-label="上个月"
              :disabled="!canShiftMonth(-1)"
              @click="shiftMonth(-1)"
            >
              ‹
            </button>

            <div ref="pickerRef" class="calendar-title-wrap">
              <button
                class="calendar-title"
                type="button"
                aria-haspopup="dialog"
                aria-controls="calendar-month-picker"
                :aria-expanded="monthPickerOpen"
                @click="toggleMonthPicker"
              >
                <span class="calendar-month">{{ calendarLabel || '日历' }}</span>
                <span class="calendar-caret" aria-hidden="true">▾</span>
              </button>
              <p class="calendar-sub">
                {{ calendarHolidayAvailable ? '农历 · 节气 · 节假日' : '该年放假安排尚未发布' }}
              </p>

              <transition name="picker">
                <div
                  v-if="monthPickerOpen"
                  id="calendar-month-picker"
                  class="month-picker"
                  role="dialog"
                  aria-label="选择年月"
                >
                  <div class="month-picker-year">
                    <button
                      class="month-picker-step"
                      type="button"
                      aria-label="上一年"
                      :disabled="pickerYear <= CALENDAR_MIN_YEAR"
                      @click="shiftPickerYear(-1)"
                    >
                      ‹
                    </button>
                    <strong>{{ pickerYear }} 年</strong>
                    <button
                      class="month-picker-step"
                      type="button"
                      aria-label="下一年"
                      :disabled="pickerYear >= CALENDAR_MAX_YEAR"
                      @click="shiftPickerYear(1)"
                    >
                      ›
                    </button>
                  </div>

                  <div class="month-picker-grid">
                    <button
                      v-for="(name, index) in MONTH_NAMES"
                      :key="name"
                      type="button"
                      :class="{
                        active: pickerYear === calendarYear && index + 1 === calendarMonth,
                        today: pickerYear === Number(todayIso.slice(0, 4))
                          && index + 1 === Number(todayIso.slice(5, 7))
                      }"
                      @click="pickMonth(index + 1)"
                    >
                      {{ name }}
                    </button>
                  </div>

                  <button class="month-picker-foot" type="button" @click="pickCurrentYear">
                    回到 {{ Number(todayIso.slice(0, 4)) }} 年
                  </button>
                </div>
              </transition>
            </div>

            <button
              class="calendar-nav"
              type="button"
              aria-label="下个月"
              :disabled="!canShiftMonth(1)"
              @click="shiftMonth(1)"
            >
              ›
            </button>
          </header>

          <div class="calendar-toolbar">
            <button
              class="today-button"
              type="button"
              :disabled="isTodaySelected"
              @click="goToToday"
            >
              回到今天
            </button>
            <p class="calendar-hint">点击日期查看详情 · 方向键切换</p>
          </div>

          <div class="calendar-body">
            <div ref="calendarMainRef" class="calendar-main">
              <div class="calendar-weekdays" aria-hidden="true">
                <span v-for="weekday in calendarWeekdays" :key="weekday">{{ weekday }}</span>
              </div>

              <!-- 日期格子用 button，天然支持 Tab / Enter / 空格；
                   方向键由 handleGridKeydown 接管，tabindex 只在选中格上。
                   注意：气泡不能放进 grid 里，否则会占掉一个格子。 -->
              <div
                ref="calendarGridRef"
                class="calendar-grid"
                role="grid"
                aria-label="日期"
                @keydown="handleGridKeydown"
              >
                <button
                  v-for="day in calendarDays"
                  :key="day.date"
                  class="calendar-cell"
                  type="button"
                  role="gridcell"
                  :data-date="day.date"
                  :tabindex="day.date === selectedDate ? 0 : -1"
                  :aria-selected="day.date === selectedDate"
                  :aria-label="cellLabel(day)"
                  :class="{
                    'is-out': !day.inMonth,
                    'is-today': day.today,
                    'is-selected': day.date === selectedDate,
                    'is-rest': day.holidayType === 1,
                    'is-work': day.holidayType === 2
                  }"
                  @click="selectDay(day)"
                  @pointerenter="showHover(day, $event)"
                  @pointerleave="hideHover"
                >
                  <span class="calendar-day">{{ day.day }}</span>
                  <span class="calendar-label">{{ day.label }}</span>
                  <span v-if="day.holidayType" class="calendar-badge">
                    {{ day.holidayType === 1 ? '休' : '班' }}
                  </span>
                </button>
              </div>

              <div
                v-if="hoverDay"
                class="calendar-tooltip"
                :class="{ 'is-below': hoverPosition.below }"
                :style="{ left: `${hoverPosition.left}px`, top: `${hoverPosition.top}px` }"
                role="tooltip"
              >
                <p class="tooltip-date">{{ formatIsoDate(hoverDay.date) }}</p>
                <p class="tooltip-meta">{{ hoverMeta(hoverDay) }}</p>
                <span
                  v-if="hoverDay.holidayType"
                  class="tooltip-tag"
                  :class="{ 'is-work': hoverDay.holidayType === 2 }"
                >
                  {{ hoverDay.holidayType === 1 ? '休' : '班' }}
                  {{ hoverDay.holidayName || '调休' }}
                </span>
              </div>
            </div>

            <aside class="calendar-detail" aria-live="polite" aria-label="选中日期详情">
              <p class="detail-eyebrow">Selected / 选中</p>

              <template v-if="selectedDay">
                <p class="detail-day">{{ selectedDay.day }}</p>
                <p class="detail-month">
                  {{ selectedDay.date.slice(0, 4) }} 年
                  {{ Number(selectedDay.date.slice(5, 7)) }} 月
                  · {{ selectedWeekday }}
                </p>
                <p class="detail-relative" :class="{ 'is-today': selectedDay.today }">
                  {{ selectedRelative || '—' }}
                </p>

                <dl class="detail-list">
                  <div v-for="row in selectedRows" :key="row.key">
                    <dt>{{ row.label }}</dt>
                    <dd>{{ row.value }}</dd>
                  </div>
                </dl>
              </template>

              <p v-else class="detail-empty">点击日历中的日期查看详情。</p>
            </aside>
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
