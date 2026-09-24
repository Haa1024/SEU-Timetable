package com.seu.timetable.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.seu.timetable.ui.theme.LocalSeuColors
import com.seu.timetable.ui.theme.LocalSeuType

/**
 * 输入框与字段标签。
 *
 * 原先只活在「校园账号」页里，登录页同样要收「学号 + 密码」，
 * 同一个东西在两处必须长得一样，故提取到这里共用。
 */

/** 字段名，位于输入框上方。 */
@Composable
fun FieldLabel(text: String) {
    Text(
        text,
        style = LocalSeuType.current.caption,
        color = LocalSeuColors.current.textSecondary,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

/**
 * 与设计语言一致的输入框：内凹底 + 1px 描边 + 13sp。
 *
 * [masked] 走 [PasswordVisualTransformation]，仅用于防肩窥——
 * 本 App 从不把已存密码回填进输入框，用户每次都要重新输入。
 */
@Composable
fun AccountField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType,
    masked: Boolean,
) {
    val c = LocalSeuColors.current
    val t = LocalSeuType.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(c.surfaceSunken)
            .border(1.dp, c.border, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 11.dp)
    ) {
        if (value.isEmpty()) {
            Text(placeholder, style = t.body, color = c.textTertiary)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(
                fontSize = t.body.fontSize,
                lineHeight = t.body.lineHeight,
                color = c.textPrimary,
            ),
            cursorBrush = SolidColor(c.primary),
            visualTransformation = if (masked) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
