package com.pluginslab.agentbridge.agent

object CheckpointRules {
    fun before(task: TaskCheckpoint,tool: String,action: Boolean,pkg: String): TaskCheckpoint = task.copy(
        status=if(action) "ACT" else "VERIFY", lastTool=tool,lastAction=if(action) tool else task.lastAction,
        pendingAction=task.pendingAction || action,activePackage=pkg,
        observation=if(action) "Сохранено перед вызовом; конечный эффект ещё не проверен" else task.observation,
        updated=System.currentTimeMillis()
    )
    fun after(task: TaskCheckpoint,tool: String,pkg: String,error: Boolean,freshUi: Boolean): TaskCheckpoint = task.copy(
        status="VERIFY",activePackage=pkg,pendingAction=if(!error && freshUi) false else task.pendingAction,
        observation="Инструмент $tool: ${if(error) "ошибка" else "ответ получен"}. ${if(freshUi && !error) "Свежий экран получен; смысл результата оценивает модель." else "Конечный эффект пока не подтверждён экраном."}",
        updated=System.currentTimeMillis()
    )
}
