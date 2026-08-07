import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.random.Random
import kotlin.system.exitProcess

val MOD_NEW_FILES = listOf(
    "config/archipelago_flavortext.onj",
    "saves/APCache/default_perma_savefile.onj",
    "saves/APCache/perma_savefile.onj",
    "saves/APCache/savefile.onj",
    "saves/APCache/user_prefs.onj",
    "screens/ap_health_check_screen.onj",
    "textures/notification_background.png",
    "textures/cards/apItemBullet.png",
    "textures/map/nodes/exit_locked.png",
)

val MOD_MODIFIED_FILES = listOf(
    "config/assets.onj",
    "logging/log_config.onj",
    "config/cards.onj",
    "config/descriptions.onj",
    "config/encounter_definitions.onj",
    "imports/map_events.onjschema",
    "maps/map_config.onj",
    "maps/static_roads/tutorial_road.onj",
    "onjschemas/encounter_definitions.onjschema",
    "onjschemas/map_config.onjschema",
    "onjschemas/perma_savefile.onjschema",
    "onjschemas/screen.onjschema",
    "screens/map_screen.onj",
    "screens/shared_components.onj",
    "screens/title_screen.onj",
)

build()

fun build() {
    if (!isInCorrectDirectory()) {
        error("The build script should be executed in the root of the project\nCurrent dir: ${File(".").canonicalPath}")
    }
    val (tag, dirName, tmpDir) = interviewUser()
    val modFilesDir = tmpDir / "mod_files"

    debug("running gradle dist task")
    command("./gradlew.bat", "dist")

    tmpDir.mkdirs()
    modFilesDir.mkdirs()

    copyModAssets(modFilesDir)
    changeLoggingVersionTag(modFilesDir, tag)
    buildModExe(modFilesDir)
    buildInstallerExes(tmpDir)

    debug("compressing build")
    shellCommand(tmpDir, "7z", "a", "$dirName.zip", "./*")
    debug("copying archive")
    File("build/script").mkdirs()
    (tmpDir / "$dirName.zip").copyTo(File("build/script/$dirName.zip"))
    debug("removing tmp directory")
    tmpDir.deleteRecursively()
    println("[34mBuild successful. Finished build can be found at: build/script/$dirName.zip[0m")
}

fun interviewUser(): Triple<String, String, File> {
    val isReleaseBuild = askYesNoQuestion("Is this a release build?", default = false)
    val letter = if (isReleaseBuild) "rc" else ask("What build string do you want to use?", default = "b")
    val date = SimpleDateFormat("yyMMdd").format(Date())
    val tag = "$letter$date"
    val dirName = "$tag-apmod"
    return Triple(tag, dirName, File("build/script/$dirName"))
}

fun copyModAssets(modFilesDir: File) {
    debug("copying new mod files")
    for (path in MOD_NEW_FILES) {
        val src = File("assets") / path
        val dst = modFilesDir / path
        dst.parentFile?.mkdirs()
        src.copyTo(dst)
    }
    debug("copying modified mod files")
    for (path in MOD_MODIFIED_FILES) {
        val src = File("assets") / path
        val dst = modFilesDir / path
        dst.parentFile?.mkdirs()
        src.copyTo(dst)
    }
    debug("copying mod jre")
    File("assets/large_assets/jre").copyRecursively(modFilesDir / "jre")
}

fun changeLoggingVersionTag(modFilesDir: File, newTag: String) {
    debug("changing version tag in log_config.onj")
    val logConfig = (modFilesDir / "logging/log_config.onj").readText()
    val newConfig = logConfig.replace("--dev--", newTag)
    (modFilesDir / "logging/log_config.onj").writeText(newConfig)
}

fun buildModExe(modFilesDir: File) {
    debug("copying jar file")
    File("desktop/build/libs/desktop.jar").copyTo(modFilesDir / "forty-five.jar")

    debug("cleaning up jar file")
    shellCommand(modFilesDir, "7z", "d", "forty-five.jar",
        "config", "dialog", "fonts", "imports", "large_assets", "logging", "maps", "onjschemas", "saves", "screens", "shaders", "textures")

    // launch4j needs the icon at ./textures/icon.ico relative to its working dir
    val iconSrc = File("assets/textures/icon.ico")
    val iconDst = modFilesDir / "textures/icon.ico"
    val borrowedIcon = iconSrc.exists() && !iconDst.exists()
    if (borrowedIcon) {
        iconDst.parentFile?.mkdirs()
        iconSrc.copyTo(iconDst)
    }

    debug("wrapping mod jar as exe")
    File("scripts/launch4j_config.xml").copyTo(modFilesDir / "launch4j_config.xml")
    shellCommand(modFilesDir, "launch4jc", "launch4j_config.xml")
    (modFilesDir / "launch4j_config.xml").delete()
    (modFilesDir / "forty-five.jar").delete()

    if (borrowedIcon) iconDst.delete()
}

fun buildInstallerExes(tmpDir: File) {
    debug("compiling Installer.kt")
    command("./gradlew.bat", ":installer:installerJar")
    val installerJar = File("build/script/installer.jar")
    installerJar.copyTo(tmpDir / "installer.jar")
    installerJar.delete()

    debug("wrapping installer as exe")
    File("scripts/installer_launch4j_config.xml").copyTo(tmpDir / "installer_launch4j_config.xml")
    File("scripts/installer.manifest").copyTo(tmpDir / "installer.manifest")
    shellCommand(tmpDir, "launch4jc", "installer_launch4j_config.xml")
    (tmpDir / "installer_launch4j_config.xml").delete()
    (tmpDir / "installer.manifest").delete()
    (tmpDir / "installer.jar").delete()

    debug("compiling Uninstaller.kt")
    command("./gradlew.bat", ":installer:uninstallerJar")
    val uninstallerJar = File("build/script/uninstaller.jar")
    uninstallerJar.copyTo(tmpDir / "uninstaller.jar")
    uninstallerJar.delete()

    debug("wrapping uninstaller as exe")
    File("scripts/uninstaller_launch4j_config.xml").copyTo(tmpDir / "uninstaller_launch4j_config.xml")
    File("scripts/installer.manifest").copyTo(tmpDir / "installer.manifest")
    shellCommand(tmpDir, "launch4jc", "uninstaller_launch4j_config.xml")
    (tmpDir / "uninstaller_launch4j_config.xml").delete()
    (tmpDir / "installer.manifest").delete()
    (tmpDir / "uninstaller.jar").delete()
}

fun isInCorrectDirectory(): Boolean =
    File("LICENSE").exists() &&
    File("readme.md").exists() &&
    File("build.gradle").exists()

fun shellCommand(vararg command: String) = command("powershell.exe", *command)

fun shellCommand(workingDir: File, vararg command: String) = command(workingDir, "powershell.exe", *command)

fun command(vararg command: String) = command(File("."), *command)

fun command(workingDir: File, vararg command: String) {
    val process = ProcessBuilder(*command)
        .directory(workingDir)
        .redirectOutput(ProcessBuilder.Redirect.INHERIT)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start()
    process.waitFor()
    if (process.exitValue() != 0) {
        error("command '${command[0]}' exited with code ${process.exitValue()}")
    }
}

fun error(message: String): Nothing {
    System.err.println(message)
    exitProcess(1)
}

fun debug(message: String) = println(message)

fun ask(question: String, default: String): String {
    println("$question [$default]")
    print("> ")
    val answer = readln()
    return answer.ifBlank { default }
}

fun askYesNoQuestion(question: String, default: Boolean): Boolean {
    println("$question y/n [${if (default) "y" else "n"}]")
    print("> ")
    val answer = readln()
    if (answer.isBlank()) return default
    return when (answer.trim().lowercase()) {
        "yes", "y", "j", "ja", "jo", "sure", "why not", "yay" -> true
        "no", "n", "nein", "na", "nah", "i dont think so" -> false
        "idk" -> Random.nextBoolean()
        else -> {
            println("answer 'y' or 'n'")
            askYesNoQuestion(question, default)
        }
    }
}

operator fun File.div(childPath: String): File = File(this.canonicalPath + "/$childPath")