package com.pocketeverything.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SyntaxGuideSheet(
    onInsertSnippet: (String) -> Unit,
    onApplyExample: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val formalRules = listOf(
        "逻辑组合" to "空格表示与 (AND)；| 或 OR 表示或；前置 - 或 ! 表示排除；支持 (...) 分组",
        "精确与通配" to "\"短语\" 精确匹配含空格/标点文本；* 匹配任意长度字符，? 匹配单个字符（文件名严格全串锚定）",
        "严格正则" to "regex:模式、re:模式 或 /模式/（默认不区分大小写，cregex: 或 /模式/c 区分大小写）",
        "字段限定" to "name: 仅匹配文件名；path: 仅匹配路径；content: 或 body: 仅匹配文档正文",
        "属性过滤" to "ext:扩展名列表（逗号分隔）；type:file 或 type:folder；size:>1m / 100k..10m；dm:today / 7d / 日期区间"
    )

    val quickKeys = listOf(
        "ext:", "name:", "path:", "content:", "regex:", "size:>", "dm:", "-", "|", "*"
    )

    val quickExamples = listOf(
        "ext:pdf",
        "type:folder",
        "type:file",
        "dm:today",
        "size:>1m",
        "name:*.txt",
        "regex:^\\d{4}.*\\.pdf$",
        "content:关键词"
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 6.dp)
        ) {
            Text("搜索语法规范", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(10.dp))

            // 1. Formal complete syntax specification
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    formalRules.forEach { (title, desc) ->
                        Column {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = desc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(12.dp))

            // 2. Interactive shortcut keys and sample query buttons
            Text("常用前缀按键（点按追加）", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                quickKeys.forEach { key ->
                    Surface(
                        onClick = {
                            onInsertSnippet(key)
                            onDismiss()
                        },
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Text(
                            text = key,
                            style = MaterialTheme.typography.labelLarge,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Text("常用组合（点按替换试搜）", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                quickExamples.forEach { sample ->
                    Surface(
                        onClick = {
                            onApplyExample(sample)
                            onDismiss()
                        },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = sample,
                            style = MaterialTheme.typography.labelLarge,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(22.dp))
        }
    }
}
