package com.pluginslab.agentbridge.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect
import com.pluginslab.agentbridge.agent.UiTargetRules
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object NodeRegistry {
    private val nodes = ConcurrentHashMap<Int, AccessibilityNodeInfo>()
    private val counter = AtomicInteger(0)
    private val evidence = ConcurrentHashMap<Int, String>()

    private fun identity(node: AccessibilityNodeInfo): String {
        val rect = Rect(); node.getBoundsInScreen(rect)
        return "${node.packageName}|${node.windowId}|${node.viewIdResourceName}|${node.className}|${node.text}|${node.contentDescription}|$rect|${node.isClickable}|${node.isLongClickable}|${node.isEditable}|${node.isEnabled}"
    }

    fun clear() {
        nodes.clear()
        evidence.clear()
        counter.set(0)
    }

    fun put(node: AccessibilityNodeInfo): Int {
        val id = counter.incrementAndGet()
        nodes[id] = node
        evidence[id] = identity(node)
        return id
    }

    fun get(id: Int): AccessibilityNodeInfo? = nodes[id]
    fun targetIdentity(id: Int): String? = evidence[id]

    fun resourceIds(): Set<String> = nodes.values.mapNotNull {
        runCatching { it.viewIdResourceName }.getOrNull()
    }.toSet()

    fun targetError(id: Int, requireNamedTarget: Boolean = false): String? {
        val node = nodes[id] ?: return "ID узла устарел или не существует. Сначала найди элемент заново через find_nodes."
        if (!node.refresh() || identity(node) != evidence[id])
            return "Элемент изменился после снимка. Дождись загрузки и заново найди нужную кнопку; старый ID не используй."
        val rect = Rect(); node.getBoundsInScreen(rect)
        if(!UiTargetRules.actionable(node.isVisibleToUser,rect.width(),rect.height()))
            return "Элемент скрыт или вне экрана. Не нажимай его: раскрой верхнюю панель или прокрути к элементу, затем найди видимый узел заново."
        if (!node.isEnabled) return "Элемент пока недоступен. Дождись загрузки и найди доступную кнопку заново."
        if (requireNamedTarget && !UiTargetRules.namedTapTarget(
                hasOwnLabel(node), hasDescendantLabel(node), node.isClickable || node.isLongClickable,
                node.isEditable, node.isEnabled))
            return "Нельзя нажимать пустой блок без подписи и понятного назначения: экран может ещё загружаться. Подожди через agent_delay, затем прочитай свежее дерево и выбери подписанный элемент. Не подбирай соседние ID."
        return null
    }

    private fun hasOwnLabel(node: AccessibilityNodeInfo): Boolean =
        !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()

    private fun hasDescendantLabel(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (depth >= 3) return false
        for (index in 0 until minOf(node.childCount, 20)) {
            val child = node.getChild(index) ?: continue
            try {
                if (child.isVisibleToUser && (hasOwnLabel(child) || hasDescendantLabel(child, depth + 1))) return true
            } finally { child.recycle() }
        }
        return false
    }

    /** App semantics only: changing system clocks must not make a loading screen look ready. */
    fun semanticSignature(packageName: String?): String = nodes.values.filter {
        it.packageName?.toString() == packageName && it.isVisibleToUser && hasOwnLabel(it)
    }.joinToString("\n") { identity(it) }

    fun hiddenEditableHints(packageName: String): JsonArray = JsonArray(nodes.values.mapNotNull { node ->
        runCatching {
            if(node.packageName?.toString() != packageName || !node.isEditable) return@runCatching null
            val rect = Rect(); node.getBoundsInScreen(rect)
            if(UiTargetRules.actionable(node.isVisibleToUser,rect.width(),rect.height())) return@runCatching null
            buildJsonObject {
                node.viewIdResourceName?.let { put("resource_id",it) }
                put("position",UiTargetRules.position(rect.top,rect.width(),rect.height()))
                put("visible",false)
            }
        }.getOrNull()
    })

    val size: Int get() = nodes.size
}
