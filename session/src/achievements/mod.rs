//! RetroAchievements, through rcheevos' `rc_client`.
//!
//! [`Achievements`] signs in with the player's token, identifies the game by
//! hashing its content the way RetroAchievements does for its console, loads
//! the game's achievements and checks them against the core's memory after
//! every frame. The server calls go out through an [`HttpClient`], and their
//! responses come back through [`Achievements::respond`], all on the session's
//! thread, which is the only one that touches the client.
//!
//! `vendor/rcheevos` holds the pinned library, which `build.rs` compiles, and
//! `ffi.rs` the bindings that `mise run session:rcheevos-bindings` generates.

#[allow(
    dead_code,
    non_camel_case_types,
    non_snake_case,
    non_upper_case_globals,
    unreachable_pub,
    unsafe_op_in_unsafe_fn,
    clippy::all
)]
mod ffi;

use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::ffi::{CStr, CString, c_char, c_int, c_uint, c_void};
use std::path::Path;
use std::ptr::NonNull;

use crate::ffi::{retro_memory_descriptor, retro_memory_map};

/// How a session takes part in RetroAchievements.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct AchievementOptions {
    /// The player's RetroAchievements username.
    pub username: String,
    /// The token the server issued when the player signed in. It stands in
    /// for their password, so it's never logged.
    pub token: String,
    /// RetroAchievements' id for the game's console, which picks how the
    /// content is hashed and how its memory is laid out.
    pub console_id: u32,
    /// Whether unlocks count as hardcore. While a game with achievements
    /// runs in hardcore, the session refuses to load states and to fast
    /// forward, as RetroAchievements requires.
    pub hardcore: bool,
}

/// An achievement of the running game.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct Achievement {
    pub id: u32,
    pub title: String,
    pub description: String,
    pub points: u32,
    /// Whether the player has earned it, in either mode.
    pub unlocked: bool,
    /// How far the player has got, such as `3/10`, for an achievement that
    /// measures it, while it's locked.
    pub progress: Option<String>,
    /// The same progress from 0 to 100, or 0 for none.
    pub progress_percent: f32,
}

/// Where the running game's achievements stand.
#[derive(Clone, Debug, PartialEq, uniffi::Enum)]
pub enum AchievementStatus {
    /// The session doesn't take part in RetroAchievements.
    Off,
    /// Signing in or identifying the game.
    Loading,
    /// RetroAchievements doesn't know the game, or has no achievements for
    /// it.
    NoSet,
    /// Signing in or loading the game failed, for `reason`, in plain words.
    Failed { reason: String },
    /// The game's achievements, as RetroAchievements groups them: recently
    /// unlocked and nearly done first, then locked, then unlocked.
    Ready {
        game: String,
        /// Whether the session plays in hardcore.
        hardcore: bool,
        achievements: Vec<Achievement>,
    },
}

/// What a session's achievements tell the app as the game runs.
#[derive(Clone, Debug, PartialEq, uniffi::Enum)]
pub enum AchievementEvent {
    /// The player earned `achievement`. The session has sent it to the
    /// server, which may still be taking it.
    Unlocked { achievement: Achievement },
    /// Signing in or loading the game failed, for `reason`, in plain words.
    Failed { reason: String },
}

/// A request the session needs sent to RetroAchievements.
#[derive(Clone, PartialEq, Eq, uniffi::Record)]
pub struct AchievementsRequest {
    pub url: String,
    /// The form body to POST, or `None` to GET. It carries the player's
    /// token, so it's never logged.
    pub post_data: Option<String>,
    pub content_type: Option<String>,
}

impl std::fmt::Debug for AchievementsRequest {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("AchievementsRequest")
            .field("url", &self.url)
            .field("post_data", &self.post_data.as_ref().map(|_| "<redacted>"))
            .field("content_type", &self.content_type)
            .finish()
    }
}

/// Sends the session's requests to RetroAchievements. Each request has an id,
/// and the app hands its response to `GameSession::achievements_response`
/// with that id once it has one.
pub trait HttpClient {
    fn request(&self, id: u64, request: AchievementsRequest);
}

/// The text of a client callback's error, or of its result code.
///
/// # Safety
///
/// `message` is null or a valid C string.
unsafe fn error_text(result: c_int, message: *const c_char) -> String {
    // SAFETY: forwarded from the caller; rc_error_str returns a static string.
    unsafe {
        let message = if message.is_null() {
            ffi::rc_error_str(result)
        } else {
            message
        };
        CStr::from_ptr(message).to_string_lossy().into_owned()
    }
}

/// How loading the game is going.
#[derive(Clone, Debug, PartialEq)]
enum Load {
    Loading,
    Loaded,
    NoSet,
    Failed(String),
}

/// A request waiting for its response.
struct Pending {
    callback: ffi::rc_client_server_callback_t,
    data: *mut c_void,
}

/// What the client's callbacks reach, through its userdata.
struct State {
    http: Box<dyn HttpClient>,
    pending: RefCell<HashMap<u64, Pending>>,
    next_id: Cell<u64>,
    load: RefCell<Load>,
    events: RefCell<Vec<AchievementEvent>>,
    memory: RefCell<Memory>,
}

/// The core's memory, laid out as RetroAchievements addresses it.
struct Memory {
    regions: ffi::rc_libretro_memory_regions_t,
    console_id: u32,
    /// Whether the layout has a region of the core's memory in it, rather
    /// than only filler.
    ready: bool,
}

impl Memory {
    fn read(&self, address: u32, buffer: *mut u8, num_bytes: u32) -> u32 {
        // SAFETY: rc_client passes a buffer of `num_bytes`, and the regions
        // point into the core's memory, which lives while the game is loaded.
        unsafe { ffi::rc_libretro_memory_read(&self.regions, address, buffer, num_bytes) }
    }
}

impl Drop for Memory {
    fn drop(&mut self) {
        // SAFETY: the regions came from rc_libretro_memory_init, or are zeroed.
        unsafe { ffi::rc_libretro_memory_destroy(&mut self.regions) };
    }
}

/// The core's memory, as `rc_libretro` reads it while it lays it out.
#[derive(Clone, Copy)]
pub(crate) struct CoreMemory {
    pub(crate) data: unsafe extern "C" fn(c_uint) -> *mut c_void,
    pub(crate) size: unsafe extern "C" fn(c_uint) -> usize,
}

thread_local! {
    /// The core whose memory `rc_libretro_memory_init` is laying out, which
    /// its callback has no other way to reach.
    static CORE_MEMORY: Cell<Option<CoreMemory>> = const { Cell::new(None) };
}

unsafe extern "C" fn core_memory_info(id: u32, info: *mut ffi::rc_libretro_core_memory_info_t) {
    let Some(info) = (unsafe { info.as_mut() }) else {
        return;
    };
    *info = ffi::rc_libretro_core_memory_info_t::default();
    if let Some(core) = CORE_MEMORY.get() {
        // SAFETY: the game is loaded while memory is laid out.
        unsafe {
            info.data = (core.data)(id).cast();
            info.size = (core.size)(id);
        }
    }
}

/// A RetroAchievements client for one game.
pub(crate) struct Achievements {
    client: NonNull<ffi::rc_client_t>,
    state: Box<State>,
    hardcore: bool,
    /// The content path, which the client may keep using.
    _content: CString,
}

impl Achievements {
    /// Signs in and starts identifying and loading the game in `content`.
    /// The first requests go out before this returns.
    pub(crate) fn start(
        options: &AchievementOptions,
        content: &Path,
        http: Box<dyn HttpClient>,
    ) -> Option<Self> {
        let state = Box::new(State {
            http,
            pending: RefCell::new(HashMap::new()),
            next_id: Cell::new(1),
            load: RefCell::new(Load::Loading),
            events: RefCell::new(Vec::new()),
            memory: RefCell::new(Memory {
                regions: ffi::rc_libretro_memory_regions_t::default(),
                console_id: options.console_id,
                ready: false,
            }),
        });
        // SAFETY: the callbacks have rc_client's signatures.
        let client = unsafe { ffi::rc_client_create(Some(read_memory), Some(server_call)) };
        let client = NonNull::new(client)?;
        let (Ok(username), Ok(token)) = (
            CString::new(options.username.as_str()),
            CString::new(options.token.as_str()),
        ) else {
            // SAFETY: the client was just created.
            unsafe { ffi::rc_client_destroy(client.as_ptr()) };
            return None;
        };
        let content = path_cstring(content);
        let achievements = Self {
            client,
            state,
            hardcore: options.hardcore,
            _content: content,
        };
        let client = achievements.client.as_ptr();
        // SAFETY: the state outlives the client, which `drop` destroys first.
        // rc_client copies the username and token.
        unsafe {
            ffi::rc_client_set_userdata(
                client,
                (&*achievements.state as *const State).cast_mut().cast(),
            );
            ffi::rc_client_enable_logging(
                client,
                ffi::RC_CLIENT_LOG_LEVEL_INFO as c_int,
                Some(log_message),
            );
            ffi::rc_client_set_event_handler(client, Some(event_handler));
            ffi::rc_client_set_hardcore_enabled(client, c_int::from(options.hardcore));
            ffi::rc_client_set_unofficial_enabled(client, 0);
            ffi::rc_client_set_encore_mode_enabled(client, 0);
            // The game's addresses are checked as its set activates, which
            // waits for the next frame or idle call, where the core's memory
            // is laid out first; a set checked before then would lose every
            // achievement.
            ffi::rc_client_set_allow_background_memory_reads(client, 0);
            ffi::rc_client_begin_login_with_token(
                client,
                username.as_ptr(),
                token.as_ptr(),
                Some(signed_in),
                std::ptr::null_mut(),
            );
            // The client waits for the sign-in before it asks for the game.
            ffi::rc_client_begin_identify_and_load_game(
                client,
                options.console_id,
                achievements._content.as_ptr(),
                std::ptr::null(),
                0,
                Some(game_loaded),
                std::ptr::null_mut(),
            );
        }
        Some(achievements)
    }

    /// Lays out the core's memory as the console's addresses, from the
    /// core's memory map if it gave one, and from its system and save memory
    /// otherwise. It's done again on each frame until the core has memory.
    pub(crate) fn set_memory(&self, map: Option<&[retro_memory_descriptor]>, core: CoreMemory) {
        let mut memory = self.state.memory.borrow_mut();
        let map = map.map(|descriptors| retro_memory_map {
            descriptors: descriptors.as_ptr(),
            num_descriptors: descriptors.len() as c_uint,
        });
        CORE_MEMORY.set(Some(core));
        let console_id = memory.console_id;
        // SAFETY: the map's descriptors and the core's memory live while the
        // game is loaded; the regions are destroyed before they're laid out
        // again.
        memory.ready = unsafe {
            ffi::rc_libretro_memory_destroy(&mut memory.regions);
            ffi::rc_libretro_memory_init(
                &mut memory.regions,
                map.as_ref().map_or(std::ptr::null(), |m| m as *const _),
                Some(core_memory_info),
                console_id,
            ) != 0
        };
        CORE_MEMORY.set(None);
    }

    /// Whether the core's memory is laid out yet.
    pub(crate) fn has_memory(&self) -> bool {
        self.state.memory.borrow().ready
    }

    /// Checks the achievements against the frame the core just ran.
    /// Nothing is checked until the core's memory is laid out, since a frame
    /// may activate the game's set.
    pub(crate) fn do_frame(&self) {
        if self.has_memory() {
            // SAFETY: the client is live; no state borrow is held.
            unsafe { ffi::rc_client_do_frame(self.client.as_ptr()) };
        }
    }

    /// Lets the client retry and ping while the game is paused, once the
    /// core's memory is laid out, since it may activate the game's set.
    pub(crate) fn idle(&self) {
        if self.has_memory() {
            // SAFETY: as in `do_frame`.
            unsafe { ffi::rc_client_idle(self.client.as_ptr()) };
        }
    }

    /// Tells the client the core reset, so achievements start over.
    pub(crate) fn reset(&self) {
        // SAFETY: as in `do_frame`.
        unsafe { ffi::rc_client_reset(self.client.as_ptr()) };
    }

    /// Tells the client the core's memory jumped to a state, so progress
    /// toward each achievement starts over rather than carrying on from
    /// before the jump.
    pub(crate) fn state_loaded(&self) {
        // SAFETY: as in `do_frame`; a null buffer resets every achievement.
        unsafe {
            ffi::rc_client_deserialize_progress_sized(self.client.as_ptr(), std::ptr::null(), 0)
        };
    }

    /// Hands a request's response to the client: the HTTP status and body,
    /// or `None` when the server couldn't be reached, which the client may
    /// retry.
    pub(crate) fn respond(&self, id: u64, status: Option<u16>, body: &[u8]) {
        let Some(pending) = self.state.pending.borrow_mut().remove(&id) else {
            log::warn!("RetroAchievements response for unknown request {id}");
            return;
        };
        // rcheevos reads some bodies as C strings, even empty ones.
        let terminated: Vec<u8> = body.iter().copied().chain([0]).collect();
        let response = ffi::rc_api_server_response_t {
            body: terminated.as_ptr().cast(),
            body_length: body.len(),
            http_status_code: status.map_or(
                ffi::RC_API_SERVER_RESPONSE_RETRYABLE_CLIENT_ERROR,
                c_int::from,
            ),
        };
        if let Some(callback) = pending.callback {
            // SAFETY: the callback and its data came from the client, which
            // calls each once; no state borrow is held.
            unsafe { callback(&response, pending.data) };
        }
    }

    /// Whether hardcore's rules apply now: the session plays in hardcore and
    /// the game has achievements, or may have them once it's identified.
    pub(crate) fn restricts(&self) -> bool {
        let load = self.state.load.borrow().clone();
        self.hardcore
            && match load {
                Load::Loading => true,
                Load::Loaded => self.has_achievements(),
                Load::NoSet | Load::Failed(_) => false,
            }
    }

    fn has_achievements(&self) -> bool {
        // SAFETY: the client is live; no state borrow is held.
        unsafe { ffi::rc_client_has_achievements(self.client.as_ptr()) != 0 }
    }

    /// Takes what happened since the last call.
    pub(crate) fn take_events(&self) -> Vec<AchievementEvent> {
        std::mem::take(&mut *self.state.events.borrow_mut())
    }

    /// Where the game's achievements stand.
    pub(crate) fn status(&self) -> AchievementStatus {
        let load = self.state.load.borrow().clone();
        match load {
            Load::Loading => AchievementStatus::Loading,
            Load::NoSet => AchievementStatus::NoSet,
            Load::Failed(reason) => AchievementStatus::Failed { reason },
            Load::Loaded if !self.has_achievements() => AchievementStatus::NoSet,
            Load::Loaded => {
                let client = self.client.as_ptr();
                // SAFETY: the game is loaded, and the list lives until it's
                // destroyed below.
                unsafe {
                    let game = ffi::rc_client_get_game_info(client)
                        .as_ref()
                        .map(|g| string(g.title))
                        .unwrap_or_default();
                    let list = ffi::rc_client_create_achievement_list(
                        client,
                        ffi::RC_CLIENT_ACHIEVEMENT_CATEGORY_CORE as c_int,
                        ffi::RC_CLIENT_ACHIEVEMENT_LIST_GROUPING_LOCK_STATE as c_int,
                    );
                    let mut achievements = Vec::new();
                    if let Some(list) = list.as_ref() {
                        for bucket in slice(list.buckets, list.num_buckets) {
                            for &achievement in slice(bucket.achievements, bucket.num_achievements)
                            {
                                if let Some(achievement) = achievement.as_ref() {
                                    achievements.push(convert(achievement));
                                }
                            }
                        }
                        ffi::rc_client_destroy_achievement_list(list as *const _ as *mut _);
                    }
                    AchievementStatus::Ready {
                        game,
                        hardcore: self.hardcore,
                        achievements,
                    }
                }
            }
        }
    }
}

impl Drop for Achievements {
    fn drop(&mut self) {
        // SAFETY: the client is live, and nothing uses it after this. Requests
        // still waiting are dropped unanswered, as the client allows.
        unsafe { ffi::rc_client_destroy(self.client.as_ptr()) };
    }
}

/// The client's state, from the userdata `start` set.
///
/// # Safety
///
/// `client` is a client that [`Achievements::start`] made and hasn't
/// destroyed.
unsafe fn state<'a>(client: *const ffi::rc_client_t) -> Option<&'a State> {
    // SAFETY: forwarded from the caller; the userdata is the boxed state.
    unsafe { ffi::rc_client_get_userdata(client).cast::<State>().as_ref() }
}

unsafe extern "C" fn read_memory(
    address: u32,
    buffer: *mut u8,
    num_bytes: u32,
    client: *mut ffi::rc_client_t,
) -> u32 {
    // SAFETY: the client calls this while it's live.
    match unsafe { state(client) } {
        Some(state) => state.memory.borrow().read(address, buffer, num_bytes),
        None => 0,
    }
}

unsafe extern "C" fn server_call(
    request: *const ffi::rc_api_request_t,
    callback: ffi::rc_client_server_callback_t,
    callback_data: *mut c_void,
    client: *mut ffi::rc_client_t,
) {
    // SAFETY: the client calls this while it's live, with a valid request.
    let (Some(state), Some(request)) = (unsafe { state(client) }, unsafe { request.as_ref() })
    else {
        return;
    };
    let id = state.next_id.get();
    state.next_id.set(id + 1);
    state.pending.borrow_mut().insert(
        id,
        Pending {
            callback,
            data: callback_data,
        },
    );
    // SAFETY: the request's strings are null or valid C strings.
    let request = unsafe {
        AchievementsRequest {
            url: string(request.url),
            post_data: opt_string(request.post_data),
            content_type: opt_string(request.content_type),
        }
    };
    state.http.request(id, request);
}

unsafe extern "C" fn signed_in(
    result: c_int,
    message: *const c_char,
    client: *mut ffi::rc_client_t,
    _userdata: *mut c_void,
) {
    if result == ffi::RC_OK {
        log::info!("signed in to RetroAchievements");
        return;
    }
    // SAFETY: the client calls this while it's live, with a null or valid
    // message.
    let (Some(state), detail) = (unsafe { state(client) }, unsafe {
        error_text(result, message)
    }) else {
        return;
    };
    log::warn!("can't sign in to RetroAchievements: {detail}");
    let reason = match result {
        ffi::RC_INVALID_CREDENTIALS | ffi::RC_EXPIRED_TOKEN => {
            "Sign in to RetroAchievements again from the launcher's quick menu.".to_owned()
        }
        _ => format!("Couldn't sign in to RetroAchievements: {detail}"),
    };
    state.fail(reason);
}

unsafe extern "C" fn game_loaded(
    result: c_int,
    message: *const c_char,
    client: *mut ffi::rc_client_t,
    _userdata: *mut c_void,
) {
    // SAFETY: as in `signed_in`.
    let Some(state) = (unsafe { state(client) }) else {
        return;
    };
    // SAFETY: as in `signed_in`.
    let detail = unsafe { error_text(result, message) };
    match result {
        ffi::RC_OK => {
            // Whether it has achievements is asked later, outside the
            // client's callback.
            log::info!("RetroAchievements loaded the game");
            *state.load.borrow_mut() = Load::Loaded;
        }
        ffi::RC_NO_GAME_LOADED => {
            log::info!("RetroAchievements doesn't know the game: {detail}");
            *state.load.borrow_mut() = Load::NoSet;
        }
        // The sign-in's failure already says why.
        ffi::RC_LOGIN_REQUIRED | ffi::RC_INVALID_CREDENTIALS | ffi::RC_EXPIRED_TOKEN
            if matches!(*state.load.borrow(), Load::Failed(_)) => {}
        _ => {
            log::warn!("RetroAchievements can't load the game: {detail}");
            state.fail(format!("Couldn't load the game's achievements: {detail}"));
        }
    }
}

impl State {
    fn fail(&self, reason: String) {
        let mut load = self.load.borrow_mut();
        if matches!(*load, Load::Failed(_)) {
            return;
        }
        *load = Load::Failed(reason.clone());
        self.events
            .borrow_mut()
            .push(AchievementEvent::Failed { reason });
    }
}

unsafe extern "C" fn event_handler(
    event: *const ffi::rc_client_event_t,
    client: *mut ffi::rc_client_t,
) {
    // SAFETY: the client calls this while it's live, with a valid event.
    let (Some(state), Some(event)) = (unsafe { state(client) }, unsafe { event.as_ref() }) else {
        return;
    };
    match event.type_ {
        ffi::RC_CLIENT_EVENT_ACHIEVEMENT_TRIGGERED => {
            // SAFETY: the event's achievement is valid for the call.
            if let Some(achievement) = unsafe { event.achievement.as_ref() } {
                let achievement = unsafe { convert(achievement) };
                log::info!(
                    "unlocked achievement {}: {}",
                    achievement.id,
                    achievement.title
                );
                state
                    .events
                    .borrow_mut()
                    .push(AchievementEvent::Unlocked { achievement });
            }
        }
        ffi::RC_CLIENT_EVENT_SERVER_ERROR => {
            // SAFETY: the event's error is valid for the call.
            if let Some(error) = unsafe { event.server_error.as_ref() } {
                let (api, message) = unsafe { (string(error.api), string(error.error_message)) };
                log::warn!("RetroAchievements {api} failed: {message}");
            }
        }
        ffi::RC_CLIENT_EVENT_DISCONNECTED => {
            log::warn!("RetroAchievements is out of reach; unlocks wait to be sent")
        }
        ffi::RC_CLIENT_EVENT_RECONNECTED => log::info!("RetroAchievements is back; unlocks sent"),
        ffi::RC_CLIENT_EVENT_RESET => {
            // Only enabling hardcore mid-game asks for this, which the
            // session never does.
            log::warn!("RetroAchievements asked for a reset");
        }
        _ => {}
    }
}

unsafe extern "C" fn log_message(message: *const c_char, _client: *const ffi::rc_client_t) {
    // SAFETY: the client logs valid C strings.
    log::info!("rcheevos: {}", unsafe { string(message) });
}

/// An achievement as the app sees it.
///
/// # Safety
///
/// The achievement's strings are null or valid C strings.
unsafe fn convert(achievement: &ffi::rc_client_achievement_t) -> Achievement {
    let unlocked = achievement.state == ffi::RC_CLIENT_ACHIEVEMENT_STATE_UNLOCKED as u8
        || achievement.unlocked != 0;
    // SAFETY: forwarded from the caller; the progress is a C string in place.
    let progress = unsafe { string(achievement.measured_progress.as_ptr()) };
    Achievement {
        id: achievement.id,
        // SAFETY: forwarded from the caller.
        title: unsafe { string(achievement.title) },
        description: unsafe { string(achievement.description) },
        points: achievement.points,
        unlocked,
        progress: (!unlocked && !progress.is_empty()).then_some(progress),
        progress_percent: if unlocked {
            0.0
        } else {
            achievement.measured_percent
        },
    }
}

/// A C string as an owned string, or empty for null.
///
/// # Safety
///
/// `s` is null or a valid C string.
unsafe fn string(s: *const c_char) -> String {
    // SAFETY: forwarded from the caller.
    unsafe { opt_string(s) }.unwrap_or_default()
}

/// # Safety
///
/// `s` is null or a valid C string.
unsafe fn opt_string(s: *const c_char) -> Option<String> {
    // SAFETY: forwarded from the caller.
    (!s.is_null()).then(|| unsafe { CStr::from_ptr(s) }.to_string_lossy().into_owned())
}

/// The `len` items at `ptr`, or none for null.
///
/// # Safety
///
/// `ptr` is null or points to `len` valid items.
unsafe fn slice<'a, T>(ptr: *const T, len: u32) -> &'a [T] {
    if ptr.is_null() || len == 0 {
        &[]
    } else {
        // SAFETY: forwarded from the caller.
        unsafe { std::slice::from_raw_parts(ptr, len as usize) }
    }
}

fn path_cstring(path: &Path) -> CString {
    use std::os::unix::ffi::OsStrExt;
    CString::new(path.as_os_str().as_bytes()).unwrap_or_default()
}

/// The clause rcheevos asks clients to add to their User-Agent, such as
/// `rcheevos/12.5`.
#[uniffi::export]
pub fn rcheevos_user_agent_clause() -> String {
    let mut buffer = [0 as c_char; 64];
    // SAFETY: the buffer has the size given; a null client is allowed.
    unsafe {
        ffi::rc_client_get_user_agent_clause(
            std::ptr::null_mut(),
            buffer.as_mut_ptr(),
            buffer.len(),
        );
        string(buffer.as_ptr())
    }
}

#[cfg(test)]
mod tests;
