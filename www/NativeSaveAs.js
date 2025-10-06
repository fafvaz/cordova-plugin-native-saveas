var exec = require('cordova/exec');

var NativeSaveAs = {
    saveBase64: function(base64String, filename, mimeType, successCallback, errorCallback) {
        if (!base64String || !filename || !mimeType) {
            errorCallback('Invalid arguments');
            return;
        }
        exec(successCallback, errorCallback, "NativeSaveAs", "saveBase64", [base64String, filename, mimeType]);
    }
};

module.exports = NativeSaveAs;
