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
 * True if [ch] is drawn on a full em square — one whose advance width equals the font
 * size exactly.
 *
 * This is what lets the source's font size be read off a character's measured width.
 * Deliberately narrower than [containsJapanese]: half-width katakana (0xFF66..0xFF9D)
 * is Japanese but advances half an em, and sampling it would halve the size we derive.
 * Kana and ideographs are the reliable core, and a Japanese string without either is
 * rare enough to leave to the caller's fallback.
 */
fun isFullWidth(ch: Char): Boolean {
    val c = ch.code
    return when {
        c in 0x3040..0x309F -> true // hiragana
        c in 0x30A0..0x30FF -> true // katakana
        c in 0x31F0..0x31FF -> true // katakana phonetic extensions
        c in 0x3400..0x4DBF -> true // CJK unified ideographs extension A
        c in 0x4E00..0x9FFF -> true // CJK unified ideographs
        c in 0xF900..0xFAFF -> true // CJK compatibility ideographs
        else -> false
    }
}

/**
 * True if the string is katakana and nothing else — no hiragana, no kanji.
 *
 * Katakana on its own is nearly always a loanword, and loanwords are where the
 * ja->en->ko route fails hardest. It recovers the English, and the Korean side then
 * translates that by meaning instead of carrying it across as the loanword Korean
 * already borrowed from the same English: ツインテール came back as "쌍둥이 꼬리"
 * rather than 트윈테일, サイドポニー as "측면 조랑말", フレンチバング as "프랑스 쾅".
 * Of the katakana-only strings the engine handled on real screens, about half were
 * wrong this way.
 *
 * Transliterating the kana instead does not help. Katakana is itself a Japanese
 * rendering of the English, so sounding it out in hangul gives 츠인테루, not 트윈테일.
 * Only a word list gets there, which is what [Glossary] is.
 *
 * Marks like ー and ・ live in the katakana block, so they need no special case.
 */
fun isKatakanaOnly(s: String): Boolean {
    var katakana = false
    for (ch in s) {
        val c = ch.code
        when {
            c in 0x30A0..0x30FF -> katakana = true
            c in 0x31F0..0x31FF -> katakana = true
            c in 0xFF66..0xFF9D -> katakana = true
            // Anything that makes it Japanese rather than a loanword settles it.
            c in 0x3040..0x309F -> return false // hiragana
            c in 0x3400..0x4DBF -> return false // kanji
            c in 0x4E00..0x9FFF -> return false
            c in 0xF900..0xFAFF -> return false
            // Digits, latin, spaces and punctuation say nothing either way.
        }
    }
    return katakana
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
