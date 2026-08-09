package com.scrtrans

/**
 * True if the string contains at least one hiragana, katakana or kanji character.
 *
 * Strings without any of these — "10", "¥5,500", "OPEN", "PICK UP" — are left alone:
 * covering them would only clutter the screen and waste engine calls.
 */
fun containsJapanese(s: String): Boolean {
    for (ch in s) {
        val c = ch.code
        val japanese = when {
            c in 0x3040..0x309F -> true // hiragana
            c in 0x30A0..0x30FF -> true // katakana
            c in 0x31F0..0x31FF -> true // katakana phonetic extensions
            c in 0x3400..0x4DBF -> true // CJK unified ideographs extension A
            c in 0x4E00..0x9FFF -> true // CJK unified ideographs
            c in 0xF900..0xFAFF -> true // CJK compatibility ideographs
            c in 0xFF66..0xFF9D -> true // half-width katakana
            else -> false
        }
        if (japanese) return true
    }
    return false
}

/**
 * True if the string contains hiragana or katakana.
 *
 * Narrower than [containsJapanese] on purpose. Kanji are shared with Chinese, so a
 * screen of nothing but kanji says "CJK", not "Japanese" — kana is what settles it.
 * Used when deciding whether a whole app is worth offering to translate, never when
 * deciding whether to draw over a string we were already asked to handle.
 */
fun containsKana(s: String): Boolean {
    for (ch in s) {
        val c = ch.code
        val kana = when {
            c in 0x3040..0x309F -> true // hiragana
            c in 0x30A0..0x30FF -> true // katakana
            c in 0x31F0..0x31FF -> true // katakana phonetic extensions
            c in 0xFF66..0xFF9D -> true // half-width katakana
            else -> false
        }
        if (kana) return true
    }
    return false
}
