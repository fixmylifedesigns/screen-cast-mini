# Screen Cast Mini

Streams a draggable crop of your phone screen to the cheap magnetic BLE "selfie monitor"
(Bluetrum firmware). Built for glancing at Google Maps directions on a small second screen.

## How it works
- MediaProjection mirrors the display into an ImageReader at half resolution.
- A floating overlay box marks the region to send; LOCK makes it pass touches through.
- The monitor pulls frames (`AA 55 03`); the service replies with a JPEG sized to fit
  the device's buffer, same protocol as the Selfie Screen app.

## Install
Grab the APK from Releases. Updates are offered in-app from the newest release.

## Expectations
BLE bandwidth caps this at a few frames per second. Good for directions, ETA, and
status displays; not for video.
