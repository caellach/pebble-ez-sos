#include "main.h"

#include "alarm.h"
#include "app_message.h"
#include "triggers.h"

#define HOLD_PROGRESS_TICK_MS 50

static Window *s_window;
static Layer *s_progress_layer;
static TextLayer *s_title_layer;
static TextLayer *s_status_layer;

static AppTimer *s_progress_timer;
static uint32_t s_hold_duration_ms;
static uint32_t s_hold_elapsed_ms;
static int32_t s_progress_permille; /* 0–1000 */

static void cancel_progress_timer(void) {
  if (s_progress_timer) {
    app_timer_cancel(s_progress_timer);
    s_progress_timer = NULL;
  }
}

static void progress_layer_update(Layer *layer, GContext *ctx) {
  if (s_progress_permille <= 0 || !layer) {
    return;
  }

  GRect bounds = layer_get_bounds(layer);
  /* Keep ring + stroke inside the visible bezel (~90% of the prior diameter). */
  const int inset = PBL_IF_ROUND_ELSE(18, 16);
  GRect oval = grect_inset(bounds, GEdgeInsets(inset));

  graphics_context_set_stroke_color(ctx, GColorBlack);
  graphics_context_set_stroke_width(ctx, 5);

  /* Clockwise from 12 o'clock (angle 0 is top for graphics_draw_arc). */
  const int32_t start = 0;
  const int32_t end = (TRIG_MAX_ANGLE * s_progress_permille) / 1000;
  graphics_draw_arc(ctx, oval, GOvalScaleModeFitCircle, start, end);
}

static void progress_tick(void *context) {
  (void)context;
  s_progress_timer = NULL;

  if (s_hold_duration_ms == 0) {
    return;
  }

  s_hold_elapsed_ms += HOLD_PROGRESS_TICK_MS;
  if (s_hold_elapsed_ms >= s_hold_duration_ms) {
    s_progress_permille = 1000;
  } else {
    s_progress_permille = (int32_t)((s_hold_elapsed_ms * 1000) / s_hold_duration_ms);
  }

  if (s_progress_layer) {
    layer_mark_dirty(s_progress_layer);
  }

  if (s_progress_permille < 1000) {
    s_progress_timer = app_timer_register(HOLD_PROGRESS_TICK_MS, progress_tick, NULL);
  }
}

void ui_hold_progress_start(uint32_t duration_ms) {
  cancel_progress_timer();
  s_hold_duration_ms = duration_ms > 0 ? duration_ms : 1500;
  s_hold_elapsed_ms = 0;
  s_progress_permille = 0;
  if (s_progress_layer) {
    layer_mark_dirty(s_progress_layer);
  }
  s_progress_timer = app_timer_register(HOLD_PROGRESS_TICK_MS, progress_tick, NULL);
}

void ui_hold_progress_cancel(void) {
  cancel_progress_timer();
  s_hold_duration_ms = 0;
  s_hold_elapsed_ms = 0;
  s_progress_permille = 0;
  if (s_progress_layer) {
    layer_mark_dirty(s_progress_layer);
  }
}

void ui_show_idle(void) {
  if (!s_title_layer || !s_status_layer) {
    return;
  }
  text_layer_set_text(s_title_layer, "EZ SOS");
  text_layer_set_text(s_status_layer, triggers_mode_hint());
}

void ui_show_confirm(void) {
  if (!s_title_layer || !s_status_layer) {
    return;
  }
  text_layer_set_text(s_title_layer, "EZ SOS");
  text_layer_set_text(s_status_layer, "Confirm SOS?");
}

void ui_set_status(const char *status_text) {
  if (!s_title_layer || !s_status_layer || !status_text) {
    return;
  }
  text_layer_set_text(s_title_layer, "EZ SOS");
  text_layer_set_text(s_status_layer, status_text);
}

Window *ui_window(void) {
  return s_window;
}

static void window_load(Window *window) {
  Layer *window_layer = window_get_root_layer(window);
  GRect bounds = layer_get_bounds(window_layer);

  s_progress_layer = layer_create(bounds);
  layer_set_update_proc(s_progress_layer, progress_layer_update);
  layer_add_child(window_layer, s_progress_layer);

  s_title_layer = text_layer_create(GRect(0, bounds.size.h / 2 - 36, bounds.size.w, 28));
  text_layer_set_text_alignment(s_title_layer, GTextAlignmentCenter);
  text_layer_set_font(s_title_layer, fonts_get_system_font(FONT_KEY_GOTHIC_24_BOLD));
  layer_add_child(window_layer, text_layer_get_layer(s_title_layer));

  s_status_layer = text_layer_create(GRect(4, bounds.size.h / 2 - 2, bounds.size.w - 8, 40));
  text_layer_set_text_alignment(s_status_layer, GTextAlignmentCenter);
  text_layer_set_font(s_status_layer, fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD));
  text_layer_set_overflow_mode(s_status_layer, GTextOverflowModeWordWrap);
  layer_add_child(window_layer, text_layer_get_layer(s_status_layer));

  ui_show_idle();
}

static void window_unload(Window *window) {
  (void)window;
  cancel_progress_timer();
  text_layer_destroy(s_status_layer);
  text_layer_destroy(s_title_layer);
  layer_destroy(s_progress_layer);
  s_status_layer = NULL;
  s_title_layer = NULL;
  s_progress_layer = NULL;
}

void ui_init(void) {
  triggers_persist_load();

  s_window = window_create();
  window_set_window_handlers(s_window, (WindowHandlers) {
    .load = window_load,
    .unload = window_unload,
  });

  triggers_init(s_window);
  alarm_init();
  ez_app_message_init();

  window_stack_push(s_window, true);
}

void ui_deinit(void) {
  ui_hold_progress_cancel();
  ez_app_message_deinit();
  alarm_deinit();
  triggers_deinit();
  window_destroy(s_window);
  s_window = NULL;
}

int main(void) {
  ui_init();
  app_event_loop();
  ui_deinit();
}
