package com.moonkey.androidagent.tool.action

import com.moonkey.androidagent.model.ScreenSnapshot

/**
 * Detects UI changes by comparing snapshot fingerprints.
 *
 * Key design decision: Unverifiable is a DISTINCT outcome — not silently
 * treated as Changed. Callers decide how to handle it.
 */
object UiChangeDetector {

    enum class ChangeResult { Changed, Unchanged, Unverifiable }

    fun compare(pre: ScreenSnapshot?, post: ScreenSnapshot?): ChangeResult {
        if (pre == null || post == null) return ChangeResult.Unverifiable
        val preHash = fingerprint(pre)
        val postHash = fingerprint(post)
        return if (preHash != postHash) ChangeResult.Changed else ChangeResult.Unchanged
    }

    /** Detects scroll boundary: pre/post content identical after swipe. */
    fun detectScrollBoundary(pre: ScreenSnapshot?, post: ScreenSnapshot?): String? {
        if (pre == null || post == null) return null

        val preTexts = pre.elements
            .filter { it.text.isNotBlank() || it.description.isNotBlank() }
            .map { "${it.text}|${it.description}|${it.bounds}" }
            .sorted()

        val postTexts = post.elements
            .filter { it.text.isNotBlank() || it.description.isNotBlank() }
            .map { "${it.text}|${it.description}|${it.bounds}" }
            .sorted()

        return if (preTexts == postTexts && preTexts.isNotEmpty()) {
            "Screen content unchanged after swipe - may have reached scroll boundary"
        } else {
            null
        }
    }

    /**
     * FNV-1a hash over sorted elements' stable fields.
     * Includes: resourceId, className, text, description, bounds, isFocused, isEnabled.
     */
    private fun fingerprint(snapshot: ScreenSnapshot): Long {
        var hash = 1469598103934665603L // FNV offset basis
        for (element in snapshot.elements.sortedBy { it.index }) {
            hash = mix(hash, element.index.toLong())
            hash = mix(hash, element.resourceId.hashCode().toLong())
            hash = mix(hash, element.className.hashCode().toLong())
            hash = mix(hash, element.text.hashCode().toLong())
            hash = mix(hash, element.description.hashCode().toLong())
            hash = mix(hash, element.bounds.left.toLong())
            hash = mix(hash, element.bounds.top.toLong())
            hash = mix(hash, element.bounds.right.toLong())
            hash = mix(hash, element.bounds.bottom.toLong())
            hash = mix(hash, element.isFocused.hashCode().toLong())
            hash = mix(hash, element.isEnabled.hashCode().toLong())
        }
        return hash
    }

    private fun mix(current: Long, value: Long): Long {
        return (current xor value) * 1099511628211L // FNV prime
    }
}
