/*
 * tapd — LiVoice's global trigger tap for macOS.
 *
 * A tiny event-tap daemon spawned by the Electron main process. It reports
 * trigger events as lines on stdout:
 *   "k <keycode> <0|1>"  trigger key down/up
 *   "m <button> <0|1>"   middle mouse button down/up
 *   "c <keycode> 1"      non-trigger key pressed while a modifier trigger is held
 *                        (cancels hold/latch so shortcuts like Opt+Left / Cmd+C
 *                         never misfire dictation)
 *
 * Uses device-dependent left/right modifier masks so Left and Right Option or
 * Command never desync. Self-heals if macOS disables the tap on timeout.
 * Exits cleanly via _exit(0) when stdin closes (parent death) or on SIGTERM.
 */
#include <ApplicationServices/ApplicationServices.h>
#include <IOKit/hidsystem/IOLLEvent.h>
#include <dispatch/dispatch.h>
#include <errno.h>
#include <limits.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

#define MAX_RULES 8

typedef struct {
  int code;
  int isModifier;
  int isCapsLock;
  CGEventFlags deviceMask;
  CGEventFlags pairMask;
  CGEventFlags genericMask;
  int isDown;
} KeyRule;

static KeyRule rules[MAX_RULES];
static int ruleCount = 0;
static CFMachPortRef g_tapPort = NULL;
static int g_lastAlphaShift = 0;

static void initRule(KeyRule *rule, int code) {
  rule->code = code;
  rule->isModifier = 0;
  rule->isCapsLock = 0;
  rule->deviceMask = 0;
  rule->pairMask = 0;
  rule->genericMask = 0;
  rule->isDown = 0;

  switch (code) {
    case 58: /* left Option */
      rule->isModifier = 1;
      rule->deviceMask = NX_DEVICELALTKEYMASK;
      rule->pairMask = NX_DEVICELALTKEYMASK | NX_DEVICERALTKEYMASK;
      rule->genericMask = kCGEventFlagMaskAlternate;
      break;
    case 61: /* right Option */
      rule->isModifier = 1;
      rule->deviceMask = NX_DEVICERALTKEYMASK;
      rule->pairMask = NX_DEVICELALTKEYMASK | NX_DEVICERALTKEYMASK;
      rule->genericMask = kCGEventFlagMaskAlternate;
      break;
    case 55: /* left Command */
      rule->isModifier = 1;
      rule->deviceMask = NX_DEVICELCMDKEYMASK;
      rule->pairMask = NX_DEVICELCMDKEYMASK | NX_DEVICERCMDKEYMASK;
      rule->genericMask = kCGEventFlagMaskCommand;
      break;
    case 54: /* right Command */
      rule->isModifier = 1;
      rule->deviceMask = NX_DEVICERCMDKEYMASK;
      rule->pairMask = NX_DEVICELCMDKEYMASK | NX_DEVICERCMDKEYMASK;
      rule->genericMask = kCGEventFlagMaskCommand;
      break;
    case 57: /* CapsLock */
      rule->isModifier = 1;
      rule->isCapsLock = 1;
      rule->genericMask = kCGEventFlagMaskAlphaShift;
      break;
    case 63: /* Fn / Globe */
      rule->isModifier = 1;
      rule->genericMask = kCGEventFlagMaskSecondaryFn;
      break;
    default:
      break;
  }
}

static int anyModifierDown(void) {
  for (int i = 0; i < ruleCount; i++) {
    if (rules[i].isModifier && !rules[i].isCapsLock && rules[i].isDown) {
      return 1;
    }
  }
  return 0;
}

static void emit(const char *kind, long value, int down) {
  printf("%s %ld %d\n", kind, value, down);
  fflush(stdout);
}

static CGEventRef tapCallback(CGEventTapProxy proxy, CGEventType type,
                              CGEventRef event, void *refcon) {
  (void)proxy;
  (void)refcon;
  if (type == kCGEventTapDisabledByTimeout || type == kCGEventTapDisabledByUserInput) {
    if (g_tapPort != NULL) CGEventTapEnable(g_tapPort, true);
    fprintf(stderr, "tapd: tap re-enabled after %u\n", type);
    return event;
  }

  if (type == kCGEventFlagsChanged) {
    int64_t kc = CGEventGetIntegerValueField(event, kCGKeyboardEventKeycode);
    CGEventFlags flags = CGEventGetFlags(event);
    for (int i = 0; i < ruleCount; i++) {
      if (!rules[i].isModifier || rules[i].code != kc) continue;
      if (rules[i].isCapsLock) {
        int alphaShift = (flags & kCGEventFlagMaskAlphaShift) != 0;
        if (alphaShift != g_lastAlphaShift) {
          g_lastAlphaShift = alphaShift;
          /* Emit a clean tap pulse so Caps Lock toggles dictation cleanly
           * instead of staying stuck down while the toggle bit is set. */
          emit("k", (long)kc, 1);
          emit("k", (long)kc, 0);
        }
        return event;
      }
      int down = 0;
      if (rules[i].deviceMask != 0 && (flags & rules[i].pairMask) != 0) {
        down = (flags & rules[i].deviceMask) != 0;
      } else {
        down = (flags & rules[i].genericMask) != 0;
      }
      if (down != rules[i].isDown) {
        rules[i].isDown = down;
        emit("k", (long)kc, down);
      }
      break;
    }
    return event;
  }

  if (type == kCGEventKeyDown || type == kCGEventKeyUp) {
    int64_t kc = CGEventGetIntegerValueField(event, kCGKeyboardEventKeycode);
    int matched = 0;
    for (int i = 0; i < ruleCount; i++) {
      if (rules[i].code == kc) {
        int down = (type == kCGEventKeyDown);
        rules[i].isDown = down;
        emit("k", (long)kc, down);
        matched = 1;
        break;
      }
    }
    if (!matched && type == kCGEventKeyDown && anyModifierDown()) {
      /* User pressed a real key while holding a modifier trigger (e.g. Cmd+C,
       * Opt+Left, Opt+Delete): notify TriggerEngine to cancel hold/latch. */
      emit("c", (long)kc, 1);
    }
    return event;
  }

  if (type == kCGEventOtherMouseDown || type == kCGEventOtherMouseUp) {
    int64_t button = CGEventGetIntegerValueField(event, kCGMouseEventButtonNumber);
    if (button == 2) { /* center/middle button (CGEvent numbering) */
      emit("m", (long)button, type == kCGEventOtherMouseDown);
    }
    return event;
  }
  return event;
}

static void watchdog(CFRunLoopTimerRef timer, void *info) {
  (void)timer;
  (void)info;
  if (g_tapPort != NULL && !CGEventTapIsEnabled(g_tapPort)) {
    CGEventTapEnable(g_tapPort, true);
    fprintf(stderr, "tapd: watchdog re-enabled tap\n");
  }
}

/* Exit when the parent closes our stdin (parent crash/quit safety). */
static void *stdinWatch(void *unused) {
  (void)unused;
  char buf[1];
  for (;;) {
    ssize_t n = read(STDIN_FILENO, buf, 1);
    if (n > 0) continue;
    if (n < 0 && errno == EINTR) continue;
    break;
  }
  _exit(0);
  return NULL;
}

int main(int argc, char **argv) {
  for (int i = 1; i < argc && ruleCount < MAX_RULES; i++) {
    char *end = NULL;
    errno = 0;
    long parsed = strtol(argv[i], &end, 10);
    if (errno != 0 || end == argv[i] || *end != '\0' || parsed < 0 || parsed > 255) {
      fprintf(stderr, "tapd: invalid keycode '%s'\n", argv[i]);
      return 2;
    }
    initRule(&rules[ruleCount], (int)parsed);
    ruleCount++;
  }
  if (ruleCount == 0) {
    fprintf(stderr, "tapd: no trigger keycodes given\n");
    return 2;
  }

  g_lastAlphaShift =
      (CGEventSourceFlagsState(kCGEventSourceStateCombinedSessionState) & kCGEventFlagMaskAlphaShift) != 0;

  CGEventMask mask = CGEventMaskBit(kCGEventFlagsChanged) |
                     CGEventMaskBit(kCGEventKeyDown) |
                     CGEventMaskBit(kCGEventKeyUp) |
                     CGEventMaskBit(kCGEventOtherMouseDown) |
                     CGEventMaskBit(kCGEventOtherMouseUp);
  g_tapPort = CGEventTapCreate(kCGSessionEventTap, kCGHeadInsertEventTap,
                               kCGEventTapOptionDefault, mask, tapCallback, NULL);
  if (g_tapPort == NULL) {
    fprintf(stderr, "tapd: CGEventTapCreate failed (Accessibility grant missing?)\n");
    return 1;
  }
  CGEventTapEnable(g_tapPort, true);

  CFRunLoopSourceRef source =
      CFMachPortCreateRunLoopSource(kCFAllocatorDefault, g_tapPort, 0);
  CFRunLoopAddSource(CFRunLoopGetCurrent(), source, kCFRunLoopDefaultMode);
  CFRelease(source);

  CFRunLoopTimerContext ctx = {0, NULL, NULL, NULL, NULL};
  CFRunLoopTimerRef watchdogTimer = CFRunLoopTimerCreate(
      kCFAllocatorDefault, CFAbsoluteTimeGetCurrent() + 2.0, 2.0, 0, 0,
      watchdog, &ctx);
  CFRunLoopAddTimer(CFRunLoopGetCurrent(), watchdogTimer, kCFRunLoopDefaultMode);
  CFRelease(watchdogTimer);

  pthread_t watcher;
  pthread_create(&watcher, NULL, stdinWatch, NULL);
  pthread_detach(watcher);

  setvbuf(stdout, NULL, _IOLBF, 0);
  fprintf(stderr, "tapd: running, watching %d keycode(s)\n", ruleCount);
  CFRunLoopRun();
  return 0;
}
