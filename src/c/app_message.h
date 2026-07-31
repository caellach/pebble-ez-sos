#pragma once

#include <pebble.h>

void ez_app_message_init(void);
void ez_app_message_deinit(void);
void ez_app_message_send_sos_request(void);
bool ez_app_message_is_awaiting_status(void);
