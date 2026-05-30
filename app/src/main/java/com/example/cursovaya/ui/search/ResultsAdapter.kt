package com.example.cursovaya.ui.search

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.cursovaya.data.model.TransportRouteDto
import com.example.cursovaya.R
import com.example.cursovaya.databinding.ItemRouteBinding

class ResultsAdapter(
    @Suppress("unused") private val onClick: (TransportRouteDto) -> Unit,
    private val showRouteCode: Boolean = false,
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
            binding.textRouteNumber.text = binding.root.context.getString(R.string.route_number_format, item.routeNumber)
            binding.textRouteTitle.text = item.title
            binding.textRouteRoute.text = binding.root.context.getString(R.string.route_path_format, item.origin, item.destination)
            binding.textRouteDateTime.text = binding.root.context.getString(R.string.route_datetime_format, item.travelDate, item.departureTime, item.arrivalTime)
            binding.textRouteType.text = binding.root.context.getString(R.string.route_type_fare_format, item.transportType, item.fare)
            if (showRouteCode) {
                binding.textRouteCode.visibility = View.VISIBLE
                binding.textRouteStatus.visibility = View.VISIBLE
                binding.textRouteCode.text = binding.root.context.getString(R.string.cabinet_route_code, item.routeCode.ifBlank { "—" })
                binding.textRouteStatus.text = if (item.isAssigned) {
                    binding.root.context.getString(R.string.cabinet_route_status_assigned)
                } else {
                    binding.root.context.getString(R.string.cabinet_route_status_free)
                }
            } else {
                binding.textRouteCode.visibility = View.GONE
                binding.textRouteStatus.visibility = View.GONE
            }

            binding.buttonRouteDetails.setOnClickListener {
                this@ResultsAdapter.onClick.invoke(item)
            }

            binding.root.setOnClickListener { this@ResultsAdapter.onClick.invoke(item) }
        }
    }

    private companion object DiffCallback : DiffUtil.ItemCallback<TransportRouteDto>() {
        override fun areItemsTheSame(oldItem: TransportRouteDto, newItem: TransportRouteDto) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: TransportRouteDto, newItem: TransportRouteDto) = oldItem == newItem
    }
}
