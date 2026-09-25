/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import androidx.core.app.NotificationCompat
import android.os.Build
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.canResumeToolExecution
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.ai.ui.limitContext
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.ai.transformers.visualTransforms
import me.rerere.rikkahub.data.ai.transformers.onGenerationFinish
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.ai.tools.SystemTools
import me.rerere.rikkahub.data.ai.tools.createSearchTools
import me.rerere.rikkahub.data.ai.tools.createSkillTools
import me.rerere.rikkahub.data.ai.tools.createWorkspaceTools
import me.rerere.rikkahub.data.ai.tools.ToolNaming
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.plugin.provider.PluginToolProvider
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.data.datastore.ProactiveMessageSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.sendNotification
import java.time.Instant
import kotlin.uuid.Uuid
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class ProactiveMessageService : KoinComponent {
    private val settingsStore: SettingsStore by inject()
    private val conversationRepository: ConversationRepository by inject()

    companion object {
        const val TAG = "ProactiveMessageService"
        const val ACTION_PROACTIVE_MESSAGE = "me.rerere.orangechat.PROACTIVE_MESSAGE"
        private const val REQUEST_CODE = 10001

        internal const val PREFS_NAME = "proactive_message_prefs"
        private const val KEY_NEXT_TRIGGER_TIME = "next_trigger_time"

        fun scheduleNext(context: Context, setting: ProactiveMessageSetting) {
            if (!setting.enabled) {
                cancel(context)
                return
            }

            val minMinutes = setting.minIntervalMinutes.coerceAtLeast(1)
            val maxMinutes = setting.maxIntervalMinutes.coerceAtLeast(minMinutes)
            val delayMinutes = Random.nextInt(minMinutes, maxMinutes + 1)
            val triggerTime = java.lang.System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(delayMinutes.toLong())

            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_NEXT_TRIGGER_TIME, triggerTime)
                .apply()

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ProactiveMessageReceiver::class.java).apply {
                action = ACTION_PROACTIVE_MESSAGE
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            triggerTime,
                            pendingIntent
                        )
                    } else {
                        alarmManager.setAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            triggerTime,
                            pendingIntent
                        )
                        Log.w(TAG, "Exact alarm permission not granted, using inexact alarm")
                    }
                } else {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                }
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            }

            Log.d(TAG, "Scheduled proactive message in $delayMinutes minutes, trigger at ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(triggerTime))}")

            ProactiveMessageWorker.scheduleNext(context, setting)
        }

        fun getNextTriggerTime(context: Context): Long? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val triggerTime = prefs.getLong(KEY_NEXT_TRIGGER_TIME, 0L)
            return if (triggerTime > 0) triggerTime else null
        }

        fun cancel(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_NEXT_TRIGGER_TIME)
                .apply()

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ProactiveMessageReceiver::class.java).apply {
                action = ACTION_PROACTIVE_MESSAGE
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            pendingIntent?.let {
                alarmManager.cancel(it)
                Log.d(TAG, "Cancelled proactive message alarm")
            }

            ProactiveMessageWorker.cancel(context)
        }

        fun resetTimer(context: Context, setting: ProactiveMessageSetting) {
            scheduleNext(context, setting)
        }

        fun triggerNow(context: Context, setting: ProactiveMessageSetting) {
            scheduleNext(context, setting)
            val serviceIntent = Intent(context, ProactiveMessageTriggerService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }

    suspend fun buildProactiveContext(context: Context, settings: Settings): String {
        val sb = StringBuilder()
        sb.appendLine("[主动消息上下文]")

        try {
            val lastMessageTime = getLastMessageTime()
            if (lastMessageTime != null) {
                val nowMs = java.lang.System.currentTimeMillis()
                val lastMs = lastMessageTime.toEpochMilliseconds()
                val diffMs = nowMs - lastMs
                val duration = diffMs.milliseconds
                val minutesAgo = duration.inWholeMinutes
                val hoursAgo = duration.inWholeHours
                when {
                    hoursAgo > 24 -> sb.appendLine("距离上次聊天: ${hoursAgo / 24}天${hoursAgo % 24}小时")
                    hoursAgo > 0 -> sb.appendLine("距离上次聊天: ${hoursAgo}小时${minutesAgo % 60}分钟")
                    else -> sb.appendLine("距离上次聊天: ${minutesAgo}分钟")
                }
            } else {
                sb.appendLine("距离上次聊天: 很久没有聊天了")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get last message time", e)
        }

        val currentTime = java.lang.System.currentTimeMillis()
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        sb.appendLine("当前时间: ${sdf.format(java.util.Date(currentTime))}")

        try {
            val amapApiKey = settings.systemToolsSetting.amapApiKey
            if (amapApiKey.isNotBlank()) {
                val amapService = AmapService(amapApiKey)
                val locationService = LocationService(context, amapService)
                val locationResult = locationService.getCurrentLocation(amapApiKey)
                if (locationResult.isSuccess) {
                    val location = locationResult.getOrThrow()
                    val locationLine = if (location.address.isNotBlank()) {
                        "当前位置: ${location.address}"
                    } else {
                        "当前坐标: ${location.latitude}, ${location.longitude}"
                    }
                    if (!location.isFresh) {
                        val ageMinutes = location.ageMs / 60000
                        sb.appendLine("$locationLine（注意：这是大约 ${ageMinutes} 分钟前的定位，可能不是当前实时位置，不要当作用户现在就在这里）")
                    } else {
                        sb.appendLine(locationLine)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get location context", e)
        }

        try {
            val appUsageService = AppUsageService(context)
            val usageResult = appUsageService.getTodayUsageStats()
            if (usageResult.isSuccess) {
                val usageStats = usageResult.getOrThrow()
                if (usageStats.isNotEmpty()) {
                    sb.appendLine("今日应用使用:")
                    usageStats.take(5).forEach { stat ->
                        val minutes = stat.totalTimeInForeground / 60000
                        if (minutes > 0) {
                            sb.appendLine("  - ${stat.appName}: ${minutes}分钟")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get app usage context", e)
        }

        try {
            val appUsageService = AppUsageService(context)
            val foregroundResult = appUsageService.getForegroundApp()
            if (foregroundResult.isSuccess) {
                val foregroundApp = foregroundResult.getOrThrow()
                if (foregroundApp.isNotBlank()) {
                    sb.appendLine("当前前台应用: $foregroundApp")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get foreground app", e)
        }

        try {
            val notifications = me.rerere.rikkahub.data.service.RikkaNotificationListenerService.getTodayNotifications().take(10)
            if (notifications.isNotEmpty()) {
                sb.appendLine("今日通知（最近${notifications.size}条）:")
                notifications.forEach { notif ->
                    sb.appendLine("  - [${notif.appName}] ${notif.title}: ${notif.content.take(50)}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get notifications", e)
        }

        try {
            val batteryIntent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            if (batteryIntent != null) {
                val level = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                val scale = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
                val pct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
                val status = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
                val isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL
                sb.appendLine("设备电量: ${pct}%${if (isCharging) "（充电中）" else ""}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get battery info", e)
        }

        return sb.toString()
    }

    suspend fun getLastMessageTimeMs(): Long {
        return try { getLastMessageTime()?.toEpochMilliseconds() ?: 0L } catch (e: Exception) { 0L }
    }
    private suspend fun getLastMessageTime(): kotlinx.datetime.Instant? {
        return try {
            val settings = settingsStore.settingsFlow.first()
            val assistantId = settings.assistantId
            val recentConversations = conversationRepository.getRecentConversations(assistantId, limit = 1)
            if (recentConversations.isNotEmpty()) {
                val conv = recentConversations.first()
                val fullConv = conversationRepository.getConversationById(conv.id)
                val localDateTime: LocalDateTime? = fullConv?.messageNodes?.lastOrNull()?.messages?.lastOrNull()?.createdAt
                localDateTime?.toInstant(TimeZone.currentSystemDefault())
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get last message time", e)
            null
        }
    }
}

class ProactiveMessageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(ProactiveMessageService.TAG, "=== onReceive triggered at ${System.currentTimeMillis()}, action=${intent.action} ===")
        when (intent.action) {
            ProactiveMessageService.ACTION_PROACTIVE_MESSAGE -> {
                Log.d(ProactiveMessageService.TAG, "Starting ProactiveMessageTriggerService...")
                val serviceIntent = Intent(context, ProactiveMessageTriggerService::class.java)
                context.startForegroundService(serviceIntent)
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d(ProactiveMessageService.TAG, "Boot completed, rescheduling proactive message")
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val settingsStore = org.koin.core.context.GlobalContext.get().get<SettingsStore>()
                        val settings = settingsStore.settingsFlow.first()
                        val proactiveSetting = settings.proactiveMessageSetting
                        if (proactiveSetting.enabled) {
                            ProactiveMessageService.scheduleNext(context, proactiveSetting)
                        }
                    } catch (e: Exception) {
                        Log.e(ProactiveMessageService.TAG, "Failed to reschedule after boot", e)
                    }
                }
            }
        }
    }
}

class ProactiveMessageTriggerService : android.app.Service(), KoinComponent {
    private val settingsStore: SettingsStore by inject()
    private val conversationRepository: ConversationRepository by inject()
    private val memoryRepository: MemoryRepository by inject()
    private val workspaceRepository: WorkspaceRepository by inject()
    private val providerManager: ProviderManager by inject()
    private val templateTransformer: TemplateTransformer by inject()
    private val localTools: LocalTools by inject()
    private val skillManager: SkillManager by inject()
    private val mcpManager: McpManager by inject()
    private val pluginToolProvider: PluginToolProvider by inject()
    private val json: Json by inject()
    private val chatService: ChatService by inject()
    private val proactiveMessageService = ProactiveMessageService()

    companion object {
        private const val TAG = "ProactiveMessageTrigger"
        private const val MAX_TOOL_STEPS = 10
        const val EXTRA_FORCE_TRIGGER = "force_trigger"
        const val EXTRA_DEVICE_EVENT_CONTEXT = "device_event_context"

        private val prefsLock = Any()
    }

    private val inputTransformers by lazy {
        listOf(
            TimeReminderTransformer,
            PromptInjectionTransformer,
            PlaceholderTransformer,
            DocumentAsPromptTransformer,
            OcrTransformer,
        )
    }

    private val outputTransformers by lazy {
        listOf(
            ThinkTagTransformer,
            Base64ImageToLocalFileTransformer,
            RegexOutputTransformer,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "=== TriggerService onStartCommand ===")
        val isForceTrigger = intent?.getBooleanExtra(EXTRA_FORCE_TRIGGER, false) ?: false
        val deviceEventContext = intent?.getStringExtra(EXTRA_DEVICE_EVENT_CONTEXT)
        val isFromDeviceEvent = deviceEventContext != null
        if (isForceTrigger) {
            Log.d(TAG, "Force trigger${if (isFromDeviceEvent) " from device event" else " from gateway poll"}, will skip min interval check")
        }
        val notification = androidx.core.app.NotificationCompat.Builder(this, CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("正在思考...")
            .setSmallIcon(me.rerere.rikkahub.R.drawable.small_icon)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MIN)
            .build()
        startForeground(20001, notification)

        CoroutineScope(Dispatchers.IO).launch {
            var conversationId: kotlin.uuid.Uuid? = null
            try {
                val settings = settingsStore.settingsFlow.first()
                val proactiveSetting = settings.proactiveMessageSetting

                if (!proactiveSetting.enabled && !isFromDeviceEvent) {
                    stopSelf()
                    return@launch
                }

                val prefs = getSharedPreferences(ProactiveMessageService.PREFS_NAME, Context.MODE_PRIVATE)

                if (!isForceTrigger) {
                    val skipDueToInterval = synchronized(prefsLock) {
                        val lastTriggeredTime = prefs.getLong("last_triggered_time", 0L)
                        val minIntervalMs = proactiveSetting.minIntervalMinutes.coerceAtLeast(1) * 60 * 1000L
                        if (System.currentTimeMillis() - lastTriggeredTime < minIntervalMs) {
                            true
                        } else {
                            prefs.edit().putLong("last_triggered_time", System.currentTimeMillis()).apply()
                            false
                        }
                    }
                    if (skipDueToInterval) {
                        Log.d(TAG, "Duplicate trigger within min interval, skipping")
                        ProactiveMessageService.scheduleNext(this@ProactiveMessageTriggerService, proactiveSetting)
                        stopSelf()
                        return@launch
                    }
                } else {
                    synchronized(prefsLock) {
                        prefs.edit().putLong("last_triggered_time", System.currentTimeMillis()).apply()
                    }
                }

                val assistant = settings.assistants.find { it.id.toString() == proactiveSetting.assistantId }
                    ?: settings.getCurrentAssistant()
                val assistantUuid = assistant.id
                val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)

                if (model == null) {
                    Log.e(ProactiveMessageService.TAG, "No model found for proactive message")
                    ProactiveMessageService.scheduleNext(this@ProactiveMessageTriggerService, proactiveSetting)
                    stopSelf()
                    return@launch
                }

                val recentConversations = conversationRepository.getRecentConversations(assistantUuid, limit = 1)
                val conversation = if (recentConversations.isNotEmpty()) {
                    conversationRepository.getConversationById(recentConversations.first().id)
                } else null

                conversationId = conversation?.id ?: kotlin.uuid.Uuid.random()
                val conversationId = conversationId!!

                chatService.addConversationReference(conversationId)
                if (conversation != null) {
                    chatService.updateConversationState(conversationId) { _ -> conversation }
                }

                val myJob = coroutineContext[Job]
                if (myJob == null || !chatService.getOrCreateSession(conversationId).tryClaimGeneration(myJob)) {
                    Log.d(
                        TAG,
                        "Skip proactive trigger: session $conversationId already generating " +
                            "(normal chat or another proactive trigger in progress)"
                    )
                    stopSelf()
                    return@launch
                }

                val idleMinutes = runCatching { val last = proactiveMessageService.getLastMessageTimeMs(); if (last > 0) ((System.currentTimeMillis() - last) / 60000L).toInt() else Int.MAX_VALUE }.getOrDefault(Int.MAX_VALUE)

                val contextStr = if (isFromDeviceEvent && deviceEventContext != null) {
                    deviceEventContext
                } else {
                    proactiveMessageService.buildProactiveContext(
                        this@ProactiveMessageTriggerService, settings
                    )
                }

                val historyMessages = filterInvalidToolMessages(
                    conversation?.currentMessages?.let {
                        if (assistant.contextMessageSize > 0) {
                            it.takeLast(assistant.contextMessageSize)
                        } else it
                    } ?: emptyList()
                )

                val systemPrompt = buildSystemPrompt(assistant, settings, idleMinutes, proactiveSetting.jumpIdleThresholdMinutes, isFromDeviceEvent, if (isFromDeviceEvent) deviceEventContext else contextStr)

                val userMessage = UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Text(
                        if (isFromDeviceEvent) {
                            "请根据以上用户动向决定是否发消息。没什么好说的就回复 [PASS]。"
                        } else {
                            "主动干点什么的机会到了。你可以和用户继续对话或者去做一些自己感兴趣的事情。如果不想做任何事，回复[PASS]即可。"
                        }
                    ))
                )

                val processedUserMessage = listOf(userMessage).transforms(
                    transformers = inputTransformers + templateTransformer,
                    context = this@ProactiveMessageTriggerService,
                    model = model,
                    assistant = assistant,
                    settings = settings
                ).first()

                val messages = mergeAdjacentSameRoleMessages(
                    buildList {
                        add(UIMessage(
                            role = MessageRole.SYSTEM,
                            parts = listOf(UIMessagePart.Text(systemPrompt))
                        ))
                        addAll(historyMessages)
                        add(processedUserMessage)
                    }
                )

                val providerSetting = model.findProvider(settings.providers)
                if (providerSetting == null) {
                    Log.e(ProactiveMessageService.TAG, "No provider found for proactive message")
                    ProactiveMessageService.scheduleNext(this@ProactiveMessageTriggerService, proactiveSetting)
                    stopSelf()
                    return@launch
                }

                val providerImpl = providerManager.getProviderByType(providerSetting)
                val tools = buildTools(settings, assistant, model)

                val params = TextGenerationParams(
                    model = model,
                    temperature = assistant.temperature,
                    topP = assistant.topP,
                    maxTokens = assistant.maxTokens,
                    tools = tools,
                    reasoningLevel = assistant.reasoningLevel,
                    customHeaders = buildList {
                        addAll(assistant.customHeaders)
                        addAll(model.customHeaders)
                    },
                    customBody = buildList {
                        addAll(assistant.customBodies)
                        addAll(model.customBodies)
                    }
                )

                Log.d(TAG, "Calling AI API for proactive message with ${historyMessages.size} history messages, ${tools.size} tools (reasoning=${assistant.reasoningLevel}, model=${model.modelId}, provider=${providerSetting::class.simpleName})...")

                val (finalMessages, hasToolCalls, hasJumpFlag) = generateWithTools(
                    conversationId = conversationId,
                    providerImpl = providerImpl,
                    providerSetting = providerSetting,
                    initialMessages = messages,
                    params = params,
                    tools = tools,
                    model = model,
                    assistant = assistant,
                    settings = settings
                )

                val aiMessage = finalMessages.lastOrNull() ?: UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = emptyList()
                )

                val rawText = aiMessage.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n") { it.text }.trim()
                val replyText = rawText.replace("\\[JUMP]".toRegex(RegexOption.IGNORE_CASE), "").trim()
                val shouldJump = hasJumpFlag

                if (rawText != replyText) {
                    val cleanedAiMessage = aiMessage.copy(
                        parts = aiMessage.parts.map { part ->
                            if (part is UIMessagePart.Text) {
                                UIMessagePart.Text(part.text.replace("\\[JUMP]".toRegex(RegexOption.IGNORE_CASE), "").trim())
                            } else {
                                part
                            }
                        }
                    )
                    updateOrAppendAiMessage(conversationId, cleanedAiMessage)
                }

                Log.d(TAG, "Proactive message generated: '${replyText.take(100)}...' (${replyText.length} chars), hasToolCalls=$hasToolCalls, shouldJump=$shouldJump")

                if (replyText.isBlank() || rawText.contains("[PASS]")) {
                    Log.d(ProactiveMessageService.TAG, "AI chose to skip proactive message")
                    val aiId = aiMessage.id
                    val session = chatService.getOrCreateSession(conversationId)
                    session.saveMutex.withLock {
                        val conv = chatService.getConversationFlow(conversationId).value
                        chatService.updateConversation(
                            conversationId,
                            conv.copy(
                                messageNodes = conv.messageNodes.filterNot { node ->
                                    node.messages.any { it.id == aiId }
                                }
                            )
                        )
                        chatService.saveConversation(conversationId, chatService.getConversationFlow(conversationId).value)
                    }
                } else {
                    saveProactiveMessage(
                        settings, assistant, conversationId, conversation
                    )
                    try {
                        val externalMemoryConfigs = settings.externalMemories.filter {
                            it.enabled && it.id in assistant.externalMemoryIds && it.autoSaveMessages
                        }
                        if (externalMemoryConfigs.isNotEmpty() && replyText.isNotBlank()) {
                            kotlinx.coroutines.coroutineScope {
                                externalMemoryConfigs.forEach { config ->
                                    launch {
                                        runCatching {
                                            val service = ExternalMemoryService(config)
                                            service.saveMessage(
                                                assistantId = assistant.id.toString(),
                                                conversationId = conversationId.toString(),
                                                role = "assistant",
                                                content = replyText,
                                            )
                                        }.onFailure {
                                            Log.w(
                                                ProactiveMessageService.TAG,
                                                "Failed to save proactive message to external memory ${config.name}",
                                                it
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(ProactiveMessageService.TAG, "Failed to save proactive message to external memory", e)
                    }
                    showProactiveNotification(conversationId, assistant.name.ifBlank { "AI" }, replyText)
                    if (proactiveSetting.floatingBubbleEnabled) {
                        FloatingBubbleService.show(
                            context = this@ProactiveMessageTriggerService,
                            conversationId = conversationId.toString(),
                            senderName = assistant.name.ifBlank { "AI" },
                            avatar = assistant.avatar,
                        )
                    }
                    if (shouldJump) {
                        try {
                            val jumpIntent = Intent(this@ProactiveMessageTriggerService, RouteActivity::class.java).apply {
                                addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                                )
                                putExtra("conversationId", conversationId.toString())
                            }
                            startActivity(jumpIntent)
                            Log.d(TAG, "Force jump to conversation $conversationId")
                        } catch (e: Exception) {
                            Log.e(TAG, "Force jump failed", e)
                        }
                    }
                }
            } catch (e: CancellationException) {
                Log.d(
                    ProactiveMessageService.TAG,
                    "Proactive generation cancelled (likely user started a new message), " +
                        "conversationId=$conversationId"
                )
                throw e
            } catch (e: Exception) {
                Log.e(ProactiveMessageService.TAG, "Failed to trigger proactive message", e)
                val cause = e.cause
                if (cause != null) {
                    Log.e(ProactiveMessageService.TAG, "Underlying cause: ${cause::class.simpleName}: ${cause.message}", cause)
                }
                conversationId?.let { cid ->
                    try {
                        val session = chatService.getOrCreateSession(cid)
                        session.saveMutex.withLock {
                            val conv = chatService.getConversationFlow(cid).value
                            val updatedNodes = conv.messageNodes.filterNot { node ->
                                node.messages.any { msg ->
                                    msg.parts.any { part ->
                                        (part is UIMessagePart.Text && (
                                            part.text.contains("呼叫大模型时被拒绝了") ||
                                            part.text.contains("Invalid request body")
                                        ))
                                    }
                                }
                            }
                            if (updatedNodes.size != conv.messageNodes.size) {
                                chatService.updateConversation(cid, conv.copy(messageNodes = updatedNodes))
                                chatService.saveConversation(cid, chatService.getConversationFlow(cid).value)
                                Log.d(ProactiveMessageService.TAG, "Cleaned up error messages from conversation history")
                            }
                        }
                    } catch (cleanupErr: Exception) {
                        Log.w(ProactiveMessageService.TAG, "Failed to cleanup error messages", cleanupErr)
                    }
                }
            } finally {
                if (!isFromDeviceEvent) {
                    withContext(NonCancellable) {
                        try {
                            val currentSettings = settingsStore.settingsFlow.first()
                            ProactiveMessageService.scheduleNext(
                                this@ProactiveMessageTriggerService,
                                currentSettings.proactiveMessageSetting
                            )
                        } catch (e: Exception) {
                            Log.e(ProactiveMessageService.TAG, "Failed to reschedule after completion/error/cancellation", e)
                        }
                    }
                }
                conversationId?.let { chatService.removeConversationReference(it) }
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private suspend fun buildSystemPrompt(assistant: Assistant, settings: Settings, idleMinutes: Int = 0, jumpThreshold: Int = 120, isFromDeviceEvent: Boolean = false, deviceEventContext: String? = null): String {
        return buildString {
            val effectiveSystemPrompt = if (assistant.allowConversationSystemPrompt) {
                assistant.systemPrompt
            } else {
                assistant.systemPrompt
            }
            if (effectiveSystemPrompt.isNotBlank()) {
                append(effectiveSystemPrompt)
            }

            if (assistant.enableMemory) {
                val memories = if (assistant.useGlobalMemory) {
                    memoryRepository.getGlobalMemories()
                } else {
                    memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
                }
                if (memories.isNotEmpty()) {
                    appendLine()
                    appendLine()
                    appendLine("## 记忆")
                    memories.forEach { memory ->
                        appendLine("- ${memory.content}")
                    }
                }
            }

            if (isFromDeviceEvent) {
                appendLine()
                appendLine()
                appendLine("## ⚠️ 当前触发原因：用户手机动向（设备事件触发）")
                appendLine("你是因为检测到用户的手机操作动向（切换应用/亮屏锁屏/回桌面）而被触发的。")
                appendLine("请特别注意：这是设备事件触发，不是定时主动消息。根据用户的手机操作动向来决定是否发消息。")
                appendLine("绝对不要复述上一轮的对话内容，要发新的话题或新的关心。")
                appendLine("请根据用户的动向，自然地决定是否主动发一条消息。距离用户上次回复已过去 $idleMinutes 分钟。")
                appendLine("如果你觉得现在没什么好说的，或者没什么有趣的话题，请只回复 [PASS] 即可。")
                appendLine("[JUMP] 标记不会展示给用户，仅用于触发屏幕跳转。")
                if (!deviceEventContext.isNullOrBlank()) {
                    appendLine()
                    appendLine(deviceEventContext)
                }
            } else {
                appendLine()
                appendLine()
                appendLine("## 主动消息触发（定时触发）")
                appendLine("距离用户上次回复已过去 $idleMinutes 分钟。")
                appendLine("这是定时触发的主动消息，不是设备事件触发。")
                appendLine("除了向用户发消息以外，你可以利用这次机会做任何事情，自娱自乐、漫游、创造或者思考。当然，如果你有没做完的工作，记得完成。")
                appendLine("如果以上都不想做，回复[PASS]。[JUMP] 标记不会展示给用户，仅用于触发屏幕跳转。")
                if (!deviceEventContext.isNullOrBlank()) {
                    appendLine()
                    appendLine(deviceEventContext)
                }
            }
        }
    }

    private suspend fun saveProactiveMessage(
        settings: Settings,
        assistant: Assistant,
        conversationId: Uuid,
        existingConversation: Conversation?
    ): Uuid {
        val assistantUuid = assistant.id

        if (existingConversation == null) {
            val session = chatService.getOrCreateSession(conversationId)
            session.saveMutex.withLock {
                val exists = conversationRepository.existsConversationById(conversationId)
                if (!exists) {
                    val newConversation = Conversation(
                        id = conversationId,
                        assistantId = assistantUuid,
                        title = "",
                        messageNodes = emptyList()
                    )
                    conversationRepository.insertConversation(newConversation)
                }
            }
        }

        val session = chatService.getOrCreateSession(conversationId)
        session.saveMutex.withLock {
            chatService.saveConversation(
                conversationId,
                chatService.getConversationFlow(conversationId).value
            )
        }

        Log.d(TAG, "Saved proactive message to conversation $conversationId")
        return conversationId
    }

    private fun showProactiveNotification(
        conversationId: kotlin.uuid.Uuid,
        senderName: String,
        message: String
    ) {
        val intent = Intent(this, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("conversationId", conversationId.toString())
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        sendNotification(
            channelId = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
            notificationId = 20002
        ) {
            title = senderName
            content = message.take(100)
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_MESSAGE
            contentIntent = pendingIntent
            useBigTextStyle = true
        }
    }

    private suspend fun buildTools(settings: Settings, assistant: Assistant, model: Model): List<Tool> {
        return buildList {
            // 本地工具
            addAll(localTools.getTools(assistant.localTools))

            // 系统工具
            val systemToolsOptions = settings.systemToolsSetting.getEnabledOptions()
            if (systemToolsOptions.isNotEmpty()) {
                val systemTools = SystemTools(this@ProactiveMessageTriggerService, settings)
                addAll(systemTools.getTools(systemToolsOptions))
            }

            // 搜索工具（解锁给主动消息全网漫游使用）
            val searchOptions = settings.searchSetting
            if (searchOptions.enableSearch) {
                addAll(createSearchTools(searchOptions))
            }

            // 工作区沙盒工具（解锁给主动消息自由运行命令、写代码、读写文件）
            if (assistant.enableWorkspace && !assistant.workspaceBindingId.isNullOrBlank()) {
                addAll(createWorkspaceTools(assistant.workspaceBindingId, workspaceRepository))
            }

            // Skills 扩展工具（解锁给主动消息使用自定义技能）
            if (assistant.skills.isNotEmpty()) {
                addAll(createSkillTools(assistant.skills, skillManager))
            }

            // MCP 工具
            mcpManager.getAllAvailableTools().forEach { (serverId, tool) ->
                add(
                    Tool(
                        name = ToolNaming.buildMcpToolName(serverId, tool.name),
                        description = tool.description ?: "",
                        parameters = { tool.inputSchema },
                        needsApproval = tool.needsApproval,
                        execute = {
                            mcpManager.callTool(serverId, tool.name, it.jsonObject)
                        },
                    )
                )
            }

            // 插件工具
            addAll(pluginToolProvider.getTools())
        }
    }

    private suspend fun updateOrAppendAiMessage(
        conversationId: Uuid,
        aiMessage: UIMessage
    ) {
        val session = chatService.getOrCreateSession(conversationId)
        session.saveMutex.withLock {
            val conv = chatService.getConversationFlow(conversationId).value
            val existingNodeIndex = conv.messageNodes.indexOfFirst { node ->
                node.messages.any { it.id == aiMessage.id }
            }
            val updated = if (existingNodeIndex >= 0) {
                val oldNode = conv.messageNodes[existingNodeIndex]
                val updatedNode = oldNode.copy(
                    messages = oldNode.messages.map {
                        if (it.id == aiMessage.id) aiMessage else it
                    }
                )
                conv.copy(
                    messageNodes = conv.messageNodes.toMutableList().apply {
                        this[existingNodeIndex] = updatedNode
                    }
                )
            } else {
                conv.copy(messageNodes = conv.messageNodes + aiMessage.toMessageNode())
            }
            chatService.updateConversation(conversationId, updated)
            chatService.saveConversation(conversationId, updated)
        }
    }

    private fun filterInvalidToolMessages(messages: List<UIMessage>): List<UIMessage> {
        return messages.filterNot { message ->
            val tools = message.getTools()
            val hasPendingTools = tools.any { !it.isExecuted }
            if (!hasPendingTools) return@filterNot false
            val hasResumableTool = tools.any { !it.isExecuted && it.approvalState.canResumeToolExecution() }
            !hasResumableTool
        }
    }

    private fun mergeAdjacentSameRoleMessages(messages: List<UIMessage>): List<UIMessage> {
        if (messages.size < 2) return messages
        return messages.fold(emptyList()) { acc, msg ->
            val prev = acc.lastOrNull()
            if (prev != null && prev.role == msg.role) {
                acc.dropLast(1) + prev.copy(parts = prev.parts + msg.parts)
            } else {
                acc + msg
            }
        }
    }

    private suspend fun generateWithTools(
        conversationId: Uuid,
        providerImpl: me.rerere.ai.provider.Provider<ProviderSetting>,
        providerSetting: ProviderSetting,
        initialMessages: List<UIMessage>,
        params: TextGenerationParams,
        tools: List<Tool>,
        model: Model,
        assistant: Assistant,
        settings: Settings
    ): Triple<List<UIMessage>, Boolean, Boolean> {
        var messages = initialMessages.toMutableList()
        var hasToolCalls = false
        var hasJumpFlag = false

        for (step in 0 until MAX_TOOL_STEPS) {
            Log.d(TAG, "generateWithTools: step $step/${MAX_TOOL_STEPS}")

            messages = mergeAdjacentSameRoleMessages(messages).toMutableList()

            var streamMessages = messages.toList()
            providerImpl.streamText(
                providerSetting = providerSetting,
                messages = messages,
                params = params
            ).collect { chunk ->
                streamMessages = streamMessages.handleMessageChunk(chunk = chunk, model = model)

                val currentAiMessage = streamMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
                if (currentAiMessage != null) {
                    updateOrAppendAiMessage(conversationId, currentAiMessage)
                }
            }

            messages = streamMessages.toMutableList()
            val aiMessage = streamMessages.lastOrNull() ?: run {
                Log.w(TAG, "No message in AI response")
                break
            }

            val rawAiText = aiMessage.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
            if (rawAiText.contains("[JUMP]")) {
                hasJumpFlag = true
                Log.d(TAG, "[JUMP] flag detected in raw AI output")
            }
            val processedMessage = listOf(aiMessage).transforms(
                transformers = outputTransformers,
                context = this@ProactiveMessageTriggerService,
                model = model,
                assistant = assistant,
                settings = settings
            ).first()
            messages[messages.lastIndex] = processedMessage

            val toolCalls = processedMessage.getTools().filter { !it.isExecuted }

            if (toolCalls.isEmpty()) {
                val now = kotlin.time.Clock.System.now()
                val finalMessage = processedMessage.copy(
                    parts = processedMessage.parts.map { part ->
                        if (part is UIMessagePart.Reasoning && part.finishedAt == null) {
                            part.copy(finishedAt = now)
                        } else {
                            part
                        }
                    }
                )
                messages[messages.lastIndex] = finalMessage
                updateOrAppendAiMessage(conversationId, finalMessage)
                break
            }

            hasToolCalls = true
            Log.d(TAG, "Tool calls detected: ${toolCalls.size}")

            val executedTools = mutableListOf<UIMessagePart.Tool>()
            for (toolCall in toolCalls) {
                val toolDef = tools.find { it.name == toolCall.toolName }
                if (toolDef == null) {
                    Log.w(TAG, "Tool ${toolCall.toolName} not found")
                    executedTools.add(toolCall.copy(
                        output = listOf(UIMessagePart.Text("""{"error":"Tool not found"}"""))
                    ))
                    continue
                }

                if (toolDef.needsApproval) {
                    Log.w(TAG, "Tool ${toolCall.toolName} needs approval, auto-denying in proactive mode")
                    executedTools.add(toolCall.copy(
                        output = listOf(UIMessagePart.Text("""{"error":"Tool execution denied: requires user approval in proactive mode"}""")),
                        approvalState = ToolApprovalState.Denied("Proactive mode: requires approval")
                    ))
                } else {
                    try {
                        val args = try {
                            json.parseToJsonElement(toolCall.input.ifBlank { "{}" })
                        } catch (e: Exception) {
                            Log.w(TAG, "Tool ${toolCall.toolName} input JSON is incomplete, falling back to empty object: ${toolCall.input.take(200)}")
                            JsonObject(emptyMap())
                        }
                        Log.d(TAG, "Executing tool ${toolDef.name} with args: $args")
                        val result = toolDef.execute(args)
                        executedTools.add(toolCall.copy(output = result))
                    } catch (e: Exception) {
                        Log.e(TAG, "Tool execution failed: ${toolCall.toolName}, args=${toolCall.input}", e)
                        executedTools.add(toolCall.copy(
                            output = listOf(UIMessagePart.Text("""{"error":"${e.message}"}"""))
                        ))
                    }
                }
            }

            val updatedParts = processedMessage.parts.map { part ->
                if (part is UIMessagePart.Tool) {
                    executedTools.find { it.toolCallId == part.toolCallId } ?: part
                } else {
                    part
                }
            }
            val updatedMessage = processedMessage.copy(parts = updatedParts)
            messages[messages.lastIndex] = updatedMessage
            updateOrAppendAiMessage(conversationId, updatedMessage)
        }

        return Triple(messages, hasToolCalls, hasJumpFlag)
    }

    override fun onBind(intent: Intent?): android.os.IBinder? = null
}
