package com.local.ocrclip

import org.json.JSONObject

/**
 * Undoes Japanese inflections (食べなかった → 食べる) with Yomitan's rules, applied the way Yomitan's
 * LanguageTransformer applies them. The rules in assets/japanese-transforms.json are Yomitan's
 * (Copyright (C) 2024-2026 Yomitan Authors, GPL-3.0-or-later), exported by tools/export_yomitan_transforms.py.
 */
class Deinflector(json: String) {
    /**
     * A possible dictionary form of the scanned text. [conditions] is what the dictionary entry's part of speech must
     * match (0 when nothing was undone, so anything matches); [reasons] names the inflections undone, outermost first.
     */
    class Candidate(val text: String, val conditions: Int, val reasons: List<String>)

    private class Rule(
        val wholeWord: Boolean, val inflected: String, val deinflected: String,
        val conditionsIn: Int, val conditionsOut: Int,
    )

    private class Transform(val name: String, val rules: List<Rule>)

    private class Frame(val transform: Int, val rule: Int, val text: String)

    private class Work(val text: String, val conditions: Int, val trace: List<Frame>)

    private val transforms: List<Transform>
    private val partsOfSpeech: Map<String, Int>

    init {
        val root = JSONObject(json)
        val pos = root.getJSONObject("partsOfSpeech")
        partsOfSpeech = pos.keys().asSequence().associateWith { pos.getInt(it) }
        val list = root.getJSONArray("transforms")
        transforms = (0 until list.length()).map { i ->
            val t = list.getJSONObject(i)
            val rules = t.getJSONArray("rules")
            Transform(t.getString("name"), (0 until rules.length()).map { j ->
                val r = rules.getJSONArray(j)
                Rule(r.getString(0) == "w", r.getString(1), r.getString(2), r.getInt(3), r.getInt(4))
            })
        }
    }

    fun deinflect(source: String): List<Candidate> {
        val work = mutableListOf(Work(source, 0, emptyList()))
        var i = 0
        while (i < work.size) {
            val current = work[i++]
            val text = current.text
            transforms.forEachIndexed { t, transform ->
                transform.rules.forEachIndexed rule@{ r, rule ->
                    if (!matches(current.conditions, rule.conditionsIn)) return@rule
                    if (if (rule.wholeWord) text != rule.inflected else !text.endsWith(rule.inflected)) return@rule
                    // Yomitan's cycle guard: a rule never applies twice to the same text within one chain.
                    if (current.trace.any { it.transform == t && it.rule == r && it.text == text }) return@rule
                    val next = if (rule.wholeWord) rule.deinflected
                    else text.substring(0, text.length - rule.inflected.length) + rule.deinflected
                    work += Work(next, rule.conditionsOut, listOf(Frame(t, r, text)) + current.trace)
                }
            }
        }
        return work.map { w -> Candidate(w.text, w.conditions, w.trace.map { transforms[it.transform].name }) }
    }

    /** Condition flags for a dictionary entry's space-separated `rules` (v1, v5, vk, vs, vz, adj-i). */
    fun conditionsOf(rules: String): Int =
        rules.split(' ').fold(0) { flags, rule -> flags or (partsOfSpeech[rule] ?: 0) }

    companion object {
        /** Yomitan's LanguageTransformer.conditionsMatch. */
        fun matches(current: Int, next: Int) = current == 0 || current and next != 0
    }
}
