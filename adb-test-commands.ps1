$adb = "C:\Users\Acer\AppData\Local\Android\Sdk\platform-tools\adb.exe"

# Verify device connection
& $adb devices

# Clear logcat before tests
& $adb logcat -c

# Stream relevant relay logs (run in a separate terminal if preferred)
& $adb logcat -v time | findstr /I "NotificationReceiver OrderScraper OrderRelayClient MainActivity AndroidRuntime"

# -------------------------
# Test 1: Basic test trigger (opens Grab Merchant)
# -------------------------
# & $adb shell am broadcast -a com.gastropos.relay.TEST_NOTIFICATION -p com.gastropos.relay

# -------------------------
# Test 2: Keyword routing -> Shopee (working escaped payload)
# -------------------------
# & $adb shell am broadcast -a com.gastropos.relay.TEST_KEYWORD -p com.gastropos.relay --es text new\ order\ \#9988

# -------------------------
# Test 3: Keyword routing -> Grab
# -------------------------
# & $adb shell am broadcast -a com.gastropos.relay.TEST_KEYWORD -p com.gastropos.relay --es text GF

