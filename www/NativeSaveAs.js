var exec = require('cordova/exec');

/**
 * NativeSaveAs Plugin
 * Provides native Save As dialog functionality for Android and iOS
 */
var NativeSaveAs = {
    /**
     * Save a base64 encoded file using native Save As dialog
     * 
     * @param {string} base64String - The base64 encoded file content (without data URI prefix)
     * @param {string} filename - The suggested filename with extension
     * @param {string} mimeType - The MIME type (e.g., 'application/pdf', 'image/png')
     * @param {function} successCallback - Called with the destination URI on success
     * @param {function} errorCallback - Called with error message on failure
     * 
     * @example
     * cordova.plugins.NativeSaveAs.saveBase64(
     *   base64String,
     *   'document.pdf',
     *   'application/pdf',
     *   function(uri) { console.log('Saved to:', uri); },
     *   function(error) { console.error('Error:', error); }
     * );
     */
    saveBase64: function(base64String, filename, mimeType, successCallback, errorCallback) {
        // Validate inputs
        if (!base64String || typeof base64String !== 'string') {
            if (errorCallback) {
                errorCallback('Invalid base64 string: must be a non-empty string');
            }
            return;
        }
        
        if (!filename || typeof filename !== 'string') {
            if (errorCallback) {
                errorCallback('Invalid filename: must be a non-empty string');
            }
            return;
        }
        
        if (!mimeType || typeof mimeType !== 'string') {
            if (errorCallback) {
                errorCallback('Invalid MIME type: must be a non-empty string');
            }
            return;
        }

        // Remove data URI prefix if present
        var cleanBase64 = base64String;
        if (base64String.indexOf(',') !== -1) {
            cleanBase64 = base64String.split(',')[1];
        }

        // Execute native plugin
        exec(
            successCallback,
            errorCallback,
            "NativeSaveAs",
            "saveBase64",
            [cleanBase64, filename, mimeType]
        );
    }
};

module.exports = NativeSaveAs;