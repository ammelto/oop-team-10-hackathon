package com.example.oop.ontology

enum class AisSeverity(val digit: Char, val label: String) {
    MINOR('1', "Minor"),
    MODERATE('2', "Moderate"),
    SERIOUS('3', "Serious"),
    SEVERE('4', "Severe"),
    CRITICAL('5', "Critical"),
    MAXIMUM('6', "Maximum"),
    UNKNOWN('9', "Unknown"),
    ;

    companion object {
        fun fromDigit(digit: Char): AisSeverity? = entries.firstOrNull { it.digit == digit }
    }
}

object AisBodyRegion {
    private val table = mapOf(
        '1' to "External",
        '2' to "Head",
        '3' to "Face",
        '4' to "Neck",
        '5' to "Thorax",
        '6' to "Abdomen & Pelvic Contents",
        '7' to "Spine",
        '8' to "Upper Extremity",
        '9' to "Lower Extremity",
    )

    fun labelFor(digit: Char): String? = table[digit]
}
