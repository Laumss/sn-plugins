const {getDefaultConfig, mergeConfig} = require('@react-native/metro-config');

const config = {

  cacheVersion: 'console-kept',
};

module.exports = mergeConfig(getDefaultConfig(__dirname), config);
