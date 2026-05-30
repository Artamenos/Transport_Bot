package com.example.cursovaya.ui.search

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.cursovaya.data.model.TransportRouteDto
import com.example.cursovaya.databinding.ItemRouteBinding

class ResultsAdapter(
    private val onClick: (TransportRouteDto) -> Unit,
) : ListAdapter<TransportRouteDto, ResultsAdapter.ResultViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResultViewHolder {
        val binding = ItemRouteBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ResultViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ResultViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ResultViewHolder(private val binding: ItemRouteBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: TransportRouteDto) {
            binding.textRouteNumber.text = "Маршрут ${item.routeNumber}"
            binding.textRouteTitle.text = item.title
            binding.textRouteType.text = "${item.transportType} | Стоимость: ${item.fare}"
            binding.textRouteDetails.text = "${item.origin} → ${item.destination}\n${item.travelDate} ${item.departureTime} - ${item.arrivalTime}"

            binding.buttonRouteDetails.setOnClickListener {
                // Here we could open details, but maybe for now just show a toast or anything
                android.widget.Toast.makeText(binding.root.context, "Открытие деталей...", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private companion object DiffCallback : DiffUtil.ItemCallback<TransportRouteDto>() {
        override fun areItemsTheSame(oldItem: TransportRouteDto, newItem: TransportRouteDto) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: TransportRouteDto, newItem: TransportRouteDto) = oldItem == newItem
    }
}
