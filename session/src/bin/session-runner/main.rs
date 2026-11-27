//! A development runner for the session: it runs a core and its content in a
//! window, with audio, keyboard and gamepad input, save memory and states.
//! It's for working on the session, not part of the product.
//!
//! Keys: arrows for the D-pad, Z, X, A and S for B, A, Y and X, Q and W for L
//! and R, 1 and 2 for L2 and R2, Enter for Start, Backspace or right Shift for
//! Select. F2 saves a state, F4 loads it, and Escape quits.

mod audio;
mod input;

use std::cell::RefCell;
use std::collections::HashMap;
use std::num::NonZeroU32;
use std::path::PathBuf;
use std::ptr::NonNull;
use std::rc::Rc;
use std::sync::Arc;
use std::time::{Duration, Instant};

use clap::{Parser, ValueEnum};
use handheld_session::{
    AudioSink, CapturedFrame, Frame, InputState, NativeWindow, NullAudioSink, Region, Session,
    SessionConfig, VideoOutput, VideoSink,
};
use winit::application::ApplicationHandler;
use winit::dpi::PhysicalSize;
use winit::event::{ElementState, KeyEvent, WindowEvent};
use winit::event_loop::{ActiveEventLoop, ControlFlow, EventLoop};
use winit::keyboard::{KeyCode, PhysicalKey};
use winit::raw_window_handle::{
    HasDisplayHandle, HasWindowHandle, RawDisplayHandle, RawWindowHandle,
};
use winit::window::{Window, WindowId};

#[derive(Parser)]
#[command(about = "Run a libretro core in a window, for developing the session")]
struct Args {
    /// The core's shared library.
    core: PathBuf,
    /// The content to load. Some cores run without any.
    content: Option<PathBuf>,
    /// How frames reach the window.
    #[arg(long, value_enum, default_value_t = Present::Gl)]
    present: Present,
    /// Where the core finds BIOS files. Defaults to a directory in
    /// $XDG_DATA_HOME.
    #[arg(long)]
    system_dir: Option<PathBuf>,
    /// Where save memory and states go. Defaults to a directory in
    /// $XDG_DATA_HOME.
    #[arg(long)]
    save_dir: Option<PathBuf>,
    /// A core option, as KEY=VALUE. Repeat it for more.
    #[arg(long = "option", value_parser = parse_option)]
    options: Vec<(String, String)>,
    /// The window's size, as a multiple of the core's base frame size.
    #[arg(long, default_value_t = 3)]
    scale: u32,
    /// Quits after this many frames.
    #[arg(long)]
    frames: Option<u64>,
    /// Runs without audio output.
    #[arg(long)]
    mute: bool,
}

#[derive(Clone, Copy, PartialEq, Eq, ValueEnum)]
enum Present {
    /// The session presents through an EGL context on the window.
    Gl,
    /// The session hands frames over, and the runner copies them to the
    /// window on the CPU.
    Cpu,
}

fn parse_option(s: &str) -> Result<(String, String), String> {
    s.split_once('=')
        .map(|(k, v)| (k.to_owned(), v.to_owned()))
        .ok_or_else(|| format!("{s:?} isn't KEY=VALUE"))
}

fn main() {
    env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("info")).init();
    let args = Args::parse();
    let event_loop = EventLoop::new().expect("an event loop");
    let mut app = App::new(args);
    event_loop.run_app(&mut app).expect("the event loop runs");
    if let Some(error) = app.error {
        eprintln!("session-runner: {error}");
        std::process::exit(1);
    }
}

/// The last frame the session handed over, for presenting on the CPU.
#[derive(Clone, Default)]
struct FrameSlot(Rc<RefCell<Option<CapturedFrame>>>);

impl VideoSink for FrameSlot {
    fn frame(&mut self, frame: Frame<'_>) {
        *self.0.borrow_mut() = Some(CapturedFrame {
            width: frame.width,
            height: frame.height,
            pixels: frame.pixels.to_vec(),
        });
    }
}

struct CpuPresenter {
    surface: softbuffer::Surface<Rc<Window>, Rc<Window>>,
    frames: FrameSlot,
}

impl CpuPresenter {
    /// Draws the last frame scaled to fit the window, with nearest-neighbor
    /// sampling, on black.
    fn present(&mut self, window: &Window, aspect: f32) {
        let size = window.inner_size();
        let (Some(win_w), Some(win_h)) =
            (NonZeroU32::new(size.width), NonZeroU32::new(size.height))
        else {
            return;
        };
        let slot = self.frames.0.borrow();
        let Some(frame) = slot.as_ref() else {
            return;
        };
        if self.surface.resize(win_w, win_h).is_err() {
            return;
        }
        let Ok(mut buffer) = self.surface.buffer_mut() else {
            return;
        };
        let (x0, y0, dst_w, dst_h) =
            Region::Whole.fit(frame.width, frame.height, aspect, win_w.get(), win_h.get());
        let [x0, y0, dst_w, dst_h] = [x0, y0, dst_w, dst_h].map(|n| n as usize);
        let win_w = win_w.get() as usize;
        buffer.fill(0);
        for y in 0..dst_h {
            let src_y = y * frame.height as usize / dst_h;
            let row = &mut buffer[(y0 + y) * win_w + x0..][..dst_w];
            for (x, out) in row.iter_mut().enumerate() {
                let src_x = x * frame.width as usize / dst_w;
                let i = (src_y * frame.width as usize + src_x) * 4;
                let [r, g, b] = [0, 1, 2].map(|c| u32::from(frame.pixels[i + c]));
                *out = (r << 16) | (g << 8) | b;
            }
        }
        let _ = buffer.present();
    }
}

struct App {
    args: Args,
    /// Declared before the window, so it's dropped first: the session may
    /// draw to the window.
    session: Option<Session>,
    cpu: Option<CpuPresenter>,
    window: Option<Rc<Window>>,
    input: Arc<InputState>,
    gamepads: input::Gamepads,
    next_frame: Instant,
    frame_period: Duration,
    frames_run: u64,
    stats: (Instant, u64),
    save_ram: PathBuf,
    state: PathBuf,
    error: Option<String>,
}

/// Where zipped games are extracted for cores that can't open them, in
/// $XDG_CACHE_HOME.
fn content_cache() -> PathBuf {
    std::env::var_os("XDG_CACHE_HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|| {
            PathBuf::from(std::env::var_os("HOME").unwrap_or_default()).join(".cache")
        })
        .join("handheld/session-runner/content")
}

impl App {
    fn new(args: Args) -> Self {
        let data = std::env::var_os("XDG_DATA_HOME")
            .map(PathBuf::from)
            .unwrap_or_else(|| {
                PathBuf::from(std::env::var_os("HOME").unwrap_or_default()).join(".local/share")
            })
            .join("handheld/session-runner");
        let save_dir = args.save_dir.clone().unwrap_or_else(|| data.join("saves"));
        let stem = args
            .content
            .as_ref()
            .or(Some(&args.core))
            .and_then(|p| p.file_stem())
            .map(|s| s.to_string_lossy().into_owned())
            .unwrap_or_default();
        Self {
            save_ram: save_dir.join(format!("{stem}.srm")),
            state: save_dir.join(format!("{stem}.state")),
            args,
            session: None,
            cpu: None,
            window: None,
            input: Arc::new(InputState::new()),
            gamepads: input::Gamepads::new(),
            next_frame: Instant::now(),
            frame_period: Duration::from_secs_f64(1.0 / 60.0),
            frames_run: 0,
            stats: (Instant::now(), 0),
            error: None,
        }
    }

    fn fail(&mut self, event_loop: &ActiveEventLoop, error: String) {
        self.error = Some(error);
        event_loop.exit();
    }

    fn start(&mut self, event_loop: &ActiveEventLoop) -> Result<(), String> {
        let attributes = Window::default_attributes()
            .with_title("session-runner")
            .with_inner_size(PhysicalSize::new(
                320 * self.args.scale,
                240 * self.args.scale,
            ));
        let window = Rc::new(
            event_loop
                .create_window(attributes)
                .map_err(|e| e.to_string())?,
        );
        let size = window.inner_size();

        let video = match self.args.present {
            Present::Gl => VideoOutput::Window {
                window: Some((native_window(&window)?, size.width, size.height)),
                lower: Region::bottom(None),
            },
            Present::Cpu => {
                let context =
                    softbuffer::Context::new(window.clone()).map_err(|e| e.to_string())?;
                let surface = softbuffer::Surface::new(&context, window.clone())
                    .map_err(|e| e.to_string())?;
                let frames = FrameSlot::default();
                self.cpu = Some(CpuPresenter {
                    surface,
                    frames: frames.clone(),
                });
                VideoOutput::Sink(Box::new(frames))
            }
        };
        let audio: Box<dyn AudioSink> = if self.args.mute {
            Box::new(NullAudioSink)
        } else {
            match audio::CpalSink::new() {
                Ok(sink) => Box::new(sink),
                Err(e) => {
                    log::warn!("no audio: {e}");
                    Box::new(NullAudioSink)
                }
            }
        };
        let save_dir = self.state.parent().expect("a save directory").to_owned();
        let config = SessionConfig {
            core: self.args.core.clone(),
            content: self.args.content.clone(),
            content_cache: Some(content_cache()),
            system_dir: self
                .args
                .system_dir
                .clone()
                .unwrap_or_else(|| save_dir.parent().expect("a data directory").join("system")),
            save_dir,
            save_ram: Some(self.save_ram.clone()),
            video,
            audio,
            input: self.input.clone(),
            options: self.args.options.iter().cloned().collect::<HashMap<_, _>>(),
            achievements: None,
        };
        // SAFETY: the user named a libretro core, and the window outlives the
        // session, which `App` drops first.
        let session = unsafe { Session::new(config) }.map_err(|e| error_chain(&e))?;
        let av = session.av_info();
        let info = session.system_info();
        window.set_title(&format!("{} {}", info.library_name, info.library_version));
        let _ = window.request_inner_size(PhysicalSize::new(
            av.base_width.max(1) * self.args.scale,
            av.base_height.max(1) * self.args.scale,
        ));
        self.frame_period = av.frame_period();
        self.next_frame = Instant::now();
        self.session = Some(session);
        self.window = Some(window);
        Ok(())
    }

    fn run_frame(&mut self, event_loop: &ActiveEventLoop) {
        let Some(session) = &mut self.session else {
            return;
        };
        self.gamepads.update(self.input.port(0));
        session.run_frame();
        // The core may change its frame rate mid-game.
        self.frame_period = session.av_info().frame_period();
        self.frames_run += 1;
        if let (Some(cpu), Some(window)) = (&mut self.cpu, &self.window) {
            cpu.present(window, session.av_info().aspect_ratio);
        }
        let (since, frames) = self.stats;
        if since.elapsed() >= Duration::from_secs(5) {
            let fps = (self.frames_run - frames) as f64 / since.elapsed().as_secs_f64();
            log::info!("{fps:.2} frames a second");
            self.stats = (Instant::now(), self.frames_run);
        }
        if session.shutdown_requested() || self.args.frames.is_some_and(|n| self.frames_run >= n) {
            event_loop.exit();
        }
    }

    fn key(&mut self, key: KeyCode, pressed: bool) {
        let Some(session) = &mut self.session else {
            return;
        };
        match (key, pressed) {
            (KeyCode::F2, true) => match session.save_state(&self.state) {
                Ok(()) => log::info!("saved a state to {}", self.state.display()),
                Err(e) => log::warn!("can't save a state: {}", error_chain(&e)),
            },
            (KeyCode::F4, true) => match session.load_state(&self.state) {
                Ok(()) => log::info!("loaded the state from {}", self.state.display()),
                Err(e) => log::warn!("can't load the state: {}", error_chain(&e)),
            },
            _ => {
                if let Some(button) = input::key_button(key) {
                    self.input.port(0).set_button(button, pressed);
                }
            }
        }
    }
}

impl ApplicationHandler for App {
    fn resumed(&mut self, event_loop: &ActiveEventLoop) {
        if self.window.is_none()
            && let Err(e) = self.start(event_loop)
        {
            self.fail(event_loop, e);
        }
    }

    fn window_event(&mut self, event_loop: &ActiveEventLoop, _: WindowId, event: WindowEvent) {
        match event {
            WindowEvent::CloseRequested => event_loop.exit(),
            WindowEvent::Resized(size) => {
                if let Some(session) = &mut self.session {
                    session.resize_window(size.width, size.height);
                }
            }
            WindowEvent::KeyboardInput {
                event:
                    KeyEvent {
                        physical_key: PhysicalKey::Code(key),
                        state,
                        repeat: false,
                        ..
                    },
                ..
            } => {
                if key == KeyCode::Escape {
                    event_loop.exit();
                } else {
                    self.key(key, state == ElementState::Pressed);
                }
            }
            WindowEvent::RedrawRequested => {
                if let (Some(cpu), Some(window), Some(session)) =
                    (&mut self.cpu, &self.window, &self.session)
                {
                    cpu.present(window, session.av_info().aspect_ratio);
                }
            }
            _ => {}
        }
    }

    fn about_to_wait(&mut self, event_loop: &ActiveEventLoop) {
        let now = Instant::now();
        if now >= self.next_frame {
            self.run_frame(event_loop);
            self.next_frame += self.frame_period;
            // After a stall, carry on from now rather than rushing to catch up.
            if self.next_frame < now {
                self.next_frame = now + self.frame_period;
            }
        }
        event_loop.set_control_flow(ControlFlow::WaitUntil(self.next_frame));
    }

    fn exiting(&mut self, _: &ActiveEventLoop) {
        self.session = None;
    }
}

/// The session's view of the winit window.
fn native_window(window: &Window) -> Result<NativeWindow, String> {
    let display = window.display_handle().map_err(|e| e.to_string())?.as_raw();
    let handle = window.window_handle().map_err(|e| e.to_string())?.as_raw();
    // SAFETY: the window and its display outlive the session.
    unsafe {
        match (display, handle) {
            (RawDisplayHandle::Wayland(d), RawWindowHandle::Wayland(w)) => {
                Ok(NativeWindow::wayland(d.display, w.surface))
            }
            (RawDisplayHandle::Xlib(d), RawWindowHandle::Xlib(w)) => {
                let display: NonNull<std::ffi::c_void> = d.display.ok_or("no Xlib display")?;
                Ok(NativeWindow::xlib(display, w.window))
            }
            _ => Err("the session can't present to this kind of window; try --present cpu".into()),
        }
    }
}

fn error_chain(e: &dyn std::error::Error) -> String {
    let mut message = e.to_string();
    let mut source = e.source();
    while let Some(e) = source {
        message.push_str(": ");
        message.push_str(&e.to_string());
        source = e.source();
    }
    message
}
