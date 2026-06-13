#!/usr/bin/env ruby
# frozen_string_literal: true

# integrate-share-extension.rb
#
# Adds the `VanceShareExtension` app-extension target to the Capacitor-
# generated `ios/App/App.xcodeproj`. Capacitor doesn't scaffold app
# extensions, so without this every `cap add ios` followed by Xcode
# build would lack the share-sheet entry. Re-running this script after
# regenerating `ios/` rebuilds the target from the committed template
# in `ios-template/VanceShareExtension/`.
#
# Idempotent: detects an existing target with the same name and exits
# cleanly. Re-runs ensure file references point at the (potentially
# updated) template sources but never duplicate targets.

require 'fileutils'

begin
  require 'xcodeproj'
rescue LoadError
  warn "✗ xcodeproj gem not found — it's installed alongside CocoaPods."
  warn "  Run `sudo gem install xcodeproj` or re-install CocoaPods."
  exit 1
end

# Paths are relative to the facelift-bridge package root (the script is
# invoked from there by cap-add-ios.sh).
PROJECT_PATH = 'ios/App/App.xcodeproj'
EXTENSION_NAME = 'VanceShareExtension'
EXTENSION_BUNDLE_ID = 'de.mhus.vance.facelift.shareExtension'
TEMPLATE_DIR = 'ios-template/VanceShareExtension'
# SOURCE_ROOT for App.xcodeproj is `ios/App/` (the directory that
# contains the .xcodeproj), so paths in build settings + group
# references are relative to that. The extension lives as a sibling
# to the App/ source directory, matching Xcode-UI conventions when
# you add a "Share Extension" target by hand.
EXTENSION_PATH_IN_SOURCE_ROOT = EXTENSION_NAME      # ⇒ ios/App/<name>
EXTENSION_DIR_FULL = "ios/App/#{EXTENSION_NAME}"
APP_TARGET_NAME = 'App'

abort "✗ #{PROJECT_PATH} not found — run `cap add ios` first." unless File.exist?(PROJECT_PATH)
abort "✗ #{TEMPLATE_DIR} not found — committed extension template missing." unless File.directory?(TEMPLATE_DIR)

project = Xcodeproj::Project.open(PROJECT_PATH)

# If an earlier run of this script left behind a target / group /
# embed-phase entry with the same name, remove them first so a
# re-run produces a clean project. Idempotency-by-replace rather
# than idempotency-by-skip — keeps the integration robust against
# template + script edits between runs.
def remove_existing(project, ext_name, app_target_name)
  app_target = project.targets.find { |t| t.name == app_target_name }
  if app_target
    app_target.copy_files_build_phases.each do |phase|
      phase.files.dup.each do |bf|
        if bf.file_ref && bf.file_ref.path&.end_with?("#{ext_name}.appex")
          phase.remove_build_file(bf)
        end
      end
    end
    app_target.dependencies.dup.each do |dep|
      dep.remove_from_project if dep.target&.name == ext_name
    end
  end
  project.targets.dup.each do |t|
    next unless t.name == ext_name

    # remove_from_project tears down the target's build configs +
    # build phases + product reference along with the target itself
    # — `project.targets.delete` only unlinks the array entry and
    # leaves orphaned build-settings in the file.
    t.remove_from_project
  end
  project.main_group.recursive_children.each do |child|
    next unless child.is_a?(Xcodeproj::Project::Object::PBXGroup)
    next unless child.name == ext_name

    child.recursive_children.each do |grandchild|
      grandchild.remove_from_project if grandchild.respond_to?(:remove_from_project)
    end
    child.remove_from_project
  end
end

remove_existing(project, EXTENSION_NAME, APP_TARGET_NAME)

# ── 1. Copy extension sources into the iOS project tree ──────────────
FileUtils.mkdir_p(EXTENSION_DIR_FULL)
%w[
  ShareViewController.swift
  MainInterface.storyboard
  Info.plist
  VanceShareExtension.entitlements
].each do |file|
  src = File.join(TEMPLATE_DIR, file)
  dst = File.join(EXTENSION_DIR_FULL, file)
  FileUtils.cp(src, dst)
end

# ── 2. Create the app-extension target ───────────────────────────────
ext_target = project.new_target(:app_extension, EXTENSION_NAME, :ios, '17.0')

ext_target.build_configurations.each do |config|
  config.build_settings.merge!(
    'PRODUCT_BUNDLE_IDENTIFIER' => EXTENSION_BUNDLE_ID,
    'INFOPLIST_FILE' => "#{EXTENSION_PATH_IN_SOURCE_ROOT}/Info.plist",
    'CODE_SIGN_ENTITLEMENTS' => "#{EXTENSION_PATH_IN_SOURCE_ROOT}/VanceShareExtension.entitlements",
    'SWIFT_VERSION' => '5.0',
    'IPHONEOS_DEPLOYMENT_TARGET' => '17.0',
    'CODE_SIGN_STYLE' => 'Automatic',
    'TARGETED_DEVICE_FAMILY' => '1,2',
    'PRODUCT_NAME' => '$(TARGET_NAME)',
    'LD_RUNPATH_SEARCH_PATHS' => [
      '$(inherited)',
      '@executable_path/Frameworks',
      '@executable_path/../../Frameworks'
    ],
    'SKIP_INSTALL' => 'YES',
    # iOS expects extensions to opt into ARC + Swift module linkage —
    # Xcode sets these automatically when adding a target via the UI;
    # mirror them here so the produced pbxproj matches.
    'ALWAYS_EMBED_SWIFT_STANDARD_LIBRARIES' => 'NO'
  )
end

# ── 3. File references + build phases ───────────────────────────────
# The extension's source group lives at the project root, sibling to
# the App group (mirroring the on-disk layout under ios/App/).
ext_group = project.main_group.new_group(EXTENSION_NAME, EXTENSION_NAME, '<group>')

swift_ref = ext_group.new_reference('ShareViewController.swift')
storyboard_ref = ext_group.new_reference('MainInterface.storyboard')
plist_ref = ext_group.new_reference('Info.plist')
entitlements_ref = ext_group.new_reference('VanceShareExtension.entitlements')

# Sources phase = .swift; Resources phase = .storyboard. plist +
# entitlements are referenced via INFOPLIST_FILE / CODE_SIGN_ENTITLEMENTS
# build settings, not as compiled inputs.
ext_target.source_build_phase.add_file_reference(swift_ref)
ext_target.resources_build_phase.add_file_reference(storyboard_ref)
_ = plist_ref # keep references attached to the group for Xcode visibility
_ = entitlements_ref

# ── 4. Embed the extension in the App target's bundle ──────────────
app_target = project.targets.find { |t| t.name == APP_TARGET_NAME }
abort "✗ '#{APP_TARGET_NAME}' target not found in #{PROJECT_PATH}." unless app_target

app_target.add_dependency(ext_target)

embed_phase = app_target.copy_files_build_phases.find do |phase|
  phase.symbol_dst_subfolder_spec == :plug_ins
end
unless embed_phase
  embed_phase = app_target.new_copy_files_build_phase('Embed App Extensions')
  embed_phase.symbol_dst_subfolder_spec = :plug_ins
end
build_file = embed_phase.add_file_reference(ext_target.product_reference)
build_file.settings = { 'ATTRIBUTES' => ['RemoveHeadersOnCopy'] }

project.save

puts "✓ #{EXTENSION_NAME} target integrated into #{PROJECT_PATH}"
puts "  Bundle ID: #{EXTENSION_BUNDLE_ID}"
puts "  Files:     #{EXTENSION_DIR_FULL}/*"
puts ""
puts "Next steps in Xcode (one-time per fresh ios/):"
puts "  1. Open ios/App/App.xcworkspace"
puts "  2. App target  → Signing & Capabilities → + Capability → App Groups"
puts "     add group.de.mhus.vance.facelift"
puts "  3. VanceShareExtension target → Signing & Capabilities → + Capability →"
puts "     App Groups, tick the same group.de.mhus.vance.facelift"
puts "  4. ⌘B then ⌘R."
