/**
 * Tiny webpack loader: export file contents as a JS string module.
 * Used to bundle config.html into a data URI for offline Pebble.openURL.
 */
module.exports = function (content) {
  if (this.cacheable) {
    this.cacheable();
  }
  return 'module.exports = ' + JSON.stringify(content);
};
