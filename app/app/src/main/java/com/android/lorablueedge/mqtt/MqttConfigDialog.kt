package com.android.lorablueedge.mqtt

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Floating dialogs (same look as WeatherConfigDialog) for the settings of
 * each IoT platform. Opened from the "Platform settings" submenu of the main
 * screen: one dialog per platform, so ThingsBoard and Konker are edited
 * independently and each keeps its own "enabled" flag.
 *
 * Saving writes straight to [MqttConfigStore]; [onSaved] then lets the caller
 * tell the service to reload the enabled platforms. The dialog stays open when
 * validation fails, so what the user typed is not lost.
 *
 * The form is wrapped in a ScrollView because the Konker section has many
 * fields and the on-screen keyboard leaves little room on a 7" display.
 */
object MqttConfigDialog {

    fun show(context: Context, platform: IotPlatform, onSaved: () -> Unit) {
        val store = MqttConfigStore(context)
        when (platform) {
            IotPlatform.THINGSBOARD -> showThingsBoard(context, store, onSaved)
            IotPlatform.KONKER -> showKonker(context, store, onSaved)
        }
    }

    // ── ThingsBoard ──────────────────────────────────────────────────────────

    private fun showThingsBoard(context: Context, store: MqttConfigStore, onSaved: () -> Unit) {
        val current = store.loadPlatform(IotPlatform.THINGSBOARD) as MqttConfig.ThingsBoard
        val form = Form(context)

        val enabled = form.checkBox("Enable publishing to ThingsBoard", current.enabled)
        form.label("Broker")
        val server = form.field("Server", current.server)
        val port = form.field("Port (default 1883)", current.port, numeric = true)
        form.label("Device access tokens")
        val cisternToken = form.field("Cistern access token", current.cisternToken)
        val tankToken = form.field("Tank access token", current.tankToken)

        showDialog(
            context = context,
            store = store,
            title = "ThingsBoard",
            form = form,
            build = {
                MqttConfig.ThingsBoard(
                    server = server.text.toString().trim(),
                    port = port.text.toString().trim().ifEmpty { "1883" },
                    cisternToken = cisternToken.text.toString().trim(),
                    tankToken = tankToken.text.toString().trim(),
                    enabled = enabled.isChecked
                )
            },
            validate = { config ->
                when {
                    !config.enabled -> null
                    config.server.isBlank() -> "ThingsBoard: server address is required"
                    !config.isCisternConfigured && !config.isTankConfigured ->
                        "ThingsBoard: enter at least one device access token"
                    else -> null
                }
            },
            onSaved = onSaved
        )
    }

    // ── Konker ───────────────────────────────────────────────────────────────

    private fun showKonker(context: Context, store: MqttConfigStore, onSaved: () -> Unit) {
        val current = store.loadPlatform(IotPlatform.KONKER) as MqttConfig.Konker
        val form = Form(context)

        val enabled = form.checkBox("Enable publishing to Konker", current.enabled)
        form.label("Broker")
        val server = form.field("Server", current.server)
        val port = form.field("Port (default 1883)", current.port, numeric = true)
        form.label("Credentials")
        val user = form.field("Username", current.user)
        val pass = form.passwordField("Password", current.pass)
        form.label("Device topics")
        val cisternTopic = form.field("Cistern topic", current.cisternTopic)
        val tankTopic = form.field("Tank topic", current.tankTopic)

        showDialog(
            context = context,
            store = store,
            title = "Konker",
            form = form,
            build = {
                MqttConfig.Konker(
                    server = server.text.toString().trim(),
                    port = port.text.toString().trim().ifEmpty { "1883" },
                    user = user.text.toString().trim(),
                    pass = pass.text.toString(),
                    cisternTopic = cisternTopic.text.toString().trim(),
                    tankTopic = tankTopic.text.toString().trim(),
                    enabled = enabled.isChecked
                )
            },
            validate = { config ->
                when {
                    !config.enabled -> null
                    config.server.isBlank() -> "Konker: server address is required"
                    !config.isCisternConfigured && !config.isTankConfigured ->
                        "Konker: enter username and at least one device topic"
                    else -> null
                }
            },
            onSaved = onSaved
        )
    }

    // ── Shared dialog logic ──────────────────────────────────────────────────

    private fun <T : MqttConfig> showDialog(
        context: Context,
        store: MqttConfigStore,
        title: String,
        form: Form,
        build: () -> T,
        validate: (T) -> String?,
        onSaved: () -> Unit
    ) {
        val scroll = ScrollView(context).apply { addView(form.root) }

        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("Save", null) // real listener set below
            .setNegativeButton("Cancel", null)
            .create()

        // The click listener is attached after show() so a validation error
        // does not dismiss the dialog (the default behaviour of the button).
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val config = build()
                val error = validate(config)
                if (error != null) {
                    Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                store.save(config)
                val state = if (config.enabled) "enabled" else "disabled"
                Toast.makeText(
                    context, "$title saved — publishing $state", Toast.LENGTH_SHORT
                ).show()
                dialog.dismiss()
                onSaved()
            }
        }
        dialog.show()
    }

    /** Small helper that builds the vertical form and keeps the views it creates. */
    private class Form(private val context: Context) {

        private val dp = context.resources.displayMetrics.density

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * dp).toInt()
            setPadding(pad, pad, pad, pad)
        }

        fun checkBox(text: String, checked: Boolean) = CheckBox(context).apply {
            this.text = text
            isChecked = checked
            root.addView(this)
        }

        fun label(text: String) = TextView(context).apply {
            this.text = text
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, (14 * dp).toInt(), 0, (4 * dp).toInt())
            root.addView(this)
        }

        fun field(hint: String, value: String, numeric: Boolean = false) = EditText(context).apply {
            this.hint = hint
            setText(value)
            if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
            root.addView(this)
        }

        fun passwordField(hint: String, value: String) = EditText(context).apply {
            this.hint = hint
            setText(value)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            root.addView(this)
        }
    }
}