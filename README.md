# cordova-plugin-native-saveas

This Cordova plugin exposes `saveBase64(base64, filename, mimeType)` and provides a native Save As flow with native progress UI on Android and iOS.

## Features
- Android: ACTION_CREATE_DOCUMENT Save As dialog + native progress dialog while writing.
- iOS: modal native progress UI while writing temp file, then present UIDocumentPicker export UI.
- Returns a single success (destination URI) or error to JS.

## Usage (JS)
```javascript
cordova.plugins.NativeSaveAs.saveBase64(base64String, "invoice.pdf", "application/pdf",
  function(resultUri) { console.log('Saved to: ' + resultUri); },
  function(err) { console.error('Save failed:', err); }
);
```

## Installation
Upload the plugin folder (or ZIP) to OutSystems Mobile Apps Cordova Plugins or add to your Cordova project.
