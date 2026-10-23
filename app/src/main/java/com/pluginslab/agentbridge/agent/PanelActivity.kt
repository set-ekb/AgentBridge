package com.pluginslab.agentbridge.agent

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
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
import androidx.core.content.FileProvider
import com.pluginslab.agentbridge.SettingsActivity
import com.pluginslab.agentbridge.accessibility.BridgeAccessibilityService
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.Locale
import java.io.File

/** Five stable surfaces. Model configuration never shares a scrolling page with the task. */
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
    private var memoryVisibleLimit=UiDisplayRules.MEMORY_PAGE_SIZE
    private var memoryQueryGeneration=0
    private var lastMemoryQuery=""
    private lateinit var journal: TextView
    private lateinit var history: TextView
    private lateinit var appEventHistory: TextView
    private lateinit var hookDiagnosticHistory: TextView
    private lateinit var healthSummary: TextView
    private lateinit var journalOverview: TextView
    private var fullEventNames=false
    private val navViews=mutableListOf<LinearLayout>()
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
    private val surface=Color.rgb(17,29,22)
    private val surfaceAlt=Color.rgb(22,38,28)
    private val border=Color.rgb(46,73,54)
    private val muted=Color.rgb(155,181,160)
    private fun dp(n: Int)=(n*resources.displayMetrics.density).toInt()
    private fun panelBg(color: Int = surface, outline: Int = border, radius: Int = 14): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius=dp(radius).toFloat()
            setStroke(dp(1).coerceAtLeast(1),outline)
        }
    private fun basicText(value: String,sp: Float,color: Int = white): TextView =
        TextView(this).apply {
            text=value;textSize=sp;setTextColor(color)
            includeFontPadding=false
        }
    private fun smallButton(title: String,accent: Boolean=false,callback: ()->Unit): Button =
        Button(this).apply {
            text=title;isAllCaps=false;textSize=13f
            minHeight=dp(45);minimumHeight=dp(45)
            minWidth=0;minimumWidth=0
            setPadding(dp(10),0,dp(10),0)
            setTextColor(if(accent) navy else white)
            backgroundTintList=null
            background=panelBg(if(accent) mint else surfaceAlt,if(accent) mint else border,11)
            elevation=0f
            setOnClickListener { callback() }
        }
    private fun actionPair(
        left: String, leftAction: ()->Unit,
        right: String, rightAction: ()->Unit,
        accentLeft: Boolean=false
    ) {
        val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        column.addView(row,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(7);bottomMargin=dp(6) })
        row.addView(smallButton(left,accentLeft,leftAction),
            LinearLayout.LayoutParams(0,dp(46),1f).apply { rightMargin=dp(5) })
        row.addView(smallButton(right,false,rightAction),
            LinearLayout.LayoutParams(0,dp(46),1f).apply { leftMargin=dp(5) })
    }
    /**
     * Disclosure sections keep heavy journal/history views out of the initial viewport.
     * Content remains in memory and expands without a new database/network request.
     */
    private fun section(title: String, subtitle: String = "", open: Boolean=false, build: ()->Unit) {
        val parent=column
        val card=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            background=panelBg()
            setPadding(dp(12),dp(5),dp(12),dp(10))
        }
        parent.addView(card,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(10) })
        val header=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL
            minimumHeight=dp(52)
        }
        val titles=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        titles.addView(basicText(title,15f,white).apply { setTypeface(null,Typeface.BOLD) })
        if(subtitle.isNotBlank()) titles.addView(basicText(subtitle,11f,muted).apply {
            setPadding(0,dp(5),0,0)
        })
        header.addView(titles,LinearLayout.LayoutParams(0,-2,1f))
        val arrow=basicText(if(open) "⌄" else "›",22f,mint).apply {
            textAlignment=View.TEXT_ALIGNMENT_CENTER
            contentDescription="Развернуть или свернуть"
        }
        header.addView(arrow,LinearLayout.LayoutParams(dp(30),-2))
        card.addView(header)
        val content=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            visibility=if(open) View.VISIBLE else View.GONE
        }
        card.addView(content)
        header.setOnClickListener {
            content.visibility=if(content.visibility==View.VISIBLE) View.GONE else View.VISIBLE
            arrow.text=if(content.visibility==View.VISIBLE) "⌄" else "›"
        }
        header.contentDescription=title+(if(subtitle.isNotBlank()) ". "+subtitle else "")
        column=content
        try { build() } finally { column=parent }
    }
    private fun buildNavigation(root: LinearLayout) {
        val tabs=UiDisplayRules.tabs
        val icons=listOf(
            android.R.drawable.ic_menu_send,
            android.R.drawable.ic_menu_agenda,
            android.R.drawable.ic_menu_manage,
            android.R.drawable.ic_menu_recent_history,
            android.R.drawable.ic_menu_info_details
        )
        val bar=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            background=panelBg(Color.rgb(12,22,16),border,15)
            setPadding(dp(2),dp(3),dp(2),dp(3))
        }
        tabs.forEachIndexed { i,item ->
            val tab=LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL
                gravity=android.view.Gravity.CENTER
                minimumHeight=dp(54)
                contentDescription=item.accessible
            }
            val icon=ImageView(this).apply {
                setImageResource(icons[i])
                setColorFilter(muted)
                contentDescription=null
            }
            tab.addView(icon,LinearLayout.LayoutParams(dp(19),dp(19)))
            tab.addView(basicText(item.short,10f,muted).apply {
                gravity=android.view.Gravity.CENTER
                isSingleLine=true
                setPadding(0,dp(4),0,0)
            },LinearLayout.LayoutParams(-1,-2))
            tab.setOnClickListener { showTab(i) }
            navViews.add(tab)
            bar.addView(tab,LinearLayout.LayoutParams(0,dp(57),1f))
        }
        root.addView(bar,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(5) })
    }
    private fun highlightNavigation(index: Int) {
        navViews.forEachIndexed { i,tab ->
            val selected=i==index
            tab.background=if(selected) panelBg(surfaceAlt,Color.rgb(66,112,79),11) else null
            val icon=tab.getChildAt(0) as ImageView
            icon.setColorFilter(if(selected) mint else muted)
            val title=tab.getChildAt(1) as TextView
            title.setTextColor(if(selected) mint else muted)
            title.setTypeface(null,if(selected) Typeface.BOLD else Typeface.NORMAL)
            tab.isSelected=selected
        }
    }
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
        val root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(navy)
            setPadding(dp(10),dp(7),dp(10),dp(6))
        }
        val header=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(11),dp(9),dp(11),dp(9))
            background=panelBg(Color.rgb(12,24,17),Color.rgb(29,54,38),13)
        }
        val top=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=android.view.Gravity.CENTER_VERTICAL
        }
        top.addView(basicText("◆",17f,mint),LinearLayout.LayoutParams(-2,-2).apply { rightMargin=dp(8) })
        top.addView(basicText("AGENTBRIDGE",16f,white).apply { setTypeface(null,Typeface.BOLD) },
            LinearLayout.LayoutParams(0,-2,1f))
        val displayedVersion = runCatching {
            packageManager.getPackageInfo(packageName,0).versionName.orEmpty()
        }.getOrDefault("")
        top.addView(basicText("v${displayedVersion.removeSuffix("-local").ifBlank { "?" }}",11f,mint).apply {
            setPadding(dp(8),dp(3),dp(8),dp(3))
            background=panelBg(surfaceAlt,border,8)
        })
        header.addView(top)
        modelLabel=basicText("",11f,muted).apply {
            maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END
            setPadding(0,dp(7),0,0)
        }
        header.addView(modelLabel)
        status=basicText("Открываю разговор…",12f,mint).apply {
            maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END
            setPadding(0,dp(5),0,0)
        }
        header.addView(status)
        root.addView(header,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(3) })
        column=root
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
            label("Подтверждённые знания доступны всем подключённым моделям в пределах разрешений.",12)
            memorySearch=input("Поиск по всем записям")
            actionPair("Найти", { refreshMemory() }, "Добавить", { editMemory(null) },true)
            section("Импорт и экспорт", "Файлы памяти · только по действию владельца") {
                button("Импорт .md / .txt / .json") {
                    importFile.launch(arrayOf("text/*","application/json","application/octet-stream"))
                }
                button("Экспорт памяти в JSON") {
                    action {
                        exportText=withContext(Dispatchers.IO) { MemoryRules.export(store.entries()) }
                        exportFile.launch("agentbridge-memory.json")
                    }
                }
                label("Экспорт исключает локальные и отмеченные личные записи. Проверь файл перед отправкой.",11)
            }
            memoryList=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
            column.addView(memoryList)
        }
        page {
            label("Настройки",27,white)
            section("Модели и подключение", "Провайдер, модель и API-ключ", true) {
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
            }
            section("OpenCode · Termux", "Запуск и защищённое подключение") {
                label("Нужен небольшой шлюз в Termux на 127.0.0.1:4097. Пароль OpenCode не передаётся в приложение. Здесь хранится отдельный ключ сопряжения.",11)
                val pairing = input("Новый ключ сопряжения из Termux",password=true)
                val linkState = label("Связь с OpenCode ещё не проверялась",12)
                var startingOpenCode = false
                button("Запустить OpenCode и коннектор") {
                    if (!startingOpenCode) action {
                        require(prefs.keyFor("opencode_link").matches(Regex("[A-Za-z0-9_-]{20,128}"))) {
                            "Сначала подготовьте Termux и сохраните ключ сопряжения"
                        }
                        if (checkSelfPermission(OpenCodeLauncher.PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                            requestPermissions(arrayOf(OpenCodeLauncher.PERMISSION), 4097)
                            linkState.text="Разрешите запуск команд в Termux и нажмите кнопку ещё раз"
                        } else {
                            startingOpenCode = true
                            try {
                                OpenCodeLauncher.start(this@PanelActivity)
                                linkState.text="Запуск OpenCode и коннектора…"
                                var ready: OpenCodeLocalLink.Status? = null
                                for (attempt in 1..20) {
                                    delay(2000)
                                    try {
                                        val checked = OpenCodeLocalLink.check(prefs.keyFor("opencode_link"))
                                        if (checked.healthy) { ready=checked; break }
                                    } catch (e: CancellationException) { throw e }
                                    catch (_: Exception) { /* Readiness, not command delivery, confirms startup. */ }
                                }
                                linkState.text=ready?.let { "OpenCode ${it.version} · коннектор доступен. Задания не запускались." }
                                    ?: "Готовность не подтверждена. Откройте Termux: проверьте скрипт запуска и allow-external-apps=true."
                            } finally { startingOpenCode=false }
                        }
                    }
                }
                button("Сохранить ключ связи") {
                    action {
                        val value = pairing.text.toString().trim()
                        require(value.matches(Regex("[A-Za-z0-9_-]{20,128}"))) { "Некорректный ключ сопряжения" }
                        prefs.setKeyFor("opencode_link",value)
                        pairing.text.clear()
                        feedback("Ключ связи сохранён в защищённом хранилище Android")
                    }
                }
                button("Проверить связь · без запросов модели") {
                    action {
                        linkState.text="Проверяю локальный OpenCode…"
                        try {
                            val result=OpenCodeLocalLink.check(prefs.keyFor("opencode_link"))
                            linkState.text=if(result.healthy)
                                "OpenCode ${result.version} · активных сессий: ${result.activeSessions} · защищённая связь"
                            else "Шлюз доступен, но сервер OpenCode не отвечает"
                        } catch (e: Exception) {
                            linkState.text=e.message?.take(160) ?: "Локальное соединение не установлено"
                        }
                    }
                }
                label("Первичная подготовка — в Termux. Запуск команд требует отдельного разрешения Android и allow-external-apps=true в Termux. Кнопка запускает сервер и шлюз с приёмом заданий; сами задания отправляются только кнопкой «Выполнить через OpenCode». После перезагрузки нажмите запуск снова. Готовность сервера не подтверждает подключение Android MCP.",11)
            }
            section("Квоты API", "Лимиты, очередь, резерв запросов") {
            label("Квоты задаются для выбранной модели и группы проекта. Ноль означает неизвестный лимит; серверные ограничения действуют всегда. TPM оценивается консервативно до ответа, RPD — по скользящим 24 часам.",12)
            button("Настроить квоты выбранной модели") { editQuota() }.also { editable.add(it) }
            }
            section("Голос", "Синтез речи и настройки событий") {
            speak=CheckBox(this).apply { text="Озвучивать итоги и ошибки";isChecked=prefs.speakResults;setTextColor(white) };column.addView(speak);editable.add(speak)
            pitch=input("Высота голоса · 0.5–2.0").apply { setText(prefs.speechPitch.toString()) }
            rate=input("Скорость речи · 0.5–2.0").apply { setText(prefs.speechRate.toString()) }
            label("Русский голос · системная служба Android. Распознавание может использовать сеть; непрерывного прослушивания нет.",12)
            button("Остановить только озвучивание") { VoiceOutput.stop() }
            val events=CheckBox(this).apply { text="Озвучивать значимые события";isChecked=prefs.speakEvents;setTextColor(white);setOnCheckedChangeListener { _,checked->prefs.speakEvents=checked } };column.addView(events)
            }
            section("Виджет и приватность", "Рабочий стол, история разговора") {
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
            }
            section("Bridge и выполнение", "Спецвозможности, MCP и задержка запуска") {
            delay=input("Задержка старта · 0–30 секунд").apply { inputType=InputType.TYPE_CLASS_NUMBER;setText(prefs.delaySeconds.toString()) }
            button("Сохранить настройки голоса и задержки") { try { saveVoice();feedback("Настройки сохранены") } catch(e:Exception) { feedback(e.message ?: "Проверьте значения") } }.also { editable.add(it) }
            button("Включить спецвозможности") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            button("Внешний MCP / настройки Bridge") { startActivity(Intent(this,SettingsActivity::class.java)) }
            button("Настройки синтеза речи Android") { runCatching { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }.onFailure { feedback("Раздел синтеза речи недоступен") } }
            label("Действия подтверждаются свежими данными экрана; остановка доступна вверху и через уведомление.",11)
            }
        }
        page {
            label("Журнал",26,white)
            journalOverview=label("Сводка ещё не обновлена",13,white).apply {
                setPadding(dp(12),dp(11),dp(12),dp(11))
                background=panelBg(surfaceAlt, border, 12)
                setTextIsSelectable(true)
            }
            button("Обновить сводку",true) {
                refreshJournalOverview()
                refreshHookDiagnostics()
                refreshAppEvents()
            }
            actionPair(
                "Сохранить .jsonl", { exportDiagnostics.launch(Diagnostics.exportFileName()) },
                "Поделиться", { shareDiagnostics() }
            )
            label("Выгрузка содержит только технические метаданные. Отправка — только по твоему выбору.",11)
            section("Vector Hooks", "Загрузка модуля и события · необязательный источник") {
                hookDiagnosticHistory=label("Нет проверенных сигналов. Нажми «Обновить».",12).apply {
                    setTextIsSelectable(true)
                }
                button("Обновить Vector") { refreshHookDiagnostics() }
                label("Без записей? Проверь Vector → Журналы и область действия Hooks. Тестовый broadcast не доказывает работу хука.",11)
            }
            section("События Android", "Последние 25 переходов · Accessibility / Vector") {
                appEventHistory=label("Нажми «Обновить события».",12).apply {
                    typeface=Typeface.MONOSPACE
                    setTextIsSelectable(true)
                }
                actionPair("Обновить", { refreshAppEvents() },
                    "Полные имена", { fullEventNames=!fullEventNames;refreshAppEvents() })
            }
            section("История задач", "Сохранённые задачи и технические события") {
                checkpoints=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
                column.addView(checkpoints)
                button("Обновить историю задач") { refreshTasks() }
                history=label("",12).apply {
                    maxLines=14
                    setTextIsSelectable(true)
                }
                button("Обновить записи") { refreshHistory() }
                button("Показать / свернуть записи") {
                    history.maxLines=if(history.maxLines==14) Int.MAX_VALUE else 14
                }
                button("Очистить историю задач") {
                    android.app.AlertDialog.Builder(this@PanelActivity)
                        .setTitle("Очистить историю?")
                        .setMessage("Будут удалены сохранённые события и история задач. Файл диагностики .jsonl сохраняется отдельно.")
                        .setNegativeButton("Отмена",null)
                        .setPositiveButton("Очистить") { _,_ ->
                            action {
                                check(!AgentRuntime.state.value.running) { "Сначала остановите задачу" }
                                withContext(Dispatchers.IO) { store.clearHistory() }
                                refreshTasks()
                                refreshHistory()
                            }
                        }.show()
                }
            }
            section("Отладка текущей задачи", "Подробности вызовов · только локально") {
                journal=label("Пока нет активного журнала задачи.",12).apply {
                    typeface=Typeface.MONOSPACE
                    maxLines=18
                    setTextIsSelectable(true)
                }
                button("Показать / свернуть подробности") {
                    journal.maxLines=if(journal.maxLines==18) Int.MAX_VALUE else 18
                }
            }
        }
        page {
            label("Состояние",26,white)
            label("Локальная проверка за 24 часа",13)
            healthSummary=label("Нажми «Проверить состояние».",13,white).apply {
                setTextIsSelectable(true)
                setPadding(dp(12),dp(12),dp(12),dp(12))
                background=panelBg(surfaceAlt,border,12)
            }
            button("Проверить состояние",true) { refreshHealthStatus() }
            section("Инструменты агента", "Шесть локальных проверок · только чтение") {
                label("• Общая сводка и последние ошибки\n"+
                    "• Состояние компонентов и API\n"+
                    "• Время выполнения инструментов\n"+
                    "• Сигналы Vector / Accessibility",13,white)
                label("API-запросы не выполняются при открытии этой страницы. Подробности можно сохранить на вкладке «Журнал».",11)
            }
            section("Что означает «не проверено»?", "Ограничения технической диагностики") {
                label("Отсутствие событий не доказывает исправность. Vector — дополнительный источник, а его сообщения не подтверждают результат действий. История может быть неполной после ротации журнала.",12)
            }
        }
        if(!intent.getBooleanExtra("compact",false)) buildNavigation(root)
        scope.launch { AgentRuntime.state.collect { render(it) } }
        scope.launch { ConversationHub.state.collect { renderConversation(it);render(AgentRuntime.state.value) } }
        refreshTasks();refreshMemory()
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
        val toOpenCode = Button(this).apply {
            text="Выполнить через OpenCode · Termux"
            isAllCaps=false
            setTextColor(mint)
            backgroundTintList=android.content.res.ColorStateList.valueOf(Color.rgb(20,35,25))
            setOnClickListener {
                val requested=taskInput.text.toString().trim()
                if(requested.isBlank()) { feedback("Сначала напиши поручение OpenCode");return@setOnClickListener }
                if(sending || AgentRuntime.state.value.running || ConversationHub.state.value.routing) {
                    feedback("Сначала дождись завершения текущей задачи");return@setOnClickListener
                }
                sending=true
                scope.launch {
                    try {
                        val id=withContext(Dispatchers.IO) {
                            ConversationHub.queueOpenCode(applicationContext,requested)
                        }
                        if(taskInput.text.toString().trim()==requested) taskInput.text.clear()
                        AgentService.command(this@PanelActivity,AgentService.OPEN_CODE_START,id)
                    } catch(e:Exception) {
                        feedback(e.message?.take(180) ?: "Не удалось отправить OpenCode")
                    } finally { sending=false;render(AgentRuntime.state.value) }
                }
            }
        }
        composer.addView(toOpenCode,LinearLayout.LayoutParams(-1,dp(49)))
        editable.add(toOpenCode)
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
        highlightNavigation(index)
        if(index==0) refreshTasks();if(index==1) refreshMemory();if(index==3) { refreshJournalOverview();refreshHookDiagnostics() }
        if(index==4) refreshHealthStatus()
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
    private fun label(text: String,size: Int,color: Int=muted): TextView = TextView(this).apply {
        this.text=text
        textSize=size.toFloat()
        setTextColor(color)
        includeFontPadding=false
        if(size>=17) setTypeface(null,Typeface.BOLD)
        setPadding(0,dp(if(size>=21) 11 else 6),0,dp(if(size>=21) 11 else 7))
        column.addView(this,LinearLayout.LayoutParams(-1,-2))
    }
    private fun input(hint: String,password: Boolean=false,multi: Boolean=false): EditText = EditText(this).apply {
        this.hint=hint
        setTextColor(white)
        setHintTextColor(muted)
        textSize=14f
        inputType=InputType.TYPE_CLASS_TEXT or if(password) InputType.TYPE_TEXT_VARIATION_PASSWORD
            else if(multi) InputType.TYPE_TEXT_FLAG_MULTI_LINE else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        backgroundTintList=null
        background=panelBg(Color.rgb(13,24,18),border,11)
        setPadding(dp(12),dp(10),dp(12),dp(10))
        minHeight=dp(48)
        isSaveEnabled=false
        column.addView(this,LinearLayout.LayoutParams(-1,-2).apply {
            bottomMargin=dp(7)
        })
        editable.add(this)
    }
    private fun button(text: String,accent: Boolean=false,action: ()->Unit): Button =
        smallButton(text,accent,action).apply {
            column.addView(this,LinearLayout.LayoutParams(-1,-2).apply {
                topMargin=dp(3);bottomMargin=dp(3)
            })
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
        if(activeTab==3 && ::journal.isInitialized) {
            val excerpt=s.log.takeLast(16000)
            if(journal.text.toString()!=excerpt) journal.text=excerpt
        }
        val openCodeActive=view.task?.provider=="opencode" &&
            view.task.status in setOf("QUEUED","DISPATCHING","RUNNING","STOP_REQUESTED")
        val busy=s.running || operation?.isActive==true || view.routing || openCodeActive
        editable.forEach { it.isEnabled=!busy || it===taskInput || it===memorySearch }
        taskInput.isEnabled=true;mic.isEnabled=true;start.isEnabled=!sending
        stop.visibility=if(busy || view.task?.status=="WAIT_USER") View.VISIBLE else View.GONE
        pauseButton.visibility=if(view.task!=null && view.task.provider!="opencode" && !IntentRules.isTerminal(view.task.status)) View.VISIBLE else View.GONE
        pauseButton.text=if(s.running) "Пауза" else if(view.task?.status=="WAIT_USER") "Ответить" else "Продолжить"
        modelLabel.text="${labels[ids.indexOf(prefs.provider)]} / ${prefs.modelFor(prefs.provider)} · ${if(BridgeAccessibilityService.instance!=null) "Bridge ●" else "Bridge ○"}"
        if(wasRunning && !s.running) refreshTasks()
        wasRunning=s.running
    }
    private fun refreshTasks() { if(!::checkpoints.isInitialized) return;action {
        val tasks=withContext(Dispatchers.IO) { store.tasks() }
        checkpoints.removeAllViews()
        tasks.take(8).forEach { t ->
            val row=TextView(this@PanelActivity).apply { text="${t.status} · ${t.provider}/${t.model}\n${t.goal.take(160)}\n${t.observation.take(180)}";setTextColor(white);setPadding(0,dp(12),0,dp(4)) };checkpoints.addView(row)
            if(t.provider=="opencode" && t.status !in setOf("COMPLETE","CANCELLED")) {
                row.append("\nOpenCode: " + when(t.status) {
                    "UNKNOWN","STOP_UNCONFIRMED","STOP_REQUESTED" ->
                        "Состояние не подтверждено; проверь сессию в Termux. Не отправляй повторно."
                    "QUEUED" -> "Задача ожидает отправки; не создавай повторную."
                    else -> "Сессия может выполняться в Termux. Стоп доступен в панели и уведомлении."
                })
            } else if(t.status !in setOf("COMPLETE","CANCELLED")) {
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
        val generation=++memoryQueryGeneration
        if(query!=lastMemoryQuery) { memoryVisibleLimit=UiDisplayRules.MEMORY_PAGE_SIZE;lastMemoryQuery=query }
        val entries=withContext(Dispatchers.IO) {
            store.entries().filter {
                query.isBlank() || (it.title+" "+it.content+" "+it.kind+" "+it.source).contains(query,true)
            }
        }
        if(generation!=memoryQueryGeneration) return@action
        memoryList.removeAllViews()
        if(entries.isEmpty()) memoryList.addView(basicText("Ничего не найдено. Измени запрос или добавь запись.",13f,muted).apply {
            setPadding(dp(8),dp(14),dp(8),dp(14))
        })
        entries.take(memoryVisibleLimit).forEach { e ->
            val card=LinearLayout(this@PanelActivity).apply {
                orientation=LinearLayout.VERTICAL
                setPadding(dp(12),dp(10),dp(12),dp(10))
                background=panelBg()
                isClickable=true;isFocusable=true
                contentDescription="Открыть запись: "+e.title
                setOnClickListener { editMemory(e) }
            }
            val head=basicText(e.title,14f,white).apply {
                setTypeface(null,Typeface.BOLD)
                maxLines=2
                ellipsize=android.text.TextUtils.TruncateAt.END
            }
            card.addView(head)
            card.addView(basicText(
                e.kind+" · "+e.verification+" · "+if(e.cloudAllowed) "для моделей" else "локально",
                11f,muted
            ).apply { setPadding(0,dp(5),0,dp(4)) })
            card.addView(basicText(e.content,12f,white).apply {
                maxLines=2
                ellipsize=android.text.TextUtils.TruncateAt.END
            })
            memoryList.addView(card,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) })
        }
        if(entries.size>memoryVisibleLimit) {
            memoryList.addView(smallButton("Показать ещё · ${entries.size-memoryVisibleLimit}") {
                memoryVisibleLimit=UiDisplayRules.nextMemoryLimit(memoryVisibleLimit,entries.size);refreshMemory()
            },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(10) })
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
    private fun shareDiagnostics() = action {
        val file = withContext(Dispatchers.IO) {
            val folder = File(cacheDir, "diagnostics_share")
            check(folder.isDirectory || folder.mkdirs()) { "Не удалось создать временную папку диагностики" }
            // Old copies can contain app usage metadata: remove them when no longer needed.
            val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
            folder.listFiles()?.filter { it.isFile && it.lastModified() < cutoff }?.forEach { it.delete() }
            File(folder, Diagnostics.exportFileName()).apply {
                writeText(Diagnostics.export(applicationContext), Charsets.UTF_8)
            }
        }
        val uri = FileProvider.getUriForFile(
            this@PanelActivity, "${packageName}.diagnosticsfiles", file
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            // text/plain allows more Android share targets than application/x-ndjson.
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Отправить диагностический файл"))
    }

    private fun refreshHealthStatus() {
        if(!::healthSummary.isInitialized) return
        action {
            val (health, summary) = withContext(Dispatchers.IO) {
                val window=DiagnosticsAnalyzer.parse(Diagnostics.snapshotLines(),24)
                DiagnosticsAnalyzer.health(window,BridgeAccessibilityService.instance!=null,AgentRuntime.state.value.running) to
                    DiagnosticsAnalyzer.summary(window)
            }
            fun str(obj: JsonObject, key: String): String =
                obj[key]?.jsonPrimitive?.contentOrNull.orEmpty()
            val a11y=when(str(health,"accessibility")) {
                "connected" -> "подключена"
                else -> "не подключена"
            }
            val journalState=when(str(health,"journal")) {
                "events_observed" -> "есть технические события"
                else -> "недостаточно данных"
            }
            val vectorState=when(str(health,"vector")) {
                "hints_observed_unverified" -> "сигналы наблюдались (не проверены)"
                else -> "не проверен / событий нет"
            }
            healthSummary.text=buildString {
                append("Accessibility  ·  ").append(a11y).append("\n")
                append("Журнал  ·  ").append(journalState).append("\n")
                append("Vector  ·  ").append(vectorState).append("\n")
                append("API  ·  не проверено").append("\n")
                append("Агент  ·  ").append(if(AgentRuntime.state.value.running) "занят" else "ожидает").append("\n\n")
                append("За 24 ч: ")
                append(str(health["coverage"]?.jsonObject ?: buildJsonObject {}, "event_count"))
                append(" событий, ")
                append(str(summary,"errors_observed"))
                append(" ошибок / отклонений\n")
                append("История может быть неполной.")
            }
        }
    }

    private fun refreshJournalOverview() {
        if(!::journalOverview.isInitialized) return
        action {
            val (window,summary) = withContext(Dispatchers.IO) {
                val w=DiagnosticsAnalyzer.parse(Diagnostics.snapshotLines(),24)
                w to DiagnosticsAnalyzer.summary(w)
            }
            val count=window.events.size
            val errors=summary["errors_observed"]?.jsonPrimitive?.intOrNull ?: 0
            val a11y=if(BridgeAccessibilityService.instance!=null) "подключена" else "не подключена"
            val vector=if(window.events.any { it.kind=="hook_event" || it.kind=="hook_status" })
                "сигналы получены · не подтверждены" else "нет подтверждённых событий"
            journalOverview.text="Событий за 24 ч: $count   •   ошибок/отклонений: $errors\n"+
                "Accessibility: $a11y\nVector: $vector"
        }
    }

    private fun refreshHookDiagnostics() {
        if (!::hookDiagnosticHistory.isInitialized) return
        action {
            val records=withContext(Dispatchers.IO) { Diagnostics.recentHooks(20) }
            hookDiagnosticHistory.text=if(records.isEmpty())
                "Сигналы хуков не наблюдались. Проверь Vector → Журналы и область действия."
            else records.joinToString("\n\n") { raw ->
                val obj=runCatching { org.json.JSONObject(raw) }.getOrNull()
                if(obj==null) "Некорректная запись"
                else listOf(
                    obj.optString("event"),obj.optString("status"),
                    obj.optString("package_name"),
                    obj.optString("activity").substringAfterLast('.')
                ).filter { it.isNotBlank() }.joinToString(" · ")
            }
        }
    }

    private fun refreshAppEvents() {
        if (!::appEventHistory.isInitialized) return
        val events=AppEventTimeline.recent(limit=25)
        appEventHistory.text=if(events.isEmpty())
            "Пока нет событий. Переключись в другое приложение и вернись."
        else events.joinToString("\n\n") { e ->
            val origin=if(e.source=="vector_hint") "Vector (не подтверждён)" else "Android"
            val activity=UiDisplayRules.activityName(e.activity,fullEventNames)
            "#${e.id}  ·  $origin  ·  ${e.type}\n${e.packageName}"+
                if(activity.isNotBlank()) "\n$activity" else ""
        }
    }
    private fun refreshHistory() { if(!::history.isInitialized) return;action { history.text=withContext(Dispatchers.IO) { store.events() } } }
    override fun onResume() { super.onResume();if(::start.isInitialized) render(AgentRuntime.state.value) }
    override fun onPause() { super.onPause();if(::taskInput.isInitialized && !AgentRuntime.state.value.running) prefs.task=taskInput.text.toString() }
    override fun onDestroy() { operation?.cancel();scope.cancel();super.onDestroy() }
}
