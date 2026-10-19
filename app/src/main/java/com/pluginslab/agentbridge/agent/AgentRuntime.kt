package com.pluginslab.agentbridge.agent

import android.content.Context
import android.os.SystemClock
import com.pluginslab.agentbridge.accessibility.BridgeAccessibilityService
import com.pluginslab.agentbridge.config.Settings
import com.pluginslab.agentbridge.accessibility.NodeRegistry
import com.pluginslab.agentbridge.server.tools.LocalTools
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.security.MessageDigest
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

object AgentRuntime {
    private const val MAX_STEPS = 64
    private const val MAX_CALLS = 128

    private suspend fun readTreeReady(args: JsonObject): JsonObject {
        val started = SystemClock.elapsedRealtime()
        val active = ActivityLaunch.activePackage()
        val readiness = SnapshotReadiness()
        var actualArgs = args
        var fallback = false
        var outcome: JsonObject
        while (true) {
            outcome = LocalTools.call("get_ui_tree", actualArgs)
            val elapsed = SystemClock.elapsedRealtime() - started
            if (outcome["isError"]?.jsonPrimitive?.booleanOrNull == true || ActivityLaunch.activePackage() != active) break
            val semantics = NodeRegistry.semanticSignature(active)
            if (semantics.isBlank() && actualArgs["single_window"]?.jsonPrimitive?.booleanOrNull != true) {
                actualArgs = buildJsonObject { args.forEach { (key,value) -> put(key,value) }; put("single_window",true) }
                fallback = true
                continue
            }
            if (readiness.ready(elapsed, semantics) || readiness.expired(elapsed)) break
            delay(250)
        }
        return buildJsonObject {
            outcome.forEach { (key,value) -> put(key,value) }
            put("render_wait_ms",SystemClock.elapsedRealtime()-started)
            if (fallback) put("snapshot_window_fallback","active_window")
            if (!readiness.ready(SystemClock.elapsedRealtime()-started,NodeRegistry.semanticSignature(active)))
                put("loading_hint","За время ожидания экран не подтвердил стабильное содержимое. Не нажимай пустые блоки. Подожди agent_delay и найди подписанный элемент заново; если содержимое остаётся недоступно — сообщи об этом.")
        }
    }
    data class State(val running: Boolean = false, val phase: String = "Готов к работе", val result: String = "",
                     val log: String = "", val started: Long = 0, val finished: Long = 0)
    private val mutable = MutableStateFlow(State())
    val state: StateFlow<State> = mutable.asStateFlow()
    private var job: Job? = null
    private var store: MemoryStore?=null
    private var checkpoint: TaskCheckpoint?=null
    val gate=TaskGate()
    @Volatile private var routingHold=false
    @Volatile private var needsRevisionAck=false
    @Volatile private var requestedTerminal="PAUSED"
    fun currentTask(): TaskCheckpoint? = synchronized(gate) { checkpoint }
    private fun saveCheckpoint(value: TaskCheckpoint) = synchronized(gate) {
        val latest=checkpoint?.takeIf { it.id==value.id }
        val merged=if(latest!=null && latest.taskRevision>value.taskRevision) value.copy(taskRevision=latest.taskRevision,conditions=latest.conditions,planSummary=latest.planSummary,questionId=latest.questionId,questionText=latest.questionText) else value
        store?.checkpoint(merged);checkpoint=merged
        ConversationHub.runtimeChanged(mutable.value.phase,merged)
    }
    fun amend(text: String,messageId: String): Boolean = synchronized(gate) {
        val current=checkpoint ?: return false
        if(job?.isActive!=true || current.status in setOf("COMPLETE","CANCELLED","WAIT_USER")) return false
        require(current.conditions.length+text.length<16000) { "Условия задачи слишком длинные; нужна новая сводная задача" }
        gate.revise(current.id);routingHold=false
        val revision=gate.snapshot()!!.revision
        needsRevisionAck=true
        saveCheckpoint(current.copy(taskRevision=revision,conditions=current.conditions+"\n"+text,planSummary="",questionId="",questionText="",updated=System.currentTimeMillis()))
        store?.messageStatus(messageId,"CONDITION:$revision")
        phase("Условие получено · пересмотр плана версии $revision")
        true
    }
    fun holdForRouting() = synchronized(gate) {
        if(job?.isActive==true && checkpoint!=null && checkpoint!!.status !in setOf("COMPLETE","CANCELLED","WAIT_USER")) { routingHold=true;gate.freeze();saveCheckpoint(checkpoint!!.copy(taskRevision=gate.snapshot()!!.revision));phase("Новая реплика получена · уточняю её отношение к задаче") }
    }
    fun releaseRoutingHold() = synchronized(gate) {
        if(routingHold && checkpoint!=null) { routingHold=false;gate.release();saveCheckpoint(checkpoint!!.copy(taskRevision=gate.snapshot()!!.revision)) }
    }
    fun waitForUser(question: String): Unit = synchronized(gate) {
        val t=checkpoint ?: return
        requestedTerminal="WAIT_USER";routingHold=false;gate.pause()
        saveCheckpoint(t.copy(status="WAIT_USER",taskRevision=gate.snapshot()!!.revision,questionId=java.util.UUID.randomUUID().toString(),questionText=question,answer=""))
        job?.cancel();Unit
    }
    fun pause() { requestedTerminal="PAUSED";gate.pause();routingHold=false;job?.cancel() }
    fun cancel() { requestedTerminal="CANCELLED";gate.cancel();routingHold=false;job?.cancel();VoiceOutput.stop() }

    fun phase(text: String) {
        mutable.update { it.copy(phase=text) }
        if((text.contains("HTTP 429") || text.contains("WAIT_QUOTA")) && checkpoint?.status!="WAIT_QUOTA") checkpoint?.let {
            saveCheckpoint(it.copy(status="WAIT_QUOTA",updated=System.currentTimeMillis()))
        }
        ConversationHub.runtimeChanged(text,currentTask())
    }
    fun stop() { cancel() }
    private suspend fun waitForForeground(context: Context,version: Int): Boolean {
        val task=currentTask() ?: return false
        if(!ForegroundWaitRules.shouldWait(task,ActivityLaunch.activePackage(),context.packageName)) return false
        phase("Диалог / шторка открыт · UI-действия ждут целевой экран; разговор доступен")
        while(ForegroundWaitRules.shouldWait(task,ActivityLaunch.activePackage(),context.packageName)) {
            currentCoroutineContext().ensureActive()
            if(!gate.valid(task.id,version)) return true
            delay(200)
        }
        return true
    }
    private fun windowIdentity(includeContent: Boolean = true): String? {
        val root = BridgeAccessibilityService.instance?.rootInActiveWindow ?: return null
        val digest = MessageDigest.getInstance("SHA-256")
        var visited = 0
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (++visited > 1500 || depth > 30) return
            val bounds = Rect(); node.getBoundsInScreen(bounds)
            val value = "${node.viewIdResourceName}|${node.className}|${node.text}|${node.contentDescription}|$bounds|${node.isEnabled}|${node.isChecked}|${node.isSelected}"
            digest.update(value.toByteArray(Charsets.UTF_8))
            for (i in 0 until node.childCount) {
                if (visited >= 1500) break
                val child = node.getChild(i) ?: continue
                try { visit(child,depth+1) } finally { child.recycle() }
            }
        }
        return try {
            val prefix = "${root.packageName}|${root.windowId}|"
            if (!includeContent) return prefix
            visit(root,0)
            prefix + digest.digest().joinToString("") { "%02x".format(it) }
        } finally { root.recycle() }
    }
    @Synchronized fun start(context: Context, scope: CoroutineScope, onFinish: () -> Unit, resume: TaskCheckpoint?=null): Boolean {
        if (job?.isActive == true) return false
        mutable.value = State(running = true, phase = "Подготовка", started = SystemClock.elapsedRealtime())
        job = scope.launch(start = CoroutineStart.LAZY) {
            var apiKey = ""
            var bridgeToken = ""
            var terminalStatus="FAILED"
            checkpoint=null;routingHold=false;needsRevisionAck=resume?.conditions?.isNotBlank()==true;requestedTerminal="PAUSED"
            try {
                val prefs = AgentPrefs(context); val provider = prefs.provider; apiKey = prefs.keyFor(provider)
                bridgeToken = Settings(context).token
                require(apiKey.isNotBlank() || provider == "compatible") { "Сохраните API-ключ выбранного провайдера" }
                val model = prefs.modelFor(provider); val task = resume?.goal ?: prefs.task
                store=MemoryStore.get(context)
                gate.begin(resume?.id ?: java.util.UUID.randomUUID().toString(),resume?.taskRevision ?: 1)
                saveCheckpoint((resume ?: TaskCheckpoint(id=gate.snapshot()!!.id,goal=task,provider=provider,model=model)).copy(provider=provider,model=model,status="PLAN",answer="",updated=System.currentTimeMillis()))
                store?.event("Задача: старт · $provider / $model${if(resume!=null) " · продолжение" else ""}")
                require(model.matches(Regex("[A-Za-z0-9._:/-]{1,150}"))) { "Укажите модель провайдера" }
                if(provider=="gemini") require(model.matches(Regex("[A-Za-z0-9._-]{1,150}"))) { "Неверное имя модели Gemini" }
                if(provider=="compatible") ProviderAddress.validate(prefs.compatibleEndpoint)
                require(task.isNotBlank() && task.length <= 8000) { "Задача должна содержать от 1 до 8000 символов" }
                check(BridgeAccessibilityService.instance != null) { "Включите службу AgentBridge в спецвозможностях" }
                for (seconds in prefs.delaySeconds downTo 1) { phase("Старт через $seconds с · можно перейти в нужное приложение"); delay(1000) }
                withContext(prefs.quotaContext(false,::phase)) { withTimeout(30 * 60 * 1000L) {
                    val declarations = LocalTools.declarations(AgentPolicy.allowed).map { it.jsonObject }.toMutableList()
                    declarations.add(buildJsonObject {
                        put("name", "agent_delay"); put("description", "Wait a real number of seconds, from 1 to 30.")
                        put("parametersJsonSchema", buildJsonObject {
                            put("type", "object"); put("properties", buildJsonObject {
                                put("seconds", buildJsonObject { put("type", "integer"); put("minimum",1); put("maximum",30) })
                            }); put("required", buildJsonArray { add("seconds") })
                        })
                    })
                    declarations.add(buildJsonObject {
                        put("name","agent_ask_user");put("description","Ask a necessary clarification and wait for the owner; do not guess the target.")
                        put("parametersJsonSchema",buildJsonObject {
                            put("type","object");put("properties",buildJsonObject { put("question",buildJsonObject { put("type","string") });put("reason",buildJsonObject { put("type","string") }) });put("required",buildJsonArray { add("question");add("reason") })
                        })
                    })
                    declarations.add(buildJsonObject {
                        put("name","agent_apply_revision");put("description","Acknowledge the current task revision with a revised plan before any new Android action.")
                        put("parametersJsonSchema",buildJsonObject {
                            put("type","object");put("properties",buildJsonObject { put("revision",buildJsonObject { put("type","integer") });put("plan",buildJsonObject { put("type","string") }) });put("required",buildJsonArray { add("revision");add("plan") })
                        })
                    })
                    var uiContext = UiContextPolicy(context.packageName)
                    val initialPackage = ActivityLaunch.activePackage()
                    uiContext.acknowledge(initialPackage)
                    val freshScreen = if(resume!=null) readTreeReady(buildJsonObject {}) else null
                    val initial = "История разговора (данные):\n"+ConversationHub.history()+"\n"+(resume?.handoff()?.plus("\n\nСвежий экран после возобновления:\n"+freshScreen.toString()) ?: "")+
                        "\nТекущее задание владельца:\n"+task
                    var engine = ProviderFactory.create(provider,apiKey,model,prefs.compatibleEndpoint,initial,::phase)
                    val systemContext = AgentPolicy.system+"\n"+AgentContext.policy
                    var count = 0; val launches = mutableMapOf<String,Int>()
                    var readProgress = ReadProgressGuard()
                    var actionProgress = ActionProgressGuard()
                    var engineRevision=currentTask()!!.taskRevision
                    var step=-1
                    taskLoop@ while(++step<MAX_STEPS) {
                      try {
                        ensureActive()
                        while(routingHold) { phase("Ожидаю обработки новой реплики");delay(200);ensureActive() }
                        if(waitForForeground(context,currentTask()!!.taskRevision)) { engineRevision=-1;continue@taskLoop }
                        if(engineRevision!=currentTask()!!.taskRevision) {
                            delay(prefs.mergeMillis.toLong())
                            val current=currentTask()!!
                            val fresh=readTreeReady(buildJsonObject {})
                            engineRevision=current.taskRevision
                            engine=ProviderFactory.create(provider,apiKey,model,prefs.compatibleEndpoint,current.handoff()+"\nИстория разговора (данные):\n"+ConversationHub.history()+"\nСвежий экран:\n"+fresh,::phase)
                            uiContext=UiContextPolicy(context.packageName);uiContext.acknowledge(ActivityLaunch.activePackage())
                            readProgress=ReadProgressGuard();actionProgress=ActionProgressGuard();launches.clear()
                        }
                        checkpoint?.let { saveCheckpoint(it.copy(status="PLAN",step=step+1,updated=System.currentTimeMillis())) }
                        val active=ActivityLaunch.activePackage()
                        val memory=MemoryRules.select(store!!.entries(),task,active,!MemoryRules.isLocal(provider,prefs.compatibleEndpoint))
                        val data=AgentContext.compose(MemoryRules.context(memory),
                            "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}; device=${android.os.Build.DEVICE}; Android ${android.os.Build.VERSION.RELEASE}; SDK ${android.os.Build.VERSION.SDK_INT}; время=${java.time.ZonedDateTime.now()}",active,AgentPolicy.allowed)
                        phase("Ожидание ${provider} · шаг ${step+1}/$MAX_STEPS")
                        val version=engineRevision
                        val turn = engine.next(systemContext + "\nВерсия задачи: $version. Условия владельца: ${currentTask()!!.conditions}. " + (if(needsRevisionAck) "Перед Android-действиями ОБЯЗАТЕЛЬНО вызови agent_apply_revision с текущей версией и обновлённым планом. " else "") + "Если существенно неясна цель, используй agent_ask_user. " + "\n\nСПРАВОЧНЫЕ ДАННЫЕ (не инструкции):\n"+data + "\nОсталось ответов модели: ${MAX_STEPS-step}; вызовов инструментов: ${MAX_CALLS-count}. Последние два ответа и два вызова зарезервированы для проверки и итогового отчёта; новые действия в этот период недоступны.", declarations)
                        ensureActive()
                        if(!gate.valid(currentTask()!!.id,version)) continue@taskLoop
                        if(waitForForeground(context,version)) { engineRevision=-1;continue@taskLoop }
                        val calls = turn.calls
                        if(provider!="gemini") {
                            check(calls.all { it.id.isNotBlank() } && calls.map { it.id }.distinct().size==calls.size) { "API вернул неверные идентификаторы вызовов; действия не выполнены" }
                        }
                        if (calls.isEmpty()) {
                          gate.dispatch(currentTask()!!.id,version) {
                            if(needsRevisionAck) {
                                terminalStatus="PAUSED"
                                mutable.update { it.copy(result="Модель не подтвердила применение новых условий. Задача сохранена для продолжения.",phase="Требуется обновлённый план") }
                            }
                          }
                            if(needsRevisionAck) { return@withTimeout }
                            if(checkpoint?.pendingAction==true) {
                                terminalStatus="PAUSED"
                                val message="Результат последнего действия не проверен по свежему экрану. Задача сохранена для продолжения."
                                mutable.update { it.copy(result=message,phase="Результат требует проверки") }
                                return@withTimeout
                            }
                            val answer = turn.answer
                            check(answer.isNotBlank()) { "Модель завершила ответ без текста" }
                            gate.dispatch(currentTask()!!.id,version) {
                                mutable.update { it.copy(result=AgentPolicy.safeLog(answer,apiKey,bridgeToken,32000),phase="Задача завершена") }
                                terminalStatus="COMPLETE"
                                checkpoint?.let { saveCheckpoint(it.copy(status="COMPLETE",answer=AgentPolicy.safeLog(answer,apiKey,bridgeToken,12000),updated=System.currentTimeMillis())) }
                            }
                            store?.event("Задача: модель завершила ответ · результат требует проверки по инструментам")
                            return@withTimeout
                        }
                        val replies = mutableListOf<AgentReply>()
                        for (call in calls) {
                            ensureActive()
                            if(!gate.valid(currentTask()!!.id,version)) continue@taskLoop
                            check(++count <= MAX_CALLS) { "Достигнут предел $MAX_CALLS вызовов инструментов. Результат последнего действия проверьте в журнале." }
                            val name = call.name
                            check(name in AgentPolicy.allowed) { "Недоступный инструмент: $name" }
                            val args = call.args
                            if(name=="agent_ask_user") {
                                val question=args["question"]?.jsonPrimitive?.contentOrNull.orEmpty().take(2000)
                                require(question.isNotBlank()) { "Пустой вопрос модели" }
                                terminalStatus="WAIT_USER"
                                gate.dispatch(currentTask()!!.id,version) {
                                    saveCheckpoint(currentTask()!!.copy(status="WAIT_USER",questionId=java.util.UUID.randomUUID().toString(),questionText=question,answer="",updated=System.currentTimeMillis()))
                                }
                                mutable.update { it.copy(phase="Нужен ответ владельца",result=question) }
                                return@withTimeout
                            }
                            if(name=="agent_apply_revision") {
                                val acknowledged=args["revision"]?.jsonPrimitive?.intOrNull
                                val plan=args["plan"]?.jsonPrimitive?.contentOrNull.orEmpty().take(2000)
                                check(acknowledged==version && plan.isNotBlank()) { "Модель не применила текущую версию условий" }
                                var applied=false
                                gate.dispatch(currentTask()!!.id,version) {
                                    applied=needsRevisionAck
                                    saveCheckpoint(currentTask()!!.copy(planSummary=plan));needsRevisionAck=false
                                }
                                if(applied) ConversationHub.conditionApplied(version)
                                replies.add(AgentReply(call,buildJsonObject { put("applied_revision",version) }))
                                continue
                            }
                            if (name == "launch_app") {
                                val pkg = args["package"]?.jsonPrimitive?.content ?: ""
                                launches[pkg] = (launches[pkg] ?: 0) + 1
                                check(launches.getValue(pkg) <= 2) { "Остановлено повторное открытие одного приложения" }
                            }
                            val activeBefore = if (name in UiContextPolicy.inspections) ActivityLaunch.activePackage() else null
                            var rejected = uiContext.reject(name, args, activeBefore)
                            if(needsRevisionAck && name in AgentPolicy.actions) rejected=buildJsonObject {
                                put("isError",true);put("message","Сначала примени актуальные условия через agent_apply_revision. Действие не выполнено.")
                            }
                            if (name in AgentPolicy.actions && (step >= MAX_STEPS-2 || count > MAX_CALLS-2)) {
                                rejected = buildJsonObject {
                                    put("isError",true)
                                    put("message","Бюджет действий исчерпан. Новое действие не выполнялось. Проверь уже полученный результат чтением и дай честный итог; если задача не выполнена, сообщи, где остановился.")
                                }
                            }
                            val targetId = args["node_id"]?.jsonPrimitive?.intOrNull
                            if(rejected == null && name in setOf("tap_node","long_press_node","type_text","clear_text") && targetId != null) {
                                NodeRegistry.targetError(targetId, requireNamedTarget = name in setOf("tap_node","long_press_node"))?.let { reason ->
                                    rejected = buildJsonObject {
                                        put("isError",true);put("content",buildJsonArray { add(buildJsonObject { put("text",reason) }) })
                                    }
                                }
                            }
                            val tapIdentity = if(name == "tap_node" && targetId != null) NodeRegistry.targetIdentity(targetId) else null
                            val actionScreen = if(name in AgentPolicy.actions && rejected == null) windowIdentity() else null
                            if (rejected == null && name == "tap_node" && actionProgress.repeatedUnchanged(tapIdentity,actionScreen)) {
                                rejected = buildJsonObject {
                                    put("isError",true)
                                    put("message","Повторное нажатие отклонено: та же кнопка на неизменившемся экране. Предыдущий action_click подтвердил только отправку события, а не результат. Подожди загрузки и проверь get_ui_tree с single_window=true и max_depth=40: диалог может отсутствовать в списке окон. Если результата всё равно не видно, не нажимай снова и сообщи о неопределённом результате.")
                                }
                            }
                            if (name in AgentPolicy.actions && rejected == null) {
                                val dependsOnUi = name !in setOf("launch_app", "open_url", "global_action")
                                val before = windowIdentity(dependsOnUi) ?: error("Нет активного окна; действие не выполнено")
                                check(!before.startsWith(context.packageName + "|") || name in setOf("launch_app", "open_url", "global_action")) {
                                    "Панель AgentBridge активна. Перейдите в нужное приложение и запустите задачу с задержкой."
                                }
                                ensureActive()
                                check(before == windowIdentity(dependsOnUi)) { "Активное окно изменилось. Действие не выполнено; запустите задачу заново." }
                            }
                            checkpoint?.let { saveCheckpoint(CheckpointRules.before(it,name,name in AgentPolicy.actions && rejected==null,ActivityLaunch.activePackage().orEmpty())) }
                            store?.event("Вызов: $name${if(rejected!=null) " · отклонён проверкой" else ""}")
                            phase("Выполнение: $name")
                            ensureActive()
                            if(name in AgentPolicy.actions && rejected==null) check(actionScreen==windowIdentity()) { "Экран изменился во время сохранения этапа; действие не выполнено" }
                            val started = SystemClock.elapsedRealtime()
                            var outcome = rejected ?: if (name == "agent_delay") {
                                val seconds = args["seconds"]?.jsonPrimitive?.intOrNull ?: error("Не задана задержка")
                                require(seconds in 1..30) { "Задержка должна быть от 1 до 30 секунд" }
                                delay(seconds*1000L); buildJsonObject { put("waited_seconds",seconds) }
                            } else if (name == "get_ui_tree") readTreeReady(args) else {
                                if(name in AgentPolicy.actions) {
                                    val first=java.util.concurrent.atomic.AtomicBoolean(true)
                                    val permit=ActionDispatch.Permit(gate,currentTask()!!.id,version) {
                                        name in AgentPolicy.allowed && (!first.compareAndSet(true,false) || (actionScreen==windowIdentity() && (targetId==null || NodeRegistry.targetError(targetId,requireNamedTarget=name in setOf("tap_node","long_press_node"))==null)))
                                    }
                                    try { ActionDispatch.withPermit(permit) { LocalTools.call(name,args) } }
                                    catch(e:IllegalStateException) {
                                        if(e.message?.startsWith("STALE_TASK")==true || e.message?.startsWith("STALE_UI")==true) continue@taskLoop else throw e
                                    }
                                } else LocalTools.call(name,args)
                            }
                            ensureActive()
                            if(rejected == null && name in AgentPolicy.actions) {
                                if(name == "tap_node" && outcome["isError"]?.jsonPrimitive?.booleanOrNull != true)
                                    actionProgress.record(tapIdentity,actionScreen)
                                else actionProgress.clear()
                            }
                            if (rejected == null && outcome["isError"]?.jsonPrimitive?.booleanOrNull != true) {
                                if (name in setOf("launch_app", "get_active_window_info", "wait_for_window")) {
                                    uiContext.acknowledge(ActivityLaunch.activePackage())
                                }
                                if (name in UiContextPolicy.snapshots && activeBefore != null && activeBefore == ActivityLaunch.activePackage()) {
                                    uiContext.acknowledge(activeBefore)
                                    // Read only actual AccessibilityNodeInfo.viewIdResourceName values.
                                    // A resource ID written inside a TextView or the task is not evidence.
                                    uiContext.observe(activeBefore, NodeRegistry.resourceIds())
                                }
                            }
                            var repeatedRead = 0
                            if(name in AgentPolicy.reads && name != "agent_delay") {
                                val active = ActivityLaunch.activePackage()
                                val screen = windowIdentity(includeContent = active != context.packageName && name != "list_apps")
                                repeatedRead = readProgress.record(name,args,outcome,screen)
                            }
                            val hidden = if(rejected == null && name in UiContextPolicy.snapshots && activeBefore != null)
                                NodeRegistry.hiddenEditableHints(activeBefore) else JsonArray(emptyList())
                            if(hidden.isNotEmpty() || repeatedRead >= 2) {
                                outcome = buildJsonObject {
                                    outcome.forEach { (key,value) -> put(key,value) }
                                    if(hidden.isNotEmpty()) put("hidden_editable_fields",hidden)
                                    put("recovery_hint", "Не повторяй одинаковый поиск без изменения экрана. Сначала определи, не открыт ли отдельный объект. Если поле above_or_collapsed — раскрой верхнюю панель одним свайпом пальцем сверху вниз в центре списка; затем найди видимый editable-узел. Если нужно вернуться внутри приложения, предпочитай видимую кнопку «Перейти вверх»/«Назад». Не нажимай скрытые узлы и не угадывай координаты.")
                                }
                            }
                            val observedPackage=ActivityLaunch.activePackage()
                            if(rejected==null && name in setOf("get_ui_tree","get_active_window_info","launch_app") && outcome["isError"]?.jsonPrimitive?.booleanOrNull!=true) {
                                observedPackage?.takeIf { it!=context.packageName }?.let { store?.observeApp(it) }
                            }
                            checkpoint?.let { saveCheckpoint(CheckpointRules.after(it,name,observedPackage.orEmpty(),
                                outcome["isError"]?.jsonPrimitive?.booleanOrNull==true || rejected!=null,
                                name=="get_ui_tree" && outcome["loading_hint"]==null && observedPackage!=null && NodeRegistry.semanticSignature(observedPackage).isNotBlank())) }
                            store?.event("Ответ: $name · ${if(outcome["isError"]?.jsonPrimitive?.booleanOrNull==true) "ошибка" else "получен"}")
                            val entry = buildJsonObject {
                                put("tool",name);put("arguments",args);put("duration_ms",SystemClock.elapsedRealtime()-started);put("result",outcome)
                                activeBefore?.let { put("active_package",it) }
                                if (rejected != null) put("guard_rejected",true)
                            }
                            val safe = AgentPolicy.safeLog(entry.toString(),apiKey,bridgeToken)
                            mutable.update { it.copy(log = (it.log + (if (it.log.isEmpty()) "" else "\n\n") + safe).takeLast(400000)) }
                            check(repeatedRead <= 4) { "Задача остановлена: агент повторяет ту же проверку на неизменившемся экране. Подробности — в журнале." }
                            if (name == "launch_app" && outcome["isError"]?.jsonPrimitive?.booleanOrNull == true) {
                                error("Запуск приложения не подтверждён: оно не стало активным. Подробности — в журнале.")
                            }
                            val bounded = if (outcome.toString().length > 60000) buildJsonObject {
                                put("truncated",true); put("text",outcome.toString().take(60000))
                            } else outcome
                            replies.add(AgentReply(call,bounded))
                        }
                        engine.accept(replies)
                        if (step == MAX_STEPS-1) error("Достигнут предел $MAX_STEPS шагов модели. Задача не подтверждена как завершённая; уже выполненные действия не отменяются. Подробности — в журнале.")
                      } catch(e:StaleTaskException) { continue@taskLoop }
                    }
                } }
            } catch(e:QuotaWaitException) {
                terminalStatus="WAIT_QUOTA"
                mutable.update { it.copy(phase="Ожидание квоты · задача сохранена",result=e.message.orEmpty()) }
            } catch (e: TimeoutCancellationException) {
                terminalStatus="PAUSED"
                mutable.update { it.copy(phase = "Задача остановлена по времени", result = "Истекло время задачи. Новые действия не выполняются.") }
            } catch (e: CancellationException) {
                terminalStatus=requestedTerminal
                mutable.update { it.copy(phase=when(terminalStatus) { "CANCELLED"->"Задача отменена";"WAIT_USER"->"Нужен ответ владельца";else->"Пауза" },result=when(terminalStatus) { "CANCELLED"->"Задача отменена. Уже выполненные действия не отменяются.";"WAIT_USER"->currentTask()?.questionText.orEmpty();else->"Задача на паузе. Можно продолжить с текущего экрана." }) }
            } catch (e: Exception) {
                val message = when (e) {
                    is java.net.SocketTimeoutException -> "Время ожидания сети истекло. Проверьте подключение."
                    is java.io.IOException -> "Ошибка сети: ${e.javaClass.simpleName}"
                    is kotlinx.serialization.SerializationException -> "Модель вернула некорректный JSON. Действия не выполнены для этого ответа."
                    is ProviderHttpFailure -> e.message ?: "Ошибка API"
                    is IllegalStateException, is IllegalArgumentException -> e.message ?: "Ошибка задачи"
                    else -> "Ошибка задачи: ${e.javaClass.simpleName}"
                }
                mutable.update { it.copy(phase = "Задача завершилась с ошибкой", result = AgentPolicy.safeLog(message,apiKey,bridgeToken)) }
            } finally {
                // Persist even after cancellation; storage failure must not keep the service alive.
                try {
                    checkpoint?.takeIf { it.status !in setOf("COMPLETE","WAIT_USER") }?.let {
                        saveCheckpoint(it.copy(status=terminalStatus,answer=AgentPolicy.safeLog(mutable.value.result,apiKey,bridgeToken,12000),updated=System.currentTimeMillis()))
                    }
                    store?.event("Задача: $terminalStatus")
                } catch (_: Exception) { mutable.update { it.copy(result=it.result+"\nНе удалось сохранить контрольную точку.") } }
                apiKey = ""; bridgeToken = ""
                mutable.update { it.copy(running = false, finished = SystemClock.elapsedRealtime()) }
                withContext(NonCancellable + Dispatchers.Main) { onFinish() }
            }
        }
        job!!.start(); return true
    }
}
