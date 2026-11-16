package app.bloccareels.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import app.bloccareels.detect.UiNode
import app.bloccareels.detect.UiSnapshot

/** Da AccessibilityNodeInfo a [UiNode]. I campi sono letti subito: il nodo non viene trattenuto. */
class NodeAdapter private constructor(
    override val viewId: String?,
    override val text: String?,
    override val contentDescription: String?,
    override val isSelected: Boolean,
    override val isScrollable: Boolean,
    override val isVisible: Boolean,
    override val heightPx: Int,
) : UiNode {
    companion object {
        private val rect = Rect()

        fun from(n: AccessibilityNodeInfo): NodeAdapter {
            n.getBoundsInScreen(rect)
            return NodeAdapter(
                viewId = shortId(n.viewIdResourceName),
                text = n.text?.toString(),
                contentDescription = n.contentDescription?.toString(),
                isSelected = n.isSelected,
                isScrollable = n.isScrollable,
                isVisible = n.isVisibleToUser,
                heightPx = rect.height(),
            )
        }

        fun shortId(full: String?): String? = full?.substringAfter(":id/", missingDelimiterValue = full)
    }
}

/** Istantanea della finestra applicativa in primo piano. */
class NodeSnapshot(
    private val root: AccessibilityNodeInfo,
    override val screenHeightPx: Int,
) : UiSnapshot {

    override val packageName: String = root.packageName?.toString().orEmpty()

    private val nodeCache = HashMap<String, List<AccessibilityNodeInfo>>()

    private fun rawById(id: String): List<AccessibilityNodeInfo> = nodeCache.getOrPut(id) {
        try {
            root.findAccessibilityNodeInfosByViewId("$packageName:id/$id").orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun findByViewId(id: String): List<UiNode> = rawById(id).map(NodeAdapter::from)

    override fun walk(maxNodes: Int): Sequence<UiNode> = sequence {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < maxNodes) {
            val n = queue.removeFirst()
            visited++
            yield(NodeAdapter.from(n))
            for (i in 0 until n.childCount) {
                val child = try { n.getChild(i) } catch (_: Exception) { null }
                if (child != null) queue.addLast(child)
            }
        }
    }

    override fun fingerprint(viewId: String): Int? {
        val container = rawById(viewId).firstOrNull { it.isVisibleToUser } ?: return null
        val texts = ArrayList<String>(3)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        for (i in 0 until container.childCount) container.getChild(i)?.let(queue::addLast)
        var visited = 0
        while (queue.isNotEmpty() && texts.size < 3 && visited < FINGERPRINT_MAX_NODES) {
            val n = queue.removeFirst()
            visited++
            if (n.isVisibleToUser) {
                n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(texts::add)
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::addLast)
        }
        if (texts.isEmpty()) return null
        return texts.joinToString("\u0001").hashCode()
    }

    companion object {
        private const val FINGERPRINT_MAX_NODES = 300
    }
}
