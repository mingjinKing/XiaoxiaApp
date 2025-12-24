package com.derbi.xiaoxia.ui

import android.graphics.Color
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.derbi.xiaoxia.R

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<Toolbar>(R.id.toolbar_settings)
        setSupportActionBar(toolbar)
        // 隐藏默认标题，因为我们使用了居中的 TextView
        supportActionBar?.setDisplayShowTitleEnabled(false)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        // 接收主题颜色并同步
        val themeColorRes = intent.getIntExtra("theme_color_res", R.color.theme_blue)
        applyTheme(themeColorRes)

        toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun applyTheme(colorResId: Int) {
        val color = ContextCompat.getColor(this, colorResId)
        val toolbar = findViewById<Toolbar>(R.id.toolbar_settings)
        val titleTextView = findViewById<TextView>(R.id.toolbar_title_settings)

        // 1. 设置背景色
        toolbar.setBackgroundColor(color)
        window.statusBarColor = color

        // 2. 根据背景计算内容颜色
        val isWhiteTheme = colorResId == R.color.theme_white
        val contentColor = if (isWhiteTheme) Color.BLACK else Color.WHITE

        // 3. 同步标题、返回键、状态栏
        titleTextView.setTextColor(contentColor)
        toolbar.navigationIcon?.setTint(contentColor)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = isWhiteTheme
    }
}
