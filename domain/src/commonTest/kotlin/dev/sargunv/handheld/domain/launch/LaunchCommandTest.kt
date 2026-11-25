package dev.sargunv.handheld.domain.launch

import kotlin.test.Test
import kotlin.test.assertEquals

class LaunchCommandTest {
  private fun viewing(vararg paths: String) =
    LaunchCommand(
      packageName = "dev.example.emu",
      action = IntentAction.VIEW,
      data = paths.firstOrNull()?.let { LaunchUri.ProviderFile(it) },
      extras = paths.drop(1).map { LaunchExtra.UriString("disc", LaunchUri.ProviderFile(it)) },
    )

  @Test
  fun readFoldersAreTheFoldersHoldingTheGrantedFiles() {
    val command =
      viewing(
        "/storage/emulated/0/ROMs/psx/Game (Disc 1).cue",
        "/storage/emulated/0/ROMs/psx/Game (Disc 2).cue",
        "/storage/1234-ABCD/ROMs/dc/Game.gdi",
      )
    assertEquals(
      listOf("/storage/emulated/0/ROMs/psx", "/storage/1234-ABCD/ROMs/dc"),
      command.readFolders,
    )
  }

  @Test
  fun readFoldersLeaveOutVolumeRootsAndAppFolders() {
    for (path in
      listOf(
        "/storage/emulated/0/game.cue",
        "/storage/1234-ABCD/game.cue",
        "/storage/emulated/0/Android/data/dev.example/files/game.cue",
        "/storage/1234-ABCD/android/game.cue",
        "/data/user/0/dev.example/files/game.cue",
      )) {
      assertEquals(emptyList(), viewing(path).readFolders, path)
    }
  }

  @Test
  fun readFoldersAreEmptyWithoutContentUris() {
    val command =
      LaunchCommand(
        packageName = "dev.example.emu",
        action = IntentAction.VIEW,
        data = LaunchUri.Raw("/storage/emulated/0/ROMs/psx/Game.cue"),
      )
    assertEquals(emptyList(), command.readFolders)
  }

  @Test
  fun shellCommandsWithContentUrisGoAsIntents() {
    val rom = LaunchUri.ProviderFile("/storage/emulated/0/ROMs/ps2/Game.chd")
    val path = LaunchUri.Raw("/storage/emulated/0/ROMs/ps2/Game.chd")
    val shell =
      LaunchCommand("dev.example.emu", action = IntentAction.VIEW, method = LaunchMethod.Shell)

    assertEquals(LaunchMethod.Shell, shell.copy(data = path).dispatchMethod)
    assertEquals(LaunchMethod.Intent, shell.copy(data = rom).dispatchMethod)
    assertEquals(
      LaunchMethod.Intent,
      shell.copy(data = path, method = LaunchMethod.Intent).dispatchMethod,
    )
  }
}
