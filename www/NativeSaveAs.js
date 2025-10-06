var exec = require('cordova/exec');

var NativeSaveAs = {
    /**
     * Save a base64 encoded file using native Save As dialog
     * @param {string} base64String - The base64 encoded file content
     * @param {string} filename - The suggested filename
     * @param {string} mimeType - The MIME type (e.g., 'application/pdf', 'image/png')
     * @param {function} successCallback - Called with the destination URI on success
     * @param {function} errorCallback - Called with error message on failure
     */
    saveBase64: function(base64String, filename, mimeType, successCallback, errorCallback) {
        if (!base64String || typeof base64String !== 'string') {
            errorCallback('Invalid base64 string');
            return;
        }
        if (!filename || typeof filename !== 'string') {
            errorCallback('Invalid filename');
            return;
        }
        if (!mimeType || typeof mimeType !== 'string') {
            errorCallback('Invalid MIME type');
            return;
        }

        exec(successCallback, errorCallback, "NativeSaveAs", "saveBase64", [base64String, filename, mimeType]);
    }
};

module.exports = NativeSaveAs;