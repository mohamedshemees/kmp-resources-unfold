package com.github.mohamedshemees.kmpresourcesunfold

import com.intellij.openapi.diagnostic.Logger
import java.util.Locale

data class KotlinImageVectorIcon(
    val name: String,
    val xmlContent: String,
    val startOffset: Int = 0,
    val endOffset: Int = 0
)

object KotlinImageVectorParser {
    private val LOG = Logger.getInstance(KotlinImageVectorParser::class.java)

    fun isKotlinImageVectorFile(content: String): Boolean {
        return content.contains("ImageVector") && (
            content.contains("ImageVector.Builder") || 
            content.contains("Builder(") ||
            content.contains(": ImageVector")
        )
    }

    fun parseKotlinImageVectors(content: String): List<KotlinImageVectorIcon> {
        val icons = mutableListOf<KotlinImageVectorIcon>()
        if (!isKotlinImageVectorFile(content)) return icons

        try {
            var searchIndex = 0
            while (searchIndex < content.length) {
                val builderIndex = content.indexOf("ImageVector.Builder", searchIndex)
                if (builderIndex == -1) break

                val openParen = content.indexOf('(', builderIndex)
                if (openParen == -1) {
                    searchIndex = builderIndex + 18
                    continue
                }
                
                val closeParen = findMatchingParen(content, openParen)
                if (closeParen == -1) {
                    searchIndex = builderIndex + 18
                    continue
                }

                val builderArgsStr = content.substring(openParen + 1, closeParen)
                
                val precedingSnippet = content.substring(maxOf(0, builderIndex - 200), builderIndex)
                val propMatch = Regex("""val\s+([A-Za-z0-9_]+)\s*:\s*ImageVector""").findAll(precedingSnippet).lastOrNull()
                val propName = propMatch?.groupValues?.get(1)

                val name = extractParamValue(builderArgsStr, "name")?.trim('"') 
                    ?: propName 
                    ?: "Icon"

                val defaultWidth = extractDimensionParam(builderArgsStr, "defaultWidth") ?: "24dp"
                val defaultHeight = extractDimensionParam(builderArgsStr, "defaultHeight") ?: "24dp"
                val viewportWidth = extractFloatParam(builderArgsStr, "viewportWidth") ?: "24"
                val viewportHeight = extractFloatParam(builderArgsStr, "viewportHeight") ?: "24"
                val autoMirror = extractBooleanParam(builderArgsStr, "autoMirror") ?: false

                val applyIndex = content.indexOf(".apply", closeParen)
                val blockStart = if (applyIndex != -1 && applyIndex < closeParen + 150) {
                    content.indexOf('{', applyIndex)
                } else {
                    content.indexOf('{', closeParen)
                }

                if (blockStart == -1) {
                    searchIndex = closeParen + 1
                    continue
                }
                val blockEnd = findMatchingBrace(content, blockStart)
                if (blockEnd == -1) {
                    searchIndex = blockStart + 1
                    continue
                }

                val bodyContent = content.substring(blockStart + 1, blockEnd)
                val xmlBody = parseBuilderBody(bodyContent)

                val autoMirrorAttr = if (autoMirror) " android:autoMirrored=\"true\"" else ""
                val xml = buildString {
                    append("<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n")
                    append("    android:width=\"$defaultWidth\"\n")
                    append("    android:height=\"$defaultHeight\"\n")
                    append("    android:viewportWidth=\"$viewportWidth\"\n")
                    append("    android:viewportHeight=\"$viewportHeight\"$autoMirrorAttr>\n")
                    append(xmlBody)
                    append("</vector>")
                }

                val startOffset = if (propMatch != null) maxOf(0, builderIndex - 200) + propMatch.range.first else builderIndex
                icons.add(KotlinImageVectorIcon(name, xml, startOffset, blockEnd))

                searchIndex = blockEnd + 1
            }
        } catch (e: Exception) {
            LOG.warn("Error parsing Kotlin ImageVectors: ${e.message}", e)
        }

        return icons
    }

    private fun findMatchingParen(str: String, openIndex: Int): Int {
        var depth = 0
        for (i in openIndex until str.length) {
            if (str[i] == '(') depth++
            else if (str[i] == ')') {
                depth--
                if (depth == 0) return i
            }
        }
        return -1
    }

    private fun findMatchingBrace(str: String, openIndex: Int): Int {
        var depth = 0
        for (i in openIndex until str.length) {
            if (str[i] == '{') depth++
            else if (str[i] == '}') {
                depth--
                if (depth == 0) return i
            }
        }
        return -1
    }

    private fun extractParamValue(argsStr: String, paramName: String): String? {
        val regex = Regex("""$paramName\s*=\s*(".*?"|[^,\s)]+)""")
        val match = regex.find(argsStr)
        return match?.groupValues?.get(1)
    }

    private fun extractDimensionParam(argsStr: String, paramName: String): String? {
        val valStr = extractParamValue(argsStr, paramName) ?: return null
        val num = valStr.replace(".dp", "").replace("dp", "").trim()
        val numDouble = num.toDoubleOrNull()
        return if (numDouble != null) "${fmt(numDouble)}dp" else null
    }

    private fun extractFloatParam(argsStr: String, paramName: String): String? {
        val valStr = extractParamValue(argsStr, paramName) ?: return null
        val num = valStr.replace("f", "").trim()
        val numDouble = num.toDoubleOrNull()
        return if (numDouble != null) fmt(numDouble) else null
    }

    private fun extractBooleanParam(argsStr: String, paramName: String): Boolean? {
        val valStr = extractParamValue(argsStr, paramName) ?: return null
        return valStr.lowercase() == "true"
    }

    private fun parseBuilderBody(body: String): String {
        val sb = StringBuilder()
        var i = 0

        while (i < body.length) {
            val pathMatch = findCallBlock(body, "path", i)
            if (pathMatch != null && pathMatch.startIndex == i) {
                sb.append(parsePathCall(pathMatch.args, pathMatch.blockBody))
                i = pathMatch.endIndex
                continue
            }

            val groupMatch = findCallBlock(body, "group", i)
            if (groupMatch != null && groupMatch.startIndex == i) {
                sb.append("  <group>\n")
                sb.append(parseBuilderBody(groupMatch.blockBody).prependIndent("  "))
                sb.append("  </group>\n")
                i = groupMatch.endIndex
                continue
            }

            i++
        }

        return sb.toString()
    }

    private data class CallBlockMatch(
        val startIndex: Int,
        val endIndex: Int,
        val args: String,
        val blockBody: String
    )

    private fun findCallBlock(content: String, callName: String, fromIndex: Int): CallBlockMatch? {
        val regex = Regex("""\b$callName\s*(?:\((.*?)\))?\s*\{""", RegexOption.DOT_MATCHES_ALL)
        val match = regex.find(content, fromIndex) ?: return null
        
        val prefix = content.substring(fromIndex, match.range.first).trim()
        if (prefix.isNotEmpty()) return null

        val openBrace = content.indexOf('{', match.range.first)
        if (openBrace == -1) return null
        val closeBrace = findMatchingBrace(content, openBrace)
        if (closeBrace == -1) return null

        val args = match.groupValues.getOrNull(1) ?: ""
        val blockBody = content.substring(openBrace + 1, closeBrace)

        return CallBlockMatch(fromIndex, closeBrace + 1, args, blockBody)
    }

    private fun parsePathCall(argsStr: String, pathBody: String): String {
        val fillColor = parseColorParam(argsStr, "fill")
        val fillAlpha = extractFloatParam(argsStr, "fillAlpha")
        val strokeColor = parseColorParam(argsStr, "stroke")
        val strokeAlpha = extractFloatParam(argsStr, "strokeAlpha")
        val strokeWidth = extractFloatParam(argsStr, "strokeLineWidth")
        val strokeCap = extractParamValue(argsStr, "strokeLineCap")?.substringAfterLast(".")?.lowercase()
        val strokeJoin = extractParamValue(argsStr, "strokeLineJoin")?.substringAfterLast(".")?.lowercase()
        val fillType = extractParamValue(argsStr, "pathFillType")?.substringAfterLast(".")

        val pathData = parsePathStatements(pathBody)

        return buildString {
            append("  <path\n")
            append("      android:pathData=\"$pathData\"\n")
            if (fillColor != null) {
                append("      android:fillColor=\"$fillColor\"\n")
            } else if (strokeColor == null) {
                append("      android:fillColor=\"#FF000000\"\n")
            }
            if (fillAlpha != null) append("      android:fillAlpha=\"$fillAlpha\"\n")
            if (fillType.equals("EvenOdd", ignoreCase = true)) append("      android:fillType=\"evenOdd\"\n")
            if (strokeColor != null) {
                append("      android:strokeColor=\"$strokeColor\"\n")
                if (strokeAlpha != null) append("      android:strokeAlpha=\"$strokeAlpha\"\n")
                if (strokeWidth != null) append("      android:strokeWidth=\"$strokeWidth\"\n")
                if (strokeCap != null) append("      android:strokeLineCap=\"$strokeCap\"\n")
                if (strokeJoin != null) append("      android:strokeLineJoin=\"$strokeJoin\"\n")
            }
            append("  />\n")
        }
    }

    private fun parseColorParam(argsStr: String, paramName: String): String? {
        val valStr = extractParamValue(argsStr, paramName) ?: return null
        if (valStr.contains("SolidColor(")) {
            val colorInside = valStr.substringAfter("SolidColor(").substringBeforeLast(")")
            return parseColorValue(colorInside)
        }
        return parseColorValue(valStr)
    }

    private fun parseColorValue(str: String): String? {
        val s = str.trim()
        if (s.startsWith("Color(")) {
            val inner = s.substringAfter("Color(").substringBeforeLast(")").trim()
            if (inner.startsWith("0x", ignoreCase = true)) {
                val hexRaw = inner.substring(2).removeSuffix("L").removeSuffix("l")
                return if (hexRaw.length == 8) {
                    val alpha = hexRaw.substring(0, 2)
                    val rgb = hexRaw.substring(2)
                    if (alpha.equals("FF", ignoreCase = true)) "#$rgb" else "#$alpha$rgb"
                } else "#$hexRaw"
            }
        }
        return when {
            s.contains("Color.White") -> "#FFFFFF"
            s.contains("Color.Black") -> "#000000"
            s.contains("Color.Red") -> "#FF0000"
            s.contains("Color.Green") -> "#00FF00"
            s.contains("Color.Blue") -> "#0000FF"
            s.contains("Color.Yellow") -> "#FFFF00"
            s.contains("Color.Cyan") -> "#00FFFF"
            s.contains("Color.Magenta") -> "#FF00FF"
            s.contains("Color.Transparent") -> "#00000000"
            else -> null
        }
    }

    private fun parsePathStatements(pathBody: String): String {
        val statements = pathBody.split(Regex("[\n;]"))
        val sb = StringBuilder()

        for (stmt in statements) {
            val line = stmt.trim()
            if (line.isEmpty() || line.startsWith("//")) continue

            val funcRegex = Regex("""([A-Za-z0-9_]+)\s*\((.*?)\)""")
            val match = funcRegex.find(line) ?: continue
            val funcName = match.groupValues[1]
            val argsStr = match.groupValues[2]

            val numbers = extractNumbersAndBooleans(argsStr)

            when (funcName) {
                "moveTo" -> if (numbers.size >= 2) sb.append("M ${numbers[0]} ${numbers[1]} ")
                "moveToRelative" -> if (numbers.size >= 2) sb.append("m ${numbers[0]} ${numbers[1]} ")
                "lineTo" -> if (numbers.size >= 2) sb.append("L ${numbers[0]} ${numbers[1]} ")
                "lineToRelative" -> if (numbers.size >= 2) sb.append("l ${numbers[0]} ${numbers[1]} ")
                "horizontalLineTo" -> if (numbers.size >= 1) sb.append("H ${numbers[0]} ")
                "horizontalLineToRelative" -> if (numbers.size >= 1) sb.append("h ${numbers[0]} ")
                "verticalLineTo" -> if (numbers.size >= 1) sb.append("V ${numbers[0]} ")
                "verticalLineToRelative" -> if (numbers.size >= 1) sb.append("v ${numbers[0]} ")
                "curveTo" -> if (numbers.size >= 6) sb.append("C ${numbers[0]} ${numbers[1]} ${numbers[2]} ${numbers[3]} ${numbers[4]} ${numbers[5]} ")
                "curveToRelative" -> if (numbers.size >= 6) sb.append("c ${numbers[0]} ${numbers[1]} ${numbers[2]} ${numbers[3]} ${numbers[4]} ${numbers[5]} ")
                "reflectiveCurveTo" -> if (numbers.size >= 4) sb.append("S ${numbers[0]} ${numbers[1]} ${numbers[2]} ${numbers[3]} ")
                "reflectiveCurveToRelative" -> if (numbers.size >= 4) sb.append("s ${numbers[0]} ${numbers[1]} ${numbers[2]} ${numbers[3]} ")
                "quadTo" -> if (numbers.size >= 4) sb.append("Q ${numbers[0]} ${numbers[1]} ${numbers[2]} ${numbers[3]} ")
                "quadToRelative" -> if (numbers.size >= 4) sb.append("q ${numbers[0]} ${numbers[1]} ${numbers[2]} ${numbers[3]} ")
                "reflectiveQuadTo" -> if (numbers.size >= 2) sb.append("T ${numbers[0]} ${numbers[1]} ")
                "reflectiveQuadToRelative" -> if (numbers.size >= 2) sb.append("t ${numbers[0]} ${numbers[1]} ")
                "arcTo" -> if (numbers.size >= 7) sb.append("A ${numbers[0]} ${numbers[1]} ${numbers[2]} ${numbers[3]} ${numbers[4]} ${numbers[5]} ${numbers[6]} ")
                "arcToRelative" -> if (numbers.size >= 7) sb.append("a ${numbers[0]} ${numbers[1]} ${numbers[2]} ${numbers[3]} ${numbers[4]} ${numbers[5]} ${numbers[6]} ")
                "close" -> sb.append("Z ")
            }
        }

        return sb.toString().trim()
    }

    private fun extractNumbersAndBooleans(argsStr: String): List<String> {
        val result = mutableListOf<String>()
        val parts = argsStr.split(',')
        for (part in parts) {
            val token = part.trim()
            if (token.isEmpty()) continue

            if (token.contains("isMoreThanHalf") || token.contains("isPositiveArc")) {
                val boolVal = token.substringAfter('=').trim().lowercase()
                result.add(if (boolVal == "true") "1" else "0")
            } else if (token.equals("true", ignoreCase = true)) {
                result.add("1")
            } else if (token.equals("false", ignoreCase = true)) {
                result.add("0")
            } else {
                val numStr = token.substringAfter('=').trim()
                    .replace("f", "")
                    .replace(".dp", "")
                    .replace("dp", "")
                    .trim()
                val d = numStr.toDoubleOrNull()
                if (d != null) {
                    result.add(fmt(d))
                }
            }
        }
        return result
    }

    private fun fmt(d: Double): String {
        return "%.3f".format(Locale.US, d).trimEnd('0').trimEnd('.')
    }

    fun convertXmlToKotlinImageVector(
        xmlContent: String, 
        iconName: String = "MyIcon",
        packageName: String = "com.example.resources"
    ): String {
        val widthMatch = Regex("""android:width="(\d+(?:\.\d+)?)(?:dp|px)?""").find(xmlContent)
        val heightMatch = Regex("""android:height="(\d+(?:\.\d+)?)(?:dp|px)?""").find(xmlContent)
        val vpWidth = Regex("""android:viewportWidth="([^"]+)"""").find(xmlContent)?.groupValues?.get(1) ?: "24"
        val vpHeight = Regex("""android:viewportHeight="([^"]+)"""").find(xmlContent)?.groupValues?.get(1) ?: "24"
        val width = widthMatch?.groupValues?.get(1) ?: vpWidth
        val height = heightMatch?.groupValues?.get(1) ?: vpHeight
        val autoMirrored = xmlContent.contains("""android:autoMirrored="true"""")

        val cleanName = iconName.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
        val backingVarName = "_$cleanName"

        val pathMatches = Regex("""<path\b([^>]*?)/>""", RegexOption.DOT_MATCHES_ALL).findAll(xmlContent)

        val sb = StringBuilder()
        if (packageName.isNotEmpty()) {
            sb.append("package $packageName\n\n")
        }
        sb.append("import androidx.compose.ui.graphics.Color\n")
        sb.append("import androidx.compose.ui.graphics.PathFillType\n")
        sb.append("import androidx.compose.ui.graphics.SolidColor\n")
        sb.append("import androidx.compose.ui.graphics.StrokeCap\n")
        sb.append("import androidx.compose.ui.graphics.StrokeJoin\n")
        sb.append("import androidx.compose.ui.graphics.vector.ImageVector\n")
        sb.append("import androidx.compose.ui.graphics.vector.path\n")
        sb.append("import androidx.compose.ui.unit.dp\n\n")

        sb.append("public val $cleanName: ImageVector\n")
        sb.append("    get() {\n")
        sb.append("        if ($backingVarName != null) {\n")
        sb.append("            return $backingVarName!!\n")
        sb.append("        }\n")
        sb.append("        $backingVarName = ImageVector.Builder(\n")
        sb.append("            name = \"$cleanName\",\n")
        sb.append("            defaultWidth = ${width}.dp,\n")
        sb.append("            defaultHeight = ${height}.dp,\n")
        sb.append("            viewportWidth = ${vpWidth}f,\n")
        sb.append("            viewportHeight = ${vpHeight}f")
        if (autoMirrored) {
            sb.append(",\n            autoMirror = true")
        }
        sb.append("\n        ).apply {\n")

        for (pm in pathMatches) {
            val attrs = pm.groupValues[1]
            val pathData = Regex("""android:pathData="([^"]+)"""").find(attrs)?.groupValues?.get(1) ?: continue
            val fillColor = Regex("""android:fillColor="([^"]+)"""").find(attrs)?.groupValues?.get(1)
            val fillAlpha = Regex("""android:fillAlpha="([^"]+)"""").find(attrs)?.groupValues?.get(1)
            val strokeColor = Regex("""android:strokeColor="([^"]+)"""").find(attrs)?.groupValues?.get(1)
            val strokeAlpha = Regex("""android:strokeAlpha="([^"]+)"""").find(attrs)?.groupValues?.get(1)
            val strokeWidth = Regex("""android:strokeWidth="([^"]+)"""").find(attrs)?.groupValues?.get(1)
            val strokeCap = Regex("""android:strokeLineCap="([^"]+)"""").find(attrs)?.groupValues?.get(1)
            val strokeJoin = Regex("""android:strokeLineJoin="([^"]+)"""").find(attrs)?.groupValues?.get(1)
            val fillType = Regex("""android:fillType="([^"]+)"""").find(attrs)?.groupValues?.get(1)

            val pathParams = mutableListOf<String>()
            if (fillColor != null && fillColor != "#00000000") {
                pathParams.add("fill = SolidColor(${formatComposeColor(fillColor)})")
            }
            if (fillAlpha != null && fillAlpha != "1.0" && fillAlpha != "1") {
                pathParams.add("fillAlpha = ${fillAlpha}f")
            }
            if (strokeColor != null) {
                pathParams.add("stroke = SolidColor(${formatComposeColor(strokeColor)})")
            }
            if (strokeAlpha != null && strokeAlpha != "1.0" && strokeAlpha != "1") {
                pathParams.add("strokeAlpha = ${strokeAlpha}f")
            }
            if (strokeWidth != null) {
                pathParams.add("strokeLineWidth = ${strokeWidth}f")
            }
            if (strokeCap != null) {
                val cap = when (strokeCap.lowercase()) {
                    "round" -> "StrokeCap.Round"
                    "square" -> "StrokeCap.Square"
                    else -> "StrokeCap.Butt"
                }
                pathParams.add("strokeLineCap = $cap")
            }
            if (strokeJoin != null) {
                val join = when (strokeJoin.lowercase()) {
                    "round" -> "StrokeJoin.Round"
                    "bevel" -> "StrokeJoin.Bevel"
                    else -> "StrokeJoin.Miter"
                }
                pathParams.add("strokeLineJoin = $join")
            }
            if (fillType.equals("evenOdd", ignoreCase = true)) {
                pathParams.add("pathFillType = PathFillType.EvenOdd")
            }

            sb.append("            path(\n")
            if (pathParams.isNotEmpty()) {
                sb.append(pathParams.joinToString(",\n") { "                $it" })
                sb.append("\n            ) {\n")
            } else {
                sb.append("            ) {\n")
            }

            val dStatements = convertSvgPathDataToKotlinStatements(pathData)
            for (stmt in dStatements) {
                sb.append("                $stmt\n")
            }
            sb.append("            }\n")
        }

        sb.append("        }.build()\n\n")
        sb.append("        return $backingVarName!!\n")
        sb.append("    }\n\n")
        sb.append("private var $backingVarName: ImageVector? = null\n")

        return sb.toString()
    }

    private fun formatComposeColor(colorHex: String): String {
        return when (colorHex.uppercase()) {
            "#FFFFFF", "#FFFFFFFF" -> "Color.White"
            "#000000", "#FF000000" -> "Color.Black"
            "#FF0000", "#FFFF0000" -> "Color.Red"
            "#00FF00", "#FF00FF00" -> "Color.Green"
            "#0000FF", "#FF0000FF" -> "Color.Blue"
            else -> {
                val hex = colorHex.trimStart('#')
                if (hex.length == 6) "Color(0xFF$hex)"
                else if (hex.length == 8) "Color(0x$hex)"
                else "Color(0xFF000000)"
            }
        }
    }

    private sealed class PathToken {
        data class Command(val cmd: Char) : PathToken()
        data class Number(val value: Double) : PathToken()
    }

    private fun tokenizePathData(d: String): List<PathToken> {
        val tokens = mutableListOf<PathToken>()
        var i = 0
        while (i < d.length) {
            val c = d[i]
            when {
                c.isWhitespace() || c == ',' -> i++
                c.isLetter() -> { tokens.add(PathToken.Command(c)); i++ }
                c == '-' || c == '+' || c.isDigit() || c == '.' -> {
                    val sb = StringBuilder()
                    if (c == '-' || c == '+') { sb.append(c); i++ }
                    while (i < d.length && d[i].isDigit()) { sb.append(d[i]); i++ }
                    if (i < d.length && d[i] == '.') {
                        sb.append(d[i]); i++
                        while (i < d.length && d[i].isDigit()) { sb.append(d[i]); i++ }
                    }
                    if (i < d.length && (d[i] == 'e' || d[i] == 'E')) {
                        sb.append(d[i]); i++
                        if (i < d.length && (d[i] == '-' || d[i] == '+')) { sb.append(d[i]); i++ }
                        while (i < d.length && d[i].isDigit()) { sb.append(d[i]); i++ }
                    }
                    sb.toString().toDoubleOrNull()?.let { tokens.add(PathToken.Number(it)) }
                }
                else -> i++
            }
        }
        return tokens
    }

    private fun convertSvgPathDataToKotlinStatements(pathData: String): List<String> {
        val statements = mutableListOf<String>()
        val tokens = tokenizePathData(pathData)
        var i = 0
        var lastCmd = ' '

        fun fmtF(d: Double): String {
            val s = "%.3f".format(Locale.US, d).trimEnd('0').trimEnd('.')
            return if (s.contains('.')) "${s}f" else "${s}f"
        }

        while (i < tokens.size) {
            val tok = tokens[i]
            val cmd = if (tok is PathToken.Command) {
                i++
                lastCmd = tok.cmd
                tok.cmd
            } else {
                lastCmd
            }

            val isRel = cmd.isLowerCase()
            val cUpper = cmd.uppercaseChar()

            fun nextNum(): Double? {
                if (i < tokens.size && tokens[i] is PathToken.Number) {
                    return (tokens[i++] as PathToken.Number).value
                }
                return null
            }

            when (cUpper) {
                'M' -> {
                    val x = nextNum() ?: break
                    val y = nextNum() ?: break
                    statements.add(if (isRel) "moveToRelative(${fmtF(x)}, ${fmtF(y)})" else "moveTo(${fmtF(x)}, ${fmtF(y)})")
                }
                'L' -> {
                    val x = nextNum() ?: break
                    val y = nextNum() ?: break
                    statements.add(if (isRel) "lineToRelative(${fmtF(x)}, ${fmtF(y)})" else "lineTo(${fmtF(x)}, ${fmtF(y)})")
                }
                'H' -> {
                    val x = nextNum() ?: break
                    statements.add(if (isRel) "horizontalLineToRelative(${fmtF(x)})" else "horizontalLineTo(${fmtF(x)})")
                }
                'V' -> {
                    val y = nextNum() ?: break
                    statements.add(if (isRel) "verticalLineToRelative(${fmtF(y)})" else "verticalLineTo(${fmtF(y)})")
                }
                'C' -> {
                    val x1 = nextNum() ?: break; val y1 = nextNum() ?: break
                    val x2 = nextNum() ?: break; val y2 = nextNum() ?: break
                    val x3 = nextNum() ?: break; val y3 = nextNum() ?: break
                    statements.add(if (isRel) 
                        "curveToRelative(${fmtF(x1)}, ${fmtF(y1)}, ${fmtF(x2)}, ${fmtF(y2)}, ${fmtF(x3)}, ${fmtF(y3)})" 
                        else "curveTo(${fmtF(x1)}, ${fmtF(y1)}, ${fmtF(x2)}, ${fmtF(y2)}, ${fmtF(x3)}, ${fmtF(y3)})")
                }
                'S' -> {
                    val x2 = nextNum() ?: break; val y2 = nextNum() ?: break
                    val x3 = nextNum() ?: break; val y3 = nextNum() ?: break
                    statements.add(if (isRel) 
                        "reflectiveCurveToRelative(${fmtF(x2)}, ${fmtF(y2)}, ${fmtF(x3)}, ${fmtF(y3)})" 
                        else "reflectiveCurveTo(${fmtF(x2)}, ${fmtF(y2)}, ${fmtF(x3)}, ${fmtF(y3)})")
                }
                'Q' -> {
                    val x1 = nextNum() ?: break; val y1 = nextNum() ?: break
                    val x2 = nextNum() ?: break; val y2 = nextNum() ?: break
                    statements.add(if (isRel) 
                        "quadToRelative(${fmtF(x1)}, ${fmtF(y1)}, ${fmtF(x2)}, ${fmtF(y2)})" 
                        else "quadTo(${fmtF(x1)}, ${fmtF(y1)}, ${fmtF(x2)}, ${fmtF(y2)})")
                }
                'T' -> {
                    val x1 = nextNum() ?: break; val y1 = nextNum() ?: break
                    statements.add(if (isRel) 
                        "reflectiveQuadToRelative(${fmtF(x1)}, ${fmtF(y1)})" 
                        else "reflectiveQuadTo(${fmtF(x1)}, ${fmtF(y1)})")
                }
                'A' -> {
                    val rx = nextNum() ?: break; val ry = nextNum() ?: break
                    val rot = nextNum() ?: break
                    val largeArc = (nextNum() ?: 0.0) != 0.0
                    val sweep = (nextNum() ?: 0.0) != 0.0
                    val x = nextNum() ?: break; val y = nextNum() ?: break
                    statements.add(if (isRel)
                        "arcToRelative(${fmtF(rx)}, ${fmtF(ry)}, ${fmtF(rot)}, isMoreThanHalf = $largeArc, isPositiveArc = $sweep, ${fmtF(x)}, ${fmtF(y)})"
                        else "arcTo(${fmtF(rx)}, ${fmtF(ry)}, ${fmtF(rot)}, isMoreThanHalf = $largeArc, isPositiveArc = $sweep, ${fmtF(x)}, ${fmtF(y)})")
                }
                'Z' -> {
                    statements.add("close()")
                }
            }
        }
        return statements
    }
}
