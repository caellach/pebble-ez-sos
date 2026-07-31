#include "alarm.h"

#include "main.h"

#define VIBE_INTERVAL_MS 1000
#define INBOUND_SUPPRESS_MS 2000
#define PERSIST_KEY_WATCH_ALARM_SOUND 3
#define SOUND_VOLUME 90

typedef enum {
  ALARM_KIND_INBOUND = 0,
  ALARM_KIND_SELF_LOCATE = 1,
} AlarmKind;

static Window *s_alarm_window;
static TextLayer *s_text_layer;
static AppTimer *s_vibe_timer;
static AppTimer *s_suppress_timer;
static bool s_alarm_active;
static bool s_suppress_inbound;
static bool s_sound_enabled = true;
static AlarmKind s_alarm_kind = ALARM_KIND_INBOUND;

static const SpeakerNote s_alarm_notes[] = {
  { .midi_note = 76, .waveform = SpeakerWaveformSquare, .duration_ms = 180, .velocity = 127, .reserved = 0 },
  { .midi_note = 0,  .waveform = SpeakerWaveformSquare, .duration_ms = 80,  .velocity = 0,   .reserved = 0 },
  { .midi_note = 79, .waveform = SpeakerWaveformSquare, .duration_ms = 180, .velocity = 127, .reserved = 0 },
  { .midi_note = 0,  .waveform = SpeakerWaveformSquare, .duration_ms = 80,  .velocity = 0,   .reserved = 0 },
  { .midi_note = 76, .waveform = SpeakerWaveformSquare, .duration_ms = 220, .velocity = 127, .reserved = 0 },
};

static const char *alarm_title_for_kind(AlarmKind kind) {
  return kind == ALARM_KIND_SELF_LOCATE ? "SOS active" : "Incoming SOS";
}

static void refresh_alarm_title(void) {
  if (s_text_layer) {
    text_layer_set_text(s_text_layer, alarm_title_for_kind(s_alarm_kind));
  }
}

static void try_play_alarm_sound(void) {
  if (!s_alarm_active || !s_sound_enabled) {
    return;
  }
  if (speaker_is_muted()) {
    return;
  }
  /* Classic SDK stubs are macros that expand to (0) and discard args. */
  const SpeakerNote *notes = s_alarm_notes;
  const uint32_t num_notes = ARRAY_LENGTH(s_alarm_notes);
  (void)speaker_stop();
  (void)speaker_play_notes(notes, num_notes, SOUND_VOLUME);
  (void)notes;
  (void)num_notes;
}

static void stop_vibe(void) {
  if (s_vibe_timer) {
    app_timer_cancel(s_vibe_timer);
    s_vibe_timer = NULL;
  }
  vibes_cancel();
}

static void vibe_timer_callback(void *context) {
  (void)context;
  vibes_long_pulse();
  try_play_alarm_sound();
  s_vibe_timer = app_timer_register(VIBE_INTERVAL_MS, vibe_timer_callback, NULL);
}

static void start_vibe(void) {
  stop_vibe();
  vibes_long_pulse();
  s_vibe_timer = app_timer_register(VIBE_INTERVAL_MS, vibe_timer_callback, NULL);
}

static void suppress_timer_callback(void *context) {
  (void)context;
  s_suppress_timer = NULL;
  s_suppress_inbound = false;
}

static void cancel_inbound_suppress(void) {
  if (s_suppress_timer) {
    app_timer_cancel(s_suppress_timer);
    s_suppress_timer = NULL;
  }
  s_suppress_inbound = false;
}

static void start_inbound_suppress(void) {
  cancel_inbound_suppress();
  s_suppress_inbound = true;
  s_suppress_timer = app_timer_register(INBOUND_SUPPRESS_MS, suppress_timer_callback, NULL);
}

static void dismiss_alarm(void) {
  if (!s_alarm_active) {
    return;
  }
  s_alarm_active = false;
  stop_vibe();
  (void)speaker_stop();
  light_enable(false);
  start_inbound_suppress();

  if (s_alarm_window && window_stack_contains_window(s_alarm_window)) {
    window_stack_remove(s_alarm_window, true);
  }
  ui_show_idle();
}

static void select_click_handler(ClickRecognizerRef recognizer, void *context) {
  (void)recognizer;
  (void)context;
  dismiss_alarm();
}

static void back_click_handler(ClickRecognizerRef recognizer, void *context) {
  (void)recognizer;
  (void)context;
  dismiss_alarm();
}

static void click_config_provider(void *context) {
  (void)context;
  window_single_click_subscribe(BUTTON_ID_SELECT, select_click_handler);
  window_single_click_subscribe(BUTTON_ID_BACK, back_click_handler);
}

static void window_load(Window *window) {
  Layer *window_layer = window_get_root_layer(window);
  GRect bounds = layer_get_bounds(window_layer);

  s_text_layer = text_layer_create(GRect(4, bounds.size.h / 2 - 20, bounds.size.w - 8, 40));
  text_layer_set_text(s_text_layer, alarm_title_for_kind(s_alarm_kind));
  text_layer_set_text_alignment(s_text_layer, GTextAlignmentCenter);
  text_layer_set_font(s_text_layer, fonts_get_system_font(FONT_KEY_GOTHIC_24_BOLD));
  text_layer_set_overflow_mode(s_text_layer, GTextOverflowModeWordWrap);
  layer_add_child(window_layer, text_layer_get_layer(s_text_layer));
}

static void window_unload(Window *window) {
  (void)window;
  if (s_text_layer) {
    text_layer_destroy(s_text_layer);
    s_text_layer = NULL;
  }
}

void alarm_set_sound_enabled(bool enabled) {
  s_sound_enabled = enabled;
  persist_write_bool(PERSIST_KEY_WATCH_ALARM_SOUND, enabled);
  if (!enabled) {
    (void)speaker_stop();
  }
}

bool alarm_get_sound_enabled(void) {
  return s_sound_enabled;
}

void alarm_persist_load(void) {
  if (persist_exists(PERSIST_KEY_WATCH_ALARM_SOUND)) {
    s_sound_enabled = persist_read_bool(PERSIST_KEY_WATCH_ALARM_SOUND);
  } else {
    s_sound_enabled = true;
  }
}

void alarm_init(void) {
  s_alarm_active = false;
  s_suppress_inbound = false;
  s_vibe_timer = NULL;
  s_suppress_timer = NULL;
  s_text_layer = NULL;
  s_alarm_kind = ALARM_KIND_INBOUND;
  alarm_persist_load();

  s_alarm_window = window_create();
  window_set_window_handlers(s_alarm_window, (WindowHandlers) {
    .load = window_load,
    .unload = window_unload,
  });
  window_set_click_config_provider(s_alarm_window, click_config_provider);
}

void alarm_deinit(void) {
  stop_vibe();
  (void)speaker_stop();
  light_enable(false);
  s_alarm_active = false;
  cancel_inbound_suppress();

  if (s_alarm_window) {
    if (window_stack_contains_window(s_alarm_window)) {
      window_stack_remove(s_alarm_window, false);
    }
    window_destroy(s_alarm_window);
    s_alarm_window = NULL;
  }
}

static void alarm_start(AlarmKind kind) {
  if (!s_alarm_window || s_suppress_inbound) {
    return;
  }

  s_alarm_kind = kind;
  light_enable(true);
  start_vibe();

  if (s_alarm_active && window_stack_contains_window(s_alarm_window)) {
    refresh_alarm_title();
    try_play_alarm_sound();
    return;
  }

  s_alarm_active = true;
  try_play_alarm_sound();
  window_stack_push(s_alarm_window, true);
  refresh_alarm_title();
}

void alarm_handle_inbound(void) {
  alarm_start(ALARM_KIND_INBOUND);
}

void alarm_handle_self_locate(void) {
  alarm_start(ALARM_KIND_SELF_LOCATE);
}
