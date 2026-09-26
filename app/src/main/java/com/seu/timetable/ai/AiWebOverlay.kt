package com.seu.timetable.ai

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.seu.timetable.data.LoadedBoard
import com.seu.timetable.ui.theme.LocalSeuColors
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

// Concrete type lets lint inspect the annotated bridge methods through Compose remember().
private fun attachAiBridge(web: WebView, bridge: AiWebBridge) {
    web.addJavascriptInterface(bridge, "AndroidAI")
}

/** Native floating window around APK-bundled AI UI; the original timetable stays Compose. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AiWebOverlay(vm: AiViewModel, loaded: LoadedBoard, week: Int, visible: Boolean,
                 settingsVisible: Boolean, open: Boolean, onOpenChange: (Boolean) -> Unit,
                 onBack: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val c = LocalSeuColors.current
    val density = LocalDensity.current
    val ui by vm.state.collectAsState()
    var previous by remember { mutableStateOf<ChatRect?>(null) }
    val latestChanged by rememberUpdatedState(onChanged)
    val latestBack by rememberUpdatedState(onBack)
    val wantsSettings by rememberUpdatedState(settingsVisible)
    val handler = remember { Handler(Looper.getMainLooper()) }
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        fileCallback?.onReceiveValue(uris.filter { it.scheme == "content" }.take(4).toTypedArray().takeIf { it.isNotEmpty() })
        fileCallback = null
    }
    val windowAction = remember { arrayOf<(String) -> Unit>({}) }
    var ready by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var loadAttempt by remember { mutableIntStateOf(0) }
    var rendererGone by remember { mutableStateOf(false) }
    val entryUrl = "https://appassets.androidplatform.net/index.html"
    val web = remember { WebView(context) }
    val alive = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    val transport = remember {
        AiNativeTransport { id, data -> handler.post {
            if (alive.get() && web.url == "https://appassets.androidplatform.net/index.html")
                web.evaluateJavascript("window.nativeReceive?.(${JsonPrimitive(id)},$data)", null)
        } }
    }
    val bridge = remember { AiWebBridge(context, transport, vm, { handler.post { latestChanged() } }, { action -> handler.post {
        if (action == "ready" && alive.get()) {
            val attempt = loadAttempt
            web.evaluateJavascript("window.nativeShow?.($wantsSettings)") {
                if (alive.get()) web.postVisualStateCallback(attempt.toLong(), object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        if (alive.get() && requestId == loadAttempt.toLong()) {
                            ready = true; loadError = null; web.invalidate()
                        }
                    }
                })
            }
        }
        else if (action == "startup-error") { ready = false; loadError = "AI 页面启动失败（脚本或组件不兼容）" }
        else windowAction[0](action)
    } }) }
    fun loadPage() {
        ready = false; loadError = null; loadAttempt++
        try {
            val html = context.assets.open("ai/index.html").bufferedReader().use { it.readText() }
            // Keep the HTTPS origin for modules/storage, but read the document directly
            // from the APK, without a top-level navigation or network dependency.
            web.loadDataWithBaseURL(entryUrl, html, "text/html", "UTF-8", entryUrl)
        } catch (_: Exception) { loadError = "无法读取 APK 内的 AI 页面" }
    }
    DisposableEffect(web) {
        // Load only after AndroidView attaches and measures the WebView. Preloading a
        // detached, zero-sized WebView can leave the first frame blank on device engines.
        web.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        web.setBackgroundColor(c.surface.toArgb())
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE // APK assets change on application updates.
            allowFileAccess = false; allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false; setSupportMultipleWindows(false)
            setGeolocationEnabled(false); mediaPlaybackRequiresUserGesture = true
        }
        attachAiBridge(web, bridge)
        web.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) { ready = false; loadError = "AI 页面加载失败（${error.errorCode}）" }
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, error: WebResourceResponse) {
                if (request.url.host == "appassets.androidplatform.net" &&
                    (request.isForMainFrame || request.url.path.orEmpty().substringAfterLast('.') in setOf("js", "mjs", "css"))) {
                    ready = false; loadError = "AI 本地资源加载失败（${error.statusCode}）"
                }
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                ready = false; rendererGone = true; loadError = "手机的网页渲染进程已退出，请重新打开应用"
                alive.set(false); bridge.close()
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
                return true
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val uri = request.url
                val path = uri.path.orEmpty().removePrefix("/")
                val allowed = uri.scheme == "https" && uri.host == "appassets.androidplatform.net" &&
                    request.method == "GET" && Regex("(?:vendor/)?[a-zA-Z0-9_.-]+\\.(?:html|css|js|mjs|json)").matches(path)
                if (allowed) try {
                    val mime = when (path.substringAfterLast('.')) { "html" -> "text/html"; "css" -> "text/css"; "json" -> "application/json"; else -> "text/javascript" }
                    return WebResourceResponse(mime, "UTF-8", context.assets.open("ai/$path"))
                } catch (_: Exception) { /* Fail closed; never fall back to remote network. */ }
                return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), "Blocked".byteInputStream())
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.toString() == entryUrl) return false
                if (request.isForMainFrame && request.hasGesture() && request.url.scheme in setOf("https", "http") && request.url.host != "appassets.androidplatform.net") {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                }
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                // Do not log arbitrary JS messages: they may contain API credentials or conversations.
                if (!ready && message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    val source = Uri.parse(message.sourceId()).lastPathSegment.orEmpty().take(60)
                    loadError = "AI 页面脚本启动失败（$source:${message.lineNumber()}）"
                }
                return true
            }
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null); fileCallback = callback
                picker.launch(arrayOf("image/*")); return true
            }
        }
        fun startWhenMeasured() {
            if (alive.get() && loadAttempt == 0 && web.isAttachedToWindow && web.width > 0 && web.height > 0) loadPage()
        }
        val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> startWhenMeasured() }
        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { view.post { startWhenMeasured() } }
            override fun onViewDetachedFromWindow(view: View) = Unit
        }
        web.addOnLayoutChangeListener(layoutListener)
        web.addOnAttachStateChangeListener(attachListener)
        web.post { startWhenMeasured() }
        onDispose {
            alive.set(false)
            fileCallback?.onReceiveValue(null); fileCallback = null
            bridge.close()
            if (!rendererGone) web.removeJavascriptInterface("AndroidAI")
            web.removeOnLayoutChangeListener(layoutListener)
            web.removeOnAttachStateChangeListener(attachListener)
            (web.parent as? ViewGroup)?.removeView(web)
            if (!rendererGone) { web.stopLoading(); web.destroy() }
        }
    }
    LaunchedEffect(loadAttempt, ready, open, settingsVisible, visible) {
        if ((settingsVisible || (visible && open)) && !ready) {
            kotlinx.coroutines.delay(15000)
            if (!ready && loadError == null) loadError = "AI 页面启动超时，请重试"
        }
    }
    SideEffect { bridge.viewedWeek = week; bridge.active = visible }
    LaunchedEffect(loaded, week, ready) { if (ready) web.evaluateJavascript("window.nativeRefresh?.()", null) }
    LaunchedEffect(c, ready) {
        if (ready) {
            val tokens=buildJsonObject {
                for ((name,color) in mapOf("bg" to c.bg,"surface" to c.surface,"surfaceSunken" to c.surfaceSunken,
                    "border" to c.border,"textPrimary" to c.textPrimary,"textSecondary" to c.textSecondary,
                    "textTertiary" to c.textTertiary,"primary" to c.primary,"onPrimary" to c.onPrimary,"danger" to c.danger)) {
                    put(name,"#%06X".format(color.toArgb() and 0xFFFFFF))
                }
            }
            web.evaluateJavascript("window.nativeTheme?.($tokens)",null)
        }
    }
    LaunchedEffect(settingsVisible, visible, ready) {
        if (ready && (settingsVisible || visible)) web.evaluateJavascript("window.nativeShow?.($settingsVisible)", null)
        if (!visible && !settingsVisible && ready) web.evaluateJavascript("window.nativeSuspend?.()", null)
    }
    BackHandler(settingsVisible || (visible && open)) {
        if (!ready || rendererGone) { if (wantsSettings) latestBack() else onOpenChange(false) }
        else web.evaluateJavascript("window.nativeBack?.()") { handled ->
            if (handled != "true") { if (wantsSettings) latestBack() else onOpenChange(false) }
            else if (wantsSettings) latestBack()
        }
    }
    // The WebView is remembered when minimized so a pending request and its draft survive.
    if (!visible && !settingsVisible) return
    val ime = WindowInsets.ime.getBottom(density) > 0
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)
        .windowInsetsPadding(WindowInsets.navigationBars).imePadding().padding(bottom = if (settingsVisible || ime) 0.dp else 80.dp)) {
        val w = maxWidth.value; val h = maxHeight.value
        val rect = ui.position.window.constrain(w, h)
        fun change(next: ChatRect) { vm.position(vm.state.value.position.copy(window = next.constrain(w, h))) }
        fun scale(amount: Float) { change(rect.copy(width = rect.width + amount, height = rect.height + amount * 1.5f)) }
        windowAction[0] = { action -> when (action) {
            "back" -> { if (wantsSettings) latestBack() }
            "minimize" -> {
                (context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(web.windowToken,0)
                web.clearFocus();onOpenChange(false); if (wantsSettings) latestBack()
            }
            "shrink" -> scale(-35f)
            "grow" -> scale(35f)
            "size" -> { val old = previous
                if (old != null) { change(old); previous = null }
                else { previous = rect; val limit = chatLimits(w,h); change(rect.copy(width = limit.maxWidth,height = limit.maxHeight)) }
            }
        } }
        if (!open && !settingsVisible) {
            val x = ui.position.bubbleX.coerceIn(8f, (w-60).coerceAtLeast(8f))
            val y = ui.position.bubbleY.coerceIn(0f, (h-60).coerceAtLeast(0f))
            Box(Modifier.offset { IntOffset((x*density.density).roundToInt(), (y*density.density).roundToInt()) }
                .size(52.dp).shadow(6.dp,CircleShape).clip(CircleShape).background(c.primary).border(2.dp,c.surface,CircleShape)
                .pointerInput(w,h) { detectDragGestures { event, delta ->
                    event.consume(); val p=vm.state.value.position
                    vm.position(p.copy(bubbleX=(p.bubbleX.coerceIn(8f,(w-60).coerceAtLeast(8f))+delta.x/density.density).coerceIn(8f,(w-60).coerceAtLeast(8f)),
                        bubbleY=(p.bubbleY.coerceIn(0f,(h-60).coerceAtLeast(0f))+delta.y/density.density).coerceIn(0f,(h-60).coerceAtLeast(0f))))
                } }.clickable { onOpenChange(true); web.evaluateJavascript("window.nativeShow?.(false)",null) }
                .semantics { contentDescription="打开 AI 助手" }, contentAlignment=Alignment.Center) {
                Text("✦ AI",color=c.onPrimary,fontSize=15.sp)
            }
        }
        if (open || settingsVisible) {
            val placement = if (settingsVisible) Modifier.fillMaxSize() else Modifier
                .offset { IntOffset((rect.x*density.density).roundToInt(),(rect.y*density.density).roundToInt()) }.size(rect.width.dp,rect.height.dp)
            Column(placement.shadow(12.dp,RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)).background(c.surface)) {
                if (!settingsVisible) Box(Modifier.fillMaxWidth().height(18.dp).pointerInput(w,h) { detectDragGestures { event,delta ->
                    event.consume(); val p=vm.state.value.position.window.constrain(w,h)
                    change(p.copy(x=p.x+delta.x/density.density,y=p.y+delta.y/density.density))
                } },contentAlignment=Alignment.Center) {
                    Box(Modifier.width(36.dp).height(3.dp).background(c.border,RoundedCornerShape(2.dp)))
                }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    if (!rendererGone) AndroidView(factory={web},modifier=Modifier.fillMaxSize())
                    if (!ready || loadError != null) Column(Modifier.fillMaxSize().background(c.surface).padding(24.dp),
                        horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.Center) {
                        Text(loadError ?: "正在加载 AI 助手…", color=c.textPrimary, fontSize=15.sp)
                        if (loadError != null) {
                            val provider = WebView.getCurrentWebViewPackage()
                            Text("WebView ${provider?.versionName ?: "未知版本"}", color=c.textSecondary, fontSize=12.sp)
                            if (!rendererGone) TextButton(onClick={ loadPage() }) { Text("重新加载", color=c.primary) }
                            TextButton(onClick={ if (wantsSettings) latestBack() else onOpenChange(false) }) { Text("返回", color=c.primary) }
                        }
                    }
                }
                if (!settingsVisible) Box(Modifier.fillMaxWidth().height(18.dp),contentAlignment=Alignment.CenterEnd) {
                    Text("◢",color=c.textTertiary,modifier=Modifier.width(32.dp).pointerInput(w,h) { detectDragGestures { event,delta ->
                        event.consume(); val p=vm.state.value.position.window.constrain(w,h)
                        change(p.copy(width=p.width+delta.x/density.density,height=p.height+delta.y/density.density))
                    } })
                }
            }
        }
    }
}
