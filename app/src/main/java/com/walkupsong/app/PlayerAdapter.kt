package com.walkupsong.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import java.text.NumberFormat
import com.google.android.material.R as MaterialR

class PlayerAdapter(
    private val players: List<Player>,
    private val onPlayerSelected: (Player) -> Unit,
    private val onPlayerMenu: (Player, View) -> Unit
) : RecyclerView.Adapter<PlayerAdapter.PlayerViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = PlayerViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.list_item_player, parent, false)
    )

    override fun onBindViewHolder(holder: PlayerViewHolder, position: Int) =
        holder.bind(players[position], position)

    override fun getItemCount() = players.size

    inner class PlayerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun bind(player: Player, position: Int) {
            val context = itemView.context
            itemView.findViewById<TextView>(R.id.textViewPlayerName).text =
                context.getString(R.string.player_heading, player.name, player.number)
            itemView.findViewById<TextView>(R.id.textViewPlayerNumber).text = NumberFormat.getIntegerInstance().format(position + 1)
            val song = player.songTitle ?: context.getString(R.string.no_song)
            itemView.findViewById<TextView>(R.id.textViewPlayerSong).text = song
            val color = if (player.isSelected) MaterialR.attr.colorSecondaryContainer
                else MaterialR.attr.colorSurfaceVariant
            (itemView as MaterialCardView).setCardBackgroundColor(MaterialColors.getColor(itemView, color))
            itemView.isSelected = player.isSelected
            itemView.contentDescription = context.getString(R.string.row_description,
                position + 1, player.name, player.number, song)
            itemView.setOnClickListener { onPlayerSelected(player) }
            itemView.findViewById<View>(R.id.buttonPlayerMenu).apply {
                contentDescription = context.getString(R.string.player_options, player.name)
                setOnClickListener { onPlayerMenu(player, it) }
            }
        }
    }
}
