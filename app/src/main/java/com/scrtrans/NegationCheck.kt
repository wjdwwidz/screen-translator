package com.scrtrans

/**
 * Catches the engine dropping a negation.
 *
 * The ja->en->ko route loses 不可 often enough to be worth watching for, and when it
 * does the result does not merely read oddly — it reads as the opposite. On a coupon
 * screen `スタッフ指名不可` came back as "직원이 지명되었습니다": a prohibition turned
 * into a statement that the thing had been done.
 *
 * There is no way to repair such a result — the whole clause is rebuilt, so there is no
 * wrong word to swap. Only a glossary entry fixes one, and a glossary entry only covers
 * the strings someone has already seen. Coupon conditions combine freely
 * (`~/ スタッフ指名不可`, `~/ 他券併用不可`), so new combinations keep appearing.
 *
 * Hence this: flag the ones worth adding instead of hunting for them by eye. A source
 * that negates and a result that does not is the signal.
 */
object NegationCheck {

    private val SOURCE_NEGATIONS = listOf(
        "不可", "禁止", "できません", "いただけません", "ご遠慮", "不要", "なし",
    )

    /** Korean carries negation in several shapes; any one of them clears the check. */
    private val RESULT_NEGATIONS = listOf(
        "불가", "없", "안 ", "안됩", "안 됩", "못", "아닙", "제외", "금지", "불허",
    )

    /** True when the source negates something and the translation no longer does. */
    fun lostNegation(source: String, result: String): Boolean {
        if (SOURCE_NEGATIONS.none { source.contains(it) }) return false
        return RESULT_NEGATIONS.none { result.contains(it) }
    }
}
