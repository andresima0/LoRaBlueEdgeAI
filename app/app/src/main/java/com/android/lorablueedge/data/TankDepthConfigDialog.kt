package com.android.lorablueedge.data

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Small dialog opened from the gear icon next to "Water Level" on each
 * card. Two fields:
 *
 *  - Total depth (required): sensor-to-bottom distance in meters. Needed
 *    to turn the raw TOF distance into a water column and a percentage.
 *  - Capacity (optional): litres the reservoir holds when full. Needed to
 *    show litres and the consumption rate in L/h. Leave empty to clear.
 *
 * Not a DialogFragment because it needs no lifecycle persistence across
 * rotation — it's a quick prompt triggered directly from a click listener
 * in MainActivity.
 */
object TankDepthConfigDialog {

    /**
     * @param deviceId    DEVICE_ID_CISTERN or DEVICE_ID_TANK
     * @param deviceLabel "Cistern" or "Tank" — used in dialog title/hint
     * @param onSaved     Invoked with the new total depth (meters) right after
     *                    saving; the capacity is already stored by then.
     */
    fun show(
        context: Context,
        deviceId: Int,
        deviceLabel: String,
        onSaved: (Double) -> Unit
    ) {
        val store = TankDepthConfigStore(context)
        val currentDepth = store.getTotalDepth(deviceId)
        val currentCapacity = store.getCapacityLiters(deviceId)

        val dp = context.resources.displayMetrics.density
        val pad = (20 * dp).toInt()

        fun label(text: String, topDp: Int = 0) = TextView(context).apply {
            this.text = text
            setPadding(0, (topDp * dp).toInt(), 0, (4 * dp).toInt())
        }

        fun decimalField(hintText: String, value: Double?, decimals: Int) =
            EditText(context).apply {
                hint = hintText
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                value?.let { setText(String.format("%.${decimals}f", it)) }
            }

        val depthInput = decimalField("e.g. 2.50", currentDepth, 2)
        val capacityInput = decimalField("e.g. 1000 (optional)", currentCapacity, 0)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(label("Total depth: distance from the sensor (at the top) to the bottom of the $deviceLabel, in meters."))
            addView(depthInput)
            addView(label("Capacity: litres the $deviceLabel holds when full. Needed for litres and consumption (L/h).", topDp = 16))
            addView(capacityInput)
        }

        AlertDialog.Builder(context)
            .setTitle("Size — $deviceLabel")
            .setView(root)
            .setPositiveButton("Save") { _, _ ->
                val depth = depthInput.text.toString().trim().replace(",", ".").toDoubleOrNull()
                if (depth == null || depth <= 0.0) {
                    Toast.makeText(
                        context,
                        "Enter a depth greater than zero",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }

                val capacityText = capacityInput.text.toString().trim().replace(",", ".")
                val capacity = capacityText.toDoubleOrNull()
                if (capacityText.isNotEmpty() && (capacity == null || capacity <= 0.0)) {
                    Toast.makeText(
                        context,
                        "Capacity must be a number greater than zero (or empty)",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }

                store.setTotalDepth(deviceId, depth)
                store.setCapacityLiters(deviceId, capacity) // null clears it
                onSaved(depth)

                val capacitySummary = capacity?.let { ", ${String.format("%.0f", it)} L" } ?: ""
                Toast.makeText(
                    context,
                    "$deviceLabel saved: ${String.format("%.2f", depth)} m$capacitySummary",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}