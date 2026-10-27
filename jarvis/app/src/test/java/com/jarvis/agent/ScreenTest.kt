package com.jarvis.agent

import com.jarvis.agent.accessibility.ScreenSnapshot
import com.jarvis.agent.accessibility.UiNode
import com.jarvis.agent.safety.UiRiskClassifier
import com.jarvis.agent.tools.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTest {
    private fun node(id: Int, text: String? = null, desc: String? = null, click: Boolean = false, edit: Boolean = false, viewId: String? = null) =
        UiNode(id, if (edit) "EditText" else "Button", text, desc, viewId, null, 0, id * 100, 200, id * 100 + 80, clickable = click, editable = edit)

    private val chat = ScreenSnapshot(
        "com.whatsapp", "WhatsApp", 1080, 2400,
        listOf(
            node(1, text = "Ammi"),
            node(2, text = "Main 10 minute mein aa raha hoon", edit = true, viewId = "com.whatsapp:id/entry"),
            node(3, desc = "Send", click = true, viewId = "com.whatsapp:id/send"),
        ),
    )

    @Test
    fun rendersCompactTree() {
        val r = chat.render()
        assertTrue(r.startsWith("App: WhatsApp (com.whatsapp)"))
        assertTrue(r.contains("[3] Button desc=\"Send\" id=send (click) @100,340"))
        assertTrue(r.contains("(edit)"))
    }

    @Test
    fun findsByTextPreferringClickable() {
        assertEquals(3, chat.findByText("send")?.id)
        assertEquals(1, chat.findByText("ammi")?.id)
        assertEquals(listOf("Main 10 minute mein aa raha hoon"), chat.editableTexts())
    }

    @Test
    fun classifiesTaps() {
        assertEquals(RiskLevel.MEDIUM, UiRiskClassifier.classifyTap("Send", "com.whatsapp", "").level)
        assertEquals(RiskLevel.LOW, UiRiskClassifier.classifyTap("Chats", "com.whatsapp", "").level)
        assertEquals(RiskLevel.LOW, UiRiskClassifier.classifyTap("Sender info", "com.whatsapp", "").level)
        assertEquals(RiskLevel.HIGH, UiRiskClassifier.classifyTap("Pay Now", "com.daraz.android", "").level)
        assertEquals(RiskLevel.HIGH, UiRiskClassifier.classifyTap("Delete for everyone", "com.whatsapp", "").level)
        assertEquals(RiskLevel.HIGH, UiRiskClassifier.classifyTap("Place order", "com.android.chrome", "").level)
        assertEquals(RiskLevel.HIGH, UiRiskClassifier.classifyTap("ادائیگی کریں", "com.x", "").level)
        assertEquals(RiskLevel.MEDIUM, UiRiskClassifier.classifyTap("Home", "pk.com.telenor.phoenix", "").level)
        assertEquals(RiskLevel.MEDIUM, UiRiskClassifier.classifyTap("Install", "com.android.vending", "").level)
    }

    @Test
    fun securitySettingsAreHighButWifiIsNot() {
        assertEquals(RiskLevel.LOW, UiRiskClassifier.classifyTap("Wi-Fi", "com.android.settings", "Network Wi-Fi Bluetooth Security & privacy Display").level)
        assertEquals(RiskLevel.HIGH, UiRiskClassifier.classifyTap("Security & privacy", "com.android.settings", "").level)
        assertEquals(RiskLevel.HIGH, UiRiskClassifier.classifyTap("None", "com.android.settings", "Choose screen lock None Swipe Pattern PIN").level)
        assertEquals(RiskLevel.HIGH, UiRiskClassifier.classifyTyping("com.android.settings", "Install unknown apps Chrome").level)
    }
}

class ImageCodecTest {
    @Test
    fun fitsLongSideWithoutUpscaling() {
        assertEquals(1280 to 576, com.jarvis.agent.tools.impl.ImageCodec.fit(2400, 1080))
        assertEquals(576 to 1280, com.jarvis.agent.tools.impl.ImageCodec.fit(1080, 2400))
        assertEquals(800 to 600, com.jarvis.agent.tools.impl.ImageCodec.fit(800, 600))
    }
}
