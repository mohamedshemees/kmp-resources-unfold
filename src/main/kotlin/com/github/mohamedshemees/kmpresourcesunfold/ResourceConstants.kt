package com.github.mohamedshemees.kmpresourcesunfold

enum class ResourceExtension(val extension: String) {
    XML("xml"),
    PNG("png"),
    SVG("svg"),
    WEBP("webp"),
    JPG("jpg"),
    JPEG("jpeg");

    companion object {
        fun fromExtension(ext: String?): ResourceExtension? =
            entries.find { it.extension == ext?.lowercase() }

        val imageExtensions = setOf(PNG, WEBP, JPG, JPEG)
        val vectorExtensions = setOf(XML, SVG)
        val allExtensions = entries.map { it.extension }.toSet()
    }
}

enum class ResourceType {
    ALL, VECTORS, IMAGES, STRINGS;

    companion object {
        fun fromFilterName(name: String): ResourceType = when (name) {
            "Vectors" -> VECTORS
            "Images" -> IMAGES
            "Strings" -> STRINGS
            else -> ALL
        }
    }
}

object ResourceConstants {
    const val COMPOSE_RESOURCES_DIR = "composeResources"
    const val DRAWABLE_DIR = "drawable"
    const val VALUES_DIR = "values"
    const val STRINGS_FILE = "strings.xml"
    const val BUILD_DIR_1 = "/build/"
    const val BUILD_DIR_2 = "\\build\\"
    const val VECTOR_TAG = "<vector"
    const val PAINTER_RESOURCE_TEMPLATE = "painterResource(Res.drawable.%s)"
    const val STRING_RESOURCE_TEMPLATE = "Res.string.%s"
}

