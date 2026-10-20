package com.dualspace.clone.ui

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.dualspace.clone.R
import com.dualspace.clone.data.CloneManager
import com.dualspace.clone.databinding.ItemInstanceAppBinding
import com.dualspace.clone.databinding.ItemInstanceCardBinding
import com.dualspace.clone.model.Clone
import com.dualspace.clone.model.InstanceAppItem
import com.dualspace.clone.model.VirtualSpace

class InstanceCardAdapter(
    private val onAppClick: (InstanceAppItem) -> Unit,
    private val onAppLongClick: (InstanceAppItem) -> Unit,
    private val onSpaceMenuClick: (VirtualSpace, View) -> Unit
) : RecyclerView.Adapter<InstanceCardAdapter.VH>() {

    private val spaces = mutableListOf<VirtualSpace>()

    fun submit(list: List<VirtualSpace>) {
        spaces.clear()
        spaces.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemInstanceCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = spaces.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(spaces[position])

    inner class VH(private val b: ItemInstanceCardBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(space: VirtualSpace) {
            val ctx = b.root.context
            b.instanceNumberBadge.text = space.spaceNumber.toString()
            b.instanceTitle.text = "Instance ${space.spaceNumber}"

            b.btnInstanceMenu.setOnClickListener {
                onSpaceMenuClick(space, it)
            }

            b.appsContainer.removeAllViews()
            val inflater = LayoutInflater.from(ctx)

            for (app in space.apps) {
                val appBinding = ItemInstanceAppBinding.inflate(inflater, b.appsContainer, false)
                appBinding.appLabel.text = app.label

                // Load icon
                loadAppIcon(ctx, app, appBinding.appIcon)

                appBinding.root.setOnClickListener {
                    onAppClick(app)
                }
                appBinding.root.setOnLongClickListener {
                    onAppLongClick(app)
                    true
                }
                b.appsContainer.addView(appBinding.root)
            }
        }

        private fun loadAppIcon(ctx: Context, app: InstanceAppItem, target: ImageView) {
            val pm = ctx.packageManager
            if (app.cloneRef != null) {
                target.setImageDrawable(CloneManager.icon(ctx, app.cloneRef))
            } else {
                val icon = runCatching { pm.getApplicationIcon(app.packageName) }
                    .getOrElse { pm.defaultActivityIcon }
                target.setImageDrawable(icon)
            }
        }
    }
}
