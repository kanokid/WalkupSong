package com.walkupsong.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.R as R_material

class PlayerAdapter(
    private val players: MutableList<Player>,
    private val onPlayerSelected: (Player) -> Unit
) : RecyclerView.Adapter<PlayerAdapter.PlayerViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlayerViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.list_item_player, parent, false)
        return PlayerViewHolder(view)
    }

    override fun onBindViewHolder(holder: PlayerViewHolder, position: Int) {
        val player = players[position]
        holder.bind(player)
    }

    override fun getItemCount(): Int = players.size

    fun addPlayer(player: Player) {
        players.add(player)
        notifyItemInserted(players.size - 1)
    }

    inner class PlayerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val playerName: TextView = itemView.findViewById(R.id.textViewPlayerName)
        private val playerNumber: TextView = itemView.findViewById(R.id.textViewPlayerNumber)
        private val playerSong: TextView = itemView.findViewById(R.id.textViewPlayerSong)

        fun bind(player: Player) {
            playerName.text = player.name
            playerNumber.text = "#${player.number}"
            playerSong.text = player.songTitle ?: "No song selected"
            val color = if (player.isSelected) {
                ContextCompat.getColor(itemView.context, R_material.color.material_dynamic_secondary90)
            } else {
                ContextCompat.getColor(itemView.context, android.R.color.transparent)
            }
            itemView.setBackgroundColor(color)
            itemView.setOnClickListener { onPlayerSelected(player) }
        }
    }
}
