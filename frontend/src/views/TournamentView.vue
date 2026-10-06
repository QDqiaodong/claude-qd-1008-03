<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import api from '../api'

const tournaments = ref([])
const players = ref([])
const holes = ref([])

const leaderboard = ref(null)
const scores = ref([])
const error = ref('')
const tip = ref('')
const loading = ref(false)
const generating = ref(false)

const selectedTourId = ref(null)
const activeTab = ref('tournament')

// ---- 赛事 ----
const tourDialog = ref(false)
const savingTour = ref(false)
const tourForm = ref(blankTour())
function blankTour() {
  return { tournamentNo: '', name: '', startDate: '2026-10-10', endDate: '2026-10-12', status: '报名中', location: '', courseRating: null, slopeRating: null, note: '' }
}

// ---- 赛事编辑（含球场难度参数补齐） ----
const editDialog = ref(false)
const savingEdit = ref(false)
const editForm = ref({})
function openEdit(t) {
  editForm.value = {
    id: t.id, name: t.name, startDate: t.startDate, endDate: t.endDate,
    location: t.location, courseRating: t.courseRating, slopeRating: t.slopeRating, note: t.note
  }
  editDialog.value = true; error.value = ''; tip.value = ''
}
async function saveEdit() {
  savingEdit.value = true; error.value = ''
  try {
    const d = {
      ...editForm.value,
      courseRating: editForm.value.courseRating === '' || editForm.value.courseRating == null ? null : Number(editForm.value.courseRating),
      slopeRating: editForm.value.slopeRating === '' || editForm.value.slopeRating == null ? null : Number(editForm.value.slopeRating)
    }
    await api.updateTournament(d.id, d)
    tip.value = `赛事「${d.name}」已更新${d.courseRating != null ? '，球场难度参数已补齐，可到差点中心定向重算受影响球员' : ''}`
    editDialog.value = false
    await loadTournaments()
  } catch (e) { error.value = e.message } finally { savingEdit.value = false }
}

// ---- 成绩 ----
const scoreDialog = ref(false)
const savingScore = ref(false)
const scoreForm = ref(blankScore())
function blankScore() {
  return { tournamentId: null, playerId: '', holeId: '', roundNo: 1, strokes: null }
}

const canStart = (t) => t && t.status === '报名中'
const canEnd = (t) => t && t.status === '进行中'

const selectedTournament = computed(() => tournaments.value.find(t => t.id === selectedTourId.value))
const isEnded = computed(() => selectedTournament.value?.status === '已结束')
// 历史已结束赛事还没有正式快照时，允许补生成（与收口共用同一份校验）
const canGenerateSnapshot = computed(() =>
  isEnded.value && leaderboard.value && leaderboard.value.type === '临时')

async function loadTournaments() {
  tournaments.value = (await api.listTournaments()) || []
  if (!selectedTourId.value && tournaments.value.length) {
    selectedTourId.value = tournaments.value[0].id
  }
}
async function loadLeaderboard() {
  if (!selectedTourId.value) { leaderboard.value = null; return }
  leaderboard.value = await api.leaderboardView(selectedTourId.value)
}
async function loadScores() {
  if (!selectedTourId.value) { scores.value = []; return }
  scores.value = (await api.listScores({ tournamentId: selectedTourId.value })) || []
}

async function loadAll() {
  await Promise.all([loadTournaments(), loadPlayers(), loadHoles()])
  await Promise.all([loadLeaderboard(), loadScores()])
}
async function loadPlayers() { players.value = (await api.listPlayers()) || [] }
async function loadHoles() { holes.value = (await api.listHoles()) || [] }

function openTour() { tourForm.value = blankTour(); tourDialog.value = true; error.value = ''; tip.value = '' }
async function saveTour() {
  savingTour.value = true; error.value = ''
  try {
    const d = {
      ...tourForm.value,
      courseRating: tourForm.value.courseRating === '' || tourForm.value.courseRating == null ? null : Number(tourForm.value.courseRating),
      slopeRating: tourForm.value.slopeRating === '' || tourForm.value.slopeRating == null ? null : Number(tourForm.value.slopeRating)
    }
    await api.createTournament(d)
    tip.value = `赛事「${tourForm.value.name}」已创建`
    tourDialog.value = false; await loadTournaments()
  } catch (e) { error.value = e.message } finally { savingTour.value = false }
}

// 状态推进携带页面看到的版本号：多人同时推进同一赛事时，过期操作会收到 409「数据已变化」
async function advance(t, target) {
  error.value = ''; tip.value = ''
  try {
    await api.advanceTournament(t.id, target, t.version)
    tip.value = target === '已结束'
      ? '赛事已推进至「已结束」，正式排行榜快照已生成并冻结'
      : `赛事已推进至「${target}」`
  } catch (e) {
    error.value = e.isConflict ? `数据已变化：${e.message}` : e.message
  }
  // 无论成败都重新读取当前状态：成功要刷新榜单与成绩；
  // 收口校验失败时，失败原因已落库，重新读取才能展示在排行榜页
  await loadTournaments()
  await Promise.all([loadLeaderboard(), loadScores()])
}

// 历史已结束赛事补生成正式快照
async function generateSnapshot() {
  generating.value = true; error.value = ''; tip.value = ''
  try {
    leaderboard.value = await api.generateLeaderboardSnapshot(selectedTourId.value)
    tip.value = '正式排行榜快照已生成并冻结，之后成绩补录/修改/删除不再影响名次'
  } catch (e) {
    error.value = e.isConflict ? `数据已变化：${e.message}` : e.message
    await loadLeaderboard().catch(() => {})
  } finally { generating.value = false }
}

function openScore() {
  if (isEnded.value) {
    error.value = '赛事已结束，正式排行榜快照已生成，成绩不能再补录或修改'
    return
  }
  scoreForm.value = blankScore(); scoreForm.value.tournamentId = selectedTourId.value
  scoreDialog.value = true; error.value = ''; tip.value = ''
}
async function saveScore() {
  savingScore.value = true; error.value = ''
  try {
    await api.recordScore({
      player: { id: Number(scoreForm.value.playerId) },
      tournament: { id: Number(scoreForm.value.tournamentId) },
      hole: { id: Number(scoreForm.value.holeId) },
      roundNo: Number(scoreForm.value.roundNo),
      strokes: Number(scoreForm.value.strokes)
    })
    tip.value = '成绩已录入（临时排名已同步刷新；赛事收口后将以正式快照为准）'
    scoreDialog.value = false
    // 成绩变化会改变临时排名：同步刷新成绩列表与排行榜
    await Promise.all([loadScores(), loadLeaderboard()])
  } catch (e) { error.value = e.message } finally { savingScore.value = false }
}

const toParText = (n) => n == null ? '-' : (n === 0 ? 'E' : (n > 0 ? `+${n}` : `${n}`))

watch(selectedTourId, async () => { await Promise.all([loadLeaderboard(), loadScores()]) })

onMounted(loadAll)
</script>

<template>
  <div>
    <div v-if="error" class="err">{{ error }}</div>
    <div v-if="tip" class="ok-tip">{{ tip }}</div>

    <el-tabs v-model="activeTab">
      <!-- ============ 赛事 ============ -->
      <el-tab-pane label="赛事" name="tournament">
        <div class="panel">
          <div class="panel-head">
            <span class="panel-title">赛事 · 报名与推进</span>
            <span class="panel-desc">状态只能逐级推进：报名中 → 进行中 → 已结束；推进到已结束时生成唯一正式排行榜快照</span>
          </div>
          <div class="toolbar">
            <el-select v-model="selectedTourId" placeholder="选择赛事" style="width: 220px">
              <el-option v-for="t in tournaments" :key="t.id" :label="`${t.name}（${t.status}）`" :value="t.id" />
            </el-select>
            <span class="spacer"></span>
            <el-button type="primary" @click="openTour">新建赛事</el-button>
          </div>
          <el-table :data="tournaments" v-loading="loading" border stripe>
            <el-table-column prop="tournamentNo" label="编号" width="130" />
            <el-table-column prop="name" label="名称" min-width="180" />
            <el-table-column prop="startDate" label="开始" width="120" />
            <el-table-column prop="endDate" label="结束" width="120" />
            <el-table-column prop="status" label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="row.status === '报名中' ? 'info' : row.status === '进行中' ? 'warning' : 'success'">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="location" label="地点" width="120" />
            <el-table-column label="难度参数" width="130">
              <template #default="{ row }">
                <span v-if="row.courseRating != null">{{ row.courseRating }} / {{ row.slopeRating }}</span>
                <el-tag v-else type="danger" size="small">未维护</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="260" fixed="right">
              <template #default="{ row }">
                <el-button size="small" type="primary" plain @click="openEdit(row)">编辑</el-button>
                <el-button size="small" type="warning" plain :disabled="!canStart(row)" @click="advance(row, '进行中')">开始</el-button>
                <el-button size="small" type="success" plain :disabled="!canEnd(row)" @click="advance(row, '已结束')">结束</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-tab-pane>

      <!-- ============ 排行榜（临时排名 / 正式快照） ============ -->
      <el-tab-pane label="排行榜" name="leaderboard">
        <div class="panel">
          <div class="panel-head">
            <span class="panel-title">赛事排行榜 · 快照一致性</span>
            <span class="panel-desc">
              报名中/进行中只展示当前已录入成绩的临时排名；赛事结束时基于收口前确认的全部参赛成绩生成唯一正式快照并冻结
            </span>
          </div>
          <div class="toolbar">
            <el-select v-model="selectedTourId" placeholder="选择赛事" style="width: 220px">
              <el-option v-for="t in tournaments" :key="t.id" :label="`${t.name}（${t.status}）`" :value="t.id" />
            </el-select>
            <template v-if="leaderboard">
              <el-tag v-if="leaderboard.type === '正式'" type="success">正式快照 · v{{ leaderboard.version }}</el-tag>
              <el-tag v-else type="warning">临时结果</el-tag>
            </template>
            <span class="spacer"></span>
            <el-button size="small" @click="loadLeaderboard">刷新</el-button>
            <el-button v-if="canGenerateSnapshot" type="success" :loading="generating" @click="generateSnapshot">
              生成正式快照
            </el-button>
          </div>

          <el-alert v-if="leaderboard && leaderboard.type === '临时'" type="warning" :closable="false" show-icon
            title="临时排名：赛事尚未收口，以下为当前已录入成绩的实时汇总，可能随成绩录入而变化，不能作为正式成绩"
            style="margin-bottom: 12px" />
          <el-alert v-else-if="leaderboard && leaderboard.type === '正式'" type="success" :closable="false" show-icon
            :title="`正式快照：生成于 ${leaderboard.generatedAt || '-'}，共纳入 ${leaderboard.scoreRows} 条成绩；名次已冻结，之后的成绩补录/修改/删除不再影响本榜`"
            style="margin-bottom: 12px" />
          <el-alert v-if="leaderboard && leaderboard.failReason" type="error" :closable="false" show-icon
            title="上次正式快照生成失败（临时排名不受影响，补齐数据后可重试）" :description="leaderboard.failReason"
            style="margin-bottom: 12px" />

          <el-table :data="leaderboard ? leaderboard.rows : []" border stripe
            empty-text="该赛事暂无成绩记录">
            <el-table-column label="名次" width="80">
              <template #default="{ row }">
                <el-tag :type="row.rank === 1 ? 'success' : 'info'" effect="plain">{{ row.rank }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="球员" min-width="130">
              <template #default="{ row }">{{ row.playerName || `未知球员 #${row.playerId}` }}</template>
            </el-table-column>
            <el-table-column prop="roundNo" label="轮次" width="80" />
            <el-table-column prop="holes" label="已录洞数" width="90" />
            <el-table-column prop="totalStrokes" label="总杆" width="90" />
            <el-table-column prop="totalPar" label="标准杆" width="90" />
            <el-table-column label="相对标准杆" width="120">
              <template #default="{ row }">
                <el-tag :type="row.toPar > 0 ? 'danger' : row.toPar < 0 ? 'success' : 'info'">
                  {{ toParText(row.toPar) }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-tab-pane>

      <!-- ============ 成绩 ============ -->
      <el-tab-pane label="成绩" name="score">
        <div class="panel">
          <div class="panel-head">
            <span class="panel-title">成绩 · 按洞录入</span>
            <span class="panel-desc">
              {{ isEnded ? '赛事已结束，正式快照已冻结，成绩不能再补录或修改' : '单洞杆数须 ≥ 1，toPar = 杆数 − 标准杆（负为好）；录入后临时排名即时刷新' }}
            </span>
          </div>
          <div class="toolbar">
            <el-select v-model="selectedTourId" placeholder="选择赛事" style="width: 220px">
              <el-option v-for="t in tournaments" :key="t.id" :label="t.name" :value="t.id" />
            </el-select>
            <span class="spacer"></span>
            <el-button type="primary" :disabled="isEnded" @click="openScore">录入成绩</el-button>
          </div>
          <el-table :data="scores" border stripe>
            <el-table-column prop="holeNo" label="洞号" width="90" />
            <el-table-column prop="par" label="标准杆" width="90" />
            <el-table-column prop="playerName" label="球员" min-width="120" />
            <el-table-column prop="roundNo" label="轮次" width="90" />
            <el-table-column prop="strokes" label="杆数" width="90" />
            <el-table-column label="相对标准杆" width="120">
              <template #default="{ row }">
                <el-tag :type="row.toPar > 0 ? 'danger' : row.toPar < 0 ? 'success' : 'info'">{{ toParText(row.toPar) }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 新建赛事 -->
    <el-dialog v-model="tourDialog" title="新建赛事" width="560px">
      <el-form :model="tourForm" label-width="110px" class="dialog-form">
        <el-form-item label="赛事编号"><el-input v-model="tourForm.tournamentNo" placeholder="如 T-2026-003" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="tourForm.name" /></el-form-item>
        <el-form-item label="开始日期"><el-date-picker v-model="tourForm.startDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" /></el-form-item>
        <el-form-item label="结束日期"><el-date-picker v-model="tourForm.endDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" /></el-form-item>
        <el-form-item label="地点"><el-input v-model="tourForm.location" /></el-form-item>
        <el-form-item label="球场难度值"><el-input v-model="tourForm.courseRating" type="number" step="0.1" placeholder="courseRating，可后补" /></el-form-item>
        <el-form-item label="坡度难度"><el-input v-model="tourForm.slopeRating" type="number" placeholder="slopeRating 55~155，可后补" /></el-form-item>
        <el-form-item label="备注" class="full"><el-input v-model="tourForm.note" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="tourDialog = false">取消</el-button>
        <el-button type="primary" :loading="savingTour" @click="saveTour">创建</el-button>
      </template>
    </el-dialog>

    <!-- 编辑赛事（含球场难度参数补齐） -->
    <el-dialog v-model="editDialog" title="编辑赛事" width="560px">
      <el-form :model="editForm" label-width="110px" class="dialog-form">
        <el-form-item label="名称"><el-input v-model="editForm.name" /></el-form-item>
        <el-form-item label="开始日期"><el-date-picker v-model="editForm.startDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" /></el-form-item>
        <el-form-item label="结束日期"><el-date-picker v-model="editForm.endDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" /></el-form-item>
        <el-form-item label="地点"><el-input v-model="editForm.location" /></el-form-item>
        <el-form-item label="球场难度值">
          <el-input v-model="editForm.courseRating" type="number" step="0.1" placeholder="courseRating 20.0~80.0" />
        </el-form-item>
        <el-form-item label="坡度难度">
          <el-input v-model="editForm.slopeRating" type="number" placeholder="slopeRating 55~155" />
        </el-form-item>
        <el-form-item label="备注" class="full"><el-input v-model="editForm.note" /></el-form-item>
      </el-form>
      <div class="muted" style="margin: -6px 0 8px 110px; font-size: 12px">
        难度参数需成对维护（都填或都留空）；补齐后请到「差点中心 → 缺参与定向重算」重算受影响球员。
      </div>
      <template #footer>
        <el-button @click="editDialog = false">取消</el-button>
        <el-button type="primary" :loading="savingEdit" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 录入成绩 -->
    <el-dialog v-model="scoreDialog" title="录入成绩" width="560px">
      <el-form :model="scoreForm" label-width="110px" class="dialog-form">
        <el-form-item label="球员" class="full">
          <el-select v-model="scoreForm.playerId" filterable style="width: 100%">
            <el-option v-for="p in players" :key="p.id" :label="`${p.name}（${p.playerNo}）`" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="球洞">
          <el-select v-model="scoreForm.holeId" style="width: 100%">
            <el-option v-for="h in holes" :key="h.id" :label="`第 ${h.holeNo} 洞 · par ${h.par}`" :value="h.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="轮次"><el-input v-model="scoreForm.roundNo" type="number" min="1" /></el-form-item>
        <el-form-item label="杆数" class="full"><el-input v-model="scoreForm.strokes" type="number" min="1" placeholder="须 ≥ 1" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="scoreDialog = false">取消</el-button>
        <el-button type="primary" :loading="savingScore" @click="saveScore">录入</el-button>
      </template>
    </el-dialog>
  </div>
</template>
