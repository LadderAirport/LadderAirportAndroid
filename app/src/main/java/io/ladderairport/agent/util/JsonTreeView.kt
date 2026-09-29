package io.ladderairport.agent.util

import android.content.Context
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import io.ladderairport.agent.R
import org.json.JSONArray
import org.json.JSONObject

/**
 * Renders JSON as an expandable hierarchical tree inside a LinearLayout.
 */
object JsonTreeView {

    fun render(container: LinearLayout, root: Any?, emptyMessage: String = "空配置") {
        container.removeAllViews()
        if (root == null) {
            addMessage(container, emptyMessage)
            return
        }
        when (root) {
            is JSONObject -> {
                if (root.length() == 0) {
                    addMessage(container, emptyMessage)
                } else {
                    appendObjectChildren(container, root, depth = 0, expandedByDefault = true)
                }
            }
            is JSONArray -> {
                if (root.length() == 0) {
                    addMessage(container, emptyMessage)
                } else {
                    appendArrayChildren(container, root, depth = 0, expandedByDefault = true)
                }
            }
            else -> addLeaf(container, "value", root, depth = 0)
        }
    }

    fun renderMessage(container: LinearLayout, message: String) {
        container.removeAllViews()
        addMessage(container, message)
    }

    private fun appendObjectChildren(
        parent: LinearLayout,
        obj: JSONObject,
        depth: Int,
        expandedByDefault: Boolean
    ) {
        val keys = obj.keys().asSequence().toList().sorted()
        for (key in keys) {
            appendNode(parent, key, obj.opt(key), depth, expandedByDefault)
        }
    }

    private fun appendArrayChildren(
        parent: LinearLayout,
        arr: JSONArray,
        depth: Int,
        expandedByDefault: Boolean
    ) {
        for (i in 0 until arr.length()) {
            appendNode(parent, "[$i]", arr.opt(i), depth, expandedByDefault)
        }
    }

    private fun appendNode(
        parent: LinearLayout,
        key: String,
        value: Any?,
        depth: Int,
        expandedByDefault: Boolean
    ) {
        when (value) {
            is JSONObject -> addBranch(
                parent = parent,
                key = key,
                typeHint = "{${value.length()}}",
                depth = depth,
                expandedByDefault = expandedByDefault && depth < 1,
                bindChildren = { childContainer ->
                    appendObjectChildren(childContainer, value, depth + 1, expandedByDefault = depth < 1)
                }
            )
            is JSONArray -> addBranch(
                parent = parent,
                key = key,
                typeHint = "[${value.length()}]",
                depth = depth,
                expandedByDefault = expandedByDefault && depth < 1,
                bindChildren = { childContainer ->
                    appendArrayChildren(childContainer, value, depth + 1, expandedByDefault = false)
                }
            )
            else -> addLeaf(parent, key, value, depth)
        }
    }

    private fun addBranch(
        parent: LinearLayout,
        key: String,
        typeHint: String,
        depth: Int,
        expandedByDefault: Boolean,
        bindChildren: (LinearLayout) -> Unit
    ) {
        val context = parent.context
        val inflater = LayoutInflater.from(context)
        val row = inflater.inflate(R.layout.item_json_tree_node, parent, false)
        applyIndent(row, depth)

        val ivExpand = row.findViewById<ImageView>(R.id.ivExpand)
        val tvKey = row.findViewById<TextView>(R.id.tvKey)
        val tvTypeHint = row.findViewById<TextView>(R.id.tvTypeHint)
        val tvValue = row.findViewById<TextView>(R.id.tvValue)

        ivExpand.visibility = View.VISIBLE
        tvKey.text = key
        tvTypeHint.text = typeHint
        tvTypeHint.visibility = View.VISIBLE
        tvValue.visibility = View.GONE

        val children = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (expandedByDefault) View.VISIBLE else View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        bindChildren(children)
        setExpandedIcon(ivExpand, expandedByDefault)

        val toggle = View.OnClickListener {
            val nowExpanded = children.visibility != View.VISIBLE
            children.visibility = if (nowExpanded) View.VISIBLE else View.GONE
            setExpandedIcon(ivExpand, nowExpanded)
        }
        row.setOnClickListener(toggle)
        ivExpand.setOnClickListener(toggle)

        parent.addView(row)
        parent.addView(children)
    }

    private fun addLeaf(parent: LinearLayout, key: String, value: Any?, depth: Int) {
        val inflater = LayoutInflater.from(parent.context)
        val row = inflater.inflate(R.layout.item_json_tree_node, parent, false)
        applyIndent(row, depth)

        row.findViewById<ImageView>(R.id.ivExpand).visibility = View.INVISIBLE
        row.findViewById<TextView>(R.id.tvKey).text = key
        row.findViewById<TextView>(R.id.tvTypeHint).visibility = View.GONE
        row.findViewById<TextView>(R.id.tvValue).apply {
            visibility = View.VISIBLE
            text = formatLeaf(value)
            setTextColor(leafColor(parent.context, value))
        }
        parent.addView(row)
    }

    private fun addMessage(parent: LinearLayout, message: String) {
        val tv = TextView(parent.context).apply {
            text = message
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setTextColor(resolveAttrColor(parent.context, com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(0, dp(parent.context, 8), 0, dp(parent.context, 8))
        }
        parent.addView(tv)
    }

    private fun applyIndent(row: View, depth: Int) {
        val pad = dp(row.context, 12) * depth
        row.setPadding(
            row.paddingLeft + pad,
            row.paddingTop,
            row.paddingRight,
            row.paddingBottom
        )
    }

    private fun setExpandedIcon(iv: ImageView, expanded: Boolean) {
        iv.rotation = if (expanded) 90f else 0f
        iv.contentDescription = if (expanded) "收起" else "展开"
    }

    private fun formatLeaf(value: Any?): String {
        return when (value) {
            null, JSONObject.NULL -> "null"
            is String -> "\"$value\""
            is Boolean, is Number -> value.toString()
            else -> value.toString()
        }
    }

    private fun leafColor(context: Context, value: Any?): Int {
        val attr = when (value) {
            null, JSONObject.NULL -> com.google.android.material.R.attr.colorOnSurfaceVariant
            is Boolean -> com.google.android.material.R.attr.colorTertiary
            is Number -> com.google.android.material.R.attr.colorSecondary
            is String -> com.google.android.material.R.attr.colorOnSurface
            else -> com.google.android.material.R.attr.colorOnSurface
        }
        return resolveAttrColor(context, attr)
    }

    private fun resolveAttrColor(context: Context, attr: Int): Int {
        val typed = TypedValue()
        context.theme.resolveAttribute(attr, typed, true)
        return if (typed.resourceId != 0) {
            ContextCompat.getColor(context, typed.resourceId)
        } else {
            typed.data
        }
    }

    private fun dp(context: Context, value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }
}
