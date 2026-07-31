#pragma once

#include <pebble.h>

void alarm_init(void);
void alarm_deinit(void);
void alarm_handle_inbound(void);
void alarm_handle_self_locate(void);
void alarm_set_sound_enabled(bool enabled);
bool alarm_get_sound_enabled(void);
void alarm_persist_load(void);
