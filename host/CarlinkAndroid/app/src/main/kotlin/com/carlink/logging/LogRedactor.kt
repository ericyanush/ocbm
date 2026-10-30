package com.carlink.logging

/** Redacts route and place text from diagnostics while preserving whether the field was present. */
object LogRedactor {
    fun place(value: String?): String = if (value.isNullOrBlank()) "<empty>" else "<place>"
}
