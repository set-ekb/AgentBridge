package com.pluginslab.agentbridge.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.pluginslab.agentbridge.SettingsActivity
import com.pluginslab.agentbridge.accessibility.BridgeAccessibilityService
import kotlinx.coroutines.*
import java.util.Locale

/** Four stable surfaces. Model configuration never shares a scrolling page with the task. */
open class PanelActivity: AppCompatActivity() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private lateinit var prefs: AgentPrefs
    private lateinit var store: MemoryStore
    private lateinit var pages: FrameLayout
    private val pageViews=mutableListOf<View>()
    private lateinit var column: LinearLayout
    private lateinit var taskInput: EditText
    private lateinit var result: TextView
    private lateinit var status: TextView
    private lateinit var modelLabel: TextView
    private lateinit var stop: Button
    private lateinit var start: Button
    private lateinit var mic: Button
    private lateinit var pauseButton: Button
    private lateinit var conversationList: LinearLayout
    private lateinit var conversationScroll: ScrollView
    private var renderedMessages: List<ConversationMessage> = emptyList()
    private var sending=false
    private lateinit var checkpoints: LinearLayout
    private lateinit var memoryList: LinearLayout
    private lateinit var memorySearch: EditText
    private lateinit var journal: TextView
    private lateinit var history: TextView
    private lateinit var appEventHistory: TextView
    private lateinit var provider: Spinner
    private lateinit var key: EditText
    private lateinit var keyStatus: TextView
    private lateinit var model: EditText
    private lateinit var endpoint: EditText
    private lateinit var delay: EditText
    private lateinit var pitch: EditText
    private lateinit var rate: EditText
    private lateinit var speak: CheckBox
    private var selection="gemini"
    private var activeTab=0
    private var launching=false
    private var operation: Job?=null
    private var exportText=""
    private val editable=mutableListOf<View>()
    private val ids=arrayOf("gemini","openai","anthropic","compatible")
    private val labels=arrayOf("Google Gemini","OpenAI","Anthropic Claude","Совместимый API")
    private val navy=Color.rgb(7,13,9)
    private val white=Color.rgb(238,245,252)
    private val mint=Color.rgb(142,211,159)
    private fun dp(n: Int)=(n*resources.displayMetrics.density).toInt()
    private val speech=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if(r.resultCode==RESULT_OK) {
            val text=r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
            if(!text.isNullOrBlank()) { taskInput.setText(text);startTask() } else feedback("Речь не распознана")
        }
    }
    private val importFile=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) action {
            val parsed=withContext(Dispatchers.IO) {
                val bytes=contentResolver.openInputStream(uri)?.use { stream ->
                    val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096)
                    while(out.size()<=256000) { val n=stream.read(buffer,0,minOf(buffer.size,256001-out.size()));if(n<0) break;out.write(buffer,0,n) }
                    out.toByteArray()
                } ?: error("Не удалось открыть файл")
                require(bytes.size<=256000) { "Импорт до 256 КБ" }
                MemoryRules.parseImport(String(bytes,Charsets.UTF_8))
            }
            android.app.AlertDialog.Builder(this@PanelActivity).setTitle("Импорт: ${parsed.size} записей")
                .setMessage("Записи будут непроверенными и недоступными облачным моделям до изменения в редакторе.\n\n"+parsed.take(3).joinToString("\n\n") { it.title+"\n"+it.content.take(400) })
                .setPositiveButton("Добавить в память") { _,_-> action { withContext(Dispatchers.IO) { store.import(parsed) };refreshMemory() } }
                .setNegativeButton("Отмена",null).show()
        }
    }
    private val exportFile=registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if(uri!=null) action {
            withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri,"wt")?.use { it.write(exportText.toByteArray(Charsets.UTF_8)) } ?: error("Не удалось записать файл") }
            exportText="";feedback("Память экспортирована")
        } else exportText=""
    }
    private val exportDiagnostics = registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson")) { uri ->
        if (uri != null) action {
            withContext(Dispatchers.IO) {
                val text = Diagnostics.export(applicationContext)
                contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    ?: error("Не удалось сохранить диагностику")
            }
            feedback("Диагностика экспортирована")
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide();window.statusBarColor=navy;window.navigationBarColor=navy
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        savedInstanceStateTask=savedInstanceState?.getString("draft")
        Diagnostics.init(this)
        prefs=AgentPrefs(this)
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(navy);setPadding(dp(12),dp(8),dp(12),dp(8)) }
        column=root
        label("AGENT / BRIDGE  0.8.1",13,mint).apply { typeface=android.graphics.Typeface.MONOSPACE }
        modelLabel=label("",11)
        status=label("Открываю разговор…",12,mint).apply { typeface=android.graphics.Typeface.MONOSPACE }
        val controls=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        fun control(text: String,action: ()->Unit)=Button(this).apply {
            this.text=text;isAllCaps=false;textSize=13f;minHeight=dp(48);setTextColor(mint)
            backgroundTintList=android.content.res.ColorStateList.valueOf(Color.rgb(20,35,25))
            controls.addView(this,LinearLayout.LayoutParams(0,-2,1f));setOnClickListener { action() }
        }
        pauseButton=control("Пауза") {
            if(AgentRuntime.state.value.running) { ConversationHub.localPause(this);AgentService.command(this,AgentService.PAUSE) }
            else if(ConversationHub.currentTask()?.status=="WAIT_USER") taskInput.requestFocus()
            else AgentService.command(this,AgentService.RESUME)
        }
        stop=control("Стоп") { operation?.cancel();ConversationHub.localCancel(this);AgentService.command(this,AgentService.CANCEL) }
        root.addView(controls);stop.visibility=View.GONE;pauseButton.visibility=View.GONE
        pages=FrameLayout(this);root.addView(pages,LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
        // Database/migration and decryption run off the UI thread; fail visibly rather than silently resetting.
        scope.launch {
            try { store=withContext(Dispatchers.IO) { val db=MemoryStore.get(applicationContext);ConversationHub.init(applicationContext);db };buildPages(root);showTab(0)
                if(intent.getBooleanExtra("microphone",false) && savedInstanceState==null) { intent.removeExtra("microphone");delay(250);listen() } }
            catch(e: Exception) { status.text="Не удалось открыть память: ${e.javaClass.simpleName}. Сохранённые данные не сброшены." }
        }
    }
    private fun buildPages(root: LinearLayout) {
        fun page(build: ()->Unit) {
            val scroll=ScrollView(this).apply { isFillViewport=true }
            column=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(6),dp(6),dp(6),dp(20)) }
            scroll.addView(column);pages.addView(scroll);pageViews.add(scroll);build()
        }
        agentPage()
        page {
            label("Память",27,white)
            label("До 1000 записей доступны всем моделям. В запрос попадают только подходящие подтверждённые знания. Состояние экрана нужно проверять заново.",13)
            memorySearch=input("Поиск по всем записям")
            button("Найти / обновить") { refreshMemory() }
            button("Добавить знание",true) { editMemory(null) }
            button("Импорт .md / .txt / .json") { importFile.launch(arrayOf("text/*","application/json","application/octet-stream")) }
            button("Экспорт памяти в JSON") { action {
                exportText=withContext(Dispatchers.IO) { MemoryRules.export(store.entries()) }
                exportFile.launch("agentbridge-memory.json")
            } }
            label("Экспорт исключает помеченные личные и локальные записи, а также распознанные секреты. Перед передачей файла просмотрите его содержимое.",12)
            memoryList=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL };column.addView(memoryList)
        }
        page {
            label("Настройки",27,white)
            label("Модели",21,white)
            selection=prefs.provider
            provider=Spinner(this).apply { adapter=ArrayAdapter(this@PanelActivity,android.R.layout.simple_spinner_dropdown_item,labels) }
            column.addView(provider,LinearLayout.LayoutParams(-1,dp(54)));editable.add(provider)
            keyStatus=label("",13)
            key=input("Новый API-ключ",password=true)
            model=input("Идентификатор модели")
            endpoint=input("Полный URL /chat/completions")
            provider.setSelection(ids.indexOf(selection).coerceAtLeast(0))
            updateProviderUi()
            provider.onItemSelectedListener=object: AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {}
                override fun onItemSelected(parent: AdapterView<*>?,view: View?,position: Int,id: Long) {
                    val next=ids[position]
                    if(next!=selection) {
                        try { saveProvider();selection=next;prefs.provider=next;updateProviderUi() }
                        catch(e: Exception) { feedback(e.message ?: "Не удалось сохранить профиль");provider.setSelection(ids.indexOf(selection)) }
                    }
                }
            }
            button("Сохранить профиль") { try { saveProvider();updateProviderUi();feedback("Профиль сохранён") } catch(e:Exception) { feedback(e.message ?: "Ошибка сохранения") } }.also { editable.add(it) }
            button("Загрузить модели Gemini") { network {
                saveProvider();require(selection=="gemini") { "Для этого провайдера укажите модель вручную" }
                val list=GeminiApi(prefs.keyFor(selection)).models()
                android.app.AlertDialog.Builder(this@PanelActivity).setTitle("Модель Gemini").setItems(list.toTypedArray()) { _,i -> model.setText(list[i]);prefs.setModelFor("gemini",list[i]) }.setNegativeButton("Закрыть",null).show()
            } }.also { editable.add(it) }
            button("Проверить соединение · без действий") { network {
                saveProvider();validateProvider()
                val id=selection
                val answer=ProviderFactory.create(id,prefs.keyFor(id),prefs.modelFor(id),prefs.compatibleEndpoint,"Ответь одним словом: ОК. Инструменты не вызывай.") { text->runOnUiThread { status.text=text } }.next("Тест API, ответь кратко.",emptyList())
                check(answer.calls.isEmpty() && answer.answer.isNotBlank()) { "Не получен текстовый ответ" }
                feedback("Соединение с $id работает")
            } }.also { editable.add(it) }
            label("Ключи шифруются отдельно. API оплачивается отдельно от подписок. Экран и разрешённая память передаются выбранному провайдеру; автоматического перехода к другому провайдеру нет.",12)
            label("Очередь API",21,white)
            label("Квоты задаются для выбранной модели и группы проекта. Ноль означает неизвестный лимит; серверные ограничения действуют всегда. TPM оценивается консервативно до ответа, RPD — по скользящим 24 часам.",12)
            button("Настроить квоты выбранной модели") { editQuota() }.also { editable.add(it) }
            label("Голос",21,white)
            speak=CheckBox(this).apply { text="Озвучивать итоги и ошибки";isChecked=prefs.speakResults;setTextColor(white) };column.addView(speak);editable.add(speak)
            pitch=input("Высота голоса · 0.5–2.0").apply { setText(prefs.speechPitch.toString()) }
            rate=input("Скорость речи · 0.5–2.0").apply { setText(prefs.speechRate.toString()) }
            label("Русский голос · системная служба Android. Распознавание может использовать сеть; непрерывного прослушивания нет.",12)
            button("Остановить только озвучивание") { VoiceOutput.stop() }
            val events=CheckBox(this).apply { text="Озвучивать значимые события";isChecked=prefs.speakEvents;setTextColor(white);setOnCheckedChangeListener { _,checked->prefs.speakEvents=checked } };column.addView(events)
            label("Виджет",21,white)
            button("Добавить виджет на рабочий стол") {
                val manager=android.appwidget.AppWidgetManager.getInstance(this)
                if(manager.isRequestPinAppWidgetSupported) manager.requestPinAppWidget(android.content.ComponentName(this,ConversationWidget::class.java),null,null)
                else feedback("Добавьте AgentBridge через меню виджетов вашего рабочего стола")
            }
            val privacy=CheckBox(this).apply { text="Скрывать реплики в виджете и уведомлении";isChecked=prefs.widgetPrivate;setTextColor(white);setOnCheckedChangeListener { _,checked->prefs.widgetPrivate=checked;ConversationWidget.refreshAsync(this@PanelActivity,true) } };column.addView(privacy)
            button("Очистить сохранённый разговор") { action {
                check(!AgentRuntime.state.value.running && !ConversationHub.state.value.routing) { "Сначала остановите работу" }
                withContext(Dispatchers.IO) { store.clearConversation();ConversationHub.refresh() }
            } }
            label("Bridge и выполнение",21,white)
            delay=input("Задержка старта · 0–30 секунд").apply { inputType=InputType.TYPE_CLASS_NUMBER;setText(prefs.delaySeconds.toString()) }
            button("Сохранить настройки голоса и задержки") { try { saveVoice();feedback("Настройки сохранены") } catch(e:Exception) { feedback(e.message ?: "Проверьте значения") } }.also { editable.add(it) }
            button("Включить спецвозможности") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            button("Внешний MCP / настройки Bridge") { startActivity(Intent(this,SettingsActivity::class.java)) }
            button("Настройки синтеза речи Android") { runCatching { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }.onFailure { feedback("Раздел синтеза речи недоступен") } }
            label("Обычные действия по задаче выполняются автоматически. Стоп доступен вверху и в уведомлении. Качество результата проверяется по свежему состоянию интерфейса.",12)
        }
        page {
            label("Журнал",27,white)
            label("События интерфейса Android / Vector",17,white)
            appEventHistory=label("Нажмите «Обновить события».",12).apply { setTextIsSelectable(true) }
            button("Обновить события") { refreshAppEvents() }
            label("Vector-подсказки не подтверждают выполнение действия. Агент дополнительно проверяет экран.",12)
            button("Экспорт диагностики (.jsonl)") { exportDiagnostics.launch("agentbridge-diagnostics.jsonl") }
            label("Экспортируются только технические события без содержимого экранов, запросов и API-ключей. Проверьте файл перед передачей.",12)
            button("История задач · показать / скрыть") { checkpoints.visibility=if(checkpoints.visibility==View.VISIBLE) View.GONE else View.VISIBLE;refreshTasks() }
            checkpoints=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;visibility=View.GONE };column.addView(checkpoints)
            label("Сохранённые технические события · без текстов экранов",13)
            history=label("",12).apply { setTextIsSelectable(true) }
            button("Обновить журнал") { refreshHistory() }
            label("Подробности текущей задачи · только в памяти процесса",16,white)
            journal=label("",12).apply { typeface=android.graphics.Typeface.MONOSPACE;setTextIsSelectable(true) }
            button("Удалить историю задач и событий") { action {
                check(!AgentRuntime.state.value.running) { "Сначала остановите задачу" }
                withContext(Dispatchers.IO) { store.clearHistory() };refreshTasks();refreshHistory()
            } }
        }
        val nav=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        listOf("Агент","Память","Настройки","Журнал").forEachIndexed { i,title ->
            val b=Button(this).apply { text=title;isAllCaps=false;textSize=12f;setTextColor(white);backgroundTintList=android.content.res.ColorStateList.valueOf(Color.rgb(20,35,25));setPadding(0,dp(8),0,dp(8));setOnClickListener { showTab(i) } }
            nav.addView(b,LinearLayout.LayoutParams(0,-2,1f))
        }
        if(!intent.getBooleanExtra("compact",false)) root.addView(nav)
        scope.launch { AgentRuntime.state.collect { render(it) } }
        scope.launch { ConversationHub.state.collect { renderConversation(it);render(AgentRuntime.state.value) } }
        refreshTasks();refreshMemory();refreshHistory()
    }
    private fun agentPage() {
        val page=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        pages.addView(page);pageViews.add(page)
        conversationScroll=ScrollView(this).apply { isFillViewport=true }
        conversationList=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(4),dp(6),dp(4),dp(8)) }
        conversationScroll.addView(conversationList);page.addView(conversationScroll,LinearLayout.LayoutParams(-1,0,1f))
        column=page
        result=TextView(this)
        val composer=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(4),dp(3),dp(4),0) }
        page.addView(composer)
        column=composer
        taskInput=input("Напиши или скажи…",multi=true).apply { minLines=1;maxLines=5;setText(savedInstanceStateTask ?: prefs.task) }
        val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL };composer.addView(row)
        mic=Button(this).apply { text="MIC";contentDescription="Сказать реплику";isAllCaps=false;setTextColor(mint);backgroundTintList=android.content.res.ColorStateList.valueOf(Color.rgb(20,35,25));setOnClickListener { listen() } }
        start=Button(this).apply { text="Отправить ↵";isAllCaps=false;setTextColor(navy);backgroundTintList=android.content.res.ColorStateList.valueOf(mint);setOnClickListener { startTask() } }
        row.addView(mic,LinearLayout.LayoutParams(0,dp(50),1f));row.addView(start,LinearLayout.LayoutParams(0,dp(50),2f))
    }
    private fun renderConversation(view: ConversationHub.View) {
        if(!::conversationList.isInitialized) return
        status.text=view.phase
        if(renderedMessages==view.messages) return
        renderedMessages=view.messages
        val wasNearBottom=conversationScroll.getChildAt(0)?.let { it.height-(conversationScroll.height+conversationScroll.scrollY)<dp(160) } ?: true
        conversationList.removeAllViews()
        view.messages.takeLast(80).forEach { message ->
            val card=LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(8),dp(12),dp(10))
                background=android.graphics.drawable.GradientDrawable().apply { setColor(if(message.role=="user") Color.rgb(15,29,20) else Color.rgb(9,18,12));cornerRadius=dp(9).toFloat();setStroke(dp(1),Color.rgb(35,61,43)) }
            }
            card.addView(TextView(this).apply { text=if(message.role=="user") "> владелец" else "агент";textSize=11f;setTextColor(mint);typeface=android.graphics.Typeface.MONOSPACE })
            card.addView(TextView(this).apply { text=message.text;textSize=16f;setTextColor(white);setTextIsSelectable(true);setPadding(0,dp(4),0,0) })
            if(message.role=="user") card.addView(TextView(this).apply {
                text=when { message.status.startsWith("CONDITION:") -> "получено · ожидает применения";message.status=="APPLIED" -> "применено к плану";message.status in setOf("RECEIVED","PROCESSING") -> "получено · обрабатывается";message.status=="ERROR" -> "ошибка обработки";else -> "сохранено" }
                textSize=10f;setTextColor(Color.rgb(126,157,135));setPadding(0,dp(4),0,0)
            })
            conversationList.addView(card,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(9) })
        }
        if(view.messages.isEmpty()) conversationList.addView(TextView(this).apply { text="Обсудим или сделаем.\nМожно дополнять задачу по ходу работы.";setTextColor(Color.rgb(154,179,161));textSize=16f;setPadding(dp(10),dp(18),dp(10),dp(8)) })
        if(wasNearBottom) conversationScroll.post { conversationScroll.fullScroll(View.FOCUS_DOWN) }
    }
    private var savedInstanceStateTask: String?=null
    override fun onSaveInstanceState(outState: Bundle) { if(::taskInput.isInitialized) outState.putString("draft",taskInput.text.toString());super.onSaveInstanceState(outState) }
    private fun showTab(index: Int) {
        activeTab=index;pageViews.forEachIndexed { i,v -> v.visibility=if(i==index) View.VISIBLE else View.GONE }
        if(index==0) refreshTasks();if(index==1) refreshMemory();if(index==3) refreshHistory()
    }
    private fun network(block: suspend ()->Unit) {
        if(AgentRuntime.state.value.running || launching || operation?.isActive==true) return
        operation=scope.launch(start=CoroutineStart.LAZY) {
            try { render(AgentRuntime.state.value);saveProvider();withContext(prefs.quotaContext(true) { text->runOnUiThread { status.text=text } }) { block() } }
            catch(e:CancellationException) { status.text="Запрос остановлен" }
            catch(e:Exception) { feedback(e.message?.take(220) ?: "Ошибка соединения") }
            finally { operation=null;render(AgentRuntime.state.value) }
        }
        operation!!.start()
    }
    private fun action(block: suspend ()->Unit) {
        scope.launch { try { block() } catch(e:CancellationException) { throw e } catch(e:Exception) { feedback(e.message?.take(220) ?: "Ошибка: ${e.javaClass.simpleName}") } }
    }
    private fun label(text: String,size: Int,color: Int=Color.rgb(154,179,161)): TextView = TextView(this).apply {
        this.text=text;textSize=size.toFloat();setTextColor(color);setPadding(0,dp(7),0,dp(5));column.addView(this,LinearLayout.LayoutParams(-1,-2))
    }
    private fun input(hint: String,password: Boolean=false,multi: Boolean=false): EditText = EditText(this).apply {
        this.hint=hint;setTextColor(white);setHintTextColor(Color.rgb(103,132,111));textSize=15f
        inputType=InputType.TYPE_CLASS_TEXT or if(password) InputType.TYPE_TEXT_VARIATION_PASSWORD else if(multi) InputType.TYPE_TEXT_FLAG_MULTI_LINE else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setPadding(dp(10),dp(10),dp(10),dp(10));isSaveEnabled=false
        column.addView(this,LinearLayout.LayoutParams(-1,-2));editable.add(this)
    }
    private fun button(text: String,accent: Boolean=false,action: ()->Unit): Button = Button(this).apply {
        this.text=text;isAllCaps=false;textSize=15f;minHeight=dp(50);setTextColor(if(accent) navy else white)
        backgroundTintList=android.content.res.ColorStateList.valueOf(if(accent) mint else Color.rgb(20,35,25))
        column.addView(this,LinearLayout.LayoutParams(-1,-2));setOnClickListener { action() }
    }
    private fun feedback(text: String) { Toast.makeText(this,text,Toast.LENGTH_LONG).show() }
    private fun updateProviderUi() {
        model.setText(prefs.modelFor(selection));key.text.clear();endpoint.setText(prefs.compatibleEndpoint)
        endpoint.visibility=if(selection=="compatible") View.VISIBLE else View.GONE
        keyStatus.text=try { if(prefs.keyFor(selection).isNotBlank()) "Ключ сохранён; поле нужно для замены" else "Ключ не задан" } catch(_:Exception) { "Ключ недоступен — сохраните заново" }
        if(::modelLabel.isInitialized) modelLabel.text="${labels[ids.indexOf(selection)]} · ${prefs.modelFor(selection).ifBlank { "выберите модель в настройках" }}"
    }
    private fun saveProvider() {
        check(!AgentRuntime.state.value.running) { "Измените профиль после остановки задачи" }
        val value=key.text.toString().trim()
        require(!value.contains('\n') && !value.contains('\r')) { "Некорректный ключ" }
        if(value.isNotBlank()) { prefs.setKeyFor(selection,value);key.text.clear() }
        prefs.setModelFor(selection,model.text.toString().trim().removePrefix("models/"))
        prefs.compatibleEndpoint=endpoint.text.toString().trim()
        prefs.provider=selection
    }
    private fun validateProvider() {
        require(prefs.modelFor(selection).matches(Regex("[A-Za-z0-9._:/-]{1,150}"))) { "Укажите идентификатор модели" }
        require(prefs.keyFor(selection).isNotBlank() || selection=="compatible") { "Сохраните API-ключ" }
        if(selection=="compatible") ProviderAddress.validate(prefs.compatibleEndpoint)
    }
    private fun editQuota() {
        try { saveProvider() } catch(e:Exception) { feedback(e.message ?: "Сохраните профиль");return }
        val id=selection;val m=prefs.modelFor(id);val limits=prefs.limitsFor(id,m)
        val col=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(8),dp(18),dp(8)) }
        fun field(h: String,text: String)=EditText(this).apply { hint=h;setText(text);col.addView(this) }
        val account=field("Группа проекта / аккаунта",prefs.quotaAccount(id))
        val group=field("Фактическая группа квоты модели",prefs.quotaModelGroup(id,m))
        val rpm=field("Запросов в минуту",limits.rpm.toString());val tpm=field("Входных токенов в минуту",limits.tpm.toString())
        val rpd=field("Запросов за 24 часа",limits.rpd.toString());val reserve=field("Резерв RPM для диалога",limits.dialogueReserve.toString())
        val merge=field("Объединение дополнений, мс (0–1000)",prefs.mergeMillis.toString())
        val dialog=android.app.AlertDialog.Builder(this).setTitle("Квоты · $m").setView(ScrollView(this).apply { addView(col) }).setPositiveButton("Сохранить",null).setNegativeButton("Отмена",null).create()
        dialog.setOnShowListener { dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                check(!AgentRuntime.state.value.running && !ConversationHub.state.value.routing) { "Измените квоты после остановки работы" }
                val l=QuotaLimits(rpm.text.toString().toInt(),tpm.text.toString().toInt(),rpd.text.toString().toInt(),reserve.text.toString().toInt())
                val a=account.text.toString().trim();val g=group.text.toString().trim();require(a.isNotBlank() && g.isNotBlank() && a.length<=100 && g.length<=150)
                prefs.setLimits(id,m,l);prefs.setQuotaGroup(id,m,a,g);prefs.mergeMillis=merge.text.toString().toInt();dialog.dismiss();feedback("Настройки очереди сохранены")
            } catch(e:Exception) { feedback(e.message ?: "Проверьте значения") }
        } };dialog.show()
    }
    private fun saveVoice() {
        val d=delay.text.toString().toIntOrNull();require(d!=null && d in 0..30) { "Задержка: 0–30" }
        val p=pitch.text.toString().replace(',','.').toFloatOrNull();val r=rate.text.toString().replace(',','.').toFloatOrNull()
        require(p!=null && p in 0.5f..2f && r!=null && r in 0.5f..2f) { "Высота и скорость: 0.5–2.0" }
        prefs.delaySeconds=d;prefs.speechPitch=p;prefs.speechRate=r;prefs.speakResults=speak.isChecked
    }
    private fun startTask(resume: TaskCheckpoint?=null) {
        if(resume!=null) { AgentService.command(this,AgentService.RESUME,resume.id);return }
        if(sending) return
        val text=taskInput.text.toString().trim()
        if(text.isBlank()) return
        try {
            val control=IntentRules.local(text,AgentRuntime.state.value.running,ConversationHub.currentTask()?.status=="WAIT_USER")
            if(control==OwnerIntent.CANCEL) ConversationHub.localCancel(this)
            if(control==OwnerIntent.PAUSE) ConversationHub.localPause(this)
            if(control !in setOf(OwnerIntent.CANCEL,OwnerIntent.PAUSE,OwnerIntent.RESUME) && !AgentRuntime.state.value.running && !ConversationHub.state.value.routing) { saveProvider();saveVoice();validateProvider() }
            require(text.length<=8000) { "Реплика: максимум 8000 символов" }
            if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),50)
            val question=ConversationHub.currentTask()?.takeIf { it.status=="WAIT_USER" }?.questionId.orEmpty()
            sending=true
            scope.launch {
                try {
                    val id=withContext(Dispatchers.IO) { ConversationHub.receive(applicationContext,text,replyTo=question) }
                    if(taskInput.text.toString().trim()==text) taskInput.text.clear()
                    AgentService.message(this@PanelActivity,id)
                } catch(e:Exception) { feedback(e.message ?: "Не удалось сохранить реплику") }
                finally { sending=false;render(AgentRuntime.state.value) }
            }
        } catch(e:Exception) { feedback(e.message ?: "Не удалось отправить") }
    }
    private fun listen() {
        VoiceOutput.stop()
        val intent=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE,"ru-RU").putExtra(RecognizerIntent.EXTRA_PROMPT,"Задание для AgentBridge")
        try { speech.launch(intent) } catch(_:Exception) { feedback("Служба распознавания не установлена. Введите задачу текстом.") }
    }
    private var wasRunning=false
    private fun render(s: AgentRuntime.State) {
        if(!::start.isInitialized) return
        val view=ConversationHub.state.value
        status.text=view.phase
        if(::journal.isInitialized && journal.text.toString()!=s.log.takeLast(60000)) journal.text=s.log.takeLast(60000)
        val busy=s.running || operation?.isActive==true || view.routing
        editable.forEach { it.isEnabled=!busy || it===taskInput || it===memorySearch }
        taskInput.isEnabled=true;mic.isEnabled=true;start.isEnabled=!sending
        stop.visibility=if(busy || view.task?.status=="WAIT_USER") View.VISIBLE else View.GONE
        pauseButton.visibility=if(view.task!=null && !IntentRules.isTerminal(view.task.status)) View.VISIBLE else View.GONE
        pauseButton.text=if(s.running) "Пауза" else if(view.task?.status=="WAIT_USER") "Ответить" else "Продолжить"
        modelLabel.text="${labels[ids.indexOf(prefs.provider)]} / ${prefs.modelFor(prefs.provider)} · ${if(BridgeAccessibilityService.instance!=null) "Bridge ●" else "Bridge ○"}"
        if(wasRunning && !s.running) { refreshTasks();refreshHistory() }
        wasRunning=s.running
    }
    private fun refreshTasks() { if(!::checkpoints.isInitialized) return;action {
        val tasks=withContext(Dispatchers.IO) { store.tasks() }
        checkpoints.removeAllViews()
        tasks.take(8).forEach { t ->
            val row=TextView(this@PanelActivity).apply { text="${t.status} · ${t.provider}/${t.model}\n${t.goal.take(160)}\n${t.observation.take(180)}";setTextColor(white);setPadding(0,dp(12),0,dp(4)) };checkpoints.addView(row)
            if(t.status !in setOf("COMPLETE","CANCELLED")) {
                val resume=Button(this@PanelActivity).apply { text="Продолжить с текущей моделью";isAllCaps=false;setOnClickListener { startTask(t) } };checkpoints.addView(resume)
                val cancel=Button(this@PanelActivity).apply { text="Отменить сохранённую задачу";isAllCaps=false;setOnClickListener { action {
                    check(!AgentRuntime.state.value.running) { "Сначала остановите задачу" }
                    withContext(Dispatchers.IO) { store.checkpoint(t.copy(status="CANCELLED",updated=System.currentTimeMillis())) };refreshTasks()
                } } };checkpoints.addView(cancel)
            } else if(t.answer.isNotBlank()) row.append("\n"+t.answer.take(500))
        }
    } }
    private fun refreshMemory() { if(!::memoryList.isInitialized) return;action {
        val query=memorySearch.text.toString().trim()
        val entries=withContext(Dispatchers.IO) { store.entries().filter { query.isBlank() || (it.title+" "+it.content+" "+it.kind+" "+it.source).contains(query,true) } }
        memoryList.removeAllViews()
        entries.forEach { e ->
            val b=Button(this@PanelActivity).apply {
                text="${e.title}\n${e.kind} · ${e.verification} · ${e.source}\n${if(e.pinned) "Закреплено · " else ""}${if(e.cloudAllowed) "Облако разрешено" else "Только локально"}\n${e.content.take(120)}"
                isAllCaps=false;setTextColor(white);backgroundTintList=android.content.res.ColorStateList.valueOf(Color.rgb(20,35,25));setOnClickListener { editMemory(e) }
            };memoryList.addView(b,LinearLayout.LayoutParams(-1,-2))
        }
    } }
    private fun editMemory(entry: MemoryEntry?) {
        if(AgentRuntime.state.value.running) { feedback("Редактируйте память после остановки задачи");return }
        val col=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(10),dp(16),dp(10)) }
        fun field(h: String,v: String,multi: Boolean=false)=EditText(this).apply { hint=h;setText(v);isSaveEnabled=false;if(multi) { minLines=4;maxLines=9;inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE };col.addView(this) }
        val title=field("Название",entry?.title.orEmpty())
        val kind=Spinner(this).apply { adapter=ArrayAdapter(this@PanelActivity,android.R.layout.simple_spinner_dropdown_item,MemoryRules.kinds);setSelection(MemoryRules.kinds.indexOf(entry?.kind ?: "core").coerceAtLeast(0));col.addView(this) }
        val body=field("Знание / сценарий",entry?.content.orEmpty(),true)
        val app=field("Область: global или app:имя.пакета",entry?.scope ?: "global")
        fun flag(text: String,checked: Boolean)=CheckBox(this).apply { this.text=text;isChecked=checked;col.addView(this) }
        val verified=flag("Подтверждено владельцем",entry?.verification=="confirmed" || entry==null)
        val pin=flag("Закрепить в контексте",entry?.pinned ?: false)
        val cloud=flag("Разрешить передачу облачным моделям",entry?.cloudAllowed ?: true)
        val privateFlag=flag("Личные данные: исключить из экспорта",entry?.isPrivate ?: false)
        val dialog=android.app.AlertDialog.Builder(this).setTitle("Запись памяти")
            .setView(ScrollView(this).apply { addView(col) }).setPositiveButton("Сохранить",null).setNegativeButton("Отмена",null)
            .setNeutralButton(if(entry!=null) "Удалить" else "",null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener { action {
                check(!AgentRuntime.state.value.running) { "Сначала остановите задачу" }
                val record=(entry ?: MemoryEntry(title="Новое",content="")).copy(title=title.text.toString(),content=body.text.toString(),kind=MemoryRules.kinds[kind.selectedItemPosition],
                    scope=app.text.toString(),verification=if(verified.isChecked) "confirmed" else "pending",pinned=pin.isChecked,cloudAllowed=cloud.isChecked,isPrivate=privateFlag.isChecked,
                    source=if(entry==null || entry.content!=body.text.toString()) "owner" else entry.source,revision=(entry?.revision ?: 0)+1,updated=System.currentTimeMillis())
                withContext(Dispatchers.IO) { store.put(record) };dialog.dismiss();refreshMemory()
            } }
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener { if(entry!=null) action {
                check(!AgentRuntime.state.value.running) { "Сначала остановите задачу" }
                withContext(Dispatchers.IO) { store.delete(entry.id) };dialog.dismiss();refreshMemory()
            } }
        };dialog.show()
    }
    private fun refreshAppEvents() {
        if (!::appEventHistory.isInitialized) return
        val events=AppEventTimeline.recent(limit=25)
        appEventHistory.text=if(events.isEmpty()) "Пока нет событий. Переключитесь в другое приложение и обратно."
        else events.joinToString("\n") { e ->
            "#${e.id} · ${e.source} · ${e.type}\n${e.packageName}${if(e.activity.isNotBlank()) " / "+e.activity else ""}"
        }
    }
    private fun refreshHistory() { if(!::history.isInitialized) return;action { history.text=withContext(Dispatchers.IO) { store.events() } } }
    override fun onResume() { super.onResume();if(::start.isInitialized) render(AgentRuntime.state.value) }
    override fun onPause() { super.onPause();if(::taskInput.isInitialized && !AgentRuntime.state.value.running) prefs.task=taskInput.text.toString() }
    override fun onDestroy() { operation?.cancel();scope.cancel();super.onDestroy() }
}
