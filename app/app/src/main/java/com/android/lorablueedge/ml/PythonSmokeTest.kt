package com.android.lorablueedge.ml

import android.util.Log
import com.chaquo.python.Python
import kotlin.concurrent.thread

/**
 * Temporary: runs lorablue_ml.smoke_test on a background thread and reports
 * the outcome. Python itself is started by PyApplication (see the manifest).
 */
object PythonSmokeTest {

    private const val TAG = "PY_SMOKE"

    fun run(onResult: (success: Boolean, text: String) -> Unit) {
        thread(name = "python-smoke-test") {
            val result = try {
                val json = Python.getInstance()
                    .getModule("lorablue_ml.smoke_test")
                    .callAttr("run")
                    .toString()
                true to json
            } catch (e: Throwable) {
                // Throwable on purpose: a missing native library is an
                // UnsatisfiedLinkError, which is an Error, not an Exception.
                false to "${e.javaClass.simpleName}: ${e.message}"
            }

            Log.i(TAG, "success=${result.first}\n${result.second}")
            onResult(result.first, result.second)
        }
    }
}