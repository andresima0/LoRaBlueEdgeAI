package com.android.lorablueedge.weather

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Dialog for the weather settings: OpenWeather API key, the totem's
 * coordinates and, optionally, the roof area that drains into the cistern.
 */
object WeatherConfigDialog {

    fun show(context: Context, onSaved: () -> Unit) {
        val store = WeatherConfigStore(context)
        val current = store.load()

        val dp = context.resources.displayMetrics.density
        val pad = (20 * dp).toInt()

        fun label(text: String, topDp: Int = 12) = TextView(context).apply {
            this.text = text
            setPadding(0, (topDp * dp).toInt(), 0, (4 * dp).toInt())
        }

        fun field(hintText: String, value: String, signed: Boolean = false, numeric: Boolean = true) =
            EditText(context).apply {
                hint = hintText
                inputType = when {
                    !numeric -> InputType.TYPE_CLASS_TEXT
                    signed -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
                            InputType.TYPE_NUMBER_FLAG_SIGNED
                    else -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                }
                setText(value)
            }

        val keyInput = field("OpenWeather API key", current.apiKey, numeric = false)
        val latInput = field("e.g. -22.7390", current.latitude?.toString() ?: "", signed = true)
        val lonInput = field("e.g. -47.3331", current.longitude?.toString() ?: "", signed = true)
        val roofInput = field("e.g. 80 (optional)", current.roofAreaM2?.toString() ?: "")

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(label("API key (openweathermap.org)", topDp = 0))
            addView(keyInput)
            addView(label("Latitude (decimal degrees)"))
            addView(latInput)
            addView(label("Longitude (decimal degrees)"))
            addView(lonInput)
            addView(label("Roof area draining into the cistern, in m². Needed to credit rain to the autonomy."))
            addView(roofInput)
        }

        AlertDialog.Builder(context)
            .setTitle("Weather settings")
            .setView(root)
            .setPositiveButton("Save") { _, _ ->
                fun number(edit: EditText) = edit.text.toString().trim().replace(",", ".").toDoubleOrNull()

                val key = keyInput.text.toString().trim()
                val lat = number(latInput)
                val lon = number(lonInput)
                val roofText = roofInput.text.toString().trim()
                val roof = number(roofInput)

                val error = when {
                    key.isEmpty() -> "Enter the API key"
                    lat == null || lat < -90.0 || lat > 90.0 -> "Latitude must be between -90 and 90"
                    lon == null || lon < -180.0 || lon > 180.0 -> "Longitude must be between -180 and 180"
                    roofText.isNotEmpty() && (roof == null || roof <= 0.0) ->
                        "Roof area must be greater than zero (or empty)"
                    else -> null
                }
                if (error != null) {
                    Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                store.save(WeatherConfig(key, lat, lon, if (roofText.isEmpty()) null else roof))
                onSaved()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}