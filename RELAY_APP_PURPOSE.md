# GastroPos Relay Purpose and Flow

This document stores the intended purpose and end-to-end flow of the GastroPos Relay app.

## 1) The "Ears": Notification Listening

The app is constantly listening for system notifications.  
When a notification arrives from Grab Merchant or Shopee Partner, the app recognizes the package name.

**Action:** It immediately wakes up and forces the phone to launch the specific merchant app (for example, Grab) so order details are visible on screen.

## 2) The "Eyes": Accessibility Scraping

Once the Grab or Shopee app is in the foreground, the Accessibility Service (the scraper) takes over.

**Action:** It scans the visual UI tree and looks for specific text patterns such as:
- Order ID
- Item names (for example, Nasi Lemak)
- Quantities
- Total price

**Benefit:** This captures order data even when delivery platforms do not provide an official API.

## 3) The "Bridge": Cloud Communication

After scraping order details, the app packages the data into JSON format.

**Action:** It sends the payload via HTTP to the configured Google Cloud endpoint (URL ending with `...a.run.app`).

**Result:** The order is saved into Firestore and can be displayed on the main POS terminal or printed for the kitchen.

