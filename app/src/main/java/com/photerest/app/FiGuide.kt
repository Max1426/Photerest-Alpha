package com.photerest.app

import android.animation.ValueAnimator
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.photerest.ai.ModelSpec
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import kotlin.math.*

@Composable
internal fun FiDisabledSheet(dismiss: () -> Unit, settings: () -> Unit) {
    OnlineTheme {
        FiSheet("fi-disabled-sheet",dismiss) { close ->
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FiIcon(Modifier.size(100.dp),disabled = true)
                Text("φ 智能尚未开启",style = MaterialTheme.typography.headlineSmall)
                Text("开启后，可以用文字寻找本机照片，或使用已获授权的 Google 扩展。",style = MaterialTheme.typography.bodyLarge)
            }
            TapButton(onClick = { close(settings) }, modifier = Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=8.dp).testTag("fi-go-settings")) {
                Text("前往设置并启用 φ 智能")
            }
            TapTextButton(onClick = { close {} }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("暂不开启") }
        }
    }
}

@Composable
internal fun FiGuideSheet(dismiss: () -> Unit, local: () -> Unit, google: () -> Unit) {
    var page by remember { mutableIntStateOf(0) }
    var replay by remember { mutableIntStateOf(0) }
    val foreground = rememberFiForeground()
    val progress = remember { Animatable(0f) }
    val context = LocalContext.current
    val componentReady = remember { ModelSpec.installed(context) }
    LaunchedEffect(page,replay,foreground) {
        if (!foreground) return@LaunchedEffect
        if (!ValueAnimator.areAnimatorsEnabled()) { progress.snapTo(1f); return@LaunchedEffect }
        progress.snapTo(0f)
        progress.animateTo(1f,tween(2400,easing=FastOutSlowInEasing))
    }
    val titles = listOf("准备本地 AI","用文字寻找照片","Google 联网搜图 · Beta")
    val details = listOf(
        if (!ModelSpec.supported()) "此设备不支持本地 AI，需要 Android 12+ ARM64。联网扩展依照授权状态单独判断。" else if (componentReady) "共享中心已安装。已有模型会直接复用，也可以进入中心下载或导入。" else "先准备共享模型中心，再下载或导入模型。你也可以稍后准备，φ 智能仍会保持开启。",
        "选择相册并建立索引，再输入“海边”“夜景”这样的描述。模型就绪后可以离线查找。",
        "联网扩展仍在测试，仅向已授权设备开放。每次新搜索都先确认上传和一次额度消耗。"
    )
    OnlineTheme {
        FiSheet("fi-guide-sheet",dismiss) { close ->
            Row(Modifier.fillMaxWidth().padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically) {
                Text("φ 智能使用指南",Modifier.weight(1f).testTag("fi-guide-heading"),style=MaterialTheme.typography.titleLarge)
                Text("${page+1} / 3",style=MaterialTheme.typography.labelLarge)
                TapTextButton(onClick = { close {} }) { Text("关闭") }
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=24.dp),
                verticalArrangement=Arrangement.spacedBy(12.dp)) {
                AnimatedContent(page,transitionSpec = {
                    (fadeIn(tween(180))+slideInHorizontally { it/8 }).togetherWith(fadeOut(tween(120)))
                },label="fi-guide-page") { step ->
                    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        FiGuideDemo(step,{ progress.value },Modifier.fillMaxWidth().height(120.dp))
                        Text(titles[step],style=MaterialTheme.typography.headlineSmall)
                        Text(details[step],style=MaterialTheme.typography.bodyLarge)
                    }
                }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    if (page == 0) TapTextButton(onClick = { close(local) }) { Text(if (ModelSpec.supported()) "前往准备本地 AI" else "查看本地 AI 兼容性") }
                    if (page == 2) TapTextButton(onClick = { close(google) }) { Text("查看 Google 扩展") }
                    TapTextButton(onClick = { replay++ }) { Text("重播演示") }
                }
                Text("这是功能演示，不会执行搜索或消耗额度。",style=MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                if (page > 0) TapTextButton(onClick = { page-- }) { Text("上一步") }
                Spacer(Modifier.weight(1f))
                TapButton(onClick = { if (page < 2) page++ else close {} },modifier=Modifier.testTag("fi-guide-next")) {
                    Text(if (page < 2) "下一步" else "开始使用")
                }
            }
        }
    }
}

/** Generated vector shapes and interpolated geometry; no media file, network or model invocation. */
@Composable
private fun FiGuideDemo(page: Int, progress: () -> Float, modifier: Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondaryContainer
    val background = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(modifier.background(background,RoundedCornerShape(24.dp))) {
        Canvas(Modifier.fillMaxSize()) {
            val p = progress().coerceIn(0f,1f)
            val w=size.width; val h=size.height
            when (page) {
                0 -> {
                    val left=Offset(w*.19f,h*.5f); val right=Offset(w*.77f,h*.5f)
                    drawLine(primary.copy(alpha=.2f),left,right,5.dp.toPx())
                    drawCircle(primary,7.dp.toPx(),Offset(left.x+(right.x-left.x)*p,left.y))
                    drawRoundRect(secondary,Offset(w*.65f,h*.25f),Size(w*.23f,h*.5f),CornerRadius(18.dp.toPx()))
                    drawRoundRect(primary.copy(alpha=.6f+.4f*p),Offset(w*.69f,h*.35f),Size(w*.15f,h*.3f),CornerRadius(8.dp.toPx()))
                }
                1 -> {
                    repeat(5) { i ->
                        val baseX=w*(.12f+i*.17f);val baseY=h*(.25f+(i%2)*.12f)
                        val x=if(i==2) baseX+(w*.45f-baseX)*p else baseX
                        val y=if(i==2) baseY+(h*.2f-baseY)*p else baseY
                        val alpha=if(i==2) 1f else 1f-p*.75f
                        val grow=if(i==2) 1f+p*.35f else 1f
                        drawRoundRect((if(i==2)primary else secondary).copy(alpha=alpha),Offset(x,y),Size(w*.13f*grow,h*.46f*grow),CornerRadius(10.dp.toPx()))
                    }
                    drawLine(primary,Offset(w*.22f,h*.86f),Offset(w*(.22f+.56f*p),h*.86f),4.dp.toPx())
                }
                else -> {
                    val start=Offset(w*.2f,h*.5f);val end=Offset(w*.76f,h*.5f)
                    drawLine(primary.copy(alpha=.22f),start,end,4.dp.toPx())
                    drawCircle(primary,6.dp.toPx(),Offset(start.x+(end.x-start.x)*p,start.y))
                    repeat(3) { i ->
                        val visible=((p-.4f)*1.7f).coerceIn(0f,1f)
                        drawRoundRect(primary.copy(alpha=visible*(.6f+i*.15f)),Offset(w*.7f+i*w*.045f,h*.2f+i*h*.1f),Size(w*.13f,h*.4f),CornerRadius(8.dp.toPx()))
                    }
                    drawCircle(primary,15.dp.toPx(),start,style=Stroke(3.dp.toPx()))
                }
            }
        }
        if (page==0) FiIcon(Modifier.align(Alignment.CenterStart).padding(start=16.dp).size(64.dp))
    }
}
