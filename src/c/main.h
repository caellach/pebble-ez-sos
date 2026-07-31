#pragma once

#include <pebble.h>

void ui_init(void);
void ui_deinit(void);
void ui_show_idle(void);
void ui_show_confirm(void);
void ui_set_status(const char *status_text);
void ui_hold_progress_start(uint32_t duration_ms);
void ui_hold_progress_cancel(void);
Window *ui_window(void);
