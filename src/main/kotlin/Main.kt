package org.mcinablackbox

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.Path
import kotlinx.serialization.json.Json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable

val fs = FileSystem.SYSTEM
var vPath = "none"
var sessionFile: Path = "${System.getProperty("user.home")}/Documents".toPath().div("MCVI-session.txt")

@Serializable
data class VersionManifest(
    val latest: Latest,
    val versions: List<VersionEntry>
)

@Serializable
data class Latest(val release: String, val snapshot: String)

@Serializable
data class VersionEntry(val id: String, val type: String, val url: String)

@Serializable
data class DownloadMap(
    val client: ClientDetails
)

@Serializable
data class ClientDetails(
    val url: String,
    val size: Long
)

@Serializable
data class VersionPackage(
    val downloads: DownloadMap,
    val libraries: List<LibraryEntry>,
    val mainClass: String,
    val assetIndex: AssetIndexInfo
)

@Serializable
data class AssetIndexInfo(
    val id: String,
    val url: String
)

@Serializable
data class AssetManifest(
    val objects: Map<String, AssetObject>
)

@Serializable
data class AssetObject(
    val hash: String,
    val size: Long
)

@Serializable
data class LibraryEntry(
    val downloads: LibraryDownloads,
    val name: String
)

@Serializable
data class LibraryDownloads(
    val artifact: ArtifactDetails? = null,
    val classifiers: Map<String, ArtifactDetails>? = null
)

@Serializable
data class ArtifactDetails(
    val url: String,
    val path: String,
    val size: Long
)

val client = HttpClient(CIO)
val url = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
var manifest: VersionManifest? = null
val json = Json { ignoreUnknownKeys = true }

suspend fun fetchMinecraftVersions() {
    try {
        val response: HttpResponse = client.get(url)
        val jsonBody = response.bodyAsText()
        manifest = json.decodeFromString<VersionManifest>(jsonBody)
        println("Manifest loaded: ${manifest?.versions?.size} versions found.")
    } catch (e: Exception) {
        println("Error: ${e.message}")
    }
}

suspend fun downloadVersion(versionId: String, metadataurl: String) {
    try {
        val instanceFolder = vPath.toPath().div("instances").div(versionId)

        if (fs.exists(instanceFolder.div("$versionId.jar"))) {
            println("Error: $versionId already exists.")
            return
        }

        val packageResponse = client.get(metadataurl).bodyAsText()
        val versionPkg = json.decodeFromString<VersionPackage>(packageResponse)
        val nativesFolder = instanceFolder.div("natives")
        if (!fs.exists(nativesFolder)) fs.createDirectories(nativesFolder)

        val jarUrl = versionPkg.downloads.client.url
        val targetFile = instanceFolder.div("$versionId.jar")
        val jarBytes = client.get(jarUrl).readRawBytes()
        fs.write(targetFile) { write(jarBytes) }

        versionPkg.libraries.forEach { lib ->
            val downloads = lib.downloads
            val artifact = downloads.artifact
            if (artifact != null) {
                val libPath = instanceFolder.div("libraries").div(artifact.path)
                val parentDir = libPath.parent
                if (parentDir != null && !fs.exists(parentDir)) fs.createDirectories(parentDir)
                val libBytes = client.get(artifact.url).readRawBytes()
                fs.write(libPath) { write(libBytes) }
            }

            val nativeKey = when {
                System.getProperty("os.name").contains("win", true) -> "natives-windows"
                System.getProperty("os.name").contains("mac", true) -> "natives-osx"
                else -> "natives-linux"
            }

            val nativeArtifact = downloads.classifiers?.get(nativeKey)
            if (nativeArtifact != null) {
                val nativeBytes = client.get(nativeArtifact.url).readRawBytes()
                java.util.zip.ZipInputStream(nativeBytes.inputStream()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && !entry.name.contains("META-INF")) {
                            val nativeFile = nativesFolder.div(entry.name.substringAfterLast("/"))
                            fs.write(nativeFile) { write(zip.readBytes()) }
                        }
                        entry = zip.nextEntry
                    }
                }
            }
        }

        val assetIndexResponse = client.get(versionPkg.assetIndex.url).bodyAsText()

        val indexesDir = instanceFolder.div("assets").div("indexes")
        if (!fs.exists(indexesDir)) fs.createDirectories(indexesDir)
        val indexFile = indexesDir.div("${versionPkg.assetIndex.id}.json")
        fs.write(indexFile) { writeUtf8(assetIndexResponse) }

        val assetManifest = json.decodeFromString<AssetManifest>(assetIndexResponse)
        val assetsDir = instanceFolder.div("assets").div("objects")
        val virtualDir = instanceFolder.div("assets").div("virtual").div("legacy")
        fs.createDirectories(virtualDir)

        println("--- MCinaBlackBox Resource Mapping ---")
        println("DO NOT PANIC IF IT LOOKS STUCK")

        assetManifest.objects.forEach { (name, obj) ->
            val hashPath = "${obj.hash.substring(0, 2)}/${obj.hash}"
            val assetFile = assetsDir.div(hashPath)
            if (!fs.exists(assetFile)) {
                val parent = assetFile.parent
                if (parent != null) fs.createDirectories(parent)
                val assetUrl = "https://resources.download.minecraft.net/$hashPath"
                val assetBytes = client.get(assetUrl).readRawBytes()
                fs.write(assetFile) { write(assetBytes) }
            }

            val virtualFile = virtualDir.div(name)
            val vParent = virtualFile.parent
            if (vParent != null && !fs.exists(vParent)) fs.createDirectories(vParent)
            if (!fs.exists(virtualFile)) fs.copy(assetFile, virtualFile)
        }

        fs.write(instanceFolder.div("instance_info.txt")) {
            writeUtf8("${versionPkg.mainClass}\n${versionPkg.assetIndex.id}\nPlayer\n2G")
        }

        println("Sync complete for $versionId.")
    } catch (e: Exception) {
        println("Error: ${e.message}")
    }
}

fun updateInstanceSettings(versionId: String, newUser: String? = null, newRam: String? = null) {
    val instancePath = vPath.toPath().div("instances").div(versionId)
    val infoFile = instancePath.div("instance_info.txt")
    if (!fs.exists(infoFile)) {
        println("Error: Instance $versionId not found.")
        return
    }
    val lines = fs.read(infoFile) { readUtf8() }.lines().toMutableList()
    while (lines.size < 4) lines.add("")
    if (newUser != null) lines[2] = newUser
    if (newRam != null) lines[3] = newRam
    fs.write(infoFile) { writeUtf8(lines.joinToString("\n")) }
    println("Settings updated for $versionId.")
}

fun launchGame(versionId: String) {
    val instancePath = vPath.toPath().div("instances").div(versionId)
    val gameJar = instancePath.div("$versionId.jar")
    val libFolder = instancePath.div("libraries")
    val nativesPath = instancePath.div("natives")
    val infoFile = instancePath.div("instance_info.txt")

    if (!fs.exists(gameJar)) {
        println("Error: Instance $versionId not found.")
        return
    }

    val infoLines = fs.read(infoFile) { readUtf8() }.lines()
    val mainClass = infoLines[0]
    val assetIndex = infoLines[1]
    val user = if (infoLines.size > 2) infoLines[2] else "Player"
    val ram = if (infoLines.size > 3) infoLines[3] else "2G"

    val libraryFiles = fs.listRecursively(libFolder)
        .filter { it.name.endsWith(".jar") }
        .map { it.toString() }
        .toMutableList()
    libraryFiles.add(gameJar.toString())

    val sep = if (System.getProperty("os.name").contains("win", ignoreCase = true)) ";" else ":"

    val command = listOf(
        "java",
        "-Xmx$ram",
        "-Djava.library.path=${nativesPath.toFile().absolutePath}",
        "-cp", libraryFiles.joinToString(sep),
        mainClass,
        "--username", user,
        "--version", versionId,
        "--gameDir", instancePath.toFile().absolutePath,
        "--assetsDir", instancePath.div("assets").toFile().absolutePath,
        "--assetIndex", assetIndex,
        "--uuid", "0",
        "--accessToken", "0",
        "--userType", "legacy"
    )

    try {
        println("Launching $versionId as $user with $ram RAM...")
        ProcessBuilder(command).directory(instancePath.toFile()).inheritIO().start()
    } catch (e: Exception) {
        println("Launch failed: ${e.message}")
    }
}

fun listInstances() {
    val instancesPath = vPath.toPath().div("instances")
    if (!fs.exists(instancesPath)) {
        println("No instances found.")
        return
    }
    val list = fs.list(instancesPath)
    if (list.isEmpty()) {
        println("The BlackBox is empty.")
    } else {
        println("--- Downloaded Instances ---")
        list.forEach { println(" > ${it.name}") }
    }
}

fun deleteInstance(versionId: String) {
    val instancePath = vPath.toPath().div("instances").div(versionId)
    if (fs.exists(instancePath)) {
        fs.deleteRecursively(instancePath)
        println("Instance $versionId deleted.")
    } else {
        println("Instance $versionId does not exist.")
    }
}

fun checkIfThisSessionIsTheUsersFirstTimeUsingTheApp() {
    val docsPath = "${System.getProperty("user.home")}/Documents".toPath()
    sessionFile = docsPath.div("MCVI-session.txt")

    if (fs.exists(sessionFile)) {
        vPath = fs.read(sessionFile) { readUtf8() }
        println("MCinaBlackBox Loaded | Path: $vPath")
    } else {
        println("Welcome to MCinaBlackBox.")
        print("Enter desired download path: ")
        vPath = readln()
        if (!fs.exists(vPath.toPath())) {
            fs.createDirectories(vPath.toPath())
        }
        fs.write(sessionFile) { writeUtf8(vPath) }
    }
}

fun printHelp() {
    println("--- MCinaBlackBox CLI Help ---")
    println("fetch               - Updates the version list from Mojang.")
    println("list                - Shows all downloaded Minecraft instances.")
    println("download <version>  - Downloads a specific version (e.g., download 1.12.2).")
    println("run <version>       - Launches a downloaded version.")
    println("delete <version>    - Deletes an instance folder.")
    println("setname <ver> <name>- Changes username for an instance.")
    println("setram <ver> <ram>  - Changes RAM for an instance (e.g., setram 1.12.2 4G).")
    println("help                - Shows this command list.")
    println("exit                - Closes the application.")
}

fun main() = runBlocking {
    checkIfThisSessionIsTheUsersFirstTimeUsingTheApp()

    while (true) {
        print("MCIBB>> ")
        val line = readln().trim()
        if (line.isEmpty()) continue

        when {
            line == "help" -> printHelp()
            line == "fetch" -> fetchMinecraftVersions()
            line == "list" -> listInstances()
            line.startsWith("download ") -> {
                val id = line.substringAfter("download ")
                val found = manifest?.versions?.find { it.id == id }
                if (found != null) downloadVersion(found.id, found.url)
                else println("Version $id not found. Run 'fetch' first.")
            }
            line.startsWith("run ") -> launchGame(line.substringAfter("run "))
            line.startsWith("delete ") -> deleteInstance(line.substringAfter("delete "))
            line.startsWith("setname ") -> {
                val parts = line.split(" ")
                if (parts.size == 3) updateInstanceSettings(parts[1], newUser = parts[2])
                else println("Usage: setname <version> <name>")
            }
            line.startsWith("setram ") -> {
                val parts = line.split(" ")
                if (parts.size == 3) updateInstanceSettings(parts[1], newRam = parts[2])
                else println("Usage: setram <version> <ram> (e.g. 4G)")
            }
            line == "exit" -> break
            else -> println("Unknown command: $line. Type 'help' for commands.")
        }
    }
}