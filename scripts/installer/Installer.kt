package installer

import java.awt.*
import java.io.File
import javax.swing.*

val NEW_FILES = listOf(
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

val MODIFIED_FILES = listOf(
    "forty-five.exe",
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

val BACKUP_DIR_NAME = "ap_mod_backup"
val DEFAULT_GAME_PATH = "C:\\Program Files (x86)\\Steam\\steamapps\\common\\Forty-Five"

fun main() {
    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
    SwingUtilities.invokeLater(::createAndShowUI)
}

fun createAndShowUI() {
    val frame = JFrame("Forty-Five AP Mod Installer")
    frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE

    val content = JPanel(GridBagLayout())
    content.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
    val gbc = GridBagConstraints().apply { insets = Insets(4, 4, 4, 4) }

    val pathField = JTextField(resolveDefaultGamePath(), 40)

    val browseButton = JButton("Browse...")
    browseButton.addActionListener {
        val chooser = JFileChooser(pathField.text).apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            pathField.text = chooser.selectedFile.canonicalPath
        }
    }

    val statusArea = JTextArea(10, 55).apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
    }

    val installButton = JButton("Install")

    // Layout row 0: label, path field, browse button
    gbc.gridx = 0; gbc.gridy = 0; gbc.anchor = GridBagConstraints.WEST; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
    content.add(JLabel("Game folder:"), gbc)
    gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
    content.add(pathField, gbc)
    gbc.gridx = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
    content.add(browseButton, gbc)

    // Layout row 1: status scroll pane
    gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 3; gbc.fill = GridBagConstraints.BOTH; gbc.weightx = 1.0; gbc.weighty = 1.0
    content.add(JScrollPane(statusArea), gbc)

    // Layout row 2: install button
    gbc.gridy = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0; gbc.weighty = 0.0; gbc.anchor = GridBagConstraints.CENTER
    content.add(installButton, gbc)

    installButton.addActionListener {
        val gameDir = File(pathField.text)
        if (!gameDir.exists() || !gameDir.isDirectory) {
            JOptionPane.showMessageDialog(frame, "Please select a valid game folder.", "Error", JOptionPane.ERROR_MESSAGE)
            return@addActionListener
        }
        installButton.isEnabled = false
        browseButton.isEnabled = false
        statusArea.text = ""
        Thread {
            try {
                install(gameDir) { msg -> SwingUtilities.invokeLater { statusArea.append("$msg\n") } }
                SwingUtilities.invokeLater {
                    statusArea.append("Installation complete!\n")
                    JOptionPane.showMessageDialog(frame, "Installation successful!", "Done", JOptionPane.INFORMATION_MESSAGE)
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    statusArea.append("ERROR: ${e.message}\n")
                    JOptionPane.showMessageDialog(frame, "Installation failed:\n${e.message}", "Error", JOptionPane.ERROR_MESSAGE)
                    installButton.isEnabled = true
                    browseButton.isEnabled = true
                }
            }
        }.start()
    }

    frame.add(content)
    frame.pack()
    frame.setLocationRelativeTo(null)
    frame.isVisible = true
}

fun resolveDefaultGamePath(): String {
    val parent = File(".").canonicalFile.parentFile
    return if (parent != null && File(parent, "forty-five.exe").exists()) parent.canonicalPath
    else DEFAULT_GAME_PATH
}

fun isGameRunning(): Boolean = ProcessHandle.allProcesses()
    .anyMatch { it.info().command().map { cmd -> cmd.endsWith("forty-five.exe", ignoreCase = true) }.orElse(false) }

fun install(gameDir: File, status: (String) -> Unit) {
    if (isGameRunning()) error("Forty-Five is currently running. Please close the game before installing.")

    val modFilesDir = File(".").canonicalFile.resolve("mod_files")
    if (!modFilesDir.exists()) error("mod_files directory not found next to installer")

    val backupDir = File(gameDir, BACKUP_DIR_NAME)
    if (backupDir.exists()) error("Backup folder already exists — mod may already be installed. Run uninstall.exe first.")
    backupDir.mkdirs()

    status("Backing up and replacing jre...")
    val vanillaJre = gameDir.resolve("jre")
    val jreBackup = backupDir.resolve("jre")
    if (vanillaJre.exists()) {
        vanillaJre.copyRecursively(jreBackup, overwrite = true)
        status("  [backup] jre/")
        vanillaJre.deleteRecursively()
    }
    modFilesDir.resolve("jre").copyRecursively(vanillaJre, overwrite = true)
    status("  [install] jre/")

    status("Backing up and replacing modified files...")
    for (path in MODIFIED_FILES) {
        val src = modFilesDir.resolve(path)
        val dst = gameDir.resolve(path)
        if (!src.exists()) { status("  [skip] missing mod file: $path"); continue }
        if (dst.exists()) {
            val backup = backupDir.resolve(path)
            backup.parentFile?.mkdirs()
            dst.copyTo(backup, overwrite = true)
            status("  [backup] $path")
        }
        dst.parentFile?.mkdirs()
        src.copyTo(dst, overwrite = true)
        status("  [install] $path")
    }

    status("Copying new mod files...")
    for (path in NEW_FILES) {
        val src = modFilesDir.resolve(path)
        val dst = gameDir.resolve(path)
        if (!src.exists()) { status("  [skip] missing mod file: $path"); continue }
        dst.parentFile?.mkdirs()
        src.copyTo(dst, overwrite = true)
        status("  [install] $path")
    }
}