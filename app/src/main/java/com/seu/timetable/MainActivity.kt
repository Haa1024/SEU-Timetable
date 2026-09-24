package com.seu.timetable

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.seu.timetable.ui.AppRoot
import com.seu.timetable.widget.TodayWidget

/**
 * 唯一的 Activity，承载整个 Compose 应用。
 * 主题、导航与数据加载均在 [AppRoot] 中编排，本类仅负责窗口级设置。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 全面屏：内容延伸至状态栏/导航栏之下，安全区由各页面的 WindowInsets 处理。
        enableEdgeToEdge()
        setContent {
            AppRoot()
        }
    }

    /**
     * 退出到桌面时刷新小组件。
     *
     * 选在这里而不是各个增删改课程的地方，是取一个"必然经过、又只有一处"的收口点：
     * 用户改完课表必然要退回桌面看，此刻刷一次即可，无需在十余个写操作里逐处埋点。
     */
    override fun onStop() {
        super.onStop()
        TodayWidget.refresh(this)
    }
}
