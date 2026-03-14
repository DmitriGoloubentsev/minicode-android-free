package com.minicode.service.speech

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Post-processes speech recognition results for terminal command context.
 * Replaces spoken punctuation/symbols with their character equivalents.
 */
@Singleton
class VoicePostProcessor @Inject constructor() {

    private val replacements = listOf(
        // Punctuation
        "\\bperiod\\b" to ".",
        "\\bdot\\b" to ".",
        "\\bcomma\\b" to ",",
        "\\bcolon\\b" to ":",
        "\\bsemicolon\\b" to ";",
        "\\bexclamation mark\\b" to "!",
        "\\bquestion mark\\b" to "?",

        // Symbols
        "\\bslash\\b" to "/",
        "\\bforward slash\\b" to "/",
        "\\bbackslash\\b" to "\\",
        "\\bdash\\b" to "-",
        "\\bhyphen\\b" to "-",
        "\\bunderscore\\b" to "_",
        "\\bpipe\\b" to "|",
        "\\btilde\\b" to "~",
        "\\bat sign\\b" to "@",
        "\\bhashtag\\b" to "#",
        "\\bhash\\b" to "#",
        "\\bdollar sign\\b" to "$",
        "\\bampersand\\b" to "&",
        "\\basterisk\\b" to "*",
        "\\bstar\\b" to "*",
        "\\bequals\\b" to "=",
        "\\bplus\\b" to "+",
        "\\bpercent\\b" to "%",
        "\\bcaret\\b" to "^",
        "\\bgreater than\\b" to ">",
        "\\bless than\\b" to "<",
        "\\bopen paren\\b" to "(",
        "\\bclose paren\\b" to ")",
        "\\bopen bracket\\b" to "[",
        "\\bclose bracket\\b" to "]",
        "\\bopen brace\\b" to "{",
        "\\bclose brace\\b" to "}",
        "\\bsingle quote\\b" to "'",
        "\\bdouble quote\\b" to "\"",
        "\\bbacktick\\b" to "`",

        // Common terminal commands (spoken oddly by ASR)
        "\\bpseudo\\b" to "sudo",
        "\\bget hub\\b" to "github",
        "\\bG I T\\b" to "git",
    )

    private val compiledReplacements = replacements.map { (pattern, replacement) ->
        Regex(pattern, RegexOption.IGNORE_CASE) to replacement
    }

    fun postProcess(text: String): String {
        var result = text
        // Remove <unk> tokens (model vocabulary gaps, e.g. ё in Cyrillic)
        result = result.replace("<unk>", "")
        // Collapse multiple spaces left by <unk> removal
        result = result.replace(Regex("  +"), " ")
        for ((regex, replacement) in compiledReplacements) {
            result = regex.replace(result, replacement)
        }
        return result.trim()
    }
}
