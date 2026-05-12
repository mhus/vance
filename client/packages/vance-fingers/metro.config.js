const path = require('node:path');
const { getDefaultConfig } = require('expo/metro-config');
const { withNativeWind } = require('nativewind/metro');

// pnpm + Expo monorepo glue. Without these tweaks Metro cannot
// resolve workspace siblings (`@vance/shared`, `@vance/generated`)
// because pnpm hoists them under the workspace root, not under the
// app's local `node_modules`.
const projectRoot = __dirname;
const workspaceRoot = path.resolve(projectRoot, '../..');

const config = getDefaultConfig(projectRoot);

config.watchFolders = [workspaceRoot];
config.resolver.nodeModulesPaths = [
  path.resolve(projectRoot, 'node_modules'),
  path.resolve(workspaceRoot, 'node_modules'),
];
// pnpm's symlinked workspace packages live outside the app's tree;
// disabling the hierarchical lookup forces Metro to use the explicit
// paths above and avoids accidental resolution into a different
// version of a transitive dep.
config.resolver.disableHierarchicalLookup = true;

module.exports = withNativeWind(config, { input: './global.css' });
