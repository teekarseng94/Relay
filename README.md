# GastroPos Relay (Android)

Android relay app that listens for Grab/Shopee merchant order notifications, opens the merchant app, scrapes the active order UI via Accessibility, and POSTs JSON to FastAPI.

## What is implemented

- `NotificationReceiverService`: listens to notifications from:
  - `com.grab.merchant`
  - `com.shopeepay.merchant.my`
- Filters non-order notifications (promo/voucher/sale).
- Extracts `order_id` from notification text.
- Launches the merchant app with `FLAG_ACTIVITY_NEW_TASK`.
- `OrderScraperService`: recursively crawls `AccessibilityNodeInfo` tree, extracts item rows, qty, prices, order id, total.
- Deduplicates processed orders using in-memory set + SharedPreferences.
- `OrderRelayClient`: sends JSON payload with OkHttp to your FastAPI URL.

## Important setup

1. Replace `YOUR_URL` in `app/src/main/java/com/gastropos/relay/OrderRelayClient.kt`.
2. Install and open the app once.
3. Enable:
   - Notification Access for GastroPos Relay
   - Accessibility Service for GastroPos Relay
4. Keep the app unrestricted in battery optimization.

## Payload shape

```json
{
  "source": "grab|shopee",
  "source_package": "com.grab.merchant",
  "order_id": "ABC12345",
  "total": "Total RM 23.50",
  "scraped_at_epoch_ms": 1710000000000,
  "raw_texts": ["...all detected text nodes..."],
  "items": [
    { "name": "Fried Rice", "quantity": 2, "price": "RM 12.00" }
  ]
}
```
