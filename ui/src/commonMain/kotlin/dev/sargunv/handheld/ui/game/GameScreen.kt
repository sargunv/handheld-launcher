package dev.sargunv.handheld.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.composeunstyled.Text
import dev.sargunv.handheld.data.achievements.RommAchievementProgress
import dev.sargunv.handheld.data.achievements.achievementProgress
import dev.sargunv.handheld.data.auth.UploadAccess
import dev.sargunv.handheld.data.downloads.LocalGameState
import dev.sargunv.handheld.data.downloads.Removal
import dev.sargunv.handheld.data.downloads.RomStatus
import dev.sargunv.handheld.data.downloads.UploadResult
import dev.sargunv.handheld.data.downloads.formatSize
import dev.sargunv.handheld.data.downloads.uploadLocalGame
import dev.sargunv.handheld.data.romm.RommClient
import dev.sargunv.handheld.data.romm.RommException
import dev.sargunv.handheld.data.saves.KeptSave
import dev.sargunv.handheld.data.saves.SaveCopy
import dev.sargunv.handheld.data.saves.StillRunningChoice
import dev.sargunv.handheld.domain.GameKey
import dev.sargunv.handheld.domain.LibraryGame
import dev.sargunv.handheld.domain.LocalGame
import dev.sargunv.handheld.domain.Rom
import dev.sargunv.handheld.ui.Placeholder
import dev.sargunv.handheld.ui.cover.Cover
import dev.sargunv.handheld.ui.dialog.Dialog
import dev.sargunv.handheld.ui.focus.FocusScope
import dev.sargunv.handheld.ui.input.Action
import dev.sargunv.handheld.ui.input.Hint
import dev.sargunv.handheld.ui.input.HintBar
import dev.sargunv.handheld.ui.input.onAction
import dev.sargunv.handheld.ui.places.AppState
import dev.sargunv.handheld.ui.places.MenuButton
import dev.sargunv.handheld.ui.places.NOT_ON_SERVER
import dev.sargunv.handheld.ui.places.Screen
import dev.sargunv.handheld.ui.saves.SaveConflict
import dev.sargunv.handheld.ui.saves.SaveConflictScreen
import dev.sargunv.handheld.ui.saves.StillRunning
import dev.sargunv.handheld.ui.saves.StillRunningDialog
import dev.sargunv.handheld.ui.saves.ago
import dev.sargunv.handheld.ui.saves.size
import dev.sargunv.handheld.ui.signin.AllowUploadsDialog
import dev.sargunv.handheld.ui.signin.UPLOADS_NOT_FOR_ACCOUNT
import dev.sargunv.handheld.ui.signin.UPLOADS_NOT_REQUESTED
import kotlin.time.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.IOException

/** A save conflict waiting for the player's choice. */
private class PendingChoice(val conflict: SaveConflict) {
  val choice = CompletableDeferred<SaveCopy?>()
}

/** An emulator that may still hold the game, waiting for the player's choice. */
private class PendingStillRunning(val prompt: StillRunning) {
  val choice = CompletableDeferred<StillRunningChoice>()
}

/** What the Game screen knows of its game. */
private sealed interface ShownGame {
  data object Loading : ShownGame

  data class Found(val game: LibraryGame) : ShownGame

  /** A local game whose file is gone. */
  data object Gone : ShownGame
}

/**
 * The game [screen] shows: a ROM from the library, or a local game while its file matches nothing.
 * A local game that a scan matches to a ROM, such as after the server gained it, becomes that ROM's
 * screen, unless [playing] holds it.
 */
@Composable
private fun rememberShownGame(state: AppState, screen: Screen.Game, playing: Boolean): ShownGame {
  val key = screen.key
  // The ROM a local game's file matched, whose screen takes this one's place.
  var matched by remember(key) { mutableStateOf<Long?>(null) }
  val shown by
    produceState<ShownGame>(ShownGame.Loading, key) {
      when (key) {
        is GameKey.Romm -> state.library.rom(key.romId)?.let { value = ShownGame.Found(it) }
        is GameKey.Local ->
          state.downloads.localGame(key.path).collect {
            when (it) {
              is LocalGameState.Local -> {
                value = ShownGame.Found(it.game)
                matched = null
              }
              LocalGameState.Gone -> value = ShownGame.Gone
              // It shows as it was until its ROM's screen takes over.
              is LocalGameState.Matched -> matched = it.romId
              null -> Unit
            }
          }
      }
    }
  LaunchedEffect(matched, playing) {
    val romId = matched ?: return@LaunchedEffect
    if (playing) return@LaunchedEffect
    val rom = state.library.rom(romId) ?: return@LaunchedEffect
    state.navigator.replace(screen, Screen.Game(rom.id, rom.title))
  }
  return shown
}

/**
 * A game: one primary action, focused first, that plays the game when it's on the device and
 * downloads it when it isn't. Everything else is in the menu. When [Screen.Game.play] asks, it
 * plays the game as it opens, if the game is on the device. A local game is always on the device,
 * and isn't on the server, so its saves stay on the device.
 *
 * A launch runs while the screen shows, so while one is under way, leaving the screen asks first,
 * and the menu and the main menu wait.
 */
@Composable
internal fun GameScreen(state: AppState, screen: Screen.Game) {
  var playing by remember { mutableStateOf(false) }
  val shown = rememberShownGame(state, screen, playing)
  val game = (shown as? ShownGame.Found)?.game
  val status by
    remember(game) {
        when (game) {
          is Rom -> state.downloads.status(game)
          is LocalGame -> flowOf(RomStatus.OnDevice(game.path))
          null -> flowOf(null)
        }
      }
      .collectAsState(null)
  var menuOpen by rememberSaveable { mutableStateOf(false) }
  var confirmRemove by rememberSaveable { mutableStateOf(false) }
  var confirmUpload by rememberSaveable { mutableStateOf(false) }
  var confirmStopUpload by rememberSaveable { mutableStateOf(false) }
  // How an upload to RomM under way is going, in a few words, such as "Uploading 42%".
  var uploading by remember { mutableStateOf<String?>(null) }
  var upload by remember { mutableStateOf<Job?>(null) }
  // Whether the server has the whole file, so the upload can't stop any more.
  var uploadFinishing by remember { mutableStateOf(false) }
  // What the sign-in may do about uploads, as of the last Upload to RomM.
  var uploadAccess by remember { mutableStateOf<UploadAccess?>(null) }
  // Whether Upload to RomM is asking what the sign-in may do, while the menu waits for it.
  var checkingUpload by remember { mutableStateOf(false) }
  // Why signing in again, with uploads allowed, is offered, while the offer shows.
  var allowUploadsReason by remember { mutableStateOf<String?>(null) }
  // What the launch is doing before the game starts, such as getting its core.
  var playProgress by remember { mutableStateOf<String?>(null) }
  // What the player should know about the last thing they did here, such as how a play went.
  var notes by remember { mutableStateOf(emptyList<String>()) }
  var pending by remember { mutableStateOf<PendingChoice?>(null) }
  var stillRunning by remember { mutableStateOf<PendingStillRunning?>(null) }
  var confirmLeave by rememberSaveable { mutableStateOf(false) }
  // Whether the play the screen was opened for has started, so it starts only once.
  var autoPlayed by rememberSaveable { mutableStateOf(false) }
  val scope = rememberCoroutineScope()

  fun play(game: LibraryGame, path: String) {
    playing = true
    notes = emptyList()
    scope.launch {
      try {
        val collections = collectionsOf(state, game)
        val rules = state.settings.launch.toRules()
        notes =
          state.launcher.play(
            game,
            path,
            rules,
            collections,
            onProgress = { playProgress = it },
            closeEmulators = state.settings.closeEmulators && state.launcher.canCloseEmulators,
            askStillRunning = { prompt ->
              val choice = PendingStillRunning(prompt)
              stillRunning = choice
              try {
                choice.choice.await()
              } finally {
                stillRunning = null
              }
            },
          ) { conflict ->
            val choice = PendingChoice(conflict)
            pending = choice
            try {
              choice.choice.await()
            } finally {
              pending = null
            }
          }
      } finally {
        playing = false
        playProgress = null
        // The game's saves may have synced.
        state.savesChanged++
      }
    }
  }

  fun upload(game: LocalGame) {
    uploading = "Uploading"
    notes = emptyList()
    upload = scope.launch {
      try {
        val client =
          state.romm.client()
            ?: run {
              notes = listOf("Sign in to RomM to upload the game.")
              return@launch
            }
        val result = client.use {
          uploadLocalGame(
            it,
            game,
            onFinishing = {
              uploadFinishing = true
              uploading = "Adding to your library"
            },
          ) { sent, total ->
            if (sent < total) uploading = "Uploading ${sent * 100 / total}%"
          }
        }
        notes =
          listOf(
            when (result) {
              UploadResult.Added -> "Uploaded to RomM."
              UploadResult.AwaitingScan ->
                "Uploaded to RomM. It joins your library when RomM next scans it."
            },
          )
        // The library learns of the game, and this page becomes its page.
        state.syncNow()
      } catch (e: RommException) {
        notes = listOf(uploadError(e))
        // A sign-in whose permissions aren't known, or don't include uploading, may be why.
        if (e is RommException.Forbidden && uploadAccess != UploadAccess.Allowed) {
          allowUploadsReason = uploadRefusal(e)
        }
      } catch (e: IOException) {
        notes = listOf("Couldn't read the game's file: ${e.message}")
      } finally {
        uploading = null
        upload = null
        uploadFinishing = false
      }
    }
  }

  val current = game
  val currentStatus = status
  LaunchedEffect(current, currentStatus) {
    if (!screen.play || autoPlayed || current == null || currentStatus == null) {
      return@LaunchedEffect
    }
    autoPlayed = true
    // A game that isn't on the device shows its page, where it can download.
    if (currentStatus is RomStatus.OnDevice) play(current, currentStatus.path)
  }
  LaunchedEffect(playing) { if (!playing) confirmLeave = false }
  // Once the server has the whole file, the upload finishes, so there's nothing to stop.
  LaunchedEffect(uploading, uploadFinishing) {
    if (uploading == null || uploadFinishing) confirmStopUpload = false
  }
  // The system back gesture asks too, as Back does, or cancels a save choice, as Back does there.
  if (playing && LocalNavigationEventDispatcherOwner.current != null) {
    NavigationBackHandler(
      state = rememberNavigationEventState(NavigationEventInfo.None),
      onBackCompleted = {
        val choice = pending
        if (choice != null) choice.choice.complete(null) else confirmLeave = true
      },
    )
  }
  if (uploading != null && LocalNavigationEventDispatcherOwner.current != null) {
    NavigationBackHandler(
      state = rememberNavigationEventState(NavigationEventInfo.None),
      onBackCompleted = { if (!uploadFinishing) confirmStopUpload = true },
    )
  }
  val primary =
    uploading?.let { PrimaryAction.Waiting(it) }
      ?: current?.let { primaryAction(it, status, playing, playProgress) }
  val hasMenu =
    current != null && status != null && !playing && uploading == null && !checkingUpload
  FocusScope(
    Modifier.fillMaxSize().onAction { action ->
      when {
        action == Action.Menu && hasMenu -> menuOpen = true
        // Leaving would stop the launch, so it asks first.
        action == Action.Back && playing -> confirmLeave = true
        // Back stops an upload, once the player says so.
        action == Action.Back && uploading != null -> if (!uploadFinishing) confirmStopUpload = true
        // The main menu waits for the launch or the upload, as it would leave the screen.
        action == Action.MainMenu && (playing || uploading != null) -> Unit
        else -> return@onAction false
      }
      true
    },
  ) {
    val choice = pending
    if (choice != null) {
      SaveConflictScreen(
        choice.conflict,
        onChoose = { choice.choice.complete(it) },
        onCancel = { choice.choice.complete(null) },
      )
      return@FocusScope
    }
    Column(Modifier.fillMaxSize()) {
      Row(
        Modifier.fillMaxWidth().weight(1f).padding(48.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
      ) {
        Cover(
          (current as? Rom)?.let { it.coverLargeUrl ?: it.coverSmallUrl },
          screen.title,
          Modifier.size(240.dp, 320.dp),
          description = null,
        )
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
          Text(
            screen.title,
            color = Placeholder.text,
            fontSize = 28.sp,
            modifier = Modifier.semantics { heading() },
          )
          current?.let { GameFacts(state, it) }
          if (shown == ShownGame.Gone) {
            Text(
              "This game's file is no longer on the device.",
              color = Placeholder.mutedText,
              modifier = Modifier.testTag("note"),
            )
          }
          if (current != null && primary != null) {
            MenuButton("primary", primary.label, enabled = primary.isAction) {
              when (primary) {
                is PrimaryAction.Download ->
                  if (current is Rom) scope.launch { state.downloads.enqueue(current) }
                PrimaryAction.Retry ->
                  if (current is Rom) scope.launch { state.downloads.retry(current.id) }
                is PrimaryAction.Play -> play(current, primary.path)
                else -> Unit
              }
            }
          }
          val lines = statusLines(status)
          for ((index, line) in (lines + notes).withIndex()) {
            // A screen reader says how the last thing the player did here went, such as a play,
            // and a failed download, when they appear. Progress changes too often to announce.
            val announce = status is RomStatus.Failed || index >= lines.size
            Text(
              line,
              color = Placeholder.mutedText,
              modifier =
                Modifier.testTag("note").semantics {
                  if (announce) liveRegion = LiveRegionMode.Polite
                },
            )
          }
          (current as? Rom)?.summary?.let {
            Text(it, color = Placeholder.mutedText, maxLines = 3, overflow = TextOverflow.Ellipsis)
          }
        }
      }
      HintBar(
        buildList {
          if (primary?.isAction == true) add(Hint(Action.Confirm, primary.label))
          when {
            uploading == null -> add(Hint(Action.Back, "Back"))
            !uploadFinishing -> add(Hint(Action.Back, "Stop upload"))
          }
          if (hasMenu) add(Hint(Action.Menu, "Menu"))
          if (!playing && uploading == null) add(Hint(Action.MainMenu, "Main menu"))
        },
      )
    }
    if (menuOpen && current != null) {
      val close = { menuOpen = false }
      Dialog(close, Modifier.background(Placeholder.dialog).padding(24.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
          Text(current.title, color = Placeholder.text, modifier = Modifier.semantics { heading() })
          // Download and Retry are the primary action, so the menu doesn't repeat them.
          when (status) {
            RomStatus.Queued,
            is RomStatus.Downloading,
            is RomStatus.Failed ->
              if (current is Rom) {
                MenuButton("menu:cancel", "Cancel download") {
                  scope.launch { state.downloads.cancel(current.id) }
                  close()
                }
              }
            is RomStatus.OnDevice ->
              MenuButton("menu:remove", "Remove from device") {
                close()
                confirmRemove = true
              }
            RomStatus.NotOnDevice,
            null -> Unit
          }
          if (current is LocalGame) {
            MenuButton("menu:upload", "Upload to RomM") {
              close()
              notes = emptyList()
              checkingUpload = true
              scope.launch {
                val access =
                  try {
                    state.romm.uploadAccess()
                  } finally {
                    checkingUpload = false
                  }
                uploadAccess = access
                // A game that started meanwhile comes first.
                if (playing) return@launch
                when (access) {
                  null -> notes = listOf("Sign in to RomM to upload the game.")
                  UploadAccess.NotRequested -> allowUploadsReason = UPLOADS_NOT_REQUESTED
                  UploadAccess.AccountMayNot -> notes = listOf(UPLOADS_NOT_FOR_ACCOUNT)
                  UploadAccess.Allowed,
                  UploadAccess.Unknown -> confirmUpload = true
                }
              }
            }
          }
          MenuButton("menu:open-with", "Open with") {
            close()
            state.navigator.push(Screen.OpenWith(OpenWithScope.Game(current.key), current.title))
          }
        }
      }
    }
    if (confirmRemove && current != null) {
      val close = { confirmRemove = false }
      Dialog(
        close,
        Modifier.background(Placeholder.dialog).padding(24.dp),
        initialFocus = "remove:cancel",
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
          Text(
            "Remove ${current.title} from this device?",
            color = Placeholder.text,
            modifier = Modifier.semantics { heading() },
          )
          Text(
            when (current) {
              is Rom -> "Its saves stay, here and on RomM, and you can download it again."
              is LocalGame ->
                "It isn't on your server, so you can't download it again. Its saves stay on " +
                  "this device."
            },
            color = Placeholder.mutedText,
          )
          Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MenuButton("remove:cancel", "Cancel", onConfirm = close)
            MenuButton("remove:confirm", "Remove") {
              close()
              scope.launch {
                val removal =
                  when (current) {
                    is Rom -> state.downloads.remove(current)
                    is LocalGame -> state.downloads.remove(current)
                  }
                // A local game removed is gone, so its page has nothing left to show.
                if (current is LocalGame && removal == Removal.Removed) {
                  state.navigator.back()
                  return@launch
                }
                notes =
                  when (removal) {
                    is Removal.HoldsOtherFiles ->
                      listOf(
                        "The game's folder also holds ${removal.names.joinToString()}, " +
                          "so nothing was removed.",
                      )
                    is Removal.Failed -> listOf("Couldn't remove the game: ${removal.reason}")
                    Removal.Removed,
                    Removal.NotOnDevice -> emptyList()
                  }
              }
            }
          }
        }
      }
    }
    if (confirmUpload && current is LocalGame) {
      val close = { confirmUpload = false }
      Dialog(
        close,
        Modifier.background(Placeholder.dialog).padding(24.dp),
        initialFocus = "upload:cancel",
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
          Text(
            "Upload ${current.title} to RomM?",
            color = Placeholder.text,
            modifier = Modifier.semantics { heading() },
          )
          Text(
            "It goes into its platform's folder on your server as ${current.fileName} " +
              "(${formatSize(current.sizeBytes)}). Once RomM has it in your library, its saves " +
              "sync like any game's.",
            color = Placeholder.mutedText,
          )
          Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MenuButton("upload:cancel", "Cancel", onConfirm = close)
            MenuButton("upload:confirm", "Upload") {
              close()
              upload(current)
            }
          }
        }
      }
    }
    allowUploadsReason?.let { reason ->
      val close = { allowUploadsReason = null }
      AllowUploadsDialog(
        reason,
        onSignInAgain = {
          close()
          scope.launch { state.signOut(allowUploads = true, keepQueue = true) }
        },
        onDismissRequest = close,
      )
    }
    if (confirmStopUpload && uploading != null) {
      val keep = { confirmStopUpload = false }
      Dialog(
        keep,
        Modifier.background(Placeholder.dialog).padding(24.dp),
        initialFocus = "upload:keep",
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
          Text(
            "Stop uploading ${screen.title}?",
            color = Placeholder.text,
            modifier = Modifier.semantics { heading() },
          )
          Text("RomM drops what it received so far.", color = Placeholder.mutedText)
          Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MenuButton("upload:keep", "Keep uploading", onConfirm = keep)
            MenuButton("upload:stop", "Stop upload") {
              keep()
              // The server may have the whole file meanwhile, and then it finishes.
              if (!uploadFinishing) {
                upload?.cancel()
                notes = listOf("The upload stopped.")
              }
            }
          }
        }
      }
    }
    if (confirmLeave && playing) {
      LeaveDialog(
        screen.title,
        onStay = { confirmLeave = false },
        onLeave = {
          confirmLeave = false
          state.navigator.back()
        },
      )
    }
    stillRunning?.let { asking ->
      StillRunningDialog(asking.prompt, onChoose = { asking.choice.complete(it) })
    }
  }
}

/**
 * Asks whether to leave a game while its launch is under way, which stops the launch. Staying is
 * focused first.
 */
@Composable
private fun LeaveDialog(title: String, onStay: () -> Unit, onLeave: () -> Unit) {
  Dialog(
    onStay,
    Modifier.background(Placeholder.dialog).padding(24.dp),
    initialFocus = "leave:stay",
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
      Text(
        "Leave $title?",
        color = Placeholder.text,
        modifier = Modifier.semantics { heading() },
      )
      Text(
        "A game that hasn't started yet won't start. One already playing in another app keeps " +
          "running, but its save won't sync with RomM when you quit it.",
        color = Placeholder.mutedText,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        MenuButton("leave:stay", "Stay", onConfirm = onStay)
        MenuButton("leave:confirm", "Leave", onConfirm = onLeave)
      }
    }
  }
}

/** Why signing in again is offered after RomM refused an upload [e] for its permissions. */
internal fun uploadRefusal(e: RommException.Forbidden): String =
  "RomM didn't allow the upload${e.detail?.let { ": $it" } ?: ""}. This device's sign-in may " +
    "not have permission to upload games."

/** What the player reads when RomM refuses an upload, or can't be reached. */
internal fun uploadError(e: RommException): String =
  when (e) {
    is RommException.Forbidden ->
      "RomM didn't allow the upload${e.detail?.let { ": $it" } ?: ""}. Your account needs " +
        "permission to upload games."
    is RommException.Status -> "RomM refused the upload: ${e.detail ?: e.message}"
    else -> "Couldn't upload the game: ${e.message}"
  }

/**
 * The game's platform, release year and genres, on one line. A local game has its platform, its
 * size, and that it isn't on the server.
 */
@Composable
private fun GameFacts(state: AppState, game: LibraryGame) {
  val platforms by remember(state.library) { state.library.platforms() }.collectAsState(emptyList())
  val platform = platforms.find { it.id == game.platformId }?.name ?: game.platformSlug
  val facts =
    when (game) {
      // An ISO instant starts with its year.
      is Rom -> listOfNotNull(platform, game.releaseDate?.toString()?.take(4)) + game.genres.take(2)
      is LocalGame ->
        listOf(
          platform,
          formatSize(game.sizeBytes),
          NOT_ON_SERVER.replaceFirstChar { it.uppercase() },
        )
    }
  Text(facts.joinToString(" · "), color = Placeholder.mutedText)
}

/** The collections [game] is in, as launch rules name them. A local game is in none. */
private suspend fun collectionsOf(state: AppState, game: LibraryGame) =
  when (game) {
    is Rom -> state.library.collectionsOf(game.id).mapTo(mutableSetOf()) { it.launchId }
    is LocalGame -> emptySet()
  }

/** What the primary action does, by the game's [RomStatus]. */
internal sealed interface PrimaryAction {
  val label: String

  /** Whether pressing it does something now. */
  val isAction: Boolean
    get() = true

  data class Download(val sizeBytes: Long) : PrimaryAction {
    override val label = "Download (${formatSize(sizeBytes)})"
  }

  data object Retry : PrimaryAction {
    override val label = "Retry download"
  }

  data class Play(val path: String) : PrimaryAction {
    override val label = "Play"
  }

  /** The game is on its way or running, so the action waits. */
  data class Waiting(override val label: String) : PrimaryAction {
    override val isAction = false
  }
}

/** The primary action for [game], with [progress] saying what a launch underway is doing. */
internal fun primaryAction(
  game: LibraryGame,
  status: RomStatus?,
  playing: Boolean,
  progress: String? = null,
): PrimaryAction? =
  when (status) {
    null -> null
    RomStatus.NotOnDevice -> PrimaryAction.Download(game.sizeBytes)
    RomStatus.Queued -> PrimaryAction.Waiting("Queued to download")
    is RomStatus.Downloading -> {
      val percent =
        if (status.totalBytes > 0) " ${status.bytesDownloaded * 100 / status.totalBytes}%" else ""
      PrimaryAction.Waiting("Downloading$percent")
    }
    is RomStatus.Failed -> PrimaryAction.Retry
    is RomStatus.OnDevice ->
      if (playing) PrimaryAction.Waiting(progress ?: "Playing") else PrimaryAction.Play(status.path)
  }

/** The lines under the primary action that say how a download is going. */
private fun statusLines(status: RomStatus?): List<String> =
  when (status) {
    is RomStatus.Downloading ->
      listOf("${formatSize(status.bytesDownloaded)} of ${formatSize(status.totalBytes)}")
    is RomStatus.Failed -> listOf("The download failed: ${status.error}")
    else -> emptyList()
  }

/** What the lower screen's save history holds. */
private sealed interface SaveHistoryState {
  data object Loading : SaveHistoryState

  data class Loaded(val saves: List<ServerSave>) : SaveHistoryState

  data class Failed(val reason: String) : SaveHistoryState
}

/**
 * The lower screen for a game: its saves on RomM across devices, the copies save sync kept on this
 * device, what the copy on this device says about itself in its header, and where it opens.
 */
@Composable
internal fun GameSecondary(state: AppState, screen: Screen.Game) {
  val game by produceState<LibraryGame?>(null, screen.key) { value = state.game(screen.key) }
  when (val current = game) {
    is Rom -> RomSecondary(state, current)
    is LocalGame -> LocalSecondary(state, current)
    null -> Unit
  }
}

/** Where [game] opens under the launch settings, in words. */
@Composable
private fun rememberOpensIn(state: AppState, game: LibraryGame): String? {
  val launch = state.settings.launch
  val opensIn by
    produceState<String?>(null, game, launch) {
      value = state.launcher.opensIn(game, launch.toRules(), collectionsOf(state, game))
    }
  return opensIn
}

/**
 * The lower screen for a local game: it isn't on the server, so its saves stay on the device, and
 * where it opens.
 */
@Composable
private fun LocalSecondary(state: AppState, game: LocalGame) {
  val opensIn = rememberOpensIn(state, game)
  Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(
      NOT_ON_SERVER.replaceFirstChar { it.uppercase() },
      color = Placeholder.text,
      fontSize = 20.sp,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier.testTag("game:not-on-server").semantics { heading() },
    )
    Text(
      "Saves stay on this device until the game is on your server.",
      color = Placeholder.mutedText,
      modifier = Modifier.testTag("game:saves-local"),
    )
    Text(game.path, color = Placeholder.mutedText, fontSize = 14.sp)
    Spacer(Modifier.weight(1f))
    opensIn?.let { Text(it, color = Placeholder.mutedText) }
  }
}

/**
 * The lower screen for a ROM: its saves on RomM across devices, the copies save sync kept on this
 * device, and where it opens.
 */
@Composable
private fun RomSecondary(state: AppState, current: Rom) {
  val history by
    produceState<SaveHistoryState>(SaveHistoryState.Loading, current, state.savesChanged) {
      value = loadSaveHistory(state, current.id)
    }
  val kept by
    produceState(emptyList<KeptSave>(), current, state.savesChanged) {
      value = state.launcher.keptSaves(current)
    }
  val opensIn = rememberOpensIn(state, current)
  val achievements by
    produceState<RommAchievementProgress?>(null, current) {
      value = loadAchievementProgress(state, current.id)
    }
  val status by remember(current) { state.downloads.status(current) }.collectAsState(null)
  val path = (status as? RomStatus.OnDevice)?.path
  // A header that can't be read shows nothing.
  val header by
    produceState(emptyList<RomHeaderField>(), current, path) {
      value = path?.let { state.romHeaders.read(current, it) }.orEmpty()
    }
  val now = remember(history) { Clock.System.now() }
  Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    achievements?.let {
      Text(
        achievementSummary(it),
        color = Placeholder.mutedText,
        modifier = Modifier.testTag("game:achievements"),
      )
    }
    Column(
      Modifier.weight(1f).verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        "Saves",
        color = Placeholder.text,
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.semantics { heading() },
      )
      when (val shown = history) {
        SaveHistoryState.Loading -> Text("Loading saves from RomM", color = Placeholder.mutedText)
        is SaveHistoryState.Failed -> Text(shown.reason, color = Placeholder.mutedText)
        is SaveHistoryState.Loaded ->
          if (shown.saves.isEmpty()) {
            Text("No saves on RomM yet.", color = Placeholder.mutedText)
          } else {
            for (save in shown.saves) {
              val details =
                listOfNotNull(
                  save.emulator.takeIf { save.device != null },
                  ago(save.changedAt, now),
                  size(save.sizeBytes),
                )
              SaveRow(save.device ?: save.emulator ?: "Unknown device", details)
            }
          }
      }
      if (kept.isNotEmpty()) {
        Spacer(Modifier.size(8.dp))
        Text(
          "Kept on this device",
          color = Placeholder.text,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.semantics { heading() },
        )
        for (save in kept) {
          val from = if (save.copy == SaveCopy.Device) "This device's copy" else "RomM's copy"
          SaveRow(from, listOf(save.emulator, ago(save.keptAt, now), size(save.sizeBytes)))
        }
      }
      if (header.isNotEmpty()) {
        Spacer(Modifier.size(8.dp))
        ThisCopy(header)
      }
    }
    opensIn?.let { Text(it, color = Placeholder.mutedText) }
  }
}

/** What the copy of the game on this device says about itself in its header. */
@Composable
private fun ThisCopy(header: List<RomHeaderField>) {
  Column(
    Modifier.testTag("game:header").semantics(mergeDescendants = true) {},
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Text(
      "This copy",
      color = Placeholder.text,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier.semantics { heading() },
    )
    Text(header.joinToString(" · ") { "${it.label} ${it.value}" }, color = Placeholder.mutedText)
  }
}

@Composable
private fun SaveRow(source: String, details: List<String>) {
  Row(
    Modifier.semantics(mergeDescendants = true) {},
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(source, color = Placeholder.text)
    Text(details.joinToString(" · "), color = Placeholder.mutedText)
  }
}

/** The player's RetroAchievements progress in the game, as RomM syncs it, in words. */
internal fun achievementSummary(progress: RommAchievementProgress): String {
  val hardcore =
    if (progress.unlockedHardcore > 0) ", ${progress.unlockedHardcore} in hardcore" else ""
  return "RetroAchievements: ${progress.unlocked} of ${progress.total} unlocked$hardcore"
}

/**
 * The player's RetroAchievements progress in ROM [romId], read from RomM, or null when there's none
 * to show or RomM is out of reach. It only reads.
 */
private suspend fun loadAchievementProgress(
  state: AppState,
  romId: Long,
): RommAchievementProgress? {
  val credentials = state.credentials.load() ?: return null
  return try {
    withContext(Dispatchers.Default) {
      RommClient(credentials.baseUrl, credentials.token, state.httpEngine()).use {
        it.achievementProgress(romId)
      }
    }
  } catch (_: RommException) {
    null
  }
}

private suspend fun loadSaveHistory(state: AppState, romId: Long): SaveHistoryState {
  val credentials =
    state.credentials.load() ?: return SaveHistoryState.Failed("Sign in to see saves on RomM.")
  return try {
    withContext(Dispatchers.Default) {
      RommClient(credentials.baseUrl, credentials.token, state.httpEngine()).use {
        SaveHistoryState.Loaded(serverSaveHistory(it.saves(romId = romId), credentials.deviceId))
      }
    }
  } catch (e: RommException) {
    SaveHistoryState.Failed("Couldn't reach RomM for the game's saves: ${e.message}")
  }
}
