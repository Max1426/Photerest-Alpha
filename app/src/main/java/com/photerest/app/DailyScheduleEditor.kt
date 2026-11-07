package com.photerest.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

internal val weekLabels = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
internal fun quietLabel(range: QuietTime): String = DailyRules.timeLabel(range.start) + "—" +
    (if (range.end < range.start) "次日" else "") + DailyRules.timeLabel(range.end)

/** A local draft is committed once; intermediate picker changes never reschedule alarms. */
@Composable
fun DailyScheduleEditor(initial: DailyConfig, saving: Boolean, dismiss: () -> Unit, save: (DailyConfig) -> Unit) {
    var draft by remember { mutableStateOf(initial) }
    var minutes by remember { mutableStateOf(initial.intervalMinutes.toString()) }
    var expanded by remember { mutableIntStateOf(-1) }
    var fixedEdit by remember { mutableStateOf<Int?>(null) }
    var showFixed by remember { mutableStateOf(false) }
    var quietDay by remember { mutableStateOf<Int?>(null) }
    var quietIndex by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val feedback = LocalTapFeedback.current
    fun changeDay(index: Int, change: (DailyDay) -> DailyDay) {
        draft = draft.copy(week = draft.week.mapIndexed { at, day -> if (at == index) change(day) else day })
    }
    OperationScreen("推荐安排", "取消编辑", dismiss, canDismiss = !saving, bottomBar = {
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("schedule-error")) }
                TapButton(onClick = {
                    val value = minutes.toIntOrNull()
                    if (draft.mode == DailyMode.INTERVAL && (value == null || value < 15)) error = "请输入至少15分钟的整数"
                    else save(PhotoSchedule.normalize(draft.copy(intervalMinutes = value?.coerceAtLeast(15) ?: initial.intervalMinutes)))
                }, enabled = !saving, modifier = Modifier.fillMaxWidth().testTag("schedule-save")) {
                    Text(if (saving) "正在保存…" else "保存安排")
                }
            }
        }
    }) {
        OperationPageBody {
            OperationGroup("推荐模式") {
                Column(Modifier.selectableGroup()) {
                    DailyMode.entries.forEach { mode ->
                        OperationItem(
                            mode.label,
                            Modifier.testTag("schedule-mode-${mode.name}").selectable(draft.mode == mode, role = Role.RadioButton) {
                                feedback.perform()
                                draft = draft.copy(mode = mode)
                            },
                            trailing = { RadioButton(draft.mode == mode, onClick = null) }
                        )
                    }
                }
                if (draft.mode == DailyMode.INTERVAL) {
                    OutlinedTextField(
                        minutes, onValueChange = { minutes = it; error = null },
                        label = { Text("间隔分钟，至少15") },
                        supportingText = { Text("首次开启或修改间隔后等待一个完整间隔。静默或停用结束时立即推荐一张，再重新计时。") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("schedule-minutes")
                    )
                } else {
                    draft.times.forEach { minute ->
                        OperationItem(
                            DailyRules.timeLabel(minute),
                            Modifier.tapClickable { fixedEdit = minute; showFixed = true },
                            trailing = {
                                TapTextButton(
                                    onClick = { draft = draft.copy(times = draft.times - minute) },
                                    enabled = draft.times.size > 1,
                                    modifier = Modifier.testTag("schedule-delete-$minute")
                                ) { Text("删除") }
                            }
                        )
                    }
                    TapTextButton(onClick = { fixedEdit = null; showFixed = true }, modifier = Modifier.padding(start = 8.dp)) { Text("添加时间") }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OperationHeading("每周安排")
                Text(
                    "星期和静默限制用于两种模式。跨午夜的静默时段归开始日管理，持续到次日结束时间。",
                    style = MaterialTheme.typography.bodyMedium, color = Muted
                )
                draft.week.forEachIndexed { index, day ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        OperationItem(
                            weekLabels[index],
                            Modifier.testTag("schedule-day-$index").toggleable(day.enabled, role = Role.Switch) { enabled ->
                                feedback.perform()
                                changeDay(index) { it.copy(enabled = enabled) }
                            },
                            summary = if (!day.enabled) "整天停用" else if (day.quiet.isEmpty()) "未设置静默时段"
                                else "静默：" + day.quiet.joinToString("、", transform = ::quietLabel),
                            trailing = { Switch(day.enabled, onCheckedChange = null) }
                        )
                        TapTextButton(
                            onClick = { expanded = if (expanded == index) -1 else index },
                            modifier = Modifier.padding(start = 8.dp).testTag("schedule-expand-$index")
                        ) { Text(if (expanded == index) "收起时段" else "管理时段") }
                        if (expanded == index) {
                            if (!day.enabled) OperationItem("当天保存的时段会在重新启用后生效。")
                            day.quiet.forEachIndexed { rangeIndex, range ->
                                OperationItem(
                                    quietLabel(range),
                                    Modifier.tapClickable(enabled = day.enabled) { quietDay = index; quietIndex = rangeIndex },
                                    trailing = {
                                        TapTextButton(onClick = { changeDay(index) { it.copy(quiet = it.quiet.filterIndexed { at, _ -> at != rangeIndex }) } }, enabled = day.enabled) {
                                            Text("删除")
                                        }
                                    }
                                )
                            }
                            TapTextButton(
                                onClick = { quietDay = index; quietIndex = null }, enabled = day.enabled,
                                modifier = Modifier.padding(start = 8.dp).testTag("schedule-add-quiet-$index")
                            ) { Text("添加静默时段") }
                        }
                    }
                }
                val enabledDays = draft.week.indices.filter { draft.week[it].enabled }.joinToString("、") { weekLabels[it] }
                Text(if (enabledDays.isEmpty()) "当前安排将暂停推荐" else "启用日期：$enabledDays", color = Muted, style = MaterialTheme.typography.bodyMedium)
                Text(if (draft.mode == DailyMode.INTERVAL) "每隔 ${minutes.ifBlank { "…" }} 分钟推荐一张" else "每天设置 ${draft.times.size} 个时点，静默时跳过")
            }
        }
    }
    if (showFixed) ScheduleTimeDialog("推荐时间", fixedEdit ?: 1200, { showFixed = false }) { value ->
        if (value in draft.times && value != fixedEdit) error = "这个推荐时间已存在"
        else draft = draft.copy(times = ((draft.times - listOfNotNull(fixedEdit).toSet()) + value).sorted())
        showFixed = false
    }
    quietDay?.let { index ->
        val previous = quietIndex?.let { draft.week[index].quiet[it] }
        QuietRangeDialog(previous, dismiss = { quietDay = null }) { range ->
            changeDay(index) { day ->
                val ranges = day.quiet.filterIndexed { at, _ -> at != quietIndex } + range
                day.copy(quiet = ranges.distinct().sortedBy { it.start })
            }
            quietDay = null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleTimeDialog(title: String, initial: Int, dismiss: () -> Unit, save: (Int) -> Unit) {
    val time = rememberTimePickerState(initialHour = initial / 60, initialMinute = initial % 60, is24Hour = true)
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { TimeInput(time) },
        confirmButton = { TapTextButton(onClick = { save(time.hour * 60 + time.minute) }) { Text("确定") } },
        dismissButton = { TapTextButton(onClick = dismiss) { Text("取消") } })
}

@Composable
private fun QuietRangeDialog(previous: QuietTime?, dismiss: () -> Unit, save: (QuietTime) -> Unit) {
    var start by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    key(start) {
        ScheduleTimeDialog(if (start == null) "静默开始时间" else "静默结束时间", if (start == null) previous?.start ?: 1380 else previous?.end ?: 480, dismiss) { minute ->
            if (start == null) start = minute
            else if (minute == start) error = "开始和结束时间应不同，请重新选择结束时间"
            else save(QuietTime(start!!, minute))
        }
    }
    if (error != null) AlertDialog(onDismissRequest = { error = null }, text = { Text(error!!) }, confirmButton = {
        TapTextButton(onClick = { error = null }) { Text("重新选择") }
    })
}
