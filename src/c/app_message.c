#include "app_message.h"

#include "alarm.h"
#include "main.h"
#include "triggers.h"

#include <string.h>

#define APP_MESSAGE_INBOX_SIZE 1024
#define APP_MESSAGE_OUTBOX_SIZE 1024
#define OUTBOX_RETRY_MS 300
#define STATUS_TIMEOUT_MS 25000

/* Persist: key 1 is TRIGGER_MODE in triggers.c. Settings use 20+. */
#define PERSIST_KEY_SETTINGS_COUNT 20
#define PERSIST_KEY_SETTINGS_BASE 21
#define PERSIST_SETTINGS_CHUNK_LEN 255 /* persist_write_string max incl. NUL => 255 chars */
#define PERSIST_SETTINGS_MAX_CHUNKS 24
#define SETTINGS_BUF_SIZE (PERSIST_SETTINGS_CHUNK_LEN * PERSIST_SETTINGS_MAX_CHUNKS)
#define APPMSG_CHUNK_DATA_MAX 800
#define APPMSG_MAX_CHUNKS ((SETTINGS_BUF_SIZE + APPMSG_CHUNK_DATA_MAX - 1) / APPMSG_CHUNK_DATA_MAX)

static bool s_awaiting_status;
static bool s_outbox_retried;
static AppTimer *s_retry_timer;
static AppTimer *s_status_timeout_timer;

/* Shared buffer: inbound assemble, persist staging, SETTINGS_REQUEST reply */
static char s_settings_buf[SETTINGS_BUF_SIZE];

/* Inbound settings assembly (PKJS → watch); PKJS uses fixed CHUNK_SIZE except last */
static int s_asm_count;
static int s_asm_received;
static bool s_asm_slot[APPMSG_MAX_CHUNKS];
static uint16_t s_asm_part_len[APPMSG_MAX_CHUNKS];

/* Outbound settings reply (watch → Android) */
static bool s_reply_active;
static int s_reply_index;
static int s_reply_count;
static int s_reply_len;

static void cancel_retry_timer(void) {
  if (s_retry_timer) {
    app_timer_cancel(s_retry_timer);
    s_retry_timer = NULL;
  }
}

static void cancel_status_timeout(void) {
  if (s_status_timeout_timer) {
    app_timer_cancel(s_status_timeout_timer);
    s_status_timeout_timer = NULL;
  }
}

static void clear_awaiting(void) {
  s_awaiting_status = false;
  s_outbox_retried = false;
  cancel_retry_timer();
  cancel_status_timeout();
}

static const char *status_code_to_text(const char *code) {
  if (!code) {
    return "Phone offline";
  }
  if (strcmp(code, "accepted") == 0) {
    return "Sending…";
  }
  if (strcmp(code, "sent") == 0) {
    return "Sent";
  }
  if (strcmp(code, "check_phone") == 0) {
    return "Check phone";
  }
  if (strcmp(code, "no_contacts") == 0) {
    return "No contacts";
  }
  if (strcmp(code, "no_gps") == 0) {
    return "No GPS";
  }
  if (strcmp(code, "offline") == 0) {
    return "Phone offline";
  }
  return NULL;
}

static void status_timeout_callback(void *context) {
  (void)context;
  s_status_timeout_timer = NULL;
  if (!s_awaiting_status) {
    return;
  }
  clear_awaiting();
  ui_set_status("Phone offline");
}

static void start_status_timeout(void) {
  cancel_status_timeout();
  s_status_timeout_timer = app_timer_register(STATUS_TIMEOUT_MS, status_timeout_callback, NULL);
}

static bool send_sos_outbox(void) {
  DictionaryIterator *iter;
  AppMessageResult begin_result = app_message_outbox_begin(&iter);
  if (begin_result != APP_MSG_OK || iter == NULL) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "Outbox begin failed: %d", (int)begin_result);
    return false;
  }

  dict_write_uint8(iter, MESSAGE_KEY_SOS_REQUEST, 1);
  AppMessageResult send_result = app_message_outbox_send();
  if (send_result != APP_MSG_OK) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "Outbox send failed: %d", (int)send_result);
    return false;
  }
  return true;
}

static void retry_timer_callback(void *context) {
  (void)context;
  s_retry_timer = NULL;
  if (!s_awaiting_status) {
    return;
  }
  if (!send_sos_outbox()) {
    clear_awaiting();
    ui_set_status("Phone offline");
  }
}

static void handle_outbox_failure(void) {
  if (s_reply_active) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "Settings reply outbox failed");
    s_reply_active = false;
    return;
  }
  if (!s_awaiting_status) {
    return;
  }
  if (!s_outbox_retried) {
    s_outbox_retried = true;
    cancel_retry_timer();
    s_retry_timer = app_timer_register(OUTBOX_RETRY_MS, retry_timer_callback, NULL);
    return;
  }
  clear_awaiting();
  ui_set_status("Phone offline");
}

static void clear_assembly(void) {
  for (int i = 0; i < APPMSG_MAX_CHUNKS; i++) {
    s_asm_slot[i] = false;
    s_asm_part_len[i] = 0;
  }
  s_asm_count = 0;
  s_asm_received = 0;
}

static int tuple_as_int(Tuple *t) {
  if (!t) {
    return 0;
  }
  if (t->type == TUPLE_INT) {
    return (int)t->value->int32;
  }
  if (t->type == TUPLE_UINT) {
    return (int)t->value->uint32;
  }
  return (int)t->value->int32;
}

static void settings_persist_clear(void) {
  if (persist_exists(PERSIST_KEY_SETTINGS_COUNT)) {
    int32_t old_count = persist_read_int(PERSIST_KEY_SETTINGS_COUNT);
    if (old_count < 0) {
      old_count = 0;
    }
    if (old_count > PERSIST_SETTINGS_MAX_CHUNKS) {
      old_count = PERSIST_SETTINGS_MAX_CHUNKS;
    }
    for (int i = 0; i < old_count; i++) {
      persist_delete(PERSIST_KEY_SETTINGS_BASE + i);
    }
    persist_delete(PERSIST_KEY_SETTINGS_COUNT);
  }
}

static bool settings_persist_write(const char *json, int len) {
  if (!json || len < 0) {
    return false;
  }
  if (len > SETTINGS_BUF_SIZE - 1) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "Settings too large to persist (%d)", len);
    return false;
  }

  int chunks = (len + PERSIST_SETTINGS_CHUNK_LEN - 1) / PERSIST_SETTINGS_CHUNK_LEN;
  if (len == 0) {
    chunks = 1;
  }
  if (chunks > PERSIST_SETTINGS_MAX_CHUNKS) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "Settings need too many persist chunks (%d)", chunks);
    return false;
  }

  settings_persist_clear();

  for (int i = 0; i < chunks; i++) {
    int offset = i * PERSIST_SETTINGS_CHUNK_LEN;
    int n = len - offset;
    if (n > PERSIST_SETTINGS_CHUNK_LEN) {
      n = PERSIST_SETTINGS_CHUNK_LEN;
    }
    char piece[PERSIST_SETTINGS_CHUNK_LEN + 1];
    if (n > 0) {
      memcpy(piece, json + offset, (size_t)n);
    }
    piece[n] = '\0';
    status_t st = persist_write_string(PERSIST_KEY_SETTINGS_BASE + i, piece);
    if (st < 0) {
      APP_LOG(APP_LOG_LEVEL_ERROR, "persist_write_string failed: %d", (int)st);
      settings_persist_clear();
      return false;
    }
  }
  persist_write_int(PERSIST_KEY_SETTINGS_COUNT, chunks);
  APP_LOG(APP_LOG_LEVEL_INFO, "Persisted settings (%d bytes, %d chunks)", len, chunks);
  return true;
}

static int settings_persist_read(char *out, int out_size) {
  if (!out || out_size <= 0) {
    return -1;
  }
  if (!persist_exists(PERSIST_KEY_SETTINGS_COUNT)) {
    out[0] = '\0';
    return 0;
  }
  int32_t chunks = persist_read_int(PERSIST_KEY_SETTINGS_COUNT);
  if (chunks <= 0) {
    out[0] = '\0';
    return 0;
  }
  if (chunks > PERSIST_SETTINGS_MAX_CHUNKS) {
    chunks = PERSIST_SETTINGS_MAX_CHUNKS;
  }

  int written = 0;
  for (int i = 0; i < chunks; i++) {
    uint32_t key = PERSIST_KEY_SETTINGS_BASE + i;
    if (!persist_exists(key)) {
      APP_LOG(APP_LOG_LEVEL_WARNING, "Missing settings persist chunk %d", i);
      break;
    }
    char piece[PERSIST_SETTINGS_CHUNK_LEN + 1];
    int n = persist_read_string(key, piece, sizeof(piece));
    if (n < 0) {
      APP_LOG(APP_LOG_LEVEL_ERROR, "persist_read_string failed: %d", n);
      return -1;
    }
    int payload = n > 0 ? n - 1 : 0;
    if (written + payload + 1 > out_size) {
      APP_LOG(APP_LOG_LEVEL_ERROR, "Settings buffer too small");
      return -1;
    }
    if (payload > 0) {
      memcpy(out + written, piece, (size_t)payload);
      written += payload;
    }
  }
  out[written] = '\0';
  return written;
}

static bool finalize_assembly(void) {
  if (s_asm_count <= 0 || s_asm_received < s_asm_count) {
    return false;
  }
  for (int i = 0; i < s_asm_count; i++) {
    if (!s_asm_slot[i]) {
      return false;
    }
  }

  int total = 0;
  for (int i = 0; i < s_asm_count; i++) {
    total += (int)s_asm_part_len[i];
  }
  if (total >= SETTINGS_BUF_SIZE) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "Assembled settings too large");
    clear_assembly();
    return false;
  }

  /* Compact fixed-slot layout (index * APPMSG_CHUNK_DATA_MAX) into contiguous JSON. */
  int write_pos = (int)s_asm_part_len[0];
  for (int i = 1; i < s_asm_count; i++) {
    int src = i * APPMSG_CHUNK_DATA_MAX;
    int n = (int)s_asm_part_len[i];
    if (src != write_pos && n > 0) {
      memmove(s_settings_buf + write_pos, s_settings_buf + src, (size_t)n);
    }
    write_pos += n;
  }
  s_settings_buf[write_pos] = '\0';

  bool ok = settings_persist_write(s_settings_buf, write_pos);
  clear_assembly();
  return ok;
}

static void handle_settings_json(Tuple *tuple) {
  if (!tuple || tuple->type != TUPLE_CSTRING) {
    return;
  }
  const char *json = tuple->value->cstring;
  int len = (int)strlen(json);
  if (len >= SETTINGS_BUF_SIZE) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "SETTINGS_JSON too large");
    return;
  }
  memcpy(s_settings_buf, json, (size_t)len + 1);
  settings_persist_write(s_settings_buf, len);
}

static void handle_settings_chunks(DictionaryIterator *iterator) {
  Tuple *index_t = dict_find(iterator, MESSAGE_KEY_SETTINGS_CHUNK_INDEX);
  Tuple *data_t = dict_find(iterator, MESSAGE_KEY_SETTINGS_CHUNK_DATA);
  Tuple *count_t = dict_find(iterator, MESSAGE_KEY_SETTINGS_CHUNK_COUNT);

  if (!index_t && !data_t && !count_t) {
    return;
  }
  if (!index_t || !data_t || !count_t) {
    APP_LOG(APP_LOG_LEVEL_WARNING, "Incomplete SETTINGS_CHUNK message");
    return;
  }

  if (data_t->type != TUPLE_CSTRING) {
    APP_LOG(APP_LOG_LEVEL_WARNING, "SETTINGS_CHUNK_DATA not string");
    return;
  }

  int count = tuple_as_int(count_t);
  int index = tuple_as_int(index_t);

  if (count <= 0 || count > APPMSG_MAX_CHUNKS) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "Invalid SETTINGS_CHUNK_COUNT %d", count);
    return;
  }
  if (index < 0 || index >= count) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "Invalid SETTINGS_CHUNK_INDEX %d", index);
    return;
  }

  if (s_reply_active) {
    APP_LOG(APP_LOG_LEVEL_WARNING, "Dropping inbound settings while reply active");
    return;
  }

  if (s_asm_count != count) {
    clear_assembly();
    s_asm_count = count;
  }

  size_t len = strlen(data_t->value->cstring);
  if (len > APPMSG_CHUNK_DATA_MAX) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "SETTINGS_CHUNK_DATA too long");
    return;
  }

  int dest = index * APPMSG_CHUNK_DATA_MAX;
  if (dest + (int)len >= SETTINGS_BUF_SIZE) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "SETTINGS_CHUNK overflows buffer");
    clear_assembly();
    return;
  }

  if (!s_asm_slot[index]) {
    s_asm_received++;
  }
  memcpy(s_settings_buf + dest, data_t->value->cstring, len);
  s_settings_buf[dest + (int)len] = '\0';
  s_asm_part_len[index] = (uint16_t)len;
  s_asm_slot[index] = true;

  APP_LOG(APP_LOG_LEVEL_INFO, "SETTINGS_CHUNK %d/%d (%u bytes)", index + 1, count, (unsigned)len);

  if (s_asm_received >= s_asm_count) {
    finalize_assembly();
  }
}

static bool send_settings_reply_chunk(void) {
  if (!s_reply_active) {
    return false;
  }
  if (s_reply_index >= s_reply_count) {
    s_reply_active = false;
    return true;
  }

  int offset = s_reply_index * APPMSG_CHUNK_DATA_MAX;
  int remaining = s_reply_len - offset;
  if (remaining < 0) {
    remaining = 0;
  }
  int n = remaining > APPMSG_CHUNK_DATA_MAX ? APPMSG_CHUNK_DATA_MAX : remaining;

  DictionaryIterator *iter;
  AppMessageResult begin_result = app_message_outbox_begin(&iter);
  if (begin_result != APP_MSG_OK || iter == NULL) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "Settings reply begin failed: %d", (int)begin_result);
    s_reply_active = false;
    return false;
  }

  /* Temporarily NUL-terminate this slice inside the shared buffer. */
  char saved = s_settings_buf[offset + n];
  s_settings_buf[offset + n] = '\0';

  dict_write_int32(iter, MESSAGE_KEY_SETTINGS_CHUNK_INDEX, s_reply_index);
  dict_write_int32(iter, MESSAGE_KEY_SETTINGS_CHUNK_COUNT, s_reply_count);
  dict_write_cstring(iter, MESSAGE_KEY_SETTINGS_CHUNK_DATA, s_settings_buf + offset);
  s_settings_buf[offset + n] = saved;

  AppMessageResult send_result = app_message_outbox_send();
  if (send_result != APP_MSG_OK) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "Settings reply send failed: %d", (int)send_result);
    s_reply_active = false;
    return false;
  }
  return true;
}

static void handle_settings_request(void) {
  if (s_reply_active) {
    APP_LOG(APP_LOG_LEVEL_WARNING, "SETTINGS_REQUEST ignored; reply in progress");
    return;
  }
  if (s_awaiting_status) {
    APP_LOG(APP_LOG_LEVEL_WARNING, "SETTINGS_REQUEST deferred; SOS in flight");
    return;
  }

  clear_assembly();

  int len = settings_persist_read(s_settings_buf, SETTINGS_BUF_SIZE);
  if (len < 0) {
    s_settings_buf[0] = '\0';
    len = 0;
  }
  s_reply_len = len;
  s_reply_count = (len + APPMSG_CHUNK_DATA_MAX - 1) / APPMSG_CHUNK_DATA_MAX;
  if (s_reply_count <= 0) {
    s_reply_count = 1;
  }
  s_reply_index = 0;
  s_reply_active = true;

  APP_LOG(APP_LOG_LEVEL_INFO, "SETTINGS_REQUEST: replying %d bytes in %d chunks", len, s_reply_count);
  if (!send_settings_reply_chunk()) {
    s_reply_active = false;
  }
}

static void outbox_failed_callback(DictionaryIterator *iterator, AppMessageResult reason, void *context) {
  (void)iterator;
  (void)context;
  APP_LOG(APP_LOG_LEVEL_ERROR, "Outbox failed: %d", (int)reason);
  handle_outbox_failure();
}

static void outbox_sent_callback(DictionaryIterator *iterator, void *context) {
  (void)iterator;
  (void)context;
  if (s_reply_active) {
    s_reply_index++;
    if (s_reply_index >= s_reply_count) {
      APP_LOG(APP_LOG_LEVEL_INFO, "Settings reply complete");
      s_reply_active = false;
      return;
    }
    if (!send_settings_reply_chunk()) {
      s_reply_active = false;
    }
    return;
  }
  APP_LOG(APP_LOG_LEVEL_INFO, "Outbox send success");
}

static void handle_status_tuple(Tuple *tuple) {
  if (!tuple || tuple->type != TUPLE_CSTRING) {
    return;
  }
  /* Final STATUS wins once (companion `sent` must not be overwritten by late PKJS).
   * Interim `accepted` only confirms the phone queued the request. */
  if (!s_awaiting_status) {
    APP_LOG(APP_LOG_LEVEL_INFO, "Ignoring STATUS (not awaiting): %s", tuple->value->cstring);
    return;
  }
  const char *code = tuple->value->cstring;
  const char *text = status_code_to_text(code);
  if (!text) {
    APP_LOG(APP_LOG_LEVEL_WARNING, "Unknown STATUS: %s", code);
    return;
  }
  if (strcmp(code, "accepted") == 0) {
    ui_set_status(text);
    start_status_timeout();
    return;
  }
  clear_awaiting();
  ui_set_status(text);
}

static void handle_trigger_mode_tuple(Tuple *tuple) {
  if (!tuple || tuple->type != TUPLE_CSTRING) {
    return;
  }
  if (!triggers_set_mode_from_string(tuple->value->cstring)) {
    APP_LOG(APP_LOG_LEVEL_WARNING, "Unknown TRIGGER_MODE: %s", tuple->value->cstring);
  }
}

static void handle_hold_ms_tuple(Tuple *tuple) {
  if (!tuple) {
    return;
  }
  int32_t ms = 0;
  if (tuple->type == TUPLE_INT) {
    ms = tuple->value->int32;
  } else if (tuple->type == TUPLE_UINT) {
    ms = (int32_t)tuple->value->uint32;
  } else {
    return;
  }
  triggers_set_hold_ms((uint32_t)ms);
}

static void handle_watch_alarm_sound_tuple(Tuple *tuple) {
  if (!tuple) {
    return;
  }
  bool enabled = true;
  if (tuple->type == TUPLE_UINT) {
    enabled = tuple->value->uint8 != 0;
  } else if (tuple->type == TUPLE_INT) {
    enabled = tuple->value->int32 != 0;
  } else {
    return;
  }
  alarm_set_sound_enabled(enabled);
}

static void inbox_received_callback(DictionaryIterator *iterator, void *context) {
  (void)context;

  Tuple *status_t = dict_find(iterator, MESSAGE_KEY_STATUS);
  if (status_t) {
    handle_status_tuple(status_t);
  }

  Tuple *mode_t = dict_find(iterator, MESSAGE_KEY_TRIGGER_MODE);
  if (mode_t) {
    handle_trigger_mode_tuple(mode_t);
  }

  Tuple *hold_t = dict_find(iterator, MESSAGE_KEY_HOLD_MS);
  if (hold_t) {
    handle_hold_ms_tuple(hold_t);
  }

  Tuple *sound_t = dict_find(iterator, MESSAGE_KEY_WATCH_ALARM_SOUND);
  if (sound_t) {
    handle_watch_alarm_sound_tuple(sound_t);
  }

  Tuple *inbound_t = dict_find(iterator, MESSAGE_KEY_INBOUND_ALERT);
  if (inbound_t) {
    alarm_handle_inbound();
  }

  Tuple *self_t = dict_find(iterator, MESSAGE_KEY_SELF_LOCATE_ALERT);
  if (self_t) {
    alarm_handle_self_locate();
  }

  Tuple *settings_t = dict_find(iterator, MESSAGE_KEY_SETTINGS_JSON);
  if (settings_t) {
    handle_settings_json(settings_t);
  }

  handle_settings_chunks(iterator);

  Tuple *req_t = dict_find(iterator, MESSAGE_KEY_SETTINGS_REQUEST);
  if (req_t) {
    handle_settings_request();
  }

  Tuple *companion_t = dict_find(iterator, MESSAGE_KEY_COMPANION_PRESENT);
  if (companion_t) {
    /* Relay to PKJS — Android→watch AppMessages are not delivered to JS directly. */
    if (!s_awaiting_status && !s_reply_active) {
      DictionaryIterator *out;
      if (app_message_outbox_begin(&out) == APP_MSG_OK && out != NULL) {
        dict_write_uint8(out, MESSAGE_KEY_COMPANION_PRESENT, 1);
        if (app_message_outbox_send() != APP_MSG_OK) {
          APP_LOG(APP_LOG_LEVEL_WARNING, "Failed to echo COMPANION_PRESENT");
        }
      }
    }
  }
}

static void inbox_dropped_callback(AppMessageResult reason, void *context) {
  (void)context;
  APP_LOG(APP_LOG_LEVEL_ERROR, "Inbox dropped: %d", (int)reason);
}

void ez_app_message_init(void) {
  s_awaiting_status = false;
  s_outbox_retried = false;
  s_retry_timer = NULL;
  s_status_timeout_timer = NULL;
  s_reply_active = false;
  s_reply_index = 0;
  s_reply_count = 0;
  s_reply_len = 0;
  clear_assembly();

  app_message_register_inbox_received(inbox_received_callback);
  app_message_register_inbox_dropped(inbox_dropped_callback);
  app_message_register_outbox_failed(outbox_failed_callback);
  app_message_register_outbox_sent(outbox_sent_callback);
  app_message_open(APP_MESSAGE_INBOX_SIZE, APP_MESSAGE_OUTBOX_SIZE);
}

void ez_app_message_deinit(void) {
  clear_awaiting();
  clear_assembly();
  s_reply_active = false;
}

void ez_app_message_send_sos_request(void) {
  vibes_short_pulse();
  ui_set_status("Sending…");

  s_awaiting_status = true;
  s_outbox_retried = false;
  cancel_retry_timer();
  start_status_timeout();

  if (!send_sos_outbox()) {
    /* Treat immediate begin/send failure like outbox failed: retry once. */
    handle_outbox_failure();
  }
}

bool ez_app_message_is_awaiting_status(void) {
  return s_awaiting_status;
}
