/*
 * A libretro core for testing what a core may ask of the frontend mid-session.
 *
 * It runs without content and draws one vertical stripe per controller port:
 * white while any button on that port is held, gray once the frontend has set
 * a RetroPad on the port, and black otherwise. It declares three ports with
 * SET_CONTROLLER_INFO, so the fourth stripe stays black until pressed.
 *
 * Select on port 0 switches between two video and audio modes with
 * SET_SYSTEM_AV_INFO: 320x240 at 4:3, 60 fps and 44.1 kHz, and 384x216 at
 * 16:9, 50 fps and 32 kHz. Start on any port asks the frontend to quit with
 * RETRO_ENVIRONMENT_SHUTDOWN.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include <stdbool.h>
#include <stdint.h>
#include <string.h>

#include "libretro.h"

#define PORTS 4
#define DECLARED_PORTS 3
#define MAX_WIDTH 384
#define MAX_HEIGHT 240

static retro_environment_t environ_cb;
static retro_video_refresh_t video_cb;
static retro_audio_sample_batch_t audio_batch_cb;
static retro_input_poll_t input_poll_cb;
static retro_input_state_t input_state_cb;

static uint32_t frame[MAX_WIDTH * MAX_HEIGHT];
static int16_t silence[2 * 44100 / 50];
static unsigned devices[PORTS];
static bool wide;
static bool select_held;

void retro_set_environment(retro_environment_t cb) {
  static const struct retro_controller_description pad = {"RetroPad", RETRO_DEVICE_JOYPAD};
  static const struct retro_controller_info ports[DECLARED_PORTS + 1] = {
      {&pad, 1}, {&pad, 1}, {&pad, 1}, {NULL, 0}};
  bool no_game = true;
  environ_cb = cb;
  cb(RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME, &no_game);
  cb(RETRO_ENVIRONMENT_SET_CONTROLLER_INFO, (void *)ports);
}

void retro_set_video_refresh(retro_video_refresh_t cb) { video_cb = cb; }
void retro_set_audio_sample(retro_audio_sample_t cb) { (void)cb; }
void retro_set_audio_sample_batch(retro_audio_sample_batch_t cb) { audio_batch_cb = cb; }
void retro_set_input_poll(retro_input_poll_t cb) { input_poll_cb = cb; }
void retro_set_input_state(retro_input_state_t cb) { input_state_cb = cb; }

unsigned retro_api_version(void) { return RETRO_API_VERSION; }

void retro_init(void) {
  memset(devices, 0, sizeof(devices));
  wide = false;
  select_held = false;
}

void retro_deinit(void) {}

void retro_get_system_info(struct retro_system_info *info) {
  memset(info, 0, sizeof(*info));
  info->library_name = "Events test";
  info->library_version = "1";
}

void retro_get_system_av_info(struct retro_system_av_info *info) {
  memset(info, 0, sizeof(*info));
  info->geometry.base_width = wide ? 384 : 320;
  info->geometry.base_height = wide ? 216 : 240;
  info->geometry.max_width = MAX_WIDTH;
  info->geometry.max_height = MAX_HEIGHT;
  info->geometry.aspect_ratio = wide ? 16.0f / 9.0f : 4.0f / 3.0f;
  info->timing.fps = wide ? 50.0 : 60.0;
  info->timing.sample_rate = wide ? 32000.0 : 44100.0;
}

void retro_set_controller_port_device(unsigned port, unsigned device) {
  if (port < PORTS) devices[port] = device;
}

void retro_reset(void) {}

void retro_run(void) {
  struct retro_system_av_info av;
  bool pressed[PORTS];
  unsigned width, height, stripe, x, y, port;

  input_poll_cb();
  for (port = 0; port < PORTS; port++)
    pressed[port] = input_state_cb(port, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_MASK) != 0;

  if (input_state_cb(0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_SELECT)) {
    if (!select_held) {
      wide = !wide;
      retro_get_system_av_info(&av);
      environ_cb(RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO, &av);
    }
    select_held = true;
  } else {
    select_held = false;
  }

  width = wide ? 384 : 320;
  height = wide ? 216 : 240;
  stripe = width / PORTS;
  for (y = 0; y < height; y++)
    for (x = 0; x < width; x++) {
      port = x / stripe;
      frame[y * width + x] = pressed[port]                         ? 0xffffff
                             : devices[port] == RETRO_DEVICE_JOYPAD ? 0x404040
                                                                     : 0x000000;
    }
  video_cb(frame, width, height, width * sizeof(uint32_t));
  audio_batch_cb(silence, (size_t)(wide ? 32000 / 50 : 44100 / 60));

  for (port = 0; port < PORTS; port++)
    if (input_state_cb(port, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_START))
      environ_cb(RETRO_ENVIRONMENT_SHUTDOWN, NULL);
}

bool retro_load_game(const struct retro_game_info *game) {
  enum retro_pixel_format format = RETRO_PIXEL_FORMAT_XRGB8888;
  (void)game;
  return environ_cb(RETRO_ENVIRONMENT_SET_PIXEL_FORMAT, &format);
}

bool retro_load_game_special(unsigned type, const struct retro_game_info *info, size_t num) {
  (void)type;
  (void)info;
  (void)num;
  return false;
}

void retro_unload_game(void) {}
unsigned retro_get_region(void) { return RETRO_REGION_NTSC; }
size_t retro_serialize_size(void) { return 0; }
bool retro_serialize(void *data, size_t size) {
  (void)data;
  (void)size;
  return false;
}
bool retro_unserialize(const void *data, size_t size) {
  (void)data;
  (void)size;
  return false;
}
void retro_cheat_reset(void) {}
void retro_cheat_set(unsigned index, bool enabled, const char *code) {
  (void)index;
  (void)enabled;
  (void)code;
}
void *retro_get_memory_data(unsigned id) {
  (void)id;
  return NULL;
}
size_t retro_get_memory_size(unsigned id) {
  (void)id;
  return 0;
}
