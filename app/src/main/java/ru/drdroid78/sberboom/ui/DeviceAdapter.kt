package ru.drdroid78.sberboom.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import ru.drdroid78.sberboom.R
import ru.drdroid78.sberboom.discovery.SpeakerDevice

class DeviceAdapter(
    private val onClick: (SpeakerDevice) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.Holder>() {

    private val items = mutableListOf<SpeakerDevice>()

    fun submit(list: List<SpeakerDevice>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position], onClick)
    }

    override fun getItemCount(): Int = items.size

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val name: TextView = itemView.findViewById(R.id.deviceName)
        private val details: TextView = itemView.findViewById(R.id.deviceDetails)

        fun bind(device: SpeakerDevice, onClick: (SpeakerDevice) -> Unit) {
            name.text = device.displayTitle
            val parts = ArrayList<String>(4)
            parts += device.host
            if (device.model.isNotBlank()) parts += device.model
            if (device.firmware.isNotBlank()) parts += "fw ${device.firmware}"
            if (device.id.isNotBlank()) parts += "id ${device.id}"
            details.text = parts.joinToString(" · ")
            itemView.setOnClickListener { onClick(device) }
        }
    }
}
