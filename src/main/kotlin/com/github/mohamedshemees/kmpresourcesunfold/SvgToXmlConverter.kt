package com.github.mohamedshemees.kmpresourcesunfold

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.InputStream
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

object SvgToXmlConverter {

    private const val DEFAULT_VIEWPORT_SIZE = 24
    private const val XML_INDENT = "  "
    private const val DEFAULT_FILL_COLOR = "#FF000000"
    private const val TRANSPARENT_COLOR = "#00000000"

    private val colorMap = mapOf(
        "white" to "#ffffff",
        "black" to "#000000",
        "red" to "#ff0000",
        "green" to "#00ff00",
        "blue" to "#0000ff",
        "yellow" to "#ffff00",
        "cyan" to "#00ffff",
        "magenta" to "#ff00ff",
        "none" to null
    )

    fun convertToXml(inputStream: InputStream): String {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(inputStream)
        val svgElement = doc.documentElement

        val viewBoxAttr = svgElement.getAttribute("viewBox")
        val viewBox = if (viewBoxAttr.isNotEmpty()) viewBoxAttr.split(Regex("\\s+|,\\s*")) else emptyList()

        val widthStr = svgElement.getAttribute("width").replace("px", "")
        val heightStr = svgElement.getAttribute("height").replace("px", "")
        val width = widthStr.toDoubleOrNull()?.toInt() ?: DEFAULT_VIEWPORT_SIZE
        val height = heightStr.toDoubleOrNull()?.toInt() ?: DEFAULT_VIEWPORT_SIZE

        val defaultFill = normalizeColor(svgElement.getAttribute("fill").ifEmpty {
            val style = svgElement.getAttribute("style")
            val fillMatch = Regex("fill:\\s*([^;]+)").find(style)
            fillMatch?.groupValues?.get(1) ?: DEFAULT_FILL_COLOR
        }) ?: DEFAULT_FILL_COLOR

        val minX = if (viewBox.size == 4) viewBox[0].toDoubleOrNull() ?: 0.0 else 0.0
        val minY = if (viewBox.size == 4) viewBox[1].toDoubleOrNull() ?: 0.0 else 0.0
        val viewportWidth  = if (viewBox.size == 4) viewBox[2] else width.toString()
        val viewportHeight = if (viewBox.size == 4) viewBox[3] else height.toString()

        return buildString {
            append("<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n")
            append("${XML_INDENT}android:width=\"${width}dp\"\n")
            append("${XML_INDENT}android:height=\"${height}dp\"\n")
            append("${XML_INDENT}android:viewportWidth=\"$viewportWidth\"\n")
            append("${XML_INDENT}android:viewportHeight=\"$viewportHeight\">\n")

            val children = svgElement.childNodes
            for (n in 0 until children.length) {
                val node = children.item(n)
                if (node.nodeType != Node.ELEMENT_NODE) continue
                val el = node as Element
                when (el.tagName.lowercase()) {
                    "rect"   -> appendRect(this, el, defaultFill, minX, minY)
                    "path"   -> appendPath(this, el, defaultFill, minX, minY)
                    "circle" -> appendCircle(this, el, defaultFill, minX, minY)
                }
            }

            append("</vector>")
        }
    }

    private fun appendPath(
        sb: StringBuilder, el: Element, defaultFill: String, minX: Double, minY: Double
    ) {
        val rawPathData = el.getAttribute("d")
        if (rawPathData.isEmpty()) return
        val pathData = bakeTranslation(rawPathData, -minX, -minY)
        val (fillColor, fillAlpha) = resolveFill(el, defaultFill)
        val (strokeColor, strokeAlpha) = resolveStroke(el)
        val strokeWidth = el.getAttribute("stroke-width").trim()
        val strokeLineCap = el.getAttribute("stroke-linecap").trim()
        val strokeLineJoin = el.getAttribute("stroke-linejoin").trim()

        val effectiveFillRule = getEffectiveFillRule(el)

        sb.append("${XML_INDENT}<path\n")
        sb.append("${XML_INDENT}${XML_INDENT}android:pathData=\"$pathData\"\n")
        sb.append("${XML_INDENT}${XML_INDENT}android:fillColor=\"$fillColor\"")
        if (fillAlpha != null) sb.append("\n${XML_INDENT}${XML_INDENT}android:fillAlpha=\"$fillAlpha\"")
        if (effectiveFillRule == "evenodd") sb.append("\n${XML_INDENT}${XML_INDENT}android:fillType=\"evenOdd\"")
        if (strokeColor != null) {
            sb.append("\n${XML_INDENT}${XML_INDENT}android:strokeColor=\"$strokeColor\"")
            if (strokeAlpha != null) sb.append("\n${XML_INDENT}${XML_INDENT}android:strokeAlpha=\"$strokeAlpha\"")
            if (strokeWidth.isNotEmpty()) sb.append("\n${XML_INDENT}${XML_INDENT}android:strokeWidth=\"${fmt(strokeWidth.toDoubleOrNull() ?: 1.0)}\"")
            if (strokeLineCap.isNotEmpty()) sb.append("\n${XML_INDENT}${XML_INDENT}android:strokeLineCap=\"$strokeLineCap\"")
            if (strokeLineJoin.isNotEmpty()) sb.append("\n${XML_INDENT}${XML_INDENT}android:strokeLineJoin=\"$strokeLineJoin\"")
        }
        sb.append("/>\n")
    }

    private fun getEffectiveFillRule(el: Element): String {
        val fillRule = el.getAttribute("fill-rule").trim().lowercase()
        val fillRuleFromStyle = Regex("fill-rule\\s*:\\s*(\\S+)").find(el.getAttribute("style"))?.groupValues?.get(1)?.trim()?.lowercase()
        return fillRule.ifEmpty { fillRuleFromStyle ?: "" }
    }

    private fun appendRect(
        sb: StringBuilder, el: Element, defaultFill: String, minX: Double, minY: Double
    ) {
        val x  = el.getAttribute("x").toDoubleOrNull() ?: 0.0
        val y  = el.getAttribute("y").toDoubleOrNull() ?: 0.0
        val w  = el.getAttribute("width").toDoubleOrNull()  ?: return
        val h  = el.getAttribute("height").toDoubleOrNull() ?: return
        val rxRaw = el.getAttribute("rx").toDoubleOrNull()
        val ryRaw = el.getAttribute("ry").toDoubleOrNull()
        val rx = rxRaw ?: ryRaw ?: 0.0
        val ry = ryRaw ?: rxRaw ?: 0.0

        val tx = x - minX
        val ty = y - minY
        val r = minOf(rx, ry, w / 2, h / 2)

        val pathData = if (r <= 0.0) {
            "M${fmt(tx)},${fmt(ty)}L${fmt(tx + w)},${fmt(ty)}L${fmt(tx + w)},${fmt(ty + h)}L${fmt(tx)},${fmt(ty + h)}Z"
        } else {
            val l = tx; val t = ty; val ri = tx + w; val b = ty + h
            "M${fmt(l + r)},${fmt(t)}L${fmt(ri - r)},${fmt(t)}A${fmt(r)},${fmt(r)} 0,0 1,${fmt(ri)} ${fmt(t + r)}" +
            "L${fmt(ri)},${fmt(b - r)}A${fmt(r)},${fmt(r)} 0,0 1,${fmt(ri - r)} ${fmt(b)}" +
            "L${fmt(l + r)},${fmt(b)}A${fmt(r)},${fmt(r)} 0,0 1,${fmt(l)} ${fmt(b - r)}" +
            "L${fmt(l)},${fmt(t + r)}A${fmt(r)},${fmt(r)} 0,0 1,${fmt(l + r)} ${fmt(t)}z"
        }

        val (fillColor, fillAlpha) = resolveFill(el, defaultFill)
        val (strokeColor, strokeAlpha) = resolveStroke(el)
        val strokeWidth = el.getAttribute("stroke-width").trim()

        sb.append("${XML_INDENT}<path\n")
        sb.append("${XML_INDENT}${XML_INDENT}android:pathData=\"$pathData\"\n")
        sb.append("${XML_INDENT}${XML_INDENT}android:fillColor=\"$fillColor\"")
        if (fillAlpha != null) sb.append("\n${XML_INDENT}${XML_INDENT}android:fillAlpha=\"$fillAlpha\"")
        if (strokeColor != null) {
            sb.append("\n${XML_INDENT}${XML_INDENT}android:strokeColor=\"$strokeColor\"")
            if (strokeAlpha != null) sb.append("\n${XML_INDENT}${XML_INDENT}android:strokeAlpha=\"$strokeAlpha\"")
            if (strokeWidth.isNotEmpty()) sb.append("\n${XML_INDENT}${XML_INDENT}android:strokeWidth=\"${fmt(strokeWidth.toDoubleOrNull() ?: 1.0)}\"")
        }
        sb.append("/>\n")
    }

    private fun appendCircle(
        sb: StringBuilder, el: Element, defaultFill: String, minX: Double, minY: Double
    ) {
        val cx = (el.getAttribute("cx").toDoubleOrNull() ?: 0.0) - minX
        val cy = (el.getAttribute("cy").toDoubleOrNull() ?: 0.0) - minY
        val r  = el.getAttribute("r").toDoubleOrNull() ?: return
        val pathData = "M${fmt(cx - r)},${fmt(cy)}" +
                "a${fmt(r)},${fmt(r)},0,1,0,${fmt(2 * r)},0" +
                "a${fmt(r)},${fmt(r)},0,1,0,${fmt(-2 * r)},0Z"
        val (fillColor, fillAlpha) = resolveFill(el, defaultFill)
        sb.append("${XML_INDENT}<path\n")
        sb.append("${XML_INDENT}${XML_INDENT}android:pathData=\"$pathData\"\n")
        sb.append("${XML_INDENT}${XML_INDENT}android:fillColor=\"$fillColor\"")
        if (fillAlpha != null) sb.append("\n${XML_INDENT}${XML_INDENT}android:fillAlpha=\"$fillAlpha\"")
        sb.append("/>\n")
    }

    private fun resolveFill(el: Element, defaultFill: String): Pair<String, String?> {
        val fillAttr = el.getAttribute("fill").trim()
        val hasStroke = el.getAttribute("stroke").trim().let { it.isNotEmpty() && it != "none" }
        val fill = when {
            fillAttr == "none" -> return Pair(TRANSPARENT_COLOR, null)
            fillAttr.isEmpty() && hasStroke -> return Pair(TRANSPARENT_COLOR, null)
            fillAttr.isEmpty() -> defaultFill
            else -> normalizeColor(fillAttr) ?: defaultFill
        }
        val (fillColor, hexAlpha) = extractAlpha(fill)

        val styleAttr = el.getAttribute("style")
        val fillOpacityFromStyle = Regex("fill-opacity\\s*:\\s*([\\d.]+)").find(styleAttr)?.groupValues?.get(1)
        val opacityFromStyle = Regex("(?<!fill-)opacity\\s*:\\s*([\\d.]+)").find(styleAttr)?.groupValues?.get(1)
        val fillOpacityStr = el.getAttribute("fill-opacity").trim().ifEmpty { fillOpacityFromStyle ?: "" }
        val opacityStr     = el.getAttribute("opacity").trim().ifEmpty { opacityFromStyle ?: "" }

        val fillAlpha: String? = when {
            fillOpacityStr.isNotEmpty() -> formatAlpha(fillOpacityStr.toDoubleOrNull() ?: 1.0)
            opacityStr.isNotEmpty()     -> formatAlpha(opacityStr.toDoubleOrNull() ?: 1.0)
            else                        -> hexAlpha
        }
        return Pair(fillColor, fillAlpha)
    }

    private fun resolveStroke(el: Element): Pair<String?, String?> {
        val strokeAttr = el.getAttribute("stroke").trim()
        if (strokeAttr.isEmpty() || strokeAttr == "none") return Pair(null, null)
        val strokeColor = normalizeColor(strokeAttr) ?: return Pair(null, null)
        val (color, hexAlpha) = extractAlpha(strokeColor)
        val strokeOpacityStr = el.getAttribute("stroke-opacity").trim()
        val alpha = if (strokeOpacityStr.isNotEmpty())
            formatAlpha(strokeOpacityStr.toDoubleOrNull() ?: 1.0)
        else hexAlpha
        return Pair(color, alpha)
    }

    private sealed class PathToken {
        data class Command(val cmd: Char) : PathToken()
        data class Number(val value: Double) : PathToken()
    }

    private fun tokenizePath(d: String): List<PathToken> {
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

    private fun argsPerCommand(cmd: Char): Int = when (cmd.uppercaseChar()) {
        'M', 'L', 'T' -> 2
        'H', 'V'      -> 1
        'S', 'Q'      -> 4
        'C'           -> 6
        'A'           -> 7
        'Z'           -> 0
        else          -> 2
    }

    private fun bakeTranslation(pathData: String, dx: Double, dy: Double): String {
        val tokens = tokenizePath(pathData)
        val result = StringBuilder()
        var i = 0

        var curX = 0.0
        var curY = 0.0
        var startX = 0.0
        var startY = 0.0
        var isFirstCmd = true

        while (i < tokens.size) {
            val token = tokens[i]
            if (token !is PathToken.Command) { i++; continue }

            val cmd = token.cmd
            val cmdUpper = cmd.uppercaseChar()
            val isRelative = cmd.isLowerCase() && !(isFirstCmd && cmdUpper == 'M')
            val argc = argsPerCommand(cmd)

            if (cmdUpper == 'Z') {
                result.append(cmd)
                curX = startX; curY = startY
                i++
                continue
            }

            result.append(cmd)
            i++
            isFirstCmd = false

            var firstInCmd = true
            var groupIndex = 0

            while (i < tokens.size && tokens[i] is PathToken.Number) {
                val group = mutableListOf<Double>()
                repeat(argc) {
                    if (i < tokens.size && tokens[i] is PathToken.Number) {
                        group.add((tokens[i] as PathToken.Number).value); i++
                    }
                }
                if (group.size < argc) break

                val groupIsRelative = isRelative || (cmdUpper == 'M' && groupIndex > 0)

                val translated = group.mapIndexed { argIdx, value ->
                    when {
                        groupIsRelative                    -> value
                        cmdUpper == 'H'                    -> value + dx
                        cmdUpper == 'V'                    -> value + dy
                        cmdUpper == 'A' && argIdx == 5     -> value + dx
                        cmdUpper == 'A' && argIdx == 6     -> value + dy
                        cmdUpper == 'A'                    -> value
                        argIdx % 2 == 0                    -> value + dx
                        else                               -> value + dy
                    }
                }

                translated.forEachIndexed { argIdx, value ->
                    val formatted = fmt(value)
                    val needSep = !firstInCmd && !formatted.startsWith('-')
                    val prevWasNeg = !firstInCmd && argIdx > 0 && translated[argIdx - 1] < 0
                    if (needSep || (prevWasNeg && formatted.startsWith('-'))) {
                        result.append(",")
                    }
                    result.append(formatted)
                    firstInCmd = false
                }

                if (!isRelative) {
                    when (cmdUpper) {
                        'M' -> { curX = translated[0]; curY = translated[1]
                            if (groupIndex == 0) { startX = curX; startY = curY } }
                        'L', 'T' -> { curX = translated[0]; curY = translated[1] }
                        'H' -> curX = translated[0]
                        'V' -> curY = translated[0]
                        'C' -> { curX = translated[4]; curY = translated[5] }
                        'S', 'Q' -> { curX = translated[2]; curY = translated[3] }
                        'A' -> { curX = translated[5]; curY = translated[6] }
                    }
                } else {
                    when (cmdUpper) {
                        'M' -> { curX += group[0]; curY += group[1]
                            if (groupIndex == 0) { startX = curX; startY = curY } }
                        'L', 'T' -> { curX += group[0]; curY += group[1] }
                        'H' -> curX += group[0]
                        'V' -> curY += group[0]
                        'C' -> { curX += group[4]; curY += group[5] }
                        'S', 'Q' -> { curX += group[2]; curY += group[3] }
                        'A' -> { curX += group[5]; curY += group[6] }
                    }
                }
                groupIndex++
            }
        }
        return result.toString()
    }

    private fun fmt(v: Double) = "%.3f".format(Locale.US, v).trimEnd('0').trimEnd('.')

    private fun formatAlpha(opacity: Double): String? {
        if (opacity >= 1.0) return null
        val s = "%.2f".format(Locale.US, opacity).trimEnd('0').trimEnd('.')
        return if (s.startsWith(".")) "0$s" else s
    }

    private fun extractAlpha(color: String): Pair<String, String?> {
        val hex = color.trimStart('#')
        if (hex.length == 8) {
            val alpha = hex.substring(0, 2).toIntOrNull(16) ?: 255
            val rgb = "#${hex.substring(2)}"
            if (alpha == 255) return Pair(rgb, null)
            val alphaStr = formatAlpha(alpha / 255.0)
            return Pair(rgb, alphaStr)
        }
        return Pair(color, null)
    }

    private fun normalizeColor(color: String): String? {
        val c = color.lowercase().trim()
        if (c.isEmpty()) return null
        if (c.startsWith("#")) {
            return when (c.length) {
                4    -> "#${c[1]}${c[1]}${c[2]}${c[2]}${c[3]}${c[3]}"
                else -> c
            }
        }
        return colorMap[c]
    }
}
