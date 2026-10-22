package com.pluginslab.agentbridge.agent

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.pluginslab.agentbridge.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.util.UUID

class AgentService: Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    companion object {
        private const val CHANNEL="native_agent_task"
        private const val ID=51
        const val CANCEL="agentbridge.CANCEL"
        const val PAUSE="agentbridge.PAUSE"
        const val RESUME="agentbridge.RESUME"
        const val OPEN_CODE_START="agentbridge.OPENCODE_START"
        private const val MESSAGE="agentbridge.MESSAGE"
        private const val REPLY="agentbridge.REPLY"
        private const val REPLY_TEXT="owner_reply"
        fun message(context: Context,id: String) { context.startForegroundService(Intent(context,AgentService::class.java).setAction(MESSAGE).putExtra("message_id",id)) }
        fun command(context: Context,action: String,taskId: String?=null,commandId: String=UUID.randomUUID().toString()) { context.startForegroundService(Intent(context,AgentService::class.java).setAction(action).putExtra("task_id",taskId).putExtra("command_epoch",AgentPrefs(context).conversationEpoch).putExtra("command_id",commandId)) }
        fun start(context: Context,resumeId: String?=null) { command(context,RESUME,resumeId) }
    }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"Диалог AgentBridge",NotificationManager.IMPORTANCE_LOW))
        if(Build.VERSION.SDK_INT>=34) startForeground(ID,notification("Открываю диалог"),ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID,notification("Открываю диалог"))
        ConversationHub.onIdle={ stopSelf() }
        scope.launch {
            ConversationHub.init(applicationContext)
            ConversationHub.state.collectLatest { state ->
                getSystemService(NotificationManager::class.java).notify(ID,notification(state.phase))
            }
        }
    }
    private fun notification(text: String): Notification {
        val state=ConversationHub.state.value;val task=state.task
        val open=PendingIntent.getActivity(this,0,Intent(this,PanelActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder=NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_agentbridge_monochrome)
            .setContentTitle("AgentBridge · диалог").setContentText(if(task?.questionText?.isNotBlank()==true && !AgentPrefs(this).widgetPrivate) DisplayPrivacy.safe(task.questionText) else DisplayPrivacy.safe(text.take(220)))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
        fun control(action: String,id: Int)=PendingIntent.getService(this,id,Intent(this,AgentService::class.java).setAction(action).putExtra("command_epoch",AgentPrefs(this).conversationEpoch).putExtra("command_id","notification:$action:${task?.id}:${task?.taskRevision}:${task?.status}:${AgentPrefs(this).conversationEpoch}"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if(AgentRuntime.state.value.running) builder.addAction(0,"Пауза",control(PAUSE,1))
        else if(task!=null && task.status !in setOf("COMPLETE","CANCELLED","WAIT_USER")) builder.addAction(0,"Продолжить",control(RESUME,2))
        if(AgentRuntime.state.value.running || state.routing || task?.status=="WAIT_USER" || task?.provider=="opencode" && task.status in setOf("QUEUED","DISPATCHING","RUNNING","STOP_REQUESTED")) builder.addAction(0,"Стоп",control(CANCEL,3))
        val question=task?.questionId.orEmpty()
        val token=if(question.isNotBlank()) question else state.messages.lastOrNull()?.id ?: "empty"
        val reply=PendingIntent.getService(this,4,Intent(this,AgentService::class.java).setAction(REPLY).putExtra("reply_token",token).putExtra("question_id",question),PendingIntent.FLAG_UPDATE_CURRENT or if(Build.VERSION.SDK_INT>=31) PendingIntent.FLAG_MUTABLE else 0)
        builder.addAction(NotificationCompat.Action.Builder(0,"Ответить",reply).addRemoteInput(RemoteInput.Builder(REPLY_TEXT).setLabel("Реплика агенту").build()).setAllowGeneratedReplies(false).build())
        return builder.build()
    }
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        val controlId=if(intent?.action in setOf(CANCEL,PAUSE)) {
            val text=if(intent?.action==CANCEL) "стоп" else "пауза"
            ConversationHub.receive(applicationContext,text,"control:"+(intent?.getStringExtra("command_id") ?: UUID.randomUUID().toString()))
        } else null
        scope.launch {
            try {
                ConversationHub.init(applicationContext)
                when(intent?.action) {
                    MESSAGE -> intent.getStringExtra("message_id")?.let { ConversationHub.process(it) }
                    OPEN_CODE_START -> intent.getStringExtra("task_id")?.let { ConversationHub.startOpenCode(it) }
                    REPLY -> {
                        val text=RemoteInput.getResultsFromIntent(intent)?.getCharSequence(REPLY_TEXT)?.toString().orEmpty()
                        if(text.isNotBlank()) {
                            val digest=java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
                            val id=ConversationHub.receive(applicationContext,text,"reply:${intent.getStringExtra("reply_token")}:$digest",intent.getStringExtra("question_id").orEmpty())
                            ConversationHub.process(id)
                        }
                    }
                    RESUME -> {
                        val expected=intent.getIntExtra("command_epoch",AgentPrefs(applicationContext).conversationEpoch)
                        if(expected==AgentPrefs(applicationContext).conversationEpoch) {
                            val taskId=intent.getStringExtra("task_id")
                            if(taskId!=null && !AgentRuntime.state.value.running) MemoryStore.get(applicationContext).tasks().firstOrNull { it.id==taskId }?.let { ConversationHub.refresh("Выбрана сохранённая задача",it) }
                            val id=ConversationHub.receive(applicationContext,"продолжай","control:"+(intent.getStringExtra("command_id") ?: UUID.randomUUID().toString()))
                            ConversationHub.process(id)
                        }
                    }
                    PAUSE,CANCEL -> controlId?.let { ConversationHub.process(it) }
                }
                ConversationHub.finishIfIdle()
            } catch(e:Exception) { AgentRuntime.phase("Не удалось обработать вход в диалог: ${e.javaClass.simpleName}");stopSelf() }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { ConversationHub.onIdle={};ConversationHub.shutdown();scope.cancel();super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder?=null
}
