package com.example.localfly.utils

import java.util.Comparator

object NaturalOrderComparator : Comparator<String> {
    override fun compare(s1: String, s2: String): Int {
        var i = 0
        var j = 0
        while (i < s1.length && j < s2.length) {
            val c1 = s1[i]
            val c2 = s2[j]

            if (c1.isDigit() && c2.isDigit()) {
                val num1 = extractNumber(s1, i)
                val num2 = extractNumber(s2, j)
                if (num1 != num2) return num1.compareTo(num2)
                i += num1.toString().length
                j += num2.toString().length
            } else {
                if (c1 != c2) return c1.compareTo(c2)
                i++
                j++
            }
        }
        return s1.length - s2.length
    }

    private fun extractNumber(s: String, start: Int): Int {
        var end = start
        while (end < s.length && s[end].isDigit()) end++
        return s.substring(start, end).toIntOrNull() ?: 0
    }
}
