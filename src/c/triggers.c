#include "triggers.h"

#include "app_message.h"
#include "main.h"

#include <stdio.h>
#include <string.h>

#define PERSIST_KEY_TRIGGER_MODE 1
#define PERSIST_KEY_HOLD_MS 2

static Window *s_window;
static TriggerMode s_mode = TRIGGER_MODE_DEFAULT;
static uint32_t s_hold_ms = HOLD_MS_DEFAULT;
static bool s_confirm_pending;
static AppTimer *s_hold_timer;
static char s_hold_hint[28];

static void refresh_click_config(void);

static uint32_t clamp_hold_ms(int32_t ms) {
  if (ms < (int32_t)HOLD_MS_MIN) {
    return HOLD_MS_DEFAULT;
  }
  if (ms > (int32_t)HOLD_MS_MAX) {
    return HOLD_MS_MAX;
  }
  return (uint32_t)ms;
}

static void cancel_hold_timer(void) {
  if (s_hold_timer) {
    app_timer_cancel(s_hold_timer);
    s_hold_timer = NULL;
  }
}

static void fire_sos(void) {
  if (ez_app_message_is_awaiting_status()) {
    return;
  }
  s_confirm_pending = false;
  cancel_hold_timer();
  ui_hold_progress_cancel();
  ez_app_message_send_sos_request();
}

static void hold_timer_callback(void *context) {
  (void)context;
  s_hold_timer = NULL;
  fire_sos();
}

static void select_click_handler(ClickRecognizerRef recognizer, void *context) {
  (void)recognizer;
  (void)context;

  if (ez_app_message_is_awaiting_status()) {
    return;
  }

  switch (s_mode) {
    case TRIGGER_MODE_SINGLE:
      fire_sos();
      break;
    case TRIGGER_MODE_CONFIRM:
      if (!s_confirm_pending) {
        s_confirm_pending = true;
        ui_show_confirm();
      } else {
        fire_sos();
      }
      break;
    case TRIGGER_MODE_HOLD:
      break;
  }
}

static void select_down_handler(ClickRecognizerRef recognizer, void *context) {
  (void)recognizer;
  (void)context;

  if (s_mode != TRIGGER_MODE_HOLD || ez_app_message_is_awaiting_status()) {
    return;
  }
  cancel_hold_timer();
  ui_hold_progress_start(s_hold_ms);
  s_hold_timer = app_timer_register(s_hold_ms, hold_timer_callback, NULL);
}

static void select_up_handler(ClickRecognizerRef recognizer, void *context) {
  (void)recognizer;
  (void)context;

  if (s_mode != TRIGGER_MODE_HOLD) {
    return;
  }
  /* Released early — cancel without sending. */
  cancel_hold_timer();
  ui_hold_progress_cancel();
}

static void back_click_handler(ClickRecognizerRef recognizer, void *context) {
  (void)recognizer;
  (void)context;

  if (s_confirm_pending) {
    s_confirm_pending = false;
    ui_show_idle();
    return;
  }

  window_stack_pop(true);
}

static void click_config_provider(void *context) {
  (void)context;
  window_single_click_subscribe(BUTTON_ID_BACK, back_click_handler);

  if (s_mode == TRIGGER_MODE_HOLD) {
    window_raw_click_subscribe(BUTTON_ID_SELECT, select_down_handler, select_up_handler, NULL);
  } else {
    window_single_click_subscribe(BUTTON_ID_SELECT, select_click_handler);
  }
}

static void refresh_click_config(void) {
  if (s_window) {
    window_set_click_config_provider(s_window, click_config_provider);
  }
}

void triggers_init(Window *window) {
  s_window = window;
  s_confirm_pending = false;
  s_hold_timer = NULL;
  refresh_click_config();
}

void triggers_deinit(void) {
  cancel_hold_timer();
  ui_hold_progress_cancel();
  s_window = NULL;
  s_confirm_pending = false;
}

void triggers_set_mode(TriggerMode mode) {
  if (mode != TRIGGER_MODE_SINGLE && mode != TRIGGER_MODE_CONFIRM && mode != TRIGGER_MODE_HOLD) {
    mode = TRIGGER_MODE_DEFAULT;
  }
  s_mode = mode;
  s_confirm_pending = false;
  cancel_hold_timer();
  ui_hold_progress_cancel();
  triggers_persist_save();
  refresh_click_config();
  if (!ez_app_message_is_awaiting_status()) {
    ui_show_idle();
  }
}

TriggerMode triggers_get_mode(void) {
  return s_mode;
}

bool triggers_set_mode_from_string(const char *mode) {
  if (!mode) {
    return false;
  }
  if (strcmp(mode, "single") == 0) {
    triggers_set_mode(TRIGGER_MODE_SINGLE);
    return true;
  }
  if (strcmp(mode, "confirm") == 0) {
    triggers_set_mode(TRIGGER_MODE_CONFIRM);
    return true;
  }
  if (strcmp(mode, "hold") == 0) {
    triggers_set_mode(TRIGGER_MODE_HOLD);
    return true;
  }
  return false;
}

void triggers_set_hold_ms(uint32_t hold_ms) {
  uint32_t clamped = clamp_hold_ms((int32_t)hold_ms);
  if (clamped == s_hold_ms) {
    return;
  }
  s_hold_ms = clamped;
  cancel_hold_timer();
  ui_hold_progress_cancel();
  triggers_persist_save();
  if (s_mode == TRIGGER_MODE_HOLD && !ez_app_message_is_awaiting_status()) {
    ui_show_idle();
  }
}

uint32_t triggers_get_hold_ms(void) {
  return s_hold_ms;
}

const char *triggers_mode_hint(void) {
  switch (s_mode) {
    case TRIGGER_MODE_SINGLE:
      return "Press Select";
    case TRIGGER_MODE_HOLD: {
      unsigned secs = (unsigned)(s_hold_ms / 1000);
      unsigned tenths = (unsigned)((s_hold_ms % 1000) / 100);
      if (tenths == 0) {
        snprintf(s_hold_hint, sizeof(s_hold_hint), "Hold Select %us", secs);
      } else {
        snprintf(s_hold_hint, sizeof(s_hold_hint), "Hold Select %u.%us", secs, tenths);
      }
      return s_hold_hint;
    }
    case TRIGGER_MODE_CONFIRM:
    default:
      return "Press to confirm";
  }
}

void triggers_persist_load(void) {
  s_mode = TRIGGER_MODE_DEFAULT;
  if (persist_exists(PERSIST_KEY_TRIGGER_MODE)) {
    int32_t stored = persist_read_int(PERSIST_KEY_TRIGGER_MODE);
    if (stored == TRIGGER_MODE_SINGLE || stored == TRIGGER_MODE_CONFIRM || stored == TRIGGER_MODE_HOLD) {
      s_mode = (TriggerMode)stored;
    }
  }

  s_hold_ms = HOLD_MS_DEFAULT;
  if (persist_exists(PERSIST_KEY_HOLD_MS)) {
    s_hold_ms = clamp_hold_ms(persist_read_int(PERSIST_KEY_HOLD_MS));
  }
}

void triggers_persist_save(void) {
  persist_write_int(PERSIST_KEY_TRIGGER_MODE, (int32_t)s_mode);
  persist_write_int(PERSIST_KEY_HOLD_MS, (int32_t)s_hold_ms);
}
