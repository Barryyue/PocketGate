package com.networkcontrol.android.automation.parser

/**
 * 輕量 JSON5 預處理器：將 GKD 社群訂閱常見的 JSON5 語法正規化為標準 JSON，
 * 讓 Android 內建的 `org.json.JSONObject` 即可直接解析，避免引入重型三方解析庫。
 *
 * 支援特性：
 * - `//` 單行註釋與 `/* ... */` 多行註釋
 * - 單引號字串（轉換為雙引號並正確轉義內部雙引號）
 * - 多行字串續行（`\` + 換行）
 * - 物件無引號鍵名（`{ id: 1, name: 'foo' }`）
 * - 陣列與物件尾隨逗號（`[1, 2,]`、`{a: 1,}`）
 * - 數字前導加號（`+1`）與前導/尾隨小數點（`.5` / `5.`）
 */
object Json5Preprocessor {

    fun toStandardJson(input: String): String {
        val noBom = if (input.startsWith("\uFEFF")) input.substring(1) else input
        val stripped = stripCommentsAndNormalizeStrings(noBom)
        val quotedKeys = quoteUnquotedKeys(stripped)
        return removeTrailingCommas(quotedKeys)
    }

    private fun stripCommentsAndNormalizeStrings(src: String): String {
        val out = StringBuilder(src.length)
        val n = src.length
        var i = 0
        while (i < n) {
            val c = src[i]
            when {
                // 雙引號字串：原樣保留，處理續行符
                c == '"' -> {
                    out.append('"')
                    i++
                    while (i < n) {
                        val ch = src[i]
                        if (ch == '\\' && i + 1 < n) {
                            val next = src[i + 1]
                            if (next == '\n') {
                                i += 2
                                continue
                            } else if (next == '\r' && i + 2 < n && src[i + 2] == '\n') {
                                i += 3
                                continue
                            } else if (next == '\r') {
                                i += 2
                                continue
                            }
                            out.append(ch).append(next)
                            i += 2
                        } else if (ch == '"') {
                            out.append('"')
                            i++
                            break
                        } else {
                            out.append(ch)
                            i++
                        }
                    }
                }
                // 單引號字串：轉為標準雙引號字串
                c == '\'' -> {
                    out.append('"')
                    i++
                    while (i < n) {
                        val ch = src[i]
                        if (ch == '\\' && i + 1 < n) {
                            val next = src[i + 1]
                            when (next) {
                                '\'' -> out.append('\'')
                                '\n' -> {
                                    i += 2
                                    continue
                                }
                                '\r' -> {
                                    i += if (i + 2 < n && src[i + 2] == '\n') 3 else 2
                                    continue
                                }
                                else -> out.append('\\').append(next)
                            }
                            i += 2
                        } else if (ch == '\'') {
                            out.append('"')
                            i++
                            break
                        } else if (ch == '"') {
                            out.append("\\\"")
                            i++
                        } else {
                            out.append(ch)
                            i++
                        }
                    }
                }
                // 單行註釋 //
                c == '/' && i + 1 < n && src[i + 1] == '/' -> {
                    i += 2
                    while (i < n && src[i] != '\n' && src[i] != '\r') i++
                }
                // 區塊註釋 /* ... */
                c == '/' && i + 1 < n && src[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < n && !(src[i] == '*' && src[i + 1] == '/')) i++
                    i = (i + 2).coerceAtMost(n)
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /** 為物件中未加引號的鍵名補上雙引號，並清理數字前導 `+`。 */
    private fun quoteUnquotedKeys(src: String): String {
        val out = StringBuilder(src.length + 64)
        val n = src.length
        var i = 0
        // 用棧記錄目前處於物件還是陣列上下文，只在物件鍵位置補引號
        val stack = ArrayDeque<Char>()
        var expectKey = false

        while (i < n) {
            val c = src[i]
            if (c == '"') {
                // 完整跳過已加引號的字串
                out.append('"')
                i++
                while (i < n) {
                    val ch = src[i]
                    out.append(ch)
                    if (ch == '\\' && i + 1 < n) {
                        i++
                        out.append(src[i])
                    } else if (ch == '"') {
                        i++
                        break
                    }
                    i++
                }
                if (expectKey) expectKey = false
                continue
            }

            if (c.isWhitespace()) {
                out.append(c)
                i++
                continue
            }

            if (c == '{') {
                stack.addLast('{')
                expectKey = true
                out.append(c)
                i++
                continue
            }
            if (c == '[') {
                stack.addLast('[')
                expectKey = false
                out.append(c)
                i++
                continue
            }
            if (c == '}' || c == ']') {
                if (stack.isNotEmpty()) stack.removeLast()
                expectKey = false
                out.append(c)
                i++
                continue
            }
            if (c == ',') {
                expectKey = stack.lastOrNull() == '{'
                out.append(c)
                i++
                continue
            }
            if (c == ':') {
                expectKey = false
                out.append(c)
                i++
                continue
            }

            if (expectKey && isIdentifierStart(c)) {
                val start = i
                i++
                while (i < n && isIdentifierPart(src[i])) i++
                out.append('"').append(src, start, i).append('"')
                expectKey = false
                continue
            }

            // 處理 JSON5 數字前導 '+'（例如 +123）
            if (c == '+' && i + 1 < n && (src[i + 1].isDigit() || src[i + 1] == '.')) {
                i++
                continue
            }

            out.append(c)
            i++
        }
        return out.toString()
    }

    /** 移除 `]` 或 `}` 前的尾隨逗號。 */
    private fun removeTrailingCommas(src: String): String {
        val out = StringBuilder(src.length)
        val n = src.length
        var i = 0
        while (i < n) {
            val c = src[i]
            if (c == '"') {
                out.append('"')
                i++
                while (i < n) {
                    val ch = src[i]
                    out.append(ch)
                    if (ch == '\\' && i + 1 < n) {
                        i++
                        out.append(src[i])
                    } else if (ch == '"') {
                        i++
                        break
                    }
                    i++
                }
                continue
            }
            if (c == ',') {
                var j = i + 1
                while (j < n && src[j].isWhitespace()) j++
                if (j < n && (src[j] == ']' || src[j] == '}')) {
                    i++
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private fun isIdentifierStart(c: Char): Boolean =
        c == '_' || c == '$' || c.isLetter()

    private fun isIdentifierPart(c: Char): Boolean =
        c == '_' || c == '$' || c.isLetterOrDigit()
}
