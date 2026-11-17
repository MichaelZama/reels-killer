package app.bloccareels.diag

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Exports the tree of the foreground window, to write or update rules.
 * Texts are never written, only their length. Content descriptions only when short.
 */
object TreeDumper {
    private const val MAX_NODES = 5000
    private const val MAX_DEPTH = 80
    private const val KEEP_FILES = 5
    private const val DESC_MAX = 30

    fun dumpsDir(context: Context): File = File(context.filesDir, "dumps").apply { mkdirs() }

    fun latest(context: Context): File? =
        dumpsDir(context).listFiles { f -> f.isFile && f.name.endsWith(".txt") }?.maxByOrNull { it.lastModified() }

    /** Writes the file and returns it with the number of exported nodes. */
    fun dump(context: Context, root: AccessibilityNodeInfo): Pair<File, Int> {
        val lines = ArrayList<String>()
        val rect = Rect()
        var count = 0

        fun visit(n: AccessibilityNodeInfo, depth: Int) {
            if (count >= MAX_NODES || depth > MAX_DEPTH) return
            count++
            n.getBoundsInScreen(rect)
            val sb = StringBuilder()
            repeat(depth) { sb.append("  ") }
            sb.append(n.className?.toString()?.substringAfterLast('.') ?: "?")
            sb.append(" id=").append(n.viewIdResourceName?.substringAfter(":id/") ?: "-")
            sb.append(" sel=").append(if (n.isSelected) 1 else 0)
            sb.append(" scr=").append(if (n.isScrollable) 1 else 0)
            sb.append(" vis=").append(if (n.isVisibleToUser) 1 else 0)
            sb.append(" h=").append(rect.height())
            n.text?.let { if (it.isNotEmpty()) sb.append(" text=<").append(it.length).append(" chars>") }
            n.contentDescription?.let { d ->
                if (d.isNotEmpty()) {
                    if (d.length < DESC_MAX) {
                        sb.append(" desc=\"").append(d.toString().replace('"', '\'').replace('\n', ' ')).append('"')
                    } else {
                        sb.append(" desc=<").append(d.length).append(" chars>")
                    }
                }
            }
            lines += sb.toString()
            for (i in 0 until n.childCount) {
                val child = try { n.getChild(i) } catch (_: Exception) { null } ?: continue
                visit(child, depth + 1)
            }
        }

        visit(root, 0)
        val now = LocalDateTime.now()
        val header = "# package=${root.packageName} date=${now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).take(19)} nodes=$count"
        val name = "tree-${root.packageName}-${now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.txt"
        val file = File(dumpsDir(context), name)
        file.writeText((listOf(header) + lines).joinToString("\n", postfix = "\n"))
        prune(context)
        return file to count
    }

    private fun prune(context: Context) {
        val files = dumpsDir(context).listFiles { f -> f.isFile }?.sortedByDescending { it.lastModified() } ?: return
        files.drop(KEEP_FILES).forEach { it.delete() }
    }
}
