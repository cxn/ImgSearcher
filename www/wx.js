module.exports = {

       init: function (success, failure) {
            cordova.exec(success, failure, "ImgSearcherAction", "init", []);
        },

    start: function (img,success, failure) {
            cordova.exec(success, failure, "ImgSearcherAction", "start", [img]);
        },

    cancel: function (success, failure) {
            cordova.exec(success, failure, "ImgSearcherAction", "cancel", []);
        },

    destroy: function (success, failure) {
            cordova.exec(success, failure, "ImgSearcherAction", "destroy", []);
        }

};
