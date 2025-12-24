package com.derbi.xiaoxia

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val quotes = listOf(
            "“生命中最重要的两天，是你出生的那天，和你明白自己为什么出生的那天。”",
            "“凡是过往，皆为序章。”",
            "“追风赶月莫停留，平芜尽处是春山。”",
            "“所谓自由，不是随心所欲，而是自我主宰。”"
        )

        // 动态设置名言
        findViewById<TextView>(R.id.tv_quote).text = quotes.random()

        // 2 秒后跳转到主界面
        Handler(Looper.getMainLooper()).postDelayed({
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
            finish() // 销毁当前页，防止返回键回到启动页
            // 添加平滑过渡动画
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }, 1500)
    }
}
