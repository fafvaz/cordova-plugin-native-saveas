var exec = require('cordova/exec');

var NativeSaveAs = {
  saveBase64: function(base64Data, fileName, mimeType, success, error) {
    exec(success || function(){}, error || function(){}, 'NativeSaveAs', 'saveBase64', [base64Data, fileName, mimeType]);
  }
};

module.exports = NativeSaveAs;
