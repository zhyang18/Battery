package com.battery.analysis.manager

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * 应用全局多语言管理工具类。
 * 采用 AndroidX 官方推荐的 [AppCompatDelegate.setApplicationLocales] 标准，
 * 支持在跟随系统、简体中文、English 三种语言环境之间实时无缝切换并持久化存储。
 */
object LanguageManager {

    private const val PREFS_NAME = "battery_app_settings"
    private const val KEY_LANGUAGE_MODE = "app_language_mode"

    /** 语言模式：跟随系统 (默认) */
    const val MODE_FOLLOW_SYSTEM = 0

    /** 语言模式：简体中文 */
    const val MODE_SIMPLIFIED_CHINESE = 1

    /** 语言模式：English */
    const val MODE_ENGLISH = 2

    /**
     * 获取当前持久化保存的应用语言模式。
     *
     * @param context 应用程序上下文
     * @return 语言模式整型常量（[MODE_FOLLOW_SYSTEM]、[MODE_SIMPLIFIED_CHINESE] 或 [MODE_ENGLISH]）
     */
    fun getLanguageMode(context: Context): Int {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_LANGUAGE_MODE, MODE_FOLLOW_SYSTEM)
    }

    /**
     * 应用并保存指定的语言模式。
     *
     * @param context 应用程序上下文
     * @param mode 目标语言模式整型常量
     */
    fun setLanguageMode(context: Context, mode: Int) {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_LANGUAGE_MODE, mode).apply()

        val locales = when (mode) {
            MODE_SIMPLIFIED_CHINESE -> LocaleListCompat.forLanguageTags("zh-CN")
            MODE_ENGLISH -> LocaleListCompat.forLanguageTags("en")
            else -> LocaleListCompat.getEmptyLocaleList()
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    /**
     * 应用启动时初始化并应用保存的语言配置。
     *
     * @param context 应用程序上下文
     */
    fun initLanguage(context: Context) {
        val mode = getLanguageMode(context)
        val locales = when (mode) {
            MODE_SIMPLIFIED_CHINESE -> LocaleListCompat.forLanguageTags("zh-CN")
            MODE_ENGLISH -> LocaleListCompat.forLanguageTags("en")
            else -> LocaleListCompat.getEmptyLocaleList()
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    /**
     * 获取当前语言模式的本地化显示名称。
     *
     * @param context 应用程序上下文
     * @param mode 目标语言模式
     * @return 友好展示文本（如 "跟随系统"、"简体中文"、"English"）
     */
    fun getLanguageTitle(context: Context, mode: Int): String {
        return when (mode) {
            MODE_SIMPLIFIED_CHINESE -> "简体中文"
            MODE_ENGLISH -> "English"
            else -> {
                val sysLocale = Locale.getDefault()
                val isZh = sysLocale.language.startsWith("zh", ignoreCase = true)
                val base = context.getString(com.battery.analysis.R.string.lang_follow_system)
                if (isZh) {
                    "$base (简体中文)"
                } else {
                    "$base (English)"
                }
            }
        }
    }
}
