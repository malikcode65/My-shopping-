package com.jarvis.agent.safety

import com.jarvis.agent.tools.RiskLevel

/**
 * Decides how risky a UI action is from what is being tapped/typed and where. This is what makes "operate
 * any app" safe: the model cannot downgrade it, and unknown buttons default to LOW only when harmless.
 */
object UiRiskClassifier {

    data class Verdict(val level: RiskLevel, val reason: String)

    private fun words(vararg w: String) = Regex(
        w.joinToString("|") { "(?<![\\p{L}])${Regex.escape(it)}(?![\\p{L}])" },
        RegexOption.IGNORE_CASE,
    )

    private val highWords = words(
        "pay", "pay now", "payment", "confirm payment", "send money", "transfer", "transfer now", "withdraw", "top up",
        "buy", "buy now", "purchase", "place order", "checkout", "check out", "subscribe", "donate", "book now",
        "delete", "delete for everyone", "delete account", "remove account", "erase", "factory reset", "reset phone",
        "uninstall", "format", "clear data", "clear storage", "deactivate", "close account", "confirm transfer",
        "adaigi", "adaegi", "paise bhejo", "paise bhejein", "raqam bhejo", "transfer karein", "khareedein", "delete karo",
        "ادائیگی", "رقم", "منتقل", "خریدیں", "حذف", "ڈیلیٹ",
    )

    private val mediumWords = words(
        "send", "send message", "post", "share", "publish", "tweet", "reply", "comment", "call", "video call",
        "voice call", "install", "update", "accept", "allow", "grant", "upload", "submit", "book", "reserve", "follow",
        "sign out", "log out", "logout", "forward", "invite", "join", "save changes", "confirm", "agree",
        "bhejo", "bhejein", "bhej do", "call karo", "ارسال", "بھیجیں", "بھیجو", "کال",
    )

    /** Settings items that control the phone's security. */
    private val securityLabel = words(
        "screen lock", "lock screen", "password", "pin", "pattern", "fingerprint", "face unlock", "biometric",
        "device admin", "device administrator", "install unknown apps", "unknown sources", "developer options",
        "usb debugging", "accessibility", "app permissions", "encryption", "find my device", "google account",
        "security", "privacy", "credential", "trusted agents", "smart lock",
    )

    /** Text that only appears on security settings pages (not on the main Settings list). */
    private val securityPage = words(
        "screen lock type", "device admin apps", "device administrators", "install unknown apps", "usb debugging",
        "smart lock", "trust agents", "encryption & credentials", "encrypt phone", "add fingerprint", "face unlock",
        "allow from this source", "unlock pattern", "choose screen lock",
    )

    private val financePackages = listOf(
        "com.techlogix.mobilinkcustomer", // JazzCash
        "pk.com.telenor.phoenix", // Easypaisa
        "com.google.android.apps.nbu.paisa.user", "com.google.android.apps.walletnfcrel", "com.paypal",
        "com.sadapay", "com.nayapay", "com.ubank", "com.hbl", "com.meezanbank", "com.mcb", "com.ubl", "com.alfalah",
        "com.bankalfalah", "com.binance", "com.coinbase", "com.daraz", "com.amazon", "com.foodpanda",
    )

    private val settingsPackages = setOf("com.android.settings", "com.samsung.android.settings", "com.miui.securitycenter")

    fun isFinanceApp(pkg: String) = financePackages.any { pkg.startsWith(it) } ||
        Regex("bank|wallet|pay(?!load)|finance|crypto|trading", RegexOption.IGNORE_CASE).containsMatchIn(pkg)

    /**
     * @param label text/description of the element being tapped (short)
     * @param packageName foreground app
     * @param screenText all visible text, used to detect security settings pages and checkout screens
     */
    fun classifyTap(label: String, packageName: String, screenText: String): Verdict {
        val l = label.take(60)
        val isSettings = packageName in settingsPackages || packageName.endsWith(".settings")
        if (isSettings && (securityLabel.containsMatchIn(l) || securityPage.containsMatchIn(screenText.take(6000)))) {
            return Verdict(RiskLevel.HIGH, "Security settings")
        }
        if (highWords.containsMatchIn(l)) return Verdict(RiskLevel.HIGH, "Money / purchase / delete action")
        if (isFinanceApp(packageName)) {
            return Verdict(RiskLevel.MEDIUM, "Action inside a payment or banking app")
        }
        if (mediumWords.containsMatchIn(l)) return Verdict(RiskLevel.MEDIUM, "Sends, posts, calls or installs")
        return Verdict(RiskLevel.LOW, "")
    }

    fun classifyTyping(packageName: String, screenText: String): Verdict {
        val isSettings = packageName in settingsPackages || packageName.endsWith(".settings")
        if (isSettings && securityPage.containsMatchIn(screenText.take(6000))) return Verdict(RiskLevel.HIGH, "Security settings")
        if (isFinanceApp(packageName)) return Verdict(RiskLevel.MEDIUM, "Typing inside a payment or banking app")
        return Verdict(RiskLevel.LOW, "")
    }
}
