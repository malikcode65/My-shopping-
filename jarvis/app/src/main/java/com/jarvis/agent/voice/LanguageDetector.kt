package com.jarvis.agent.voice

enum class SpokenLanguage { URDU, ROMAN_URDU, ENGLISH }

/**
 * Cheap heuristic language detection, good enough to choose a TTS voice and to tell the model which
 * language/script to answer in. Urdu script is detected by Unicode block, Roman Urdu by common words.
 */
object LanguageDetector {

    private val romanUrduWords = setOf(
        "hai", "hain", "ho", "hoon", "kya", "kyun", "kar", "karo", "kardo", "karein", "karna", "mujhe", "mera",
        "meri", "mere", "ko", "ka", "ki", "ke", "se", "aur", "nahi", "nahin", "haan", "han", "bhai", "bhej",
        "bhejo", "bhejdo", "dein", "dena", "kal", "aaj", "abhi", "yeh", "ye", "woh", "wo", "kahan", "kaise",
        "kitna", "kitne", "batao", "bata", "chalao", "chala", "band", "kholo", "khol", "lagao", "laga", "wala",
        "wali", "acha", "accha", "theek", "thik", "jee", "ji", "hum", "tum", "aap", "apna", "apni", "sab",
        "kuch", "phir", "jab", "tak", "liye", "waqt", "baje", "subah", "shaam", "raat", "zara", "please",
        "ammi", "abbu", "beta", "yaar", "sun", "suno", "dekho", "dikhao", "parho", "likho", "msg", "paigham",
    )

    private val englishOnlyHints = setOf("the", "is", "are", "what", "please", "my", "to", "and", "send", "open")

    fun detect(text: String): SpokenLanguage {
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return SpokenLanguage.ENGLISH
        val arabicScript = letters.count { it in '؀'..'ۿ' || it in 'ݐ'..'ݿ' || it in 'ﭐ'..'﷿' }
        if (arabicScript * 10 >= letters.length * 3) return SpokenLanguage.URDU

        val words = text.lowercase().split(Regex("[^a-z']+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return SpokenLanguage.ENGLISH
        val urduHits = words.count { it in romanUrduWords && it !in englishOnlyHints }
        return if (urduHits >= 2 || (urduHits >= 1 && words.size <= 3) || urduHits * 4 >= words.size && urduHits > 0) {
            SpokenLanguage.ROMAN_URDU
        } else {
            SpokenLanguage.ENGLISH
        }
    }
}
