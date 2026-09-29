package io.ladderairport.agent.ui

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.ladderairport.agent.databinding.PageConfigBinding
import io.ladderairport.agent.databinding.PageDashboardBinding
import io.ladderairport.agent.databinding.PageLogsBinding
import io.ladderairport.agent.databinding.PageProxyBinding

class MainPagerAdapter(
    val dashboardBinding: PageDashboardBinding,
    val configBinding: PageConfigBinding,
    val proxyBinding: PageProxyBinding,
    val logsBinding: PageLogsBinding
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    override fun getItemCount(): Int = 4

    override fun getItemViewType(position: Int): Int = position

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val view = when (viewType) {
            0 -> dashboardBinding.root
            1 -> configBinding.root
            2 -> proxyBinding.root
            3 -> logsBinding.root
            else -> throw IllegalArgumentException("Invalid position: $viewType")
        }
        (view.parent as? ViewGroup)?.removeView(view)
        view.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        return object : RecyclerView.ViewHolder(view) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {}
}
