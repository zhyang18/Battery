package com.battery.analysis.model

/**
 * 耗电量显示单位枚举类。
 * 支持在瓦时（Wh，物理真实能量）与毫安时（mAh，物理等效电量）之间互相切换，
 * 满足普通用户直观对照电池标称容量与极客用户严谨度量物理做功的双重需求。
 *
 * @property id 存储与持久化整型标识（0 为 Wh，1 为 mAh）
 * @property symbol 单位简写符号（如 "Wh"、"mAh"）
 * @property label 人类可读完整名称（如 "瓦时 (能量)"、"毫安时 (电量)"）
 */
enum class PowerDisplayUnit(val id: Int, val symbol: String, val label: String) {
    /**
     * 瓦时（Wh）：物理真实能量单位（功率对时间的积分）。
     * 消除工作电压变动和双电芯串联结构干扰，真实反映化学能做功与发热。
     */
    WH(0, "Wh", "瓦时 (能量)"),

    /**
     * 毫安时（mAh）：等效物理电荷量单位（基于标准标称电压折算）。
     * 贴近绝大多数手机电池出厂标称容量（如 5000mAh）的日常直觉感知。
     */
    MAH(1, "mAh", "毫安时 (电量)");

    /**
     * 获取相反的显示单位，用于实现单按钮往复切换。
     *
     * @return 切换后的目标 [PowerDisplayUnit] 实例
     */
    fun toggle(): PowerDisplayUnit {
        return if (this == WH) MAH else WH
    }

    /**
     * 获取多语言本地化的完整名称。
     *
     * @param context 应用程序上下文
     * @return 本地化的单位名称（如 "瓦时 (能量)" / "Wh (Energy)"）
     */
    fun getLabel(context: android.content.Context): String {
        return when (this) {
            WH -> context.getString(com.battery.analysis.R.string.power_unit_label_wh)
            MAH -> context.getString(com.battery.analysis.R.string.power_unit_label_mah)
        }
    }

    companion object {
        /** 耗电统计全局 SharedPreferences 配置文件名称 */
        const val PREFS_NAME = "power_stats_prefs"

        /** SharedPreferences 中存储电量显示单位的持久化键名 */
        const val PREF_KEY_POWER_DISPLAY_UNIT = "pref_power_display_unit"

        /** SharedPreferences 中存储切换电量单位时是否不再弹出对话框的键名 */
        const val PREF_KEY_POWER_UNIT_DIALOG_DONOT_SHOW = "pref_power_unit_dialog_donot_show"

        /**
         * 根据整型持久化标识解析对应的显示单位，无效或未知数值安全回退默认 [WH]。
         *
         * @param id 持久化数值标识
         * @return 匹配的 [PowerDisplayUnit] 枚举实例
         */
        fun fromId(id: Int): PowerDisplayUnit {
            return entries.firstOrNull { it.id == id } ?: WH
        }
    }
}
