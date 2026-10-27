package com.jarvis.agent.accessibility

/** A simplified, model-friendly view of one on-screen element (pure data so it can be unit tested). */
data class UiNode(
    val id: Int,
    val className: String,
    val text: String?,
    val description: String?,
    val viewId: String?,
    val hint: String?,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val password: Boolean = false,
    val focused: Boolean = false,
    val enabled: Boolean = true,
    val selected: Boolean = false,
) {
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2

    /** The best human label for risk checks and confirmations. */
    val label: String
        get() = listOfNotNull(text, description, hint).firstOrNull { it.isNotBlank() }
            ?: viewId?.substringAfter(":id/")?.replace('_', ' ')
            ?: className
}

data class ScreenSnapshot(
    val packageName: String,
    val appLabel: String,
    val width: Int,
    val height: Int,
    val nodes: List<UiNode>,
    val truncated: Boolean = false,
) {
    fun node(id: Int) = nodes.firstOrNull { it.id == id }

    /** Text currently typed into input fields (shown in "send" confirmations). */
    fun editableTexts(): List<String> = nodes.filter { it.editable && !it.password && !it.text.isNullOrBlank() }.map { it.text!! }

    fun allText(): String = nodes.joinToString(" ") { listOfNotNull(it.text, it.description).joinToString(" ") }

    fun findByText(query: String): UiNode? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null
        fun matches(n: UiNode, exact: Boolean) = listOfNotNull(n.text, n.description).any {
            val v = it.trim().lowercase()
            if (exact) v == q else v.contains(q)
        }
        val exact = nodes.filter { matches(it, true) }
        val pool = exact.ifEmpty { nodes.filter { matches(it, false) } }
        return pool.firstOrNull { it.clickable || it.editable } ?: pool.firstOrNull()
    }

    /** Compact text rendering for the model. */
    fun render(maxTextLen: Int = 80): String = buildString {
        append("App: ").append(appLabel).append(" (").append(packageName).append(")  Screen: ")
            .append(width).append('x').append(height).append('\n')
        for (n in nodes) {
            append('[').append(n.id).append("] ").append(n.className)
            n.text?.takeIf { it.isNotBlank() }?.let { append(" \"").append(clean(it, maxTextLen)).append('"') }
            n.description?.takeIf { it.isNotBlank() && it != n.text }?.let { append(" desc=\"").append(clean(it, maxTextLen)).append('"') }
            n.hint?.takeIf { it.isNotBlank() && it != n.text }?.let { append(" hint=\"").append(clean(it, maxTextLen)).append('"') }
            n.viewId?.let { append(" id=").append(it.substringAfter(":id/")) }
            val flags = buildList {
                if (n.clickable) add("click")
                if (n.longClickable) add("long")
                if (n.editable) add("edit")
                if (n.scrollable) add("scroll")
                if (n.checkable) add(if (n.checked) "on" else "off")
                if (n.password) add("password")
                if (n.focused) add("focused")
                if (n.selected) add("selected")
                if (!n.enabled) add("disabled")
            }
            if (flags.isNotEmpty()) append(" (").append(flags.joinToString(",")).append(')')
            append(" @").append(n.centerX).append(',').append(n.centerY).append('\n')
        }
        if (truncated) append("[more elements not shown — scroll or use screenshot]\n")
        if (nodes.isEmpty()) append("[no readable elements — try screenshot]\n")
    }

    private fun clean(s: String, max: Int) = s.replace('\n', ' ').replace("\"", "'").let { if (it.length > max) it.take(max) + "…" else it }
}
