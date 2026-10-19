package com.pluginslab.agentbridge.agent

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.pluginslab.agentbridge.R
import kotlinx.coroutines.*

class ConversationWidget: AppWidgetProvider() {
    override fun onUpdate(context: Context,manager: AppWidgetManager,ids: IntArray) { refreshAsync(context,true) }
    override fun onReceive(context: Context,intent: Intent) {
        super.onReceive(context,intent)
        if(intent.action=="agentbridge.WIDGET_PAUSE") {
            val action=intent.getStringExtra("control_action") ?: AgentService.PAUSE
            if(action in setOf(AgentService.PAUSE,AgentService.RESUME)) AgentService.command(context,action,commandId=intent.getStringExtra("command_id") ?: "widget-control")
        } else if(intent.action=="agentbridge.WIDGET_STOP") {
            AgentService.command(context,AgentService.CANCEL,commandId=intent.getStringExtra("command_id") ?: "widget-stop")
        }
    }
    companion object {
        private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        private val updateLock=Any()
        private var updateJob: Job?=null
        fun refreshAsync(context: Context,force: Boolean=false) = synchronized(updateLock) {
            if(force) updateJob?.cancel()
            if(updateJob?.isActive==true) return
            val app=context.applicationContext
            updateJob=scope.launch {
                if(!force) delay(250)
                runCatching { ConversationHub.init(app);render(app) }
            }
        }
        private fun render(context: Context) {
            val manager=AppWidgetManager.getInstance(context)
            val ids=manager.getAppWidgetIds(ComponentName(context,ConversationWidget::class.java))
            if(ids.isEmpty()) return
            val state=ConversationHub.state.value;val task=state.task
            val live=AgentRuntime.state.value.running || state.routing
            val hide=AgentPrefs(context).widgetPrivate
            val views=RemoteViews(context.packageName,R.layout.conversation_widget)
            views.setTextViewText(R.id.widget_status,state.phase.take(180))
            views.setTextViewText(R.id.widget_message,DisplayPrivacy.safe(if(hide) "Диалог скрыт" else task?.questionText?.takeIf { it.isNotBlank() } ?: state.messages.lastOrNull()?.text?.take(220) ?: "Открой диалог или скажи реплику"))
            fun open(mic: Boolean)=PendingIntent.getActivity(context,if(mic) 72 else 71,Intent(context,CompactConversationActivity::class.java).putExtra("microphone",mic),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_open,open(false));views.setOnClickPendingIntent(R.id.widget_mic,open(true))
            views.setTextViewText(R.id.widget_pause,if(AgentRuntime.state.value.running) "ПАУЗА" else if(task?.status=="WAIT_USER") "ОТВЕТ" else "ДАЛЕЕ")
            val control=if(task?.status=="WAIT_USER") open(false) else PendingIntent.getBroadcast(context,73,Intent(context,ConversationWidget::class.java).setAction("agentbridge.WIDGET_PAUSE").putExtra("control_action",if(AgentRuntime.state.value.running) AgentService.PAUSE else AgentService.RESUME).putExtra("command_id","widget-pause:${task?.id}:${task?.taskRevision}:${task?.status}:${AgentPrefs(context).conversationEpoch}"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_pause,control)
            views.setViewVisibility(R.id.widget_pause,if(task!=null && !IntentRules.isTerminal(task.status)) View.VISIBLE else View.GONE)
            views.setOnClickPendingIntent(R.id.widget_stop,PendingIntent.getBroadcast(context,74,Intent(context,ConversationWidget::class.java).setAction("agentbridge.WIDGET_STOP").putExtra("command_id","widget-stop:${task?.id}:${task?.taskRevision}:${task?.status}:${AgentPrefs(context).conversationEpoch}"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            views.setViewVisibility(R.id.widget_stop,if(live || task?.status=="WAIT_USER") View.VISIBLE else View.GONE)
            manager.updateAppWidget(ids,views)
        }
    }
}
