package com.github.mohamedshemees.kmpresourcesunfold.bridge

import com.github.mohamedshemees.kmpresourcesunfold.toolWindow.ImportDrawablesDialog
import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.*
import java.util.concurrent.Executors

class FigmaBridgeServer(private val project: Project) {
    private val LOG = Logger.getInstance(FigmaBridgeServer::class.java)
    private var server: HttpServer? = null
    private val gson = Gson()
    var activePort: Int? = null
        private set

    fun start(): Int? {
        stop()
        val startPort = 6789
        var currentPort = startPort
        val maxTries = 10

        for (i in 0 until maxTries) {
            try {
                server = HttpServer.create(InetSocketAddress("127.0.0.1", currentPort), 0)
                server?.let {
                    it.createContext("/import", ImportHandler())
                    it.executor = Executors.newFixedThreadPool(2)
                    it.start()
                    LOG.info("Figma Bridge Server started on port $currentPort")
                    activePort = currentPort
                    return currentPort
                }
            } catch (e: Exception) {
                LOG.info("Port $currentPort is taken, trying next... ($e)")
                currentPort++
            }
        }
        LOG.error("Failed to start Figma Bridge Server after $maxTries attempts.")
        return null
    }

    fun stop() {
        server?.stop(0)
        server = null
        activePort = null
        LOG.info("Figma Bridge Server stopped")
    }

    private data class FigmaImage(
        val name: String,
        val data: String,
        val width: Int,
        val height: Int,
        val density: String,
        val format: String
    )

    private data class FigmaImportRequest(
        val images: List<FigmaImage>
    )

    inner class ImportHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            if (exchange.requestMethod == "OPTIONS") {
                exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
                exchange.responseHeaders.add("Access-Control-Allow-Methods", "POST, OPTIONS")
                exchange.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type")
                exchange.sendResponseHeaders(204, -1)
                return
            }

            if (exchange.requestMethod != "POST") {
                exchange.sendResponseHeaders(405, -1)
                return
            }

            exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")

            try {
                val body = exchange.requestBody.bufferedReader().readText()
                val request = gson.fromJson(body, FigmaImportRequest::class.java)

                if (request?.images == null) {
                    sendResponse(exchange, 400, "Invalid payload")
                    return
                }

                val virtualFiles = request.images.map { image ->
                    val bytes = Base64.getDecoder().decode(image.data)
                    val densitySuffix = when (image.density.lowercase()) {
                        "hdpi" -> "@1.5x"
                        "xhdpi" -> "@2x"
                        "xxhdpi" -> "@3x"
                        "xxxhdpi" -> "@4x"
                        "any" -> ""
                        else -> ""
                    }
                    val finalName = "${image.name}$densitySuffix.${image.format.lowercase()}"
                    FigmaInMemoryFile(finalName, bytes)
                }

                ApplicationManager.getApplication().invokeLater {
                    ImportDrawablesDialog(project, virtualFiles).show()
                }

                sendResponse(exchange, 200, "OK")
            } catch (e: Exception) {
                LOG.error("Figma Import Error", e)
                sendResponse(exchange, 500, "Error: ${e.message}")
            }
        }

        private fun sendResponse(exchange: HttpExchange, code: Int, text: String) {
            exchange.sendResponseHeaders(code, text.length.toLong())
            exchange.responseBody.use { it.write(text.toByteArray()) }
        }
    }
}
