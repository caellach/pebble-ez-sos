#pragma once

#include <pebble.h>

typedef enum {
  TRIGGER_MODE_SINGLE = 0,
  TRIGGER_MODE_CONFIRM = 1,
  TRIGGER_MODE_HOLD = 2,
} TriggerMode;

#define TRIGGER_MODE_DEFAULT TRIGGER_MODE_CONFIRM
#define HOLD_MS_DEFAULT 1500
#define HOLD_MS_MIN 500
#define HOLD_MS_MAX 5000

void triggers_init(Window *window);
void triggers_deinit(void);
void triggers_set_mode(TriggerMode mode);
TriggerMode triggers_get_mode(void);
bool triggers_set_mode_from_string(const char *mode);
void triggers_set_hold_ms(uint32_t hold_ms);
uint32_t triggers_get_hold_ms(void);
const char *triggers_mode_hint(void);
void triggers_persist_load(void);
void triggers_persist_save(void);
