package com.pluginslab.agentbridge.agent

/**
 * Stable, privacy-safe reasons for launch_app failures. No exception messages
 * or intent/user data in error codes. A failed launch never proves why Android
 * did not bring the target to the foreground.
 */
object LaunchFailurePolicy {
    private val packageRegex = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*){1,15}")

    fun validPackage(value: String): Boolean =
        value.length in 3..180 && packageRegex.matches(value)

    fun foregroundCode(observed: String?, accessibilityConnected: Boolean): String = when {
        !accessibilityConnected -> "ACCESSIBILITY_LOST"
        observed.isNullOrBlank() -> "FOREGROUND_UNKNOWN"
        else -> "FOREGROUND_MISMATCH"
    }

    fun exceptionCode(e: Exception): String = when (e) {
        is SecurityException -> "DISPATCH_DENIED"
        else -> "DISPATCH_FAILED"
    }

    fun guidance(code: String): String = when(code) {
        "INVALID_PACKAGE" -> "Получите правильное имя пакета через list_apps; не угадывайте его."
        "ACCESSIBILITY_UNAVAILABLE", "ACCESSIBILITY_LOST" ->
            "Проверьте подключение Accessibility. Не нажимайте элементы без актуального экрана."
        "NO_LAUNCHER" ->
            "У пакета нет доступной launcher-активности. Проверьте приложение через list_apps."
        "DISPATCH_DENIED", "DISPATCH_FAILED" ->
            "Android не подтвердил запуск. Проверьте актуальное окно и сообщите о невозможности запуска."
        else ->
            "Запуск не подтверждён. Проверьте текущее окно через get_active_window_info. " +
                "Не повторяйте запуск вслепую и не выполняйте действия в предполагаемом приложении."
    }
}
