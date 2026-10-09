package com.noapmat.tsream.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.noapmat.tsream.R
import com.noapmat.tsream.databinding.ItemFileBinding
import com.noapmat.tsream.torrent.Fmt
import com.noapmat.tsream.torrent.TorrentFileMeta

class FileAdapter(private val onClick: (TorrentFileMeta) -> Unit) : RecyclerView.Adapter<FileAdapter.VH>() {
    private var items: List<TorrentFileMeta> = emptyList()

    fun submit(list: List<TorrentFileMeta>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(val b: ItemFileBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        Fonts.apply(b.root)
        return VH(b)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val f = items[position]
        val dir = f.path.substringBeforeLast('/', "")
        h.b.name.text = f.path.substringAfterLast('/')
        h.b.meta.text = if (dir.isEmpty()) Fmt.size(f.size) else Fmt.size(f.size) + "  \u00B7  " + dir
        h.b.icon.setImageResource(
            when {
                f.playable -> R.drawable.ic_play
                f.subtitle -> R.drawable.ic_subtitles
                else -> R.drawable.ic_file
            }
        )
        h.b.root.alpha = if (f.playable) 1f else if (f.subtitle) 0.8f else 0.55f
        h.b.root.setOnClickListener { onClick(f) }
    }
}
