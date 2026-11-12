package com.example.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.GridLayout
import android.widget.ScrollView
import android.widget.Toast

/**
 * قائمة كل التطبيقات المثبتة.
 * - بدون EXTRA_SLOT: الضغط على التطبيق يفتحه.
 * - مع EXTRA_SLOT: الضغط يحطه في المربع المحدد بالواجهة الرئيسية.
 */
class AppDrawerActivity : Activity() {

    companion object {
        const val EXTRA_SLOT = "slot"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val slot = intent.getStringExtra(EXTRA_SLOT)
        val store = AppStore(this)
        val pm = packageManager

        val apps = pm.getInstalledApplications(0)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .filter { it.packageName != packageName }
            .sortedBy { pm.getApplicationLabel(it).toString() }

        val grid = GridLayout(this).apply {
            columnCount = 5
            setPadding(24, 24, 24, 24)
        }

        for (info in apps) {
            val label = pm.getApplicationLabel(info).toString()
            val button = Button(this).apply {
                text = label
                textSize = 18f
                setOnClickListener {
                    if (slot != null) {
                        store.set(slot, info.packageName)
                        Toast.makeText(this@AppDrawerActivity, "تم تعيين $label", Toast.LENGTH_SHORT).show()
                        finish()
                    } else {
                        val launch = pm.getLaunchIntentForPackage(info.packageName)
                        if (launch != null) {
                            startActivity(launch)
                        }
                        finish()
                    }
                }
            }
            grid.addView(button, GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(8, 8, 8, 8)
            })
        }

        setContentView(ScrollView(this).apply { addView(grid) })
    }
}
