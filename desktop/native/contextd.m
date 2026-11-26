#import <Cocoa/Cocoa.h>
#import <ApplicationServices/ApplicationServices.h>
#include <unistd.h>

// Private, ephemeral editor snapshot. Only bounded snippets leave this process,
// and only for an explicit nearby-text capture. No files, screen reads, or logs.
static const NSUInteger MAX_FULL_VALUE_LEN = 200000;

static AXUIElementRef target = NULL;
static AXUIElementRef windowRef = NULL;
static NSString *ticket = nil;
static pid_t owner = 0;
static CFRange selection;
static NSString *originalValue = nil;
static NSString *originalSlice = nil;
static CFRange originalSliceRange;
static NSUInteger originalLength = 0;
static BOOL hasValue = NO;
static BOOL boundedValidation = NO;
static BOOL fallbackMode = NO;
static BOOL skipValueCheck = NO;

static id attribute(AXUIElementRef element, CFStringRef name) {
    CFTypeRef value = NULL;
    if (!element || AXUIElementCopyAttributeValue(element, name, &value) != kAXErrorSuccess) return nil;
    return CFBridgingRelease(value);
}

static NSString *stringAttribute(AXUIElementRef element, CFStringRef name) {
    id value = attribute(element, name);
    return [value isKindOfClass:NSString.class] ? (NSString *)value : nil;
}

static NSString *jsonString(NSDictionary *dict, NSString *key) {
    id value = dict[key];
    return [value isKindOfClass:NSString.class] ? (NSString *)value : nil;
}

static void clearTarget(void) {
    if (target) CFRelease(target);
    if (windowRef) CFRelease(windowRef);
    target = NULL;
    windowRef = NULL;
    ticket = nil;
    owner = 0;
    hasValue = NO;
    boundedValidation = NO;
    fallbackMode = NO;
    skipValueCheck = NO;
    originalValue = nil;
    originalSlice = nil;
    originalLength = 0;
    selection = CFRangeMake(0, 0);
    originalSliceRange = CFRangeMake(0, 0);
}

// Query the focused application directly through Accessibility instead of
// NSWorkspace.frontmostApplication, which stays stale without a running RunLoop.
static pid_t frontmostPid(void) {
    AXUIElementRef systemWide = AXUIElementCreateSystemWide();
    AXUIElementSetMessagingTimeout(systemWide, 0.15);
    AXUIElementRef appElement = NULL;
    AXError err = AXUIElementCopyAttributeValue(
        systemWide, kAXFocusedApplicationAttribute, (CFTypeRef *)&appElement);
    CFRelease(systemWide);
    if (err != kAXErrorSuccess || !appElement) return 0;
    pid_t pid = 0;
    AXError pidErr = AXUIElementGetPid(appElement, &pid);
    CFRelease(appElement);
    return pidErr == kAXErrorSuccess ? pid : 0;
}

static AXUIElementRef focusedElementForPid(pid_t pid) {
    AXUIElementRef app = AXUIElementCreateApplication(pid);
    AXUIElementSetMessagingTimeout(app, 0.15);
    AXUIElementRef element = NULL;
    AXUIElementCopyAttributeValue(app, kAXFocusedUIElementAttribute, (CFTypeRef *)&element);
    CFRelease(app);
    if (element) AXUIElementSetMessagingTimeout(element, 0.15);
    return element;
}

static id focusedWindowForPid(pid_t pid) {
    AXUIElementRef app = AXUIElementCreateApplication(pid);
    AXUIElementSetMessagingTimeout(app, 0.15);
    id win = attribute(app, kAXFocusedWindowAttribute);
    CFRelease(app);
    return win;
}

static BOOL rangeOf(AXUIElementRef element, CFRange *range) {
    id value = attribute(element, kAXSelectedTextRangeAttribute);
    return value && CFGetTypeID((__bridge CFTypeRef)value) == AXValueGetTypeID() &&
        AXValueGetType((__bridge AXValueRef)value) == kAXValueCFRangeType &&
        AXValueGetValue((__bridge AXValueRef)value, kAXValueCFRangeType, range) &&
        range->location >= 0 && range->length >= 0;
}

static NSString *stringForRange(AXUIElementRef element, CFRange range) {
    if (!element || range.location < 0 || range.length < 0) return nil;
    AXValueRef rangeVal = AXValueCreate(kAXValueCFRangeType, &range);
    if (!rangeVal) return nil;
    CFTypeRef out = NULL;
    AXError err = AXUIElementCopyParameterizedAttributeValue(
        element, kAXStringForRangeParameterizedAttribute, rangeVal, &out);
    CFRelease(rangeVal);
    if (err != kAXErrorSuccess || !out) return nil;
    id bridged = CFBridgingRelease(out);
    return [bridged isKindOfClass:NSString.class] ? (NSString *)bridged : nil;
}

static BOOL isSecureElement(AXUIElementRef element) {
    if (!element) return NO;
    NSString *role = stringAttribute(element, kAXRoleAttribute);
    NSString *subrole = stringAttribute(element, kAXSubroleAttribute);
    return [subrole isEqualToString:@"AXSecureTextField"] || [role isEqualToString:@"AXSecureTextField"];
}

static BOOL safeTextElement(AXUIElementRef element) {
    if (!element || isSecureElement(element)) return NO;
    NSString *role = stringAttribute(element, kAXRoleAttribute);
    return [role isEqualToString:@"AXTextField"] ||
        [role isEqualToString:@"AXTextArea"] ||
        [role isEqualToString:@"AXComboBox"];
}

static BOOL isTerminalBundle(NSString *bundleId) {
    return [bundleId isEqualToString:@"com.apple.Terminal"] ||
        [bundleId isEqualToString:@"com.googlecode.iterm2"] ||
        [bundleId isEqualToString:@"dev.warp.Warp-Stable"] ||
        [bundleId isEqualToString:@"net.kovidgoyal.kitty"] ||
        [bundleId isEqualToString:@"org.alacritty"];
}

static NSDictionary *capture(NSString *level) {
    clearTarget();
    if (!AXIsProcessTrusted()) return @{@"available": @NO, @"reason": @"accessibility"};

    pid_t pid = frontmostPid();
    if (pid <= 0 || pid == getpid() || pid == getppid()) {
        return @{@"available": @NO, @"reason": @"editor"};
    }

    NSRunningApplication *app = [NSRunningApplication runningApplicationWithProcessIdentifier:pid];
    NSString *appId = (app && [app.bundleIdentifier isKindOfClass:NSString.class]) ? app.bundleIdentifier : @"";
    NSString *effectiveLevel = [level isKindOfClass:NSString.class] ? level : @"off";

    AXUIElementRef element = focusedElementForPid(pid);
    // Password / secure fields are ALWAYS excluded regardless of context level.
    if (element && isSecureElement(element)) {
        CFRelease(element);
        return @{@"available": @NO, @"reason": @"secure"};
    }

    CFRange range = CFRangeMake(0, 0);
    BOOL isStandardText = element && safeTextElement(element) && rangeOf(element, &range);

    if (isStandardText) {
        NSNumber *charCountObj = attribute(element, kAXNumberOfCharactersAttribute);
        BOOL hasCharCount = [charCountObj isKindOfClass:NSNumber.class];
        NSUInteger charCount = hasCharCount ? charCountObj.unsignedIntegerValue : 0;

        NSString *prefix = nil;
        NSString *suffix = nil;
        BOOL textResolved = NO;

        if (hasCharCount && charCount > MAX_FULL_VALUE_LEN) {
            NSUInteger start = (NSUInteger)range.location;
            NSUInteger end = start + (NSUInteger)range.length;
            if (start <= charCount && end <= charCount) {
                NSUInteger left = start > 512 ? start - 512 : 0;
                NSUInteger right = end + MIN(charCount - end, (NSUInteger)128);
                CFRange sliceRange = CFRangeMake((CFIndex)left, (CFIndex)(right - left));
                NSString *slice = stringForRange(element, sliceRange);
                if (slice != nil) {
                    NSUInteger relStart = start - left;
                    NSUInteger relEnd = MIN(slice.length, end - left);
                    if (relStart <= slice.length) {
                        prefix = [slice substringWithRange:NSMakeRange(0, relStart)];
                        suffix = [slice substringWithRange:NSMakeRange(relEnd, slice.length - relEnd)];
                        originalSlice = [slice copy];
                        originalSliceRange = sliceRange;
                        originalLength = charCount;
                        boundedValidation = YES;
                        hasValue = YES;
                        textResolved = YES;
                    }
                }
            }
        } else {
            NSString *value = stringAttribute(element, kAXValueAttribute);
            if (value != nil &&
                (NSUInteger)range.location <= value.length &&
                (NSUInteger)range.length <= value.length - (NSUInteger)range.location) {
                NSUInteger start = (NSUInteger)range.location;
                NSUInteger end = start + (NSUInteger)range.length;
                NSUInteger left = start > 512 ? start - 512 : 0;
                if (left < start && CFStringIsSurrogateLowCharacter([value characterAtIndex:left])) left++;
                NSUInteger right = end + MIN(value.length - end, (NSUInteger)128);
                if (right > end && CFStringIsSurrogateHighCharacter([value characterAtIndex:right - 1])) right--;
                prefix = [value substringWithRange:NSMakeRange(left, start - left)];
                suffix = [value substringWithRange:NSMakeRange(end, right - end)];

                if (value.length > MAX_FULL_VALUE_LEN) {
                    CFRange sliceRange = CFRangeMake((CFIndex)left, (CFIndex)(right - left));
                    originalSlice = [[value substringWithRange:NSMakeRange(left, right - left)] copy];
                    originalSliceRange = sliceRange;
                    originalLength = value.length;
                    boundedValidation = YES;
                } else {
                    originalValue = [value copy];
                    boundedValidation = NO;
                }
                hasValue = YES;
                textResolved = YES;
            }
        }

        if (textResolved) {
            target = element;
            owner = pid;
            selection = range;
            fallbackMode = NO;
            skipValueCheck = isTerminalBundle(appId);
            id focusedWindow = attribute(element, kAXWindowAttribute) ?: focusedWindowForPid(pid);
            if (focusedWindow) windowRef = (AXUIElementRef)CFRetain((__bridge CFTypeRef)focusedWindow);
            ticket = NSUUID.UUID.UUIDString;

            NSMutableDictionary *result = [@{
                @"available": @YES,
                @"fallback": @NO,
                @"token": ticket,
                @"appId": appId,
                @"field": skipValueCheck ? @"code" : @"text"
            } mutableCopy];

            if (![effectiveLevel isEqualToString:@"off"]) {
                NSString *hint = stringAttribute(element, kAXPlaceholderValueAttribute);
                if (hint != nil) result[@"hint"] = [hint substringToIndex:MIN(hint.length, (NSUInteger)256)];
                NSString *subrole = stringAttribute(element, kAXSubroleAttribute);
                if ([subrole isEqualToString:@"AXSearchField"]) result[@"field"] = @"search";
            }

            NSString *trimmed = [prefix stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
            BOOL isStart = (!trimmed.length ||
                [@".!?" rangeOfString:[trimmed substringFromIndex:trimmed.length - 1]].location != NSNotFound ||
                [prefix hasSuffix:@"\n"]);
            result[@"sentenceStart"] = isStart ? @YES : @NO;
            if ([effectiveLevel isEqualToString:@"nearby"]) {
                result[@"before"] = prefix ?: @"";
                result[@"after"] = suffix ?: @"";
            }
            return result;
        }
    }

    // Fallback target for non-AX / Electron / Chromium / Web / Terminal editors
    // that do not expose kAXValueAttribute or kAXSelectedTextRangeAttribute.
    // Password fields were already rejected above and are checked again in validate().
    target = element; // retained if non-NULL, owned by clearTarget()
    owner = pid;
    fallbackMode = YES;
    id focusedWindow = (element ? attribute(element, kAXWindowAttribute) : nil) ?: focusedWindowForPid(pid);
    if (focusedWindow) windowRef = (AXUIElementRef)CFRetain((__bridge CFTypeRef)focusedWindow);
    ticket = NSUUID.UUID.UUIDString;

    NSMutableDictionary *fallbackResult = [@{
        @"available": @YES,
        @"fallback": @YES,
        @"token": ticket,
        @"appId": appId,
        @"field": isTerminalBundle(appId) ? @"code" : @"text",
        @"sentenceStart": @YES
    } mutableCopy];

    if (![effectiveLevel isEqualToString:@"off"] && element != NULL) {
        NSString *hint = stringAttribute(element, kAXPlaceholderValueAttribute);
        if (hint != nil) fallbackResult[@"hint"] = [hint substringToIndex:MIN(hint.length, (NSUInteger)256)];
        NSString *subrole = stringAttribute(element, kAXSubroleAttribute);
        if ([subrole isEqualToString:@"AXSearchField"]) fallbackResult[@"field"] = @"search";
    }
    return fallbackResult;
}

static BOOL validate(NSString *token) {
    if (![token isKindOfClass:NSString.class] || ticket == nil || ![ticket isEqualToString:token]) return NO;
    if (owner <= 0 || frontmostPid() != owner) return NO;

    AXUIElementRef current = focusedElementForPid(owner);
    if (current && isSecureElement(current)) {
        CFRelease(current);
        return NO;
    }

    if (fallbackMode) {
        BOOL valid = YES;
        if (windowRef) {
            id currentWindow = (current ? attribute(current, kAXWindowAttribute) : nil) ?: focusedWindowForPid(owner);
            valid = currentWindow && CFEqual((__bridge CFTypeRef)currentWindow, windowRef);
        }
        if (current) CFRelease(current);
        return valid;
    }

    if (!target || !current) {
        if (current) CFRelease(current);
        return NO;
    }

    BOOL valid = CFEqual(current, target) && safeTextElement(current);
    if (valid && windowRef) {
        id currentWindow = attribute(current, kAXWindowAttribute) ?: focusedWindowForPid(owner);
        valid = currentWindow && CFEqual((__bridge CFTypeRef)currentWindow, windowRef);
    }
    if (valid && !skipValueCheck) {
        CFRange range = CFRangeMake(0, 0);
        valid = rangeOf(current, &range) && range.location == selection.location && range.length == selection.length;
        if (valid && hasValue) {
            if (boundedValidation) {
                NSNumber *charCountObj = attribute(current, kAXNumberOfCharactersAttribute);
                if ([charCountObj isKindOfClass:NSNumber.class]) {
                    valid = charCountObj.unsignedIntegerValue == originalLength;
                    if (valid && originalSlice != nil) {
                        NSString *currentSlice = stringForRange(current, originalSliceRange);
                        if (currentSlice != nil) {
                            valid = [currentSlice isEqualToString:originalSlice];
                        }
                    }
                } else {
                    NSString *value = stringAttribute(current, kAXValueAttribute);
                    valid = value != nil && value.length == originalLength;
                    if (valid && originalSlice != nil &&
                        (NSUInteger)(originalSliceRange.location + originalSliceRange.length) <= value.length) {
                        NSString *slice = [value substringWithRange:NSMakeRange(
                            (NSUInteger)originalSliceRange.location, (NSUInteger)originalSliceRange.length)];
                        valid = [slice isEqualToString:originalSlice];
                    }
                }
            } else {
                NSString *value = stringAttribute(current, kAXValueAttribute);
                valid = value != nil && [value isEqualToString:originalValue];
            }
        }
    }

    CFRelease(current);
    return valid;
}

static NSDictionary *performPaste(void) {
    if (!AXIsProcessTrusted()) return @{@"ok": @NO, @"reason": @"accessibility"};
    // Private event source prevents physical modifier keys (e.g. Option just
    // released) from polluting our synthetic Cmd+V event flags.
    CGEventSourceRef source = CGEventSourceCreate(kCGEventSourceStatePrivate);
    CGEventRef down = CGEventCreateKeyboardEvent(source, (CGKeyCode)9 /* kVK_ANSI_V */, true);
    CGEventRef up = CGEventCreateKeyboardEvent(source, (CGKeyCode)9 /* kVK_ANSI_V */, false);
    if (!down || !up) {
        if (down) CFRelease(down);
        if (up) CFRelease(up);
        if (source) CFRelease(source);
        return @{@"ok": @NO, @"reason": @"event"};
    }
    CGEventSetFlags(down, kCGEventFlagMaskCommand);
    CGEventSetFlags(up, kCGEventFlagMaskCommand);
    CGEventPost(kCGHIDEventTap, down);
    usleep(8000);
    CGEventPost(kCGHIDEventTap, up);
    CFRelease(down);
    CFRelease(up);
    if (source) CFRelease(source);
    return @{@"ok": @YES};
}

int main(void) {
    @autoreleasepool {
        char *line = NULL;
        size_t capacity = 0;
        ssize_t len = 0;
        while ((len = getline(&line, &capacity, stdin)) > 0) {
            @autoreleasepool {
                if (len > 4096) continue;
                NSString *lineStr = [[NSString alloc] initWithBytes:line
                                                             length:(NSUInteger)len
                                                           encoding:NSUTF8StringEncoding];
                NSData *data = lineStr ? [lineStr dataUsingEncoding:NSUTF8StringEncoding] : nil;
                NSDictionary *request = (data != nil && data.length > 0 && data.length <= 4096)
                    ? [NSJSONSerialization JSONObjectWithData:data options:0 error:NULL]
                    : nil;
                if (![request isKindOfClass:NSDictionary.class]) continue;
                NSString *command = jsonString(request, @"command");
                if (command == nil) continue;
                NSDictionary *result = nil;
                if ([command isEqualToString:@"capture"]) {
                    result = capture(jsonString(request, @"level"));
                } else if ([command isEqualToString:@"validate"]) {
                    result = @{@"valid": validate(jsonString(request, @"token")) ? @YES : @NO};
                } else if ([command isEqualToString:@"paste"]) {
                    result = performPaste();
                } else {
                    clearTarget();
                    result = @{};
                }
                id reqId = request[@"id"];
                if (![reqId isKindOfClass:NSNumber.class] && ![reqId isKindOfClass:NSString.class]) {
                    reqId = @0;
                }
                NSData *out = [NSJSONSerialization dataWithJSONObject:@{@"id": reqId, @"result": result}
                                                              options:0
                                                                error:NULL];
                if (out) {
                    fwrite(out.bytes, 1, out.length, stdout);
                    fputc('\n', stdout);
                    fflush(stdout);
                }
            }
        }
        free(line);
        clearTarget();
    }
    return 0;
}
