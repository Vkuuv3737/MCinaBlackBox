package org.mcinablackbox

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.Path
import kotlinx.serialization.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import io.ktor.client.plugins.*

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

val client = HttpClient(CIO) {
    install(HttpTimeout) {
        requestTimeoutMillis = 30000
        connectTimeoutMillis = 15000
        socketTimeoutMillis = 15000
    }
}
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

        println("Downloading: Minecraft Client JAR ($versionId)")
        val jarUrl = versionPkg.downloads.client.url
        val targetFile = instanceFolder.div("$versionId.jar")
        val jarBytes = client.get(jarUrl).readRawBytes()
        fs.write(targetFile) { write(jarBytes) }

        versionPkg.libraries.forEach { lib ->
            val downloads = lib.downloads
            val artifact = downloads.artifact
            if (artifact != null) {
                println("Downloading Library: ${lib.name}")
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
                println("Extracting Natives: ${lib.name}")
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

        println("Downloading: Asset Index (${versionPkg.assetIndex.id})")
        val assetIndexResponse = client.get(versionPkg.assetIndex.url).bodyAsText()

        val indexesDir = instanceFolder.div("assets").div("indexes")
        if (!fs.exists(indexesDir)) fs.createDirectories(indexesDir)
        val indexFile = indexesDir.div("${versionPkg.assetIndex.id}.json")
        fs.write(indexFile) { writeUtf8(assetIndexResponse) }

        val assetManifest = json.decodeFromString<AssetManifest>(assetIndexResponse)
        val assetsDir = instanceFolder.div("assets").div("objects")
        val virtualDir = instanceFolder.div("assets").div("virtual").div("legacy")
        fs.createDirectories(virtualDir)

        println("--- Syncing Assets & Sound Files ---")
        assetManifest.objects.forEach { (name, obj) ->
            val hashPath = "${obj.hash.substring(0, 2)}/${obj.hash}"
            val assetFile = assetsDir.div(hashPath)

            if (!fs.exists(assetFile)) {
                println("Downloading Asset: $name")
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
        println("Error during download: ${e.message}")
    }
}

suspend fun mkFabricInstance(versionId: String) {
    try {
        val loaderMetaUrl = "https://meta.fabricmc.net/v2/versions/loader/$versionId"
        val loaderResponse = client.get(loaderMetaUrl).bodyAsText()
        val metaArray = json.parseToJsonElement(loaderResponse).jsonArray

        if (metaArray.isEmpty()) {
            println("Error: No Fabric loader found for version $versionId.")
            return
        }

        val firstEntry = metaArray[0].jsonObject
        val loaderVer = firstEntry["loader"]!!.jsonObject["version"]!!.jsonPrimitive.content
        val intermediaryVer = firstEntry["intermediary"]!!.jsonObject["version"]!!.jsonPrimitive.content

        val instancesDir = vPath.toPath().div("instances")
        var count = 1
        var newFolderName = "$versionId-fabric$count"
        while (fs.exists(instancesDir.div(newFolderName))) {
            count++
            newFolderName = "$versionId-fabric$count"
        }

        val newPath = instancesDir.div(newFolderName)
        val vanillaPath = instancesDir.div(versionId)

        if (!fs.exists(vanillaPath)) {
            println("Error: Vanilla version $versionId must be downloaded first.")
            return
        }

        fs.createDirectories(newPath)
        fs.copy(vanillaPath.div("$versionId.jar"), newPath.div("$versionId.jar"))

        val libsFolder = newPath.div("libraries")
        fs.createDirectories(libsFolder)

        val loaderJarUrl = "https://maven.fabricmc.net/net/fabricmc/fabric-loader/$loaderVer/fabric-loader-$loaderVer.jar"
        val interJarUrl = "https://maven.fabricmc.net/net/fabricmc/intermediary/$intermediaryVer/intermediary-$intermediaryVer.jar"

        listOf(loaderJarUrl to "fabric-loader.jar", interJarUrl to "intermediary.jar").forEach { (url, name) ->
            println("Downloading Fabric Component: $name")
            val bytes = client.get(url).readRawBytes()
            fs.write(libsFolder.div(name)) { write(bytes) }
        }

        fs.write(newPath.div("instance_info.txt")) {
            writeUtf8("net.fabricmc.loader.impl.launch.knot.KnotClient\n$versionId\nPlayer\n4G")
        }

        println("Instance $newFolderName created and patched.")
    } catch (e: Exception) {
        println("Fabric instance creation failed: ${e.message}")
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

fun launchGame(instanceId: String) {
    val instancePath = vPath.toPath().div("instances").div(instanceId)
    val infoFile = instancePath.div("instance_info.txt")

    if (!fs.exists(infoFile)) {
        println("Error: Instance $instanceId not found.")
        return
    }

    val infoLines = fs.read(infoFile) { readUtf8() }.lines()
    val mainClass = infoLines[0]
    val versionId = infoLines[1]
    val user = if (infoLines.size > 2) infoLines[2] else "Player"
    val ram = if (infoLines.size > 3) infoLines[3] else "2G"

    val currentJava = System.getProperty("java.version")
    val javaMajor = if (currentJava.startsWith("1.")) currentJava.split(".")[1].toInt() else currentJava.split(".")[0].split("-")[0].toInt()

    val isLegacy = versionId.contains("1.8") || versionId.contains("1.12")

    if (isLegacy && javaMajor != 8) {
        println("Error: Legacy version $versionId requires Java 8. Current: Java $javaMajor.")
        return
    }

    if (!isLegacy && javaMajor < 17) {
        println("Error: Modern versions require Java 17+. Current: Java $javaMajor.")
        return
    }

    val gameJar = fs.list(instancePath).find { it.name.endsWith(".jar") }
    val libFolder = instancePath.div("libraries")
    val nativesPath = instancePath.div("natives")

    val libraryFiles = fs.listRecursively(libFolder)
        .filter { it.name.endsWith(".jar") }
        .map { it.toString() }
        .toMutableList()

    if (gameJar != null) libraryFiles.add(gameJar.toString())

    val sep = if (System.getProperty("os.name").contains("win", ignoreCase = true)) ";" else ":"

    val command = listOf(
        "java",
        "-Xmx$ram",
        "--add-opens", "java.base/java.io=ALL-UNNAMED",
        "--add-opens", "java.base/java.util=ALL-UNNAMED",
        "-Djava.library.path=${nativesPath.toFile().absolutePath}",
        "-cp", libraryFiles.joinToString(sep),
        mainClass,
        "--username", user,
        "--version", versionId,
        "--gameDir", instancePath.toFile().absolutePath,
        "--assetsDir", instancePath.div("assets").toFile().absolutePath,
        "--assetIndex", versionId.substringBeforeLast("."),
        "--uuid", "0",
        "--accessToken", "0",
        "--userType", "legacy"
    )

    try {
        println("Launching $instanceId...")
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
    println("download <version>  - Downloads a specific version.")
    println("mkFabricInstance <v>- Creates a new Fabric instance from vanilla.")
    println("run <instance>      - Launches a downloaded instance.")
    println("delete <instance>   - Deletes an instance folder.")
    println("setname <ver> <name>- Changes username for an instance.")
    println("setram <ver> <ram>  - Changes RAM for an instance.")
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
            line.startsWith("mkFabricInstance ") -> mkFabricInstance(line.substringAfter("mkFabricInstance "))
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
                else println("Usage: setram <version> <ram>")
            }
            line == "exit" -> break
            else -> println("Unknown command: $line. Type 'help' for commands.")
        }
    }
}