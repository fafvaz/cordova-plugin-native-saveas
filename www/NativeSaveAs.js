var exec = require('cordova/exec');

var NativeSaveAs = {
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