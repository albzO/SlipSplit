package local.ocrbridge

/** Which characters the recognizer may output. Chinese keeps Latin, so mixed bills still read. */
enum class ReceiptLanguage { AUTO, ENGLISH, CHINESE }

// On the samples English receipts stay under 6% and Chinese or Japanese ones above 80%.
private const val CJK_RATIO_FOR_CHINESE = 0.15

private const val LATIN_END = 0x024F
private const val PUNCTUATION_START = 0x2000
private const val PUNCTUATION_END = 0x206F
private const val CURRENCY_START = 0x20A0
private const val CURRENCY_END = 0x20CF
private const val MULTIPLY_SIGN = '×'

private fun isCjk(char: Char) =
    char in '぀'..'ヿ' || char in '㐀'..'鿿' || char in '가'..'힯'

/** True for the characters an English receipt can contain, once Chinese is ruled out. */
fun isEnglishCharacter(text: String): Boolean = text.all {
    val code = it.code
    code <= LATIN_END || code in PUNCTUATION_START..PUNCTUATION_END ||
        code in CURRENCY_START..CURRENCY_END || it == MULTIPLY_SIGN
}

/** Decides from a free reading whether a receipt is English, so stray CJK can be read again. */
fun looksEnglish(texts: List<String>): Boolean {
    val text = texts.joinToString("")
    val letters = text.count { it.isLetter() }
    if (letters == 0) return true
    return text.count(::isCjk).toDouble() / letters < CJK_RATIO_FOR_CHINESE
}
