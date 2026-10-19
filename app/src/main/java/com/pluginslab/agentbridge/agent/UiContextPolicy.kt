package com.pluginslab.agentbridge.agent

import kotlinx.serialization.json.*

/** Per-task context and IDs observed in actual accessibility metadata, never UI text. */
class UiContextPolicy(private val ownPackage: String) {
    companion object {
        val snapshots = setOf("get_ui_tree", "find_nodes", "wait_for_node", "scroll_to_text")
        val inspections = snapshots + setOf("tap_node", "tap_coords", "long_press_node", "swipe",
            "type_text", "clear_text", "send_key_events")
    }
    private var expectedPackage: String? = null
    private val observedIds = mutableMapOf<String, MutableSet<String>>()
    fun acknowledge(packageName: String?) {
        if (!packageName.isNullOrBlank() && packageName != ownPackage) expectedPackage = packageName
    }
    fun observe(packageName: String, ids: Set<String>) {
        if (packageName == ownPackage) return
        observedIds.getOrPut(packageName) { mutableSetOf() }.addAll(ids)
    }
    fun reject(name: String, args: JsonObject, active: String?): JsonObject? {
        if (name !in inspections) return null
        val code: String
        val message: String
        when {
            active.isNullOrBlank() -> {
                code="no_active_window"
                message="Нет активного окна. Проверь get_active_window_info; не ищи элементы по предположениям."
            }
            active == ownPackage -> {
                code="agent_panel_is_active"
                message="Сейчас открыта панель AgentBridge. Текст задачи в ней не является интерфейсом целевого приложения. Сначала найди и открой нужное приложение через list_apps и launch_app."
            }
            expectedPackage != null && expectedPackage != active -> {
                code="application_changed"
                message="Активное приложение изменилось. Проверь get_active_window_info и убедись, что это приложение нужно для текущего шага; иначе открой целевое приложение."
            }
            else -> {
                val id = args["resource_id"]?.jsonPrimitive?.contentOrNull
                if (id == null || id in observedIds[active].orEmpty()) return null
                code="unobserved_resource_id"
                message="Этот resource_id ещё не получен из интерфейса данного приложения. Не угадывай ID и не жди его по таймеру: найди элемент по тексту, описанию или editable=true либо прочитай get_ui_tree, затем используй реальный ID."
            }
        }
        val details=buildJsonObject {
            put("error",code);put("message",message);put("active_package",active ?: "unknown")
            expectedPackage?.let { put("expected_package",it) }
        }
        return buildJsonObject {
            put("isError",true)
            put("content",buildJsonArray { add(buildJsonObject { put("text",details.toString()) }) })
        }
    }
}
